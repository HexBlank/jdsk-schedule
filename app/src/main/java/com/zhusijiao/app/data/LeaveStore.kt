package com.zhusijiao.app.data

import com.zhusijiao.app.MainApplication
import com.zhusijiao.app.domain.Leave
import com.zhusijiao.app.domain.LeaveDraft
import com.zhusijiao.app.domain.Leaves
import com.zhusijiao.app.domain.toLeaveList
import com.zhusijiao.app.reminder.ClassReminders
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 请假记录的本机存储。
 *
 * **不进课表、不进同步、不按课表分组**：请假是这台手机主人自己的事，说的是「我这段时间不在」，
 * 不会随 `ApiClient.syncOwner` 上传，订阅同一份课表的同学、情侣课表里的 TA 都看不到。
 * 课表重新导入、切换当前课表都不影响请假记录；「删除全部数据」时一并清掉。
 */
object LeaveStore {

    /** 记录条数上限，防止本机文件无限增长；到上限后要先删掉旧的。 */
    const val MAX_LEAVES = 200

    private val lock = Any()

    /** 全部请假，按开始时间升序。会读本机文件，请在后台线程调用。 */
    fun list(): List<Leave> = synchronized(lock) { read().sortedBy { it.start } }

    /**
     * 新建（[leaveId] 为 null）或修改一条请假。
     * 校验失败、超出条数上限时抛 [ApiException]，message 即用户可读文案。
     * 两条请假的时间可以重叠（比如公假中间又请了半天病假），重叠时段按开始时间早的那条显示。
     */
    fun save(draft: LeaveDraft, leaveId: String?): Leave = synchronized(lock) {
        val normalized = try {
            Leaves.normalize(draft)
        } catch (error: IllegalArgumentException) {
            throw ApiException(error.message ?: "请假信息无效")
        }
        val all = read()
        val existing = leaveId?.let { id -> all.find { it.id == id } }
        if (leaveId != null && existing == null) throw ApiException("这条请假已经不存在了")
        if (existing == null && all.size >= MAX_LEAVES) {
            throw ApiException("请假记录最多 $MAX_LEAVES 条，请先删掉一些旧的")
        }
        val timestamp = now()
        val saved = Leave(
            id = existing?.id ?: "leave-${UUID.randomUUID()}",
            type = normalized.type,
            start = normalized.start,
            end = normalized.end,
            note = normalized.note,
            createdAt = existing?.createdAt ?: timestamp,
            updatedAt = timestamp
        )
        write(all.filterNot { it.id == saved.id } + saved)
        saved
    }

    fun delete(leaveId: String) = synchronized(lock) {
        val all = read()
        val remaining = all.filterNot { it.id == leaveId }
        if (remaining.size == all.size) throw ApiException("这条请假已经不存在了")
        write(remaining)
    }

    /** 撤销删除：把刚删掉的请假原样放回（id、创建时间都不变）。 */
    fun restore(leave: Leave) = synchronized(lock) {
        val all = read()
        if (all.any { it.id == leave.id }) return@synchronized
        write(all + leave)
    }

    fun clear() = synchronized(lock) {
        file().delete()
        tempFile().delete()
        ClassReminders.requestSync()
    }

    private fun read(): List<Leave> = runCatching {
        val raw = file().takeIf { it.exists() }?.readText() ?: return@runCatching emptyList()
        JSONArray(raw).toLeaveList()
    }.getOrDefault(emptyList())

    /** 与 LocalScheduleStore 一致的原子写：先写临时文件并 fsync，再整体替换。 */
    private fun write(leaves: List<Leave>) {
        val target = file()
        val temp = tempFile()
        target.parentFile?.mkdirs()
        FileOutputStream(temp).use { output ->
            output.write(JSONArray(leaves.map { it.toJson() }).toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        // 请假时段内的课不再提醒、桌面小部件上也要标出来：增删改后重排
        ClassReminders.requestSync()
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).run {
        timeZone = TimeZone.getTimeZone("UTC")
        format(Date())
    }

    private fun file() = File(MainApplication.appContext.filesDir, "leaves/leaves_v1.json")
    private fun tempFile() = File(MainApplication.appContext.filesDir, "leaves/leaves_v1.tmp")
}
