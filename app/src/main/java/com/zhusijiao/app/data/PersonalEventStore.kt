package com.zhusijiao.app.data

import com.zhusijiao.app.MainApplication
import com.zhusijiao.app.domain.PersonalEvent
import com.zhusijiao.app.domain.PersonalEventDraft
import com.zhusijiao.app.domain.PersonalEventValidator
import com.zhusijiao.app.domain.ScheduleOccurrences
import com.zhusijiao.app.domain.TimeSlot
import com.zhusijiao.app.domain.toPersonalEventList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 自定义日程的本机存储，按 scheduleId 分组。
 *
 * **不进课表、不进同步**：日程是这台手机上的私人数据，既不会随 `ApiClient.syncOwner`
 * 上传给订阅这份课表的同学，也不会被 `storeRemote` 的远端副本覆盖掉——所以订阅别人
 * 课表的同学同样能加自己的日程。教务重新导入覆盖课表时（scheduleId 不变）日程自动保留。
 * 见 docs/DECISIONS.md D18。
 */
object PersonalEventStore {

    /** 每份课表的日程上限，防止本机文件无限增长。 */
    const val MAX_PER_SCHEDULE = 100

    private val lock = Any()

    fun list(scheduleId: String): List<PersonalEvent> = synchronized(lock) {
        read()[scheduleId].orEmpty().sortedWith(compareBy({ it.day }, { it.startSection }))
    }

    fun get(scheduleId: String, eventId: String): PersonalEvent? =
        list(scheduleId).find { it.id == eventId }

    /**
     * 新建（[eventId] 为 null）或修改一条日程。
     * 校验失败、同时段已有日程、超出条数上限时抛 [ApiException]，message 即用户可读文案。
     */
    fun save(
        scheduleId: String,
        draft: PersonalEventDraft,
        eventId: String?,
        totalWeeks: Int,
        slots: List<TimeSlot>
    ): PersonalEvent = synchronized(lock) {
        val normalized = try {
            PersonalEventValidator.normalize(draft, totalWeeks, slots)
        } catch (error: IllegalArgumentException) {
            throw ApiException(error.message ?: "日程信息无效")
        }
        val all = read().toMutableMap()
        val events = all[scheduleId].orEmpty()
        val existing = eventId?.let { id -> events.find { it.id == id } }
        if (eventId != null && existing == null) throw ApiException("日程不存在")
        if (existing == null && events.size >= MAX_PER_SCHEDULE) {
            throw ApiException("一份课表最多添加 $MAX_PER_SCHEDULE 条日程")
        }
        ScheduleOccurrences.overlappingEvent(events, normalized, eventId)?.let {
            throw ApiException("这个时段已经有日程「${it.title}」了")
        }
        val timestamp = now()
        val saved = PersonalEvent(
            id = existing?.id ?: "event-${UUID.randomUUID()}",
            title = normalized.title,
            position = normalized.position,
            note = normalized.note,
            day = normalized.day,
            startSection = normalized.startSection,
            endSection = normalized.endSection,
            startTime = normalized.startTime,
            endTime = normalized.endTime,
            weeks = normalized.weeks,
            color = normalized.color,
            createdAt = existing?.createdAt ?: timestamp,
            updatedAt = timestamp
        )
        all[scheduleId] = events.filterNot { it.id == saved.id } + saved
        write(all)
        saved
    }

    fun delete(scheduleId: String, eventId: String) = synchronized(lock) {
        val all = read().toMutableMap()
        val events = all[scheduleId].orEmpty()
        val remaining = events.filterNot { it.id == eventId }
        if (remaining.size == events.size) throw ApiException("日程不存在")
        if (remaining.isEmpty()) all.remove(scheduleId) else all[scheduleId] = remaining
        write(all)
    }

    /** 课表被删除或退出时清理它名下的日程。 */
    fun removeSchedule(scheduleId: String) = synchronized(lock) {
        val all = read().toMutableMap()
        if (all.remove(scheduleId) != null) write(all)
    }

    fun clear() = synchronized(lock) {
        file().delete()
        tempFile().delete()
    }

    private fun read(): Map<String, List<PersonalEvent>> = runCatching {
        val raw = file().takeIf { it.exists() }?.readText() ?: return@runCatching emptyMap()
        val root = JSONObject(raw)
        val result = mutableMapOf<String, List<PersonalEvent>>()
        for (key in root.keys()) {
            val events = root.optJSONArray(key).toPersonalEventList()
            if (key.isNotBlank() && events.isNotEmpty()) result[key] = events
        }
        result
    }.getOrDefault(emptyMap())

    /** 与 LocalScheduleStore 一致的原子写：先写临时文件并 fsync，再整体替换。 */
    private fun write(events: Map<String, List<PersonalEvent>>) {
        val target = file()
        val temp = tempFile()
        target.parentFile?.mkdirs()
        val root = JSONObject()
        events.forEach { (scheduleId, list) ->
            if (list.isNotEmpty()) root.put(scheduleId, JSONArray(list.map { it.toJson() }))
        }
        FileOutputStream(temp).use { output ->
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).run {
        timeZone = TimeZone.getTimeZone("UTC")
        format(Date())
    }

    private fun file() = File(MainApplication.appContext.filesDir, "events/personal_events_v1.json")
    private fun tempFile() = File(MainApplication.appContext.filesDir, "events/personal_events_v1.tmp")
}
