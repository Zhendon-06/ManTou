package com.hfad.mantou.utils.harness

/** Local execution effort; does not change the provider's model or reasoning parameters. */
enum class HarnessIntensity(
    val label: String,
    val description: String,
    val iterations: Int,
    val turns: Int,
    val totalTurns: Int,
    val strictSelfTest: Boolean
) {
    LOW("低", "优先速度，较少修复轮次，保留基础质量与功能验收", 4, 8, 24, false),
    MEDIUM("中", "均衡速度与检查深度，适合日常生成", 8, 12, 64, false),
    HIGH("高", "增加修复预算和屏幕覆盖，自测失败阻止交付", 12, 16, 128, true),
    EXTRA_HIGH("极高", "最大修复预算，覆盖平板横竖屏，严格自测与验收", 16, 20, 200, true);

    fun limits() = HarnessLimits(
        maxCodeIterations = iterations,
        maxTaskTurns = turns,
        maxTotalModelTurns = totalTurns,
        allowSelfTestBypass = !strictSelfTest
    )

    fun qualityGate(base: WebQualityGateContract): WebQualityGateContract {
        val extra = when (this) {
            LOW, MEDIUM -> emptyList()
            HIGH -> listOf(WebQualityViewport("tablet-portrait", 768, 1024))
            EXTRA_HIGH -> listOf(
                WebQualityViewport("tablet-portrait", 768, 1024),
                WebQualityViewport("tablet-landscape", 1024, 768)
            )
        }
        val existing = base.requiredViewports.map { it.id }.toSet()
        return base.copy(requiredViewports = base.requiredViewports + extra.filterNot { it.id in existing })
    }

    companion object {
        fun fromStored(value: String?): HarnessIntensity = entries.firstOrNull { it.name == value } ?: HIGH
    }
}
