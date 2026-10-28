@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.sakana.spw.prism.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.awt.GraphicsEnvironment

/**
 * Typography shared by every screen.
 *
 * The host renders its UI with the Windows UI font; we resolve the same family explicitly
 * (first installed name wins) instead of trusting the framework default, and Skia's own
 * fallback still covers CJK glyphs missing from the Latin face.
 *
 * Four levels only: title / section / body / caption. Nothing else may invent a size.
 */
object Type {

    /** Candidate list, most specific first; the first installed family is used. */
    private val CANDIDATES = listOf(
        "Segoe UI Variable Text",
        "Segoe UI Variable",
        "Segoe UI",
        "Microsoft YaHei UI",
        "Microsoft YaHei",
    )

    val family: FontFamily by lazy {
        val installed = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        }.getOrDefault(emptySet())
        val picked = CANDIDATES.firstOrNull { it in installed }
        if (picked != null) FontFamily(picked) else FontFamily.Default
    }

    /** 24sp SemiBold — SaltUI's largeTitle, used for the page title. */
    val title = TextStyle(
        fontFamily = family,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    )

    /** 14/20 Regular — SaltUI's `main` style, used for group headers. */
    val section = TextStyle(
        fontFamily = family,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )

    /** 14/20 Regular — control labels and values. */
    val body = TextStyle(
        fontFamily = family,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )

    /** 12/16 Regular — SaltUI's `sub` style: descriptions, units, legends. */
    val caption = TextStyle(
        fontFamily = family,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )

    /** Chart tick labels: 10sp, the smallest size allowed inside a chart. */
    val chartTick = caption.copy(fontSize = 10.sp, lineHeight = 13.sp)

    /**
     * Live numbers (dB, Hz, FS, correlation). Tabular figures keep the digits from shifting
     * as they change; families without `tnum` simply ignore the setting, so callers also give
     * these values a fixed-width, right-aligned container.
     */
    val numeric = body.copy(fontFeatureSettings = "tnum")

    val numericSmall = caption.copy(fontFeatureSettings = "tnum")
}
