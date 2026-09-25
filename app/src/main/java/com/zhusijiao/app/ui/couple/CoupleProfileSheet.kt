package com.zhusijiao.app.ui.couple

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.CouplePalette
import com.zhusijiao.app.domain.CoupleState
import com.zhusijiao.app.ui.common.ColorPickerSheet
import com.zhusijiao.app.ui.common.ImeSheetDialog

/**
 * 改名字和颜色：点日视图顶部的名字、周视图左上角的图例，或设置里的「名字和颜色」进入。
 * [who] = "me" / "partner"，双方都能改双方；保存后两部手机上都会变。
 * 颜色和对方太像时只提示、不拦：分栏时还有左右位置可以区分。
 */
class CoupleProfileSheet(
    context: Context,
    private val who: String,
    state: CoupleState,
    private val onSave: (nickname: String, color: String) -> Unit
) : ImeSheetDialog(context) {

    private val isMe = who == "me"
    private val member = requireNotNull(if (isMe) state.me else state.partner)
    private val otherColor = (if (isMe) state.partner else state.me)?.color
    private val otherName = if (isMe) state.partnerName else state.myName
    private val originalColor = member.color
    private var selectedColor = member.color

    private val nameInput: EditText
    private val swatchRow: LinearLayout

    init {
        setContentView(R.layout.dialog_couple_profile)
        setCanceledOnTouchOutside(true)

        findViewById<TextView>(R.id.profileTitle).text = context.getString(
            if (isMe) R.string.couple_profile_title_me else R.string.couple_profile_title_partner
        )
        findViewById<TextView>(R.id.previewHint).text = context.getString(
            if (isMe) R.string.couple_profile_hint_me else R.string.couple_profile_hint_partner
        )
        nameInput = findViewById(R.id.nameInput)
        nameInput.hint = context.getString(
            if (isMe) R.string.couple_profile_name_hint_me else R.string.couple_profile_name_hint_partner
        )
        nameInput.setText(member.nickname)
        nameInput.setSelection(nameInput.text.length)
        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        swatchRow = findViewById(R.id.swatchRow)
        findViewById<TextView>(R.id.customColor).setOnClickListener { openCustomPicker() }
        findViewById<TextView>(R.id.profileCancel).setOnClickListener { dismiss() }
        findViewById<TextView>(R.id.profileSave).setOnClickListener {
            onSave(nameInput.text.toString().trim(), selectedColor)
            dismiss()
        }
        render()
    }

    private fun render() {
        val colors = CoupleColors.of(selectedColor)
        findViewById<LinearLayout>(R.id.previewBlock).background = GradientDrawable().apply {
            cornerRadius = dp(10f)
            setColor(colors.fill)
        }
        findViewById<TextView>(R.id.previewCourse).setTextColor(colors.ink)
        findViewById<TextView>(R.id.previewRoom).setTextColor(colors.ink)
        findViewById<View>(R.id.previewSwatch).showSwatch(colors.fill)
        val typed = nameInput.text.toString().trim()
        findViewById<TextView>(R.id.previewName).text = typed.ifEmpty { nameInput.hint }

        swatchRow.removeAllViews()
        CouplePalette.PRESETS.forEach { preset ->
            val selected = preset.fill.equals(selectedColor, ignoreCase = true)
            val cell = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                isClickable = true
                isFocusable = true
                contentDescription = preset.label + if (selected) "，已选" else ""
                setOnClickListener {
                    selectedColor = preset.fill
                    render()
                }
            }
            val size = dp(30f).toInt()
            val dot = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(CouplePalette.argb(preset.fill) ?: 0)
                    if (selected) setStroke(dp(2.5f).toInt(), CouplePalette.argb(preset.ink) ?: 0)
                }
            }
            cell.addView(dot)
            swatchRow.addView(cell)
        }

        val clash = findViewById<TextView>(R.id.clashNote)
        val tooClose = otherColor != null && CouplePalette.tooClose(selectedColor, otherColor)
        clash.visibility = if (tooClose) View.VISIBLE else View.GONE
        if (tooClose) clash.text = context.getString(R.string.couple_profile_clash, otherName)
    }

    /** 自定义颜色复用课程选色面板；「跟随默认」在这里表示回到打开面板时的颜色。 */
    private fun openCustomPicker() {
        val name = nameInput.text.toString().trim().ifEmpty { nameInput.hint.toString() }
        ColorPickerSheet(
            context,
            courseName = context.getString(R.string.couple_profile_custom_title, name),
            autoColor = CouplePalette.argb(originalColor) ?: 0,
            currentManual = selectedColor,
            onPick = { hex ->
                CouplePalette.normalize(hex)?.let { selectedColor = it }
                render()
            },
            onReset = {
                selectedColor = originalColor
                render()
            },
            title = context.getString(R.string.couple_profile_custom)
        ).show()
    }

    private fun dp(value: Float) = value * context.resources.displayMetrics.density
}
