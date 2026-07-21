package com.calla.remoteterminal

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment

/**
 * 后台文件推送通知的点击接收器：点通知 → 用 DownloadManager 把文件下载到公共 Download 目录。
 *
 * 为什么用独立 receiver 而不是直接拉起 Activity：通知被点击时 TerminalActivity 可能
 * 已经被系统回收，靠 Activity 回调不可靠；receiver 由系统直接唤起进程执行，最稳。
 * URL（含 token）在通知发出前由 TerminalActivity 拼好放在 extra 里。
 */
class DownloadFileReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DOWNLOAD = "com.calla.remoteterminal.DOWNLOAD_FILE"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_NAME = "extra_name"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val name = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotEmpty() } ?: "download"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            // DownloadManager 写公共 Download 目录不需要 READ/WRITE 存储权限
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        runCatching {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }
    }
}
