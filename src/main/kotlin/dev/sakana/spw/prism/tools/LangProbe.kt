package dev.sakana.spw.prism.tools

import dev.sakana.spw.prism.i18n.AppLanguage
import dev.sakana.spw.prism.i18n.WindowsLanguage
import java.util.Locale

/** Prints how a Windows locale maps onto the plugin language table. */
fun main() {
    val samples = listOf(
        "zh-CN", "zh-TW", "zh-HK", "zh-MO", "zh-SG",
        "en-US", "en-GB",
        "fr-FR", "es-ES", "ru-RU", "ar-SA",
        "ja-JP", "ko-KR",
        "de-DE", "pt-BR",
    )
    for (tag in samples) {
        val locale = Locale.forLanguageTag(tag)
        val picked = WindowsLanguage.fromLocale(locale)
        println("$tag  ->  ${picked.code}  (${picked.nativeName})${if (picked.rtl) "  [RTL]" else ""}")
    }
    println()
    println("current JVM default: ${Locale.getDefault().toLanguageTag()} -> ${WindowsLanguage.detect().code}")
    println("explicit overrides: " + AppLanguage.entries.joinToString { it.code } + " (plus system)")
}
