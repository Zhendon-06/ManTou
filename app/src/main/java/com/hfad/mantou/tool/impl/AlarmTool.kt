package com.hfad.mantou.tool.impl

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.webkit.JavascriptInterface
import com.hfad.mantou.tool.BaseTool
import com.hfad.mantou.tool.MantouTool
import com.hfad.mantou.tool.ToolMethod
import com.hfad.mantou.tool.ToolParam
import com.hfad.mantou.tool.ToolReturns
import java.util.Calendar

@MantouTool(
    name = "alarm",
    description = "调用系统闹钟应用：单次闹钟、重复闹钟、倒计时器，以及打开闹钟主界面",
    usageScenario = "网页里需要让用户在系统层面被叫醒/提醒；倒计时；早起提醒；周一到周五重复闹钟"
)
class AlarmTool(context: Context) : BaseTool(context) {

    @JavascriptInterface
    @ToolMethod(
        description = "跳转到系统闹钟 App，预填一个新的单次闹钟。用户在系统页面里点击保存才生效。",
        example = "window.MantouApp.alarm.alarmSet(7, 30, '晨练');"
    )
    @ToolReturns(
        description = "是否成功唤起系统闹钟界面",
        jsonExample = "{\"success\": true, \"data\": {\"launched\": true}, \"error\": null}"
    )
    fun alarmSet(
        @ToolParam(name = "hour", description = "0-23") hour: Int,
        @ToolParam(name = "minute", description = "0-59") minute: Int,
        @ToolParam(name = "label", description = "闹钟标签，可为空字符串") label: String
    ): String {
        if (hour !in 0..23) return error("hour 必须在 0-23 之间，收到 $hour")
        if (minute !in 0..59) return error("minute 必须在 0-59 之间，收到 $minute")

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }
        return launchIntent(intent, "找不到系统闹钟应用")
    }

    @JavascriptInterface
    @ToolMethod(
        description = "设置重复闹钟。daysCsv 用 1-7 表示周日到周六，逗号分隔；例如 '2,3,4,5,6' 表示周一到周五。",
        example = "window.MantouApp.alarm.alarmSetWithDays(7, 0, '上班', '2,3,4,5,6');"
    )
    @ToolReturns(
        description = "是否成功唤起重复闹钟设置界面",
        jsonExample = "{\"success\": true, \"data\": {\"launched\": true}, \"error\": null}"
    )
    fun alarmSetWithDays(
        @ToolParam(name = "hour", description = "0-23") hour: Int,
        @ToolParam(name = "minute", description = "0-59") minute: Int,
        @ToolParam(name = "label", description = "闹钟标签") label: String,
        @ToolParam(name = "daysCsv", description = "1=周日,2=周一,...,7=周六；逗号分隔") daysCsv: String
    ): String {
        if (hour !in 0..23) return error("hour 必须在 0-23 之间")
        if (minute !in 0..59) return error("minute 必须在 0-59 之间")

        val days = ArrayList<Int>()
        for (part in daysCsv.split(',')) {
            val n = part.trim().toIntOrNull() ?: return error("daysCsv 包含非整数：$part")
            if (n !in Calendar.SUNDAY..Calendar.SATURDAY) {
                return error("daysCsv 元素必须在 1-7 之间，收到 $n")
            }
            if (n !in days) days.add(n)
        }
        if (days.isEmpty()) return error("daysCsv 不能为空")

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, days)
        }
        return launchIntent(intent, "找不到系统闹钟应用")
    }

    @JavascriptInterface
    @ToolMethod(
        description = "跳转到系统闹钟，启动一个倒计时器。",
        example = "window.MantouApp.alarm.alarmTimer(300, '面条计时');"
    )
    @ToolReturns(
        description = "是否成功唤起倒计时器界面",
        jsonExample = "{\"success\": true, \"data\": {\"launched\": true}, \"error\": null}"
    )
    fun alarmTimer(
        @ToolParam(name = "seconds", description = "倒计时秒数，1-86400") seconds: Int,
        @ToolParam(name = "label", description = "计时标签，可为空字符串") label: String
    ): String {
        if (seconds !in 1..86400) return error("seconds 必须在 1-86400 之间")

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }
        return launchIntent(intent, "找不到系统闹钟应用")
    }

    @JavascriptInterface
    @ToolMethod(
        description = "直接打开系统闹钟应用，不预填任何内容。",
        example = "window.MantouApp.alarm.alarmOpen();"
    )
    @ToolReturns(
        description = "是否成功打开闹钟应用",
        jsonExample = "{\"success\": true, \"data\": {\"launched\": true}, \"error\": null}"
    )
    fun alarmOpen(): String {
        return launchIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS), "找不到系统闹钟应用")
    }
}
