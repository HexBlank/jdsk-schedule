package com.zhusijiao.app.data

import com.zhusijiao.app.MainApplication
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 本地课表与服务端课表之间的轻量映射/待同步状态。
 *
 * 课表文件始终是 App 的可用数据源；这里的信息只决定联网后如何同步，绝不能影响离线读写。
 */
object ScheduleSyncStore {

    enum class PendingAction { DELETE, LEAVE }

    data class Record(
        val localId: String,
        val remoteId: String,
        val remoteRevision: Int,
        val dirty: Boolean,
        val pendingAction: PendingAction? = null,
        /** 最近一次推送失败的原因（用户可读）；推送成功或重新建立映射后清空。 */
        val lastError: String? = null
    )

    private val lock = Any()

    fun get(localId: String): Record? = synchronized(lock) { read().firstOrNull { it.localId == localId } }

    fun findByRemoteId(remoteId: String): Record? = synchronized(lock) {
        read().firstOrNull { it.remoteId == remoteId }
    }

    fun all(): List<Record> = synchronized(lock) { read() }

    fun link(localId: String, remoteId: String, remoteRevision: Int, dirty: Boolean = false) = synchronized(lock) {
        put(Record(localId, remoteId, remoteRevision, dirty))
    }

    fun markDirty(localId: String, fallbackRemoteId: String, fallbackRevision: Int) = synchronized(lock) {
        val existing = read().firstOrNull { it.localId == localId }
        put(
            (existing ?: Record(localId, fallbackRemoteId, fallbackRevision, dirty = false)).copy(
                dirty = true,
                pendingAction = null
            )
        )
    }

    fun updateRemoteRevision(localId: String, revision: Int, dirty: Boolean) = synchronized(lock) {
        val existing = read().firstOrNull { it.localId == localId } ?: return@synchronized
        put(existing.copy(remoteRevision = revision, dirty = dirty, pendingAction = null))
    }

    fun markPending(record: Record, action: PendingAction) = synchronized(lock) {
        put(record.copy(dirty = false, pendingAction = action, lastError = null))
    }

    fun setError(localId: String, message: String?) = synchronized(lock) {
        val existing = read().firstOrNull { it.localId == localId } ?: return@synchronized
        if (existing.lastError != message) put(existing.copy(lastError = message))
    }

    fun remove(localId: String) = synchronized(lock) {
        write(read().filterNot { it.localId == localId })
    }

    fun clear() = synchronized(lock) {
        file().delete()
        tempFile().delete()
    }

    private fun put(record: Record) {
        val records = read().filterNot { it.localId == record.localId }.toMutableList()
        records.add(record)
        write(records)
    }

    private fun read(): List<Record> = runCatching {
        val array = file().takeIf { it.exists() }?.readText()?.let(::JSONArray) ?: return@runCatching emptyList()
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val localId = item.optString("localId")
            val remoteId = item.optString("remoteId")
            if (localId.isBlank() || remoteId.isBlank()) return@mapNotNull null
            Record(
                localId = localId,
                remoteId = remoteId,
                remoteRevision = item.optInt("remoteRevision", 1),
                dirty = item.optBoolean("dirty"),
                pendingAction = item.optString("pendingAction").takeIf { it.isNotBlank() }
                    ?.let { runCatching { PendingAction.valueOf(it) }.getOrNull() },
                lastError = item.optString("lastError").takeIf { it.isNotBlank() }
            )
        }
    }.getOrDefault(emptyList())

    private fun write(records: List<Record>) {
        val target = file()
        val temp = tempFile()
        target.parentFile?.mkdirs()
        val json = JSONArray(records.map { record ->
            JSONObject()
                .put("localId", record.localId)
                .put("remoteId", record.remoteId)
                .put("remoteRevision", record.remoteRevision)
                .put("dirty", record.dirty)
                .put("pendingAction", record.pendingAction?.name)
                .put("lastError", record.lastError)
        })
        FileOutputStream(temp).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun file() = File(MainApplication.appContext.filesDir, "sync/schedule_sync_v1.json")
    private fun tempFile() = File(MainApplication.appContext.filesDir, "sync/schedule_sync_v1.tmp")
}
