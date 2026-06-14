package com.hfad.mantou.tool.impl

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import com.hfad.mantou.tool.BaseTool
import com.hfad.mantou.tool.MantouTool
import com.hfad.mantou.tool.ToolMethod
import com.hfad.mantou.tool.ToolParam
import com.hfad.mantou.tool.ToolReturns

@MantouTool(
    name = "vibration",
    description = "控制设备振动：单次振动、自定义模式振动、停止振动",
    usageScenario = "操作反馈、通知提醒、节奏模拟（如心跳、SOS）"
)
class VibrationTool(context: Context) : BaseTool(context) {

    private val vibrator: Vibrator? = resolveVibrator(context)

    @JavascriptInterface
    @ToolMethod(
        description = "振动一次，时长 durationMs 毫秒。",
        example = "window.MantouApp.vibration.vibrateOnce(200);"
    )
    @ToolReturns(
        description = "操作是否成功",
        jsonExample = "{\"success\": true, \"data\": {\"durationMs\": 200}, \"error\": null}"
    )
    fun vibrateOnce(
        @ToolParam(name = "durationMs", description = "振动时长，1-10000 毫秒") durationMs: Int
    ): String {
        val v = vibrator ?: return error("设备不支持振动")
        if (!v.hasVibrator()) return error("设备不支持振动")
        if (durationMs !in 1..10000) return error("durationMs 必须在 1-10000 之间，收到 $durationMs")

        return runCatching {
            val effect = VibrationEffect.createOneShot(
                durationMs.toLong(),
                VibrationEffect.DEFAULT_AMPLITUDE
            )
            v.vibrate(effect)
            success("durationMs" to durationMs)
        }.getOrElse { e ->
            error("振动失败：${e.message ?: e::class.java.simpleName}")
        }
    }

    @JavascriptInterface
    @ToolMethod(
        description = "按模式振动。patternCsv 是逗号分隔的毫秒序列，从「等待」开始，奇偶交替；如 '0,200,100,200' = 立即振 200ms，停 100ms，再振 200ms。repeatIndex>=0 表示从该下标循环，-1 不循环。",
        example = "window.MantouApp.vibration.vibratePattern('0,100,50,100,50,300', -1);"
    )
    @ToolReturns(
        description = "操作是否成功",
        jsonExample = "{\"success\": true, \"data\": {\"steps\": 6}, \"error\": null}"
    )
    fun vibratePattern(
        @ToolParam(name = "patternCsv", description = "逗号分隔的毫秒序列，等待/振动交替") patternCsv: String,
        @ToolParam(name = "repeatIndex", description = "循环起点下标，-1 表示只播放一次") repeatIndex: Int
    ): String {
        val v = vibrator ?: return error("设备不支持振动")
        if (!v.hasVibrator()) return error("设备不支持振动")

        val parts = patternCsv.split(',').map { it.trim() }
        if (parts.isEmpty()) return error("patternCsv 不能为空")
        val pattern = LongArray(parts.size)
        for ((i, p) in parts.withIndex()) {
            val n = p.toLongOrNull() ?: return error("patternCsv 第 ${i + 1} 个值不是整数：$p")
            if (n < 0 || n > 60_000) return error("patternCsv 元素必须在 0-60000 之间，收到 $n")
            pattern[i] = n
        }
        if (repeatIndex < -1 || repeatIndex >= pattern.size) {
            return error("repeatIndex 越界：$repeatIndex (pattern 长度 ${pattern.size})")
        }

        return runCatching {
            val effect = VibrationEffect.createWaveform(pattern, repeatIndex)
            v.vibrate(effect)
            success("steps" to pattern.size)
        }.getOrElse { e ->
            error("振动失败：${e.message ?: e::class.java.simpleName}")
        }
    }

    @JavascriptInterface
    @ToolMethod(
        description = "立即停止振动。",
        example = "window.MantouApp.vibration.vibrateCancel();"
    )
    @ToolReturns(
        description = "操作是否成功",
        jsonExample = "{\"success\": true, \"data\": {\"cancelled\": true}, \"error\": null}"
    )
    fun vibrateCancel(): String {
        val v = vibrator ?: return error("设备不支持振动")
        return runCatching {
            v.cancel()
            success("cancelled" to true)
        }.getOrElse { e ->
            error("停止振动失败：${e.message ?: e::class.java.simpleName}")
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
