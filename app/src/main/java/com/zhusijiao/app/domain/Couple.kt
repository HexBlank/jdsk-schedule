package com.zhusijiao.app.domain

import org.json.JSONObject

/**
 * 情侣一方的名字和颜色。名字和颜色跟着人走，两部手机上同一个人一致，双方都能改。
 * [nicknameUpdatedBy] / [colorUpdatedBy] 是最后修改人，相对当前用户："me"、"partner" 或 null（从没改过）。
 */
data class CoupleMember(
    val nickname: String,
    val nicknameUpdatedBy: String?,
    val color: String,
    val colorUpdatedBy: String?,
    val revision: Int,
    val updatedAt: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("nickname", nickname)
        .put("nicknameUpdatedBy", nicknameUpdatedBy ?: JSONObject.NULL)
        .put("color", color)
        .put("colorUpdatedBy", colorUpdatedBy ?: JSONObject.NULL)
        .put("revision", revision)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject, fallbackColor: String) = CoupleMember(
            nickname = o.stringOrNull("nickname").orEmpty(),
            nicknameUpdatedBy = o.stringOrNull("nicknameUpdatedBy"),
            color = CouplePalette.normalize(o.stringOrNull("color")) ?: fallbackColor,
            colorUpdatedBy = o.stringOrNull("colorUpdatedBy"),
            revision = o.optInt("revision", 1),
            updatedAt = o.stringOrNull("updatedAt").orEmpty()
        )
    }
}

data class CoupleInvite(val code: String, val expiresAt: String)

/** 对方当前课表的摘要：只有 id 和版本号，用来判断要不要重新拉完整课表。 */
data class PartnerScheduleRef(val id: String, val revision: Int, val updatedAt: String)

/**
 * `GET /api/v1/couple` 的结果，也原样存在本机（[com.zhusijiao.app.data.CoupleStore]）。
 * 未绑定时只有 [invite]（自己发出、还没被接受的邀请码）。
 */
data class CoupleState(
    val bound: Boolean,
    val invite: CoupleInvite? = null,
    val coupleId: String? = null,
    val since: String? = null,
    val me: CoupleMember? = null,
    /** 服务端记录的我的当前课表（远端 id）。 */
    val myCurrentScheduleId: String? = null,
    val partner: CoupleMember? = null,
    val partnerSchedule: PartnerScheduleRef? = null,
    /** 两个人用的是同一份课表（比如同班，一方直接加入了另一方的课表）。 */
    val sameSchedule: Boolean = false
) {
    /** 界面上显示的名字：没设置时是「我」「TA」。 */
    val myName: String get() = me?.nickname?.takeIf { it.isNotBlank() } ?: "我"
    val partnerName: String get() = partner?.nickname?.takeIf { it.isNotBlank() } ?: "TA"

    fun toJson(): JSONObject = JSONObject()
        .put("bound", bound)
        .put("invite", invite?.let { JSONObject().put("code", it.code).put("expiresAt", it.expiresAt) } ?: JSONObject.NULL)
        .put("coupleId", coupleId ?: JSONObject.NULL)
        .put("since", since ?: JSONObject.NULL)
        .put("me", me?.toJson()?.put("currentScheduleId", myCurrentScheduleId ?: JSONObject.NULL) ?: JSONObject.NULL)
        .put(
            "partner",
            partner?.toJson()
                ?.put("schedule", partnerSchedule?.let {
                    JSONObject().put("id", it.id).put("revision", it.revision).put("updatedAt", it.updatedAt)
                } ?: JSONObject.NULL)
                ?.put("sameScheduleAsMine", sameSchedule)
                ?: JSONObject.NULL
        )

    companion object {
        val UNBOUND = CoupleState(bound = false)

        fun fromJson(o: JSONObject): CoupleState {
            if (!o.optBoolean("bound")) {
                val invite = o.optJSONObject("invite")?.let { json ->
                    val code = json.stringOrNull("code") ?: return@let null
                    CoupleInvite(code, json.stringOrNull("expiresAt").orEmpty())
                }
                return CoupleState(bound = false, invite = invite)
            }
            val me = o.optJSONObject("me") ?: JSONObject()
            val partner = o.optJSONObject("partner") ?: JSONObject()
            val schedule = partner.optJSONObject("schedule")?.let { json ->
                val id = json.stringOrNull("id") ?: return@let null
                PartnerScheduleRef(id, json.optInt("revision", 1), json.stringOrNull("updatedAt").orEmpty())
            }
            return CoupleState(
                bound = true,
                coupleId = o.stringOrNull("coupleId"),
                since = o.stringOrNull("since"),
                me = CoupleMember.fromJson(me, CouplePalette.INVITER_COLOR),
                myCurrentScheduleId = me.stringOrNull("currentScheduleId"),
                partner = CoupleMember.fromJson(partner, CouplePalette.INVITEE_COLOR),
                partnerSchedule = schedule,
                sameSchedule = partner.optBoolean("sameScheduleAsMine")
            )
        }
    }
}

