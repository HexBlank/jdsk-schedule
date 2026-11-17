package com.zhusijiao.app.ui.common

import android.content.Context
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.zhusijiao.app.R

/** 带图标的分组操作面板；主操作共用空格子面板的双列布局。 */
class ActionSheet(
    context: Context,
    title: String,
    items: List<Item>,
    primaryActions: Pair<() -> Unit, () -> Unit>? = null
) : SheetDialog(context) {
    class Item(
        val label: String,
        val icon: Int = R.drawable.ic_nav_schedule,
        val group: Int = 0,
        val danger: Boolean = false,
        val selected: Boolean? = null,
        val onClick: () -> Unit
    )

    init {
        setContentView(R.layout.dialog_action_sheet)
        setCanceledOnTouchOutside(true)
        findViewById<TextView>(R.id.actionSheetTitle).text = title
        val rows = findViewById<LinearLayout>(R.id.actionSheetRows)
        primaryActions?.let { (course, event) ->
            val tiles = LayoutInflater.from(context).inflate(R.layout.include_add_entry_choices, rows, false)
            AddEntrySheet.bindChoices(tiles, { dismiss(); course() }, { dismiss(); event() })
            rows.addView(tiles)
        }
        // 危险操作始终独立放在末尾，不受调用方传入顺序影响。
        items.groupBy { if (it.danger) Int.MAX_VALUE else it.group }.toSortedMap().values.forEach { group ->
            val container = MenuRows.group(rows)
            group.forEachIndexed { index, item ->
                if (index > 0) MenuRows.divider(container)
                container.addView(MenuRows.row(container, item.label, item.icon, item.danger, item.selected) {
                    dismiss()
                    item.onClick()
                })
            }
        }
    }
}
