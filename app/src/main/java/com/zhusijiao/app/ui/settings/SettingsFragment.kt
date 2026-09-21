package com.zhusijiao.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.AppConfig
import com.zhusijiao.app.BuildConfig
import com.zhusijiao.app.MainActivity
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ApiClient
import com.zhusijiao.app.data.AppUpdater
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.data.UpdateCheck
import com.zhusijiao.app.databinding.FragmentSettingsBinding
import com.zhusijiao.app.domain.ServerAddress
import com.zhusijiao.app.domain.UpdateChannelOptions
import com.zhusijiao.app.ui.common.AppearanceSheet
import com.zhusijiao.app.ui.common.ChannelSheet
import com.zhusijiao.app.ui.common.Refreshable
import com.zhusijiao.app.ui.common.ServerSheet
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.launch
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase

/** 设置页。 */
class SettingsFragment : Fragment(), Refreshable {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.header.setTitle(getString(R.string.settings_title))
        bindStatus()
        bindAppearanceRow()
        bindUpdateSection()
        bindServerRow()
        bindChannelRow()
        binding.menuPrivacy.setOnClickListener {
            Ui.alert(requireContext(), getString(R.string.settings_privacy_title), getString(R.string.settings_privacy_content), getString(R.string.common_i_see))
        }
        binding.menuAbout.setOnClickListener {
            Ui.alert(requireContext(), getString(R.string.settings_about_title), getString(R.string.settings_about_content))
        }
        binding.menuQqGroup.setOnClickListener { copyQqGroupNumber() }
        binding.menuOpenSource.setOnClickListener { openProjectRepo() }
        binding.menuDelete.setOnClickListener {
            val danger = ContextCompat.getColor(requireContext(), R.color.danger_confirm_alt)
            Ui.confirm(
                requireContext(),
                getString(R.string.settings_delete_title),
                getString(
                    if (ApiClient.isLocalMode) R.string.settings_delete_content_local
                    else R.string.settings_delete_content
                ),
                confirmText = getString(R.string.settings_delete_confirm),
                confirmColor = danger
            ) {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        ApiClient.deleteAccount()
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        WebStorage.getInstance().deleteAllData()
                        WebViewDatabase.getInstance(requireContext()).apply {
                            clearFormData()
                            clearHttpAuthUsernamePassword()
                        }
                        Ui.toast(requireContext(), getString(R.string.settings_delete_done))
                        (activity as? MainActivity)?.openLibraryTab()
                    } catch (e: Exception) {
                        Ui.toast(requireContext(), e.message ?: getString(R.string.common_load_failed))
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun refresh() {
        if (_binding != null) {
            bindStatus()
            bindAppearanceRow()
            bindServerRow()
            bindChannelRow()
        }
    }

    /** 「课表外观」行：副标题摘要当前档位，点击切到课表页并弹出外观面板。 */
    private fun bindAppearanceRow() {
        binding.appearanceSub.text = AppearanceSheet.summary(requireContext(), Prefs.timetableAppearance)
        binding.menuAppearance.setOnClickListener {
            (activity as? MainActivity)?.openTimetableAppearance()
        }
    }

    /** 「服务器地址」行：副标题显示当前生效地址，点击打开更换面板。 */
    private fun bindServerRow() {
        val override = Prefs.serverBaseUrlOverride
        binding.serverSub.text = if (override.isBlank()) {
            val defaultLabel = ServerAddress.labelOf(AppConfig.API_BASE_URL)
            if (defaultLabel.isBlank()) {
                getString(R.string.settings_server_local_default)
            } else {
                getString(R.string.settings_server_sub_default, defaultLabel)
            }
        } else {
            getString(R.string.settings_server_sub_custom, ServerAddress.labelOf(override.trimEnd('/')))
        }
        binding.menuServer.setOnClickListener {
            ServerSheet(requireContext()) {
                bindStatus()
                bindServerRow()
            }.show()
        }
    }

    /** 「更新通道」行：副标题显示当前通道名，点击打开通道面板。 */
    private fun bindChannelRow() {
        val id = Prefs.updateChannelId
        val label = UpdateChannelOptions.builtIns(AppConfig.apiBase)
            .plus(Prefs.customUpdateChannels)
            .firstOrNull { it.id == id }?.label
            ?: getString(R.string.channel_stable)
        binding.updateChannelSub.text = getString(R.string.settings_channel_sub, label)
        binding.updateChannelRow.setOnClickListener {
            ChannelSheet(requireContext()) { bindChannelRow() }.show()
        }
    }

    private fun bindUpdateSection() {
        binding.updateCurrentVersion.text = getString(R.string.settings_update_current, BuildConfig.VERSION_NAME)
        renderUpdateToggle()
        binding.updateAutoRow.setOnClickListener {
            Prefs.autoUpdateCheck = !Prefs.autoUpdateCheck
            renderUpdateToggle()
        }
        binding.updateCheckRow.setOnClickListener { checkUpdateManually() }
    }

    private fun renderUpdateToggle() {
        binding.updateAutoPill.setText(
            if (Prefs.autoUpdateCheck) R.string.settings_update_on else R.string.settings_update_off
        )
    }

    /** 手动检查更新：有更新弹更新框，否则轻提示。 */
    private fun checkUpdateManually() {
        if (ApiClient.isLocalMode) {
            Ui.alert(
                requireContext(),
                getString(R.string.import_helper_unavailable_title),
                getString(R.string.local_sharing_unavailable)
            )
            return
        }
        Ui.toast(requireContext(), getString(R.string.update_checking))
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = AppUpdater.fetch()) {
                is UpdateCheck.Found ->
                    if (AppUpdater.isNewer(result.release)) {
                        (activity as? MainActivity)?.showUpdateDialog(result.release, recordDismiss = false)
                    } else {
                        Ui.toast(requireContext(), getString(R.string.update_latest_already))
                    }
                UpdateCheck.NoRelease -> Ui.toast(requireContext(), getString(R.string.update_latest_already))
                UpdateCheck.Failed -> Ui.toast(requireContext(), getString(R.string.update_check_failed))
            }
        }
    }

    /** 复制 QQ 交流群号到剪贴板并轻提示。 */
    private fun copyQqGroupNumber() {
        val groupNumber = getString(R.string.settings_qq_group_number)
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("QQ群号", groupNumber))
        Ui.toast(requireContext(), getString(R.string.settings_qq_group_copied, groupNumber))
    }

    /** 用系统浏览器打开开源项目仓库主页。 */
    private fun openProjectRepo() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.settings_open_source_url)))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Ui.toast(requireContext(), getString(R.string.settings_open_source_failed))
        }
    }

    private fun bindStatus() {
        binding.apiHost.text = AppConfig.apiHostLabel
        if (ApiClient.isLocalMode) {
            binding.statusPill.text = getString(R.string.settings_status_local_mode)
            return
        }
        binding.statusPill.text = getString(R.string.settings_status_checking)
        viewLifecycleOwner.lifecycleScope.launch {
            val online = ApiClient.healthCheck()
            if (_binding != null) binding.statusPill.text = getString(
                if (online) R.string.settings_status_online else R.string.settings_status_unavailable
            )
        }
    }
}
