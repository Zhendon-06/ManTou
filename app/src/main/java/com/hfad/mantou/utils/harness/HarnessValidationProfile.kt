package com.hfad.mantou.utils.harness

internal enum class HarnessValidationProfile(
    val label: String,
    val description: String,
    val strictSelfTest: Boolean
) {
    PROJECT("普通验收", "构建和核心场景通过后进入浅验收，测试集只跑一轮并记录通过数", false),
    TABLET_PORTRAIT("严格验收", "增加平板竖屏，页面自测必须通过", true),
    TABLET_ALL("严格验收与横竖屏", "增加平板横竖屏，页面自测必须通过", true);

    val shallowAcceptance: Boolean get() = this == PROJECT

    fun qualityGate(base: WebQualityGateContract): WebQualityGateContract {
        val phone = WebQualityGateContract.DEFAULT_MOBILE_VIEWPORTS.first { it.id == "standard-portrait" }
        val portrait = WebQualityViewport("tablet-portrait", 768, 1024)
        val extra = when (this) {
            PROJECT -> return base.copy(requiredViewports = listOf(phone), requireScreenshotEvidence = false)
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
