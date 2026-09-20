package com.zhusijiao.app.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手动课程颜色：优先于自动配色、非法值回退、跨课程集合保持稳定。 */
class CourseColorTest {

    private fun course(name: String, day: Int = 1) = Course(
        name = name, teacher = "老师", position = "A101",
        day = day, startSection = 1, endSection = 2, weeks = listOf(1)
    )

    private fun schedule(colors: Map<String, String>) = Schedule(
        id = "s1", name = "测试课表", school = "", semesterStart = "2026-09-07", totalWeeks = 20,
        shareCode = null, revision = 1, createdAt = "", updatedAt = "", subscriberCount = 0,
        role = "owner", courseCount = 1, timeSlots = emptyList(),
        courses = listOf(course("高等数学")),
        courseColors = colors
    )

    /** JSON 往返由后端 smoke 端到端覆盖（本工程 JVM 测试的 org.json 为 stub，不可直接用）。 */
    @Test
    fun manualColorOverridesHashColor() {
        val courses = listOf(course("高等数学"))
        val auto = ScheduleView.buildCoursePaletteMap(courses)["高等数学"]
        val manualHex = ScheduleView.COURSE_PALETTES
            .map { String.format("#%06X", 0xFFFFFF and it.background) }
            .first { hex -> hex != String.format("#%06X", 0xFFFFFF and (auto?.background ?: 0)) }
        val manual = ScheduleView.buildCoursePaletteMap(courses, mapOf("高等数学" to manualHex))["高等数学"]
        assertEquals(ScheduleView.manualPalette(manualHex), manual)
        assertNotEquals(auto, manual)
    }

    @Test
    fun invalidManualColorFallsBackToAuto() {
        val courses = listOf(course("高等数学"))
        val auto = ScheduleView.buildCoursePaletteMap(courses)["高等数学"]
        assertEquals(auto, ScheduleView.buildCoursePaletteMap(courses, mapOf("高等数学" to "red"))["高等数学"])
        assertNull(ScheduleView.manualPalette(null))
        assertNull(ScheduleView.manualPalette("#12345"))
        assertNull(ScheduleView.manualPalette("#GGHHII"))
    }

    @Test
    fun manualColorIsPureFunctionOfName() {
        val manual = mapOf("高等数学" to "#B54E6C")
        val small = ScheduleView.buildCoursePaletteMap(listOf(course("高等数学")), manual)
        val crowded = ScheduleView.buildCoursePaletteMap(
            listOf(
                course("高等数学"),
                course("大学英语", 2),
                course("数据结构", 3),
                course("操作系统", 4)
            ),
            manual
        )
        assertEquals(small["高等数学"], crowded["高等数学"])
        assertEquals(ScheduleView.manualPalette("#B54E6C"), crowded["高等数学"])
    }

    @Test
    fun changingOneCourseNeverChangesOtherCourseColors() {
        val courses = listOf(
            course("高等数学"),
            course("大学英语", 2),
            course("数据结构", 3),
            course("操作系统", 4),
            course("计算机网络", 5)
        )
        val automatic = ScheduleView.buildCoursePaletteMap(courses)
        val target = "数据结构"
        val manualHex = ScheduleView.stablePaletteColors()
            .map { String.format("#%06X", 0xFFFFFF and it.background) }
            .first { it != String.format("#%06X", 0xFFFFFF and automatic.getValue(target).background) }
        val changed = ScheduleView.buildCoursePaletteMap(courses, mapOf(target to manualHex))

        assertEquals(ScheduleView.manualPalette(manualHex), changed[target])
        automatic.keys.filterNot { it == target }.forEach { name ->
            assertEquals("修改「$target」不应影响「$name」", automatic[name], changed[name])
        }
        assertEquals(automatic, ScheduleView.buildCoursePaletteMap(courses, emptyMap()))
    }

    @Test
    fun manualColorsAcceptLowerCaseAndTrim() {
        assertEquals(ScheduleView.manualPalette("#b54e6c"), ScheduleView.manualPalette(" #B54E6C "))
        assertTrue(ScheduleView.manualPalette("#b54e6c") != null)
    }
}
