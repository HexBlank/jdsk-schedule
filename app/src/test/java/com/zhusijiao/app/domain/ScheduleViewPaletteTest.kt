package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 课程配色规则：预设层次清楚、默认分配主动拉开色差、文字可读。 */
class ScheduleViewPaletteTest {

    private fun course(name: String, day: Int = 1) = Course(
        name = name,
        teacher = "老师",
        position = "A101",
        day = day,
        startSection = 1,
        endSection = 2,
        weeks = listOf(1)
    )

    private fun names(count: Int): List<Course> =
        (1..count).map { course("课程%02d".format(it), day = (it % 7) + 1) }

    /** 20 个预设色不重复，并同时提供深色/浅色层，扩大可感知区间。 */
    @Test
    fun curatedPalettesHaveDistinctLightAndDarkLayers() {
        val palettes = ScheduleView.stablePaletteColors()
        assertEquals(20, palettes.size)
        assertEquals(20, palettes.map { it.background }.distinct().size)
        assertTrue("应同时包含白字深色与墨字浅色", palettes.map { it.foreground }.distinct().size >= 2)
    }

    /** 全部预设色均满足正文对比度。 */
    @Test
    fun stablePaletteIsReadable() {
        val palettes = ScheduleView.stablePaletteColors()
        palettes.forEachIndexed { i, palette ->
            val contrast = ScheduleView.contrastRatio(palette.foreground, palette.background)
            assertTrue("稳定色 $i contrast=$contrast", contrast >= 4.5f)
        }
    }

    /** 常见 12 门课的默认色由最远色差策略分配，不会挤在相近色区间。 */
    @Test
    fun defaultAssignmentsStayVisuallySeparated() {
        val colors = ScheduleView.buildCoursePaletteMap(names(12)).values.map { it.background }
        for (i in colors.indices) {
            for (j in i + 1 until colors.size) {
                val distance = ScheduleView.colorDistance(colors[i], colors[j])
                assertTrue("默认色 $i/$j 色差仅 $distance", distance >= 70f)
            }
        }
    }

    /** 超过 20 门课时的兜底扩展色同样满足文字对比度。 */
    @Test
    fun extendedPalettesReadable() {
        (20 until 36).forEach { index ->
            val palette = ScheduleView.paletteForIndex(index)
            val contrast = ScheduleView.contrastRatio(palette.foreground, palette.background)
            assertTrue("扩展色 $index contrast=$contrast", contrast >= 4.5f)
        }
    }

    /** 一门课一种颜色：60 门课 60 种颜色。 */
    @Test
    fun everyCourseGetsUniqueColor() {
        val map = ScheduleView.buildCoursePaletteMap(names(60))
        assertEquals(60, map.size)
        assertEquals(60, map.values.map { it.background }.distinct().size)
    }

    /** 同一课程集合的配色是确定的，输入顺序（导入顺序）不影响结果。 */
    @Test
    fun colorDependsOnlyOnCourseName() {
        val map = ScheduleView.buildCoursePaletteMap(names(12))
        val shuffled = ScheduleView.buildCoursePaletteMap(names(12).shuffled())
        assertEquals(map, shuffled)
        val duplicated = names(12) + course("课程01", day = 3)
        assertEquals(map["课程01"], ScheduleView.buildCoursePaletteMap(duplicated)["课程01"])
    }

    /** 单独一门课的颜色就是它的「家」色格：颜色是课程名的纯函数。 */
    @Test
    fun singleCourseSitsAtHomeSlot() {
        listOf("高等数学", "大学英语", "计算机网络", "数据结构", "毛概").forEach { name ->
            val alone = ScheduleView.buildCoursePaletteMap(listOf(course(name)))
            assertEquals(
                "课程「$name」应坐在自己的色格上",
                ScheduleView.paletteForIndex(ScheduleView.stableColorIndex(name)),
                alone[name]
            )
        }
    }

    /** 末尾增删课程时，已有课程颜色保持稳定。 */
    @Test
    fun mostCoursesKeepColorWhenSetChanges() {
        val base = ScheduleView.buildCoursePaletteMap(names(10))
        val extended = ScheduleView.buildCoursePaletteMap(names(10) + course("全新的课程甲", day = 2))
        val keptAfterAdd = base.count { base[it.key] == extended[it.key] }
        assertTrue("增课后仅 $keptAfterAdd/10 门课保持原色", keptAfterAdd >= 8)

        val reduced = ScheduleView.buildCoursePaletteMap(names(10).dropLast(3))
        val keptAfterRemove = base.filterKeys { it in reduced.keys }.count { base[it.key] == reduced[it.key] }
        assertTrue("删课后仅 $keptAfterRemove/7 门课保持原色", keptAfterRemove >= 6)
    }
}
