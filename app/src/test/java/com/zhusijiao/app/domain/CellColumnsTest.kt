package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同格冲突分栏的回归：重修课与原班课撞在同一时段时，两门都必须各占一栏，
 * 不能再出现后画的整块盖住先画的、只看得到一门课的情况。
 */
class CellColumnsTest {

    private fun course(day: Int, start: Int, end: Int) = CellColumns.Item(day, start, end)
    private fun event(day: Int, start: Int, end: Int) = CellColumns.Item(day, start, end, isEvent = true)
    private fun slot(column: Int, count: Int) = CellColumns.Slot(column, count)

    @Test
    fun `不重叠的课各自独占整格`() {
        val slots = CellColumns.assign(listOf(course(1, 1, 2), course(1, 3, 4), course(2, 1, 2)))
        assertEquals(listOf(slot(0, 1), slot(0, 1), slot(0, 1)), slots)
    }

    @Test
    fun `重修课与原班课同一时段左右并排`() {
        val slots = CellColumns.assign(listOf(course(1, 1, 2), course(1, 1, 2)))
        assertEquals(listOf(slot(0, 2), slot(1, 2)), slots)
    }

    @Test
    fun `部分重叠的课同样分栏且时长的排左边`() {
        // 重修课 3–4 节先出现在列表里，原班课 1–4 节在后：按起始节排，原班课在左
        val slots = CellColumns.assign(listOf(course(1, 3, 4), course(1, 1, 4)))
        assertEquals(listOf(slot(1, 2), slot(0, 2)), slots)
    }

    @Test
    fun `同起始节时长的在左`() {
        val slots = CellColumns.assign(listOf(course(3, 1, 2), course(3, 1, 4)))
        assertEquals(listOf(slot(1, 2), slot(0, 2)), slots)
    }

    @Test
    fun `三门课撞在一起分三栏`() {
        val slots = CellColumns.assign(listOf(course(1, 1, 2), course(1, 1, 2), course(1, 2, 2)))
        assertEquals(listOf(slot(0, 3), slot(1, 3), slot(2, 3)), slots)
    }

    @Test
    fun `链式重叠复用空出来的栏`() {
        // 1–2 与 2–3 冲突、2–3 与 3–4 冲突，但 1–2 与 3–4 不冲突：两栏就够
        val slots = CellColumns.assign(listOf(course(1, 1, 2), course(1, 2, 3), course(1, 3, 4)))
        assertEquals(listOf(slot(0, 2), slot(1, 2), slot(0, 2)), slots)
    }

    @Test
    fun `不同天的同节次课互不影响`() {
        val slots = CellColumns.assign(listOf(course(1, 1, 2), course(2, 1, 2), course(1, 1, 2)))
        assertEquals(listOf(slot(0, 2), slot(0, 1), slot(1, 2)), slots)
    }

    @Test
    fun `课与日程仍是左课右程`() {
        val slots = CellColumns.assign(listOf(event(5, 10, 11), course(5, 10, 11)))
        assertEquals(listOf(slot(1, 2), slot(0, 2)), slots)
    }

    @Test
    fun `两门课撞车再加日程时日程排在最右`() {
        val slots = CellColumns.assign(listOf(course(1, 1, 2), event(1, 1, 1), course(1, 1, 2)))
        assertEquals(listOf(slot(0, 3), slot(2, 3), slot(1, 3)), slots)
    }

    @Test
    fun `日程即使时间上能塞进左栏也排在课的右边`() {
        // 课 1–2 与 2–3 占两栏，日程 3–3 按时间能放进第 0 栏，但要守住「左课右程」
        val slots = CellColumns.assign(listOf(course(1, 1, 2), course(1, 2, 3), event(1, 3, 3)))
        assertEquals(listOf(slot(0, 3), slot(1, 3), slot(2, 3)), slots)
    }

    @Test
    fun `只有日程时不分栏`() {
        val slots = CellColumns.assign(listOf(event(6, 1, 2), event(6, 5, 6)))
        assertEquals(listOf(slot(0, 1), slot(0, 1)), slots)
    }

    // ===== 情侣周视图：两个人的块画在同一张表上 =====

    private fun left(day: Int, start: Int, end: Int, isEvent: Boolean = false) =
        CellColumns.Item(day, start, end, isEvent = isEvent, owner = 0)
    private fun right(day: Int, start: Int, end: Int) = CellColumns.Item(day, start, end, owner = 1)

    @Test
    fun `情侣同一时段都有课时左右并排且左边的人在左`() {
        val slots = CellColumns.assign(listOf(right(1, 1, 2), left(1, 1, 2)))
        assertEquals(listOf(slot(1, 2), slot(0, 2)), slots)
    }

    @Test
    fun `情侣各自的课不重叠时都独占整格`() {
        val slots = CellColumns.assign(listOf(left(1, 1, 2), right(1, 3, 4), right(2, 1, 2)))
        assertEquals(listOf(slot(0, 1), slot(0, 1), slot(0, 1)), slots)
    }

    @Test
    fun `左边的人的课和日程都排在右边的人前面`() {
        // 我的课、我的日程、TA 的课：日程只在自己这一侧守住「左课右程」
        val slots = CellColumns.assign(listOf(right(1, 1, 2), left(1, 1, 1, isEvent = true), left(1, 1, 2)))
        assertEquals(listOf(slot(2, 3), slot(1, 3), slot(0, 3)), slots)
    }

    @Test
    fun `右边的人的课即使时间上放得进左栏也不插到左边的人中间`() {
        // 我的 1–2 与 2–3 占两栏，TA 的 3–3 按时间能放进第 0 栏，但两个人不能混在一起
        val slots = CellColumns.assign(listOf(left(1, 1, 2), left(1, 2, 3), right(1, 3, 3)))
        assertEquals(listOf(slot(0, 3), slot(1, 3), slot(2, 3)), slots)
    }

    @Test
    fun `只有右边的人有课时从第一栏开始`() {
        val slots = CellColumns.assign(listOf(right(4, 6, 7), right(4, 6, 7)))
        assertEquals(listOf(slot(0, 2), slot(1, 2)), slots)
    }
}
