package com.zhusijiao.app.domain.export

import com.zhusijiao.app.data.ScheduleExporter
import com.zhusijiao.app.data.ScheduleExporter.Format
import com.zhusijiao.app.domain.export.ExportFixtures.schedule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ExportFileNameTest {

    private val now = Calendar.getInstance().apply { clear(); set(2026, 9, 26, 12, 0) }.timeInMillis

    private fun name(format: Format, weeks: List<Int>? = null, scheduleName: String = "测试课表") =
        ScheduleExporter.fileName(
            ScheduleExporter.Source(schedule().copy(name = scheduleName), emptyList(), emptyList(), emptyList()),
            format,
            ExportOptions(weeks = weeks),
            now
        )

    @Test
    fun namesDescribeTheWeeks() {
        assertEquals("测试课表-第1-16周.xlsx", name(Format.XLSX))
        assertEquals("测试课表-第3周.ics", name(Format.ICS, listOf(3)))
        assertEquals("测试课表-第2-5周.xlsx", name(Format.XLSX, listOf(5, 2, 3, 4)))
        assertEquals("测试课表-第1-15周单周.xlsx", name(Format.XLSX, (1..15 step 2).toList()))
        assertEquals("测试课表-第3-9周选.xlsx", name(Format.XLSX, listOf(3, 4, 9)))
        assertEquals("测试课表-备份-20261026.json", name(Format.JSON, listOf(3)))
    }

    @Test
    fun unsafeCharactersAreReplaced() {
        assertEquals("a_b_c-第1-16周.xlsx", name(Format.XLSX, scheduleName = "a/b:c"))
        assertEquals("课表-第1-16周.xlsx", name(Format.XLSX, scheduleName = "   "))
    }
}
