package com.zhusijiao.app.ui.couple

import android.graphics.drawable.GradientDrawable
import android.view.View
import com.zhusijiao.app.domain.CouplePalette
import com.zhusijiao.app.ui.common.CoupleDayView

/** 情侣课表里一个人的颜色：底色、文字色、描边色（正在上课的那圈、日程的虚线框）。 */
data class CoupleColors(val fill: Int, val ink: Int, val ring: Int) {

    fun column(name: String, items: List<com.zhusijiao.app.domain.CoupleDay.Item>, placeholder: String? = null) =
        CoupleDayView.Column(name, fill, ink, ring, items, placeholder)

    companion object {
        fun of(color: String): CoupleColors {
            val fill = CouplePalette.argb(color) ?: CouplePalette.argb(CouplePalette.INVITER_COLOR)!!
            val ink = CouplePalette.argb(CouplePalette.inkFor(color)) ?: 0xff202725.toInt()
            return CoupleColors(fill, ink, darken(fill, 0.22f))
        }

        private fun darken(color: Int, amount: Float): Int {
            fun channel(shift: Int) = ((color shr shift and 0xff) * (1f - amount)).toInt().coerceIn(0, 255)
            return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}

/** 名字旁、图例里的小色块。 */
fun View.showSwatch(color: Int, sizeDp: Float = 10f) {
    background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 3f * resources.displayMetrics.density * sizeDp / 10f
        setColor(color)
    }
}
