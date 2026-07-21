package com.calla.remoteterminal

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Scroller
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.termux.terminal.KeyHandler
import com.termux.terminal.RemoteTerminalSession
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 终端页：顶部标签栏 + 状态行 + 全屏 TerminalView + 底部特殊键栏。
 *
 * 多标签架构：
 *  - 标签列表归本 Activity 持有；每个标签 = 一个 [Tab]（含独立 RemoteTerminalSession
 *    和 WebSocket 连接）。切换标签只是 TerminalView 重新 attachSession()，
 *    后台会话的连接和 emulator 都保持存活、继续接收输出。
 *  - TerminalSessionClient 回调按会话身份路由：后台会话的输出只更新自己的
 *    emulator（feedFromServer 内 append），onTextChanged 等 UI 回调只在
 *    "来源会话 == 当前标签会话" 时才触碰 TerminalView。
 *  - onEmulatorSet（attach/旋转/键盘/字号变化时由 TerminalView 触发）只对当前
 *    标签生效：未连接则 connect（幂等），已连接则按需 sendResize（内部去重）——
 *    切换回某标签时自动补发尺寸同步。
 *  - 会话生命周期：创建/重连/关闭标签/onDestroy 四处，全部由 Activity 显式管理。
 *  - 后台保活：有标签期间 KeepAliveService（前台服务 + 唤醒锁）常驻，配合
 *    RemoteTerminalSession 的指数退避自动重连，推到后台也不断线。
 */
