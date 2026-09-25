package com.zhusijiao.app.domain

import kotlin.math.max

/**
 * 课表同格冲突的分栏算法：同一天里节次相交、当周都真的要上的块左右并排，谁也不盖住谁。
 *
 * 重修课与原班课、两门选课撞在同一时段是常态；过去只有「课 + 日程」才分栏，
 * 课与课重叠时后画的整块盖住先画的，用户只看得到一门甚至看不出那里有课。见 docs/DECISIONS.md D19。
 *
 * 规则：
 * - 同一天内经由重叠两两相连的块构成一个冲突组，组与组之间互不影响；
 * - 组内先排课、后排日程：课从左往右放进第一个放得下的栏，日程一律排在所有课的右边
 *   （沿用 D18「左课右程」）；同起始节时长的在左；
 * - 情侣周视图里两个人的块画在同一张表上：组内先按 [Item.owner] 分人，排在左边的人的块
 *   （课、日程）全部在另一个人的左边，每个人内部仍是「左课右程」；
 * - 组内所有块共用同一个总栏数，各自保持真实的起止节（高度恒等于时间）。
 *
 * 纯函数、不依赖 Android，[com.zhusijiao.app.ui.common.TimetableView] 只负责把栏号换成像素。
 */
object CellColumns {

    /**
     * 参与分栏的一块：星期、起止节次，它是不是日程，以及属于哪个人。
     * [owner] 只在情侣周视图里有意义：0 是排在左边的人，1 是右边的人；单人课表恒为 0。
     */
    data class Item(
        val day: Int,
        val startSection: Int,
        val endSection: Int,
        val isEvent: Boolean = false,
        val owner: Int = 0
    )

    /** 分栏结果：本块所在的栏（0 起，从左到右）与所在冲突组的总栏数（1 表示独占整格）。 */
    data class Slot(val column: Int, val columnCount: Int)

    /** 返回与 [items] 一一对应的分栏结果。 */
    fun assign(items: List<Item>): List<Slot> {
        val result = MutableList(items.size) { Slot(0, 1) }
        items.indices.groupBy { items[it].day }.values.forEach { sameDay ->
            groups(items, sameDay).forEach { group -> layoutGroup(items, group, result) }
        }
        return result
    }

    /** 按起始节扫一遍，把同一天的块切成若干冲突组：新块的起始节超过当前组的最晚结束节就另起一组。 */
    private fun groups(items: List<Item>, indices: List<Int>): List<List<Int>> {
        val result = mutableListOf<MutableList<Int>>()
        var groupEnd = Int.MIN_VALUE
        indices.sortedBy { items[it].startSection }.forEach { index ->
            val item = items[index]
            if (result.isEmpty() || item.startSection > groupEnd) {
                result += mutableListOf(index)
                groupEnd = item.endSection
            } else {
                result.last() += index
                groupEnd = max(groupEnd, item.endSection)
            }
        }
        return result
    }

    private fun layoutGroup(items: List<Item>, group: List<Int>, result: MutableList<Slot>) {
        if (group.size == 1) return
        val columns = mutableListOf<MutableList<Int>>()
        val columnOf = mutableMapOf<Int, Int>()

        // 从第 from 栏起找第一个与本块不相交的栏，都放不下就新开一栏
        fun place(index: Int, from: Int) {
            val item = items[index]
            var column = from
            while (column < columns.size && columns[column].any { overlaps(items[it], item) }) column += 1
            if (column == columns.size) columns += mutableListOf<Int>()
            columns[column] += index
            columnOf[index] = column
        }

        // 按「人 → 课在前、日程在后」切成若干道，每一道都从前面各道占用的栏之后开始放
        val order = compareBy<Int>({ items[it].startSection }, { -items[it].endSection })
        group.groupBy { items[it].owner to items[it].isEvent }
            .toSortedMap(compareBy<Pair<Int, Boolean>>({ it.first }, { it.second }))
            .values
            .forEach { lane ->
                val firstColumn = columns.size
                lane.sortedWith(order).forEach { place(it, firstColumn) }
            }
        group.forEach { result[it] = Slot(columnOf.getValue(it), columns.size) }
    }

    private fun overlaps(a: Item, b: Item): Boolean =
        ScheduleOccurrences.overlaps(a.startSection, a.endSection, b.startSection, b.endSection)
}
