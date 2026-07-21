package com.calla.remoteterminal

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 一条文件推送记录（推送收件箱）。 */
data class InboxItem(
    val name: String,
    val size: Long,
    val time: Long,      // epoch millis（收到时间）
    val host: String,
    val port: Int,
    val token: String,
    val machine: String, // 机器显示名
    var downloaded: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("size", size).put("time", time)
        .put("host", host).put("port", port).put("token", token)
        .put("machine", machine).put("downloaded", downloaded)

    companion object {
        fun fromJson(o: JSONObject) = InboxItem(
            o.optString("name"), o.optLong("size"), o.optLong("time"),
            o.optString("host"), o.optInt("port", 7681), o.optString("token"),
            o.optString("machine"), o.optBoolean("downloaded", false)
        )
    }
}

/**
 * 推送收件箱的持久化（SharedPreferences 里一个 JSON 数组，最新在前，上限 50 条）。
 * 为什么持久化：文件推送的即时 Toast 错过就没了，用户要求随时能回看/下载。
 */
object FileInbox {
    private const val KEY = "file_inbox"
    private const val MAX = 50

    fun load(prefs: SharedPreferences): MutableList<InboxItem> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        val items = mutableListOf<InboxItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) items += InboxItem.fromJson(arr.getJSONObject(i))
        } catch (t: Throwable) {
            items.clear()
        }
        return items
    }

    private fun save(prefs: SharedPreferences, items: List<InboxItem>) {
        val arr = JSONArray()
        for (it in items) arr.put(it.toJson())
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** 头部插入新记录并截断；返回更新后的列表。 */
    fun add(prefs: SharedPreferences, item: InboxItem): MutableList<InboxItem> {
        val items = load(prefs)
        items.add(0, item)
        while (items.size > MAX) items.removeAt(items.size - 1)
        save(prefs, items)
        return items
    }

    fun markDownloaded(prefs: SharedPreferences, item: InboxItem) {
        val items = load(prefs)
        val idx = items.indexOfFirst { it.name == item.name && it.time == item.time }
        if (idx >= 0) {
            items[idx].downloaded = true
            item.downloaded = true
            save(prefs, items)
        }
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(KEY).apply()
    }

    /** 未读（未下载）数量：收件箱按钮角标用。 */
    fun unreadCount(prefs: SharedPreferences): Int = load(prefs).count { !it.downloaded }
}
