package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R

/**
 * 通用自定义模态弹窗，替代系统 AlertDialog，与应用视觉保持一致：
 * 白色圆角卡片 + 标题/正文 + 底部按钮（主按钮实心绿、次按钮描边、危险按钮红色），
 * 带缩放淡入淡出动画与半透明遮罩。全站统一通过 [com.zhusijiao.app.util.Ui] 调用。
 */
class AppDialog(context: Context) : Dialog(context, R.style.Theme_Zhusijiao_Modal) {

    private val titleView: TextView
    private val messageView: TextView
    private val negativeView: TextView
    private val positiveView: TextView

    private var onPositive: (() -> Unit)? = null
    private var onNegative: (() -> Unit)? = null

    init {
        setContentView(R.layout.dialog_app_modal)
        titleView = findViewById(R.id.modalTitle)
        messageView = findViewById(R.id.modalMessage)
        negativeView = findViewById(R.id.modalNegative)
        positiveView = findViewById(R.id.modalPositive)
        setCanceledOnTouchOutside(true)
        negativeView.setOnClickListener { dismiss(); onNegative?.invoke() }
        positiveView.setOnClickListener { dismiss(); onPositive?.invoke() }
    }

    fun title(text: CharSequence?) = apply {
        titleView.text = text
        titleView.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun message(text: CharSequence?) = apply {
        messageView.text = text
        messageView.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun positive(text: CharSequence, action: (() -> Unit)? = null) = apply {
        positiveView.text = text
        onPositive = action
    }

    fun negative(text: CharSequence, action: (() -> Unit)? = null) = apply {
        negativeView.visibility = View.VISIBLE
        negativeView.text = text
        onNegative = action
    }

    /** 主按钮切换为危险样式（红色），用于删除/退出等不可逆操作。 */
    fun danger() = apply {
        positiveView.setBackgroundResource(R.drawable.bg_danger_button)
        positiveView.setTextColor(ContextCompat.getColor(context, R.color.danger))
    }
}
