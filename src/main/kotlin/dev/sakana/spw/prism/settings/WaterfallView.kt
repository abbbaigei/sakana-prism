package dev.sakana.spw.prism.settings

/**
 * Camera presets for the 3D spectrogram. [yaw] rotates the slab around the vertical
 * axis, [pitch] tilts the camera down onto it, both in degrees.
 */
enum class WaterfallView(val label: String, val yaw: Float, val pitch: Float) {
    FRONT_HIGH("正面高", 0f, 64f),
    FRONT_LOW("正面低", 0f, 26f),
    DIAGONAL("斜视", 36f, 46f),
    SIDE_LOW("侧面低", 74f, 22f),
    SIDE_HIGH("侧面高", 74f, 58f),
}
