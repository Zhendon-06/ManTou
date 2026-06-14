package com.hfad.mantou.tool.impl

import android.content.Context
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.hfad.mantou.tool.BaseTool
import com.hfad.mantou.tool.MantouTool
import com.hfad.mantou.tool.ToolMethod
import com.hfad.mantou.tool.ToolParam
import com.hfad.mantou.tool.ToolReturns

@MantouTool(
    name = "toast",
    description = "弹一个原生 Toast 提示，用于轻量级即时反馈",
    usageScenario = "网页里给用户操作完成 / 失败 / 复制成功等即时反馈；也用于调试桥连通性"
)
class ToastTool(context: Context) : BaseTool(context) {

    @JavascriptInterface
    @ToolMethod(
        description = "弹一个 Toast，可指定时长。Toast.makeText 必须在主线程，内部已切回。",
        example = "window.MantouApp.toast.toastShow('保存成功', true);"
    )
    @ToolReturns(
        description = "是否成功调度 Toast",
        jsonExample = "{\"success\": true, \"data\": {\"shown\": true}, \"error\": null}"
    )
    fun toastShow(
        @ToolParam(name = "message", description = "提示文本") message: String,
        @ToolParam(name = "longDuration", description = "true=LENGTH_LONG (约 3.5s)，false=LENGTH_SHORT (约 2s)") longDuration: Boolean
    ): String {
        if (message.isEmpty()) return error("message 不能为空")
        val duration = if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        runOnMain { Toast.makeText(context, message, duration).show() }
        return success("shown" to true)
    }

    @JavascriptInterface
    @ToolMethod(
        description = "弹一个短 Toast（约 2s）。是 toastShow(msg, false) 的便捷写法。",
        example = "window.MantouApp.toast.toastShort('已复制');"
    )
    @ToolReturns(
        description = "是否成功调度 Toast",
        jsonExample = "{\"success\": true, \"data\": {\"shown\": true}, \"error\": null}"
    )
    fun toastShort(
        @ToolParam(name = "message", description = "提示文本") message: String
    ): String = toastShow(message, false)

    @JavascriptInterface
    @ToolMethod(
        description = "弹一个长 Toast（约 3.5s）。是 toastShow(msg, true) 的便捷写法。",
        example = "window.MantouApp.toast.toastLong('网络不稳，请稍后重试');"
    )
    @ToolReturns(
        description = "是否成功调度 Toast",
        jsonExample = "{\"success\": true, \"data\": {\"shown\": true}, \"error\": null}"
    )
    fun toastLong(
        @ToolParam(name = "message", description = "提示文本") message: String
    ): String = toastShow(message, true)
}
