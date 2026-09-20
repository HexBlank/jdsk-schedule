package com.zhusijiao.app.domain

import java.security.MessageDigest
import java.text.Collator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 课程配色、单双周标签与周次摘要。 */
object ScheduleView {

    data class Palette(val background: Int, val foreground: Int)

    private const val WHITE = 0xffffffff.toInt()
    private const val BLACK = 0xff202725.toInt()

    /**
     * 课程配色在同一份课表内保持确定性，输入顺序不影响结果。
     * 20 色同时拉开色相和明度：深色使用白字、浅色使用墨色字。
     * 默认分配会在当前课表中逐门选择与已用颜色距离最远的候选色，避免随机哈希让相邻色同时出现。
     */
    val COURSE_PALETTES: List<Palette> = listOf(
        palette("#B13F5C"), // 莓红
        palette("#247A70"), // 松石
        palette("#7055B5"), // 鸢尾紫
        palette("#607C25"), // 苔绿
        palette("#AC6118"), // 琥珀
        palette("#9A3F91"), // 品红
        palette("#3573BD"), // 湖蓝
        palette("#A84432"), // 砖红
        palette("#28723F"), // 森林绿
        palette("#66506F"), // 灰紫
        palette("#E3A2B4"), // 雾粉
        palette("#86C8BC"), // 薄荷
        palette("#B7A7DF"), // 浅紫
        palette("#B8CB72"), // 青柠
        palette("#EEB36A"), // 杏橙
        palette("#D995D1"), // 兰花粉
        palette("#8CB7E7"), // 晴蓝
        palette("#E39783"), // 珊瑚
        palette("#83C696"), // 嫩绿
        palette("#D6BC6A")  // 麦黄
    )

    /** 稳定调色板的常规色数（哈希取模的模数，固定不变以保证颜色稳定）。 */
    private const val STABLE_COLOR_COUNT = 20

    /** 白字最小对比度（WCAG AA），生成色与精调色共用同一标准。 */
    private const val MIN_TEXT_CONTRAST = 4.5f
    private const val MAX_GENERATION_ATTEMPTS = 48
    private val HEX_COLOR = Regex("^#[0-9A-F]{6}$")

    fun normalizedWeeks(weeks: List<Int>?): List<Int> =
        (weeks ?: emptyList()).filter { it > 0 }.distinct().sorted()

    /** 若为严格单/双周（同奇偶且步长为 2），返回「单周」「双周」，否则空串。 */
    fun alternatingWeekBadge(weeks: List<Int>?): String {
        val values = normalizedWeeks(weeks)
        if (values.size < 2) return ""
        val sameParity = values.all { it % 2 == values[0] % 2 }
        val continuous = values.drop(1).withIndex().all { (i, w) -> w - values[i] == 2 }
        if (!sameParity || !continuous) return ""
        return if (values[0] % 2 == 0) "双周" else "单周"
    }

    /** 把周次列表压成连续区间字符串，如 [1,2,3,5] -> ["1–3","5"]。 */
    fun consecutiveRanges(weeks: List<Int>?): List<String> {
        val values = normalizedWeeks(weeks)
        if (values.isEmpty()) return emptyList()
        val ranges = mutableListOf<String>()
        var start = values[0]
        var previous = values[0]
        values.drop(1).forEach { week ->
            if (week == previous + 1) {
                previous = week
                return@forEach
            }
            ranges += if (start == previous) "$start" else "$start–$previous"
            start = week
            previous = week
        }
        ranges += if (start == previous) "$start" else "$start–$previous"
        return ranges
    }

    fun formatWeekSummary(weeks: List<Int>?): String {
        val values = normalizedWeeks(weeks)
        if (values.isEmpty()) return "周次待定"
        val badge = alternatingWeekBadge(values)
        if (badge.isNotEmpty()) return "第 ${values.first()}–${values.last()} 周 · $badge"
        return "第 ${consecutiveRanges(values).joinToString("、")} 周"
    }

