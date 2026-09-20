package com.zhusijiao.app.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ApiClient
import com.zhusijiao.app.databinding.ActivityShareBinding
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.ui.common.BaseActivity
import com.zhusijiao.app.util.Rpx
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.launch

/** 分享课表页：只读分享码字符格 + 统计 + 复制/更换/转发。 */
class ShareActivity : BaseActivity() {

    private lateinit var binding: ActivityShareBinding
    private var schedule: Schedule? = null
    private var scheduleId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ui.padBottomNav(binding.root)

        binding.header.setTitle(getString(R.string.share_title))
        binding.header.setBackVisible(true)
        binding.header.onBackClick = { finish() }

        scheduleId = intent.getStringExtra(EXTRA_ID) ?: ""
        binding.copyBtn.setOnClickListener { copyCode() }
        binding.rotateBtn.setOnClickListener { rotateCode() }
        binding.shareMain.setOnClickListener { shareSheet() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                // 本地课表始终可用；进入分享页时才要求联网发布/同步。
                val s = ApiClient.prepareShare(scheduleId)
                if (s.role != "owner") {
                    showError(getString(R.string.share_only_owner))
                    return@launch
                }
                schedule = s
                render(s)
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.share_schedule_not_found))
            }
        }
    }

    private fun render(s: Schedule) {
        binding.loading.visibility = View.GONE
        binding.scroll.visibility = View.VISIBLE
        binding.shareName.text = s.name
        binding.statSubscribers.text = s.subscriberCount.toString()
        binding.statCourses.text = s.courses.size.toString()
        binding.statWeeks.text = s.totalWeeks.toString()
        binding.statVersion.text = "v${s.revision}"
        renderCodeChars(s.shareCode ?: "")
    }

    private fun renderCodeChars(code: String) {
        val margin = Rpx.dp(6f)
        binding.codeChars.removeAllViews()
        code.forEachIndexed { index, ch ->
            val cell = TextView(this).apply {
                text = ch.toString()
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.value_ink))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 19f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setBackgroundResource(R.drawable.bg_code_char)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (index < code.length - 1) marginEnd = margin
                }
            }
            binding.codeChars.addView(cell)
        }
    }

    private fun copyCode() {
        val code = schedule?.shareCode ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("shareCode", code))
        Ui.toast(this, getString(R.string.share_copied))
    }

    private fun rotateCode() {
        Ui.confirm(
            this,
            getString(R.string.share_rotate_title),
            getString(R.string.share_rotate_content),
            confirmText = getString(R.string.share_rotate_confirm)
        ) {
            lifecycleScope.launch {
                try {
                    val newCode = ApiClient.rotateShareCode(scheduleId)
                    schedule = schedule?.copy(shareCode = newCode)
                    renderCodeChars(newCode)
                    Ui.toast(this@ShareActivity, getString(R.string.share_rotated))
                } catch (e: Exception) {
                    Ui.toast(this@ShareActivity, e.message ?: getString(R.string.common_load_failed))
                }
            }
        }
    }

    private fun shareSheet() {
        val s = schedule ?: return
        val text = getString(R.string.share_message, s.name, s.shareCode ?: "")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser_title)))
    }

    private fun showError(message: String) {
        binding.loading.visibility = View.GONE
        Ui.alert(this, getString(R.string.share_cannot), message) { finish() }
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
