package com.zhusijiao.app.ui.importer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.AppConfig
import com.zhusijiao.app.MainActivity
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ApiClient
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.databinding.ActivityImportBinding
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.EamsParser
import com.zhusijiao.app.domain.ParsedSchedule
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.export.ScheduleBackup
import com.zhusijiao.app.ui.common.BaseActivity
import com.zhusijiao.app.ui.common.WeekStartSheet
import com.zhusijiao.app.ui.eams.EamsWebActivity
import com.zhusijiao.app.ui.schedule.ScheduleFragment
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导入/更新课表页：文件导入，以及「应用内 WebView 登录教务系统直接导入」。
 *
 * 分两步、一屏只做一件事：先选来源（教务系统是主路径，文件 / 剪贴板 / 手动粘贴是行）；
 * 识别出来后整页换成结果——来源一行（点它回到第一步）、课程预览、导入到哪里、名称 / 第 1 周 / 总周数，
 * 保存钉在底部。第 1 周直接选整周（[WeekStartSheet]），不再让人选日期再提示「已按周一算起」。
 */
class ImportActivity : BaseActivity() {

    private lateinit var binding: ActivityImportBinding
    private var requestedTargetId = ""
    private var overwriteTarget: Schedule? = null
    private var targetLookupPending = false
    private var parsed: ParsedSchedule? = null
    /** 解析出来的是 JSON 备份（v2）时不为 null：保存时连同调课、日程等一起还原成新课表。 */
    private var backup: ScheduleBackup.Backup? = null
    private var semesterStart = ""
    /** 导入到哪里：true 覆盖 [overwriteTarget]，false 新建。 */
    private var overwriteSelected = false
    /** 当前内容来自哪里（「来自教务系统」「来自剪贴板」或文件名），显示在结果页顶部那一行。 */
    private var sourceLabel = ""

    private val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")

