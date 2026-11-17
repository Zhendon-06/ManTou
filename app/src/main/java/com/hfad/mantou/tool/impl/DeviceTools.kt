package com.hfad.mantou.tool.impl

import android.webkit.JavascriptInterface
import com.hfad.mantou.tool.BaseTool
import com.hfad.mantou.tool.MantouTool
import com.hfad.mantou.tool.ToolMethod
import com.hfad.mantou.tool.ToolParam
import com.hfad.mantou.tool.ToolReturns

@MantouTool(
    name = "photoPicker",
    description = "打开系统照片选择器，选取照片并保存到当前小应用",
    usageScenario = "从手机相册选择照片做旅行手账、照片墙或日记。把返回的 url 设为 img.src，用 storage 保存照片信息。",
    autoRegister = false
)
internal class PhotoPickerTool(private val page: WebAppDevicePage) : BaseTool(page.context) {

    @JavascriptInterface
    @ToolMethod(
        description = "从用户点击事件调用。打开系统照片选择器，选择 1 到 5 张照片。结果通过 window 上的 mantou:photoPicker 事件返回。event.detail 含 requestId, success, data, error；成功的 data 含 cancelled 和 photos 数组，每张含 id、url、mimeType。用户取消时 cancelled=true、photos 为空。",
        example = "window.addEventListener('mantou:photoPicker', e => { const r = e.detail; if (r.success && !r.data.cancelled) document.querySelector('img').src = r.data.photos[0].url; }); window.MantouApp.photoPicker.photoPick(1);"
    )
    @ToolReturns(description = "请求编号；实际结果通过事件返回", jsonExample = REQUEST_EXAMPLE)
    fun photoPick(
        @ToolParam(name = "maxPhotos", description = "允许选择的最多照片数，1–5") maxPhotos: Int
    ): String {
        if (maxPhotos !in 1..5) return error("最多可选择 1 到 5 张照片")
        return page.submit(toolName) { page.host.pickPhotos(it, maxPhotos) }
    }

    @JavascriptInterface
    @ToolMethod(description = "删除当前小应用中指定 id 的照片。", example = "window.MantouApp.photoPicker.photoDelete(photo.id);")
    @ToolReturns(description = "是否删除已有照片", jsonExample = DELETE_EXAMPLE)
    fun photoDelete(@ToolParam(name = "id", description = "照片选择结果中的 id") id: String): String =
        page.deleteMedia(id, recording = false)
}

@MantouTool(
    name = "speech",
    description = "用 Android 系统语音朗读文本，可以停止正在播报的内容",
    usageScenario = "语音播报、朗读、训练计时提示、读单词、语音倒数",
    autoRegister = false
)
internal class SpeechTool(private val page: WebAppDevicePage) : BaseTool(page.context) {

    @JavascriptInterface
    @ToolMethod(
        description = "朗读文本。可以从按钮或用户启动的计时器触发。播报结束、被打断或失败时，在 window 触发 mantou:speech，event.detail 含 requestId, success, data, error；成功时 data.spoken=true。",
        example = "window.addEventListener('mantou:speech', e => console.log(e.detail)); window.MantouApp.speech.speechSpeak('训练开始', 'zh-CN');"
    )
    @ToolReturns(description = "请求编号；实际结果通过事件返回", jsonExample = REQUEST_EXAMPLE)
    fun speechSpeak(
        @ToolParam(name = "text", description = "要朗读的文本，1–500 字符") text: String,
        @ToolParam(name = "languageTag", description = "BCP 47 语言标签，如 zh-CN、en-US，空字符串使用设备语言") languageTag: String
    ): String {
        if (text.isBlank() || text.length > 500) return error("播报内容应为 1 到 500 个字符")
        return page.submit(toolName) { page.host.speak(it, text, languageTag) }
    }

    @JavascriptInterface
    @ToolMethod(description = "停止当前网页的语音播报。通过 mantou:speech 事件返回 data.stopped。", example = "window.MantouApp.speech.speechStop();")
    @ToolReturns(description = "请求编号；实际结果通过事件返回", jsonExample = REQUEST_EXAMPLE)
    fun speechStop(): String = page.submit(toolName, page.host::stopSpeech)
}

