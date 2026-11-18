package com.hfad.mantou.utils.harness

import com.hfad.mantou.utils.project.WebAppAcceptancePriority
import com.hfad.mantou.utils.project.WebAppProjectFileRole
import com.hfad.mantou.utils.project.WebAppProjectManifest

internal data class HarnessTaskComplexity(
    val level: Level,
    val evidence: List<String>
) {
    enum class Level(val label: String, val guidance: String) {
        SIMPLE("简单", "围绕主要操作完成实现，修复时优先读写诊断直接指向的文件。"),
        STANDARD("常规", "按项目规格处理主要交互、状态和验收失败。修复时按诊断定位受影响的文件。"),
        COMPLEX("复杂", "按可独立验收的用户流程实现。优先处理跨页面状态、持久化、文件依赖及相关系统能力的边界，修复后复验受影响的流程。")
    }

    fun limits(validation: HarnessValidationProfile): HarnessLimits {
        val limits = when (level) {
            Level.SIMPLE -> HarnessLimits(maxCodeIterations = 4, maxTaskTurns = 12, maxTotalModelTurns = 40)
            Level.STANDARD -> HarnessLimits(maxCodeIterations = 8, maxTaskTurns = 14, maxTotalModelTurns = 80)
            Level.COMPLEX -> HarnessLimits(maxCodeIterations = 12, maxTaskTurns = 16, maxTotalModelTurns = 160)
        }
        return limits.copy(allowSelfTestBypass = !validation.strictSelfTest)
    }

    companion object {
        fun assess(manifest: WebAppProjectManifest?, relevantTools: Set<String>): HarnessTaskComplexity {
            val spec = manifest?.appSpec
            val screens = spec?.screens.orEmpty().size
            val primaryCriteria = spec?.acceptanceContract?.criteria.orEmpty()
                .count { it.priority == WebAppAcceptancePriority.P0 }
            val stateChanges = spec?.interactions.orEmpty().count { it.stateChanges.orEmpty().isNotEmpty() }
            val scripts = manifest?.files.orEmpty().count { it.role == WebAppProjectFileRole.SCRIPT }
            val systemTools = relevantTools.size
            val level = when {
                screens >= 3 || primaryCriteria >= 3 || scripts >= 4 ||
                    stateChanges >= 5 || systemTools >= 2 -> Level.COMPLEX
                spec == null || screens > 1 || primaryCriteria > 1 || scripts > 1 ||
                    stateChanges > 2 || systemTools > 0 -> Level.STANDARD
                else -> Level.SIMPLE
            }
            val evidence = buildList {
                if (spec != null) {
                    add("${screens} 个界面")
                    add("${primaryCriteria} 项核心验收")
                    add("${stateChanges} 个状态变更交互")
                    add("${scripts} 个脚本")
                }
                if (systemTools > 0) add("${systemTools} 项相关 Android 能力")
                if (isEmpty()) add("本轮沿用已有项目，采用常规编排")
            }
            return HarnessTaskComplexity(level, evidence)
        }
    }
}
