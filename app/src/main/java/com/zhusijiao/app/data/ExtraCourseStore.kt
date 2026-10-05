package com.zhusijiao.app.data

import com.zhusijiao.app.MainApplication
import com.zhusijiao.app.domain.Course
import com.zhusijiao.app.domain.ExtraCourses
import com.zhusijiao.app.domain.toCourseList
import com.zhusijiao.app.reminder.ClassReminders
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 自己加的课的本机存储，按 scheduleId 分组。
 *
 * 与日程一样**不进课表、不进同步**：不会随 `ApiClient.syncOwner` 上传，也不会被 `storeRemote`
 * 的远端副本覆盖，所以订阅别人课表的同学同样能加自己的课。教务重新导入覆盖课表时
 * （scheduleId 不变）自己加的课自动保留。读出来的课要用 [ExtraCourses.merge] 并进课表才会显示。
 */
object ExtraCourseStore {

    private val lock = Any()

    /** 这份课表名下自己加的课。会读本机文件，请在后台线程调用。 */
    fun list(scheduleId: String): List<Course> = synchronized(lock) {
        read()[scheduleId].orEmpty().sortedWith(compareBy({ it.day }, { it.startSection }))
    }

    /**
     * 新建（[courseId] 为 null）或修改一门自己加的课。
     * 校验失败、超出条数上限时抛 [ApiException]，message 即用户可读文案。
     * 与别的课时间重叠不拦——重修课和原班课撞在一起是常态，课表会并排显示。
     */
    fun save(scheduleId: String, draft: Course, courseId: String?, totalWeeks: Int): Course = synchronized(lock) {
        val normalized = try {
            ExtraCourses.normalize(draft, totalWeeks)
        } catch (error: IllegalArgumentException) {
            throw ApiException(error.message ?: "课程信息无效")
        }
        val all = read().toMutableMap()
        val courses = all[scheduleId].orEmpty()
        val existing = courseId?.let { id -> courses.find { it.id == id } }
        if (courseId != null && existing == null) throw ApiException("这门课已经不存在了")
        if (existing == null && courses.size >= ExtraCourses.MAX_PER_SCHEDULE) {
            throw ApiException("一份课表最多自己加 ${ExtraCourses.MAX_PER_SCHEDULE} 门课")
        }
        val saved = normalized.copy(id = existing?.id ?: "${ExtraCourses.ID_PREFIX}${UUID.randomUUID()}")
        all[scheduleId] = courses.filterNot { it.id == saved.id } + saved
        write(all)
        saved
    }

    fun delete(scheduleId: String, courseId: String) = synchronized(lock) {
        val all = read().toMutableMap()
        val courses = all[scheduleId].orEmpty()
        val remaining = courses.filterNot { it.id == courseId }
        if (remaining.size == courses.size) throw ApiException("这门课已经不存在了")
        if (remaining.isEmpty()) all.remove(scheduleId) else all[scheduleId] = remaining
        write(all)
    }

    /** 撤销删除：把刚删掉的课原样放回（id 不变）。 */
    fun restore(scheduleId: String, course: Course) = synchronized(lock) {
        val all = read().toMutableMap()
        val courses = all[scheduleId].orEmpty()
        if (courses.any { it.id == course.id }) return@synchronized
        all[scheduleId] = courses + course
        write(all)
    }

    /** 课表被删除或退出时清理它名下自己加的课。 */
    fun removeSchedule(scheduleId: String) = synchronized(lock) {
        val all = read().toMutableMap()
        if (all.remove(scheduleId) != null) write(all)
    }

    fun clear() = synchronized(lock) {
        file().delete()
        tempFile().delete()
    }

    private fun read(): Map<String, List<Course>> = runCatching {
        val raw = file().takeIf { it.exists() }?.readText() ?: return@runCatching emptyMap()
        val root = JSONObject(raw)
        val result = mutableMapOf<String, List<Course>>()
        for (key in root.keys()) {
            val courses = root.optJSONArray(key).toCourseList().filter { course ->
                ExtraCourses.isExtra(course) && course.name.isNotBlank() && course.day in 1..7 &&
                    course.startSection >= 1 && course.endSection >= course.startSection && course.weeks.isNotEmpty()
            }
            if (key.isNotBlank() && courses.isNotEmpty()) result[key] = courses
        }
        result
    }.getOrDefault(emptyMap())

    /** 与 LocalScheduleStore 一致的原子写：先写临时文件并 fsync，再整体替换。 */
    private fun write(courses: Map<String, List<Course>>) {
        val target = file()
        val temp = tempFile()
        target.parentFile?.mkdirs()
        val root = JSONObject()
        courses.forEach { (scheduleId, list) ->
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
        // 自己加的课和导入的课一样要提醒、要显示在桌面小部件上：增删改后重排
        ClassReminders.requestSync()
    }

    private fun file() = File(MainApplication.appContext.filesDir, "extras/extra_courses_v1.json")
    private fun tempFile() = File(MainApplication.appContext.filesDir, "extras/extra_courses_v1.tmp")
}
