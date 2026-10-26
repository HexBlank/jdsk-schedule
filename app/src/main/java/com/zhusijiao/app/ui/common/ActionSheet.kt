package com.zhusijiao.app.ui.common

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R

/**
 * 通用操作面板：标题是操作对象的名字，下面一组行，每行一个操作，危险操作用红字放最后。
 *
 * 用来收起卡片上一排文字按钮（更新 / 分享 / 导出 / 删除）：卡片本身只留信息和一个「⋯」，
 * 不常用的操作不占常驻位置。
 */
class ActionSheet(
    context: Context,
    title: String,
    items: List<Item>
) : SheetDialog(context) {

    class Item(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

    init {
        setContentView(R.layout.dialog_action_sheet)
        setCanceledOnTouchOutside(true)
        findViewById<TextView>(R.id.actionSheetTitle).text = title
        val rows = findViewById<LinearLayout>(R.id.actionSheetRows)
        val inflater = LayoutInflater.from(context)
        items.forEachIndexed { index, item ->
            if (index > 0) rows.addView(divider(rows))
            val row = inflater.inflate(R.layout.item_action_row, rows, false) as TextView
            row.text = item.label
            if (item.danger) row.setTextColor(ContextCompat.getColor(context, R.color.danger_action))
            row.setOnClickListener {
                dismiss()
                item.onClick()
            }
            rows.addView(row)
        }
    }

    private fun divider(parent: ViewGroup): View =
        LayoutInflater.from(context).inflate(R.layout.item_form_divider, parent, false)
}
