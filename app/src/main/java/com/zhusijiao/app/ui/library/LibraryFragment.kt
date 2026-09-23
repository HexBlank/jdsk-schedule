package com.zhusijiao.app.ui.library

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.MainActivity
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ApiClient
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.databinding.FragmentLibraryBinding
import com.zhusijiao.app.databinding.ItemScheduleCardBinding
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.Schedule
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.header.setTitle(getString(R.string.library_title))
        binding.importBtn.setOnClickListener { startActivity(Intent(requireContext(), ImportActivity::class.java)) }
        binding.joinBtn.setOnClickListener {
            if (ApiClient.isLocalMode) showSharingUnavailable()
            else startActivity(Intent(requireContext(), JoinActivity::class.java))
        }
        if (ApiClient.isLocalMode) binding.joinBtn.text = getString(R.string.library_join_offline)
    }

    override fun onDestroyView() {
        super.onDestroyView()
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
                if (schedules.isEmpty()) {
                    binding.emptyState.visibility = View.VISIBLE
                } else {
                    schedules.forEach { binding.cardList.addView(buildCard(it, it.id == activeId)) }
                }
                if (syncRemote && !ApiClient.isLocalMode && !syncRunning) {
                    syncRunning = true
                    val synced = ApiClient.syncSchedules()
                    syncRunning = false
                    if (_binding == null) return@launch
                    ApiClient.takeNewSyncError()?.let { Ui.toast(requireContext(), it) }
                    // 失败时也重绘：卡片上的同步状态可能变成「同步失败」
                    load(syncRemote = false)
                }
            } catch (e: Exception) {
                binding.skeletonList.visibility = View.GONE
                Ui.toast(requireContext(), e.message ?: getString(R.string.common_load_failed))
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
        card.activeTag.visibility = if (active) View.VISIBLE else View.GONE
        card.cardMeta.text = getString(
            R.string.library_meta_format,
            getString(if (isOwner) R.string.library_meta_owner else R.string.library_meta_sync),
            courseCount,
            item.totalWeeks
        )
        card.cardSub.text = getString(R.string.library_version_format, item.revision, DateUtils.relativeTime(item.updatedAt))

        val shared = ApiClient.isShared(item.id)
        val pending = ApiClient.isSyncPending(item.id)
        val syncError = ApiClient.syncError(item.id)
        val syncing = if (isOwner) shared && !pending else true
        card.syncText.text = if (syncError != null) {
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
                syncError != null -> R.drawable.sync_dot_error
                syncing -> R.drawable.sync_dot_green
                else -> R.drawable.sync_dot_gray
            }
        )
        if (syncError != null) {
            card.syncText.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger))
            card.syncText.setOnClickListener {
                Ui.confirm(
                    requireContext(),
                    getString(R.string.sync_failed_title),
                    getString(R.string.sync_failed_detail, syncError),
                    confirmText = getString(R.string.sync_copy_log),
                    cancelText = getString(R.string.common_known)
                ) { SyncLogClipboard.copy(requireContext()) }
            }
        }

        card.actionUpdate.visibility = if (isOwner) View.VISIBLE else View.GONE
        card.actionShare.visibility = if (isOwner && !ApiClient.isLocalMode) View.VISIBLE else View.GONE
        card.actionRemove.text = getString(if (isOwner) R.string.library_action_delete else R.string.library_action_leave)

        card.cardMain.setOnClickListener { openSchedule(item.id) }
        card.actionUpdate.setOnClickListener {
            startActivity(Intent(requireContext(), ImportActivity::class.java).putExtra(ImportActivity.EXTRA_ID, item.id))
        }
        card.actionShare.setOnClickListener {
            startActivity(Intent(requireContext(), ShareActivity::class.java).putExtra(ShareActivity.EXTRA_ID, item.id))
        }
        card.actionRemove.setOnClickListener { confirmRemove(item, isOwner) }
        return card.root
    }

    private fun openSchedule(id: String) {
        Prefs.activeScheduleId = id
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
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    if (isOwner) ApiClient.deleteSchedule(item.id) else ApiClient.leaveSchedule(item.id)
                    if (Prefs.activeScheduleId == item.id) Prefs.removeActiveSchedule()
                    load()
                    Ui.toast(requireContext(), getString(if (isOwner) R.string.library_deleted else R.string.library_left))
                } catch (e: Exception) {
                    Ui.toast(requireContext(), e.message ?: getString(R.string.common_load_failed))
                }
            }
        }
    }

    private fun showSharingUnavailable() = Ui.alert(
        requireContext(),
        getString(R.string.import_helper_unavailable_title),
        getString(R.string.local_sharing_unavailable)
    )
}
