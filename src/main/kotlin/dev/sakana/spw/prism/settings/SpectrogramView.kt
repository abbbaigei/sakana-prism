package dev.sakana.spw.prism.settings

/**
 * Camera presets for the 3D spectrogram. [yaw] rotates the slab around the vertical
 * axis, [pitch] tilts the camera down onto it, both in degrees. Low pitch values keep
 * the "lying sheet" look of the reference visualiser.
 */
enum class SpectrogramView(val label: String, val yaw: Float, val pitch: Float) {
    FRONT_HIGH("正面高", 0f, 44f),
    FRONT_LOW("正面低", 0f, 20f),
    DIAGONAL("斜视", 26f, 30f),
    SIDE_LOW("侧面低", 58f, 18f),
    SIDE_HIGH("侧面高", 58f, 36f),
}
