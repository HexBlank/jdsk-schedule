package com.zhusijiao.app.data

import com.zhusijiao.app.MainApplication
import com.zhusijiao.app.domain.Schedule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** 旧版联网只读缓存；升级时迁入本地主存储，之后仅保留为同步响应的兼容副本。 */
object ScheduleCache {
    private val lock = Any()

    fun list(): List<Schedule>? = synchronized(lock) {
        val array = read()?.optJSONArray("list") ?: return@synchronized null
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Schedule::fromJson) }
    }

    fun detail(id: String): Schedule? = synchronized(lock) {
        read()?.optJSONObject("details")?.optJSONObject(id)?.let(Schedule::fromJson)
    }

    fun putList(schedules: List<Schedule>) = synchronized(lock) {
        val root = read() ?: JSONObject()
        root.put("list", JSONArray(schedules.map { it.toJson() }))
        write(root)
    }

    fun putDetail(schedule: Schedule) = synchronized(lock) {
        val root = read() ?: JSONObject()
        val details = root.optJSONObject("details") ?: JSONObject().also { root.put("details", it) }
        details.put(schedule.id, schedule.toJson())
        root.optJSONArray("list")?.let { list ->
            var found = false
            val updated = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { item ->
                if (item.optString("id") == schedule.id) {
                    found = true
                    schedule.toJson()
                } else item
            }.toMutableList()
            if (!found) updated.add(schedule.toJson())
            root.put("list", JSONArray(updated))
        }
        write(root)
    }

    fun remove(id: String) = synchronized(lock) {
        val root = read() ?: return@synchronized
        root.optJSONObject("details")?.remove(id)
        root.optJSONArray("list")?.let { list ->
            val remaining = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .filterNot { it.optString("id") == id }
            root.put("list", JSONArray(remaining))
        }
        write(root)
    }

    fun clear() = synchronized(lock) {
        file().delete()
        tempFile().delete()
    }

    private fun read(): JSONObject? = runCatching {
        file().takeIf { it.exists() }?.readText()?.let(::JSONObject)
    }.getOrNull()

    private fun write(root: JSONObject) {
        val target = file()
        val temp = tempFile()
        target.parentFile?.mkdirs()
        FileOutputStream(temp).use { output ->
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun file() = File(MainApplication.appContext.filesDir, "cache/schedules_v1.json")
    private fun tempFile() = File(MainApplication.appContext.filesDir, "cache/schedules_v1.tmp")
}
