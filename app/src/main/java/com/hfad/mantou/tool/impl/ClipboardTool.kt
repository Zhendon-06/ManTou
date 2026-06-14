package com.hfad.mantou.tool.impl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.webkit.JavascriptInterface
import com.hfad.mantou.tool.BaseTool
import com.hfad.mantou.tool.MantouTool
import com.hfad.mantou.tool.ToolMethod
import com.hfad.mantou.tool.ToolParam
import com.hfad.mantou.tool.ToolReturns
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@MantouTool(
    name = "clipboard",
    description = "读取和写入系统剪贴板",
    usageScenario = "复制链接/口令到剪贴板；从剪贴板读取用户已复制的文本"
)
class ClipboardTool(context: Context) : BaseTool(context) {

    private val clipboard: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @JavascriptInterface
    @ToolMethod(
        description = "读取剪贴板里第一项的纯文本。无内容时返回空字符串。注意 Android 10+ 只有前台 App 能读。",
        example = "var r = JSON.parse(window.MantouApp.clipboard.clipboardRead()); if (r.success) alert(r.data.text);"
    )
    @ToolReturns(
        description = "剪贴板文本，无内容时为空字符串",
        jsonExample = "{\"success\": true, \"data\": {\"text\": \"hello\"}, \"error\": null}"
    )
    fun clipboardRead(): String {
        // ClipboardManager 的访问必须在主线程，JSBridge 调用线程不是主线程。
        // 用 CountDownLatch 同步取主线程的结果。最多等 1 秒。
        val result = AtomicReference<String>("")
        val errRef = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        runOnMain {
            try {
                if (!clipboard.hasPrimaryClip()) {
                    // 没内容，返回空字符串
                } else {
                    val clip = clipboard.primaryClip
                    val itemCount = clip?.itemCount ?: 0
                    if (itemCount > 0) {
                        val text = clip!!.getItemAt(0).coerceToText(context)
                        result.set(text?.toString() ?: "")
                    }
                }
            } catch (e: Throwable) {
                errRef.set(e.message ?: e::class.java.simpleName)
            } finally {
                latch.countDown()
            }
        }
        val done = latch.await(1, TimeUnit.SECONDS)
        if (!done) return error("读取剪贴板超时（主线程繁忙）")
        errRef.get()?.let { return error("读取剪贴板失败：$it") }
        return success("text" to result.get())
    }

    @JavascriptInterface
    @ToolMethod(
        description = "把文本写入剪贴板。Android 13+ 系统会自动屏蔽 Toast，请配合 toast 自行提示用户。",
        example = "window.MantouApp.clipboard.clipboardWrite('https://example.com', '邀请链接');"
    )
    @ToolReturns(
        description = "操作是否成功",
        jsonExample = "{\"success\": true, \"data\": {\"length\": 19}, \"error\": null}"
    )
    fun clipboardWrite(
        @ToolParam(name = "text", description = "要写入的文本") text: String,
        @ToolParam(name = "label", description = "剪贴项的标签（系统通知里显示），可为空字符串") label: String
    ): String {
        val safeLabel = label.ifBlank { "mantou-app" }
        return runCatching {
            runOnMain {
                clipboard.setPrimaryClip(ClipData.newPlainText(safeLabel, text))
            }
            success("length" to text.length)
        }.getOrElse { e ->
            error("写入剪贴板失败：${e.message ?: e::class.java.simpleName}")
        }
    }

    @JavascriptInterface
    @ToolMethod(
        description = "清空剪贴板。Android 9 以下不支持，会返回错误。",
        example = "window.MantouApp.clipboard.clipboardClear();"
    )
    @ToolReturns(
        description = "操作是否成功",
        jsonExample = "{\"success\": true, \"data\": {\"cleared\": true}, \"error\": null}"
    )
    fun clipboardClear(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return error("Android 9 以下不支持清空剪贴板")
        }
        return runCatching {
            runOnMain { clipboard.clearPrimaryClip() }
            success("cleared" to true)
        }.getOrElse { e ->
            error("清空剪贴板失败：${e.message ?: e::class.java.simpleName}")
        }
    }
}
