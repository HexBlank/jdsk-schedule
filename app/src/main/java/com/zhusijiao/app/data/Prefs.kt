package com.zhusijiao.app.data

import android.content.Context
import android.content.SharedPreferences
import com.zhusijiao.app.MainApplication
import com.zhusijiao.app.domain.TimetableAppearance
import com.zhusijiao.app.domain.UpdateChannelOption
import com.zhusijiao.app.domain.UpdateChannelOptions
import com.zhusijiao.app.domain.WeekendDisplayMode
import java.util.UUID

/**
 * 本地存储：基于 SharedPreferences 的键值存取。
 * 保存：登录 JWT、当前课表 id、匿名设备 id、本机课表数据。
 */
object Prefs {

    private const val FILE = "zhusijiao_prefs"
    private const val KEY_TOKEN = "zhusijiaoToken"
    private const val KEY_ACTIVE = "activeScheduleId"
    private const val KEY_DEVICE = "deviceId"
    private const val KEY_LOCAL_SCHEDULES = "localSchedulesV1"
    private const val KEY_LEGACY_DEMO_STATE = "zhusijiaoMockStateV1"
    private const val KEY_AUTO_UPDATE = "autoUpdateCheck"
    private const val KEY_DISMISSED_UPDATE = "dismissedUpdateCode"
    private const val KEY_SHOW_WEEKEND = "showWeekend"
    private const val KEY_WEEKEND_DISPLAY_MODE = "weekendDisplayMode"
    private const val KEY_CUSTOM_COURSE_COLORS = "customCourseColors"
    private const val KEY_SERVER_OVERRIDE = "serverBaseUrlOverride"
    private const val KEY_UPDATE_CHANNEL = "updateChannelId"
    private const val KEY_TT_ROW_HEIGHT = "timetableRowHeightLevel"
    private const val KEY_TT_PADDING = "timetablePaddingLevel"
    private const val KEY_TT_TEXT = "timetableTextLevel"
    private const val KEY_TT_FIT_SCREEN = "timetableFitScreen"
    private const val KEY_TT_SHOW_FINISHED = "timetableShowFinished"
    private const val KEY_CUSTOM_CHANNELS = "customUpdateChannels"
    private val HEX_COLOR = Regex("^#[0-9A-F]{6}$")

