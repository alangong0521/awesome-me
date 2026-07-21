package com.calla.remoteterminal

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 已存机器列表（登录页"存为机器"的数据）的读写工具，
 * SetupActivity（机器管理）与 TerminalActivity（新建标签选机器）共用，
 * 底层是同一份 SharedPreferences（PREFS_NAME/KEY_MACHINES）。
 */
data class Machine(val name: String, val host: String, val port: String, val token: String)

object MachineStore {
    const val PREFS_NAME = "remote_terminal"
    const val KEY_MACHINES = "machines"
    const val DEFAULT_PORT = "7681"

    fun load(prefs: SharedPreferences): MutableList<Machine> {
        val raw = prefs.getString(KEY_MACHINES, null) ?: return mutableListOf()
        val machines = mutableListOf<Machine>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                machines += Machine(
                    o.optString("name"), o.optString("host"),
                    o.optString("port", DEFAULT_PORT), o.optString("token")
                )
            }
        } catch (t: Throwable) {
            machines.clear() // 数据损坏则重置，不崩溃
        }
        return machines
    }

    fun save(prefs: SharedPreferences, machines: List<Machine>) {
        val arr = JSONArray()
        for (m in machines) {
            arr.put(
                JSONObject()
                    .put("name", m.name).put("host", m.host)
                    .put("port", m.port).put("token", m.token)
            )
        }
        prefs.edit().putString(KEY_MACHINES, arr.toString()).apply()
    }
}
