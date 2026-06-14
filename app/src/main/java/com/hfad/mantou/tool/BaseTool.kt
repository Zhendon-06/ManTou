package com.hfad.mantou.tool

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 所有 Tool 的基类。
 *
 * 接口契约（开发者必须遵守）：
 * 1. 子类必须加 @MantouTool 注解，提供 name/description/usageScenario
 * 2. 暴露给 WebView 的方法必须加三件套：
 *    - @android.webkit.JavascriptInterface  （让 WebView 能调）
 *    - @ToolMethod                          （描述方法用途）
 *    - @ToolReturns                         （描述返回值结构）
 * 3. 每个参数必须加 @ToolParam（Kotlin 反射拿不到运行时参数名）
 * 4. 方法签名只能用基本类型：String / Int / Long / Boolean / Double
 *    （JS → Java 桥不支持复杂对象传参）
 * 5. 返回值统一为 JSON String，schema：
 *      { "success": true,  "data": ..., "error": null    }
 *      { "success": false, "data": null, "error": "..." }
 * 6. 不做耗时操作；权限敏感操作必须先检查，没权限时返回 error JSON
 * 7. 必须在 ToolRegistry 手动注册
 *
 * JS 侧调用约定：
 *   `window.MantouApp.<toolName>.<methodName>(...)` 返回 JSON 字符串
 *   例如：window.MantouApp.alarm.alarmSet(7, 30, "晨练")
 */
abstract class BaseTool(protected val context: Context) {

    /** Tool 名称，默认读取 @MantouTool 注解。子类可覆盖。 */
    open val toolName: String
        get() = this::class.java.getAnnotation(MantouTool::class.java)?.name
            ?: this::class.java.simpleName.replaceFirstChar { it.lowercase() }

    /** 统一成功响应。data 支持 JSONObject / JSONArray / 基本类型 / null。 */
    protected fun success(data: Any? = null): String {
        val json = JSONObject()
        json.put("success", true)
        when (data) {
            null -> json.put("data", JSONObject.NULL)
            is JSONObject, is JSONArray, is String, is Number, is Boolean -> json.put("data", data)
            else -> json.put("data", data.toString())
        }
        json.put("error", JSONObject.NULL)
        return json.toString()
    }

    /** 便捷重载：用 key-value 对直接拼一个 data JSONObject。 */
    protected fun success(vararg pairs: Pair<String, Any?>): String {
        val data = JSONObject()
        for ((k, v) in pairs) data.put(k, v ?: JSONObject.NULL)
        return success(data)
    }

    /** 统一错误响应。 */
    protected fun error(message: String): String {
        val json = JSONObject()
        json.put("success", false)
        json.put("data", JSONObject.NULL)
        json.put("error", message)
        return json.toString()
    }

    /**
     * 把 block 切到主线程异步执行。JSBridge 调用线程不是主线程，
     * 涉及 Toast / Vibrator / ClipboardManager 等 UI / 系统服务时必须切。
     */
    protected fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    /**
     * 安全启动一个 Intent。捕获 ActivityNotFoundException / SecurityException 等异常，
     * 返回统一的 success/error JSON。所有需要跳系统页（闹钟、日历、相机…）的 Tool 用它。
     */
    protected fun launchIntent(
        intent: android.content.Intent,
        notFoundMessage: String = "目标系统应用未找到"
    ): String = runCatching {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        success("launched" to true)
    }.getOrElse { e ->
        when (e) {
            is android.content.ActivityNotFoundException -> error(notFoundMessage)
            else -> error("启动失败：${e.message ?: e::class.java.simpleName}")
        }
    }

    private companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
