package com.zhusijiao.app.data

import android.net.Uri
import com.zhusijiao.app.AppConfig
import com.zhusijiao.app.domain.ParsedSchedule
import com.zhusijiao.app.domain.CourseAdjustmentDraft
import com.zhusijiao.app.domain.DayHolidayDraft
import com.zhusijiao.app.domain.DayMakeupDraft
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.SharePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 统一的接口异常，message 即用户可读文案；status 为 HTTP 状态码（非 HTTP 错误为 0）。 */
class ApiException(
    message: String,
    val networkFailure: Boolean = false,
    val status: Int = 0
) : Exception(message)

/**
 * 本地优先的数据入口。课表始终先写入本机；配置后端只会增加分享和同步能力。
 * 身份：匿名设备 id → /api/v1/auth/device → 30 天 JWT；401 时自动重登一次重试。
 */
object ApiClient {

    val isLocalMode: Boolean get() = AppConfig.isLocalMode
    @Volatile var lastReadWasOffline: Boolean = false
        private set

    /**
     * 服务器地址变更（设置页切换/恢复默认）后调用：
     * 丢弃旧服务器的登录态，下一次请求会在新服务器重新设备登录。
     * 同步绑定（本机课表 ↔ 远端课表映射）由调用方按需 [ScheduleSyncStore.clear] 清理。
     */
    fun resetSession() {
        Prefs.removeToken()
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val loginMutex = Mutex()
    private val migrationMutex = Mutex()
    private val syncMutex = Mutex()
    @Volatile private var cacheMigrated = false

    // ===== 业务接口 =====

    suspend fun healthCheck(): Boolean {
        if (isLocalMode) return true
        return runCatching {
            val (code, text) = call("GET", "/healthz", null, null)
            code == 200 && parseOrEmpty(text).optBoolean("ok")
        }.getOrDefault(false)
    }

    suspend fun listSchedules(): List<Schedule> {
        migrateReadOnlyCache()
        return withContext(Dispatchers.IO) { LocalScheduleStore.listSchedules() }
    }

    suspend fun getSchedule(id: String): Schedule {
        migrateReadOnlyCache()
        return withContext(Dispatchers.IO) { LocalScheduleStore.getSchedule(id) }
    }

    suspend fun saveSchedule(input: ParsedSchedule, id: String?, expectedRevision: Int? = null): Schedule {
        migrateReadOnlyCache()
        return withContext(Dispatchers.IO) {
            val before = id?.let(LocalScheduleStore::getScheduleOrNull)
            val saved = LocalScheduleStore.saveSchedule(input, id, expectedRevision)
            before?.let { markDirtyIfShared(it) }
            saved
        }
    }

    suspend fun previewShareCode(code: String): SharePreview {
        requireOnlineSharing()
        val r = request("POST", "/api/v1/share/preview", JSONObject().put("code", code))
        val preview = SharePreview.fromJson(r.optJSONObject("schedule") ?: JSONObject())
        // 服务端认为已加入/已拥有，但本机没有可用副本（例如已在本机移除、退出请求还没同步上去）：
        // 此时「打开课表」会指向不存在的课表而回退到别的课表，必须按未加入处理，走一次加入把副本落回本机。
        val present = withContext(Dispatchers.IO) { hasLocalCopy(preview.id) }
        return if ((preview.joined || preview.owned) && !present) {
            preview.copy(joined = false, owned = false)
        } else preview
    }

    /** 本机是否有指向该远端课表、且没有待删除/待退出的副本。 */
    private fun hasLocalCopy(remoteId: String): Boolean {
        val record = ScheduleSyncStore.findByRemoteId(remoteId)
        if (record != null) {
            return record.pendingAction == null && LocalScheduleStore.getScheduleOrNull(record.localId) != null
        }
        return LocalScheduleStore.getScheduleOrNull(remoteId) != null
    }

    suspend fun joinShareCode(code: String): Schedule {
        requireOnlineSharing()
        val r = request("POST", "/api/v1/share/join", JSONObject().put("code", code))
        val remote = Schedule.fromJson(r.optJSONObject("schedule") ?: JSONObject())
        return withContext(Dispatchers.IO) {
            // 已有指向同一远端课表的本地副本（例如自己分享过这份课表）时，
            // 更新原副本而不是再添加一份，避免课表库出现两张相同的课表。
            val existing = ScheduleSyncStore.findByRemoteId(remote.id)
            val localId = existing?.localId
                ?.takeIf { LocalScheduleStore.getScheduleOrNull(it) != null }
                ?: remote.id
            LocalScheduleStore.putRemoteSchedule(remote, localId)
            ScheduleSyncStore.link(localId, remote.id, remote.revision)
            remote.copy(id = localId)
        }
    }

    suspend fun rotateShareCode(id: String): String {
        requireOnlineSharing()
        val record = withContext(Dispatchers.IO) { ScheduleSyncStore.get(id) }
            ?: throw ApiException("请先联网发布这份课表")
        val r = request("POST", "/api/v1/schedules/${enc(record.remoteId)}/share-code")
        val code = r.optString("shareCode")
        withContext(Dispatchers.IO) {
            LocalScheduleStore.getScheduleOrNull(id)?.let { LocalScheduleStore.putSchedule(it.copy(shareCode = code)) }
        }
        return code
    }

    suspend fun leaveSchedule(id: String) {
        migrateReadOnlyCache()
        withContext(Dispatchers.IO) {
            val schedule = LocalScheduleStore.getScheduleOrNull(id)
            val record = syncRecordFor(schedule)
            LocalScheduleStore.deleteSchedule(id)
            PersonalEventStore.removeSchedule(id)
            if (record != null) ScheduleSyncStore.markPending(record, ScheduleSyncStore.PendingAction.LEAVE)
        }
    }

    suspend fun deleteSchedule(id: String) {
        migrateReadOnlyCache()
        withContext(Dispatchers.IO) {
            val schedule = LocalScheduleStore.getScheduleOrNull(id)
            val record = syncRecordFor(schedule)
            LocalScheduleStore.deleteSchedule(id)
            PersonalEventStore.removeSchedule(id)
            if (record != null) ScheduleSyncStore.markPending(record, ScheduleSyncStore.PendingAction.DELETE)
        }
    }

    suspend fun deleteAccount() {
        withContext(Dispatchers.IO) {
            LocalScheduleStore.deleteAll()
            PersonalEventStore.clear()
        }
        if (!isLocalMode) {
            // 删除本机数据绝不依赖网络；服务端删除只做短时尽力尝试。
            withTimeoutOrNull(2_000) { runCatching { request("DELETE", "/api/v1/me") } }
        }
        withContext(Dispatchers.IO) {
            ScheduleSyncStore.clear()
            ScheduleCache.clear()
            cacheMigrated = true
            Prefs.clearAllIdentityAndData()
        }
    }

    /** 分享页调用：首次分享会发布本机课表，之后先同步本机修改再显示分享码。 */
    suspend fun prepareShare(localId: String): Schedule = syncMutex.withLock {
        requireOnlineSharing()
        migrateReadOnlyCache()
        val local = withContext(Dispatchers.IO) { LocalScheduleStore.getSchedule(localId) }
        if (!local.isOwner) throw ApiException("只有课表发布者可以分享")
        var record = withContext(Dispatchers.IO) { ScheduleSyncStore.get(localId) }
        if (record == null) {
            val created = scheduleFrom(
                request("POST", "/api/v1/schedules", local.toParsedSchedule().toJson())
            )
            withContext(Dispatchers.IO) {
                ScheduleSyncStore.link(
                    localId,
                    created.id,
                    created.revision,
                    dirty = local.adjustments.isNotEmpty() ||
                        local.holidays.isNotEmpty() || local.makeups.isNotEmpty()
                )
            }
            record = withContext(Dispatchers.IO) { ScheduleSyncStore.get(localId) }
            if (local.adjustments.isEmpty() && local.holidays.isEmpty() && local.makeups.isEmpty()) {
                return@withLock storeRemote(created, localId, expectedLocal = local)
            }
        }
        val linked = requireNotNull(record)
        if (linked.dirty) syncOwner(localId, linked)
        else {
            val remote = scheduleFrom(request("GET", "/api/v1/schedules/${enc(linked.remoteId)}"))
            storeRemote(remote, localId)
        }
    }

    /**
     * 尝试同步，不成功只更新状态并返回 false；调用方已经在使用本机数据，不能转入错误页。
     */
    suspend fun syncSchedules(): Boolean {
        if (isLocalMode) return false
        migrateReadOnlyCache()
        return syncMutex.withLock {
            lastReadWasOffline = false
            try {
                val pendingError = processPendingWrites()
                // 必须在拉列表之前取快照：拉取期间新加入的课表不在快照里，不会被误清理。
                val knownBefore = withContext(Dispatchers.IO) { ScheduleSyncStore.all() }
                val response = request("GET", "/api/v1/schedules")
                val array = response.optJSONArray("schedules")
                val summaries = (0 until (array?.length() ?: 0)).map {
                    Schedule.fromJson(array?.optJSONObject(it) ?: JSONObject())
                }
                withContext(Dispatchers.IO) {
                    ScheduleCache.putList(summaries)
                    pruneRemovedSubscriptions(knownBefore, summaries.map { it.id }.toSet())
                }
                for (summary in summaries) {
                    val record = withContext(Dispatchers.IO) { ScheduleSyncStore.findByRemoteId(summary.id) }
                    if (record?.dirty == true || record?.pendingAction != null) continue
                    val remote = scheduleFrom(request("GET", "/api/v1/schedules/${enc(summary.id)}"))
                    storeRemote(remote, record?.localId ?: remote.id)
                }
                if (pendingError != null) throw pendingError
                true
            } catch (error: Exception) {
                lastReadWasOffline = error is ApiException && error.networkFailure
                false
            }
        }
    }

    fun isShared(id: String): Boolean = ScheduleSyncStore.get(id) != null

    fun isSyncPending(id: String): Boolean = ScheduleSyncStore.get(id)?.let {
        it.dirty || it.pendingAction != null
    } == true

    // ===== 网络与鉴权 =====

    private fun requireOnlineSharing() {
        if (isLocalMode) throw ApiException("分享与加入课表需要先配置数据服务")
    }

    suspend fun saveAdjustment(
        scheduleId: String,
        draft: CourseAdjustmentDraft,
        adjustmentId: String? = null,
        expectedRevision: Int
    ): Schedule {
        migrateReadOnlyCache()
        return withContext(Dispatchers.IO) {
            val before = LocalScheduleStore.getSchedule(scheduleId)
            val saved = LocalScheduleStore.saveAdjustment(scheduleId, draft, adjustmentId, expectedRevision)
            markDirtyIfShared(before)
            saved
        }
    }

    suspend fun deleteAdjustment(scheduleId: String, adjustmentId: String, expectedRevision: Int): Schedule {
        migrateReadOnlyCache()
        return withContext(Dispatchers.IO) {
            val before = LocalScheduleStore.getSchedule(scheduleId)
            val saved = LocalScheduleStore.deleteAdjustment(scheduleId, adjustmentId, expectedRevision)
            markDirtyIfShared(before)
            saved
        }
    }

    suspend fun saveHoliday(scheduleId: String, draft: DayHolidayDraft, expectedRevision: Int): Schedule {
        migrateReadOnlyCache()
        return withContext(Dispatchers.IO) {
            val before = LocalScheduleStore.getSchedule(scheduleId)
            val saved = LocalScheduleStore.saveHoliday(scheduleId, draft, expectedRevision)
            markDirtyIfShared(before)
            saved
        }
    }

    suspend fun deleteHoliday(scheduleId: String, holidayId: String, expectedRevision: Int): Schedule {
        migrateReadOnlyCache()
        return withContext(Dispatchers.IO) {
            val before = LocalScheduleStore.getSchedule(scheduleId)
            val saved = LocalScheduleStore.deleteHoliday(scheduleId, holidayId, expectedRevision)
            markDirtyIfShared(before)
            saved
        }
    }

    suspend fun saveHolidays(scheduleId: String, drafts: List<DayHolidayDraft>, expectedRevision: Int): Schedule {
        migrateReadOnlyCache()
        return withContext(Dispatchers.IO) {
            val before = LocalScheduleStore.getSchedule(scheduleId)
            val saved = LocalScheduleStore.saveHolidays(scheduleId, drafts, expectedRevision)
            markDirtyIfShared(before)
            saved
        }
    }

    suspend fun saveMakeup(scheduleId: String, draft: DayMakeupDraft, expectedRevision: Int): Schedule {
        migrateReadOnlyCache()
        return withContext(Dispatchers.IO) {
            val before = LocalScheduleStore.getSchedule(scheduleId)
            val saved = LocalScheduleStore.saveMakeup(scheduleId, draft, expectedRevision)
            markDirtyIfShared(before)
            saved
        }
    }

    suspend fun setCourseColors(
        scheduleId: String,
        colors: Map<String, String>,
        expectedRevision: Int
    ): Schedule {
        migrateReadOnlyCache()
        return withContext(Dispatchers.IO) {
            val before = LocalScheduleStore.getSchedule(scheduleId)
            val saved = LocalScheduleStore.setCourseColors(scheduleId, colors, expectedRevision)
            markDirtyIfShared(before)
            saved
        }
    }

    suspend fun deleteMakeup(scheduleId: String, makeupId: String, expectedRevision: Int): Schedule {
        migrateReadOnlyCache()
        return withContext(Dispatchers.IO) {
            val before = LocalScheduleStore.getSchedule(scheduleId)
            val saved = LocalScheduleStore.deleteMakeup(scheduleId, makeupId, expectedRevision)
            markDirtyIfShared(before)
            saved
        }
    }

    /**
     * 逐条推送本机待同步写入。每条记录互不影响：某一条失败（如发布者的课表版本冲突）
     * 不能卡住其余记录，否则「退出课表」会永远发不出去、订阅的课表也再也刷新不了。
     * 返回第一条失败的异常，由调用方在拉取完远端数据后再上报。
     */
    private suspend fun processPendingWrites(): Exception? {
        var firstError: Exception? = null
        for (record in withContext(Dispatchers.IO) { ScheduleSyncStore.all() }) {
            try {
                when (record.pendingAction) {
                    ScheduleSyncStore.PendingAction.DELETE -> {
                        requestIgnoringNotFound("DELETE", "/api/v1/schedules/${enc(record.remoteId)}")
                        withContext(Dispatchers.IO) { ScheduleSyncStore.remove(record.localId) }
                    }
                    ScheduleSyncStore.PendingAction.LEAVE -> {
                        requestIgnoringNotFound("DELETE", "/api/v1/schedules/${enc(record.remoteId)}/membership")
                        withContext(Dispatchers.IO) { ScheduleSyncStore.remove(record.localId) }
                    }
                    null -> if (record.dirty) syncOwner(record.localId, record)
                }
            } catch (error: Exception) {
                if (error is ApiException && error.networkFailure) throw error
                if (firstError == null) firstError = error
            }
        }
        return firstError
    }

    /**
     * 清理服务端已不再返回的订阅副本（发布者删了课表或把自己移出）。
     * 不清理的话副本会一直留在本机，用户再移除时产生一条永远 404 的退出请求。
     * 只动订阅者副本：发布者的本机课表是权威数据，服务端丢了也不能删。
     */
    private fun pruneRemovedSubscriptions(knownBefore: List<ScheduleSyncStore.Record>, remoteIds: Set<String>) {
        for (record in knownBefore) {
            if (record.remoteId in remoteIds || record.dirty || record.pendingAction != null) continue
            val local = LocalScheduleStore.getScheduleOrNull(record.localId)
            if (local != null && local.isOwner) continue
            LocalScheduleStore.deleteSchedule(record.localId)
            PersonalEventStore.removeSchedule(record.localId)
            ScheduleSyncStore.remove(record.localId)
            if (Prefs.activeScheduleId == record.localId) Prefs.removeActiveSchedule()
        }
    }

    /** 删除/退出类请求：服务端已经不存在（404）说明目标状态已达成，按成功处理。 */
    private suspend fun requestIgnoringNotFound(method: String, path: String) {
        try {
            request(method, path)
        } catch (error: ApiException) {
            if (error.status != 404) throw error
        }
    }

    private suspend fun syncOwner(localId: String, record: ScheduleSyncStore.Record): Schedule {
        val local = withContext(Dispatchers.IO) { LocalScheduleStore.getSchedule(localId) }
        var remote = scheduleFrom(
            request(
                "PUT",
                "/api/v1/schedules/${enc(record.remoteId)}",
                local.toParsedSchedule().toJson().put("_expectedRevision", record.remoteRevision)
            )
        )
        withContext(Dispatchers.IO) {
            ScheduleSyncStore.updateRemoteRevision(localId, remote.revision, dirty = true)
        }
        for (adjustment in local.adjustments) {
            remote = scheduleFrom(
                request(
                    "POST",
                    "/api/v1/schedules/${enc(record.remoteId)}/adjustments",
                    CourseAdjustmentDraft.from(adjustment).toJson().put("_expectedRevision", remote.revision)
                )
            )
            withContext(Dispatchers.IO) {
                ScheduleSyncStore.updateRemoteRevision(localId, remote.revision, dirty = true)
            }
        }
        for (holiday in local.holidays) {
            remote = scheduleFrom(
                request(
                    "POST",
                    "/api/v1/schedules/${enc(record.remoteId)}/holidays",
                    DayHolidayDraft(holiday.week, holiday.day).toJson()
                        .put("_expectedRevision", remote.revision)
                )
            )
            withContext(Dispatchers.IO) {
                ScheduleSyncStore.updateRemoteRevision(localId, remote.revision, dirty = true)
            }
        }
        for (makeup in local.makeups) {
            remote = scheduleFrom(
                request(
                    "POST",
                    "/api/v1/schedules/${enc(record.remoteId)}/makeups",
                    DayMakeupDraft(
                        makeup.sourceWeek, makeup.sourceDay, makeup.targetWeek, makeup.targetDay
                    ).toJson().put("_expectedRevision", remote.revision)
                )
            )
            withContext(Dispatchers.IO) {
                ScheduleSyncStore.updateRemoteRevision(localId, remote.revision, dirty = true)
            }
        }
        remote = scheduleFrom(
            request(
                "PUT",
                "/api/v1/schedules/${enc(record.remoteId)}/course-colors",
                JSONObject().put("colors", JSONObject(local.courseColors))
                    .put("_expectedRevision", remote.revision)
            )
        )
        withContext(Dispatchers.IO) {
            ScheduleSyncStore.updateRemoteRevision(localId, remote.revision, dirty = true)
        }
        return storeRemote(
            remote,
            localId,
            expectedLocal = local,
            completesDirtySync = true
        )
    }

    private suspend fun storeRemote(
        remote: Schedule,
        localId: String,
        expectedLocal: Schedule? = null,
        completesDirtySync: Boolean = false
    ): Schedule {
        val localized = remote.copy(id = localId)
        return withContext(Dispatchers.IO) {
            val current = LocalScheduleStore.getScheduleOrNull(localId)
            val record = ScheduleSyncStore.get(localId)
            val changedDuringSync = expectedLocal != null && current != null &&
                (current.revision != expectedLocal.revision || current.updatedAt != expectedLocal.updatedAt)
            val preserveLocal = record?.pendingAction != null || changedDuringSync ||
                (!completesDirtySync && record?.dirty == true)
            ScheduleCache.putDetail(remote)
            if (preserveLocal) {
                if (current == null) {
                    // 本地副本缺失时直接落地远端数据，避免留下幽灵同步记录导致课表看似消失。
                    LocalScheduleStore.putSchedule(localized)
                    ScheduleSyncStore.link(localId, remote.id, remote.revision)
                    return@withContext localized
                }
                if (record?.pendingAction == null) {
                    ScheduleSyncStore.link(localId, remote.id, remote.revision, dirty = true)
                }
                current
            } else {
                LocalScheduleStore.putSchedule(localized)
                ScheduleSyncStore.link(localId, remote.id, remote.revision)
                localized
            }
        }
    }

    private fun markDirtyIfShared(schedule: Schedule) {
        val record = syncRecordFor(schedule) ?: return
        ScheduleSyncStore.markDirty(schedule.id, record.remoteId, record.remoteRevision)
    }

    private fun syncRecordFor(schedule: Schedule?): ScheduleSyncStore.Record? {
        schedule ?: return null
        return ScheduleSyncStore.get(schedule.id)
            ?: schedule.id.takeUnless { it.startsWith("local-") }?.let {
                ScheduleSyncStore.Record(schedule.id, schedule.id, schedule.revision, dirty = false)
            }
    }

    private suspend fun migrateReadOnlyCache() {
        if (cacheMigrated) return
        migrationMutex.withLock {
            if (cacheMigrated) return@withLock
            withContext(Dispatchers.IO) {
                for (summary in ScheduleCache.list().orEmpty()) {
                    val detail = ScheduleCache.detail(summary.id) ?: continue
                    // 同一份远端课表可能已有本地副本（本地 id 与远端 id 不同）：
                    // 必须先查同步映射，否则冷启动会凭远端 id 重复落地一份，
                    // 导致课表库出现两张相同的课表。
                    val linked = ScheduleSyncStore.findByRemoteId(detail.id)
                    // 本机已删除/退出、等待同步到服务端的课表不能从旧缓存复活，
                    // 否则 link() 会抹掉待同步的删除/退出动作。
                    if (linked?.pendingAction != null) continue
                    val localId = linked?.localId ?: detail.id
                    if (LocalScheduleStore.getScheduleOrNull(localId) == null) {
                        LocalScheduleStore.putRemoteSchedule(detail, localId)
                        ScheduleSyncStore.link(localId, detail.id, detail.revision)
                    }
                }
                dedupeLinkedCopies()
            }
            cacheMigrated = true
        }
    }

    /**
     * 清理历史版本产生的重复课表：当多个本地副本指向同一个远端 id 时只保留一份
     * （优先保留当前课表），并同步修正当前课表指针。
     * 背景：旧版迁移逻辑会在冷启动后凭远端 id 重复落地副本，造成课表库出现两张
     * 相同的课表、当前标记来回跳，且删除任意一张都会误删云端共享课表。
     */
    private fun dedupeLinkedCopies() {
        val activeId = Prefs.activeScheduleId
        for (group in ScheduleSyncStore.all().groupBy { it.remoteId }.values) {
            if (group.size <= 1) continue
            val keep = group.firstOrNull { it.localId == activeId && LocalScheduleStore.getScheduleOrNull(it.localId) != null }
                ?: group.firstOrNull { LocalScheduleStore.getScheduleOrNull(it.localId) != null }
                ?: continue
            for (record in group) {
                if (record.localId == keep.localId) continue
                LocalScheduleStore.deleteSchedule(record.localId)
                ScheduleSyncStore.remove(record.localId)
                if (Prefs.activeScheduleId == record.localId) Prefs.activeScheduleId = keep.localId
            }
        }
    }

    private fun Schedule.toParsedSchedule() = ParsedSchedule(
        name = name,
        semesterStart = semesterStart,
        totalWeeks = totalWeeks,
        timeSlots = timeSlots,
        courses = courses,
        school = school
    )

    private fun scheduleFrom(response: JSONObject): Schedule =
        Schedule.fromJson(response.optJSONObject("schedule") ?: JSONObject())

    private fun enc(s: String) = Uri.encode(s)

    private suspend fun request(method: String, path: String, body: JSONObject? = null, retried: Boolean = false): JSONObject {
        val token = ensureToken()
        val (code, text) = call(method, path, body, token)
        if (code == 401 && !retried) {
            Prefs.removeToken()
            ensureToken(force = true)
            return request(method, path, body, true)
        }
        val json = parseOrEmpty(text)
        if (code !in 200..299) throw ApiException(errorMessage(json, code), status = code)
        return json
    }

    private suspend fun ensureToken(force: Boolean = false): String {
        if (!force) Prefs.token?.let { return it }
        return login()
    }

    private suspend fun login(): String = loginMutex.withLock {
        Prefs.token?.let { return@withLock it }
        val path: String
        val body: JSONObject
        if (AppConfig.DEV_AUTH_OPENID.isNotBlank()) {
            path = "/api/v1/auth/dev"
            body = JSONObject().put("openid", AppConfig.DEV_AUTH_OPENID)
        } else {
            path = "/api/v1/auth/device"
            body = JSONObject().put("deviceId", Prefs.deviceId)
        }
        val (code, text) = call("POST", path, body, null)
        val json = parseOrEmpty(text)
        if (code !in 200..299) throw ApiException(errorMessage(json, code, "登录失败"))
        val token = json.optString("token")
        if (token.isEmpty()) throw ApiException("登录失败")
        Prefs.token = token
        token
    }

    private suspend fun call(method: String, path: String, body: JSONObject?, token: String?): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(buildRequest(method, path, body, token))
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        ApiException("网络连接失败，请检查网络或稍后重试", networkFailure = true)
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (continuation.isActive) continuation.resume(it.code to (it.body?.string() ?: ""))
                    }
                }
            })
        }

    private fun buildRequest(method: String, path: String, body: JSONObject?, token: String?): Request {
        val jsonStr = body?.toString()
        val builder = Request.Builder().url(AppConfig.apiBase + path)
        if (token != null) builder.header("Authorization", "Bearer $token")
        when (method.uppercase()) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post((jsonStr ?: "{}").toRequestBody(JSON_MEDIA))
            "PUT" -> builder.put((jsonStr ?: "{}").toRequestBody(JSON_MEDIA))
            else -> builder.method(method.uppercase(), jsonStr?.toRequestBody(JSON_MEDIA))
        }
        return builder.build()
    }

    private fun parseOrEmpty(text: String): JSONObject =
        if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrDefault(JSONObject())

    private fun errorMessage(json: JSONObject, code: Int, fallback: String = "请求失败（$code）"): String {
        val msg = json.optJSONObject("error")?.optString("message")
        return if (!msg.isNullOrEmpty()) msg else fallback
    }
}
