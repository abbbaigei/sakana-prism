package dev.sakana.spw.prism.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import java.util.prefs.Preferences

/** Light or dark, as the host is currently rendering. */
enum class HostAppearance { LIGHT, DARK }

/**
 * Semantic colours. Components never hard-code hex values; they read these tokens.
 *
 * The page itself is transparent: the plugin is mounted inside the host window, so the
 * host's mica / acrylic / wallpaper shows through and every card is a translucent tint on
 * top of it. That is also why only two sets exist (light / dark) — the four materials differ
 * in what is *behind* the tokens, not in the tokens themselves.
 */
@Immutable
data class HostTokens(
    val dark: Boolean,
    /** Card / grouped content surface: a translucent tint over the host material. */
    val card: Color,
    val cardHover: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineStrong: Color,
    val accent: Color,
    val onAccent: Color,
    /** Chart backdrop: deliberately opaque so the visualisers never shift with the wallpaper. */
    val chart: Color,
    val leftChannel: Color,
    val rightChannel: Color,
)

val LocalHostTokens = staticCompositionLocalOf { HostTheme.tokens(HostAppearance.DARK) }

object HostTheme {

    private const val PREFS_NODE = "/com/xuncorp/voxzen"
    private const val PERSONALIZE = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"

    /** Host accent when it follows the Windows blue family (measured from the screenshots). */
    private val ACCENT_DARK = Color(0xFF4CC2FF)
    private val ACCENT_LIGHT = Color(0xFF0E6ABA)

    /**
     * Resolves the appearance the host is using.
     *
     * `light_dark_theme` is stored in the host's preference node: 0 follow system,
     * 1 light, 2 dark (matches the order of the 跟随系统 / 浅色 / 深色 options). The mapping
     * for non-zero values is inferred, not confirmed — if a user reports an inverted theme we
     * only have to flip one `when` branch here.
     */
    fun detect(): HostAppearance {
        val stored = runCatching {
            Preferences.userRoot().node(PREFS_NODE).get("light_dark_theme", "0")
        }.getOrNull()
        return when (stored) {
            "1" -> HostAppearance.LIGHT
            "2" -> HostAppearance.DARK
            else -> if (systemIsLight()) HostAppearance.LIGHT else HostAppearance.DARK
        }
    }

    /** `AppsUseLightTheme` in HKCU; JNA ships inside the host API jar. */
    fun systemIsLight(): Boolean = runCatching {
        val value = com.sun.jna.platform.win32.Advapi32Util.registryGetIntValue(
            com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
            PERSONALIZE,
            "AppsUseLightTheme",
        )
        value != 0
    }.getOrDefault(false)

    fun tokens(appearance: HostAppearance, accentOverride: Color? = null): HostTokens =
        if (appearance == HostAppearance.LIGHT) light(accentOverride) else dark(accentOverride)

    private fun dark(accentOverride: Color?) = HostTokens(
        dark = true,
        // SaltUI dark: subBackground #08FFFFFF, text #EBEEF1, subText #BFE1E6EB,
        // stroke = subText @10%. Highlight comes from what the host actually renders.
        card = Color(0x0DFFFFFF),
        cardHover = Color(0x1AFFFFFF),
        onSurface = Color(0xFFEBEEF1),
        onSurfaceVariant = Color(0xBFE1E6EB),
        outline = Color(0x1AE1E6EB),
        outlineStrong = Color(0x8CE1E6EB),
        accent = accentOverride ?: ACCENT_DARK,
        onAccent = Color(0xFF06222E),
        chart = Color(0xE60B0F15),
        leftChannel = Color(0xFFFF6B81),
        rightChannel = Color(0xFF4DD8C4),
    )

    private fun light(accentOverride: Color?) = HostTokens(
        dark = false,
        // SaltUI light: subBackground #80FFFFFF, text #1E1715, subText #8C8C8C,
        // stroke = subText @15%.
        card = Color(0x80FFFFFF),
        cardHover = Color(0xB3FFFFFF),
        onSurface = Color(0xFF1E1715),
        onSurfaceVariant = Color(0xFF8C8C8C),
        outline = Color(0x268C8C8C),
        outlineStrong = Color(0x668C8C8C),
        accent = accentOverride ?: ACCENT_LIGHT,
        onAccent = Color(0xFFFFFFFF),
        chart = Color(0xF20B0F15),
        leftChannel = Color(0xFFFF6B81),
        rightChannel = Color(0xFF4DD8C4),
    )
}
