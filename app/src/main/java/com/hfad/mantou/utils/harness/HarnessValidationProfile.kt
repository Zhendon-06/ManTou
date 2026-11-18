package com.hfad.mantou.utils.harness

internal enum class HarnessValidationProfile(
    val label: String,
    val description: String,
    val strictSelfTest: Boolean
) {
    PROJECT("按项目验收", "手机和项目声明的尺寸，页面自测问题可作为警告", false),
    TABLET_PORTRAIT("严格验收", "增加平板竖屏，页面自测必须通过", true),
    TABLET_ALL("严格验收与横竖屏", "增加平板横竖屏，页面自测必须通过", true);

    fun qualityGate(base: WebQualityGateContract): WebQualityGateContract {
        val portrait = WebQualityViewport("tablet-portrait", 768, 1024)
        val extra = when (this) {
            PROJECT -> emptyList()
            TABLET_PORTRAIT -> listOf(portrait)
            TABLET_ALL -> listOf(portrait, WebQualityViewport("tablet-landscape", 1024, 768))
        }
        return base.copy(requiredViewports = base.requiredViewports + extra.filterNot { viewport ->
            base.requiredViewports.any { current ->
                current.id == viewport.id ||
                    (current.widthCssPixels == viewport.widthCssPixels &&
                        current.heightCssPixels == viewport.heightCssPixels)
            }
        })
    }

    companion object {
        fun fromStored(value: String?, legacyIntensity: String?): HarnessValidationProfile {
            return entries.firstOrNull { it.name == value } ?: when (legacyIntensity) {
                "HIGH" -> TABLET_PORTRAIT
                "EXTRA_HIGH" -> TABLET_ALL
                else -> PROJECT
            }
        }
    }
}
