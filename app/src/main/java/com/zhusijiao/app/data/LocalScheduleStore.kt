package com.zhusijiao.app.data

import com.zhusijiao.app.MainApplication
import com.zhusijiao.app.domain.ParsedSchedule
import com.zhusijiao.app.domain.CourseAdjustment
import com.zhusijiao.app.domain.CourseAdjustmentDraft
import com.zhusijiao.app.domain.DayHoliday
import com.zhusijiao.app.domain.DayHolidayDraft
import com.zhusijiao.app.domain.DayMakeup
import com.zhusijiao.app.domain.DayMakeupDraft
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleValidator
import com.zhusijiao.app.reminder.ClassReminders
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.io.File
import java.io.FileOutputStream

/**
 * 永远可用的本机课表存储。
 *
 * 初始状态严格为空，只保存用户主动导入/加入的课表。是否配置后端都先读写这里；
 * 分享和同步是附加能力，断网不得影响查看、导入、调课和删除。
 */
object LocalScheduleStore {

    private val lock = Any()

    fun listSchedules(): List<Schedule> = synchronized(lock) { read().sortedByDescending { it.updatedAt } }

    fun getSchedule(id: String): Schedule = getScheduleOrNull(id) ?: throw ApiException("课表不存在")

    fun getScheduleOrNull(id: String): Schedule? = synchronized(lock) { read().find { it.id == id } }

    fun putSchedule(schedule: Schedule) = synchronized(lock) {
        val schedules = read().filterNot { it.id == schedule.id }.toMutableList()
        schedules.add(schedule)
        write(schedules)
    }

    fun putRemoteSchedule(schedule: Schedule, localId: String = schedule.id) {
        putSchedule(schedule.copy(id = localId))
    }