    private val eamsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val json = result.data?.getStringExtra(EamsWebActivity.EXTRA_JSON)
            if (json.isNullOrBlank()) {
                // 走到这里说明教务页宣称成功却没带回内容，给一句话，别让页面看着像没点过
                Ui.toastError(this, getString(R.string.import_eams_empty))
            } else {
                binding.sourceInput.setText(json)
                sourceLabel = getString(R.string.import_source_eams)
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
        // 有输入框：键盘弹出时整页底部让出输入法高度，滚动区跟着缩短，输入框不会被挡住
        Ui.liftAboveIme(binding.root)

        val explicitTargetId = intent.getStringExtra(EXTRA_ID).orEmpty()
        requestedTargetId = explicitTargetId.ifBlank { Prefs.activeScheduleId }
        binding.header.setTitle(getString(R.string.import_title))
        binding.header.setBackVisible(true)
        binding.header.onBackClick = { finish() }
        binding.overwriteRow.setOnClickListener { selectMode(overwrite = true) }
        binding.newRow.setOnClickListener { selectMode(overwrite = false) }
        binding.sourceRow.setOnClickListener { showSourceStep() }
        binding.pasteRow.setOnClickListener {
            binding.pasteArea.visibility = View.VISIBLE
            binding.pasteRow.visibility = View.GONE
            binding.sourceInput.requestFocus()
        }
        binding.nameInput.doAfterTextChanged { updateSaveButton() }
        binding.weeksStepper.configure(1, MAX_WEEKS, 20, { getString(R.string.import_week_count, it) }) {}

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
            loadOverwriteTarget(showError = explicitTargetId.isNotBlank())
        }
    }

    private fun loadOverwriteTarget(showError: Boolean) {
        lifecycleScope.launch {
            try {
                val schedule = ApiClient.getSchedule(requestedTargetId)
                // 订阅来的课表没有写权限，不能成为覆盖目标
                if (schedule.isOwner) {
                    overwriteTarget = schedule
                    // 有自己发布的课表时默认覆盖它（备份只能新建，见 renderBackupNote）
                    overwriteSelected = backup == null
                    applyModeDefaults()
                }
            } catch (e: Exception) {
                if (showError) Ui.toastError(this@ImportActivity, e.message ?: getString(R.string.import_original_failed))
            } finally {
                targetLookupPending = false
                renderTarget()
                updateSaveButton()
            }
        }
    }

    /** 「导入到哪里」：只有能覆盖的课表时才出现；覆盖会删掉手动调课时在下面写一句后果。 */
    private fun renderTarget() {
        val target = overwriteTarget
        binding.targetGroup.visibility = if (target != null) View.VISIBLE else View.GONE
        if (target == null) return
        val canOverwrite = backup == null && !targetLookupPending
        binding.overwriteText.text = getString(R.string.import_mode_overwrite, target.name)
        binding.overwriteRow.isEnabled = canOverwrite
        binding.overwriteRow.alpha = if (canOverwrite) 1f else 0.4f
        binding.overwriteCheck.visibility = if (overwriteSelected) View.VISIBLE else View.INVISIBLE
        binding.newCheck.visibility = if (overwriteSelected) View.INVISIBLE else View.VISIBLE
        val warn = overwriteSelected && target.adjustments.isNotEmpty()
        binding.overwriteWarning.visibility = if (warn) View.VISIBLE else View.GONE
        if (warn) binding.overwriteWarning.text = getString(R.string.import_mode_overwrite_adjustments, target.adjustments.size)
    }

    private fun selectMode(overwrite: Boolean) {
        if (overwrite == overwriteSelected) return
        overwriteSelected = overwrite
        applyModeDefaults()
    }

    private fun handleFile(uri: Uri) {
        val text = readUri(uri)
        if (text == null) {
            Ui.toastError(this, getString(R.string.import_read_failed))
            return
        }
        binding.sourceInput.setText(text)
        sourceLabel = displayName(uri)
        parseSource()
    }

    private fun readUri(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_BYTES) {
                    Ui.toastError(this, getString(R.string.import_file_too_large))
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
            Ui.toastError(this, getString(R.string.import_clip_empty))
            return
        }
        binding.sourceInput.setText(text)
        sourceLabel = getString(R.string.import_source_clipboard)
        parseSource()
    }

    private fun copyHelperUrl() {
        if (AppConfig.isLocalMode) {
            Ui.alert(this, getString(R.string.import_helper_unavailable_title), getString(R.string.import_helper_unavailable_content))
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("helper", AppConfig.helperScriptUrl))
        Ui.toastSuccess(this, getString(R.string.import_helper_copied))
    }

    private fun parseSource() {
        val source = binding.sourceInput.text.toString()
        if (source.isBlank()) {
            Ui.toastError(this, getString(R.string.import_need_source))
            return
        }
        binding.parseBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                val (result, restored) = withContext(Dispatchers.Default) {
                    val decoded = ScheduleBackup.decode(source)
                    if (decoded != null) decoded.toParsedSchedule() to decoded
                    else EamsParser.parseImportContent(source) to null
                }
                parsed = result
                backup = restored
                if (sourceLabel.isBlank()) sourceLabel = getString(R.string.import_source_pasted)
                renderResult(result)
            } catch (e: Exception) {
                parsed = null
                backup = null
                showSourceStep()
                Ui.alert(this@ImportActivity, getString(R.string.import_parse_failed_title), e.message ?: getString(R.string.import_need_source))
            } finally {
                binding.parseBtn.isEnabled = true
            }
        }
    }

    /** 回到第一步：来源重新选，已识别的结果不清掉（再选一次会覆盖）。 */
    private fun showSourceStep() {
        binding.sourceSection.visibility = View.VISIBLE
        binding.resultSection.visibility = View.GONE
        binding.saveBar.visibility = View.GONE
        binding.scroll.scrollTo(0, 0)
    }

    private fun renderResult(p: ParsedSchedule) {
        Ui.hideKeyboard(binding.root)
        binding.sourceSection.visibility = View.GONE
        binding.resultSection.visibility = View.VISIBLE
        binding.saveBar.visibility = View.VISIBLE
        binding.scroll.scrollTo(0, 0)
        binding.sourceName.text = sourceLabel
        // 下一次从文件 / 剪贴板 / 粘贴进来时重新记来源
        sourceLabel = ""
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
                setTextColor(getColor(R.color.text_tertiary))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, com.zhusijiao.app.util.Rpx.dp(8f), 0, com.zhusijiao.app.util.Rpx.dp(8f))
            }
            binding.coursePreview.addView(more)
        }

        renderBackupNote()
        applyModeDefaults()
    }

    /**
     * 备份只能恢复成新课表：「覆盖」会清空调课，订阅来的课表覆盖后再同步还会冲突。
     * 所以解析出备份时锁定「新建课表」，换成别的内容再解析时恢复原样。
     */
    private fun renderBackupNote() {
        val restoring = backup
        // 课程以外还会恢复的内容是个值（「另含调课 3 条、日程 2 条」），没有就不显示
        val parts = restoring?.let { ScheduleBackup.summaryParts(it) }.orEmpty().joinToString("、") { (label, count) ->
            getString(R.string.import_backup_part, label, count)
        }
        binding.backupNote.visibility = if (parts.isNotEmpty()) View.VISIBLE else View.GONE
        binding.backupNote.text = if (parts.isNotEmpty()) getString(R.string.import_backup_note, parts) else ""
        if (restoring != null) overwriteSelected = false
        renderTarget()
    }

    private fun divider(): View = View(this).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
        )
        setBackgroundColor(getColor(R.color.divider))
    }

    private fun weekText(weeks: List<Int>): String =
        if (weeks.size > 6) getString(R.string.import_week_range, weeks.first(), weeks.last())
        else getString(R.string.import_week_list, weeks.joinToString("、"))

    private fun pickDate() {
        Ui.hideKeyboard(binding.root)
        WeekStartSheet(this, semesterStart) { monday -> setDate(monday) }.show()
    }

    /**
     * 第 1 周一律从周一算起：用户选了周中或周末的日子（学校常把报到日当开学日）就回退到
     * 那一周的周一。否则 day 1 不是真正的周一，此后每一天的星期都会整体偏移，
     * 调休日历会把周六标成周一，照着那一格停课就停错了天。
     */
    private fun setDate(value: String) {
        val aligned = if (DateUtils.hasSemesterStart(value)) DateUtils.weekStartOf(value) else value
        semesterStart = aligned
        binding.dateValue.text = if (DateUtils.hasSemesterStart(aligned)) WeekStartSheet.label(this, aligned)
        else getString(R.string.import_date_hint)
        updateSaveButton()
    }

    private fun save() {
        val p = parsed
        if (p == null) {
            Ui.toastError(this, getString(R.string.import_need_parse))
            return
        }
        val name = binding.nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Ui.toastError(this, getString(R.string.import_need_name))
            return
        }
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(semesterStart)) {
            Ui.toastError(this, getString(R.string.import_need_date))
            return
        }
        val weeks = binding.weeksStepper.value
        val target = overwriteTarget?.takeIf { overwriteSelected && backup == null }
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
                val restoring = backup
                var skipped = 0
                val saved = if (restoring != null) {
                    ApiClient.restoreBackup(payload, restoring).also { skipped = it.skipped }.items
                } else {
                    ApiClient.saveSchedule(payload, target?.id, target?.revision)
                }
                val switched = Prefs.activeScheduleId != saved.id
                Prefs.activeScheduleId = saved.id
                ScheduleFragment.requestIntro(saved.id)
                // 情侣课表：TA 看到的是当前课表，切换了要让用户知道（轻提示同时只显示一条，所以二选一）
                val message = when {
                    switched && ApiClient.coupleState().bound -> getString(R.string.library_couple_switch_toast)
                    restoring != null && skipped > 0 -> getString(R.string.import_backup_restored_skipped, skipped)
                    restoring != null -> getString(R.string.import_backup_restored)
                    target != null -> getString(R.string.import_updated)
                    else -> getString(R.string.import_created)
                }
                Ui.toastSuccess(this@ImportActivity, message)
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
        renderTarget()
        updateSaveButton()
        val p = parsed ?: return
        val target = overwriteTarget?.takeIf { overwriteSelected }
        binding.nameInput.setText(target?.name ?: p.name)
        setDate(p.semesterStart.ifBlank { target?.semesterStart.orEmpty() })
        val weeks = if (p.totalWeeks > 0) p.totalWeeks else target?.totalWeeks ?: 20
        binding.weeksStepper.configure(1, MAX_WEEKS, weeks.coerceIn(1, MAX_WEEKS), { getString(R.string.import_week_count, it) }) {}
    }

    /** 按钮文案随选择变；名称、第 1 周没填齐时置灰，不等点了再报错。 */
    private fun updateSaveButton() {
        binding.saveBtn.setText(
            if (overwriteTarget != null && overwriteSelected) R.string.import_save_overwrite else R.string.import_save_new
        )
        binding.saveBtn.isEnabled = !targetLookupPending && parsed != null &&
            binding.nameInput.text.isNotBlank() && DateUtils.hasSemesterStart(semesterStart)
    }

    companion object {
        const val EXTRA_ID = "id"
        private const val MAX_BYTES = (1.8 * 1024 * 1024).toLong()
        /** 与 ScheduleValidator 的总周数上限一致。 */
        private const val MAX_WEEKS = 30
    }
}
