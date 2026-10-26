package com.zhusijiao.app.ui.common

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ScheduleExporter
import com.zhusijiao.app.data.ScheduleExporter.Format
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.export.ExportOptions
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导出课表的整套交互：读数据 → 选项面板 → 保存到用户选的位置，或写进缓存再分享出去。
 *
 * 入口有两处，都用这一份流程：课表库卡片上的「导出」、设置页的「导出课表」（当前课表）。
 * - 保存到…：系统文件选择器（[ActivityResultContracts.CreateDocument]），不需要存储权限；
 * - 分享：写到 `cacheDir/export/`，经 [FileProvider] 交给微信、QQ 等，每次导出前清掉上一次的文件。
 *
 * 文件选择器的启动器必须在 Fragment 创建时注册，所以宿主要把它作为属性持有（同 [LeaveFlow]）。
 * 每种格式的 MIME 不同而启动器的 MIME 是注册时定死的，所以每种格式注册一个。
 */
class ExportFlow(private val fragment: Fragment) {

    private class Pending(
        val source: ScheduleExporter.Source,
        val format: Format,
        val options: ExportOptions,
        val fileName: String
    )

    /** 等待文件选择器返回的那次导出；进程在选择器期间被回收时会丢，届时提示重来。 */
    private var pending: Pending? = null
    private var sheet: ExportSheet? = null

    private val saveLaunchers: Map<Format, ActivityResultLauncher<String>> = Format.entries.associateWith { format ->
        fragment.registerForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri ->
            onSaveTarget(uri)
        }
    }

    /** 宿主视图销毁时调用，收起还开着的面板。 */
    fun dismiss() {
        sheet?.dismiss()
        sheet = null
    }

    /** 打开 [scheduleId] 这份课表的导出面板。 */
    fun show(scheduleId: String) {
        fragment.lifecycleScope.launch {
            val source = try {
                withContext(Dispatchers.IO) { ScheduleExporter.load(scheduleId) }
            } catch (e: Exception) {
                Ui.toastError(fragment.requireContext(), e.message ?: fragment.getString(R.string.export_failed))
                return@launch
            }
            if (!fragment.isAdded) return@launch
            val schedule = source.stored
            val totalWeeks = if (schedule.totalWeeks > 0) schedule.totalWeeks else 20
            val next = ExportSheet(
                fragment.requireContext(),
                schedule.name,
                totalWeeks,
                DateUtils.currentWeek(schedule.semesterStart, totalWeeks),
                DateUtils.hasSemesterStart(schedule.semesterStart),
                onSave = { format, options -> save(source, format, options) },
                onShare = { format, options -> share(source, format, options) }
            )
            next.setOnDismissListener { if (sheet === next) sheet = null }
            sheet?.dismiss()
            sheet = next
            next.show()
        }
    }

    private fun save(source: ScheduleExporter.Source, format: Format, options: ExportOptions) {
        val fileName = ScheduleExporter.fileName(source, format, options)
        pending = Pending(source, format, options, fileName)
        try {
            saveLaunchers.getValue(format).launch(fileName)
        } catch (_: Exception) {
            // 极少数精简系统没有文件选择器：退回分享
            pending = null
            share(source, format, options)
        }
    }

    private fun onSaveTarget(uri: Uri?) {
        val job = pending
        pending = null
        if (uri == null) return
        val context = fragment.context ?: return
        if (job == null) {
            Ui.toastError(context, context.getString(R.string.export_expired))
            return
        }
        fragment.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    // "wt" 截断已有内容；个别文档提供方不认 "wt"，退回 "w"
                    val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                        ?: resolver.openOutputStream(uri, "w")
                        ?: throw IllegalStateException(context.getString(R.string.export_failed))
                    stream.use { ScheduleExporter.write(job.source, job.format, job.options, it) }
                }
                Ui.toastSuccess(context, context.getString(R.string.export_saved))
            } catch (e: Exception) {
                Ui.alert(context, context.getString(R.string.export_failed), e.message ?: context.getString(R.string.export_failed))
            }
        }
    }

    private fun share(source: ScheduleExporter.Source, format: Format, options: ExportOptions) {
        val context = fragment.context ?: return
        fragment.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, EXPORT_DIR)
                    dir.deleteRecursively()
                    dir.mkdirs()
                    val target = File(dir, ScheduleExporter.fileName(source, format, options))
                    target.outputStream().use { ScheduleExporter.write(source, format, options, it) }
                    target
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = format.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, file.name)
                    // 部分系统的分享面板只认 ClipData 上的授权
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, context.getString(R.string.export_share_chooser)))
            } catch (e: Exception) {
                Ui.alert(context, context.getString(R.string.export_failed), e.message ?: context.getString(R.string.export_failed))
            }
        }
    }

    private companion object {
        /** 与 res/xml/file_paths.xml 里的 cache-path 一致。 */
        const val EXPORT_DIR = "export"
    }
}
