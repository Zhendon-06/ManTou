package com.hfad.mantou.utils.linux

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.hfad.mantou.utils.project.WebProjectPaths

internal const val RUN_LINUX_TOOL = "run_linux"
internal const val LINUX_OUTPUT_BYTES = 8 * 1024 * 1024

internal data class LinuxWorkspaceJob(
    val language: String,
    val script: String,
    val inputs: List<String>,
    val output: String?
) {
    companion object {
        fun parse(body: String): LinuxWorkspaceJob {
            require(body.length <= 96 * 1024) { "Linux 任务请求过大" }
            val objectValue = JsonParser.parseString(body).asJsonObject
            fun string(value: JsonElement?): String {
                require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) {
                    "Linux 任务参数必须是字符串"
                }
                return value.asString
            }
            require(objectValue.keySet() == setOf("language", "script", "inputs", "output")) {
                "Linux 任务需要 language、script、inputs 和 output"
            }
            val language = string(objectValue["language"])
            require(language == "python" || language == "shell") { "脚本语言应为 python 或 shell" }
            val script = string(objectValue["script"])
            require(script.isNotBlank() && script.toByteArray().size <= 32 * 1024 && '\u0000' !in script) {
                "脚本不能为空或超过 32 KiB"
            }
            val inputs = objectValue.getAsJsonArray("inputs")
            require(inputs.size() <= 32) { "每次最多传入 32 个文件" }
            val inputPaths = inputs.map { path(string(it)) }
            require(inputPaths.distinct().size == inputPaths.size) { "输入文件路径重复" }
            val output = objectValue["output"].takeUnless(JsonElement::isJsonNull)?.let { path(string(it)) }
            return LinuxWorkspaceJob(language, script, inputPaths, output)
        }

        private fun path(raw: String): String {
            require(raw.length <= 512 && raw.none { it.isISOControl() }) { "Linux 文件路径无效" }
            val normalized = WebProjectPaths.normalizeRelativePath(raw)
            require(normalized == raw && normalized.split('/').none { it.startsWith('.') }) {
                "Linux 文件路径必须是工作区内的普通相对路径"
            }
            return normalized
        }
    }
}

internal object LinuxWorkspaceSettings {
    private fun preferences(context: Context) = context.getSharedPreferences(
        "linux_workspace", Context.MODE_PRIVATE
    )

    fun enabled(context: Context): Boolean = preferences(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("enabled", enabled).apply()
    }
}
