package com.hfad.mantou.view.glass

import androidx.compose.ui.graphics.Color

private fun lightOutline(alpha: Float): Color =
    Color(0xFF61738E).copy(alpha = alpha)

enum class GlassStyle(
    internal val cornerRadiusDp: Float,
    internal val blurRadiusDp: Float,
    internal val refractionHeightDp: Float,
    internal val refractionAmountDp: Float,
    internal val surfaceColor: Color,
    internal val outlineColor: Color
) {
    Capsule(999f, 3f, 22f, 50f, Color.White.copy(alpha = 0.11f), lightOutline(0.22f)),
    Circle(999f, 3f, 24f, 54f, Color.White.copy(alpha = 0.11f), lightOutline(0.20f)),
    Button(999f, 3f, 24f, 54f, Color.White.copy(alpha = 0.12f), lightOutline(0.22f)),
    Card(20f, 4f, 25f, 58f, Color.White.copy(alpha = 0.10f), lightOutline(0.16f)),
    Panel(28f, 5f, 30f, 68f, Color.White.copy(alpha = 0.09f), lightOutline(0.15f)),
    Input(14f, 3f, 20f, 46f, Color.White.copy(alpha = 0.08f), lightOutline(0.18f)),
    Toolbar(999f, 4f, 28f, 64f, Color.White.copy(alpha = 0.11f), lightOutline(0.18f)),
    Sheet(32f, 6f, 34f, 76f, Color.White.copy(alpha = 0.10f), lightOutline(0.14f)),
    Accent(
        999f,
        3f,
        24f,
        54f,
        Color(0xFF4D9FFF).copy(alpha = 0.28f),
        Color(0xFF1687FF).copy(alpha = 0.32f)
    );

    companion object {
        internal fun fromTag(tag: Any?): GlassStyle? {
            val value = tag as? String ?: return null
            if (!value.startsWith(TAG_PREFIX, ignoreCase = true)) return null
            return entries.firstOrNull {
                it.name.equals(value.substringAfter(':'), ignoreCase = true)
            }
        }

        private const val TAG_PREFIX = "glass:"
    }
}
