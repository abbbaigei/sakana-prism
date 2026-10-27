package dev.sakana.spw.prism.settings

/**
 * Strength of the halo drawn under the spectrogram ridges.
 *
 * Wide strokes over a path with one segment per vertex are the most expensive part of the
 * frame on a software rasterizer, so the user gets to trade looks for frame time instead of
 * the renderer guessing. [MEDIUM] is the default: one soft pass, which reads as a glow
 * without doubling the fill area.
 */
enum class SpectrogramGlow(val label: String) {
    OFF("关"),
    MEDIUM("中"),
    STRONG("强"),
}
