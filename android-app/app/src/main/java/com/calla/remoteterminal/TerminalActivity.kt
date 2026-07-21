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

        /** 文件推送去重窗口：同一 name|size 在该窗口内（多标签同时收到同一广播）只提示一次。 */
        private const val PUSH_DEDUPE_WINDOW_MS = 10_000L

        /** 需要"输入框内点按定位光标"的 TUI 程序（kimi/claude 不吃鼠标点击，用方向键挪光标）。 */
        private val TUI_APPS = setOf("kimi", "kimi-c", "kimi-r", "claude", "claude-c", "claude-r")

        /** 点按定位：最长按下时长（超时视为滚动/选择，不拦截）。 */
        private const val CURSOR_TAP_MAX_MS = 400L
    }

    /** 一个标签。session 的生命周期由 Activity 管理（重连时会整个替换）。
     *  每个标签自带连接三元组（多机切换：不同标签可以连不同机器）。 */
    private class Tab(
        val id: Int,
        val label: String,
        val app: String,
        val tmuxSession: String?,
        var session: RemoteTerminalSession?,
        val host: String,
        val port: Int,
        val token: String,
        /** 机器显示名（机器列表里的名字；查不到则为 host），用于标签标题。 */
        val machineName: String,
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
    private lateinit var machinesContainer: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var ctrlButton: Button

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
    private var baseFontPx = 0
    private var termTitle: String? = null
    private var endDialogShowing = false
    private lateinit var orientationButton: Button
    private lateinit var shiftButton: Button
    private lateinit var altButton: Button

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
        machinesContainer = findViewById(R.id.machines_container)
        emptyHint = findViewById(R.id.empty_hint)
        ctrlButton = findViewById(R.id.key_ctrl)

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
        // 标签标题 = 机器名/会话名(如 公司服务器/kimi-2),多机标签一眼可辨,也和 `tmux ls` 对得上
        val tab = Tab(nextTabId++, "$mName/$sessionName", app, sessionName, null, mHost, mPort, mToken, mName)
        tab.session = createSession(tab)
        tabs.add(tab)
        // 新建的标签归属即用户当前想看的:机器栏选中跟随到该机(空态随之解除)
        selectedMachine = tab.machineName
        refreshMachinesBar()
        updateKeepAlive()
        switchToTab(tab)
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
        val selectedBg = ContextCompat.getColorStateList(this, R.color.ctrl_active)
        val normalBg = ContextCompat.getColorStateList(this, R.color.key_background)
        val textColor = ContextCompat.getColor(this, R.color.key_text)
        for ((name, _) in machineBarEntries()) {
            machinesContainer.addView(Button(this).apply {
                text = name
                isAllCaps = false
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(12), 0, dp(12), 0)
                backgroundTintList = if (name == selectedMachine) selectedBg else normalBg
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
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

    /** 当前选中机器可见的标签(标签栏只画这些)。 */
    private fun visibleTabs(): List<Tab> = tabs.filter { it.machineName == selectedMachine }

    /** 选中机器没有标签时的空态:藏终端区(后台连接不动),提示去点 +。 */
    private fun showEmptyState() {
        currentTab = null
        terminalView.visibility = View.INVISIBLE
        emptyHint.visibility = View.VISIBLE
        refreshTabBar()
        refreshStatus()
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
        // 每个标签用自己的三元组连接(多机标签各连各的机器)
        return RemoteTerminalSession(tab.host, tab.port, tab.app, tab.tmuxSession, tab.token, this, this)
    }

    /** 切换标签：TerminalView 重新 attach；后台标签的连接与 emulator 保持不动。 */
    private fun switchToTab(tab: Tab) {
        // 从空态恢复:终端区显示回来
        emptyHint.visibility = View.GONE
        terminalView.visibility = View.VISIBLE
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
            fetchTmuxSessions(h, p, t, sessionsStatus, sessionsContainer) { name ->
                openTab("tmux", name, m)
                dialog.dismiss()
            }
        }
        machineSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) =
                refetchSessions()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        refetchSessions()

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
            openTab(app, typed.ifEmpty { null }, selectedMachine())
            dialog.dismiss()
        }
    }

    /** GET /sessions 列出指定机器的活 tmux 会话；每项一个按钮，点按即开标签接入（app=tmux + session=名）。 */
    private fun fetchTmuxSessions(
        mHost: String, mPort: Int, mToken: String,
        status: TextView, container: LinearLayout, onPick: (String) -> Unit
    ) {
        val url = "http://$mHost:$mPort/sessions?token=${URLEncoder.encode(mToken, Charsets.UTF_8.name())}"
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

    /** 标签栏重绘：只画当前选中机器的标签；当前标签高亮；已断开加 ✕、重连中加 ↻ 前缀。 */
    private fun refreshTabBar() {
        tabsContainer.removeAllViews()
        val currentBg = ContextCompat.getColorStateList(this, R.color.ctrl_active)
        val normalBg = ContextCompat.getColorStateList(this, R.color.key_background)
        val normalText = ContextCompat.getColor(this, R.color.key_text)
        val deadText = ContextCompat.getColor(this, R.color.tab_dead_text)
        for (tab in visibleTabs()) {
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
        // 推送来自哪个标签的连接,下载 URL 就用哪个标签的机器(多机场景)
        val tab = tabs.firstOrNull { it.session === session }
        if (inForeground) {
            showFilePushedDialog(name, size, tab)
        } else {
            postFilePushedNotification(name, size, tab)
        }
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

    /** DownloadManager 写公共 Download 目录，不需要 READ/WRITE 存储权限。 */
    private fun downloadPushedFile(name: String, tab: Tab?) {
        val request = DownloadManager.Request(Uri.parse(fileDownloadUrl(name, tab)))
            .setTitle(name)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        runCatching {
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }.onFailure {
            Toast.makeText(this, getString(R.string.download_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun showFilePushedDialog(name: String, size: Long, tab: Tab?) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_file_pushed_title)
            .setMessage(getString(R.string.dialog_file_pushed_message, name, formatSize(size)))
            .setPositiveButton(R.string.action_download) { _, _ -> downloadPushedFile(name, tab) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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
        findViewById<Button>(R.id.key_enter).setOnClickListener {
            currentTab?.session?.sendUserInput(byteArrayOf(0x0D))
        }
        findViewById<Button>(R.id.key_esc).setOnClickListener {
            currentTab?.session?.sendUserInput(byteArrayOf(0x1B))
        }
        findViewById<Button>(R.id.key_up).setOnClickListener { sendArrowKey(KeyEvent.KEYCODE_DPAD_UP) }
        findViewById<Button>(R.id.key_down).setOnClickListener { sendArrowKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        // 退格 = 0x7F(DEL),与多数终端/backspace 约定一致
        findViewById<Button>(R.id.key_backspace).setOnClickListener {
            currentTab?.session?.sendUserInput(byteArrayOf(0x7F))
        }
        findViewById<Button>(R.id.key_left).setOnClickListener { sendArrowKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        findViewById<Button>(R.id.key_right).setOnClickListener { sendArrowKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        findViewById<Button>(R.id.key_tab).setOnClickListener {
            currentTab?.session?.sendUserInput(byteArrayOf(0x09))
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
        findViewById<Button>(R.id.key_keyboard).setOnClickListener { toggleSoftKeyboard() }
        orientationButton = findViewById(R.id.key_orientation)
        orientationButton.setOnClickListener { toggleOrientation() }
        updateModifierButtons()
    }

    /** IME 动画期间冻结的 TerminalView 高度（-1 = 未冻结）。 */
    private var frozenTermHeight = -1
    private var lastContainerHeight = -1

    /** 键盘动画稳定后的一次性对齐动作（每次容器高度变化都会重置计时）。 */
    private val imeSettleRunnable = Runnable {
        if (frozenTermHeight >= 0) {
            frozenTermHeight = -1
            // 恢复跟随容器:触发一次 onSizeChanged → updateSize → 单次 resize
            val lp = terminalView.layoutParams
            lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            terminalView.layoutParams = lp
        }
    }

    /**
     * 弹/收键盘"内容不滚动"修复(备选方案 B,已否决 adjustPan——它把键栏盖在键盘下):
     * adjustResize 下键盘动画会让容器高度逐帧变化,TerminalView 每帧 updateSize →
     * 本地 emulator 连续重排 = 用户看到的"内容滚动很多行"。这里在容器高度开始变化时
     * 把 TerminalView 钉死为当前像素高度(底部被键盘盖住,内容原地不动),动画稳定
     * 250ms 后一次性恢复 MATCH_PARENT —— 整个弹/收过程只触发一次 resize/重排。
     */
    private fun setupImeResizeFreeze() {
        val container = findViewById<android.widget.FrameLayout>(R.id.terminal_container)
        container.viewTreeObserver.addOnGlobalLayoutListener {
            val h = container.height
            if (h == lastContainerHeight || h <= 0) return@addOnGlobalLayoutListener
            lastContainerHeight = h
            if (frozenTermHeight < 0 && terminalView.height > 0 && terminalView.height != h) {
                // 动画开始:钉住当前高度,TerminalView 不再随容器收缩 → 不触发 updateSize
                frozenTermHeight = terminalView.height
                val lp = terminalView.layoutParams
                lp.height = frozenTermHeight
                terminalView.layoutParams = lp
            }
            container.removeCallbacks(imeSettleRunnable)
            container.postDelayed(imeSettleRunnable, 250)
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
        val keybar = findViewById<HorizontalScrollView>(R.id.keybar_scroll)
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

    /** CTRL/SHIFT/ALT 粘滞修饰键统一点亮/熄灭（点亮 = ctrl_active 底色）。 */
    private fun updateModifierButtons() {
        val active = ContextCompat.getColorStateList(this, R.color.ctrl_active)
        val normal = ContextCompat.getColorStateList(this, R.color.key_background)
        ctrlButton.backgroundTintList = if (stickyCtrl) active else normal
        shiftButton.backgroundTintList = if (stickyShift) active else normal
        altButton.backgroundTintList = if (stickyAlt) active else normal
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
            .append(" @ ").append(tab.host).append(':').append(tab.port)
        if (tab.stateText.isNotEmpty()) sb.append("  ·  ").append(tab.stateText)
        termTitle?.takeIf { it.isNotEmpty() }?.let { sb.append("  ·  ").append(it) }
        // 末尾固定显示版本号:让用户一眼确认装的是不是最新版(排查"以为装了新版"问题)
        sb.append("  ·  v").append(BuildConfig.VERSION_NAME)
        statusText.text = sb.toString()
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
     * kimi/claude 标签输入框点按定位光标:kimi 不吃鼠标点击,点按位置与光标
     * 同行不同列时,发 ←/→ 方向键(次数=列差)把光标挪到点按处;同行同列或其他行
     * 不拦截(其他行照旧走 Termux 的鼠标转发=TUI 点选)。返回 true=已消费该 UP。
     */
    private fun tryCursorRowTap(up: MotionEvent): Boolean {
        val tab = currentTab ?: return false
        if (tab.app !in TUI_APPS) return false
        val emulator = terminalView.mEmulator ?: return false
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
        if (row != emulator.cursorRow) return false
        val colWidth = terminalView.width.toFloat() / emulator.mColumns
        if (colWidth <= 0) return false
        val col = (up.x / colWidth).toInt()
        val delta = col - emulator.cursorCol
        if (delta == 0) return false // 正点在光标上:放行维持原行为
        val key = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(minOf(abs(delta), 100)) { sendArrowKey(key) }
        // 点输入框就是要打字:弹键盘,与其他单击行为一致
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        return true
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