    /**
     * 课程名排序与哈希共同决定颜色，输入顺序不影响结果。
     * 每次分配都优先选择与当前已用色距离最远的颜色；课程少时尤其容易一眼分清。
     * manualColors 里的手动颜色（长按课程选的）优先于自动配色。
     */
    fun buildCoursePaletteMap(
        courses: List<Course>?,
        manualColors: Map<String, String> = emptyMap()
    ): Map<String, Palette> {
        val collator = Collator.getInstance(Locale.CHINA)
        val names = (courses ?: emptyList())
            .map { it.name }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(collator)
        val automatic = linkedMapOf<String, Palette>()
        val used = mutableListOf<Palette>()

        // 自动配色只由课程集合决定；手动修改任何一门课都不能触发其它课程重新分配。
        names.forEach { name ->
            val home = stableColorIndex(name)
            val usedBackgrounds = used.mapTo(mutableSetOf()) { it.background }
            val candidates = COURSE_PALETTES.indices.filter { index ->
                COURSE_PALETTES[index].background !in usedBackgrounds
            }.ifEmpty {
                val index = COURSE_PALETTES.size + used.size
                listOf(index)
            }
            val chosenIndex = candidates.maxWithOrNull(
                compareBy<Int> { index ->
                    if (used.isEmpty()) 0f
                    else used.minOf { colorDistance(paletteForIndex(index).background, it.background) }
                }.thenBy { index -> -circularIndexDistance(index, home, COURSE_PALETTES.size) }
            ) ?: home
            val chosen = paletteForIndex(chosenIndex)
            automatic[name] = chosen
            used += chosen
        }
        return automatic.mapValues { (name, palette) ->
            manualPalette(manualColors[name]) ?: palette
        }
    }

    /** 手动指定的课程色（#RRGGBB）；格式不合法时返回 null，回退自动配色。 */
    fun manualPalette(hex: String?): Palette? {
        val value = hex?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (!HEX_COLOR.matches(value)) return null
        return palette(value)
    }

    /** 稳定色板的全部候选色（手动选色面板展示用）。 */
    fun stablePaletteColors(): List<Palette> = COURSE_PALETTES

    /** 课程名的稳定色格（0..STABLE_COLOR_COUNT-1）：SHA-256 前 8 字节大端取模，跨进程/跨端一致。 */
    fun stableColorIndex(name: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(name.trim().toByteArray(Charsets.UTF_8))
        var acc = 0L
        for (i in 0 until 8) acc = (acc shl 8) or (digest[i].toLong() and 0xff)
        return ((acc % STABLE_COLOR_COUNT + STABLE_COLOR_COUNT) % STABLE_COLOR_COUNT).toInt()
    }

    /** 第 index 个稳定色：前 20 格为固定色板，之后继续按色相与明暗双层扩展。 */
    fun paletteForIndex(index: Int): Palette {
        val palette = COURSE_PALETTES
        if (index < palette.size) return palette[index]
        val hue = generatedHue(index)
        return paletteForHue(hue, light = index % 2 == 1)
    }

    /** 给定色相生成可读课程色；浅色层使用墨色字，深色层使用白字。 */
    private fun paletteForHue(hue: Float, light: Boolean = false): Palette {
        var saturation = if (light) 0.42f else 0.62f
        var value = if (light) 0.84f else 0.70f
        var background = hsvToColor(hue, saturation, value)
        var attempts = 0
        var foreground = readableForeground(background)
        while (contrastRatio(background, foreground) < MIN_TEXT_CONTRAST && attempts < MAX_GENERATION_ATTEMPTS) {
            value = if (foreground == WHITE) max(0.14f, value - 0.018f) else min(0.96f, value + 0.012f)
            saturation = min(1f, saturation + 0.008f)
            background = hsvToColor(hue, saturation, value)
            foreground = readableForeground(background)
            attempts++
        }
        return Palette(background, foreground)
    }

