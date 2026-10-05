package com.zhusijiao.app.ui.common

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.R
import com.zhusijiao.app.data.LeaveStore
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.Leave
import com.zhusijiao.app.domain.Leaves
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleTime
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 请假的整套交互：记录面板、编辑面板、落盘、撤销。
 *
 * 请假不是常用功能，入口刻意放得不显眼，共两处，都用这一份流程：
 * - 课程详情里的「这节课请假」（[showForCourse]，按这节课的上下课时间预填）；
 * - 设置页「请假」一行（[showList]，看全部记录、请跨天的长假）。
 *
 * 每次增删改落盘后回调 [onChanged]（主线程），宿主拿最新的请假列表重画自己的界面。
 */
class LeaveFlow(
    private val fragment: Fragment,
    private val onChanged: (List<Leave>) -> Unit
) {

    /** 打开中的记录面板：增删改后就地刷新，不必关掉再进。 */
    private var listSheet: LeaveListSheet? = null

    /** 宿主视图销毁时调用，收起还开着的面板。 */
    fun dismiss() {
        listSheet?.dismiss()
        listSheet = null
    }

    /** 请假记录面板。课表没有开学日期时说明原因、不进面板。 */
    fun showList(schedule: Schedule, leaves: List<Leave>) {
        if (!ready(schedule)) return
        val context = fragment.requireContext()
        val sheet = LeaveListSheet(
            context,
            schedule,
            leaves,
            onAdd = {
                // 默认请今天一整个教学日：第一节上课到最后一节下课
                val slots = ScheduleTime.slotsOf(schedule.timeSlots)
                val lastSection = schedule.courses.maxOfOrNull { it.endSection } ?: slots.last().number
                val today = DateUtils.formatDate(Calendar.getInstance())
                val start = ScheduleTime.minutesOf(slots.first().startTime) ?: (8 * 60)
                val end = ScheduleTime.minutesOf(slots.find { it.number == lastSection }?.endTime) ?: (18 * 60)
                showEditor(schedule, null, Leaves.moment(today, start), Leaves.moment(today, maxOf(end, start + 1)))
            },
            onEdit = { leave -> showEditor(schedule, leave, leave.start, leave.end) }
        )
        sheet.setOnDismissListener { if (listSheet === sheet) listSheet = null }
        listSheet = sheet
        sheet.show()
    }

    /** 从课程详情进入：已请假的打开那条请假，否则按这节课的上下课时间预填一条。 */
    fun showForCourse(schedule: Schedule, click: TimetableView.CourseClick) {
        if (!ready(schedule)) return
        click.leave?.let { leave ->
            showEditor(schedule, leave, leave.start, leave.end)
            return
        }
        val dateIso = click.dateIso ?: return
        val slots = ScheduleTime.slotsOf(schedule.timeSlots)
        val start = ScheduleTime.minutesOf(slots.find { it.number == click.course.startSection }?.startTime) ?: return
        val end = ScheduleTime.minutesOf(slots.find { it.number == click.course.endSection }?.endTime) ?: return
        showEditor(schedule, null, Leaves.moment(dateIso, start), Leaves.moment(dateIso, maxOf(end, start + 1)))
    }

    /** 没有开学日期的课表推不出每节课是哪一天，没法按时间请假。 */
    private fun ready(schedule: Schedule): Boolean {
        if (DateUtils.hasSemesterStart(schedule.semesterStart)) return true
        val context = fragment.requireContext()
        Ui.alert(context, context.getString(R.string.leave_need_semester_title), context.getString(R.string.leave_need_semester))
        return false
    }

    private fun showEditor(schedule: Schedule, existing: Leave?, start: String, end: String) {
        val context = fragment.requireContext()
        LeaveEditorSheet(
            context,
            schedule,
            existing,
            initialStart = start,
            initialEnd = end,
            onSave = { draft ->
                persist(context.getString(R.string.leave_saved)) { LeaveStore.save(draft, existing?.id) }
            },
            onDelete = existing?.let { leave ->
                {
                    persist(
                        context.getString(R.string.leave_deleted),
                        undo = { persist(context.getString(R.string.leave_restored)) { LeaveStore.restore(leave) } }
                    ) { LeaveStore.delete(leave.id) }
                }
            }
        ).show()
    }

    /** 落盘后重读列表、通知宿主、刷新记录面板；[undo] 非空时轻提示带「撤销」。 */
    private fun persist(message: String, undo: (() -> Unit)? = null, block: () -> Unit) {
        val owner = fragment.viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch {
            val context = fragment.context ?: return@launch
            try {
                val loaded = withContext(Dispatchers.IO) {
                    block()
                    LeaveStore.list()
                }
                if (fragment.view == null) return@launch
                onChanged(loaded)
                listSheet?.update(loaded)
                Ui.toastSuccess(
                    context,
                    message,
                    undo?.let { AppToast.Action(context.getString(R.string.common_undo)) { it() } }
                )
            } catch (error: Exception) {
                Ui.toastError(context, error.message ?: context.getString(R.string.common_load_failed))
            }
        }
    }
}
