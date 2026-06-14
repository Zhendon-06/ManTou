package com.hfad.mantou.utils

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.hfad.mantou.tool.BaseTool
import com.hfad.mantou.tool.MantouTool
import com.hfad.mantou.tool.ToolMethod
import com.hfad.mantou.tool.ToolParam
import com.hfad.mantou.tool.ToolReturns
import com.hfad.mantou.tool.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

object MantouWebViewRuntime {

    /** JS 侧用的 Tool bridge 名前缀。`MantouApp_toast` 对应 toolName="toast"。 */
    private const val TOOL_BRIDGE_PREFIX = "MantouApp_"
    private const val STORAGE_TOOL_NAME = "storage"

    fun install(webView: WebView, htmlFile: File? = null) {
        webView.settings.userAgentString =
            AppGenerator.withMantouWebAppUserAgent(webView.settings.userAgentString)

        // 主桥：暴露 isMantouApp() + getToolNames()，运行时门禁 + Tool 别名挂载用。
        webView.addJavascriptInterface(MainBridge, AppGenerator.WEB_APP_BRIDGE_NAME)

        // 把 Tools 实例化并逐个注册为独立 bridge。
        // runtime guard 的 JS 会在页面里把它们挂到 window.MantouApp.<toolName> 下，
        // 让生成网页可以写 `window.MantouApp.toast.toastShow(...)`。
        ToolRegistry.init(webView.context)
        for (tool in ToolRegistry.instances()) {
            webView.addJavascriptInterface(tool, TOOL_BRIDGE_PREFIX + tool.toolName)
        }
        webView.addJavascriptInterface(StorageTool(webView.context, htmlFile), TOOL_BRIDGE_PREFIX + STORAGE_TOOL_NAME)
    }

    private object MainBridge {
        @JavascriptInterface
        fun isMantouApp(): Boolean = true

        /**
         * 返回 JSON 数组形式的所有 toolName，例如 `["alarm","calendar","toast"]`。
         * runtime guard JS 用它来构建 `window.MantouApp.<toolName>` 别名表。
         */
        @JavascriptInterface
        fun getToolNames(): String {
            val arr = JSONArray()
            for (tool in ToolRegistry.instances()) arr.put(tool.toolName)
            arr.put(STORAGE_TOOL_NAME)
            return arr.toString()
        }
    }

