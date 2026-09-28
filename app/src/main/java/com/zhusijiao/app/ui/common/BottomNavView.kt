package com.zhusijiao.app.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.util.Rpx

/**
 * 底部导航：线性图标 + 小标签，当前项的图标放进一枚浅主色胶囊，图标和文字变主色。
 * 新增入口只需在 [Tab] 增加一项（文字与图标），其余（布局、点击、高亮）自动生效。
 * 「我们」（情侣课表）只在绑定后出现，由宿主用 [setTabVisible] 控制，默认隐藏；出现时心形轻轻弹一下。
 */
class BottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Tab(val labelRes: Int, val iconRes: Int) {
        SCHEDULE(R.string.nav_schedule, R.drawable.ic_nav_schedule),
        COUPLE(R.string.nav_couple, R.drawable.ic_nav_couple),
        LIBRARY(R.string.nav_library, R.drawable.ic_nav_library),
        SETTINGS(R.string.nav_settings, R.drawable.ic_nav_settings)
    }

    var onTabSelected: ((Tab) -> Unit)? = null

    private var current: Tab = Tab.SCHEDULE
    private val pills = mutableMapOf<Tab, View>()
    private val icons = mutableMapOf<Tab, ImageView>()
    private val labels = mutableMapOf<Tab, TextView>()
    private val containers = mutableMapOf<Tab, View>()
    private val pillAnimators = mutableMapOf<Tab, ValueAnimator>()

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
        applyState(animate = false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    private fun addTab(tab: Tab) {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, Rpx.dp(60f), 1f)
            setBackgroundResource(R.drawable.bg_menu_row)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(tab.labelRes)
            setOnClickListener { selectTab(tab) }
        }
        // 胶囊与图标叠放：胶囊宽度会从 28dp 动画到 52dp，图标始终居中
        val holder = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(Rpx.dp(PILL_WIDTH_DP), Rpx.dp(28f))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val pill = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = Rpx.dp(14f).toFloat()
                setColor(ContextCompat.getColor(context, R.color.accent_soft))
            }
            layoutParams = FrameLayout.LayoutParams(Rpx.dp(PILL_WIDTH_DP), Rpx.dp(28f), Gravity.CENTER)
            visibility = View.INVISIBLE
        }
        val icon = ImageView(context).apply {
            setImageResource(tab.iconRes)
            layoutParams = FrameLayout.LayoutParams(Rpx.dp(22f), Rpx.dp(22f), Gravity.CENTER)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        holder.addView(pill)
        holder.addView(icon)
        val label = TextView(context).apply {
            text = context.getString(tab.labelRes)
            setTextColor(ContextCompat.getColor(context, R.color.nav_text))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Rpx.dp(3f) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        container.addView(holder)
        container.addView(label)
        addView(container)
        containers[tab] = container
        pills[tab] = pill
        icons[tab] = icon
        labels[tab] = label
    }

    private fun selectTab(tab: Tab) {
        if (tab == current) return
        current = tab
        applyState(animate = true)
        onTabSelected?.invoke(tab)
    }

    /** 显示或隐藏某个页签（目前只有「我们」会隐藏）；从隐藏变为显示时图标轻轻弹一下，提示多了一个入口。 */
    fun setTabVisible(tab: Tab, visible: Boolean) {
        val container = containers[tab] ?: return
        val wasHidden = container.visibility != View.VISIBLE
        container.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && wasHidden && isAttachedToWindow) {
            icons[tab]?.apply {
                scaleX = 0.4f
                scaleY = 0.4f
                animate().scaleX(1f).scaleY(1f).setDuration(360L)
                    .setInterpolator(OvershootInterpolator(3f)).start()
            }
        }
    }

    /** 由宿主设置当前项（不触发回调）。 */
    fun setCurrent(tab: Tab) {
        if (tab == current) return
        current = tab
        applyState(animate = isAttachedToWindow)
    }

    private fun applyState(animate: Boolean) {
        val accent = ContextCompat.getColor(context, R.color.accent)
        val normal = ContextCompat.getColor(context, R.color.nav_text)
        Tab.entries.forEach { tab ->
            val active = tab == current
            icons[tab]?.setColorFilter(if (active) accent else normal)
            labels[tab]?.apply {
                setTextColor(if (active) accent else normal)
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            containers[tab]?.isSelected = active
            animatePill(tab, active, animate)
        }
    }

    /** 选中项的胶囊从 28dp 宽展开到 52dp，取消选中时直接隐藏。 */
    private fun animatePill(tab: Tab, active: Boolean, animate: Boolean) {
        val pill = pills[tab] ?: return
        pillAnimators.remove(tab)?.cancel()
        if (!active) {
            pill.visibility = View.INVISIBLE
            return
        }
        pill.visibility = View.VISIBLE
        val full = Rpx.dp(PILL_WIDTH_DP)
        if (!animate) {
            pill.layoutParams = pill.layoutParams.apply { width = full }
            return
        }
        pillAnimators[tab] = ValueAnimator.ofInt(Rpx.dp(28f), full).apply {
            duration = 150L
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                pill.layoutParams = pill.layoutParams.apply { width = it.animatedValue as Int }
            }
            start()
        }
    }

    companion object {
        private const val PILL_WIDTH_DP = 52f
    }
}
