package com.zhusijiao.app.ui.couple

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.MainActivity
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ApiClient
import com.zhusijiao.app.databinding.ActivityCoupleBindBinding
import com.zhusijiao.app.domain.CoupleInvite
import com.zhusijiao.app.ui.common.BaseActivity
import com.zhusijiao.app.util.Rpx
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 绑定情侣课表：一方生成邀请码发给对方，另一方输入。
 * 生成邀请码后页面每隔几秒问一次服务端，对方一接受就自动跳到「我们」页。
 */
class CoupleBindActivity : BaseActivity() {

    private lateinit var binding: ActivityCoupleBindBinding
    private var invite: CoupleInvite? = null
    private var code = ""
    private var focused = false
    private var updating = false
    private var busy = false
    private var waitJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCoupleBindBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 有输入框：键盘弹出时整页底部让出输入法高度，滚动区跟着缩短，输入框不会被挡住
        Ui.liftAboveIme(binding.root)

        binding.header.setTitle(getString(R.string.couple_bind_title))
        binding.header.setBackVisible(true)
        binding.header.onBackClick = { finish() }

        if (ApiClient.isLocalMode) {
            Ui.alert(this, getString(R.string.import_helper_unavailable_title), getString(R.string.local_sharing_unavailable)) {
                finish()
            }
            return
        }

        binding.inviteButton.setOnClickListener { generateInvite() }
        binding.copyInvite.setOnClickListener { copyInvite() }
        binding.shareInvite.setOnClickListener { shareInvite() }

        binding.codeInput.setOnFocusChangeListener { _, has -> focused = has; renderCells() }
        binding.codeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                applyCode(s?.toString() ?: "")
            }
        })
        binding.codeField.setOnClickListener {
            binding.codeInput.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(binding.codeInput, InputMethodManager.SHOW_IMPLICIT)
        }
        binding.pasteBtn.setOnClickListener { paste() }
        binding.acceptButton.setOnClickListener { accept() }

        renderCells()
        // 之前生成过、还没过期的邀请码直接显示，并继续等对方
        ApiClient.coupleState().invite?.let { showInvite(it) }
    }

    override fun onResume() {
        super.onResume()
        if (invite != null) startWaiting()
    }

    override fun onPause() {
        super.onPause()
        waitJob?.cancel()
    }

    // ===== 邀请 TA =====

    private fun generateInvite() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                showInvite(ApiClient.createCoupleInvite())
                startWaiting()
            } catch (error: Exception) {
                Ui.toast(this@CoupleBindActivity, error.message ?: getString(R.string.common_load_failed))
            } finally {
                busy = false
            }
        }
    }

    private fun showInvite(value: CoupleInvite) {
        invite = value
        val margin = Rpx.dp(6f)
        binding.inviteChars.removeAllViews()
        value.code.forEachIndexed { index, ch ->
            binding.inviteChars.addView(TextView(this).apply {
                text = ch.toString()
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.value_ink))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                typeface = Typeface.DEFAULT_BOLD
                setBackgroundResource(R.drawable.bg_code_char)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (index < value.code.length - 1) marginEnd = margin
                }
            })
        }
        binding.inviteChars.contentDescription = value.code.toCharArray().joinToString(" ")
        binding.inviteChars.visibility = View.VISIBLE
        binding.inviteActions.visibility = View.VISIBLE
        binding.inviteWaiting.visibility = View.VISIBLE
        binding.inviteMeta.visibility = View.VISIBLE
        binding.inviteMeta.text = getString(R.string.couple_invite_expires, expiryText(value.expiresAt))
        binding.inviteButton.text = getString(R.string.couple_invite_regenerate)
    }

    /** 服务端给的是 UTC 时间，显示成本机的「9月26日 14:05」。 */
    private fun expiryText(iso: String): String = runCatching {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val date = parser.parse(iso) ?: return@runCatching iso
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(date)
    }.getOrDefault(iso)

    /** 等对方输入邀请码：每 4 秒问一次，绑定成功就进「我们」页。 */
    private fun startWaiting() {
        if (waitJob?.isActive == true) return
        waitJob = lifecycleScope.launch {
            while (isActive) {
                delay(WAIT_POLL_MS)
                ApiClient.syncCoupleNow()
                val state = ApiClient.coupleState()
                if (state.bound) {
                    onBound()
                    break
                }
                if (state.invite == null) {
                    // 邀请码过期或被替换：收起，让用户重新生成
                    binding.inviteWaiting.visibility = View.GONE
                    break
                }
            }
        }
    }

    private fun copyInvite() {
        val value = invite ?: return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("coupleInvite", value.code))
        Ui.toast(this, getString(R.string.couple_invite_copied))
    }

    private fun shareInvite() {
        val value = invite ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(R.string.couple_invite_message, value.code))
        }
        startActivity(Intent.createChooser(send, getString(R.string.couple_invite_chooser)))
    }

    // ===== 输入 TA 的邀请码 =====

    private fun normalizeCode(value: String): String =
        value.trim().uppercase().replace(Regex("[^2-9A-HJ-NP-Z]"), "").take(CODE_LENGTH)

    private fun applyCode(value: String) {
        val normalized = normalizeCode(value)
        if (normalized != binding.codeInput.text.toString()) {
            updating = true
            binding.codeInput.setText(normalized)
            binding.codeInput.setSelection(normalized.length)
            updating = false
        }
        code = normalized
        binding.errorNote.visibility = View.GONE
        renderCells()
    }

    private fun renderCells() {
        val margin = Rpx.dp(6f)
        binding.codeCells.removeAllViews()
        for (i in 0 until CODE_LENGTH) {
            val filled = i < code.length
            val active = focused && i == minOf(code.length, CODE_LENGTH - 1)
            binding.codeCells.addView(TextView(this).apply {
                text = code.getOrNull(i)?.toString() ?: ""
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.title_ink))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
                setBackgroundResource(
                    when {
                        active -> R.drawable.bg_code_cell_active
                        filled -> R.drawable.bg_code_cell_filled
                        else -> R.drawable.bg_code_cell_default
                    }
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i < CODE_LENGTH - 1) marginEnd = margin
                }
            })
        }
    }

    private fun paste() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val normalized = normalizeCode(text)
        if (normalized.isEmpty()) {
            Ui.toast(this, getString(R.string.couple_paste_empty))
            return
        }
        applyCode(normalized)
    }

    private fun accept() {
        if (busy) return
        if (code.length != CODE_LENGTH) {
            showError(getString(R.string.couple_accept_incomplete))
            return
        }
        busy = true
        lifecycleScope.launch {
            try {
                val state = ApiClient.acceptCoupleInvite(code)
                if (state.bound) onBound()
            } catch (error: Exception) {
                showError(error.message ?: getString(R.string.common_load_failed))
            } finally {
                busy = false
            }
        }
    }

    private fun showError(message: String) {
        binding.errorNote.text = message
        binding.errorNote.visibility = View.VISIBLE
    }

    private fun onBound() {
        waitJob?.cancel()
        Ui.toast(this, getString(R.string.couple_bound_toast))
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_COUPLE, true)
        )
        finish()
    }

    companion object {
        private const val CODE_LENGTH = 8
        private const val WAIT_POLL_MS = 4_000L
    }
}
