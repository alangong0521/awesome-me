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
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
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
import kotlin.math.abs

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

        // noVNC 键盘注入用的 DOM KeyboardEvent.code(注入方式见 sendDesktopKeysym)
        private const val XK_ENTER = "Enter"
        private const val XK_ESCAPE = "Escape"
        private const val XK_TAB = "Tab"
        private const val XK_BACKSPACE = "Backspace"
        private const val XK_UP = "ArrowUp"
        private const val XK_DOWN = "ArrowDown"
        private const val XK_LEFT = "ArrowLeft"
        private const val XK_RIGHT = "ArrowRight"
        private const val XK_HOME = "Home"
        private const val XK_END = "End"
        private const val XK_PRIOR = "PageUp"
        private const val XK_NEXT = "PageDown"
        private const val XK_F1 = "F1"
        private const val XK_F2 = "F2"

        /** 文件推送去重窗口：同一 name|size 在该窗口内（多标签同时收到同一广播）只提示一次。 */
        private const val PUSH_DEDUPE_WINDOW_MS = 10_000L

        /** 需要"输入框内点按定位光标"的 TUI 程序（kimi/claude 不吃鼠标点击，用方向键挪光标）。 */
        private val TUI_APPS = setOf("kimi", "kimi-c", "kimi-r", "claude", "claude-c", "claude-r")

        /** 点按定位：最长按下时长（超时视为滚动/选择，不拦截）。 */
        private const val CURSOR_TAP_MAX_MS = 400L
    }

    /** 一个标签。session 的生命周期由 Activity 管理（重连时会整个替换）。
     *  每个标签自带连接三元组（多机切换：不同标签可以连不同机器）。
     *  desktop=true 时是 noVNC 桌面标签:无终端会话,内容是自己的 WebView。 */
    private class Tab(
        val id: Int,
        /** 显示名(可变:支持"重命名页签",只改 App 侧显示,不动 tmux 会话名)。 */
        var label: String,
        val app: String,
        val tmuxSession: String?,
        var session: RemoteTerminalSession?,
        val host: String,
        val port: Int,
        val token: String,
        /** 机器显示名（机器列表里的名字；查不到则为 host），用于标签标题。 */
        val machineName: String,
        val desktop: Boolean = false,
    ) {
        /** 双指缩放的字号，按标签各自记住。 */
        var fontScale = 1f

        /** 会话已退出/断连（标签置灰）。 */
        var dead = false

        /** 断线自动重连进行中（标签加 ↻ 前缀）。 */
        var reconnecting = false

        var reconnectAttempt = 0

        var stateText = ""

        /** 最近一次连接状态（状态行着色用：已连接绿/重连中黄/断开红）。 */
        var lastState: RemoteTerminalSession.State? = null

        /** 桌面标签的 WebView(懒创建,挂在终端容器里,切标签只 show/hide 不销毁)。 */
        var webView: android.webkit.WebView? = null

        /** 桌面标签的完整加载 URL(openDesktopTab 时含 VNC 密码参数)。 */
        var desktopUrl: String? = null

        /** 桌面/浏览器标签的 noVNC 端口(6080=桌面,6081=浏览器),状态行显示用。 */
        var vncPort: Int = 6080
    }

    private lateinit var terminalView: TerminalView
    private lateinit var statusText: TextView
    private lateinit var tabsContainer: LinearLayout
    private lateinit var machinesContainer: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var ctrlButton: Button
    private lateinit var inboxBadge: TextView

    private lateinit var host: String
    private var port: Int = 7681
    private lateinit var token: String

    private val tabs = ArrayList<Tab>()
    private var currentTab: Tab? = null
    private var nextTabId = 1

    /** 机器栏当前选中的机器名。标签栏按它过滤显示——只是显示过滤,其他机器的标签
     *  连接和会话在后台完全不受影响(不断连、继续收输出)。 */
    private var selectedMachine = ""

    private var stickyCtrl = false
    private var stickyShift = false
    private var stickyAlt = false
    private var stickyFn = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var baseFontPx = 0
    private var termTitle: String? = null
    private var endDialogShowing = false
    private lateinit var orientationButton: Button
    private lateinit var shiftButton: Button
    private lateinit var altButton: Button
    private lateinit var fnButton: Button

    /** 拉取服务端活 tmux 会话列表用（新建标签对话框的"接入已有会话"）。 */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** 文件推送去重：key=name|size，value=上次提示时间戳；只挡窗口期内的重复广播。 */
    private val pushedFileKeys = LinkedHashMap<String, Long>()

    /** 最近一次 /sessions 拉到的服务端 tmux 会话名集合(autoSessionName 避让撞名用)。 */
    private val lastKnownTmuxSessions = HashSet<String>()

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
        machinesContainer = findViewById(R.id.machines_container)
        emptyHint = findViewById(R.id.empty_hint)
        ctrlButton = findViewById(R.id.key_ctrl)
        inboxBadge = findViewById(R.id.inbox_badge)
        findViewById<Button>(R.id.btn_inbox).setOnClickListener { showInboxDialog() }
        findViewById<Button>(R.id.btn_upload).setOnClickListener {
            uploadPicker.launch(arrayOf("*/*"))
        }

        baseFontPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, BASE_FONT_SP, resources.displayMetrics
        ).toInt()

        terminalView.setTerminalViewClient(this)
        terminalView.setTextSize(baseFontPx)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true

        setupExtraKeys()
        setupKeybarGestureExclusion()
        setupImeResizeFreeze()
        findViewById<Button>(R.id.btn_add_tab).setOnClickListener { showNewTabDialog() }

        // terminal-emulator 的 JNI 库（libtermux.so）必须随 AAR 打进 APK；第一次
        // updateSize 就会在 framework 回调里触发加载，这里先显式加载以便提前暴露问题。
        runCatching { System.loadLibrary("termux") }.onFailure {
            showFatal(getString(R.string.error_jni_load_failed))
            return
        }

        // 初始标签若没给会话名,先拉一次 /sessions 再开——否则自动生成名可能撞上服务端
        // 已有会话(tmux -A 存在即接入,"全新 shell"变接入历史会话)。拉不到也照开(退让)。
        if (initialSession == null) {
            prefetchSessionsThenOpen(initialApp)
        } else {
            openTab(initialApp, initialSession)
        }
        refreshInboxBadge()

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

    /**
     * singleTask 下重复"连接"（SetupActivity 再次 startActivity）/重复 am start 会走到这里。
     * 按新参数重建连接层：销毁全部旧标签会话，以新 host/token/app 重开初始标签。
     * 为什么必须重建而不是复用：extras 可能指向另一台机器/另一个 token，复用旧会话就是错的。
     * 视图层在 onCreate 已初始化，这里只重置连接层。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newHost = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val newToken = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (newHost.isEmpty() || newToken.isEmpty()) return // 没带参数：仅把已有实例带回前台
        for (tab in tabs) {
            tab.session?.destroy()
            tab.session = null
        }
        tabs.clear()
        currentTab = null
        host = newHost
        port = intent.getStringExtra(EXTRA_PORT)?.toIntOrNull() ?: 7681
        token = newToken
        val app = intent.getStringExtra(EXTRA_APP).orEmpty().ifEmpty { "shell" }
        val session = intent.getStringExtra(EXTRA_SESSION)
        openTab(app, session)
    }

    override fun onPause() {
        inForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        // 先停前台服务（释放唤醒锁），再逐个关连接;收件箱下载轮询一并停
        downloadHandler.removeCallbacksAndMessages(null)
        activeDownloads.clear()
        KeepAliveService.stop(this)
        for (tab in tabs) {
            tab.session?.destroy()
            tab.session = null
            tab.webView?.destroy()
            tab.webView = null
        }
        super.onDestroy()
    }

    /** 有标签就启动/更新前台保活服务；全部关闭则停止。 */
    private fun updateKeepAlive() {
        if (tabs.isEmpty()) KeepAliveService.stop(this)
        else KeepAliveService.startOrUpdate(this, tabs.size)
    }

    // ---------- 标签管理 ----------

    /** 启动预拉 /sessions 填 lastKnownTmuxSessions 后再开初始标签(防初始标签撞名误接旧会话)。 */
    private fun prefetchSessionsThenOpen(initialApp: String) {
        val url = "http://$host:$port/sessions?token=${URLEncoder.encode(token, Charsets.UTF_8.name())}"
        httpClient.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                response.close()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        runCatching {
                            val arr = JSONObject(body).getJSONArray("sessions")
                            lastKnownTmuxSessions.clear()
                            for (i in 0 until arr.length()) {
                                arr.getJSONObject(i).optString("name").trim()
                                    .takeIf { it.isNotEmpty() }?.let { lastKnownTmuxSessions.add(it) }
                            }
                        }
                        openTab(initialApp, null)
                    }
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) openTab(initialApp, null) // 拉不到也照开(退让)
                }
            }
        })
    }

    /**
     * 新建标签（创建会话对象）并立即切换过去；连接在 onEmulatorSet 里发起。
     * 任何 app 都落进 tmux 命名会话（服务端对所有 app 的 session 参数都包 tmux）：
     * 手机断线进程不死、server 本机可 `tmux attach -t <名>` 接力、断线重连即恢复原画面。
     * 不填会话名时自动生成 `<app>-<序号>`（序号=同 app 现有标签数+1，撞名则顺延）。
     * machine != null 时该标签连指定机器（多机切换），否则连默认机器（intent 带进来的那台）。
     */
    private fun openTab(app: String, tmuxSession: String?, machine: Machine? = null) {
        val sessionName = tmuxSession?.trim()?.takeIf { it.isNotEmpty() } ?: autoSessionName(app)
        val mHost: String
        val mPort: Int
        val mToken: String
        val mName: String
        if (machine == null) {
            mHost = host; mPort = port; mToken = token; mName = defaultMachineName()
        } else {
            mHost = machine.host
            mPort = machine.port.toIntOrNull() ?: 7681
            mToken = machine.token
            mName = machine.name
        }
        // 标签标题只用会话名(机器归属由机器栏表达,不再带 "机器名/" 前缀);
        // 若用户曾"重命名页签",用自定义名(prefs 按 机器/会话名 持久化)
        val label = customTabLabel(mName, sessionName) ?: sessionName
        val tab = Tab(nextTabId++, label, app, sessionName, null, mHost, mPort, mToken, mName)
        tab.session = createSession(tab)
        tabs.add(tab)
        // 新建的标签归属即用户当前想看的:机器栏选中跟随到该机(空态随之解除)
        selectedMachine = tab.machineName
        refreshMachinesBar()
        updateKeepAlive()
        switchToTab(tab)
    }

    /**
     * 新建 noVNC 标签(桌面或浏览器):先弹 VNC 密码对话框(EditText 带眼睛切换 + 记住密码勾选,
     * 默认回填上次密码),确认后用带 password 参数的 URL 直连,免在 noVNC 页面二次输入。
     * vncPort: 6080=整桌面(Xvfb :0), 6081=远程 Chrome 浏览器(Xvfb :99)。
     */
    private fun openDesktopTab(machine: Machine? = null, vncPort: Int = 6080, label: String = "桌面") {
        val mHost: String
        val mPort: Int
        val mToken: String
        val mName: String
        if (machine == null) {
            mHost = host; mPort = port; mToken = token; mName = defaultMachineName()
        } else {
            mHost = machine.host
            mPort = machine.port.toIntOrNull() ?: 7681
            mToken = machine.token
            mName = machine.name
        }
        showVncPasswordDialog { password ->
            val tab = Tab(nextTabId++, label, "desktop", null, null, mHost, mPort, mToken, mName, desktop = true)
            tab.vncPort = vncPort
            tab.stateText = "noVNC :$vncPort"
            tab.desktopUrl = "http://$mHost:$vncPort/vnc.html?autoconnect=true&resize=off" +
                "&password=" + URLEncoder.encode(password, Charsets.UTF_8.name())
            tabs.add(tab)
            selectedMachine = tab.machineName
            refreshMachinesBar()
            updateKeepAlive()
            switchToTab(tab)
        }
    }

    /** VNC 密码输入对话框:密码框(眼睛切换明文/密文) + 记住密码勾选。 */
    private fun showVncPasswordDialog(onConfirm: (String) -> Unit) {
        val prefs = inboxPrefs()
        val saved = prefs.getString("vnc_password", "").orEmpty()
        val til = com.google.android.material.textfield.TextInputLayout(this).apply {
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
            hint = getString(R.string.vnc_password_hint)
        }
        val edit = com.google.android.material.textfield.TextInputEditText(til.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(saved)
            setSelection(saved.length)
        }
        til.addView(edit)
        val remember = android.widget.CheckBox(this).apply {
            text = getString(R.string.vnc_password_remember)
            isChecked = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
            addView(til)
            addView(remember)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vnc_password_title)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_connect) { _, _ ->
                val pwd = edit.text?.toString().orEmpty()
                if (remember.isChecked) {
                    prefs.edit().putString("vnc_password", pwd).apply()
                }
                onConfirm(pwd)
            }
            .show()
    }

    /** 默认机器（intent 带进来的那台）的显示名：机器列表里查到就用列表名，否则用 host。 */
    private fun defaultMachineName(): String =
        MachineStore.load(getSharedPreferences(MachineStore.PREFS_NAME, MODE_PRIVATE))
            .firstOrNull { it.host == host }?.name ?: host

    // ---------- 机器栏(顶部,按机器过滤标签显示) ----------

    /** 机器栏条目:默认机器 + 已存机器列表 + 现有标签用到的机器名,按名去重。value 为机器配置(默认机器可能查不到,为 null)。 */
    private fun machineBarEntries(): List<Pair<String, Machine?>> {
        val stored = MachineStore.load(getSharedPreferences(MachineStore.PREFS_NAME, MODE_PRIVATE))
        val entries = LinkedHashMap<String, Machine?>()
        val defName = defaultMachineName()
        entries[defName] = stored.firstOrNull { it.host == host }
        for (m in stored) entries.putIfAbsent(m.name, m)
        for (tab in tabs) entries.putIfAbsent(tab.machineName, null)
        return entries.entries.map { it.key to it.value }
    }

    private fun refreshMachinesBar() {
        machinesContainer.removeAllViews()
        val normalBg = ContextCompat.getColorStateList(this, R.color.key_background)
        val selectedText = ContextCompat.getColor(this, android.R.color.white)
        val normalText = ContextCompat.getColor(this, R.color.status_text)
        for ((name, _) in machineBarEntries()) {
            val selected = name == selectedMachine
            machinesContainer.addView(Button(this).apply {
                text = name
                isAllCaps = false
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(12), 0, dp(12), 0)
                // 选中 = 亮底 + 2dp primary 描边;未选中 = 深灰 + 降对比文字
                if (selected) {
                    setBackgroundResource(R.drawable.tab_bg_selected)
                    setTextColor(selectedText)
                } else {
                    setBackgroundResource(R.drawable.key_btn_bg)
                    backgroundTintList = normalBg
                    setTextColor(normalText)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)
                ).apply { marginEnd = dp(4) }
                setOnClickListener { selectMachine(name) }
            })
        }
    }

    /** 选中机器:标签栏只显示该机标签,currentTab 切到该机第一个;没有则空态。 */
    private fun selectMachine(name: String) {
        if (selectedMachine == name) return
        selectedMachine = name
        refreshMachinesBar()
        val first = tabs.firstOrNull { it.machineName == name }
        if (first != null) switchToTab(first) else showEmptyState()
    }

    /** 桌面标签的 WebView:懒创建一次后只 show/hide(切后台/切标签不重载页面)。 */
    private fun showDesktop(tab: Tab, container: PanInterceptLayout) {
        for (t in tabs) t.webView?.visibility = View.GONE
        var wv = tab.webView
        if (wv == null) {
            wv = android.webkit.WebView(this).apply {
                // 高度钉死为创建时容器高:键盘弹收时容器收缩也不改变 WebView 尺寸,
                // 浏览器画面不缩放不位移(底部被键盘遮住),显示不全靠单指拖拽平移
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    container.height
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true // noVNC 用 localStorage 记 VNC 密码等设置
                settings.loadWithOverviewMode = false
                // 双指捏合整页缩放(内建);单指触摸仍透传给 noVNC 当鼠标
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                // 默认视野:桌面 URL 用 resize=off(画布原生分辨率,5120x1440 双屏),
                // 初始缩放让可视宽度约 2560 CSS px(半宽),横纵滚动定位;
                // 滚动条常驻,方便用户知道当前位置
                settings.useWideViewPort = false
                setInitialScale(desktopInitialScalePct(tab, container))
                setScrollbarFadingEnabled(false)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                webViewClient = android.webkit.WebViewClient() // 链接都在本 WebView 内打开
                loadUrl(tab.desktopUrl ?: "http://${tab.host}:6080/vnc.html?autoconnect=true&resize=off")
            }
            container.addView(wv)
            tab.webView = wv
        }
        wv.visibility = View.VISIBLE
        addDesktopSliders(container, tab) // 底部水平 + 右侧垂直视野滑块
    }

    /** 各桌面标签的 CSS translate 平移量(切标签保留)。 */
    private val desktopPanOffsets = HashMap<Int, Pair<Float, Float>>()

    /** 单指拖拽平移:物理 px → CSS px(除以 WebView 当前缩放),累计后注入 transform。 */
    private fun panDesktop(tab: Tab, dx: Float, dy: Float) {
        val wv = tab.webView ?: return
        val scale = if (wv.scale > 0f) wv.scale else 1f
        val (ox, oy) = desktopPanOffsets[tab.id] ?: (0f to 0f)
        applyDesktopTransform(tab, ox + dx / scale, oy + dy / scale)
    }

    /** 统一写平移量并注入 CSS transform(拖拽和滑块共用)。 */
    private fun applyDesktopTransform(tab: Tab, nx: Float, ny: Float) {
        val wv = tab.webView ?: return
        desktopPanOffsets[tab.id] = nx to ny
        wv.evaluateJavascript(
            "(()=>{const el=document.getElementById('noVNC_container')||document.body;" +
            "el.style.transform='translate(${nx}px,${ny}px)';return 0;})()", null
        )
    }

    // ---------- 桌面视野滑块(底部水平 + 右侧垂直,半透明,随缩放联动) ----------

    private var sliderSyncToken = 0

    /** 为桌面标签在容器上加两条视野滑块;重复调用先清掉旧的。 */
    private fun addDesktopSliders(container: PanInterceptLayout, tab: Tab) {
        val old = (0 until container.childCount).mapNotNull { i ->
            container.getChildAt(i).takeIf { it.tag == "desktop_slider" }
        }
        old.forEach { container.removeView(it) }

        val hSeek = android.widget.SeekBar(this).apply {
            tag = "desktop_slider"
            alpha = 0.3f
            max = 1
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(18),
                android.view.Gravity.BOTTOM
            )
        }
        val vSeek = android.widget.SeekBar(this).apply {
            tag = "desktop_slider"
            alpha = 0.3f
            max = 1
            rotation = 270f // 竖向滑块
            layoutParams = android.widget.FrameLayout.LayoutParams(
                dp(220), dp(18),
                android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            )
        }
        val listener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val (ox, oy) = desktopPanOffsets[tab.id] ?: (0f to 0f)
                if (sb === hSeek) applyDesktopTransform(tab, -progress.toFloat(), oy)
                else applyDesktopTransform(tab, ox, -progress.toFloat())
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        }
        hSeek.setOnSeekBarChangeListener(listener)
        vSeek.setOnSeekBarChangeListener(listener)
        container.addView(hSeek)
        container.addView(vSeek)

        // 周期性把"当前缩放下的可视溢出"映射到滑块行程,并同步滑块位置
        val token = ++sliderSyncToken
        val sync = object : Runnable {
            override fun run() {
                if (token != sliderSyncToken || currentTab !== tab) return
                val wv = tab.webView ?: return
                val scale = if (wv.scale > 0f) wv.scale else 1f
                val dm = resources.displayMetrics
                val remoteW = if (tab.vncPort == 6081) 1080f else 5120f
                val remoteH = if (tab.vncPort == 6081) 1920f else 1440f
                // 可视 CSS 尺寸 = 物理 px / (scale × density)
                val visW = wv.width / (scale * dm.density)
                val visH = wv.height / (scale * dm.density)
                val overX = (remoteW - visW).coerceAtLeast(0f)
                val overY = (remoteH - visH).coerceAtLeast(0f)
                hSeek.max = overX.toInt().coerceAtLeast(1)
                vSeek.max = overY.toInt().coerceAtLeast(1)
                val (ox, oy) = desktopPanOffsets[tab.id] ?: (0f to 0f)
                val cx = ox.coerceIn(-overX, 0f)
                val cy = oy.coerceIn(-overY, 0f)
                if (cx != ox || cy != oy) applyDesktopTransform(tab, cx, cy)
                hSeek.progress = (-cx).toInt().coerceIn(0, hSeek.max)
                vSeek.progress = (-cy).toInt().coerceIn(0, vSeek.max)
                container.postDelayed(this, 500)
            }
        }
        container.post(sync)
    }
    /** 初始缩放百分比:center-crop——宽/高两向各算缩放比取较大者,短边贴满,
     *  长边超出部分靠滑块/拖拽看(不再 center-inside 留粗黑边)。 */
    private fun desktopInitialScalePct(tab: Tab, container: android.view.View): Int {
        val remoteW = if (tab.vncPort == 6081) 1080f else 5120f
        val remoteH = if (tab.vncPort == 6081) 1920f else 1440f
        val dm = resources.displayMetrics
        // WebView 的 SCALE_NORMAL(100) 下 CSS px = 物理 px / density
        val cssW = container.width / dm.density
        val cssH = container.height / dm.density
        return (maxOf(cssW / remoteW, cssH / remoteH) * 100).toInt().coerceIn(10, 150)
    }

    // ---------- 标签长按菜单(重命名页签/关闭页签/取消) ----------

    /** 自定义标签名持久化(prefs JSON: "机器名/会话名" → 自定义显示名)。 */
    private fun customTabLabel(machine: String, session: String): String? {
        val raw = getSharedPreferences(MachineStore.PREFS_NAME, MODE_PRIVATE)
            .getString("tab_custom_labels", null) ?: return null
        return runCatching { JSONObject(raw).optString("$machine/$session").ifEmpty { null } }.getOrNull()
    }

    private fun saveCustomTabLabel(machine: String, session: String, label: String) {
        val prefs = getSharedPreferences(MachineStore.PREFS_NAME, MODE_PRIVATE)
        val raw = prefs.getString("tab_custom_labels", null)
        val obj = runCatching { JSONObject(raw ?: "{}") }.getOrElse { JSONObject() }
        obj.put("$machine/$session", label)
        prefs.edit().putString("tab_custom_labels", obj.toString()).apply()
    }

    /** 长按标签:重命名页签 / 关闭页签 / 取消。 */
    private fun showTabMenu(tab: Tab) {
        val items = arrayOf(getString(R.string.tab_menu_rename), getString(R.string.tab_menu_close), getString(R.string.action_cancel))
        MaterialAlertDialogBuilder(this)
            .setTitle(tab.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenameTabDialog(tab)
                    1 -> confirmCloseTab(tab)
                    else -> Unit // 取消
                }
            }
            .show()
    }

    /** 重命名页签:只改 App 侧显示名(不动服务端 tmux 会话名),按 机器/会话名 持久化。 */
    private fun showRenameTabDialog(tab: Tab) {
        val edit = android.widget.EditText(this).apply {
            setText(tab.label)
            setSelection(tab.label.length)
            hint = getString(R.string.hint_tab_name)
        }
        val pad = dp(20)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tab_menu_rename)
            .setView(edit, pad, pad / 2, pad, 0)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = edit.text.toString().trim()
            if (name.isEmpty()) {
                edit.error = getString(R.string.error_tab_name_required)
                return@setOnClickListener
            }
            tab.label = name
            tab.tmuxSession?.let { saveCustomTabLabel(tab.machineName, it, name) }
            refreshTabBar()
            refreshStatus()
            dialog.dismiss()
        }
    }

    /** 当前选中机器可见的标签(标签栏只画这些)。 */
    private fun visibleTabs(): List<Tab> = tabs.filter { it.machineName == selectedMachine }

    /** 选中机器没有标签时的空态:藏终端区(后台连接不动),提示去点 +。 */
    private fun showEmptyState() {
        currentTab = null
        terminalView.visibility = View.INVISIBLE
        for (t in tabs) t.webView?.visibility = View.GONE
        emptyHint.visibility = View.VISIBLE
        refreshTabBar()
        refreshStatus()
    }

    private fun autoSessionName(app: String): String {
        // 避让三类已占用名:本地标签、服务端 tmux 现有会话(/sessions 刚拉到的)。
        // 必须避让服务端已有名:tmux new-session -A -s <名> 是"存在即接入",
        // 撞名会把"全新 shell"变成接入某个历史会话(用户的 codebuddy 旧会话就是这么被误接的)。
        val used = tabs.mapNotNull { it.tmuxSession }.toHashSet()
        used += lastKnownTmuxSessions
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
        // 每个标签用自己的三元组连接(多机标签各连各的机器)
        return RemoteTerminalSession(tab.host, tab.port, tab.app, tab.tmuxSession, tab.token, this, this)
    }

    /** 切换标签：TerminalView 重新 attach；后台标签的连接与 emulator 保持不动。 */
    private fun switchToTab(tab: Tab) {
        // 从空态恢复:终端区显示回来
        emptyHint.visibility = View.GONE
        // 桌面标签:显示自己的 WebView;TerminalView 用 alpha=0 隐藏但保持 VISIBLE
        // (INVISIBLE/GONE 会丢 IME 输入连接,桌面标签的"键盘"按钮就弹不出软键盘)
        val container = findViewById<PanInterceptLayout>(R.id.terminal_container)
        if (tab.desktop) {
            terminalView.visibility = View.VISIBLE
            terminalView.alpha = 0f
            showDesktop(tab, container)
            // 桌面手势:单指拖拽=平移(CSS translate 注入,noVNC 的 overflow:hidden 下
            // WebView 原生滚动无效),双指捏合=缩放(WebView 内建),单击=noVNC 鼠标
            container.panTarget = tab.webView
            container.onPan = { dx, dy -> panDesktop(tab, dx, dy) }
            // 桌面标签上单击(noVNC 点击照常放行)时弹本机键盘:
            // 文本经 TerminalView 的 IME 输入连接 → onCodePoint → JS 注入 noVNC
            container.onSingleTap = {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                terminalView.requestFocus()
                imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            }
            container.panEnabled = true
        } else {
            container.panEnabled = false
            container.panTarget = null
            container.onPan = null
            container.onSingleTap = null
            for (t in tabs) t.webView?.visibility = View.GONE
            terminalView.alpha = 1f
            terminalView.visibility = View.VISIBLE
        }
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
        tab.webView?.let { wv ->
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        tab.webView = null
        tabs.remove(tab)
        updateKeepAlive()
        if (tabs.isEmpty()) {
            finish() // 防御：正常路径 confirmCloseTab 会拦住最后一个标签
            return
        }
        if (currentTab === tab) {
            currentTab = null
            // 机器栏过滤下:优先切到当前选中机器的第一个可见标签,没有则空态
            val first = tabs.firstOrNull { it.machineName == selectedMachine }
            if (first != null) switchToTab(first) else showEmptyState()
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
        val machineSpinner = view.findViewById<android.widget.Spinner>(R.id.dialog_spinner_machine)

        // 机器下拉:第一项"当前机器"(intent 带进来的连接),后面是登录页存的机器列表
        val prefs = getSharedPreferences(MachineStore.PREFS_NAME, MODE_PRIVATE)
        val machines = MachineStore.load(prefs)
        val spinnerNames = mutableListOf(getString(R.string.machine_current, defaultMachineName()))
        spinnerNames += machines.map { it.name }
        val spinnerAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, spinnerNames)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        machineSpinner.adapter = spinnerAdapter
        // 机器下拉默认跟随机器栏当前选中(选中的是默认机器则停在第 0 项"当前机器")
        val followIdx = machines.indexOfFirst { it.name == selectedMachine }
        when {
            followIdx >= 0 -> machineSpinner.setSelection(followIdx + 1)
            else -> {
                val hostIdx = machines.indexOfFirst { it.host == host }
                if (hostIdx >= 0) machineSpinner.setSelection(hostIdx + 1)
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_new_tab_title)
            .setView(view)
            .setPositiveButton(R.string.action_create, null)
            .setNegativeButton(R.string.action_cancel, null)
            .show()

        // 选中的机器(null = 当前机器)
        fun selectedMachine(): Machine? {
            val pos = machineSpinner.selectedItemPosition
            return if (pos in 1..machines.size) machines[pos - 1] else null
        }

        /** 最近一次 /sessions 拉到的 (name, attached) 列表(shell 接续选择用)。 */
        var lastSessions = listOf<Pair<String, Boolean>>()

        // 接入已有会话:按选中机器异步拉它的 tmux 会话列表,切换机器时重新拉;
        // 请求失败/为空只在区域内提示"无可用会话",不阻塞手动新建
        fun refetchSessions() {
            sessionsContainer.removeAllViews()
            sessionsStatus.visibility = View.VISIBLE
            sessionsStatus.setText(R.string.sessions_loading)
            val m = selectedMachine()
            val h = m?.host ?: host
            val p = m?.port?.toIntOrNull() ?: port
            val t = m?.token ?: token
            // 更新 refetchSessions 回调处,同步维护"服务端现有会话名"集合(供 autoSessionName 避让)
        fetchTmuxSessions(h, p, t, sessionsStatus, sessionsContainer,
            onListParsed = {
                lastSessions = it
                lastKnownTmuxSessions.clear()
                lastKnownTmuxSessions.addAll(it.map { s -> s.first })
            }) { name ->
                openTab("tmux", name, m)
                dialog.dismiss()
            }
        }
        machineSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) =
                refetchSessions()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        // 注意:不再手动 refetchSessions()——给 Spinner 赋 listener 会立即以当前选中项回调一次
        // (v1.4.6 及之前手动调一次 + 回调一次 = 并发两趟拉取,清空后各追加一遍 = 列表重复两遍)

        // 手动处理确定按钮，校验失败时不关闭对话框
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            when (radioApp.checkedRadioButtonId) {
                R.id.dialog_radio_desktop -> {
                    // 桌面标签:无需会话名,直接开 noVNC WebView 标签
                    openDesktopTab(selectedMachine(), 6080, getString(R.string.tab_label_desktop))
                    dialog.dismiss()
                    return@setOnClickListener
                }
                R.id.dialog_radio_browser -> {
                    // 浏览器标签:同一台机器的 6081 端口(独立 Xvfb :99 上跑全屏 Chrome)
                    openDesktopTab(selectedMachine(), 6081, getString(R.string.tab_label_browser))
                    dialog.dismiss()
                    return@setOnClickListener
                }
            }
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
            // 新建 shell 且没填会话名时:若有"最近活跃但未连接"的 shell-* 会话,
            // 给用户两个选择——接续它,或全新 shell
            if (app == "shell" && typed.isEmpty()) {
                val resume = lastSessions.firstOrNull { it.first.startsWith("shell-") && !it.second }
                if (resume != null) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.shell_new_title)
                        .setPositiveButton(getString(R.string.shell_resume_pick, resume.first)) { _, _ ->
                            openTab("tmux", resume.first, selectedMachine())
                            dialog.dismiss()
                        }
                        .setNegativeButton(R.string.shell_fresh) { _, _ ->
                            openTab("shell", null, selectedMachine())
                            dialog.dismiss()
                        }
                        .show()
                    return@setOnClickListener
                }
            }
            openTab(app, typed.ifEmpty { null }, selectedMachine())
            dialog.dismiss()
        }
    }

    /** GET /sessions 列出指定机器的活 tmux 会话；每项一个按钮，点按即开标签接入（app=tmux + session=名）。 */
    private fun fetchTmuxSessions(
        mHost: String, mPort: Int, mToken: String,
        status: TextView, container: LinearLayout,
        onListParsed: ((List<Pair<String, Boolean>>) -> Unit)? = null,
        onPick: (String) -> Unit
    ) {
        val url = "http://$mHost:$mPort/sessions?token=${URLEncoder.encode(mToken, Charsets.UTF_8.name())}"
        httpClient.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                response.close()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    var added = 0
                    val parsed = ArrayList<Triple<String, Boolean, Long>>()
                    try {
                        val arr = JSONObject(body).getJSONArray("sessions")
                        // 按名去重(服务端/并发拉取都可能给重复项),服务端已按 activity 倒序,取前 6 个
                        val seen = LinkedHashSet<String>()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val name = o.optString("name").trim()
                            if (name.isNotEmpty() && seen.add(name)) {
                                parsed += Triple(name, o.optBoolean("attached", false), o.optLong("activity", 0L))
                            }
                        }
                        for ((name, attached, activity) in parsed.take(6)) {
                            container.addView(Button(this@TerminalActivity).apply {
                                text = buildString {
                                    append(name)
                                    if (activity > 0) append(" · ").append(relativeTime(activity))
                                    if (attached) append(" · 已接入")
                                }
                                isAllCaps = false
                                setOnClickListener { onPick(name) }
                            })
                            added++
                        }
                    } catch (t: Throwable) {
                        // 响应异常视同无会话，下面统一显示提示
                    }
                    onListParsed?.invoke(parsed.map { it.first to it.second })
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

    /** epoch 秒 → "x 分钟/小时/天前活跃"。 */
    private fun relativeTime(epochSec: Long): String {
        val diff = System.currentTimeMillis() / 1000 - epochSec
        return when {
            diff < 60 -> "刚刚活跃"
            diff < 3600 -> "${diff / 60} 分钟前活跃"
            diff < 86400 -> "${diff / 3600} 小时前活跃"
            else -> "${diff / 86400} 天前活跃"
        }
    }

    /** 标签栏重绘：只画当前选中机器的标签；当前标签 = 亮底+primary 描边；已断开加 ✕、重连中加 ↻ 前缀。 */
    private fun refreshTabBar() {
        tabsContainer.removeAllViews()
        val normalBg = ContextCompat.getColorStateList(this, R.color.key_background)
        val selectedText = ContextCompat.getColor(this, android.R.color.white)
        val normalText = ContextCompat.getColor(this, R.color.status_text)
        val deadText = ContextCompat.getColor(this, R.color.tab_dead_text)
        for (tab in visibleTabs()) {
            val selected = tab === currentTab
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
                if (selected) {
                    setBackgroundResource(R.drawable.tab_bg_selected)
                } else {
                    setBackgroundResource(R.drawable.key_btn_bg)
                    backgroundTintList = normalBg
                }
                setTextColor(
                    when {
                        tab.dead -> deadText
                        selected -> selectedText
                        else -> normalText
                    }
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)
                ).apply { marginEnd = dp(4) }
                setOnClickListener { switchToTab(tab) }
                setOnLongClickListener {
                    showTabMenu(tab)
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
        tab.lastState = state
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

    // ---------- 文件上传(⬆:系统文件选择器 → POST /upload → ~/phone-uploads/) ----------

    /** 系统文件选择器(单选,选中即传;多个文件可重复点 ⬆)。 */
    private val uploadPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { uploadFiles(listOf(it)) }
    }

    /** 逐个上传:读内容 → raw body POST,文件名走 X-Filename。 */
    private fun uploadFiles(uris: List<android.net.Uri>) {
        // 用当前标签的机器(没有则默认机)
        val tab = currentTab
        val h = tab?.host ?: host
        val p = tab?.port ?: port
        val t = tab?.token ?: token
        for (uri in uris) {
            Thread {
                try {
                    val name = queryDisplayName(uri) ?: "upload-${System.currentTimeMillis()}"
                    val bytes = contentResolver.openInputStream(uri)!!.readBytes()
                    val url = "http://$h:$p/upload?token=${URLEncoder.encode(t, Charsets.UTF_8.name())}"
                    val body = okhttp3.RequestBody.create(null, bytes)
                    val req = Request.Builder().url(url).post(body)
                        .header("X-Filename", URLEncoder.encode(name, Charsets.UTF_8.name()))
                        .header("Content-Type", "application/octet-stream")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val respBody = resp.body?.string().orEmpty()
                        runOnUiThread {
                            if (resp.isSuccessful) {
                                Toast.makeText(this, "已上传到远端 ~/phone-uploads/$name", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this, getString(R.string.upload_failed, name, "HTTP ${resp.code}: $respBody"), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.upload_failed, uri.lastPathSegment ?: "?", e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    /** 从 content:// URI 查显示名(查不到返回 null)。 */
    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    // ---------- 文件推送（服务端 watch ~/phone-push/ 的广播） ----------

    override fun onFilePushed(session: RemoteTerminalSession, name: String, size: Long) {
        if (isFinishing || isDestroyed) return
        Log.i("TerminalActivity", "收到文件推送: $name ($size B)")
        // 时间窗去重(见 FileInbox 注释;广播会送达本机每个标签的连接)
        val now = System.currentTimeMillis()
        val key = "$name|$size"
        val last = pushedFileKeys[key]
        if (last != null && now - last < PUSH_DEDUPE_WINDOW_MS) return
        pushedFileKeys[key] = now
        val it = pushedFileKeys.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > 60_000L) it.remove()
        }
        // Toast 即时感知 + 记入收件箱(可随时回看/下载);不弹模态对话框打断操作;
        // 后台时仍发系统通知
        Toast.makeText(this, getString(R.string.file_pushed_toast, name), Toast.LENGTH_LONG).show()
        val tab = tabs.firstOrNull { it.session === session }
        FileInbox.add(
            inboxPrefs(),
            InboxItem(
                name = name, size = size, time = now,
                host = tab?.host ?: host, port = tab?.port ?: port, token = tab?.token ?: token,
                machine = tab?.machineName ?: defaultMachineName(), downloaded = false,
            )
        )
        refreshInboxBadge()
        if (!inForeground) {
            postFilePushedNotification(name, size, tab)
        }
    }

    // ---------- 推送收件箱 ----------

    private fun inboxPrefs() = getSharedPreferences(MachineStore.PREFS_NAME, MODE_PRIVATE)

    /** 角标 = 未下载条数;0 时隐藏。 */
    private fun refreshInboxBadge() {
        val n = FileInbox.unreadCount(inboxPrefs())
        if (n <= 0) {
            inboxBadge.visibility = View.GONE
        } else {
            inboxBadge.visibility = View.VISIBLE
            inboxBadge.text = if (n > 99) "99+" else n.toString()
        }
    }

    private fun showInboxDialog() {
        val items = FileInbox.load(inboxPrefs())
        val scroll = android.widget.ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        scroll.addView(list)
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = getString(R.string.inbox_empty)
                setPadding(0, dp(16), 0, dp(16))
            })
        }
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val info = TextView(this).apply {
                text = buildString {
                    append(item.name).append("（").append(formatSize(item.size)).append("）\n")
                    append(fmt.format(java.util.Date(item.time))).append(" · ").append(item.machine)
                    // 已下载的条目把存放路径也显示出来
                    item.downloadedPath?.let { append('\n').append(it) }
                }
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(info)
            if (item.downloaded) {
                row.addView(TextView(this).apply {
                    text = getString(R.string.inbox_downloaded)
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@TerminalActivity, R.color.state_connected))
                })
            } else {
                val btn = Button(this).apply {
                    text = getString(R.string.action_download)
                    textSize = 12f
                }
                btn.setOnClickListener {
                    startInboxDownload(item, row, info, btn)
                }
                row.addView(btn)
            }
            list.addView(row)
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.inbox_title)
            .setView(scroll)
            .setNegativeButton(R.string.action_cancel, null)
        if (items.isNotEmpty()) {
            builder.setNeutralButton(R.string.inbox_clear) { _, _ ->
                FileInbox.clear(inboxPrefs())
                refreshInboxBadge()
            }
        }
        builder.show()
    }

    /** 推送下载统一存放子目录(Download/awesome-me/)。 */
    private fun downloadDestPath(name: String): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path + "/awesome-me/" + name

    /** 进行中的下载:轮询进度要更新的行视图(对话框关掉后视图失效,轮询仍继续到完成)。 */
    private val activeDownloads = HashMap<Long, Pair<TextView?, android.widget.ProgressBar?>>()
    private val downloadHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 收件箱条目下载:入队后把"下载"按钮换成进度条,每 500ms 轮询 DownloadManager
     * 更新已下载/总字节;成功 → 标记已下载并显示完整路径;失败 → 恢复按钮。
     * 轮询挂在 Activity 级 handler 上,对话框中途关闭也不丢完成标记。
     */
    private fun startInboxDownload(item: InboxItem, row: LinearLayout, info: TextView, btn: Button) {
        val encodedName = URLEncoder.encode(item.name, Charsets.UTF_8.name()).replace("+", "%20")
        val url = "http://${item.host}:${item.port}/files/$encodedName" +
            "?token=${URLEncoder.encode(item.token, Charsets.UTF_8.name())}"
        val destPath = downloadDestPath(item.name)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(item.name)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "awesome-me/${item.name}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = runCatching { dm.enqueue(request) }.getOrElse {
            Toast.makeText(this, getString(R.string.download_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
            return
        }
        // 按钮位置换成进度条
        val idx = row.indexOfChild(btn)
        row.removeView(btn)
        val progressWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        progressWrap.addView(bar)
        row.addView(progressWrap, idx)
        activeDownloads[id] = info to bar
        pollInboxDownload(dm, id, item, destPath)
    }

    private fun pollInboxDownload(dm: DownloadManager, id: Long, item: InboxItem, destPath: String) {
        val (info, bar) = activeDownloads[id] ?: (null to null)
        val c = dm.query(DownloadManager.Query().setFilterById(id))
        if (c != null && c.moveToFirst()) {
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            c.close()
            // 进度条与百分比文本(总行数优先用服务端 content-length,退化为广播里的 size)
            val denom = if (total > 0) total else item.size
            if (denom > 0) {
                bar?.progress = ((soFar * 100) / denom).toInt().coerceIn(0, 100)
            } else {
                bar?.isIndeterminate = true
            }
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    activeDownloads.remove(id)
                    FileInbox.markDownloaded(inboxPrefs(), item, destPath)
                    refreshInboxBadge()
                    info?.let {
                        it.append(" · ${getString(R.string.inbox_downloaded)}\n$destPath")
                        // 进度条换成绿色"已下载"
                        val parent = bar?.parent as? LinearLayout
                        parent?.removeAllViews()
                        parent?.addView(TextView(this).apply {
                            text = getString(R.string.inbox_downloaded)
                            textSize = 12f
                            setTextColor(ContextCompat.getColor(this@TerminalActivity, R.color.state_connected))
                        })
                    }
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    activeDownloads.remove(id)
                    val parent = bar?.parent as? LinearLayout
                    parent?.removeAllViews()
                    parent?.addView(Button(this).apply {
                        text = getString(R.string.action_download)
                        textSize = 12f
                        setOnClickListener { v ->
                            val row = v.parent.parent as? LinearLayout ?: return@setOnClickListener
                            startInboxDownload(item, row, info!!, v as Button)
                        }
                    })
                    Toast.makeText(this, getString(R.string.download_failed, "status=$status"), Toast.LENGTH_LONG).show()
                    return
                }
                else -> { // PENDING/RUNNING/PAUSED:继续轮询
                }
            }
        } else {
            c?.close()
        }
        downloadHandler.postDelayed({ pollInboxDownload(dm, id, item, destPath) }, 500)
    }

    private fun formatSize(size: Long): String = when {
        size < 0 -> "?"
        size < 1024 -> "${size}B"
        size < 1024 * 1024 -> "${size / 1024}KB"
        else -> "%.1fMB".format(size / 1024f / 1024f)
    }

    /** 拼 /files 下载 URL（用来源标签的机器）；URLEncoder 把空格编成 '+'，路径段里 '+' 是字面量，需换成 %20。 */
    private fun fileDownloadUrl(name: String, tab: Tab?): String {
        val h = tab?.host ?: host
        val p = tab?.port ?: port
        val t = tab?.token ?: token
        val encodedName = URLEncoder.encode(name, Charsets.UTF_8.name()).replace("+", "%20")
        return "http://$h:$p/files/$encodedName?token=${URLEncoder.encode(t, Charsets.UTF_8.name())}"
    }

    /** App 在后台时的提示：系统通知，点击经 DownloadFileReceiver 触发同一个下载。 */
    private fun postFilePushedNotification(name: String, size: Long, tab: Tab?) {
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
            putExtra(DownloadFileReceiver.EXTRA_URL, fileDownloadUrl(name, tab))
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
        // 底栏功能键统一走 sendBarKey/sendArrowKeyWithMods:粘滞 ALT/CTRL/SHIFT 对其生效
        // (v1.3.7 前这些键直发字节,绕过 onCodePoint,ALT+⏎ 等组合根本到不了被控端);
        // 桌面标签(noVNC)时改走 JS 注入(见 sendDesktopKeysym)。
        findViewById<Button>(R.id.key_enter).setOnClickListener {
            // ⏎:裸 0x0D;SHIFT+⏎ = CSI-u(实测 kimi 换行);CTRL+⏎ = C-j(实测也换行)
            sendKeyOrDesktop(byteArrayOf(0x0D), XK_ENTER,
                ctrl = byteArrayOf(0x0A), shift = "\u001B[13;2u".toByteArray())
        }
        findViewById<Button>(R.id.key_esc).setOnClickListener {
            sendKeyOrDesktop(byteArrayOf(0x1B), XK_ESCAPE)
        }
        findViewById<Button>(R.id.key_up).setOnClickListener { sendArrowKeyWithMods(KeyEvent.KEYCODE_DPAD_UP) }
        findViewById<Button>(R.id.key_down).setOnClickListener { sendArrowKeyWithMods(KeyEvent.KEYCODE_DPAD_DOWN) }
        findViewById<Button>(R.id.key_backspace).setOnClickListener {
            sendKeyOrDesktop(byteArrayOf(0x7F), XK_BACKSPACE, ctrl = byteArrayOf(0x08))
        }
        findViewById<Button>(R.id.key_left).setOnClickListener { sendArrowKeyWithMods(KeyEvent.KEYCODE_DPAD_LEFT) }
        findViewById<Button>(R.id.key_right).setOnClickListener { sendArrowKeyWithMods(KeyEvent.KEYCODE_DPAD_RIGHT) }
        findViewById<Button>(R.id.key_tab).setOnClickListener {
            sendKeyOrDesktop(byteArrayOf(0x09), XK_TAB)
        }
        // F1/F2 = SS3 序列(Hacker's Keyboard 风格);桌面标签注入对应 keysym
        findViewById<Button>(R.id.key_f1).setOnClickListener {
            sendKeyOrDesktop("\u001BOP".toByteArray(), XK_F1)
        }
        findViewById<Button>(R.id.key_f2).setOnClickListener {
            sendKeyOrDesktop("\u001BOQ".toByteArray(), XK_F2)
        }
        // CTRL/SHIFT/ALT 三个"粘滞"修饰键:点一下点亮,下一个字符带修饰;再点取消。
        // CTRL 走 Termux 默认控制字符映射(readControlKey);SHIFT/ALT 在 onCodePoint 手工实现。
        ctrlButton.setOnClickListener {
            stickyCtrl = !stickyCtrl
            updateModifierButtons()
        }
        shiftButton = findViewById(R.id.key_shift)
        shiftButton.setOnClickListener {
            stickyShift = !stickyShift
            updateModifierButtons()
        }
        altButton = findViewById(R.id.key_alt)
        altButton.setOnClickListener {
            stickyAlt = !stickyAlt
            updateModifierButtons()
        }
        // Fn 粘滞:点亮后下一次按方向键变 Home/End/PgUp/PgDn,按一次自动熄灭
        fnButton = findViewById(R.id.key_fn)
        fnButton.setOnClickListener {
            stickyFn = !stickyFn
            updateModifierButtons()
        }
        findViewById<Button>(R.id.key_keyboard).setOnClickListener { toggleSoftKeyboard() }
        orientationButton = findViewById(R.id.key_orientation)
        orientationButton.setOnClickListener { toggleOrientation() }
        updateModifierButtons()
    }

    /** 方向键的修饰版:无修饰走 KeyHandler(DECCKM 感知);Fn → Home/End/PgUp/PgDn;
     *  CTRL → CSI 1;5X(词移动);ALT 由 sendBarKey 前置;桌面标签走 JS 注入。 */
    private fun sendArrowKeyWithMods(keyCode: Int) {
        if (stickyFn) {
            stickyFn = false
            updateModifierButtons()
            val (seq, keysym) = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> "\u001B[5~".toByteArray() to XK_PRIOR   // PgUp
                KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B[6~".toByteArray() to XK_NEXT  // PgDn
                KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B[1~".toByteArray() to XK_HOME
                else -> "\u001B[4~".toByteArray() to XK_END
            }
            sendKeyOrDesktop(seq, keysym)
            return
        }
        val emulator = terminalView.mEmulator ?: return
        val base = KeyHandler.getCode(
            keyCode, 0, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode
        )?.toByteArray() ?: return
        val (letter, keysym) = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 'A' to XK_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> 'B' to XK_DOWN
            KeyEvent.KEYCODE_DPAD_RIGHT -> 'C' to XK_RIGHT
            else -> 'D' to XK_LEFT
        }
        sendKeyOrDesktop(base, keysym, ctrl = "\u001B[1;5$letter".toByteArray())
    }

    /** 终端走字节流 / 桌面(noVNC)走 JS 注入 的统一入口。 */
    private fun sendKeyOrDesktop(base: ByteArray, domCode: String, ctrl: ByteArray? = null, shift: ByteArray? = null) {
        if (currentTab?.desktop == true) {
            // 桌面标签:修饰键暂不支持组合,直接发基础键(注释说明,避免误以为生效)
            sendDesktopKeysym(domCode)
            return
        }
        sendBarKey(base, ctrl, shift)
    }

    /** TerminalView 的钉住高度（-1 = 尚未量到首次尺寸）。 */
    private var pinnedTermHeight = -1
    private var lastContainerHeight = -1

    /** 容器高度稳定 250ms 后的一次性对齐（每次弹/收只触发一次 resize）。 */
    private var imeHideTime = 0L
    private val imeSettleRunnable = Runnable {
        val lp = terminalView.layoutParams
        lp.height = findViewById<android.widget.FrameLayout>(R.id.terminal_container).height
        terminalView.layoutParams = lp
        // 瞬时切换、不露滚动过程:resize 之后本地 emulator 立即重排(逐行位移 = 用户看到
        // 的"滚动很多行"),随后 200ms 去抖清屏 + 服务端全量重绘(~400ms)。把这段窗口
        // 用 alpha=0 盖住;新内容到达(onTextChanged)且距遮蔽满 400ms 后才恢复——
        // 防 tmux 的部分重绘(状态栏时钟/spinner)提前揭开露出半成品画面。
        imeHideTime = System.currentTimeMillis()
        terminalView.alpha = 0f
        terminalView.postDelayed({
            if (terminalView.alpha == 0f) terminalView.alpha = 1f
        }, 900)
    }

    /**
     * 弹/收键盘"内容不滚动"：TerminalView 的高度始终由这里以固定像素钉住，
     * 键盘动画期间容器逐帧变化都不影响它（底部被键盘遮住/留出，内容原地不动）；
     * 容器高度稳定 250ms 后才把 TerminalView 一次性对齐到容器高 = 每次弹/收
     * 只触发一次 resize/重排，两个方向都不滚。
     * 前版实现为什么没生效：match_parent 让 TerminalView 在同一个 layout pass 里
     * 随容器同步变化，OnGlobalLayoutListener 触发时高度早已变完，"冻结"从未成立。
     */
    private fun setupImeResizeFreeze() {
        val container = findViewById<PanInterceptLayout>(R.id.terminal_container)
        container.viewTreeObserver.addOnGlobalLayoutListener {
            val h = container.height
            if (h <= 0) return@addOnGlobalLayoutListener
            if (pinnedTermHeight < 0 && terminalView.height > 0) {
                // 首次量到尺寸即钉住
                pinnedTermHeight = terminalView.height
                val lp = terminalView.layoutParams
                lp.height = pinnedTermHeight
                terminalView.layoutParams = lp
            }
            if (h != lastContainerHeight) {
                // CLI 闪烁根治:IME 候选栏(Gboard 建议条)随打字反复出现/消失,容器高度
                // 小幅度抖动(~50px),每次抖动都触发 resize→本地重排+清屏重绘+alpha 遮蔽
                // = 用户看到的"边打字边一闪一闪"。只对显著变化(键盘整体弹收,数百 px)
                // 才结算;小于 64dp 的抖动直接忽略,终端尺寸原地不动。
                val significant = lastContainerHeight < 0 || kotlin.math.abs(h - lastContainerHeight) >= dp(64)
                lastContainerHeight = h
                if (significant) {
                    container.removeCallbacks(imeSettleRunnable)
                    container.postDelayed(imeSettleRunnable, 150)
                }
            }
        }
    }

    /**
     * 键栏横滑与系统手势冲突修复：把键栏注册为系统手势排除区（API 29+ 的
     * setSystemGestureExclusionRects，标准做法），否则在键栏上横向滑动翻键时
     * 经常被系统手势导航抢成"切换应用/返回"。
     * 注意：排除矩形必须是 **view 本地坐标** (0,0,w,h)——v1.3.4 误用了
     * getGlobalVisibleRect 的屏幕坐标,真机手势导航下不生效;键盘弹收、旋转
     * 会改变键栏尺寸,故在 OnLayoutChange 里每次重设。
     * 另在布局里给键栏加了 8dp 底部 margin,离开屏幕底缘手势热区一点,双保险。
     */
    private fun setupKeybarGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return // API 29 以下无此 API，空实现
        val keybar = findViewById<LinearLayout>(R.id.keybar_container)
        keybar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
        }
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
     * 向当前桌面标签的 noVNC 注入一次按键。
     * 为什么不走 UI.rfb.sendKey:noVNC 1.3 的 UI 是 ES module 局部变量,window.UI 不存在;
     * 但 noVNC 的键盘监听挂在 canvas 的 keydown/keyup 上(core/input/keyboard.js),
     * 派发自带 code 的 KeyboardEvent 与真实按键等效(合成事件同样触发监听器)。
     */
    private fun sendDesktopKeysym(domCode: String) {
        val wv = currentTab?.webView ?: return
        val js = "(()=>{const cs=document.querySelectorAll('#noVNC_container canvas');" +
            "const c=cs.length?cs[cs.length-1]:document.querySelector('canvas');" +
            "if(!c)return 'nocanvas';" +
            "const o={code:'$domCode',key:'$domCode',bubbles:true,cancelable:true};" +
            "c.dispatchEvent(new KeyboardEvent('keydown',o));" +
            "c.dispatchEvent(new KeyboardEvent('keyup',o));" +
            "return 0;})()"
        wv.evaluateJavascript(js, null)
    }

    /** 软键盘输入的文本码点注入 noVNC(尽量给标准 code,其他字符只给 key)。 */
    private fun sendDesktopChar(codePoint: Int) {
        val ch = String(Character.toChars(codePoint))
        val code = when (ch) {
            " " -> "Space"
            in "a".."z", in "A".."Z" -> "Key" + ch.uppercase()
            in "0".."9" -> "Digit$ch"
            "-" -> "Minus"; "=" -> "Equal"; "," -> "Comma"; "." -> "Period"
            "/" -> "Slash"; "\\" -> "Backslash"; ";" -> "Semicolon"; "'" -> "Quote"
            "[" -> "BracketLeft"; "]" -> "BracketRight"; "`" -> "Backquote"
            else -> ""
        }
        val wv = currentTab?.webView ?: return
        val js = "(()=>{const cs=document.querySelectorAll('#noVNC_container canvas');" +
            "const c=cs.length?cs[cs.length-1]:document.querySelector('canvas');" +
            "if(!c)return 'nocanvas';" +
            "const o={code:'$code',key:'${ch.replace("'", "\\'")}',bubbles:true,cancelable:true};" +
            "c.dispatchEvent(new KeyboardEvent('keydown',o));" +
            "c.dispatchEvent(new KeyboardEvent('keyup',o));" +
            "return 0;})()"
        wv.evaluateJavascript(js, null)
    }

    /**
     * 底栏功能键统一入口:让粘滞修饰键对底栏生效(它们不走 IME 的 onCodePoint)。
     * ALT = 前置 0x1B(Meta);CTRL = 指定控制序列(⏎→C-j、⌫→C-h、方向→CSI 1;5X);
     * SHIFT = 指定序列(目前仅 ⏎→CSI-u 13;2u);其余键 SHIFT 无操作。
     * 任一粘滞修饰生效后全部复位(与 onCodePoint 的一次性约定一致)。
     */
    private fun sendBarKey(base: ByteArray, ctrl: ByteArray? = null, shift: ByteArray? = null) {
        val rs = currentTab?.session ?: return
        var payload = when {
            stickyShift && shift != null -> shift
            stickyCtrl && ctrl != null -> ctrl
            else -> base
        }
        if (stickyAlt) payload = byteArrayOf(0x1B) + payload
        if (stickyCtrl || stickyShift || stickyAlt) {
            stickyCtrl = false
            stickyShift = false
            stickyAlt = false
            updateModifierButtons()
        }
        rs.sendUserInput(payload)
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

    /** CTRL/SHIFT/ALT/Fn 粘滞修饰键统一点亮/熄灭（点亮 = primary 蓝填充 + 白字，更醒目）。 */
    private fun updateModifierButtons() {
        val active = ContextCompat.getColorStateList(this, R.color.ctrl_active)
        val normal = ContextCompat.getColorStateList(this, R.color.key_background)
        val white = ContextCompat.getColor(this, android.R.color.white)
        val keyText = ContextCompat.getColor(this, R.color.key_text)
        for ((btn, on) in listOf(
            ctrlButton to stickyCtrl, shiftButton to stickyShift,
            altButton to stickyAlt, fnButton to stickyFn
        )) {
            btn.backgroundTintList = if (on) active else normal
            btn.setTextColor(if (on) white else keyText)
        }
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
        // 分级着色:标签名亮色;[app]/版本号最暗;host:port/标题次暗;
        // 连接状态按状态着色(已连接绿/重连中·连接中黄/断开·失败红)
        val sb = StringBuilder()
        val spans = ArrayList<Triple<Int, Int, Int>>()
        fun append(s: String, color: Int) {
            val start = sb.length
            sb.append(s)
            spans.add(Triple(start, sb.length, color))
        }
        val bright = ContextCompat.getColor(this, R.color.key_text)
        val dim = ContextCompat.getColor(this, R.color.status_text)
        val dimmer = ContextCompat.getColor(this, R.color.text_dim)
        append(tab.label, bright)
        append(" [${tab.app}]", dimmer)
        if (tab.desktop) {
            append(" @ ${tab.host}:${tab.vncPort}", dim)
        } else {
            append(" @ ${tab.host}:${tab.port}", dim)
        }
        if (tab.stateText.isNotEmpty()) {
            val stateColor = ContextCompat.getColor(
                this, when {
                    tab.reconnecting -> R.color.state_pending
                    tab.dead -> R.color.state_error
                    tab.lastState == RemoteTerminalSession.State.OPEN -> R.color.state_connected
                    else -> R.color.state_pending
                }
            )
            append("  ·  ${tab.stateText}", stateColor)
        }
        termTitle?.takeIf { it.isNotEmpty() }?.let { append("  ·  $it", dim) }
        append("  ·  v${BuildConfig.VERSION_NAME}", dimmer)
        val ss = SpannableString(sb.toString())
        for ((start, end, color) in spans) {
            ss.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        statusText.text = ss
    }

    // ---------- TerminalViewClient ----------

    // 单击按下点记录(dispatchTouchEvent 里"光标行点按定位"用)
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapDownTime = 0L

    /**
     * 触摸分发层拦截"TUI 输入框内的点按定位"。为什么必须在这里而不是 onSingleTapUp:
     * Termux 在 GestureAndScaleRecognizer.onUp(ACTION_UP 时)就把单击翻译成鼠标点击
     * 转发了,等到 onSingleTapConfirmed 回调客户端时点击早已发出,拦不住。
     * 这里在 ACTION_UP 分发给 TerminalView 之前判断:命中"光标行点按"就消费掉
     * (Termux 的 onUp 收不到 → 不会转发鼠标事件),改发方向键挪光标。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapDownX = ev.x
                tapDownY = ev.y
                tapDownTime = ev.eventTime
            }
            MotionEvent.ACTION_UP -> {
                if (tryCursorRowTap(ev)) return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * kimi/claude 标签输入框点按定位光标。
     * 多行版：先检测输入框边框（kimi/claude 的输入框是 Unicode 边框 ╭/│/╰），
     * 点按落在框内任意行 → 先发 ↑/↓（行差），待远端重绘刷新本地光标后再发 ←/→（列差）。
     * 为什么必须先检测边框：空输入框里 ↑ 是历史召回，没确认在框内绝不能发垂直方向键。
     * 检测不到框时退化为 v1.3.7 的"仅同行 ←/→"（无垂直移动，安全）；其他行照旧鼠标转发。
     */
    private fun tryCursorRowTap(up: MotionEvent): Boolean {
        val tab = currentTab ?: return false
        val emulator = terminalView.mEmulator ?: return false
        if (tab.app !in TUI_APPS) return false
        if (!emulator.isMouseTrackingActive) return false // 无 mouse tracking 时单击本就不转发
        // 必须是干净单击:位移小于 touch slop、按下时间短(否则是滚动/长按选择)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        if (abs(up.x - tapDownX) > slop || abs(up.y - tapDownY) > slop) return false
        if (up.eventTime - tapDownTime > CURSOR_TAP_MAX_MS) return false
        // 点按须落在 TerminalView 内(可能在键栏/标签栏上)
        val loc = IntArray(2)
        terminalView.getLocationOnScreen(loc)
        val viewY = up.rawY - loc[1]
        if (viewY < 0 || viewY > terminalView.height || emulator.mRows <= 0) return false
        val lineSpacing = terminalView.height / emulator.mRows
        if (lineSpacing <= 0) return false
        val row = (viewY / lineSpacing).toInt() + topRowOffset()
        val colWidth = terminalView.width.toFloat() / emulator.mColumns
        if (colWidth <= 0) return false
        val col = (up.x / colWidth).toInt()

        val box = detectInputBox(emulator)
        if (box != null && row in box.contentTop..box.contentBottom) {
            val targetCol = col.coerceIn(box.leftCol + 1, box.rightCol - 1)
            val dRow = row - emulator.cursorRow
            if (dRow != 0) {
                val key = if (dRow < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
                repeat(minOf(abs(dRow), 60)) { sendArrowKey(key) }
                // 垂直移动后本地光标位置要等远端重绘才刷新,列移动延后补
                mainHandler.postDelayed({ sendHorizontalTo(emulator, targetCol) }, 150)
            } else {
                sendHorizontalTo(emulator, targetCol)
            }
            showIme()
            return true
        }
        if (row == emulator.cursorRow) {
            // 退化路径(无边框/检测失败):只允许同行水平移动,绝不发垂直键
            sendHorizontalTo(emulator, col)
            showIme()
            return true
        }
        return false // 其他行:放行,Termux 手势层照常转发鼠标点击(TUI 点选)
    }

    /** 按列差发 ←/→ 把光标挪到 targetCol。 */
    private fun sendHorizontalTo(emulator: com.termux.terminal.TerminalEmulator, targetCol: Int) {
        if (isFinishing || isDestroyed) return
        val dCol = targetCol - emulator.cursorCol
        if (dCol == 0) return
        val key = if (dCol < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(minOf(abs(dCol), 100)) { sendArrowKey(key) }
    }

    private fun showIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    /** 输入框(Unicode 边框)检测结果:内容行范围 + 左右边框列。 */
    private class InputBox(val contentTop: Int, val contentBottom: Int, val leftCol: Int, val rightCol: Int)

    /** 检测缓存:(cursorRow, cursorCol, box);光标一移动即失效重测。 */
    private var boxCache: Triple<Int, Int, InputBox?>? = null

    private fun detectInputBox(emulator: com.termux.terminal.TerminalEmulator): InputBox? {
        val curRow = emulator.cursorRow
        val curCol = emulator.cursorCol
        boxCache?.let { (r, c, box) -> if (r == curRow && c == curCol) return box }
        val box = scanInputBox(emulator, curRow)
        boxCache = Triple(curRow, curCol, box)
        return box
    }

    /** 从光标行向上找 ╭、向下找 ╰(中间必须都是 │ 行,否则不在框内)。 */
    private fun scanInputBox(emulator: com.termux.terminal.TerminalEmulator, curRow: Int): InputBox? {
        val cols = emulator.mColumns
        val rows = emulator.mRows
        var top = -1
        var r = curRow
        while (r >= 0 && r > curRow - 40) {
            // 注意:tmux 画框可能带前导空格(实测框线 '│' 在 col 1 而非 col 0),
            // 所以按"首个非空格字符"判断行类型
            val t = emulator.getSelectedText(0, r, cols - 1, r).trim()
            when {
                t.startsWith("│") -> r--
                t.startsWith("╭") -> {
                    top = r; break
                }
                else -> return null // 光标不在 │ 内容行上,或中途断框
            }
        }
        if (top < 0) return null
        var bottom = -1
        r = curRow
        while (r < rows && r < curRow + 40) {
            val t = emulator.getSelectedText(0, r, cols - 1, r).trim()
            when {
                t.startsWith("│") -> r++
                t.startsWith("╰") -> {
                    bottom = r; break
                }
                else -> return null
            }
        }
        if (bottom < 0 || bottom - top < 2) return null
        val topLine = emulator.getSelectedText(0, top, cols - 1, top)
        val left = topLine.indexOf('╭')
        val bottomLine = emulator.getSelectedText(0, bottom, cols - 1, bottom)
        val right = bottomLine.lastIndexOf('╯')
        if (left < 0 || right <= left + 1) return null
        return InputBox(top + 1, bottom - 1, left, right)
    }

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
        if (currentTab?.desktop == true) {
            // 桌面/远程浏览器标签:点 noVNC 页面输入区(如浏览器地址栏)时弹本机键盘;
            // 文本经 TerminalView 的 IME 输入连接 → onCodePoint → JS 注入 noVNC(见 sendDesktopChar)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            terminalView.requestFocus()
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            return
        }
        terminalView.requestFocus()
        // 根因修复（滚屏跳动）之二：fling 动画还在跑的时候点按屏幕（用户意图是"停住滚动"），
        // GestureDetector 会把这次点按当成干净单击回调到这里；若照旧翻译方向键/弹键盘，
        // 画面一边惯性滚动一边被方向键/重绘拉扯 = 跳动。所以 fling 未结束时的单击
        // 只用来终止 fling，不做任何翻译。（正常的滑动不会走到这里：GestureDetector
        // 只有位移始终小于 touch slop 才回调 onSingleTapConfirmed。）
        if (abortFlingIfRunning()) return
        // mouse tracking(tmux mouse on)时:单击已被 TerminalView 的手势层(onUp)
        // 作为鼠标事件转发给被控端——TUI(kimi/claude)标签的点选定位能力靠它保留。
        // 但转发不等于要牺牲键盘:用户在 TUI 里点完输入框通常紧接着要打字,
        // 所以任何干净单击都继续往下弹软键盘(不再按 app 类型早退)。
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

    /** 粘滞 ALT/SHIFT：同时回报给 Termux 的硬件按键路径（IME 字符由 onCodePoint 手工处理）。 */
    override fun readAltKey(): Boolean = stickyAlt

    override fun readShiftKey(): Boolean = stickyShift

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        // 桌面标签(noVNC):软键盘输入直接经 JS 注入远端(无终端会话可走)
        if (currentTab?.desktop == true) {
            sendDesktopChar(codePoint)
            return true
        }
        // 每个文本码点发送前都会经过这里；粘滞修饰键在下一个字符发出后自动取消。
        // SHIFT/ALT 对 IME 字符 Termux 不会自动处理（readShiftKey/readAltKey 只在硬件
        // 按键路径被查询），所以在发送前手工实现：
        if (stickyShift) {
            stickyShift = false
            updateModifierButtons()
            val upper = Character.toUpperCase(codePoint)
            if (upper != codePoint) {
                // 字母 → 直接发大写（Shift 的最小有用语义）；非字母按原样发
                currentTab?.session?.sendUserInput(String(Character.toChars(upper)))
                return true
            }
        }
        if (stickyAlt) {
            stickyAlt = false
            updateModifierButtons()
            // Meta = ESC 前缀(readline 的 M-b/M-f/M-d 等);用 0x1B 避免源码里藏不可见控制字符
            currentTab?.session?.sendUserInput(0x1B.toChar().toString() + String(Character.toChars(codePoint)))
            return true
        }
        if (stickyCtrl) {
            stickyCtrl = false
            updateModifierButtons()
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
        // IME 弹/收 resize 后新内容到达且已过遮蔽期 = 画面已重绘稳定,瞬间恢复可见(见 imeSettleRunnable)
        if (terminalView.alpha == 0f && System.currentTimeMillis() - imeHideTime > 400) {
            terminalView.alpha = 1f
        }
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
