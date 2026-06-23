package com.hfad.mantou.utils

import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.data.database.ChatMessageEntity
import java.io.File

object ChatContextFormatter {
    private const val GENERATED_APP_HEADER = "当前生成的网页应用 HTML 源码"
    private val htmlCache = mutableMapOf<String, CachedHtml>()

    fun contentForContext(message: ChatMessage): String {
        return contentForContext(message.content, message.appHtmlPath)
    }

    fun contentForContext(message: ChatMessageEntity): String {
        return contentForContext(message.content, message.appHtmlPath)
    }

    fun contentForContext(content: String, appHtmlPath: String?): String {
        val html = readHtml(appHtmlPath) ?: return content
        return buildString {
            append(content.trimEnd())
            if (isNotEmpty()) append("\n\n")
            append("[$GENERATED_APP_HEADER]\n")
            append("后续用户如果要求修改这个应用，请基于下面完整源码继续输出更新后的完整 HTML。\n")
            append("```html\n")
            append(html)
            if (!html.endsWith("\n")) append('\n')
            append("```")
        }
    }

    private fun readHtml(appHtmlPath: String?): String? {
        if (appHtmlPath.isNullOrBlank()) return null

        val file = File(appHtmlPath)
        if (!file.isFile) return null

        val key = file.absolutePath
        val lastModified = file.lastModified()
        val length = file.length()

        synchronized(htmlCache) {
            htmlCache[key]
                ?.takeIf { it.lastModified == lastModified && it.length == length }
                ?.let { return it.content }
        }

        return runCatching { file.readText() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.also { html ->
                synchronized(htmlCache) {
                    htmlCache[key] = CachedHtml(
                        lastModified = lastModified,
                        length = length,
                        content = html
                    )
                }
            }
    }

    private data class CachedHtml(
        val lastModified: Long,
        val length: Long,
        val content: String
    )
}
