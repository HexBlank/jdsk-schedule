package com.zhusijiao.app.data

import com.zhusijiao.app.MainApplication
import com.zhusijiao.app.domain.CoupleMember
import com.zhusijiao.app.domain.CoupleState
import com.zhusijiao.app.domain.Schedule
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 情侣课表的本机状态：服务端最近一次返回的绑定状态、对方课表的缓存、还没推送上去的名字/颜色修改，
 * 以及「上次看到的我的名字/颜色」（判断要不要提示「TA 改了你的名字」）。
 *
 * 对方的课表单独存在这里，**不进课表库**：不影响当前课表、不影响上课提醒，解绑后直接清掉。
 */
object CoupleStore {

    /** 还没推送上去的修改；字段为 null 表示这一项没改。 */
    data class PendingProfile(val nickname: String?, val color: String?)

    data class Snapshot(
        val state: CoupleState,
        val partnerSchedule: Schedule?,
        /** 最近一次成功从服务端拉到状态的时间（毫秒）；0 表示从没成功过。 */
        val syncedAt: Long,
        /** "me" / "partner" → 待推送的修改。 */
        val pendingProfiles: Map<String, PendingProfile>,
        val seenNickname: String?,
        val seenColor: String?
    )

    private val EMPTY = Snapshot(CoupleState.UNBOUND, null, 0L, emptyMap(), null, null)

    private val lock = Any()
    @Volatile private var cached: Snapshot? = null

    fun snapshot(): Snapshot = synchronized(lock) { cached ?: read().also { cached = it } }

    /** 叠加了待推送修改的状态：本机改完立刻生效，不等服务端。 */
    fun effectiveState(): CoupleState {
        val snapshot = snapshot()
        val state = snapshot.state
        if (!state.bound || snapshot.pendingProfiles.isEmpty()) return state
        return state.copy(
            me = state.me?.let { apply(it, snapshot.pendingProfiles["me"]) },
            partner = state.partner?.let { apply(it, snapshot.pendingProfiles["partner"]) }
        )
    }

    private fun apply(member: CoupleMember, pending: PendingProfile?): CoupleMember {
        pending ?: return member
        return member.copy(
            nickname = pending.nickname ?: member.nickname,
            nicknameUpdatedBy = if (pending.nickname != null) "me" else member.nicknameUpdatedBy,
            color = pending.color ?: member.color,
            colorUpdatedBy = if (pending.color != null) "me" else member.colorUpdatedBy
        )
    }

    /** 接受邀请、生成邀请码后直接落地服务端返回的状态。 */
    fun saveState(state: CoupleState) = synchronized(lock) {
        val current = snapshot()
        write(withSeenInitialized(current.copy(state = state, partnerSchedule = current.partnerSchedule.takeIf { state.bound })))
    }

    /** 一次完整同步的结果。待推送修改保持不动（同步期间可能又有新修改）。 */
    fun saveSynced(state: CoupleState, partnerSchedule: Schedule?, syncedAt: Long) = synchronized(lock) {
        val current = snapshot()
        write(
            withSeenInitialized(
                current.copy(
                    state = state,
                    partnerSchedule = partnerSchedule.takeIf { state.bound },
                    syncedAt = syncedAt,
                    pendingProfiles = if (state.bound) current.pendingProfiles else emptyMap()
                )
            )
        )
    }

    /** 刚绑定时把当前名字/颜色记为「已看过」，不对绑定前的默认值发提示；解绑后清空。 */
    private fun withSeenInitialized(snapshot: Snapshot): Snapshot {
        val me = snapshot.state.me
        if (!snapshot.state.bound || me == null) return snapshot.copy(seenNickname = null, seenColor = null)
        if (snapshot.seenNickname != null && snapshot.seenColor != null) return snapshot
        return snapshot.copy(seenNickname = me.nickname, seenColor = me.color)
    }

    fun putPendingProfile(who: String, nickname: String?, color: String?) = synchronized(lock) {
        val current = snapshot()
        val previous = current.pendingProfiles[who]
        val merged = PendingProfile(nickname ?: previous?.nickname, color ?: previous?.color)
        write(current.copy(pendingProfiles = current.pendingProfiles + (who to merged)))
    }

    /** 推送成功（或注定失败）后移除；只有仍是推送出去的那一份时才移除，推送期间的新修改保留。 */
    fun removePendingProfile(who: String, pushed: PendingProfile) = synchronized(lock) {
        val current = snapshot()
        if (current.pendingProfiles[who] == pushed) write(current.copy(pendingProfiles = current.pendingProfiles - who))
    }

    /** 记下我当前的名字和颜色为「已看过」：提示关掉后、或我自己改完后调用。 */
    fun markSeen(nickname: String, color: String) = synchronized(lock) {
        val current = snapshot()
        if (current.seenNickname != nickname || current.seenColor != color) {
            write(current.copy(seenNickname = nickname, seenColor = color))
        }
    }

    fun clear() = synchronized(lock) {
        cached = EMPTY
        file().delete()
        tempFile().delete()
    }

    private fun read(): Snapshot = runCatching {
        val text = file().takeIf { it.exists() }?.readText() ?: return@runCatching EMPTY
        val o = JSONObject(text)
        val pending = mutableMapOf<String, PendingProfile>()
        o.optJSONObject("pendingProfiles")?.let { json ->
            for (who in json.keys()) {
                val item = json.optJSONObject(who) ?: continue
                pending[who] = PendingProfile(item.stringOrNull("nickname"), item.stringOrNull("color"))
            }
        }
        Snapshot(
            state = o.optJSONObject("state")?.let(CoupleState::fromJson) ?: CoupleState.UNBOUND,
            partnerSchedule = o.optJSONObject("partnerSchedule")?.let(Schedule::fromJson),
            syncedAt = o.optLong("syncedAt", 0L),
            pendingProfiles = pending,
            seenNickname = o.stringOrNull("seenNickname"),
            seenColor = o.stringOrNull("seenColor")
        )
    }.getOrDefault(EMPTY)

    private fun write(snapshot: Snapshot) {
        cached = snapshot
        val pending = JSONObject()
        snapshot.pendingProfiles.forEach { (who, profile) ->
            pending.put(
                who,
                JSONObject()
                    .put("nickname", profile.nickname ?: JSONObject.NULL)
                    .put("color", profile.color ?: JSONObject.NULL)
            )
        }
        val json = JSONObject()
            .put("state", snapshot.state.toJson())
            .put("partnerSchedule", snapshot.partnerSchedule?.toJson() ?: JSONObject.NULL)
            .put("syncedAt", snapshot.syncedAt)
            .put("pendingProfiles", pending)
            .put("seenNickname", snapshot.seenNickname ?: JSONObject.NULL)
            .put("seenColor", snapshot.seenColor ?: JSONObject.NULL)
        val target = file()
        val temp = tempFile()
        target.parentFile?.mkdirs()
        FileOutputStream(temp).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun JSONObject.stringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name)

    private fun file() = File(MainApplication.appContext.filesDir, "couple/couple_v1.json")
    private fun tempFile() = File(MainApplication.appContext.filesDir, "couple/couple_v1.tmp")
}