    /** 精调色相之外第 step 个补充色相（step 从 0 开始）：每次取色环上与所有已用色相的最小圆周距离最大的点。 */
    fun generatedHue(step: Int): Float {
        require(step >= 0) { "step must be >= 0" }
        val used = COURSE_PALETTES.map { hueOf(it.background) }.distinct().toMutableList()
        // 即便 step=0 也要取一个新点：返回第 step+1 次「最大间隔取点」的结果
        repeat(step + 1) { used += furthestHueOnCircle(used) }
        return used.last()
    }

    private fun furthestHueOnCircle(used: List<Float>): Float {
        val sorted = used.map { normalizeHue(it) }.sorted()
        var bestStart = sorted.last()
        var bestGap = sorted.first() + 360f - bestStart
        for (i in 0 until sorted.lastIndex) {
            val gap = sorted[i + 1] - sorted[i]
            if (gap > bestGap) {
                bestGap = gap
                bestStart = sorted[i]
            }
        }
        return normalizeHue(bestStart + bestGap / 2f)
    }

    private fun normalizeHue(hue: Float): Float {
        val h = hue % 360f
        return if (h < 0f) h + 360f else h
    }

    private fun palette(hex: String): Palette {
        val background = parseColor(hex)
        return Palette(background, readableForeground(background))
    }

    private fun readableForeground(background: Int): Int =
        if (contrastRatio(background, WHITE) >= contrastRatio(background, BLACK)) WHITE else BLACK

    private fun circularIndexDistance(a: Int, b: Int, size: Int): Int {
        if (a >= size) return size
        val distance = abs(a - b)
        return min(distance, size - distance)
    }

    /** 近似感知色差：同时考虑人眼对 RGB 通道的不同敏感度。 */
    fun colorDistance(a: Int, b: Int): Float {
        val redMean = ((a shr 16 and 0xff) + (b shr 16 and 0xff)) / 2f
        val red = (a shr 16 and 0xff) - (b shr 16 and 0xff)
        val green = (a shr 8 and 0xff) - (b shr 8 and 0xff)
        val blue = (a and 0xff) - (b and 0xff)
        return kotlin.math.sqrt(
            (2f + redMean / 256f) * red * red +
                4f * green * green +
                (2f + (255f - redMean) / 256f) * blue * blue
        )
    }

    /** 解析 #RRGGBB / #AARRGGBB 为 ARGB int；不依赖 android.graphics，JVM 单元测试可直接运行。 */
    private fun parseColor(hex: String): Int {
        val value = hex.removePrefix("#")
        return when (value.length) {
            6 -> (0xff shl 24) or value.toLong(16).toInt()
            8 -> value.toLong(16).toInt()
            else -> throw IllegalArgumentException("Unknown color: $hex")
        }
    }

    // ==== 纯 Kotlin 色彩换算（HSV/对比度），不依赖 android.graphics 原生方法，JVM 单元测试可直接验证 ====

    /** 颜色的 HSV 色相（0–360）。 */
    fun hueOf(color: Int): Float {
        val r = channel(color, 16)
        val g = channel(color, 8)
        val b = channel(color, 0)
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        if (maxC == minC) return 0f
        val delta = maxC - minC
        val hue = when (maxC) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return normalizeHue(hue)
    }

    private fun hsvToColor(hue: Float, saturation: Float, value: Float): Int {
        val h = normalizeHue(hue) / 60f
        val c = value * saturation
        val x = c * (1f - abs(h % 2f - 1f))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = value - c
        fun channelByte(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (channelByte(r) shl 16) or (channelByte(g) shl 8) or channelByte(b)
    }

    /** WCAG 对比度（约 1–21），用于白字可读性校验。 */
    fun contrastRatio(a: Int, b: Int): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private fun relativeLuminance(color: Int): Float = 0.2126f * linearChannel(channel(color, 16)) +
        0.7152f * linearChannel(channel(color, 8)) +
        0.0722f * linearChannel(channel(color, 0))

    private fun linearChannel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    private fun channel(color: Int, shift: Int): Float = (color shr shift and 0xff) / 255f
}
