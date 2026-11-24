package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.TimetableAppearance
import com.zhusijiao.app.domain.WeekendDisplayMode

/**
 * 课表外观底部面板，分两页，页签就是面板标题：
 * - 「尺寸」：格子高度、格子留白、文字大小三排分段选择，外加「自动铺满一屏」；
 * - 「显示」：下一节课提示、当前时间线、已上状态、周末无课时隐藏四个开关，
 *   和停课、日程的样式（新版 / 经典，带预览的卡片二选一）。
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
    /** 打开后直接停在「显示」页（从「换了新样式」提示的「改回」进来时）。 */
    private val focusStateStyle: Boolean = false,
    /** 周末列的显示方式另存一项偏好，不在 [TimetableAppearance] 里，但同属「课表长什么样」，开关放在这里。 */
    initialWeekendMode: WeekendDisplayMode = WeekendDisplayMode.AUTO,
    private val onWeekendModeChanged: (WeekendDisplayMode) -> Unit = {},
    private val onChanged: (TimetableAppearance) -> Unit
) : SheetDialog(context, SheetDialog.SCRIM_CLEAR) {

    private var current = initial
    private var weekendMode = initialWeekendMode
    private val weekendToggle: ToggleView
    private val tabSize: TextView
    private val tabDisplay: TextView
    private val pageSize: View
    private val pageDisplay: View

    private val heightChoice: SegmentedChoiceView
    private val paddingChoice: SegmentedChoiceView
    private val textChoice: SegmentedChoiceView
    private val fitToggle: ToggleView
    private val finishedToggle: ToggleView
    private val dayFinishedToggle: ToggleView
    private val nowLineToggle: ToggleView
    private val nextClassToggle: ToggleView
    private val softColorToggle: ToggleView
    private val showTeacherToggle: ToggleView
    private val resetButton: TextView
    private val modernCard: View
    private val classicCard: View
    private val modernRadio: View
    private val classicRadio: View

    init {
        setContentView(R.layout.dialog_appearance)
        setCanceledOnTouchOutside(true)

        heightChoice = findViewById(R.id.heightChoice)
        paddingChoice = findViewById(R.id.paddingChoice)
        textChoice = findViewById(R.id.textChoice)
        fitToggle = findViewById(R.id.fitScreenToggle)
        finishedToggle = findViewById(R.id.showFinishedToggle)
        dayFinishedToggle = findViewById(R.id.showDayFinishedToggle)
        nowLineToggle = findViewById(R.id.showNowLineToggle)
        nextClassToggle = findViewById(R.id.showNextClassToggle)
        softColorToggle = findViewById(R.id.softColorToggle)
        showTeacherToggle = findViewById(R.id.showTeacherToggle)
        resetButton = findViewById(R.id.appearanceReset)
        weekendToggle = findViewById(R.id.weekendAutoToggle)
        tabSize = findViewById(R.id.appearanceTabSize)
        tabDisplay = findViewById(R.id.appearanceTabDisplay)
        pageSize = findViewById(R.id.appearancePageSize)
        pageDisplay = findViewById(R.id.appearancePageDisplay)
        tabSize.setOnClickListener { showPage(display = false) }
        tabDisplay.setOnClickListener { showPage(display = true) }

        // 开启 = 当周周六日有安排时才显示（AUTO），关闭 = 始终显示（ALWAYS）。
        // 周末列不允许完全隐藏，避免用户漏看周末课程。
        weekendToggle.setChecked(weekendMode == WeekendDisplayMode.AUTO, animate = false)
        weekendToggle.onCheckedChange = { checked ->
            weekendMode = if (checked) WeekendDisplayMode.AUTO else WeekendDisplayMode.ALWAYS
            onWeekendModeChanged(weekendMode)
            renderResetState()
        }
        findViewById<View>(R.id.weekendAutoRow).setOnClickListener { weekendToggle.toggle() }

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

        dayFinishedToggle.setChecked(current.showDayFinished, animate = false)
        dayFinishedToggle.onCheckedChange = { checked -> apply(current.copy(showDayFinished = checked)) }
        findViewById<View>(R.id.showDayFinishedRow).setOnClickListener { dayFinishedToggle.toggle() }

        nowLineToggle.setChecked(current.showNowLine, animate = false)
        nowLineToggle.onCheckedChange = { checked -> apply(current.copy(showNowLine = checked)) }
        findViewById<View>(R.id.showNowLineRow).setOnClickListener { nowLineToggle.toggle() }

        nextClassToggle.setChecked(current.showNextClass, animate = false)
        nextClassToggle.onCheckedChange = { checked -> apply(current.copy(showNextClass = checked)) }
        findViewById<View>(R.id.showNextClassRow).setOnClickListener { nextClassToggle.toggle() }

        softColorToggle.setChecked(current.softColorStyle, animate = false)
        softColorToggle.onCheckedChange = { checked -> apply(current.copy(softColorStyle = checked)) }
        findViewById<View>(R.id.softColorRow).setOnClickListener { softColorToggle.toggle() }

        showTeacherToggle.setChecked(current.showTeacher, animate = false)
        showTeacherToggle.onCheckedChange = { checked -> apply(current.copy(showTeacher = checked)) }
        findViewById<View>(R.id.showTeacherRow).setOnClickListener { showTeacherToggle.toggle() }

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

        showPage(display = focusStateStyle)
    }

    /** 两页叠放，不在的那页只是不可见：面板高度不随换页跳动。选中的页签用纯白胶囊高亮。 */
    private fun showPage(display: Boolean) {
        pageSize.visibility = if (display) View.INVISIBLE else View.VISIBLE
        pageDisplay.visibility = if (display) View.VISIBLE else View.INVISIBLE
        fun style(tab: TextView, selected: Boolean) {
            tab.isSelected = selected
            tab.setBackgroundResource(if (selected) R.drawable.bg_segment_pill else 0)
            tab.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tab.setTextColor(ContextCompat.getColor(context, if (selected) R.color.text_primary else R.color.text_secondary))
        }
        style(tabSize, !display)
        style(tabDisplay, display)
    }

    private val allDefault get() = current.isDefault && weekendMode == WeekendDisplayMode.AUTO

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
        if (allDefault) return
        weekendToggle.setChecked(true, animate = true)
        if (weekendMode != WeekendDisplayMode.AUTO) {
            weekendMode = WeekendDisplayMode.AUTO
            onWeekendModeChanged(weekendMode)
            renderResetState()
        }
        heightChoice.setSelection(target.rowHeightLevel)
        paddingChoice.setSelection(target.paddingLevel)
        textChoice.setSelection(target.textLevel)
        fitToggle.setChecked(target.fitScreen, animate = true)
        finishedToggle.setChecked(target.showFinished, animate = true)
        dayFinishedToggle.setChecked(target.showDayFinished, animate = true)
        nowLineToggle.setChecked(target.showNowLine, animate = true)
        nextClassToggle.setChecked(target.showNextClass, animate = true)
        softColorToggle.setChecked(target.softColorStyle, animate = true)
        showTeacherToggle.setChecked(target.showTeacher, animate = true)
        apply(target)
        renderFitState()
        renderStateStyle()
    }

    /** 铺满一屏时高度档位不生效，整排淡化提示「当前不起作用」，但保持可点。 */
    private fun renderFitState() {
        heightChoice.alpha = if (current.fitScreen) 0.45f else 1f
    }

    private fun renderResetState() {
        resetButton.isEnabled = !allDefault
        resetButton.alpha = if (allDefault) 0.35f else 1f
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
    }
}
