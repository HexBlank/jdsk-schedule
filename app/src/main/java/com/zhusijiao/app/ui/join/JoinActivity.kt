package com.zhusijiao.app.ui.join

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.MainActivity
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ApiClient
import com.zhusijiao.app.data.LocalScheduleStore
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.data.ScheduleSyncStore
import com.zhusijiao.app.databinding.ActivityJoinBinding
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.SharePreview
import com.zhusijiao.app.ui.common.BaseActivity
import com.zhusijiao.app.util.Rpx
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.launch

/** 加入课表页：分享码字符格 + 预览 + 加入。 */
class JoinActivity : BaseActivity() {

    private lateinit var binding: ActivityJoinBinding
    private var code = ""
    private var focused = false
    private var preview: SharePreview? = null
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJoinBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ui.padBottomNav(binding.root)

        binding.header.setTitle(getString(R.string.join_title))
        binding.header.setBackVisible(true)
        binding.header.onBackClick = { finish() }

        binding.codeInput.setOnFocusChangeListener { _, has -> focused = has; renderCells() }
        binding.codeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val normalized = normalizeCode(s?.toString() ?: "")
                if (normalized != s?.toString()) {
                    updating = true
                    binding.codeInput.setText(normalized)
                    binding.codeInput.setSelection(normalized.length)
                    updating = false
                }
                code = normalized
                renderCells()
                if (code.length == DEFAULT_CELLS) doPreview()
            }
        })
        binding.codeField.setOnClickListener {
            binding.codeInput.requestFocus()
            showKeyboard()
        }
        binding.pasteBtn.setOnClickListener { paste() }
        binding.lookupBtn.setOnClickListener { doPreview() }
        binding.joinConfirm.setOnClickListener { join() }

        renderCells()
        intent.getStringExtra(EXTRA_CODE)?.let {
            if (it.isNotBlank()) { applyCode(it); doPreview() }
        }
    }

    private fun normalizeCode(value: String): String =
        value.trim().uppercase().replace(Regex("[^2-9A-HJ-NP-Z]"), "").take(MAX_LENGTH)

    private fun applyCode(value: String): String {
        val normalized = normalizeCode(value)
        updating = true
        binding.codeInput.setText(normalized)
        binding.codeInput.setSelection(normalized.length)
        updating = false
        code = normalized
        preview = null
        binding.previewCard.visibility = View.GONE
        binding.lookupBtn.visibility = View.VISIBLE
        binding.errorNote.visibility = View.GONE
        renderCells()
        return normalized
    }

    private fun renderCells() {
        val count = maxOf(DEFAULT_CELLS, code.length)
        val dense = count > DEFAULT_CELLS
        val margin = Rpx.dp(if (dense) 4f else 6f)
        binding.codeCells.removeAllViews()
        for (i in 0 until count) {
            val ch = code.getOrNull(i)?.toString() ?: ""
            val filled = i < code.length
            val active = focused && i == minOf(code.length, count - 1)
            val cell = TextView(this).apply {
                text = ch
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.title_ink))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (dense) 14f else 18f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setBackgroundResource(
                    when {
                        active -> R.drawable.bg_code_cell_active
                        filled -> R.drawable.bg_code_cell_filled
                        else -> R.drawable.bg_code_cell_default
                    }
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i < count - 1) marginEnd = margin
                }
            }
            binding.codeCells.addView(cell)
        }
    }

    private fun paste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        applyCode(text)
        if (code.length >= 6) doPreview() else Ui.toast(this, getString(R.string.join_clip_empty))
    }

    private fun doPreview() {
        if (code.length < 6) {
            Ui.toast(this, getString(R.string.join_need_full))
            return
        }
        binding.errorNote.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val result = ApiClient.previewShareCode(code)
                preview = result
                hideKeyboard()
                showPreview(result)
            } catch (e: Exception) {
                preview = null
                showError(e.message ?: getString(R.string.join_need_full))
            }
        }
    }

    private fun showPreview(p: SharePreview) {
        binding.previewCard.visibility = View.VISIBLE
        binding.lookupBtn.visibility = View.GONE
        binding.errorNote.visibility = View.GONE
        binding.previewName.text = p.name
        when {
            p.owned -> { binding.previewTag.visibility = View.VISIBLE; binding.previewTag.setText(R.string.join_tag_owned) }
            p.joined -> { binding.previewTag.visibility = View.VISIBLE; binding.previewTag.setText(R.string.join_tag_joined) }
            else -> binding.previewTag.visibility = View.GONE
        }
        if (p.school.isNotBlank()) { binding.previewSchool.visibility = View.VISIBLE; binding.previewSchool.text = p.school }
        else binding.previewSchool.visibility = View.GONE
        binding.statSync.text = p.subscriberCount.toString()
        binding.statCourses.text = p.courseCount.toString()
        binding.statWeeks.text = p.totalWeeks.toString()
        binding.previewMeta.text = getString(
            R.string.join_meta_format, p.revision, DateUtils.relativeTime(p.updatedAt), p.semesterStart
        )
        binding.joinConfirm.setText(if (p.joined || p.owned) R.string.join_open else R.string.join_confirm)
    }

    private fun showError(message: String) {
        binding.previewCard.visibility = View.GONE
        binding.lookupBtn.visibility = View.VISIBLE
        binding.errorNote.visibility = View.VISIBLE
        binding.errorNote.text = message
    }

    private fun join() {
        val p = preview ?: return
        if (p.joined || p.owned) { openSchedule(p.id); return }
        lifecycleScope.launch {
            try {
                val schedule = ApiClient.joinShareCode(code)
                Ui.toast(this@JoinActivity, getString(R.string.join_success))
                openSchedule(schedule.id)
            } catch (e: Exception) {
                Ui.toast(this@JoinActivity, e.message ?: getString(R.string.common_load_failed))
            }
        }
    }

    private fun openSchedule(id: String) {
        // 预览/加入返回的是远端 id，本地副本可能使用不同的 id；
        // 先映射回本机课表 id，避免当前课表指向不存在的课表导致标记来回跳。
        val localId = ScheduleSyncStore.findByRemoteId(id)
            ?.localId?.takeIf { LocalScheduleStore.getScheduleOrNull(it) != null }
            ?: id
        if (LocalScheduleStore.getScheduleOrNull(localId) == null) {
            // 不能跳转：课表页找不到它会退回显示别的课表，用户会以为打开的是那一份
            Ui.alert(this, getString(R.string.schedule_missing_title), getString(R.string.join_missing_local))
            return
        }
        Prefs.activeScheduleId = localId
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_SCHEDULE, true)
        }
        startActivity(intent)
        finish()
    }

    private fun showKeyboard() {
        binding.codeInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.codeInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.codeInput.windowToken, 0)
    }

    companion object {
        const val EXTRA_CODE = "code"
        private const val DEFAULT_CELLS = 8
        private const val MAX_LENGTH = 12
    }
}
