package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.util.Rpx

/**
 * 底部导航：白底文字标签，当前项绿色文字 + 顶部细线。
 * 新增入口只需在 [Tab] 增加一项，其余（布局、点击、高亮）自动生效。
 * 「我们」（情侣课表）只在绑定后出现，由宿主用 [setTabVisible] 控制，默认隐藏。
 */
class BottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Tab(val labelRes: Int) {
        SCHEDULE(R.string.nav_schedule),
        COUPLE(R.string.nav_couple),
        LIBRARY(R.string.nav_library),
        SETTINGS(R.string.nav_settings)
    }

    var onTabSelected: ((Tab) -> Unit)? = null

    private var current: Tab = Tab.SCHEDULE
    private val indicators = mutableMapOf<Tab, View>()
    private val labels = mutableMapOf<Tab, TextView>()
    private val containers = mutableMapOf<Tab, View>()

    init {
        orientation = HORIZONTAL
        setBackgroundResource(R.drawable.bg_bottom_nav)
        Tab.entries.forEach { addTab(it) }
        setTabVisible(Tab.COUPLE, false)
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
            insets
        }
        applyState()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    private fun addTab(tab: Tab) {
        val container = FrameLayout(context).apply {
            layoutParams = LayoutParams(0, Rpx.dp(52f), 1f)
            setBackgroundResource(R.drawable.bg_menu_row)
            isClickable = true
            isFocusable = true
            setOnClickListener { selectTab(tab) }
        }
        val indicator = View(context).apply {
            setBackgroundResource(R.drawable.bg_nav_indicator)
            layoutParams = FrameLayout.LayoutParams(Rpx.dp(22f), Rpx.dp(2f)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            visibility = View.GONE
        }
        val label = TextView(context).apply {
            text = context.getString(tab.labelRes)
            setTextColor(ContextCompat.getColor(context, R.color.nav_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        container.addView(indicator)
        container.addView(label)
        addView(container)
        containers[tab] = container
        indicators[tab] = indicator
        labels[tab] = label
    }

    private fun selectTab(tab: Tab) {
        if (tab == current) return
        current = tab
        applyState()
        onTabSelected?.invoke(tab)
    }

    /** 显示或隐藏某个页签（目前只有「我们」会隐藏）。 */
    fun setTabVisible(tab: Tab, visible: Boolean) {
        containers[tab]?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** 由宿主设置当前项（不触发回调）。 */
    fun setCurrent(tab: Tab) {
        current = tab
        applyState()
    }

    private fun applyState() {
        val accent = ContextCompat.getColor(context, R.color.accent)
        val normal = ContextCompat.getColor(context, R.color.nav_text)
        Tab.entries.forEach { tab ->
            val active = tab == current
            indicators[tab]?.visibility = if (active) View.VISIBLE else View.GONE
            labels[tab]?.apply {
                setTextColor(if (active) accent else normal)
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
    }
}
