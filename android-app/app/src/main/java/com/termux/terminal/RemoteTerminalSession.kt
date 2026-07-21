package com.termux.terminal

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程终端会话：复用 termux 的 [TerminalSession] + [TerminalEmulator] 做 xterm-256color
 * 终端仿真，但底层 I/O 不是本地子进程 pty，而是 WebSocket 字节流。
 *
 * 设计说明（依据 termux-app v0.118.1 源码）：
 *  - TerminalSession / TerminalView 都是 `final class`，无法继承；且
 *    TerminalView.attachSession() 只接受 TerminalSession 实例。因此本类放在与
 *    TerminalSession 相同的 package 下，利用包级可见性直接操控其内部状态。
 *  - TerminalSession 构造器只保存参数，不 fork 进程；fork 发生在
 *    initializeEmulator() 内，而它仅由 updateSize() 在 mEmulator == null 时触发。
 *  - 因此 init 时预注入 mEmulator：之后 TerminalView 调 updateSize() 只会走
 *    `JNI.setPtyWindowSize(fd=0, ...) + mEmulator.resize()` 分支——对默认 fd 0
 *    的 ioctl 无害返回，绝不调用 JNI.createSubprocess()，没有本地进程和 I/O 线程。
 *  - 置 mShellPid = 1，使 session.write() 把字节投入 mTerminalToProcessIOQueue
 *    （该 pid 不会被使用——我们从不调用 finishIfRunning()）。
 *  - 抽干线程把队列里的全部终端输入（IME 字符、硬件按键、KeyHandler 特殊键序列、
 *    粘贴、鼠标上报——它们最终都汇入 session.write()）经 WebSocket binary 帧发出。
 *  - 服务器 binary 帧在主线程 mEmulator.append() + client.onTextChanged()，
 *    与原生 MainThreadHandler 的 MSG_NEW_INPUT 刷新路径一致。
 *
 * 断线自动重连：
 *  - 非主动关闭的断线（onClosed / onFailure 且非 intentional）不进入终态，而是按
 *    指数退避（1s/2s/4s…上限 30s）自动重建 WebSocket。服务端把会话包在 tmux 里，
 *    重连后 attach 回同一会话即恢复原画面。
 *  - 例外：服务端回了 HTTP 错误（如 401 token 错误、400 app 非法）说明重连无意义，
 *    直接进 FAILED 终态；收到 exit/error 控制帧同样进终态，不重连。
 *  - 用户手动关标签/退出 App 时走 destroy()（intentional + destroyed），绝不重连。
 */
