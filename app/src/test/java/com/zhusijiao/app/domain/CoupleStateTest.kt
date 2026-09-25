package com.zhusijiao.app.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoupleStateTest {

    private val boundJson = """
        {"bound":true,"coupleId":"c1","since":"2026-09-25T00:00:00.000Z",
         "me":{"nickname":"大熊","nicknameUpdatedBy":"partner","color":"#8fd1bf","colorUpdatedBy":null,
               "revision":3,"updatedAt":"x","currentScheduleId":"s-me"},
         "partner":{"nickname":"","nicknameUpdatedBy":null,"color":"#F4AFC2","colorUpdatedBy":null,
               "revision":1,"updatedAt":"y","schedule":{"id":"s-ta","revision":7,"updatedAt":"z"},
               "sameScheduleAsMine":false}}
    """.trimIndent()

    @Test
    fun `解析服务端返回的绑定状态`() {
        val state = CoupleState.fromJson(JSONObject(boundJson))
        assertTrue(state.bound)
        assertEquals("大熊", state.myName)
        assertEquals("TA", state.partnerName)
        assertEquals("#8FD1BF", state.me!!.color)
        assertEquals("partner", state.me!!.nicknameUpdatedBy)
        assertNull(state.me!!.colorUpdatedBy)
        assertEquals("s-me", state.myCurrentScheduleId)
        assertEquals(PartnerScheduleRef("s-ta", 7, "z"), state.partnerSchedule)
    }

    @Test
    fun `本机存储往返不丢字段`() {
        val state = CoupleState.fromJson(JSONObject(boundJson))
        assertEquals(state, CoupleState.fromJson(state.toJson()))
        val unbound = CoupleState(bound = false, invite = CoupleInvite("ABCD2345", "2026-09-26T00:00:00.000Z"))
        assertEquals(unbound, CoupleState.fromJson(unbound.toJson()))
    }

    @Test
    fun `对方还没有课表时摘要为空`() {
        val json = JSONObject(boundJson)
        json.getJSONObject("partner").put("schedule", JSONObject.NULL)
        assertNull(CoupleState.fromJson(json).partnerSchedule)
    }

    @Test
    fun `预设色用配好的文字色，自定义色按对比度选`() {
        assertEquals("#12304F", CouplePalette.inkFor("#7FAEE3"))
        assertEquals("#FFFFFF", CouplePalette.inkFor("#1A2B3C"))
        assertEquals("天蓝", CouplePalette.nameOf("#7faee3"))
        assertEquals("新颜色", CouplePalette.nameOf("#123456"))
    }

    @Test
    fun `默认的蓝和粉不算太像，几乎一样的蓝算太像`() {
        assertFalse(CouplePalette.tooClose(CouplePalette.INVITER_COLOR, CouplePalette.INVITEE_COLOR))
        assertTrue(CouplePalette.tooClose("#7FAEE3", "#84B2E6"))
    }

    // ===== 「TA 改了你的名字」提示 =====

    private fun state(nickname: String, nicknameBy: String?, color: String, colorBy: String?) = CoupleState(
        bound = true,
        me = CoupleMember(nickname, nicknameBy, color, colorBy, 1, ""),
        partner = CoupleMember("小鹿", "me", "#F4AFC2", null, 1, "")
    )

    @Test
    fun `对方改了我的名字才提示`() {
        val text = CoupleNotice.text(state("大熊", "partner", "#7FAEE3", null), seenNickname = "", seenColor = "#7FAEE3")
        assertEquals("小鹿 把你的名字改成了「大熊」", text)
    }

    @Test
    fun `名字和颜色一起改时合并成一句`() {
        val text = CoupleNotice.text(state("大熊", "partner", "#8FD1BF", "partner"), seenNickname = "", seenColor = "#7FAEE3")
        assertEquals("小鹿 把你的名字改成了「大熊」，颜色换成了薄荷", text)
    }

    @Test
    fun `只改颜色`() {
        val text = CoupleNotice.text(state("", null, "#8FD1BF", "partner"), seenNickname = "", seenColor = "#7FAEE3")
        assertEquals("小鹿 把你的颜色换成了薄荷", text)
    }

    @Test
    fun `我自己改的、已经看过的、刚绑定的都不提示`() {
        assertNull(CoupleNotice.text(state("大熊", "me", "#7FAEE3", null), "", "#7FAEE3"))
        assertNull(CoupleNotice.text(state("大熊", "partner", "#7FAEE3", null), "大熊", "#7FAEE3"))
        assertNull(CoupleNotice.text(state("大熊", "partner", "#7FAEE3", null), null, null))
    }
}
