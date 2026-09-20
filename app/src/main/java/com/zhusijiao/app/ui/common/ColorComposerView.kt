package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/** 轻量 HSV 调色板：上方选饱和度/明度，下方选色相。 */
class ColorComposerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.WHITE
        setShadowLayer(dp(2f), 0f, dp(1f), 0x66000000)
    }
    private val hsv = floatArrayOf(0f, 0.65f, 0.72f)
    private var hueShader: Shader? = null
    private var listener: ((Int) -> Unit)? = null

    // 拖动时 onDraw 会被连续调用，着色器按「尺寸 + 色相」缓存，避免每帧重新分配。
    private var spectrumShader: Shader? = null
    private var spectrumHue = Float.NaN
    private val hueOnly = floatArrayOf(0f, 1f, 1f)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isFocusable = true
    }

    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        updateDescription()
        invalidate()
    }

    fun selectedColor(): Int = Color.HSVToColor(hsv)

    fun setOnColorChangedListener(value: (Int) -> Unit) {
        listener = value
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        spectrumShader = null
        spectrumHue = Float.NaN
        hueShader = LinearGradient(
            paddingLeft.toFloat(), hueTop(), (w - paddingRight).toFloat(), hueTop(),
            intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                Color.BLUE, Color.MAGENTA, Color.RED
            ),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = spectrumBottom()
        if (spectrumShader == null || spectrumHue != hsv[0]) {
            hueOnly[0] = hsv[0]
            val hueColor = Color.HSVToColor(hueOnly)
            val saturation =
                LinearGradient(left, top, right, top, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
            val value =
                LinearGradient(left, top, left, bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            spectrumShader = ComposeShader(saturation, value, PorterDuff.Mode.MULTIPLY)
            spectrumHue = hsv[0]
        }
        paint.shader = spectrumShader
        canvas.drawRoundRect(left, top, right, bottom, dp(8f), dp(8f), paint)

        paint.shader = hueShader
        canvas.drawRoundRect(left, hueTop(), right, hueBottom(), dp(6f), dp(6f), paint)
        paint.shader = null

        knobPaint.color = if (hsv[2] > 0.72f && hsv[1] < 0.45f) Color.DKGRAY else Color.WHITE
        canvas.drawCircle(
            left + hsv[1] * (right - left),
            top + (1f - hsv[2]) * (bottom - top),
            dp(7f),
            knobPaint
        )
        knobPaint.color = Color.WHITE
        canvas.drawCircle(left + hsv[0] / 360f * (right - left), (hueTop() + hueBottom()) / 2f, dp(6f), knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(false)
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val x = event.x.coerceIn(left, right)
        if (event.y >= hueTop() - dp(8f)) {
            hsv[0] = ((x - left) / (right - left) * 360f).coerceIn(0f, 359.9f)
        } else {
            val y = event.y.coerceIn(paddingTop.toFloat(), spectrumBottom())
            hsv[1] = ((x - left) / (right - left)).coerceIn(0f, 1f)
            hsv[2] = (1f - (y - paddingTop) / (spectrumBottom() - paddingTop)).coerceIn(0f, 1f)
        }
        updateDescription()
        listener?.invoke(selectedColor())
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateDescription() {
        contentDescription = String.format("自定义颜色 #%06X", selectedColor() and 0xFFFFFF)
    }

    private fun spectrumBottom() = height - paddingBottom - dp(38f)
    private fun hueTop() = height - paddingBottom - dp(24f)
    private fun hueBottom() = height - paddingBottom.toFloat()
    private fun dp(value: Float) = (value * resources.displayMetrics.density).roundToInt().toFloat()
}
