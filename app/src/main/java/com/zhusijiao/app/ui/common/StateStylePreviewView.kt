package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.TimetableAppearance

/**
 * 课表外观面板里「停课与日程的样式」卡片上的小预览：左边一节停课的课、右边一条日程，
 * 按给定样式画出来。新版配色取自 [BlockStyles]，经典配色与课表里的经典分支一致，预览和真实效果不会对不上。
 */
class StateStylePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var stateStyle: Int = TimetableAppearance.STATE_STYLE_MODERN
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val courseColor = 0xFF83C696.toInt() // 课程色板里的「嫩绿」，与示例课程「体育」对应
    private val classicGhostBg = Color.rgb(238, 240, 243)
    private val classicGhostText = Color.rgb(105, 112, 124)
    private val classicEventBg = Color.rgb(74, 90, 114)
    private val classicBadgeBg = ContextCompat.getColor(context, R.color.tt_badge_bg)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dash = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = dp(11f)
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(8.5f) }
    private val rect = RectF()

    private val courseName = context.getString(R.string.state_style_preview_course)
    private val eventName = context.getString(R.string.state_style_preview_event)
    private val suspendedBadge = context.getString(R.string.state_style_preview_suspended)
    private val eventBadge = context.getString(R.string.event_badge)

    override fun onDraw(canvas: Canvas) {
        val gap = dp(6f)
        val each = (width - gap) / 2f
        val modern = stateStyle != TimetableAppearance.STATE_STYLE_CLASSIC
        // 左：停课的课
        rect.set(0f, 0f, each, height.toFloat())
        if (modern) {
            drawBlock(canvas, Color.WHITE, BlockStyles.ghostStroke(courseColor), true,
                BlockStyles.ghostText(courseColor), BlockStyles.badgeFill(courseColor), courseName, suspendedBadge, strike = true)
        } else {
            drawBlock(canvas, classicGhostBg, classicGhostText, false,
                classicGhostText, classicBadgeBg, courseName, suspendedBadge, strike = false)
        }
        // 右：日程
        rect.set(each + gap, 0f, width.toFloat(), height.toFloat())
        val tone = BlockStyles.DEFAULT_EVENT_COLOR
        if (modern) {
            drawBlock(canvas, BlockStyles.eventFill(tone), BlockStyles.eventStroke(tone), false,
                BlockStyles.eventText(tone), BlockStyles.badgeFill(tone), eventName, eventBadge, strike = false)
        } else {
            drawBlock(canvas, classicEventBg, null, false, Color.WHITE, classicBadgeBg, eventName, eventBadge, strike = false)
        }
    }

    private fun drawBlock(
        canvas: Canvas,
        bg: Int,
        border: Int?,
        dashed: Boolean,
        text: Int,
        badgeBg: Int,
        name: String,
        badge: String,
        strike: Boolean
    ) {
        val radius = dp(6f)
        fill.color = bg
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (border != null) {
            stroke.color = border
            stroke.strokeWidth = dp(if (dashed) 1.5f else 1.2f)
            stroke.pathEffect = if (dashed) dash else null
            val half = stroke.strokeWidth / 2f
            canvas.drawRoundRect(RectF(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half), radius, radius, stroke)
        }
        val pad = dp(6f)
        val badgeW = badgePaint.measureText(badge) + dp(8f)
        val badgeH = dp(13f)
        fill.color = badgeBg
        canvas.drawRoundRect(RectF(rect.left + pad, rect.top + pad, rect.left + pad + badgeW, rect.top + pad + badgeH), dp(4f), dp(4f), fill)
        badgePaint.color = text
        val bfm = badgePaint.fontMetrics
        canvas.drawText(badge, rect.left + pad + dp(4f), rect.top + pad + badgeH / 2f - (bfm.ascent + bfm.descent) / 2f, badgePaint)
        namePaint.color = text
        namePaint.isStrikeThruText = strike
        canvas.drawText(name, rect.left + pad, rect.top + pad + badgeH + dp(4f) - namePaint.ascent(), namePaint)
        namePaint.isStrikeThruText = false
    }
}
