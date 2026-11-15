package com.zhusijiao.app.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zhusijiao.app.R

/**
 * 自定义页头：整屏居中标题、可选返回、右侧操作区。
 * 顶部按系统 insets 自动避让状态栏。
 */
class AppHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val backView: TextView
    private val titleView: TextView
    private val actionsView: LinearLayout

    var onBackClick: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_app_header, this, true)
        backView = findViewById(R.id.headerBack)
        titleView = findViewById(R.id.headerTitle)
        actionsView = findViewById(R.id.headerActions)
        backView.setOnClickListener { onBackClick?.invoke() }
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    fun setTitle(title: CharSequence?) {
        titleView.text = title ?: ""
    }

    fun setBackVisible(visible: Boolean) {
        backView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun addAction(view: View) {
        actionsView.addView(view)
    }

    fun clearActions() {
        actionsView.removeAllViews()
    }
}
