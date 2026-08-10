package com.hfad.mantou.view.glass

import androidx.compose.ui.graphics.Color

enum class GlassStyle(
    internal val cornerRadiusDp: Float,
    internal val blurRadiusDp: Float,
    internal val refractionHeightDp: Float,
    internal val refractionAmountDp: Float,
    internal val surfaceColor: Color
) {
    Capsule(999f, 3f, 22f, 50f, Color.White.copy(alpha = 0.11f)),
    Circle(999f, 3f, 24f, 54f, Color.White.copy(alpha = 0.11f)),
    Button(999f, 3f, 24f, 54f, Color.White.copy(alpha = 0.12f)),
    Card(20f, 4f, 25f, 58f, Color.White.copy(alpha = 0.10f)),
    Panel(28f, 5f, 30f, 68f, Color.White.copy(alpha = 0.09f)),
    Input(14f, 3f, 20f, 46f, Color.White.copy(alpha = 0.08f)),
    Toolbar(999f, 4f, 28f, 64f, Color.White.copy(alpha = 0.11f)),
    Sheet(32f, 6f, 34f, 76f, Color.White.copy(alpha = 0.10f)),
    Accent(999f, 3f, 24f, 54f, Color(0xFF4D9FFF).copy(alpha = 0.28f));

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