class TerminalActivity : AppCompatActivity(),
    TerminalViewClient,
    TerminalSessionClient,
    RemoteTerminalSession.Listener {

    companion object {
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_TOKEN = "extra_token"
        const val EXTRA_APP = "extra_app"
        const val EXTRA_SESSION = "extra_session"

        private const val BASE_FONT_SP = 12f
        private const val MIN_FONT_SCALE = 0.4f
        private const val MAX_FONT_SCALE = 3f
        private val SESSION_NAME_REGEX = Regex("^[a-zA-Z0-9_-]+$")

        private const val FILE_CHANNEL_ID = "file_push"
        private const val REQ_POST_NOTIFICATIONS = 1

        /** 文件推送去重窗口：同一 name|size 在该窗口内（多标签同时收到同一广播）只提示一次。 */
        private const val PUSH_DEDUPE_WINDOW_MS = 10_000L
    }

    /** 一个标签。session 的生命周期由 Activity 管理（重连时会整个替换）。 */
    private class Tab(
        val id: Int,
        val label: String,
        val app: String,
        val tmuxSession: String?,
        var session: RemoteTerminalSession?,
    ) {
        /** 双指缩放的字号，按标签各自记住。 */
        var fontScale = 1f

        /** 会话已退出/断连（标签置灰）。 */
        var dead = false

        /** 断线自动重连进行中（标签加 ↻ 前缀）。 */
        var reconnecting = false

        var reconnectAttempt = 0

        var stateText = ""
    }

    private lateinit var terminalView: TerminalView
    private lateinit var statusText: TextView
    private lateinit var tabsContainer: LinearLayout
    private lateinit var ctrlButton: Button

    private lateinit var host: String
    private var port: Int = 7681
    private lateinit var token: String

    private val tabs = ArrayList<Tab>()
    private var currentTab: Tab? = null
    private var nextTabId = 1

    private var stickyCtrl = false
    private var baseFontPx = 0
    private var termTitle: String? = null
    private var endDialogShowing = false
    private lateinit var orientationButton: Button

    /** 拉取服务端活 tmux 会话列表用（新建标签对话框的"接入已有会话"）。 */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** 文件推送去重：key=name|size，value=上次提示时间戳；只挡窗口期内的重复广播。 */
    private val pushedFileKeys = LinkedHashMap<String, Long>()

    /** 前台弹对话框、后台发系统通知（onResume/onPause 维护）。 */
    private var inForeground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        port = intent.getStringExtra(EXTRA_PORT)?.toIntOrNull() ?: 7681
        token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (host.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, R.string.missing_params, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val initialApp = intent.getStringExtra(EXTRA_APP).orEmpty().ifEmpty { "shell" }
        val initialSession = intent.getStringExtra(EXTRA_SESSION)

        terminalView = findViewById(R.id.terminal_view)
        statusText = findViewById(R.id.status_text)
        tabsContainer = findViewById(R.id.tabs_container)
        ctrlButton = findViewById(R.id.key_ctrl)

        baseFontPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, BASE_FONT_SP, resources.displayMetrics
        ).toInt()

        terminalView.setTerminalViewClient(this)
        terminalView.setTextSize(baseFontPx)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true

        setupExtraKeys()
        findViewById<Button>(R.id.btn_add_tab).setOnClickListener { showNewTabDialog() }

        // terminal-emulator 的 JNI 库（libtermux.so）必须随 AAR 打进 APK；第一次
        // updateSize 就会在 framework 回调里触发加载，这里先显式加载以便提前暴露问题。
        runCatching { System.loadLibrary("termux") }.onFailure {
            showFatal(getString(R.string.error_jni_load_failed))
            return
        }

        openTab(initialApp, initialSession)

        // Android 13+ 发系统通知需要运行时权限（后台收到文件推送时用），只请求一次
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 软键盘弹出时返回键先收键盘，再按才走退出确认
                val imeVisible = ViewCompat.getRootWindowInsets(terminalView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                if (imeVisible) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
                } else {
                    confirmExit()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
    }

    override fun onPause() {
        inForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        // 先停前台服务（释放唤醒锁），再逐个关连接
        KeepAliveService.stop(this)
        for (tab in tabs) {
            tab.session?.destroy()
            tab.session = null
        }
        super.onDestroy()
    }

    /** 有标签就启动/更新前台保活服务；全部关闭则停止。 */
    private fun updateKeepAlive() {
        if (tabs.isEmpty()) KeepAliveService.stop(this)
        else KeepAliveService.startOrUpdate(this, tabs.size)
    }

    // ---------- 标签管理 ----------

    /**
     * 新建标签（创建会话对象）并立即切换过去；连接在 onEmulatorSet 里发起。
     * 任何 app 都落进 tmux 命名会话（服务端对所有 app 的 session 参数都包 tmux）：
     * 手机断线进程不死、server 本机可 `tmux attach -t <名>` 接力、断线重连即恢复原画面。
     * 不填会话名时自动生成 `<app>-<序号>`（序号=同 app 现有标签数+1，撞名则顺延）。
     */
    private fun openTab(app: String, tmuxSession: String?) {
        val sessionName = tmuxSession?.trim()?.takeIf { it.isNotEmpty() } ?: autoSessionName(app)
        // 标签标题直接用会话名，方便和 server 本机 `tmux ls` 对应
        val tab = Tab(nextTabId++, sessionName, app, sessionName, null)
        tab.session = createSession(tab)
        tabs.add(tab)
        updateKeepAlive()
        switchToTab(tab)
    }

    private fun autoSessionName(app: String): String {
        val used = tabs.mapNotNull { it.tmuxSession }.toHashSet()
        var n = tabs.count { it.app == app } + 1
        var name = "$app-$n"
        while (name in used) {
            n++
            name = "$app-$n"
        }
        return name
    }

    private fun createSession(tab: Tab): RemoteTerminalSession {
        tab.stateText = getString(R.string.state_connecting)
        return RemoteTerminalSession(host, port, tab.app, tab.tmuxSession, token, this, this)
    }

    /** 切换标签：TerminalView 重新 attach；后台标签的连接与 emulator 保持不动。 */
    private fun switchToTab(tab: Tab) {
        if (currentTab === tab) {
            refreshTabBar()
            refreshStatus()
            return
        }
        currentTab = tab
        termTitle = tab.session?.session?.title
        terminalView.stopTextSelectionMode()
        tab.session?.let { rs ->
            try {
                // attachSession → updateSize → onEmulatorSet → connect/补发 resize
                terminalView.attachSession(rs.session)
            } catch (t: Throwable) {
                showFatal(getString(R.string.error_attach_failed, t.message ?: ""))
                return
            }
        }
        // 恢复该标签记住的字号；若因此改变行列数会再走一次 onEmulatorSet 补发 resize
        terminalView.setTextSize((baseFontPx * tab.fontScale).toInt().coerceAtLeast(6))
        refreshTabBar()
        refreshStatus()
        // 切到一个已断开/已退出的标签：给出可取消的重连提示（不强制）
        if (tab.dead) {
            showEndDialog(tab.stateText.ifEmpty { getString(R.string.dialog_disconnected) }, tab, cancelable = true)
        }
    }

    private fun closeTab(tab: Tab) {
        tab.session?.destroy()
        tab.session = null
        val idx = tabs.indexOf(tab)
        tabs.remove(tab)
        updateKeepAlive()
        if (tabs.isEmpty()) {
            finish() // 防御：正常路径 confirmCloseTab 会拦住最后一个标签
            return
        }
        if (currentTab === tab) {
            currentTab = null
            switchToTab(tabs[minOf(idx.coerceAtLeast(0), tabs.size - 1)])
        } else {
            refreshTabBar()
        }
    }

    private fun confirmCloseTab(tab: Tab) {
        if (tabs.size <= 1) {
            Toast.makeText(this, R.string.error_last_tab, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_close_tab_title)
            .setMessage(getString(R.string.dialog_close_tab_message, tab.label))
            .setPositiveButton(R.string.action_close_tab) { _, _ -> closeTab(tab) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 重连某标签的会话：销毁旧 RemoteTerminalSession，换新实例重新 attach。 */
    private fun reconnectTab(tab: Tab) {
        tab.session?.destroy()
        tab.session = createSession(tab)
        tab.dead = false
        tab.reconnecting = false
        tab.reconnectAttempt = 0
        if (currentTab === tab) {
            tab.session?.let { terminalView.attachSession(it.session) }
            refreshStatus()
        }
        refreshTabBar()
    }

    // ---------- 新建标签对话框（命名会话 + 接入已有会话 + resume 条目） ----------

    private fun showNewTabDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_new_tab, null)
        val radioApp = view.findViewById<RadioGroup>(R.id.dialog_radio_app)
        val sessionEdit = view.findViewById<TextInputEditText>(R.id.dialog_edit_session)
        val sessionsStatus = view.findViewById<TextView>(R.id.dialog_sessions_status)
        val sessionsContainer = view.findViewById<LinearLayout>(R.id.dialog_sessions_container)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_new_tab_title)
            .setView(view)
            .setPositiveButton(R.string.action_create, null)
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        // 接入已有会话：对话框打开时异步拉服务端活 tmux 会话列表；
        // 请求失败/为空只在区域内提示"无可用会话"，不阻塞手动新建
        fetchTmuxSessions(sessionsStatus, sessionsContainer) { name ->
            openTab("tmux", name)
            dialog.dismiss()
        }
        // 手动处理确定按钮，校验失败时不关闭对话框
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val app = when (radioApp.checkedRadioButtonId) {
                R.id.dialog_radio_kimi -> "kimi"
                R.id.dialog_radio_claude -> "claude"
                R.id.dialog_radio_tmux -> "tmux"
                R.id.dialog_radio_claude_c -> "claude-c"
                R.id.dialog_radio_claude_r -> "claude-r"
                R.id.dialog_radio_kimi_c -> "kimi-c"
                R.id.dialog_radio_kimi_r -> "kimi-r"
                else -> "shell"
            }
            // 任何 app 都可以填会话名；留空由 openTab 自动生成 <app>-<序号>
            val typed = sessionEdit.text?.toString()?.trim().orEmpty()
            if (typed.isNotEmpty() && !SESSION_NAME_REGEX.matches(typed)) {
                sessionEdit.error = getString(R.string.error_session_invalid)
                return@setOnClickListener
            }
            openTab(app, typed.ifEmpty { null })
            dialog.dismiss()
        }
    }

    /** GET /sessions 列出活 tmux 会话；每项一个按钮，点按即开标签接入（app=tmux + session=名）。 */
    private fun fetchTmuxSessions(status: TextView, container: LinearLayout, onPick: (String) -> Unit) {
        val url = "http://$host:$port/sessions?token=${URLEncoder.encode(token, Charsets.UTF_8.name())}"
        httpClient.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                response.close()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    var added = 0
                    try {
                        val arr = JSONObject(body).getJSONArray("sessions")
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val name = o.optString("name").trim()
                            if (name.isEmpty()) continue
                            val attached = o.optBoolean("attached", false)
                            container.addView(Button(this@TerminalActivity).apply {
                                text = if (attached) {
                                    getString(R.string.sessions_item_attached, name)
                                } else {
                                    name
                                }
                                isAllCaps = false
                                setOnClickListener { onPick(name) }
                            })
                            added++
                        }
                    } catch (t: Throwable) {
                        // 响应异常视同无会话，下面统一显示提示
                    }
                    if (added == 0) {
                        status.setText(R.string.sessions_none)
                    } else {
                        status.visibility = View.GONE
                    }
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) status.setText(R.string.sessions_none)
                }
            }
        })
    }

    /** 标签栏重绘：当前标签高亮；已断开加 ✕、重连中加 ↻ 前缀。 */
    private fun refreshTabBar() {
        tabsContainer.removeAllViews()
        val currentBg = ContextCompat.getColorStateList(this, R.color.ctrl_active)
        val normalBg = ContextCompat.getColorStateList(this, R.color.key_background)
        val normalText = ContextCompat.getColor(this, R.color.key_text)
        val deadText = ContextCompat.getColor(this, R.color.tab_dead_text)
        for (tab in tabs) {
            val button = Button(this).apply {
                text = when {
                    tab.dead -> "✕ ${tab.label}"
                    tab.reconnecting -> "↻ ${tab.label}"
                    else -> tab.label
                }
                isAllCaps = false
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(12), 0, dp(12), 0)
                backgroundTintList = if (tab === currentTab) currentBg else normalBg
                setTextColor(if (tab.dead) deadText else normalText)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(4) }
                setOnClickListener { switchToTab(tab) }
                setOnLongClickListener {
                    confirmCloseTab(tab)
                    true
                }
            }
            tabsContainer.addView(button)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    // ---------- 会话状态回调（RemoteTerminalSession.Listener，已在 UI 线程） ----------

    override fun onStateChanged(
        session: RemoteTerminalSession,
        state: RemoteTerminalSession.State,
        detail: String?
    ) {
        if (isFinishing || isDestroyed) return
        val tab = tabs.firstOrNull { it.session === session } ?: return
        when (state) {
            RemoteTerminalSession.State.CONNECTING ->
                tab.stateText = getString(R.string.state_connecting)

            RemoteTerminalSession.State.OPEN -> {
                tab.stateText = getString(R.string.state_connected)
                tab.reconnecting = false
                tab.reconnectAttempt = 0
                if (tab === currentTab) {
                    Toast.makeText(this, R.string.state_connected, Toast.LENGTH_SHORT).show()
                }
            }

            RemoteTerminalSession.State.EXITED -> {
                tab.dead = true
                tab.reconnecting = false
                tab.stateText = getString(R.string.state_exited, detail ?: "?")
                if (tab === currentTab) {
                    showEndDialog(getString(R.string.dialog_process_exited, detail ?: "?"), tab)
                }
            }

            RemoteTerminalSession.State.CLOSED -> {
                tab.dead = true
                tab.reconnecting = false
                tab.stateText = getString(R.string.state_disconnected)
                if (tab === currentTab) {
                    showEndDialog(getString(R.string.dialog_disconnected), tab)
                }
            }

            RemoteTerminalSession.State.FAILED -> {
                tab.dead = true
                tab.reconnecting = false
                tab.stateText = getString(R.string.state_failed)
                if (tab === currentTab) {
                    showEndDialog(getString(R.string.dialog_connect_failed, detail ?: ""), tab)
                }
            }
        }
        // 后台标签的状态变化只反映在标签栏（置灰/✕/↻），不打扰当前操作
        refreshTabBar()
        if (tab === currentTab) refreshStatus()
    }

    /** 断线自动重连提示：标签加 ↻ 前缀 + 状态行显示第几次重连。 */
    override fun onReconnecting(session: RemoteTerminalSession, attempt: Int) {
        if (isFinishing || isDestroyed) return
        val tab = tabs.firstOrNull { it.session === session } ?: return
        tab.reconnecting = true
        tab.reconnectAttempt = attempt
        tab.stateText = getString(R.string.state_reconnecting, attempt)
        refreshTabBar()
        if (tab === currentTab) refreshStatus()
    }

    // ---------- 文件推送（服务端 watch ~/phone-push/ 的广播） ----------

    override fun onFilePushed(session: RemoteTerminalSession, name: String, size: Long) {
        if (isFinishing || isDestroyed) return
        Log.i("TerminalActivity", "收到文件推送: $name ($size B)")
        // 去重(问题3 根因):服务端的广播会送达本机每个标签的连接,同一次推送要在
        // 多标签间去重;但 v3 用无限期集合去重,常驻 Activity(保活服务让进程长期
        // 不死)下,同名同大小的再次推送会被永远吞掉——用户 10:40 推送的
        // app-debug.apk 与 08:17 的 size 完全相同,就这样被吞了。改为 10s 时间窗:
        // 窗口内(多标签同时收到的同一广播)去重,窗口外的再次推送照样提示。
        val now = System.currentTimeMillis()
        val key = "$name|$size"
        val last = pushedFileKeys[key]
        if (last != null && now - last < PUSH_DEDUPE_WINDOW_MS) return
        pushedFileKeys[key] = now
        // 顺手清理过期记录,防长时间运行无限增长
        val it = pushedFileKeys.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > 60_000L) it.remove()
        }
        // 保底:无论走对话框还是通知,先弹 Toast 确保用户一定感知到
        Toast.makeText(this, getString(R.string.file_pushed_toast, name), Toast.LENGTH_LONG).show()
        if (inForeground) {
            showFilePushedDialog(name, size)
        } else {
            postFilePushedNotification(name, size)
        }
    }

    private fun formatSize(size: Long): String = when {
        size < 0 -> "?"
        size < 1024 -> "${size}B"
        size < 1024 * 1024 -> "${size / 1024}KB"
        else -> "%.1fMB".format(size / 1024f / 1024f)
    }

    /** 拼 /files 下载 URL；URLEncoder 把空格编成 '+'，路径段里 '+' 是字面量，需换成 %20。 */
    private fun fileDownloadUrl(name: String): String {
        val encodedName = URLEncoder.encode(name, Charsets.UTF_8.name()).replace("+", "%20")
        return "http://$host:$port/files/$encodedName?token=${URLEncoder.encode(token, Charsets.UTF_8.name())}"
    }

    /** DownloadManager 写公共 Download 目录，不需要 READ/WRITE 存储权限。 */
    private fun downloadPushedFile(name: String) {
        val request = DownloadManager.Request(Uri.parse(fileDownloadUrl(name)))
            .setTitle(name)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        runCatching {
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }.onFailure {
            Toast.makeText(this, getString(R.string.download_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun showFilePushedDialog(name: String, size: Long) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_file_pushed_title)
            .setMessage(getString(R.string.dialog_file_pushed_message, name, formatSize(size)))
            .setPositiveButton(R.string.action_download) { _, _ -> downloadPushedFile(name) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** App 在后台时的提示：系统通知，点击经 DownloadFileReceiver 触发同一个下载。 */
    private fun postFilePushedNotification(name: String, size: Long) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    FILE_CHANNEL_ID,
                    getString(R.string.notif_channel_file),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val intent = Intent(this, DownloadFileReceiver::class.java).apply {
            action = DownloadFileReceiver.ACTION_DOWNLOAD
            putExtra(DownloadFileReceiver.EXTRA_URL, fileDownloadUrl(name))
            putExtra(DownloadFileReceiver.EXTRA_NAME, name)
        }
        val pending = PendingIntent.getBroadcast(
            this, name.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, FILE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notif_file_pushed_title))
            .setContentText(getString(R.string.dialog_file_pushed_message, name, formatSize(size)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(name.hashCode(), notification) }
    }

    // ---------- 对话框 ----------

    /**
     * 会话终止对话框：重新连接 / 关闭标签。
     * 只剩一个标签时"关闭标签"表现为退出 Activity（回到 SetupActivity）。
     */
    private fun showEndDialog(message: String, tab: Tab, cancelable: Boolean = false) {
        if (endDialogShowing || isFinishing || isDestroyed) return
        endDialogShowing = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_session_ended_title)
            .setMessage(message)
            .setCancelable(cancelable)
            .setOnDismissListener { endDialogShowing = false }
            .setPositiveButton(R.string.action_reconnect) { _, _ -> reconnectTab(tab) }
            .setNegativeButton(R.string.action_close_tab) { _, _ ->
                if (tabs.size <= 1) finish() else closeTab(tab)
            }
            .show()
    }

    private fun showFatal(message: String) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_error_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.action_back) { _, _ -> finish() }
            .show()
    }

    /** 返回键：确认后退出整个 Activity（onDestroy 关闭全部连接）。 */
    private fun confirmExit() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_exit_title)
            .setMessage(R.string.dialog_exit_message)
            .setPositiveButton(R.string.action_disconnect) { _, _ -> finish() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------- 特殊键栏 ----------

    private fun setupExtraKeys() {
        findViewById<Button>(R.id.key_esc).setOnClickListener {
            currentTab?.session?.sendUserInput(byteArrayOf(0x1B))
        }
        findViewById<Button>(R.id.key_tab).setOnClickListener {
            currentTab?.session?.sendUserInput(byteArrayOf(0x09))
        }
        findViewById<Button>(R.id.key_enter).setOnClickListener {
            currentTab?.session?.sendUserInput(byteArrayOf(0x0D))
        }
        findViewById<Button>(R.id.key_left).setOnClickListener { sendArrowKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        findViewById<Button>(R.id.key_up).setOnClickListener { sendArrowKey(KeyEvent.KEYCODE_DPAD_UP) }
        findViewById<Button>(R.id.key_down).setOnClickListener { sendArrowKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        findViewById<Button>(R.id.key_right).setOnClickListener { sendArrowKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        ctrlButton.setOnClickListener {
            stickyCtrl = !stickyCtrl
            updateCtrlButton()
        }
        findViewById<Button>(R.id.key_keyboard).setOnClickListener { toggleSoftKeyboard() }
        orientationButton = findViewById(R.id.key_orientation)
        orientationButton.setOnClickListener { toggleOrientation() }
        updateCtrlButton()
    }

    /**
     * 横竖屏切换。manifest 的 configChanges 已含 orientation|screenSize，Activity 不重建；
     * TerminalView 尺寸变化走 onSizeChanged → updateSize → onEmulatorSet →
     * 既有机制自动给服务器补发 resize，无需额外处理。
     */
    private fun toggleOrientation() {
        val toLandscape = requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        requestedOrientation = if (toLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        orientationButton.setText(if (toLandscape) R.string.key_to_portrait else R.string.key_to_landscape)
    }

    /**
     * 方向键序列随"应用光标键模式"(DECCKM)切换（如全屏 TUI 开启后应发 \033OA 等），
     * 与 TerminalView 处理硬件方向键的路径一致，走 KeyHandler。
     */
    private fun sendArrowKey(keyCode: Int) {
        val emulator = terminalView.mEmulator ?: return
        val code = KeyHandler.getCode(
            keyCode, 0, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode
        ) ?: return
        currentTab?.session?.sendUserInput(code)
    }

    private fun updateCtrlButton() {
        ctrlButton.backgroundTintList = ContextCompat.getColorStateList(
            this, if (stickyCtrl) R.color.ctrl_active else R.color.key_background
        )
    }

    private fun toggleSoftKeyboard() {
        terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val imeVisible = ViewCompat.getRootWindowInsets(terminalView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (imeVisible) {
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
        } else {
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---------- 状态行 ----------

    private fun refreshStatus() {
        val tab = currentTab
        if (tab == null) {
            statusText.text = ""
            return
        }
        val sb = StringBuilder()
            .append(tab.label).append(" [").append(tab.app).append(']')
            .append(" @ ").append(host).append(':').append(port)
        if (tab.stateText.isNotEmpty()) sb.append("  ·  ").append(tab.stateText)
        termTitle?.takeIf { it.isNotEmpty() }?.let { sb.append("  ·  ").append(it) }
        // 末尾固定显示版本号:让用户一眼确认装的是不是最新版(排查"以为装了新版"问题)
        sb.append("  ·  v").append(BuildConfig.VERSION_NAME)
        statusText.text = sb.toString()
    }

    // ---------- TerminalViewClient ----------

    override fun onScale(scale: Float): Float {
        // TerminalView 传入的是累计缩放因子；返回修正后的因子。字号按标签各自记住。
        val tab = currentTab ?: return 1f
        val clamped = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        if (clamped != tab.fontScale) {
            tab.fontScale = clamped
            terminalView.setTextSize((baseFontPx * clamped).toInt().coerceAtLeast(6))
        }
        return clamped
    }

    override fun onSingleTapUp(e: MotionEvent) {
        terminalView.requestFocus()
        // 根因修复（滚屏跳动）之二：fling 动画还在跑的时候点按屏幕（用户意图是"停住滚动"），
        // GestureDetector 会把这次点按当成干净单击回调到这里；若照旧翻译方向键/弹键盘，
        // 画面一边惯性滚动一边被方向键/重绘拉扯 = 跳动。所以 fling 未结束时的单击
        // 只用来终止 fling，不做任何翻译。（正常的滑动不会走到这里：GestureDetector
        // 只有位移始终小于 touch slop 才回调 onSingleTapConfirmed。）
        if (abortFlingIfRunning()) return
        // 与鼠标上报的冲突处理:mouse tracking 开启(被控端 DECSET 1000/1002)时,
        // 单击已在 TerminalView 的手势层作为鼠标事件发给被控端,这里不再介入。
        val emulator = terminalView.mEmulator
        if (emulator?.isMouseTrackingActive == true) return
        // 注:曾经的"TUI 点选翻译"(点按某行 = 连发 ↑/↓ 把选择挪过去)已彻底移除——
        // 全量 tmux 化后备用缓冲区常开,行距换算稍有偏差就把单击误译成几十次方向键
        // (弹不出键盘、界面乱滚),且功能与底栏 ⏎←↑↓→ 重复。TUI 菜单导航请用底栏方向键。
        // 干净单击(fling 已停、非 mouse tracking)一律弹软键盘:
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    /** TerminalView.mTopRow 是包私有,反射读(滚动偏移);失败按 0(未滚动)。 */
    private fun topRowOffset(): Int = try {
        val f = terminalView.javaClass.getDeclaredField("mTopRow")
        f.isAccessible = true
        f.getInt(terminalView)
    } catch (t: Throwable) {
        0
    }

    /** TerminalView.mScroller 同样是包私有,反射拿;fling 进行中则终止并返回 true。 */
    private fun abortFlingIfRunning(): Boolean = try {
        val f = terminalView.javaClass.getDeclaredField("mScroller")
        f.isAccessible = true
        val scroller = f.get(terminalView) as Scroller
        if (!scroller.isFinished) {
            scroller.abortAnimation()
            true
        } else {
            false
        }
    } catch (t: Throwable) {
        false
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    /** 粘滞 CTRL：TerminalView 默认控制字符映射（c→0x03、d→0x04、[→0x1B 等）读取此状态。 */
    override fun readControlKey(): Boolean = stickyCtrl

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        // 每个文本码点发送前都会经过这里；按约定 CTRL 在下一个字符发出后自动取消
        if (stickyCtrl) {
            stickyCtrl = false
            updateCtrlButton()
        }
        return false // 交回 TerminalView 默认逻辑（含 ctrl 控制字符映射）处理
    }

    override fun onEmulatorSet() {
        // attach/尺寸变化/字号变化时回调；只对当前可见标签的会话生效：
        // connect 幂等（已连接直接返回），sendResize 内部按尺寸去重、未连接不发送。
        val emulator = terminalView.mEmulator ?: return
        val rs = currentTab?.session ?: return
        rs.connect(emulator.mColumns, emulator.mRows)
        rs.sendResize(emulator.mColumns, emulator.mRows)
    }

    // ---------- TerminalSessionClient（按会话身份路由，只刷新当前标签的 UI） ----------

    override fun onTextChanged(changedSession: TerminalSession) {
        if (changedSession !== currentTab?.session?.session) return
        // 根因修复（滚屏跳动）之一：Termux 的 TerminalView.onScreenUpdated() 在任何新输出
        // 到达时都会把 mTopRow 强制归零（拽回底部，只有文本选择时豁免）。本地 Termux 里
        // shell 只在用户敲命令时输出，无所谓；但这里远端输出持续到达（tmux 状态栏时钟、
        // AI spinner 重绘、后台任务日志），用户向上翻看历史时会被不断拽回底部。
        // TerminalView 是 final 无法子类化，故在调用现场保护：更新前记下回看位置与滚动计数，
        // 更新后恢复并补偿新增历史行数（scrollCounter 每有一行滚入历史 +1，与
        // onScreenUpdated 内文本选择分支的 mTopRow -= rowShift 同一算法）。
        //
        // 守卫自审（问题2 教训）：
        //  - 在底部（topRow == 0）：绝不能恢复，否则 setTopRow(0 - shift) 会把视图
        //    拽进历史区，表现为打字时屏幕乱跳/重复 → 条件里硬性要求 topRow < 0。
        //  - 上翻中（topRow < 0）：恢复 + 补偿，画面保持不动。
        //  - 上翻后手动回到底部：doScroll 已把 mTopRow 归 0，走"在底部"分支，不干预。
        //  - 文本选择中：onScreenUpdated 内部已自行保持位置（skipScrolling），不重复补偿。
        //  - 无历史（rowsInHistory == 0，如备用缓冲区/tmux）：无可恢复，直接跳过
        //    （也避免 coerceIn(0, 0) 的边界）。
        val emulator = terminalView.mEmulator
        val topRow = topRowOffset()
        val scrollShift = emulator?.scrollCounter ?: 0
        val selecting = terminalView.isSelectingText
        terminalView.onScreenUpdated()
        if (!selecting && topRow < 0 && emulator != null) {
            val rowsInHistory = emulator.screen.activeTranscriptRows
            if (rowsInHistory > 0) {
                terminalView.setTopRow((topRow - scrollShift).coerceIn(-rowsInHistory, 0))
            }
        }
        // 后台会话：emulator 已在 feedFromServer 里 append 完毕，这里不触发 UI 刷新
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        if (changedSession === currentTab?.session?.session) {
            termTitle = changedSession.title
            refreshStatus()
        }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        // 本地没有子进程，不会触发；远端退出走 RemoteTerminalSession.State.EXITED
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        // 只有前台文本选择会触发（TerminalView 只对 attach 的会话做选择）
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this)?.toString()
            if (!text.isNullOrEmpty()) {
                // 走 emulator.paste()：自动处理 bracketed paste，字节汇入该会话的 ws
                terminalView.mEmulator?.paste(text)
            }
        }
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        if (session === currentTab?.session?.session) {
            terminalView.invalidate()
        }
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int? = null // null → 默认方块光标

    // ---------- 日志（TerminalViewClient 与 TerminalSessionClient 签名相同，一份实现即可） ----------

    override fun logError(tag: String, message: String?) {
        Log.e(tag, message ?: "")
    }

    override fun logWarn(tag: String, message: String?) {
        Log.w(tag, message ?: "")
    }

    override fun logInfo(tag: String, message: String?) {
        Log.i(tag, message ?: "")
    }

    override fun logDebug(tag: String, message: String?) {
        Log.d(tag, message ?: "")
    }

    override fun logVerbose(tag: String, message: String?) {
        Log.v(tag, message ?: "")
    }

    override fun logStackTraceWithMessage(tag: String, message: String?, e: Exception) {
        Log.e(tag, message ?: "", e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "stack trace", e)
    }
}
