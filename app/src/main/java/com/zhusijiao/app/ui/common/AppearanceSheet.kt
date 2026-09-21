package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.TimetableAppearance

/**
 * 课表外观底部面板：格子高度、格子留白、文字大小三排分段选择，外加「自动铺满一屏」。
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
    private val onChanged: (TimetableAppearance) -> Unit
) : Dialog(context, R.style.Theme_Zhusijiao_Sheet_Clear) {

    private var current = initial

    private val heightChoice: SegmentedChoiceView
    private val paddingChoice: SegmentedChoiceView
    private val textChoice: SegmentedChoiceView
    private val fitToggle: ToggleView
    private val resetButton: TextView

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

        resetButton.setOnClickListener { resetToDefault() }

        renderFitState()
        renderResetState()
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
        apply(target)
        renderFitState()
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

        /** 设置页「课表外观」行的副标题：一行说清当前三档，如「格子偏高 · 留白宽松 · 文字标准」。 */
        fun summary(context: Context, value: TimetableAppearance): String {
            val head = if (value.fitScreen) {
                context.getString(R.string.appearance_summary_fit)
            } else {
                context.getString(
                    R.string.appearance_summary_height,
                    context.getString(HEIGHT_LABELS[value.rowHeightLevel])
                )
            }
            return context.getString(
                R.string.appearance_summary,
                head,
                context.getString(PADDING_LABELS[value.paddingLevel]),
                context.getString(TEXT_LABELS[value.textLevel])
            )
        }
    }
}
