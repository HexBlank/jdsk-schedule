package com.zhusijiao.app.ui.library

import android.content.Intent
import android.os.Bundle
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.MainActivity
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ApiClient
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.data.ScheduleSyncStore
import com.zhusijiao.app.data.SyncLog
import com.zhusijiao.app.databinding.FragmentLibraryBinding
import com.zhusijiao.app.databinding.ItemScheduleCardBinding
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.ui.common.ActionSheet
import com.zhusijiao.app.ui.common.BottomNavView
import com.zhusijiao.app.ui.common.ExportFlow
import com.zhusijiao.app.ui.common.Refreshable
import com.zhusijiao.app.ui.importer.ImportActivity
import com.zhusijiao.app.ui.join.JoinActivity
import com.zhusijiao.app.ui.share.ShareActivity
import com.zhusijiao.app.util.SyncLogClipboard
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.launch

/** 课表库页：本机与已加入课表的列表管理。 */
class LibraryFragment : Fragment(), Refreshable {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private var syncRunning = false
    private val exportFlow = ExportFlow(this)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.header.setTitle(getString(R.string.library_title))
        BottomNavView.padScrollContent(binding.scroll)
        val openImport = View.OnClickListener { startActivity(Intent(requireContext(), ImportActivity::class.java)) }
        val openJoin = View.OnClickListener { startActivity(Intent(requireContext(), JoinActivity::class.java)) }
        binding.importBtn.setOnClickListener(openImport)
        binding.emptyImport.setOnClickListener(openImport)
        binding.joinBtn.setOnClickListener(openJoin)
        binding.emptyJoin.setOnClickListener(openJoin)
        // 本机模式没有数据服务，加入永远走不通：不放一个点了才报错的按钮
        val joinVisibility = if (ApiClient.isLocalMode) View.GONE else View.VISIBLE
        binding.joinBtn.visibility = joinVisibility
        binding.emptyJoin.visibility = joinVisibility
    }

    override fun onDestroyView() {
        super.onDestroyView()
        exportFlow.dismiss()
        _binding = null
    }

    override fun refresh() {
        if (_binding != null) load()
    }

    private fun load(syncRemote: Boolean = true) {
        binding.skeletonList.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        binding.cardList.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val activeId = Prefs.activeScheduleId
                val schedules = ApiClient.listSchedules()
                binding.skeletonList.visibility = View.GONE
                binding.countText.text = getString(R.string.library_count, schedules.size)
                // 空的时候只剩「一句标题 + 按钮」：顶部按钮行与列表标题让位给空状态
                val empty = schedules.isEmpty()
                binding.actionRow.visibility = if (empty) View.GONE else View.VISIBLE
                binding.listHeader.visibility = if (empty) View.GONE else View.VISIBLE
                if (empty) {
                    binding.emptyState.visibility = View.VISIBLE
                } else {
                    schedules.forEach { binding.cardList.addView(buildCard(it, it.id == activeId)) }
                }
                if (syncRemote && !ApiClient.isLocalMode && !syncRunning) {
                    syncRunning = true
                    val synced = ApiClient.syncSchedules()
                    syncRunning = false
                    if (_binding == null) return@launch
                    ApiClient.takeNewSyncError()?.let { Ui.toastError(requireContext(), it) }
                    // 失败时也重绘：卡片上的同步状态可能变成「同步失败」
                    load(syncRemote = false)
                }
            } catch (e: Exception) {
                binding.skeletonList.visibility = View.GONE
                Ui.toastError(requireContext(), e.message ?: getString(R.string.common_load_failed))
            }
        }
    }

    private fun buildCard(item: Schedule, active: Boolean): View {
        val card = ItemScheduleCardBinding.inflate(layoutInflater, binding.cardList, false)
        val isOwner = item.role == "owner"
        val courseCount = if (item.courseCount > 0) item.courseCount else item.courses.size
        val subscribers = item.subscriberCount

        card.cardRoot.setBackgroundResource(if (active) R.drawable.bg_card_active else R.drawable.bg_card)
        card.cardName.text = item.name
        card.cardThumb.setSchedule(item)
        card.activeTag.visibility = if (active) View.VISIBLE else View.GONE
        // 情侣课表：TA 看到的就是当前课表，并进同一个标签
        card.activeTag.setText(
            if (ApiClient.coupleState().bound) R.string.library_active_couple_tag else R.string.library_active_tag
        )
        card.cardMeta.text = getString(
            R.string.library_meta_format,
            courseCount,
            item.totalWeeks,
            DateUtils.relativeTime(item.updatedAt)
        )

        val shared = ApiClient.isShared(item.id)
        val pending = ApiClient.isSyncPending(item.id)
        val syncError = ApiClient.syncError(item.id)
        val gone = if (isOwner) null else ApiClient.remoteGone(item.id)
        val syncing = if (isOwner) shared && !pending else true
        card.syncText.text = if (gone != null) {
            getString(
                if (gone == ScheduleSyncStore.RemoteGone.DELETED) R.string.library_gone_deleted
                else R.string.library_gone_not_member
            )
        } else if (syncError != null) {
            getString(R.string.library_sync_failed)
        } else if (isOwner) {
            when {
                pending -> getString(R.string.library_sync_pending)
                !shared -> getString(R.string.library_sync_local)
                subscribers > 0 -> getString(R.string.library_sync_owner, subscribers)
                else -> getString(R.string.library_sync_none)
            }
        } else getString(R.string.library_sync_subscriber)
        card.syncDot.setBackgroundResource(
            when {
                gone != null || syncError != null -> R.drawable.sync_dot_error
                syncing -> R.drawable.sync_dot_green
                else -> R.drawable.sync_dot_gray
            }
        )
        if (gone != null) {
            card.syncText.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger))
            card.syncRow.setOnClickListener { showGoneDialog(item, gone) }
        } else if (syncError != null) {
            card.syncText.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger))
            card.syncRow.setOnClickListener {
                Ui.confirm(
                    requireContext(),
                    getString(R.string.sync_failed_title),
                    getString(R.string.sync_failed_detail, syncError),
                    confirmText = getString(R.string.sync_copy_log),
                    cancelText = getString(R.string.common_known)
                ) { SyncLogClipboard.copy(requireContext()) }
            }
        }

        // 状态行只有一行小字高：可点（失败 / 已失效）时把触摸区在文字列内上下撑到 48dp
        if (card.syncRow.hasOnClickListeners()) expandTouchArea(card.syncRow)
        card.cardMain.setOnClickListener { openSchedule(item.id) }
        card.cardMain.setOnLongClickListener { showActions(item, isOwner); true }
        card.actionMore.setOnClickListener { showActions(item, isOwner) }
        return card.root
    }

    /** 不改视图大小，只把 [view] 的可点范围在父容器内上下、向右扩到至少 48dp 见方。 */
    private fun expandTouchArea(view: View) {
        val parent = view.parent as? View ?: return
        parent.post {
            val min = (48 * resources.displayMetrics.density).toInt()
            val rect = Rect()
            view.getHitRect(rect)
            val extraY = ((min - rect.height()) / 2).coerceAtLeast(0)
            rect.top -= extraY
            rect.bottom += extraY
            rect.right += (min - rect.width()).coerceAtLeast(0)
            parent.touchDelegate = TouchDelegate(rect, view)
        }
    }

    /** 卡片的操作面板：发布者能重新导入、分享；所有人都能导出、删除（订阅者是移除）。 */
    private fun showActions(item: Schedule, isOwner: Boolean) {
        val actions = buildList {
            if (isOwner) add(ActionSheet.Item(getString(R.string.library_action_update), R.drawable.ic_menu_import, group = 0) {
                startActivity(Intent(requireContext(), ImportActivity::class.java).putExtra(ImportActivity.EXTRA_ID, item.id))
            })
            if (isOwner && !ApiClient.isLocalMode) add(ActionSheet.Item(getString(R.string.library_action_share), R.drawable.ic_menu_share, group = 1) {
                startActivity(Intent(requireContext(), ShareActivity::class.java).putExtra(ShareActivity.EXTRA_ID, item.id))
            })
            // 导出不分发布者、订阅者：订阅来的课表一样可以导出给自己用
            add(ActionSheet.Item(getString(R.string.library_action_export), R.drawable.ic_menu_export, group = 1) { exportFlow.show(item.id) })
            add(ActionSheet.Item(
                getString(if (isOwner) R.string.library_action_delete else R.string.library_action_leave),
                icon = R.drawable.ic_menu_delete,
                danger = true
            ) { confirmRemove(item, isOwner) })
        }
        ActionSheet(requireContext(), item.name, actions).show()
    }

    private fun openSchedule(id: String) {
        val switched = Prefs.activeScheduleId != id
        Prefs.activeScheduleId = id
        if (switched && ApiClient.coupleState().bound) {
            Ui.toast(requireContext(), getString(R.string.library_couple_switch_toast))
        }
        (activity as? MainActivity)?.openScheduleTab()
    }

    private fun confirmRemove(item: Schedule, isOwner: Boolean) {
        val title = getString(if (isOwner) R.string.library_delete_title else R.string.library_leave_title)
        val content = when {
            !isOwner -> getString(R.string.library_leave_content, item.name)
            item.subscriberCount > 0 -> getString(
                R.string.library_delete_content_shared, item.name, item.subscriberCount
            )
            else -> getString(R.string.library_delete_content, item.name)
        }
        val dangerColor = ContextCompat.getColor(requireContext(), R.color.danger_confirm)
        Ui.confirm(requireContext(), title, content, confirmColor = dangerColor) {
            performRemove(item, isOwner, getString(if (isOwner) R.string.library_deleted else R.string.library_left))
        }
    }

    /** 服务端已失效的订阅课表：说明原因，由用户决定是否移除（App 不替用户删）。 */
    private fun showGoneDialog(item: Schedule, gone: ScheduleSyncStore.RemoteGone) {
        val deleted = gone == ScheduleSyncStore.RemoteGone.DELETED
        Ui.confirm(
            requireContext(),
            getString(if (deleted) R.string.gone_deleted_title else R.string.gone_not_member_title),
            getString(if (deleted) R.string.gone_deleted_content else R.string.gone_not_member_content, item.name),
            confirmText = getString(R.string.gone_remove),
            cancelText = getString(R.string.gone_keep),
            confirmColor = ContextCompat.getColor(requireContext(), R.color.danger_confirm),
            onCancel = { ApiClient.acknowledgeGone(item.id) }
        ) { performRemove(item, isOwner = false, getString(R.string.gone_removed)) }
    }

    private fun performRemove(item: Schedule, isOwner: Boolean, successMessage: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (isOwner) ApiClient.deleteSchedule(item.id) else ApiClient.leaveSchedule(item.id)
                if (Prefs.activeScheduleId == item.id) Prefs.removeActiveSchedule()
                load()
                Ui.toastSuccess(requireContext(), successMessage)
            } catch (e: Exception) {
                SyncLog.log("移除课表失败", "local=${item.id} ${e.javaClass.name}: ${e.message}")
                Ui.toastError(requireContext(), e.message ?: getString(R.string.common_load_failed))
            }
        }
    }
}
