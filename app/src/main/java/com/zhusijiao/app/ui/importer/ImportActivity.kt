package com.zhusijiao.app.ui.importer

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.AppConfig
import com.zhusijiao.app.MainActivity
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ApiClient
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.databinding.ActivityImportBinding
import com.zhusijiao.app.domain.EamsParser
import com.zhusijiao.app.domain.ParsedSchedule
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.ui.common.BaseActivity
import com.zhusijiao.app.ui.eams.EamsWebActivity
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/** 导入/更新课表页：文件导入，以及「应用内 WebView 登录教务系统直接导入」。 */
class ImportActivity : BaseActivity() {

    private lateinit var binding: ActivityImportBinding
    private var requestedTargetId = ""
    private var overwriteTarget: Schedule? = null
    private var targetLookupPending = false
    private var parsed: ParsedSchedule? = null
    private var semesterStart = ""

    private val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")

    private val eamsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val json = result.data?.getStringExtra(EamsWebActivity.EXTRA_JSON)
            if (json.isNullOrBlank()) {
                // 走到这里说明教务页宣称成功却没带回内容，给一句话，别让页面看着像没点过
                Ui.toast(this, getString(R.string.import_eams_empty))
            } else {
                binding.sourceInput.setText(json)
                showFileInfo(getString(R.string.import_source_eams), json.length)
                parseSource()
            }
        }
    }

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) handleFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ui.padBottomNav(binding.root)

        val explicitTargetId = intent.getStringExtra(EXTRA_ID).orEmpty()
        requestedTargetId = explicitTargetId.ifBlank { Prefs.activeScheduleId }
        binding.header.setTitle(getString(R.string.import_title))
        binding.header.setBackVisible(true)
        binding.header.onBackClick = { finish() }
        binding.newMode.isChecked = true
        binding.importModeSection.setOnCheckedChangeListener { _, _ -> applyModeDefaults() }

        binding.eamsBtn.setOnClickListener { eamsLauncher.launch(Intent(this, EamsWebActivity::class.java)) }
        binding.fileBtn.setOnClickListener { fileLauncher.launch("*/*") }
        binding.clipBtn.setOnClickListener { readClipboard() }
        binding.guideBtn.setOnClickListener {
            Ui.alert(this, getString(R.string.import_guide_title), getString(R.string.import_guide_content), getString(R.string.common_known))
        }
        binding.copyHelperBtn.setOnClickListener { copyHelperUrl() }
        binding.parseBtn.setOnClickListener { parseSource() }
        binding.datePicker.setOnClickListener { pickDate() }
        binding.saveBtn.setOnClickListener { save() }

        if (requestedTargetId.isNotBlank()) {
            targetLookupPending = true
            binding.overwriteMode.apply {
                visibility = View.VISIBLE
                isEnabled = false
            }
            loadOverwriteTarget(showError = explicitTargetId.isNotBlank())
        }
    }

    private fun loadOverwriteTarget(showError: Boolean) {
        lifecycleScope.launch {
            try {
                val schedule = ApiClient.getSchedule(requestedTargetId)
                if (!schedule.isOwner) {
                    binding.overwriteMode.visibility = View.GONE
                    binding.overwriteWarning.visibility = View.GONE
                    return@launch
                }
                overwriteTarget = schedule
                binding.overwriteMode.apply {
                    visibility = View.VISIBLE
                    isEnabled = true
                    text = getString(R.string.import_mode_overwrite, schedule.name)
                    isChecked = true
                }
                binding.overwriteWarning.apply {
                    visibility = View.VISIBLE
                    text = if (schedule.adjustments.isEmpty()) {
                        getString(R.string.import_mode_overwrite_desc)
                    } else {
                        getString(R.string.import_mode_overwrite_adjustments, schedule.adjustments.size)
                    }
                }
                updateSaveButton()
                applyModeDefaults()
            } catch (e: Exception) {
                binding.overwriteMode.visibility = View.GONE
                binding.overwriteWarning.visibility = View.GONE
                if (showError) Ui.toast(this@ImportActivity, e.message ?: getString(R.string.import_original_failed))
            } finally {
                targetLookupPending = false
                updateSaveButton()
            }
        }
    }

    private fun handleFile(uri: Uri) {
        val text = readUri(uri)
        if (text == null) {
            Ui.toast(this, getString(R.string.import_read_failed))
            return
        }
        binding.sourceInput.setText(text)
        showFileInfo(displayName(uri), text.length)
        parseSource()
    }

    private fun readUri(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_BYTES) {
                    Ui.toast(this, getString(R.string.import_file_too_large))
                    null
                } else String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun displayName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: getString(R.string.import_file_default_name)

    private fun readClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (text.isBlank()) {
            Ui.toast(this, getString(R.string.import_clip_empty))
            return
        }
        binding.sourceInput.setText(text)
        showFileInfo(getString(R.string.import_source_clipboard), text.length)
        parseSource()
    }

    private fun showFileInfo(name: String, length: Int) {
        binding.fileInfo.visibility = View.VISIBLE
        binding.fileName.text = name
        binding.fileChars.text = getString(R.string.import_file_chars, length)
    }

    private fun copyHelperUrl() {
        if (AppConfig.isLocalMode) {
            Ui.alert(this, getString(R.string.import_helper_unavailable_title), getString(R.string.import_helper_unavailable_content))
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("helper", AppConfig.helperScriptUrl))
        Ui.toast(this, getString(R.string.import_helper_copied))
    }

    private fun parseSource() {
        val source = binding.sourceInput.text.toString()
        if (source.isBlank()) {
            Ui.toast(this, getString(R.string.import_need_source))
            return
        }
        binding.parseBtn.isEnabled = false
        binding.parseBtn.text = getString(R.string.import_parsing)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { EamsParser.parseImportContent(source) }
                parsed = result
                renderResult(result)
                Ui.toast(this@ImportActivity, getString(R.string.import_parsed_count, result.courses.size))
            } catch (e: Exception) {
                parsed = null
                binding.resultSection.visibility = View.GONE
                Ui.alert(this@ImportActivity, getString(R.string.import_parse_failed_title), e.message ?: getString(R.string.import_need_source))
            } finally {
                binding.parseBtn.isEnabled = true
                binding.parseBtn.text = getString(R.string.import_parse)
            }
        }
    }

    private fun renderResult(p: ParsedSchedule) {
        binding.resultSection.visibility = View.VISIBLE
        binding.resultCount.text = getString(R.string.import_result_count, p.courses.size)
        binding.coursePreview.removeAllViews()
        val preview = p.courses.take(5)
        preview.forEachIndexed { index, course ->
            if (index > 0) binding.coursePreview.addView(divider())
            val row = layoutInflater.inflate(R.layout.item_import_preview, binding.coursePreview, false)
            row.findViewById<TextView>(R.id.rowDay).text = getString(R.string.import_week_day, dayNames.getOrElse(course.day - 1) { "" })
            row.findViewById<TextView>(R.id.rowName).text = course.name
            val place = course.position.ifBlank { getString(R.string.detail_place_pending) }
            row.findViewById<TextView>(R.id.rowSub).text = getString(R.string.import_preview_sub, place, course.startSection, course.endSection)
            row.findViewById<TextView>(R.id.rowWeek).text = weekText(course.weeks)
            binding.coursePreview.addView(row)
        }
        if (p.courses.size > 5) {
            val more = TextView(this).apply {
                text = getString(R.string.import_more, p.courses.size - 5)
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.sub_8a))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, com.zhusijiao.app.util.Rpx.dp(8f), 0, com.zhusijiao.app.util.Rpx.dp(8f))
            }
            binding.coursePreview.addView(more)
        }

        applyModeDefaults()
    }

    private fun divider(): View = View(this).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
        )
        setBackgroundColor(getColor(R.color.detail_border))
    }

    private fun weekText(weeks: List<Int>): String =
        if (weeks.size > 6) getString(R.string.import_week_range, weeks.first(), weeks.last())
        else getString(R.string.import_week_list, weeks.joinToString("、"))

    private fun pickDate() {
        val cal = Calendar.getInstance()
        val parts = semesterStart.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size == 3) cal.set(parts[0], parts[1] - 1, parts[2])
        DatePickerDialog(this, { _, y, m, d ->
            setDate("%04d-%02d-%02d".format(y, m + 1, d))
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun setDate(value: String) {
        semesterStart = value
        binding.datePicker.text = value
        binding.datePicker.setTextColor(getColor(if (value.isBlank()) R.color.sub_a0 else R.color.ink))
    }

    private fun save() {
        val p = parsed
        if (p == null) {
            Ui.toast(this, getString(R.string.import_need_parse))
            return
        }
        val name = binding.nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Ui.toast(this, getString(R.string.import_need_name))
            return
        }
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(semesterStart)) {
            Ui.toast(this, getString(R.string.import_need_date))
            return
        }
        val weeks = binding.weeksInput.text.toString().toIntOrNull() ?: 20
        val target = overwriteTarget?.takeIf { binding.overwriteMode.isChecked }
        if (target != null && target.adjustments.isNotEmpty()) {
            Ui.confirm(
                this,
                getString(R.string.import_overwrite_confirm_title),
                getString(R.string.import_overwrite_confirm_content, target.name, target.adjustments.size),
                confirmText = getString(R.string.import_overwrite_confirm)
            ) { performSave(p, name, weeks, target) }
            return
        }
        performSave(p, name, weeks, target)
    }

    private fun performSave(p: ParsedSchedule, name: String, weeks: Int, target: Schedule?) {
        binding.saveBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                val payload = p.copy(name = name, semesterStart = semesterStart, totalWeeks = weeks)
                val saved = ApiClient.saveSchedule(payload, target?.id, target?.revision)
                Prefs.activeScheduleId = saved.id
                Ui.toast(this@ImportActivity, getString(if (target != null) R.string.import_updated else R.string.import_created))
                val intent = Intent(this@ImportActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(MainActivity.EXTRA_OPEN_SCHEDULE, true)
                }
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                binding.saveBtn.isEnabled = true
                Ui.alert(this@ImportActivity, getString(R.string.import_save_failed), e.message ?: getString(R.string.common_load_failed))
            }
        }
    }

    private fun applyModeDefaults() {
        val p = parsed ?: return
        val target = overwriteTarget?.takeIf { binding.overwriteMode.isChecked }
        binding.nameInput.setText(target?.name ?: p.name)
        setDate(p.semesterStart.ifBlank { target?.semesterStart.orEmpty() })
        binding.weeksInput.setText((if (p.totalWeeks > 0) p.totalWeeks else target?.totalWeeks ?: 20).toString())
        updateSaveButton()
    }

    private fun updateSaveButton() {
        binding.saveBtn.setText(
            if (overwriteTarget != null && binding.overwriteMode.isChecked) {
                R.string.import_save_overwrite
            } else {
                R.string.import_save_new
            }
        )
        binding.saveBtn.isEnabled = !targetLookupPending
    }

    companion object {
        const val EXTRA_ID = "id"
        private const val MAX_BYTES = (1.8 * 1024 * 1024).toLong()
    }
}