/** 情侣双方的颜色：8 个浅色预设（与服务端默认色一致），也允许自定义。 */
object CouplePalette {

    data class Preset(val label: String, val fill: String, val ink: String)

    /** 默认颜色：发邀请的人浅蓝，接受邀请的人浅粉（与 backend/src/couple-service.js 一致）。 */
    const val INVITER_COLOR = "#7FAEE3"
    const val INVITEE_COLOR = "#F4AFC2"

    val PRESETS: List<Preset> = listOf(
        Preset("天蓝", "#7FAEE3", "#12304F"),
        Preset("樱粉", "#F4AFC2", "#56192C"),
        Preset("薰衣草", "#B9A6E6", "#2E1F55"),
        Preset("薄荷", "#8FD1BF", "#0F3D31"),
        Preset("杏橙", "#F5C08A", "#4A2808"),
        Preset("柠黄", "#EAD67A", "#3D3305"),
        Preset("珊瑚", "#F4A988", "#4D2414"),
        Preset("雾蓝灰", "#A9B8C9", "#1F2B38")
    )

    /** 两种颜色近到这个距离以内（[ScheduleView.colorDistance]）就提示「和 TA 的颜色太像」。 */
    private const val TOO_CLOSE_DISTANCE = 60f

    private val HEX = Regex("^#[0-9A-F]{6}$")

    fun normalize(value: String?): String? = value?.trim()?.uppercase()?.takeIf { HEX.matches(it) }

    fun presetOf(color: String?): Preset? = normalize(color)?.let { hex -> PRESETS.firstOrNull { it.fill == hex } }

    /** 课块上的文字颜色：预设用配好的深色字，自定义色按对比度在白字和墨色字里选。 */
    fun inkFor(color: String): String =
        presetOf(color)?.ink ?: ScheduleView.manualPalette(color)?.let { "#%06X".format(it.foreground and 0xFFFFFF) } ?: "#202725"

    /** 提示文案里的颜色名：预设用名字，自定义色统称「新颜色」。 */
    fun nameOf(color: String): String = presetOf(color)?.label ?: "新颜色"

    fun tooClose(a: String, b: String): Boolean {
        val x = argb(a) ?: return false
        val y = argb(b) ?: return false
        return ScheduleView.colorDistance(x, y) < TOO_CLOSE_DISTANCE
    }

    fun argb(color: String): Int? = normalize(color)?.let { (0xFF shl 24) or it.substring(1).toInt(16) }
}

/**
 * 「TA 改了你的名字 / 颜色」提示：只在**对方**改了**我的**名字或颜色、且和我上次看到的不一样时提示一次。
 * TA 改自己的不提示（界面上本来就看得到）；我自己改的也不提示。
 */
object CoupleNotice {

    /** [seenNickname] / [seenColor] 为 null 表示第一次看到（刚绑定），不提示。 */
    fun text(state: CoupleState, seenNickname: String?, seenColor: String?): String? {
        val me = state.me ?: return null
        if (!state.bound) return null
        val parts = mutableListOf<String>()
        if (seenNickname != null && me.nicknameUpdatedBy == "partner" && me.nickname != seenNickname) {
            parts += if (me.nickname.isBlank()) "把你的名字改回了「我」" else "把你的名字改成了「${me.nickname}」"
        }
        if (seenColor != null && me.colorUpdatedBy == "partner" && !me.color.equals(seenColor, ignoreCase = true)) {
            parts += "${if (parts.isEmpty()) "把你的颜色" else "颜色"}换成了${CouplePalette.nameOf(me.color)}"
        }
        if (parts.isEmpty()) return null
        return state.partnerName + " " + parts.joinToString("，")
    }
}

private fun JSONObject.stringOrNull(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
