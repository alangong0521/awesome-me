package com.calla.remoteterminal

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 启动页：填写主机/端口/token/程序（kimi/claude/tmux），测试连接或直接进入终端。
 * 选 tmux 时显示"会话名"输入框（接入本机已有 tmux 会话或新建持久会话）。
 * 上次输入通过 SharedPreferences 回填。
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "remote_terminal"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_APP = "app"
        private const val KEY_SESSION = "session"
        private const val KEY_MACHINES = "machines"
        private const val DEFAULT_PORT = "7681"
        private const val DEFAULT_SESSION = "phone"

        /** 与服务端过滤规则一致的基本校验：[a-zA-Z0-9_-] */
        private val SESSION_NAME_REGEX = Regex("^[a-zA-Z0-9_-]+$")
    }

    private lateinit var prefs: SharedPreferences

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private lateinit var hostEdit: TextInputEditText
    private lateinit var portEdit: TextInputEditText
    private lateinit var tokenEdit: TextInputEditText
    private lateinit var appRadioGroup: RadioGroup
    private lateinit var sessionLayout: TextInputLayout
    private lateinit var sessionEdit: TextInputEditText
    private lateinit var testButton: Button
    private lateinit var connectButton: Button
    private lateinit var machineSpinner: Spinner

    /** 已存机器(登录页下拉快速切换)；读写逻辑与 TerminalActivity 共用 MachineStore。 */
    private val machines = ArrayList<Machine>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // 开屏欢迎页:1.5s 后隐藏(不阻塞登录页可用)
        findViewById<View>(R.id.splash_overlay).postDelayed({
            findViewById<View>(R.id.splash_overlay).visibility = View.GONE
        }, 1500)

        // 副标题尾部追加版本号:让用户一眼确认装的是不是最新版
        findViewById<android.widget.TextView>(R.id.setup_subtitle).text =
            getString(R.string.setup_subtitle) + "  ·  v" + BuildConfig.VERSION_NAME

        hostEdit = findViewById(R.id.edit_host)
        portEdit = findViewById(R.id.edit_port)
        tokenEdit = findViewById(R.id.edit_token)
        appRadioGroup = findViewById(R.id.radio_app)
        sessionLayout = findViewById(R.id.til_session)
        sessionEdit = findViewById(R.id.edit_session)
        testButton = findViewById(R.id.btn_test)
        connectButton = findViewById(R.id.btn_connect)
        machineSpinner = findViewById(R.id.spinner_machine)

        appRadioGroup.setOnCheckedChangeListener { _, _ -> updateSessionFieldVisibility() }

        // 回填上次输入(连接时自动保存,不用每次重填)
        hostEdit.setText(prefs.getString(KEY_HOST, ""))
        portEdit.setText(prefs.getString(KEY_PORT, DEFAULT_PORT))
        tokenEdit.setText(prefs.getString(KEY_TOKEN, ""))
        sessionEdit.setText(prefs.getString(KEY_SESSION, DEFAULT_SESSION))
        appRadioGroup.check(
            when (prefs.getString(KEY_APP, "shell")) {
                "kimi" -> R.id.radio_kimi
                "claude" -> R.id.radio_claude
                "tmux" -> R.id.radio_tmux
                else -> R.id.radio_shell
            }
        )
        updateSessionFieldVisibility()

        setupMachineSwitcher()

        testButton.setOnClickListener { testConnection() }
        connectButton.setOnClickListener { saveAndConnect() }
        findViewById<Button>(R.id.btn_battery).setOnClickListener { requestIgnoreBatteryOptimizations() }

        showSplash()
    }

    /**
     * 开屏欢迎画面:盖在登录页上的全屏浮层(窗口主题背景本来就是终端黑,无白屏/黑屏间隙),
     * 艺术字 App 名(等宽粗体 + 加宽字距 + 与图标一致的蓝紫渐变着色),1.6s 后 400ms 淡出移除。
     */
    private fun showSplash() {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val overlay = android.widget.FrameLayout(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@SetupActivity, R.color.terminal_bg))
            isClickable = true // 挡住 splash 期间对下方的误触
        }
        val title = TextView(this).apply {
            text = "AwesomeMe"
            textSize = 52f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setTextColor(android.graphics.Color.WHITE) // shader 生效前的占位色
            setShadowLayer(24f, 0f, 0f, 0x804F8CFF.toInt()) // 泛光,增强"艺术感"
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
        }
        val subtitle = TextView(this).apply {
            text = ">_"
            textSize = 28f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@SetupActivity, R.color.status_text))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            ).apply { topMargin = dp(96) }
        }
        overlay.addView(title)
        overlay.addView(subtitle)
        root.addView(overlay)
        // 等文字量出宽度后挂蓝紫渐变(此时 width 才非 0)
        title.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                title.viewTreeObserver.removeOnGlobalLayoutListener(this)
                title.paint.shader = android.graphics.LinearGradient(
                    0f, 0f, title.width.toFloat(), 0f,
                    intArrayOf(0xFF4F8CFF.toInt(), 0xFF8A5CF5.toInt()), null,
                    android.graphics.Shader.TileMode.CLAMP
                )
                title.invalidate()
            }
        })
        overlay.postDelayed({
            overlay.animate().alpha(0f).setDuration(400).withEndAction {
                root.removeView(overlay)
            }.start()
        }, 1600)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * 引导用户把 App 加入电池优化白名单:后台保活双保险(前台服务+唤醒锁之外,
     * 某些国产 ROM 仍会激进清理后台网络)。只提供按钮,不做弹窗轰炸。
     */
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast(getString(R.string.battery_already_ignored))
            return
        }
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            toast(getString(R.string.battery_request_failed))
        }
    }

    // ---------- 机器切换(多机器配置:公司服务器 / 家里电脑 …) ----------

    private fun setupMachineSwitcher() {
        loadMachines()
        refreshMachineSpinner(prefs.getString(KEY_HOST, null))
        machineSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in 1..machines.size) {
                    val m = machines[position - 1]
                    hostEdit.setText(m.host)
                    portEdit.setText(m.port)
                    tokenEdit.setText(m.token)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        findViewById<Button>(R.id.btn_save_machine).setOnClickListener { showSaveMachineDialog() }
    }

    private fun loadMachines() {
        machines.clear()
        if (!prefs.contains(KEY_MACHINES)) {
            // 首次:预置两台常用机器,token 留空,连接一次后自动回填
            machines += Machine("公司服务器", "CHANGE_ME_SERVER_TAILNET_IP", DEFAULT_PORT, "")
            machines += Machine("家里电脑", "CHANGE_ME_MAC_TAILNET_IP", DEFAULT_PORT, "")
            saveMachines()
            return
        }
        machines += MachineStore.load(prefs)
    }

    private fun saveMachines() = MachineStore.save(prefs, machines)

    private fun refreshMachineSpinner(selectHost: String? = null) {
        val names = listOf(getString(R.string.machine_manual)) + machines.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        machineSpinner.adapter = adapter
        val idx = machines.indexOfFirst { it.host == selectHost }
        if (idx >= 0) machineSpinner.setSelection(idx + 1)
    }

    private fun showSaveMachineDialog() {
        val inputs = readInputs() ?: return
        val edit = android.widget.EditText(this).apply {
            hint = getString(R.string.hint_machine_name)
            val pos = machineSpinner.selectedItemPosition
            if (pos in 1..machines.size) setText(machines[pos - 1].name)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_machine_name_title)
            .setView(edit, pad, pad / 2, pad, 0)
            .setPositiveButton(R.string.action_save_machine, null)
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        // 手动处理确定按钮:校验不过不关对话框
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = edit.text.toString().trim()
            if (name.isEmpty()) {
                edit.error = getString(R.string.error_machine_name_required)
                return@setOnClickListener
            }
            val m = Machine(name, inputs.host, inputs.port, inputs.token)
            val idx = machines.indexOfFirst { it.name == name }
            if (idx >= 0) machines[idx] = m else machines += m
            saveMachines()
            refreshMachineSpinner(m.host)
            toast(getString(R.string.machine_saved, name))
            dialog.dismiss()
        }
    }

    private fun updateSessionFieldVisibility() {
        sessionLayout.visibility =
            if (appRadioGroup.checkedRadioButtonId == R.id.radio_tmux) View.VISIBLE else View.GONE
    }

    private data class Inputs(
        val host: String,
        val port: String,
        val token: String,
        val app: String,
        val session: String?, // 仅 tmux 时有值
    )

    /** 读取并校验输入；不合法时 Toast 提示并返回 null。 */
    private fun readInputs(): Inputs? {
        // 容忍用户粘贴进来的 scheme/结尾斜杠
        val host = hostEdit.text?.toString()?.trim()
            ?.removePrefix("http://")?.removePrefix("https://")
            ?.removePrefix("ws://")?.removePrefix("wss://")
            ?.trimEnd('/')?.trim().orEmpty()
        if (host.isEmpty()) {
            toast(getString(R.string.error_host_required))
            return null
        }

        val port = portEdit.text?.toString()?.trim().orEmpty().ifEmpty { DEFAULT_PORT }
        val portNum = port.toIntOrNull()
        if (portNum == null || portNum !in 1..65535) {
            toast(getString(R.string.error_port_invalid))
            return null
        }

        val token = tokenEdit.text?.toString()?.trim().orEmpty()
        if (token.isEmpty()) {
            toast(getString(R.string.error_token_required))
            return null
        }

        val app = when (appRadioGroup.checkedRadioButtonId) {
            R.id.radio_kimi -> "kimi"
            R.id.radio_claude -> "claude"
            R.id.radio_tmux -> "tmux"
            else -> "shell"
        }

        var session: String? = null
        if (app == "tmux") {
            session = sessionEdit.text?.toString()?.trim().orEmpty().ifEmpty { DEFAULT_SESSION }
            if (!SESSION_NAME_REGEX.matches(session)) {
                toast(getString(R.string.error_session_invalid))
                return null
            }
        }

        return Inputs(host, portNum.toString(), token, app, session)
    }

    private fun testConnection() {
        val inputs = readInputs() ?: return
        testButton.isEnabled = false
        val url = "http://${inputs.host}:${inputs.port}/health" +
            "?token=${URLEncoder.encode(inputs.token, Charsets.UTF_8.name())}"
        httpClient.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val message = when (response.code) {
                    200 -> getString(R.string.test_success)
                    401 -> getString(R.string.test_unauthorized)
                    else -> getString(R.string.test_http_error, response.code)
                }
                response.close()
                runOnUiThread {
                    testButton.isEnabled = true
                    toast(message)
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    testButton.isEnabled = true
                    toast(getString(R.string.test_unreachable, e.message ?: e.javaClass.simpleName))
                }
            }
        })
    }

    private fun saveAndConnect() {
        val inputs = readInputs() ?: return
        prefs.edit()
            .putString(KEY_HOST, inputs.host)
            .putString(KEY_PORT, inputs.port)
            .putString(KEY_TOKEN, inputs.token)
            .putString(KEY_APP, inputs.app)
            .putString(KEY_SESSION, inputs.session ?: DEFAULT_SESSION)
            .apply()
        // 连接成功路径:把最新主机/端口/token 回写到当前选中的机器配置
        val pos = machineSpinner.selectedItemPosition
        if (pos in 1..machines.size) {
            machines[pos - 1] = Machine(machines[pos - 1].name, inputs.host, inputs.port, inputs.token)
            saveMachines()
        }
        startActivity(Intent(this, TerminalActivity::class.java).apply {
            putExtra(TerminalActivity.EXTRA_HOST, inputs.host)
            putExtra(TerminalActivity.EXTRA_PORT, inputs.port)
            putExtra(TerminalActivity.EXTRA_TOKEN, inputs.token)
            putExtra(TerminalActivity.EXTRA_APP, inputs.app)
            putExtra(TerminalActivity.EXTRA_SESSION, inputs.session)
        })
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
