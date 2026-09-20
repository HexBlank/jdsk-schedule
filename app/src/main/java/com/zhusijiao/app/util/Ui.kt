package com.zhusijiao.app.util

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.ui.common.AppDialog

/**
 * UI 辅助：统一的 toast 与弹窗入口，以及状态栏/导航栏安全区避让。
 * 弹窗统一使用自定义 [AppDialog]（不使用系统 AlertDialog），保证全站视觉一致。
 */
object Ui {

    fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /** 单按钮提示。 */
    fun alert(
        context: Context,
        title: String,
        message: String,
        positive: String = context.getString(R.string.common_known),
        onPositive: (() -> Unit)? = null
    ) {
        AppDialog(context)
            .title(title)
            .message(message)
            .positive(positive, onPositive)
            .show()
    }

    /** 二次确认。confirmColor 非空时主按钮用危险（红色）样式。 */
    fun confirm(
        context: Context,
        title: String,
        message: String,
        confirmText: String = context.getString(R.string.common_confirm),
        cancelText: String = context.getString(R.string.common_cancel),
        confirmColor: Int? = null,
        onCancel: (() -> Unit)? = null,
        onConfirm: () -> Unit
    ) {
        val dialog = AppDialog(context)
            .title(title)
            .message(message)
            .negative(cancelText, onCancel)
            .positive(confirmText, onConfirm)
        if (confirmColor != null) dialog.danger()
        dialog.show()
    }

    /** 让视图顶部避让状态栏。 */
    fun padTopStatusBar(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
        view.requestApplyInsets()
    }

    /** 让视图底部避让手势/导航条。 */
    fun padBottomNav(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
            insets
        }
        view.requestApplyInsets()
    }

    /**
     * 底部抽屉避让软键盘：把 [view] 整体上抬到输入法上方（微信式「顶上去」），
     * 同时始终避让手势/导航条。用于 edge-to-edge 下 adjustResize 不再缩窗的场景
     * （API 30+ IME 只发 inset 不触发 resize）。
     */
    fun liftAboveIme(view: View) {
        val baseBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            )
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, baseBottom + bottom)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            view,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    val bottom = maxOf(
                        insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                        insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                    )
                    view.setPadding(
                        view.paddingLeft, view.paddingTop, view.paddingRight, baseBottom + bottom
                    )
                    return insets
                }
            }
        )
        view.requestApplyInsets()
    }
}
