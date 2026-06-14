package com.hfad.mantou.tool.impl

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.webkit.JavascriptInterface
import com.hfad.mantou.tool.BaseTool
import com.hfad.mantou.tool.MantouTool
import com.hfad.mantou.tool.ToolMethod
import com.hfad.mantou.tool.ToolParam
import com.hfad.mantou.tool.ToolReturns

@MantouTool(
    name = "calendar",
    description = "在系统日历里插入事件 / 全天日程，或直接打开日历到指定时间",
    usageScenario = "网页里要把一个事件写入用户的日历并在指定时间提醒；或导航到某天查看日程"
)
class CalendarTool(context: Context) : BaseTool(context) {

    @JavascriptInterface
    @ToolMethod(
        description = "唤起系统日历，预填一个新事件。用户在系统页面点击保存才生效。",
        example = "window.MantouApp.calendar.calendarAdd('团队周会', '线上会议室 A', 1718352000000, 1718355600000);"
    )
    @ToolReturns(
        description = "是否成功唤起日历事件编辑界面",
        jsonExample = "{\"success\": true, \"data\": {\"launched\": true}, \"error\": null}"
    )
    fun calendarAdd(
        @ToolParam(name = "title", description = "事件标题") title: String,
        @ToolParam(name = "location", description = "地点，可为空字符串") location: String,
        @ToolParam(name = "beginMillis", description = "开始时间戳（毫秒，UTC）") beginMillis: Long,
        @ToolParam(name = "endMillis", description = "结束时间戳（毫秒，UTC）") endMillis: Long
    ): String {
        val err = validateRange(title, beginMillis, endMillis)
        if (err != null) return err

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            if (location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
        }
        return launchIntent(intent, "找不到系统日历应用")
    }

    @JavascriptInterface
    @ToolMethod(
        description = "添加事件并预设提醒。reminderMinutes 表示事件开始前 N 分钟提醒（0=准点）。",
        example = "window.MantouApp.calendar.calendarAddWithReminder('体检', '市医院', 1718352000000, 1718355600000, 30);"
    )
    @ToolReturns(
        description = "是否成功唤起日历事件编辑界面",
        jsonExample = "{\"success\": true, \"data\": {\"launched\": true}, \"error\": null}"
    )
    fun calendarAddWithReminder(
        @ToolParam(name = "title", description = "事件标题") title: String,
        @ToolParam(name = "location", description = "地点，可为空字符串") location: String,
        @ToolParam(name = "beginMillis", description = "开始时间戳（毫秒，UTC）") beginMillis: Long,
        @ToolParam(name = "endMillis", description = "结束时间戳（毫秒，UTC）") endMillis: Long,
        @ToolParam(name = "reminderMinutes", description = "事件开始前 N 分钟提醒，0-10080") reminderMinutes: Int
    ): String {
        val err = validateRange(title, beginMillis, endMillis)
        if (err != null) return err
        if (reminderMinutes !in 0..10080) return error("reminderMinutes 必须在 0-10080 (一周) 之间")

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            if (location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            // 日历事件编辑界面会读这个 extra 预填提醒
            putExtra(CalendarContract.Reminders.MINUTES, reminderMinutes)
            putExtra(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        return launchIntent(intent, "找不到系统日历应用")
    }

    @JavascriptInterface
    @ToolMethod(
        description = "添加全天日程，beginMillis 表示当天 00:00（UTC），自动 +1 天。",
        example = "window.MantouApp.calendar.calendarAddAllDay('生日', 1718323200000);"
    )
    @ToolReturns(
        description = "是否成功唤起日历事件编辑界面",
        jsonExample = "{\"success\": true, \"data\": {\"launched\": true}, \"error\": null}"
    )
    fun calendarAddAllDay(
        @ToolParam(name = "title", description = "事件标题") title: String,
        @ToolParam(name = "beginMillis", description = "当天起始时间戳（毫秒，UTC）") beginMillis: Long
    ): String {
        if (title.isBlank()) return error("title 不能为空")
        if (beginMillis <= 0) return error("beginMillis 必须为正整数")

        val oneDayMs = 24L * 60 * 60 * 1000
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.ALL_DAY, true)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, beginMillis + oneDayMs)
        }
        return launchIntent(intent, "找不到系统日历应用")
    }

    @JavascriptInterface
    @ToolMethod(
        description = "打开系统日历到指定时间戳所在日。timeMillis<=0 时打开默认视图。",
        example = "window.MantouApp.calendar.calendarOpen(1718352000000);"
    )
    @ToolReturns(
        description = "是否成功打开日历",
        jsonExample = "{\"success\": true, \"data\": {\"launched\": true}, \"error\": null}"
    )
    fun calendarOpen(
        @ToolParam(name = "timeMillis", description = "目标时间戳（毫秒，UTC）；<=0 表示打开默认视图") timeMillis: Long
    ): String {
        val intent = if (timeMillis > 0) {
            val uri = ContentUris.withAppendedId(
                CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build(),
                timeMillis
            )
            Intent(Intent.ACTION_VIEW).setData(uri)
        } else {
            Intent(Intent.ACTION_VIEW).setData(CalendarContract.CONTENT_URI)
        }
        return launchIntent(intent, "找不到系统日历应用")
    }

    private fun validateRange(title: String, begin: Long, end: Long): String? = when {
        title.isBlank() -> error("title 不能为空")
        begin <= 0 -> error("beginMillis 必须为正整数")
        end <= begin -> error("endMillis 必须晚于 beginMillis")
        else -> null
    }
}