    fun saveSchedule(input: ParsedSchedule, id: String?, expectedRevision: Int? = null): Schedule = synchronized(lock) {
        val normalized = try {
            ScheduleValidator.normalize(input)
        } catch (error: IllegalArgumentException) {
            throw ApiException(error.message ?: "课表数据不完整")
        }
        val schedules = read().toMutableList()
        val existingIndex = schedules.indexOfFirst { it.id == id }
        val existing = schedules.getOrNull(existingIndex)
        if (id != null && existing == null) throw ApiException("课表不存在")
        if (existing != null && expectedRevision != null && existing.revision != expectedRevision) {
            throw ApiException("课表已在其他页面更新，请刷新后重试")
        }

        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).run {
            timeZone = TimeZone.getTimeZone("UTC")
            format(Date())
        }
        val saved = Schedule(
            id = existing?.id ?: "local-${UUID.randomUUID()}",
            name = normalized.name,
            school = normalized.school,
            semesterStart = normalized.semesterStart,
            totalWeeks = normalized.totalWeeks,
            shareCode = null,
            revision = (existing?.revision ?: 0) + 1,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            subscriberCount = 0,
            role = "owner",
            courseCount = normalized.courses.size,
            timeSlots = normalized.timeSlots,
            courses = normalized.courses,
            // 整表导入覆盖以最新教务快照为准，旧的手动调课全部清空。
            adjustments = emptyList()
        )
        if (existingIndex >= 0) schedules[existingIndex] = saved else schedules.add(saved)
        write(schedules)
        saved
    }

    fun deleteSchedule(id: String) = synchronized(lock) {
        val schedules = read().filterNot { it.id == id }
        write(schedules)
    }

    fun saveAdjustment(
        scheduleId: String,
        draft: CourseAdjustmentDraft,
        adjustmentId: String?,
        expectedRevision: Int? = null
    ): Schedule = synchronized(lock) {
        val schedules = read().toMutableList()
        val index = schedules.indexOfFirst { it.id == scheduleId }
        val schedule = schedules.getOrNull(index) ?: throw ApiException("课表不存在")
        if (expectedRevision != null && schedule.revision != expectedRevision) {
            throw ApiException("课表已在其他页面更新，请刷新后重试")
        }
        if (!schedule.isOwner) throw ApiException("只有发布者可以调课")
        val base = try {
            ScheduleValidator.validateAdjustment(schedule, draft, adjustmentId)
        } catch (error: IllegalArgumentException) {
            throw ApiException(error.message ?: "调课信息无效")
        }
        val old = adjustmentId?.let { id -> schedule.adjustments.find { it.id == id } }
        if (adjustmentId != null && old == null) throw ApiException("调课记录不存在")
        val timestamp = now()
        val adjustment = CourseAdjustment(
            id = old?.id ?: "adjustment-${UUID.randomUUID()}",
            courseId = draft.courseId,
            sourceWeek = draft.sourceWeek,
            sourceDay = draft.sourceDay,
            sourceStartSection = draft.sourceStartSection,
            sourceEndSection = draft.sourceEndSection,
            targetWeek = draft.targetWeek,
            targetDay = draft.targetDay,
            targetStartSection = draft.targetStartSection,
            targetEndSection = draft.targetEndSection,
            targetPosition = draft.targetPosition?.trim()?.takeIf { it.isNotEmpty() },
            courseSnapshot = base,
            createdAt = old?.createdAt ?: timestamp,
            updatedAt = timestamp
        )
        val updated = schedule.copy(
            revision = schedule.revision + 1,
            updatedAt = timestamp,
            adjustments = schedule.adjustments.filterNot { it.id == adjustment.id } + adjustment
        )
        schedules[index] = updated
        write(schedules)
        updated
    }

    fun deleteAdjustment(scheduleId: String, adjustmentId: String, expectedRevision: Int? = null): Schedule = synchronized(lock) {
        val schedules = read().toMutableList()
        val index = schedules.indexOfFirst { it.id == scheduleId }
        val schedule = schedules.getOrNull(index) ?: throw ApiException("课表不存在")
        if (expectedRevision != null && schedule.revision != expectedRevision) {
            throw ApiException("课表已在其他页面更新，请刷新后重试")
        }
        val remaining = schedule.adjustments.filterNot { it.id == adjustmentId }
        if (remaining.size == schedule.adjustments.size) throw ApiException("调课记录不存在")
        val updated = schedule.copy(
            revision = schedule.revision + 1,
            updatedAt = now(),
            adjustments = remaining
        )
        schedules[index] = updated
        write(schedules)
        updated
    }

    /** 停课/补课共用的本机更新流程：本机优先、revision 乐观锁、只有发布者可修改。 */
    private fun updateSchedule(
        scheduleId: String,
        expectedRevision: Int?,
        permissionMessage: String,
        transform: (Schedule) -> Schedule
    ): Schedule = synchronized(lock) {
        val schedules = read().toMutableList()
        val index = schedules.indexOfFirst { it.id == scheduleId }
        val schedule = schedules.getOrNull(index) ?: throw ApiException("课表不存在")
        if (expectedRevision != null && schedule.revision != expectedRevision) {
            throw ApiException("课表已在其他页面更新，请刷新后重试")
        }
        if (!schedule.isOwner) throw ApiException(permissionMessage)
        val updated = transform(schedule)
        schedules[index] = updated
        write(schedules)
        updated
    }

    fun saveHoliday(
        scheduleId: String,
        draft: DayHolidayDraft,
        expectedRevision: Int? = null
    ): Schedule = updateSchedule(scheduleId, expectedRevision, "只有发布者可以设置调休") { schedule ->
        try {
            ScheduleValidator.validateHoliday(schedule, draft)
        } catch (error: IllegalArgumentException) {
            throw ApiException(error.message ?: "停课设置无效")
        }
        val timestamp = now()
        schedule.copy(
            revision = schedule.revision + 1,
            updatedAt = timestamp,
            holidays = schedule.holidays + DayHoliday(
                id = "holiday-" + UUID.randomUUID(),
                week = draft.week,
                day = draft.day,
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
    }

    fun deleteHoliday(
        scheduleId: String,
        holidayId: String,
        expectedRevision: Int? = null
    ): Schedule = updateSchedule(scheduleId, expectedRevision, "只有发布者可以撤销停课") { schedule ->
        val remaining = schedule.holidays.filterNot { it.id == holidayId }
        if (remaining.size == schedule.holidays.size) throw ApiException("停课记录不存在")
        schedule.copy(
            revision = schedule.revision + 1,
            updatedAt = now(),
            holidays = remaining
        )
    }

    fun saveMakeup(
        scheduleId: String,
        draft: DayMakeupDraft,
        expectedRevision: Int? = null
    ): Schedule = updateSchedule(scheduleId, expectedRevision, "只有发布者可以设置调休") { schedule ->
        try {
            ScheduleValidator.validateMakeup(schedule, draft)
        } catch (error: IllegalArgumentException) {
            throw ApiException(error.message ?: "补课设置无效")
        }
        val timestamp = now()
        schedule.copy(
            revision = schedule.revision + 1,
            updatedAt = timestamp,
            makeups = schedule.makeups + DayMakeup(
                id = "makeup-" + UUID.randomUUID(),
                sourceWeek = draft.sourceWeek,
                sourceDay = draft.sourceDay,
                targetWeek = draft.targetWeek,
                targetDay = draft.targetDay,
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
    }

    /** 批量设置停课日（连停多天一次保存）：一次 revision 自增，逐日做链式校验。 */
    fun saveHolidays(
        scheduleId: String,
        drafts: List<DayHolidayDraft>,
        expectedRevision: Int? = null
    ): Schedule = updateSchedule(scheduleId, expectedRevision, "只有发布者可以设置调休") { schedule ->
        if (drafts.isEmpty()) return@updateSchedule schedule
        var state = schedule
        val timestamp = now()
        val added = mutableListOf<DayHoliday>()
        drafts.forEach { draft ->
            try {
                ScheduleValidator.validateHoliday(state, draft)
            } catch (error: IllegalArgumentException) {
                throw ApiException(error.message ?: "停课设置无效")
            }
            val holiday = DayHoliday(
                id = "holiday-" + UUID.randomUUID(),
                week = draft.week,
                day = draft.day,
                createdAt = timestamp,
                updatedAt = timestamp
            )
            added += holiday
            state = state.copy(holidays = state.holidays + holiday)
        }
        state.copy(revision = schedule.revision + 1, updatedAt = timestamp)
    }

    fun deleteMakeup(
        scheduleId: String,
        makeupId: String,
        expectedRevision: Int? = null
    ): Schedule = updateSchedule(scheduleId, expectedRevision, "只有发布者可以撤销补课") { schedule ->
        val remaining = schedule.makeups.filterNot { it.id == makeupId }
        if (remaining.size == schedule.makeups.size) throw ApiException("补课记录不存在")
        schedule.copy(
            revision = schedule.revision + 1,
            updatedAt = now(),
            makeups = remaining
        )
    }

    /** 手动课程颜色（课程名 → #RRGGBB）整表替换：只有发布者可修改。 */
    fun setCourseColors(
        scheduleId: String,
        colors: Map<String, String>,
        expectedRevision: Int? = null
    ): Schedule = updateSchedule(scheduleId, expectedRevision, "只有发布者可以修改课程颜色") { schedule ->
        schedule.copy(
            revision = schedule.revision + 1,
            updatedAt = now(),
            courseColors = colors
        )
    }

    fun deleteAll() = synchronized(lock) {
        scheduleFile().delete()
        backupFile().delete()
        tempFile().delete()
        Prefs.removeLocalSchedules()
        Prefs.removeActiveSchedule()
    }

    private fun read(): List<Schedule> {
        val file = scheduleFile()
        val raw = when {
            file.exists() -> runCatching { file.readText() }.getOrNull()
            backupFile().exists() -> runCatching { backupFile().readText() }.getOrNull()
            else -> Prefs.localSchedulesJson
        } ?: return emptyList()
        val schedules = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(Schedule::fromJson)
            }
        }.getOrElse { emptyList() }
        if (!file.exists() && schedules.isNotEmpty()) {
            write(schedules)
            Prefs.removeLocalSchedules()
        }
        return schedules
    }

    private fun write(schedules: List<Schedule>) {
        val target = scheduleFile()
        val temp = tempFile()
        val backup = backupFile()
        target.parentFile?.mkdirs()
        FileOutputStream(temp).use { output ->
            output.write(JSONArray(schedules.map { it.toJson() }).toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (target.exists()) target.copyTo(backup, overwrite = true)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        Prefs.removeLocalSchedules()
        // 导入、调课、停课补课、同步都经这里落盘：统一在此重排上课提醒（异步，不占本锁）
        ClassReminders.requestSync()
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).run {
        timeZone = TimeZone.getTimeZone("UTC")
        format(Date())
    }

    private fun scheduleFile() = File(MainApplication.appContext.filesDir, "schedules/local_schedules_v2.json")
    private fun backupFile() = File(MainApplication.appContext.filesDir, "schedules/local_schedules_v2.backup.json")
    private fun tempFile() = File(MainApplication.appContext.filesDir, "schedules/local_schedules_v2.tmp")
}
