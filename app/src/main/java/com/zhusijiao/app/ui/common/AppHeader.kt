package com.zhusijiao.app.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zhusijiao.app.R

/**
 * 自定义页头：整屏居中标题、可选返回、右侧操作区。
 * 顶部按系统 insets 自动避让状态栏。
 */
class AppHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val backView: TextView
    private val titleView: TextView
    private val actionsView: LinearLayout

    var onBackClick: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_app_header, this, true)
        backView = findViewById(R.id.headerBack)
        titleView = findViewById(R.id.headerTitle)
        actionsView = findViewById(R.id.headerActions)
        backView.setOnClickListener { onBackClick?.invoke() }
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    fun setTitle(title: CharSequence?) {
        titleView.text = title ?: ""
    }

    /**
     * 标题可点（如课表页的课表名 = 切换课表）：传入回调时标题后出现一个小箭头，传 null 恢复成普通标题。
     * 箭头另一侧补等宽内边距，文字仍然整屏居中。
     */
    fun setOnTitleClick(listener: (() -> Unit)?) {
        val density = resources.displayMetrics.density
        if (listener == null) {
            titleView.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            titleView.setPaddingRelative(0, 0, 0, 0)
            titleView.setOnClickListener(null)
            titleView.isClickable = false
            titleView.background = null
            return
        }
        val gap = (6 * density).toInt()
        titleView.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_caret_down, 0)
        titleView.compoundDrawablePadding = gap
        titleView.setPaddingRelative(gap + (9 * density).toInt(), 0, 0, 0)
        titleView.setBackgroundResource(R.drawable.bg_menu_row)
        titleView.setOnClickListener { listener() }
    }

    fun setBackVisible(visible: Boolean) {
        backView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun addAction(view: View) {
        actionsView.addView(view)
    }

    fun clearActions() {
        actionsView.removeAllViews()
    }
}