@MantouTool(
    name = "notification",
    description = "立即发送或撤销当前小应用的 Android 本地通知",
    usageScenario = "系统通知、通知栏提示、用户启动的计时器结束后发通知、测试通知",
    autoRegister = false
)
internal class NotificationTool(private val page: WebAppDevicePage) : BaseTool(page.context) {

    @JavascriptInterface
    @ToolMethod(
        description = "立即发送本地通知，同一小应用内相同 id 会更新原通知。Android 13+ 可能询问权限。window 的 mantou:notification 事件 detail 含 requestId, success, data, error；发送成功时 data.id 为通知 id。此方法立即发送，关闭页面后的定时通知请使用系统闹钟能力。",
        example = "window.addEventListener('mantou:notification', e => console.log(e.detail)); window.MantouApp.notification.notificationShow('timer', '计时结束', '可以休息了');"
    )
    @ToolReturns(description = "请求编号；实际结果通过事件返回", jsonExample = REQUEST_EXAMPLE)
    fun notificationShow(
        @ToolParam(name = "id", description = "小应用内的通知标识，1–64 位英文字母、数字、下划线或连字符") id: String,
        @ToolParam(name = "title", description = "通知标题，1–64 字符") title: String,
        @ToolParam(name = "message", description = "通知正文，最多 240 字符") message: String
    ): String {
        if (!NOTIFICATION_ID.matches(id)) return error("通知 id 只能使用英文字母、数字、下划线或连字符")
        if (title.isBlank() || title.length > 64 || message.length > 240) return error("通知标题或正文长度无效")
        return page.submit(toolName) { page.host.showNotification(it, id, title, message) }
    }

    @JavascriptInterface
    @ToolMethod(description = "撤销当前小应用的指定通知。通过 mantou:notification 事件返回 data.id。", example = "window.MantouApp.notification.notificationCancel('timer');")
    @ToolReturns(description = "请求编号；实际结果通过事件返回", jsonExample = REQUEST_EXAMPLE)
    fun notificationCancel(@ToolParam(name = "id", description = "发送通知时使用的 id") id: String): String {
        if (!NOTIFICATION_ID.matches(id)) return error("通知 id 无效")
        return page.submit(toolName) { page.host.cancelNotification(it, id) }
    }

    private companion object {
        val NOTIFICATION_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

@MantouTool(
    name = "recorder",
    description = "让用户在原生录音窗口确认开始、停止并保存或取消录音",
    usageScenario = "录音、语音备忘录、英语跟读、语音日记。使用返回的 url 在 audio 元素中回放，用 storage 保存录音信息。",
    autoRegister = false
)
internal class RecorderTool(private val page: WebAppDevicePage) : BaseTool(page.context) {

    @JavascriptInterface
    @ToolMethod(
        description = "从用户点击事件调用，进入有明确开始、保存和取消按钮的录音流程，最多 5 分钟。window 的 mantou:recorder 事件 detail 含 requestId, success, data, error。保存成功时 data 含 id、url、mimeType、durationMs、cancelled=false；放弃时只有 cancelled=true。",
        example = "window.addEventListener('mantou:recorder', e => { const r = e.detail; if (r.success && !r.data.cancelled) document.querySelector('audio').src = r.data.url; }); window.MantouApp.recorder.recordAudio();"
    )
    @ToolReturns(description = "请求编号；实际结果通过事件返回", jsonExample = REQUEST_EXAMPLE)
    fun recordAudio(): String = page.submit(toolName, page.host::requestRecording)

    @JavascriptInterface
    @ToolMethod(description = "删除当前小应用中指定 id 的录音文件。", example = "window.MantouApp.recorder.recordingDelete(recording.id);")
    @ToolReturns(description = "是否删除已有录音", jsonExample = DELETE_EXAMPLE)
    fun recordingDelete(@ToolParam(name = "id", description = "录音结果中的 id") id: String): String =
        page.deleteMedia(id, recording = true)
}

private const val REQUEST_EXAMPLE = "{\"success\":true,\"data\":{\"requestId\":\"...\"},\"error\":null}"
private const val DELETE_EXAMPLE = "{\"success\":true,\"data\":{\"removed\":true},\"error\":null}"