    private val sp: SharedPreferences by lazy {
        MainApplication.appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var token: String?
        get() = sp.getString(KEY_TOKEN, null)
        set(value) = sp.edit().putString(KEY_TOKEN, value).apply()

    var activeScheduleId: String
        get() = sp.getString(KEY_ACTIVE, "") ?: ""
        set(value) = sp.edit().putString(KEY_ACTIVE, value).apply()

    /** 稳定的匿名设备标识：首次启动生成并持久化，用于向后端换取 JWT。 */
    val deviceId: String
        get() {
            val existing = sp.getString(KEY_DEVICE, null)
            if (!existing.isNullOrBlank()) return existing
            val id = UUID.randomUUID().toString()
            sp.edit().putString(KEY_DEVICE, id).apply()
            return id
        }

    /** 打开时是否自动检查新版本（默认开启）。 */
    var autoUpdateCheck: Boolean
        get() = sp.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    /** 用户点「下次再说」时记录的版本号：同一版本只自动提醒一次。 */
    var dismissedUpdateCode: Int
        get() = sp.getInt(KEY_DISMISSED_UPDATE, 0)
        set(value) = sp.edit().putInt(KEY_DISMISSED_UPDATE, value).apply()

    /**
     * 周末列显示方式（默认 AUTO，即「当周周六日有课时才显示」开启）。
     * 迁移：旧版只有「始终显示」布尔值，开启者迁移为 ALWAYS；1.2.18 及更早可能存过
     * WEEKDAYS_ONLY（两开关全关），该模式会让用户漏看周末课程，已废弃，统一按 AUTO 处理。
     */
    var weekendDisplayMode: WeekendDisplayMode
        get() {
            val stored = sp.getString(KEY_WEEKEND_DISPLAY_MODE, null)
            if (stored != null) {
                return runCatching { WeekendDisplayMode.valueOf(stored) }.getOrDefault(WeekendDisplayMode.AUTO)
            }
            return if (sp.getBoolean(KEY_SHOW_WEEKEND, false)) {
                WeekendDisplayMode.ALWAYS
            } else {
                WeekendDisplayMode.AUTO
            }
        }
        set(value) {
            sp.edit()
                .putString(KEY_WEEKEND_DISPLAY_MODE, value.name)
                .remove(KEY_SHOW_WEEKEND)
                .apply()
        }

    /**
     * 课表外观（格子高度/留白/文字大小、铺满一屏、显示已上状态）。
     * 纯本机显示偏好，不随课表同步：同一份课表各人可以各调各的。
     */
    var timetableAppearance: TimetableAppearance
        get() = TimetableAppearance(
            rowHeightLevel = sp.getInt(KEY_TT_ROW_HEIGHT, TimetableAppearance.DEFAULT_ROW_HEIGHT_LEVEL)
                .coerceIn(TimetableAppearance.ROW_HEIGHTS.indices),
            paddingLevel = sp.getInt(KEY_TT_PADDING, TimetableAppearance.DEFAULT_PADDING_LEVEL)
                .coerceIn(TimetableAppearance.BLOCK_MARGINS.indices),
            textLevel = sp.getInt(KEY_TT_TEXT, TimetableAppearance.DEFAULT_TEXT_LEVEL)
                .coerceIn(TimetableAppearance.TEXT_SCALES.indices),
            fitScreen = sp.getBoolean(KEY_TT_FIT_SCREEN, false),
            showFinished = sp.getBoolean(KEY_TT_SHOW_FINISHED, false)
        )
        set(value) {
            sp.edit()
                .putInt(KEY_TT_ROW_HEIGHT, value.rowHeightLevel)
                .putInt(KEY_TT_PADDING, value.paddingLevel)
                .putInt(KEY_TT_TEXT, value.textLevel)
                .putBoolean(KEY_TT_FIT_SCREEN, value.fitScreen)
                .putBoolean(KEY_TT_SHOW_FINISHED, value.showFinished)
                .apply()
        }

    /** 用户自己保存的课程颜色，仅保存在本机；实际选给课程的颜色仍随课表同步。 */
    var customCourseColors: List<String>
        get() = (sp.getString(KEY_CUSTOM_COURSE_COLORS, "") ?: "")
            .split('|')
            .map { it.trim().uppercase() }
            .filter(HEX_COLOR::matches)
            .distinct()
            .take(MAX_CUSTOM_COLORS)
        set(value) {
            val normalized = value
                .map { it.trim().uppercase() }
                .filter(HEX_COLOR::matches)
                .distinct()
                .takeLast(MAX_CUSTOM_COLORS)
            sp.edit().putString(KEY_CUSTOM_COURSE_COLORS, normalized.joinToString("|")).apply()
        }

    fun saveCustomCourseColor(hex: String) {
        val value = hex.trim().uppercase()
        if (!HEX_COLOR.matches(value)) return
        customCourseColors = customCourseColors.filterNot { it == value } + value
    }

    fun removeCustomCourseColor(hex: String) {
        customCourseColors = customCourseColors.filterNot { it.equals(hex, ignoreCase = true) }
    }

    /** 用户自定义的数据服务器地址（空 = 使用构建时内置默认）。仅本机联调地址允许 http。 */
    var serverBaseUrlOverride: String
        get() = (sp.getString(KEY_SERVER_OVERRIDE, "") ?: "").trim()
        set(value) = sp.edit().putString(KEY_SERVER_OVERRIDE, value.trim()).apply()

    /** 当前更新通道：stable / beta / 自定义通道 id。 */
    var updateChannelId: String
        get() = (sp.getString(KEY_UPDATE_CHANNEL, null) ?: UpdateChannelOptions.STABLE_ID).trim()
            .ifBlank { UpdateChannelOptions.STABLE_ID }
        set(value) = sp.edit().putString(KEY_UPDATE_CHANNEL, value.trim()).apply()

    /** 用户自建的更新通道（最多 [UpdateChannelOptions.MAX_CUSTOM] 个），随安装保留。 */
    var customUpdateChannels: List<UpdateChannelOption>
        get() = UpdateChannelOptions.parseCustom(sp.getString(KEY_CUSTOM_CHANNELS, "") ?: "")
        set(value) = sp.edit().putString(KEY_CUSTOM_CHANNELS, UpdateChannelOptions.toText(value)).apply()

    /** 新增自定义更新通道；地址已存在时直接返回已有通道，数量已满时返回 null。 */
    fun addCustomUpdateChannel(label: String, manifestUrl: String): UpdateChannelOption? {
        val cleanLabel = label.trim().take(20)
        val url = manifestUrl.trim()
        if (cleanLabel.isEmpty() || url.isEmpty()) return null
        val existing = customUpdateChannels
        existing.firstOrNull { it.manifestUrl == url }?.let { return it }
        if (existing.size >= UpdateChannelOptions.MAX_CUSTOM) return null
        val option = UpdateChannelOption("c-" + UUID.randomUUID().toString().substring(0, 8), cleanLabel, url)
        customUpdateChannels = existing + option
        return option
    }

    fun removeCustomUpdateChannel(id: String) {
        customUpdateChannels = customUpdateChannels.filterNot { it.id == id }
    }

    var localSchedulesJson: String?
        get() = sp.getString(KEY_LOCAL_SCHEDULES, null)
        set(value) = sp.edit().putString(KEY_LOCAL_SCHEDULES, value).apply()

    fun removeToken() = sp.edit().remove(KEY_TOKEN).apply()

    fun removeActiveSchedule() = sp.edit().remove(KEY_ACTIVE).apply()

    fun removeLocalSchedules() = sp.edit().remove(KEY_LOCAL_SCHEDULES).apply()

    /** 删除账号/全部数据时连同长期设备凭据一起清除，下次启动生成全新身份。 */
    fun clearAllIdentityAndData() = sp.edit().clear().commit()

    /** 升级安装时清掉旧演示模式状态及其当前课表指针；不会触碰新版本机课表。 */
    fun removeLegacyDemoData() {
        if (sp.contains(KEY_LEGACY_DEMO_STATE)) {
            sp.edit()
                .remove(KEY_LEGACY_DEMO_STATE)
                .remove(KEY_ACTIVE)
                .apply()
        }
    }

    private const val MAX_CUSTOM_COLORS = 12
}
