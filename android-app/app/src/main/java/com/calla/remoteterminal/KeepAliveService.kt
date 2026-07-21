package com.calla.remoteterminal

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 前台保活服务：TerminalActivity 存在活跃标签期间常驻。
 *
 * 为什么需要：App 退到后台后，系统随时可能休眠 CPU、让 Wi-Fi 芯片进入省电模式，
 * OkHttp 的 15s ping 发不出去，WebSocket 就会被悄悄掐断。前台服务提升进程优先级，
 * PARTIAL_WAKE_LOCK 防 CPU 休眠，WIFI_MODE_FULL_HIGH_PERF 防 Wi-Fi 省电，
 * 三者配合保证"除非用户手动关闭，连接永远存活"。
 *
 * targetSdk 34 要求声明 foregroundServiceType；这里用 specialUse（ manifest 里
 * 配 FOREGROUND_SERVICE_SPECIAL_USE 权限 + PROPERTY_SPECIAL_USE_FGS_SUBTYPE）。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_SESSION_COUNT = "extra_session_count"

        /** 启动或更新（通知里的会话数变化时重复调用即可，startForegroundService 幂等）。 */
        fun startOrUpdate(context: Context, sessionCount: Int) {
            val intent = Intent(context, KeepAliveService::class.java)
                .putExtra(EXTRA_SESSION_COUNT, sessionCount)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_SESSION_COUNT, 0) ?: 0
        createChannel()
        // startForegroundService 后必须在约 5s 内 startForeground，否则 ANR/崩溃
        startForeground(NOTIFICATION_ID, buildNotification(count))
        acquireLocks()
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // IMPORTANCE_MIN：常驻但完全不打扰（无声音、不振动、状态栏最小化）
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_keepalive),
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
    }

    private fun buildNotification(count: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.notif_keepalive_title))
            .setContentText(getString(R.string.notif_keepalive_text, count))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    @SuppressLint("WakelockTimeout") // 必须持到连接全部结束：设超时释放等于后台定时断线
    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "remoteterminal:keepalive").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // WIFI_MODE_FULL_HIGH_PERF 在 API 29 起标记废弃但仍有效（minSdk 24 可用）；
            // 低功耗模式下 Wi-Fi 会批量延迟小包，正好掐死 WebSocket 的心跳 ping
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "remoteterminal:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        super.onDestroy()
    }
}
