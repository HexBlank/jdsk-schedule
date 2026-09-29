package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.TimetableAppearance

/**
 * 课表外观底部面板：格子高度、格子留白、文字大小三排分段选择，外加「自动铺满一屏」，
 * 以及「状态显示」分组：「显示已上状态」「显示当前时间线」「显示下一节课提示」三个开关，
 * 和停课、日程的样式（新版 / 经典，带预览的卡片二选一）。
 *
 * 交互约定：
 * - 面板只占下半屏且几乎不压暗背景，用户一边点一边能看到上半屏真实课表，所以没有「确定」按钮，
 *   每次改动通过 [onChanged] 立刻生效并由调用方持久化；
 * - 开启「铺满一屏」后高度档位不生效，那一排淡化但**仍可点**——点任一档即自动关闭铺满并选中该档，
 *   不做点了没反应的禁用态；
 * - 「恢复默认」在已经是默认值时淡化不可点。
 */
class AppearanceSheet(
    context: Context,
    initial: TimetableAppearance,
    /** 打开后直接滚到「状态显示」分组（从「换了新样式」提示的「改回」进来时）。 */
    private val focusStateStyle: Boolean = false,
    private val onChanged: (TimetableAppearance) -> Unit
) : Dialog(context, R.style.Theme_Zhusijiao_Sheet_Clear) {

    private var current = initial

    private val heightChoice: SegmentedChoiceView
    private val paddingChoice: SegmentedChoiceView
    private val textChoice: SegmentedChoiceView
    private val fitToggle: ToggleView
    private val finishedToggle: ToggleView
    private val nowLineToggle: ToggleView
    private val nextClassToggle: ToggleView
    private val resetButton: TextView
    private val modernCard: View
    private val classicCard: View
    private val modernRadio: View
    private val classicRadio: View

    init {
        setContentView(R.layout.dialog_appearance)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        setCanceledOnTouchOutside(true)

        heightChoice = findViewById(R.id.heightChoice)
        paddingChoice = findViewById(R.id.paddingChoice)
        textChoice = findViewById(R.id.textChoice)
        fitToggle = findViewById(R.id.fitScreenToggle)
        finishedToggle = findViewById(R.id.showFinishedToggle)
        nowLineToggle = findViewById(R.id.showNowLineToggle)
        nextClassToggle = findViewById(R.id.showNextClassToggle)
        resetButton = findViewById(R.id.appearanceReset)

        heightChoice.configure(
            items = HEIGHT_LABELS.map(context::getString),
            initialIndex = current.rowHeightLevel,
            contentDescriptionPrefix = context.getString(R.string.appearance_desc_height)
        ) { index ->
            // 手动选高度即视为放弃自动铺满，否则这一下点击会看不出任何变化
            apply(current.copy(rowHeightLevel = index, fitScreen = false))
            fitToggle.setChecked(false, animate = true)
            renderFitState()
        }

        paddingChoice.configure(
            items = PADDING_LABELS.map(context::getString),
            initialIndex = current.paddingLevel,
            contentDescriptionPrefix = context.getString(R.string.appearance_desc_padding)
        ) { index -> apply(current.copy(paddingLevel = index)) }

        textChoice.configure(
            items = TEXT_LABELS.map(context::getString),
            initialIndex = current.textLevel,
            contentDescriptionPrefix = context.getString(R.string.appearance_desc_text)
        ) { index -> apply(current.copy(textLevel = index)) }

        fitToggle.setChecked(current.fitScreen, animate = false)
        fitToggle.onCheckedChange = { checked ->
            apply(current.copy(fitScreen = checked))
            renderFitState()
        }
        findViewById<View>(R.id.fitScreenRow).setOnClickListener { fitToggle.toggle() }

        finishedToggle.setChecked(current.showFinished, animate = false)
        finishedToggle.onCheckedChange = { checked -> apply(current.copy(showFinished = checked)) }
        findViewById<View>(R.id.showFinishedRow).setOnClickListener { finishedToggle.toggle() }

        nowLineToggle.setChecked(current.showNowLine, animate = false)
        nowLineToggle.onCheckedChange = { checked -> apply(current.copy(showNowLine = checked)) }
        findViewById<View>(R.id.showNowLineRow).setOnClickListener { nowLineToggle.toggle() }

        nextClassToggle.setChecked(current.showNextClass, animate = false)
        nextClassToggle.onCheckedChange = { checked -> apply(current.copy(showNextClass = checked)) }
        findViewById<View>(R.id.showNextClassRow).setOnClickListener { nextClassToggle.toggle() }

        modernCard = findViewById(R.id.stateStyleModern)
        classicCard = findViewById(R.id.stateStyleClassic)
        modernRadio = findViewById(R.id.stateStyleModernRadio)
        classicRadio = findViewById(R.id.stateStyleClassicRadio)
        findViewById<StateStylePreviewView>(R.id.stateStyleModernPreview).stateStyle = TimetableAppearance.STATE_STYLE_MODERN
        findViewById<StateStylePreviewView>(R.id.stateStyleClassicPreview).stateStyle = TimetableAppearance.STATE_STYLE_CLASSIC
        modernCard.contentDescription = context.getString(R.string.appearance_state_modern_desc)
        classicCard.contentDescription = context.getString(R.string.appearance_state_classic_desc)
        modernCard.setOnClickListener { selectStateStyle(TimetableAppearance.STATE_STYLE_MODERN) }
        classicCard.setOnClickListener { selectStateStyle(TimetableAppearance.STATE_STYLE_CLASSIC) }

        resetButton.setOnClickListener { resetToDefault() }

        renderFitState()
        renderStateStyle()
        renderResetState()

        if (focusStateStyle) {
            val scroll = findViewById<MaxHeightScrollView>(R.id.appearanceScroll)
            val group = findViewById<View>(R.id.stateGroupTitle)
            scroll.post { scroll.smoothScrollTo(0, group.top) }
        }
    }

    private fun selectStateStyle(style: Int) {
        apply(current.copy(stateStyle = style))
        renderStateStyle()
    }

    /** 选中的卡片：主色描边加浅主色底，单选圆点实心；未选中：浅灰描边。 */
    private fun renderStateStyle() {
        val density = context.resources.displayMetrics.density
        fun card(selected: Boolean) = GradientDrawable().apply {
            cornerRadius = 12f * density
            setColor(ContextCompat.getColor(context, if (selected) R.color.code_cell_active_bg else R.color.surface))
            setStroke(
                ((if (selected) 1.5f else 1f) * density).toInt().coerceAtLeast(1),
                ContextCompat.getColor(context, if (selected) R.color.accent else R.color.line)
            )
        }
        fun radio(selected: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, R.color.surface))
            setStroke(
                ((if (selected) 4.5f else 1.5f) * density).toInt(),
                ContextCompat.getColor(context, if (selected) R.color.accent else R.color.toggle_track_off)
            )
        }
        val modern = current.modernStateStyle
        modernCard.background = card(modern)
        classicCard.background = card(!modern)
        modernRadio.background = radio(modern)
        classicRadio.background = radio(!modern)
        modernCard.isSelected = modern
        classicCard.isSelected = !modern
    }

    private fun apply(next: TimetableAppearance) {
        if (next == current) return
        current = next
        renderResetState()
        onChanged(next)
    }

    private fun resetToDefault() {
        val target = TimetableAppearance.DEFAULT
        if (current == target) return
        heightChoice.setSelection(target.rowHeightLevel)
        paddingChoice.setSelection(target.paddingLevel)
        textChoice.setSelection(target.textLevel)
        fitToggle.setChecked(target.fitScreen, animate = true)
        finishedToggle.setChecked(target.showFinished, animate = true)
        nowLineToggle.setChecked(target.showNowLine, animate = true)
        nextClassToggle.setChecked(target.showNextClass, animate = true)
        apply(target)
        renderFitState()
        renderStateStyle()
    }

    /** 铺满一屏时高度档位不生效，整排淡化提示「当前不起作用」，但保持可点。 */
    private fun renderFitState() {
        heightChoice.alpha = if (current.fitScreen) 0.45f else 1f
    }

    private fun renderResetState() {
        resetButton.isEnabled = !current.isDefault
        resetButton.alpha = if (current.isDefault) 0.35f else 1f
    }

    companion object {
        private val HEIGHT_LABELS = listOf(
            R.string.appearance_height_1,
            R.string.appearance_height_2,
            R.string.appearance_height_3,
            R.string.appearance_height_4,
            R.string.appearance_height_5
        )
        private val PADDING_LABELS = listOf(
            R.string.appearance_padding_1,
            R.string.appearance_padding_2,
            R.string.appearance_padding_3
        )
        private val TEXT_LABELS = listOf(
            R.string.appearance_text_1,
            R.string.appearance_text_2,
            R.string.appearance_text_3
        )

        /** 设置页「课表外观」行的副标题：一行说清当前三档，如「格子偏高 · 留白宽松 · 文字标准」；开了「显示已上」再追加一段。 */
        fun summary(context: Context, value: TimetableAppearance): String {
            val head = if (value.fitScreen) {
                context.getString(R.string.appearance_summary_fit)
            } else {
                context.getString(
                    R.string.appearance_summary_height,
                    context.getString(HEIGHT_LABELS[value.rowHeightLevel])
                )
            }
            val summary = context.getString(
                R.string.appearance_summary,
                head,
                context.getString(PADDING_LABELS[value.paddingLevel]),
                context.getString(TEXT_LABELS[value.textLevel])
            )
            val withFinished = if (value.showFinished) {
                context.getString(R.string.appearance_summary_with_finished, summary)
            } else {
                summary
            }
            return if (value.modernStateStyle) withFinished
            else context.getString(R.string.appearance_summary_classic, withFinished)
        }
    }
}
