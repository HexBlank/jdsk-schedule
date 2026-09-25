package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.util.Ui
import kotlin.math.roundToInt

/**
 * 手动课程选色面板：展示高区分预设色、用户保存色与可触摸 HSV 调色板。
 * 选中手动色后该课程不再走哈希配色；重置则回到自动配色。仅发布者可用。
 */
class ColorPickerSheet(
    context: Context,
    private val courseName: String,
    private val autoColor: Int,
    private val currentManual: String?,
    private val onPick: (String) -> Unit,
    private val onReset: () -> Unit,
    /** 面板标题，默认「课程颜色」；情侣课表选人的颜色时换掉。 */
    title: String? = null
) : Dialog(context, R.style.Theme_Zhusijiao_Sheet) {

    init {
        setContentView(R.layout.dialog_course_color)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        setCanceledOnTouchOutside(true)

        findViewById<TextView>(R.id.colorPickerCourse).text = courseName
        if (title != null) findViewById<TextView>(R.id.colorPickerTitle).text = title
        buildGrid()
        buildCustomGrid()
        buildFollowDefault()
        configureCustomEditor()
    }

    private fun buildGrid() {
        val grid = findViewById<GridLayout>(R.id.colorGrid)
        grid.removeAllViews()
        ScheduleView.stablePaletteColors().forEach { palette ->
            val hex = String.format("#%06X", 0xFFFFFF and palette.background)
            grid.addView(
                swatchCell(
                    color = palette.background,
                    description = hex,
                    selected = currentManual.equals(hex, ignoreCase = true)
                ) {
                    onPick(hex)
                    dismiss()
                }
            )
        }
    }

    private fun buildCustomGrid() {
        val grid = findViewById<GridLayout>(R.id.customColorGrid)
        grid.removeAllViews()
        Prefs.customCourseColors.forEach { hex ->
            val color = Color.parseColor(hex)
            grid.addView(
                swatchCell(
                    color = color,
                    description = context.getString(R.string.color_picker_saved_description, hex),
                    selected = currentManual.equals(hex, ignoreCase = true),
                    onLongClick = {
                        Prefs.removeCustomCourseColor(hex)
                        buildCustomGrid()
                        Ui.toast(context, context.getString(R.string.color_picker_removed))
                    }
                ) {
                    onPick(hex)
                    dismiss()
                }
            )
        }
        grid.visibility = if (grid.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun swatchCell(
        color: Int,
        description: String,
        selected: Boolean,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): View {
        val cell = FrameLayout(context)
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10f).toFloat()
            setColor(color)
            setStroke(dp(1f), 0x14000000)
        }
        if (selected) drawable.setStroke(dp(2f), ContextCompat.getColor(context, R.color.heading_ink))
        val swatch = View(context)
        swatch.background = drawable
        cell.addView(
            swatch,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        if (selected) {
            val foreground = ScheduleView.manualPalette(String.format("#%06X", color and 0xFFFFFF))?.foreground
                ?: Color.WHITE
            cell.addView(
                TextView(context).apply {
                    text = "✓"
                    setTextColor(foreground)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
        }
        cell.isClickable = true
        cell.isFocusable = true
        cell.contentDescription = description
        cell.setOnClickListener { onClick() }
        if (onLongClick != null) {
            cell.setOnLongClickListener {
                onLongClick()
                true
            }
        }
        cell.layoutParams = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = 0
            height = dp(44f)
            setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        return cell
    }

    private fun buildFollowDefault() {
        findViewById<FrameLayout>(R.id.followDefaultSwatch).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6f).toFloat()
            setColor(autoColor)
        }
        findViewById<TextView>(R.id.followDefaultCheck).visibility =
            if (currentManual == null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.followDefault).setOnClickListener {
            onReset()
            dismiss()
        }
    }

    private fun configureCustomEditor() {
        val paletteContent = findViewById<View>(R.id.paletteContent)
        val editor = findViewById<View>(R.id.customColorEditor)
        val composer = findViewById<ColorComposerView>(R.id.colorComposer)
        val preview = findViewById<View>(R.id.customColorPreview)
        val hexText = findViewById<TextView>(R.id.customColorHex)

        fun render(color: Int) {
            val hex = String.format("#%06X", color and 0xFFFFFF)
            hexText.text = hex
            preview.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(7f).toFloat()
                setColor(color)
                setStroke(dp(1f), 0x18000000)
            }
        }

        findViewById<View>(R.id.addCustomColor).setOnClickListener {
            val startColor = currentManual?.let { value ->
                runCatching { Color.parseColor(value) }.getOrNull()
            } ?: autoColor
            composer.setColor(startColor)
            render(startColor)
            paletteContent.visibility = View.GONE
            editor.visibility = View.VISIBLE
        }
        composer.setOnColorChangedListener(::render)
        findViewById<View>(R.id.customColorCancel).setOnClickListener {
            editor.visibility = View.GONE
            paletteContent.visibility = View.VISIBLE
        }
        findViewById<View>(R.id.saveCustomColor).setOnClickListener {
            val hex = String.format("#%06X", composer.selectedColor() and 0xFFFFFF)
            Prefs.saveCustomCourseColor(hex)
            onPick(hex)
            dismiss()
        }
    }

    private fun dp(v: Float) = (v * context.resources.displayMetrics.density).roundToInt()
}