class RemoteTerminalSession(
    private val host: String,
    private val port: Int,
    private val app: String,
    private val tmuxSession: String?, // 任何 app 都可以有值：服务端对所有 app 的 session 参数都包 tmux 命名会话
    private val token: String,
    private val client: TerminalSessionClient,
    private val listener: Listener,
) {

    enum class State { CONNECTING, OPEN, EXITED, CLOSED, FAILED }

    interface Listener {
        /** 始终在 UI 线程回调。EXITED/CLOSED/FAILED 只会上报一次。 */
        fun onStateChanged(session: RemoteTerminalSession, state: State, detail: String?)

        /** 断线自动重连：每次重连尝试发起前回调（UI 线程），attempt 从 1 开始。 */
        fun onReconnecting(session: RemoteTerminalSession, attempt: Int) {}

        /** 服务端 watch ~/phone-push/ 后广播的文件推送（UI 线程）。 */
        fun onFilePushed(session: RemoteTerminalSession, name: String, size: Long) {}
    }

    /** attach 到 TerminalView 的会话对象（构造器不 fork 任何进程）。 */
    val session: TerminalSession =
        TerminalSession("/system/bin/sh", "/", emptyArray(), emptyArray(), null, client)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // WebSocket 长连接，禁用读超时
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var open = false

    /** 主动关闭（用户退出/重连，或收到 exit/error 帧）后置位，onClosed/onFailure 不再当作异常上报，也不触发自动重连。 */
    private val intentional = AtomicBoolean(false)

    /** 终态（EXITED/CLOSED/FAILED）只通知一次。 */
    private val notified = AtomicBoolean(false)

    private val destroyed = AtomicBoolean(false)

    /** 最近一次已知的终端尺寸，连接时放进 URL query，之后变化时发 resize 控制帧。 */
    @Volatile
    private var lastCols = 80

    @Volatile
    private var lastRows = 24

    /** 自动重连已尝试次数，连接成功（onOpen）后归零。 */
    private var reconnectAttempt = 0

    /** 退避到点后的重连动作；destroy/主动 close 时移除。 */
    private val reconnectRunnable = Runnable {
        if (!destroyed.get() && !intentional.get() && webSocket == null) {
            doConnect()
        }
    }

    /**
     * resize 去抖（300ms）：软键盘弹起/收起动画、IME 候选栏高度变化会让行数快速抖动，
     * 每次抖动都发 resize 会让 tmux 反复重排重绘整个 pane（实测 kimi 界面以多种尺寸
     * 叠印）。尺寸变化后 300ms 内无更新才发；期间有新变化则重置计时。
     */
    private val resizeDebounceRunnable = Runnable {
        if (open && !destroyed.get()) {
            webSocket?.send(
                JSONObject().put("type", "resize").put("cols", lastCols).put("rows", lastRows).toString()
            )
        }
    }

    /** 抽干线程在断线期间挂起的等待锁；onOpen/close/destroy 时 notifyAll 唤醒。 */
    private val openLock = Object()

    val isOpen: Boolean
        get() = open

    init {
        // 预注入 emulator，使 updateSize() 永远走 resize 分支，绝不 fork 本地进程
        session.mEmulator = TerminalEmulator(session, lastCols, lastRows, null, client)

        // mShellPid > 0 时 TerminalSession.write() 才会把字节写入输入队列
        session.mShellPid = 1

        // 抽干输入队列 → WebSocket binary 帧
        Thread({
            val buffer = ByteArray(4096)
            while (true) {
                // 断线/未连接时绝不读队列：读出来就只能丢（open=false 时没有可发的连接），
                // 丢弃会把一个转义序列拦腰截断——实测抓到终端 DA 应答 ESC[>41;320;0c 在
                // 重连窗口被截成 "41;320;0c" 文本进了 bash 输入行。这里阻塞等待连接恢复，
                // 字节完整留在队列里；首次 connect 前积压（如 TerminalEmulator 的 DA 应答）
                // 也是期望行为，连上后一次性完整发出。
                // 队列是 4096B 的 ByteQueue（termux 源码：write 在队列满时阻塞等待消费），
                // 所以断线期间积压超过 4096B 会阻塞 write 调用方——但断线属短暂状态、
                // 正常输入量远到不了上限，可接受；openLock 与 ByteQueue 内部锁相互独立，
                // 且等待发生在 read() 之外，不会死锁。
                val canSend = synchronized(openLock) {
                    while (!open && !destroyed.get() && !intentional.get()) {
                        try {
                            openLock.wait()
                        } catch (_: InterruptedException) {
                        }
                    }
                    open && !destroyed.get() && !intentional.get()
                }
                if (!canSend) break // destroy() 或主动 close()：线程退出
                val n = session.mTerminalToProcessIOQueue.read(buffer, true)
                if (n < 0) break // 队列已关闭（destroy()），线程退出
                if (n > 0 && open) {
                    webSocket?.send(buffer.toByteString(0, n))
                }
                // 极限竞态：read 之后、send 之前连接恰好断开（onFailure 清 open），
                // ws.send 返回 false 字节仍会丢；窗口极小且字节无法回塞队列，接受该残留风险。
            }
        }, "RemoteTerminalSession-input-drain").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 建立 WebSocket 连接（幂等：已连接或连接中调用无效）。
     * cols/rows 取自 TerminalView 当前终端尺寸，用于服务器侧 pty 的初始大小。
     */
    fun connect(cols: Int, rows: Int) {
        lastCols = cols
        lastRows = rows
        if (webSocket != null || destroyed.get()) return
        doConnect()
    }

    /** 真正发起一次连接；自动重连也走这里（复用最近一次的尺寸）。 */
    private fun doConnect() {
        if (destroyed.get() || intentional.get()) return
        val url = buildString {
            append("ws://$host:$port/ws?app=$app")
            append("&token=${URLEncoder.encode(token, Charsets.UTF_8.name())}")
            append("&cols=$lastCols&rows=$lastRows")
            if (!tmuxSession.isNullOrEmpty()) {
                append("&session=${URLEncoder.encode(tmuxSession, Charsets.UTF_8.name())}")
            }
        }
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            // host 含非法字符等导致 URL 构造失败
            notifyState(State.FAILED, e.message ?: "invalid url")
            return
        }
        notifyState(State.CONNECTING, null)
        webSocket = httpClient.newWebSocket(request, SessionWebSocketListener())
    }

    /**
     * 终端尺寸变化时调用（TerminalActivity 在 onEmulatorSet 回调里转发）。
     * 连接建立时的首次尺寸走 connect() 的 URL query（不防抖）；连接后的变化走
     * resize 控制帧，300ms 去抖（见 resizeDebounceRunnable 注释）。
     */
    fun sendResize(cols: Int, rows: Int) {
        if (cols == lastCols && rows == lastRows) return
        lastCols = cols
        lastRows = rows
        if (!open) return // 未连接不发；重连时最新尺寸由 doConnect 的 URL query 携带
        mainHandler.removeCallbacks(resizeDebounceRunnable)
        mainHandler.postDelayed(resizeDebounceRunnable, 300)
    }

    /** 特殊键栏注入输入；与键盘输入走完全相同的通道（write → 输入队列 → 抽干线程 → ws）。 */
    fun sendUserInput(text: String) {
        session.write(text)
    }

    fun sendUserInput(bytes: ByteArray) {
        session.write(bytes, 0, bytes.size)
    }

    /** 主动断开连接（会话对象保留，界面仍可显示已有内容），不再自动重连。 */
    fun close() {
        intentional.set(true)
        open = false
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(resizeDebounceRunnable)
        // 唤醒可能在 openLock 上等待的抽干线程,让它看到 intentional 后退出
        synchronized(openLock) { openLock.notifyAll() }
        webSocket?.close(1000, null)
    }

    /** 释放全部资源（Activity.onDestroy / 关闭标签时调用，不可恢复）。 */
    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        close()
        webSocket?.cancel()
        session.mTerminalToProcessIOQueue.close() // 抽干线程 read() 返回 -1 后退出
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    /** 服务器 stdout 字节流 → 终端仿真器。在主线程执行，与原生刷新路径一致。 */
    private fun feedFromServer(data: ByteArray) {
        if (data.isEmpty()) return
        mainHandler.post {
            if (destroyed.get()) return@post
            val emulator = session.mEmulator ?: return@post
            emulator.append(data, data.size)
            // 等价于 TerminalSession.notifyScreenUpdate()（protected）：
            // 其方法体就是 mClient.onTextChanged(this)，这里直接回调同一个 client
            client.onTextChanged(session)
        }
    }

    /** 服务器文本帧 = JSON 控制消息。 */
    private fun handleControlFrame(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        when (json.optString("type")) {
            "exit" -> {
                intentional.set(true) // 服务器随后关闭连接，onClosed 不再重复上报
                webSocket?.close(1000, null)
                notifyState(State.EXITED, json.optInt("code", -1).toString())
            }
            "error" -> {
                intentional.set(true)
                webSocket?.close(1000, null)
                notifyState(State.FAILED, json.optString("message", "unknown server error"))
            }
            "file" -> {
                // ~/phone-push/ 出现新文件的广播；上报给 Activity（去重在 Activity 做，
                // 因为该广播会送达本机所有标签的连接）
                val name = json.optString("name").trim()
                if (name.isNotEmpty()) {
                    val size = json.optLong("size", -1L)
                    mainHandler.post {
                        if (!destroyed.get()) listener.onFilePushed(this, name, size)
                    }
                }
            }
        }
    }

    private fun notifyState(state: State, detail: String?) {
        if (destroyed.get()) return
        if (state == State.EXITED || state == State.CLOSED || state == State.FAILED) {
            if (!notified.compareAndSet(false, true)) return
        }
        mainHandler.post {
            if (!destroyed.get()) listener.onStateChanged(this, state, detail)
        }
    }

    /**
     * 连接断开后的统一处理：网络原因 → 指数退避自动重连；
     * 服务端 HTTP 拒绝（有 response，如 401）→ 重连无意义，直接 FAILED 终态。
     */
    private fun onDisconnected(httpResponse: Response?) {
        open = false
        webSocket = null // 置空后才允许 doConnect 重建
        if (destroyed.get() || intentional.get()) return
        if (httpResponse != null) {
            val detail = when (httpResponse.code) {
                401 -> "HTTP 401 Unauthorized（token 错误）"
                else -> "HTTP ${httpResponse.code}"
            }
            notifyState(State.FAILED, detail)
            return
        }
        scheduleReconnect()
    }

    /** 指数退避：1s/2s/4s…封顶 30s，直到连上或用户关闭标签。 */
    private fun scheduleReconnect() {
        reconnectAttempt++
        val delayMs = (1000L shl (reconnectAttempt - 1)).coerceAtMost(30_000L)
        mainHandler.post {
            if (!destroyed.get() && !intentional.get()) {
                listener.onReconnecting(this, reconnectAttempt)
            }
        }
        mainHandler.postDelayed(reconnectRunnable, delayMs)
    }

    private inner class SessionWebSocketListener : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            reconnectAttempt = 0
            // 唤醒断线期间挂起的抽干线程:队列里积压的输入(含 DA 应答等)此刻完整发出
            synchronized(openLock) {
                open = true
                openLock.notifyAll()
            }
            notifyState(State.OPEN, null)
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            feedFromServer(bytes.toByteArray())
        }

        override fun onMessage(ws: WebSocket, text: String) {
            handleControlFrame(text)
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(code, reason)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            onDisconnected(null)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            onDisconnected(response)
        }
    }
}