    @MantouTool(
        name = STORAGE_TOOL_NAME,
        description = "读取和写入当前网页 App 专属的 JSON 数据文件",
        usageScenario = "待办、笔记、设置、历史记录、游戏进度、统计数据等需要下次打开仍保留的数据"
    )
    private class StorageTool(
        context: android.content.Context,
        htmlFile: File?
    ) : BaseTool(context.applicationContext) {

        private val dataFile: File? = htmlFile?.let { AppGenerator.ensureWebAppDataFile(it) }

        @JavascriptInterface
        @ToolMethod(
            description = "读取当前网页 App 的完整 JSON 数据内容。首次使用时返回空对象字符串 {}。",
            example = "var r = JSON.parse(window.MantouApp.storage.storageRead()); var state = r.success ? JSON.parse(r.data.content || '{}') : {};"
        )
        @ToolReturns(
            description = "JSON 文件内容",
            jsonExample = "{\"success\": true, \"data\": {\"content\": \"{}\"}, \"error\": null}"
        )
        fun storageRead(): String {
            val file = dataFile ?: return error("当前网页未绑定数据文件")
            return runCatching {
                ensureDataFile(file)
                val content = file.readText().ifBlank { "{}" }
                parseJsonValue(content)
                success("content" to content)
            }.getOrElse { e ->
                error("读取数据失败：${e.message ?: e::class.java.simpleName}")
            }
        }

        @JavascriptInterface
        @ToolMethod(
            description = "写入当前网页 App 的完整 JSON 数据内容。jsonContent 必须是合法 JSON 字符串。",
            example = "window.MantouApp.storage.storageWrite(JSON.stringify(state));"
        )
        @ToolReturns(
            description = "写入的字节数",
            jsonExample = "{\"success\": true, \"data\": {\"bytes\": 12}, \"error\": null}"
        )
        fun storageWrite(
            @ToolParam(name = "jsonContent", description = "完整 JSON 内容字符串，通常由 JSON.stringify(state) 生成") jsonContent: String
        ): String {
            val file = dataFile ?: return error("当前网页未绑定数据文件")
            return runCatching {
                parseJsonValue(jsonContent)
                file.parentFile?.mkdirs()
                file.writeText(jsonContent)
                success("bytes" to jsonContent.toByteArray(Charsets.UTF_8).size)
            }.getOrElse { e ->
                error("写入数据失败：${e.message ?: e::class.java.simpleName}")
            }
        }

        @JavascriptInterface
        @ToolMethod(
            description = "从根 JSON 对象中读取一个字段。",
            example = "var r = JSON.parse(window.MantouApp.storage.storageGet('todos')); var todos = r.success && r.data.exists ? JSON.parse(r.data.valueJson) : [];"
        )
        @ToolReturns(
            description = "字段值的 JSON 字符串；字段不存在时 exists=false",
            jsonExample = "{\"success\": true, \"data\": {\"exists\": true, \"valueJson\": \"[]\"}, \"error\": null}"
        )
        fun storageGet(
            @ToolParam(name = "key", description = "根对象字段名") key: String
        ): String {
            if (key.isBlank()) return error("key 不能为空")
            return runCatching {
                val root = readRootObject()
                if (!root.has(key)) {
                    success("exists" to false, "valueJson" to "null")
                } else {
                    success("exists" to true, "valueJson" to jsonValueToString(root.get(key)))
                }
            }.getOrElse { e ->
                error("读取字段失败：${e.message ?: e::class.java.simpleName}")
            }
        }

        @JavascriptInterface
        @ToolMethod(
            description = "把一个字段写入根 JSON 对象。valueJson 必须是合法 JSON 值，例如字符串、数字、数组或对象。",
            example = "window.MantouApp.storage.storageSet('todos', JSON.stringify(todos));"
        )
        @ToolReturns(
            description = "是否成功写入字段",
            jsonExample = "{\"success\": true, \"data\": {\"key\": \"todos\"}, \"error\": null}"
        )
        fun storageSet(
            @ToolParam(name = "key", description = "根对象字段名") key: String,
            @ToolParam(name = "valueJson", description = "字段值的 JSON 字符串") valueJson: String
        ): String {
            if (key.isBlank()) return error("key 不能为空")
            val file = dataFile ?: return error("当前网页未绑定数据文件")
            return runCatching {
                val root = readRootObject()
                root.put(key, parseJsonValue(valueJson))
                file.writeText(root.toString())
                success("key" to key)
            }.getOrElse { e ->
                error("写入字段失败：${e.message ?: e::class.java.simpleName}")
            }
        }

        @JavascriptInterface
        @ToolMethod(
            description = "从根 JSON 对象中删除一个字段。",
            example = "window.MantouApp.storage.storageRemove('draft');"
        )
        @ToolReturns(
            description = "是否删除过该字段",
            jsonExample = "{\"success\": true, \"data\": {\"removed\": true}, \"error\": null}"
        )
        fun storageRemove(
            @ToolParam(name = "key", description = "根对象字段名") key: String
        ): String {
            if (key.isBlank()) return error("key 不能为空")
            val file = dataFile ?: return error("当前网页未绑定数据文件")
            return runCatching {
                val root = readRootObject()
                val existed = root.has(key)
                root.remove(key)
                file.writeText(root.toString())
                success("removed" to existed)
            }.getOrElse { e ->
                error("删除字段失败：${e.message ?: e::class.java.simpleName}")
            }
        }

        @JavascriptInterface
        @ToolMethod(
            description = "清空当前网页 App 的 JSON 数据文件，重置为 {}。",
            example = "window.MantouApp.storage.storageClear();"
        )
        @ToolReturns(
            description = "是否成功清空",
            jsonExample = "{\"success\": true, \"data\": {\"cleared\": true}, \"error\": null}"
        )
        fun storageClear(): String {
            val file = dataFile ?: return error("当前网页未绑定数据文件")
            return runCatching {
                file.parentFile?.mkdirs()
                file.writeText("{}")
                success("cleared" to true)
            }.getOrElse { e ->
                error("清空数据失败：${e.message ?: e::class.java.simpleName}")
            }
        }

        private fun readRootObject(): JSONObject {
            val file = dataFile ?: throw IllegalStateException("当前网页未绑定数据文件")
            ensureDataFile(file)
            val content = file.readText().ifBlank { "{}" }
            val value = parseJsonValue(content)
            return value as? JSONObject ?: throw IllegalStateException("数据文件根节点必须是 JSON 对象")
        }

        private fun ensureDataFile(file: File) {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText("{}")
            }
        }

        private fun parseJsonValue(text: String): Any {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("JSON 内容不能为空")
            val tokener = JSONTokener(trimmed)
            val value = tokener.nextValue()
            if (tokener.more()) {
                throw IllegalArgumentException("JSON 内容包含多余字符")
            }
            return value
        }

        private fun jsonValueToString(value: Any?): String {
            return when (value) {
                null, JSONObject.NULL -> "null"
                is JSONObject, is JSONArray -> value.toString()
                is String -> JSONObject.quote(value)
                is Number, is Boolean -> value.toString()
                else -> JSONObject.quote(value.toString())
            }
        }
    }
}
