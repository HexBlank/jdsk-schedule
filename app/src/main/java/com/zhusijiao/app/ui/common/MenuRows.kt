package com.zhusijiao.app.ui.common

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.ViewCompat
import com.zhusijiao.app.R

/** 操作与单选列表共用的图标行，选中状态独立于名称。 */
object MenuRows {
    fun group(parent: LinearLayout): LinearLayout = LinearLayout(parent.context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 16) }
        parent.addView(this)
    }

    fun divider(parent: LinearLayout) {
        parent.addView(LayoutInflater.from(parent.context).inflate(R.layout.item_form_divider, parent, false))
    }

    fun row(parent: LinearLayout, label: String, icon: Int, danger: Boolean = false,
            selected: Boolean? = null, onClick: () -> Unit): LinearLayout {
        val context = parent.context
        val row = LayoutInflater.from(context).inflate(R.layout.item_action_row, parent, false) as LinearLayout
        val color = ContextCompat.getColor(context, if (danger) R.color.danger_action else R.color.text_primary)
        row.findViewById<TextView>(R.id.actionRowLabel).apply { text = label; setTextColor(color) }
        row.findViewById<ImageView>(R.id.actionRowIcon).apply { setImageResource(icon); setColorFilter(color) }
        row.findViewById<ImageView>(R.id.actionRowCheck).visibility =
            if (selected == true) View.VISIBLE else View.INVISIBLE
        row.isSelected = selected == true
        row.contentDescription = label
        if (selected != null) {
            ViewCompat.setStateDescription(row,
                context.getString(if (selected) R.string.menu_selected else R.string.menu_unselected))
            ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = selected
                }
            })
        }
        row.setOnClickListener { onClick() }
        return row
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
