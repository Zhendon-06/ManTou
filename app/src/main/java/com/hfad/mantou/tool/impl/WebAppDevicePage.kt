package com.hfad.mantou.tool.impl

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class WebAppDevicePage(
    private val view: WebView,
    val host: WebAppDeviceHost,
    val media: WebAppMedia,
    val appKey: String,
    val displayName: String,
    val openTarget: File,
    entryUrl: String
) {
    val context = view.context.applicationContext
    private val entry = Uri.parse(entryUrl)

    @Volatile private var document = Document(allowed = false)
    @Volatile private var closed = false

    fun onPageStarted(url: String) {
        document = Document(allows(url))
        host.interruptAudio(this)
    }

    fun close() {
        closed = true
        host.interruptAudio(this)
    }

    fun submit(event: String, start: (Request) -> Unit): String {
        val current = document
        if (closed || !current.allowed) return response(null, "当前页面无法使用此功能").toString()

        val request = Request(this, current, event, UUID.randomUUID().toString())
        mainHandler.post {
            if (!request.isCurrent) return@post
            if (!request.canStart) {
                request.fail("请在页面可见时使用此功能")
            } else {
                start(request)
            }
        }
        return response(JSONObject().put("requestId", request.id), null).toString()
    }

    fun deleteMedia(id: String, recording: Boolean): String {
        if (closed || !document.allowed) return response(null, "当前页面无法使用此功能").toString()
        return runCatching {
            response(JSONObject().put("removed", media.delete(id, recording)), null)
        }.getOrElse { response(null, "无法删除此文件，请检查文件编号") }.toString()
    }

    private fun allows(url: String?): Boolean {
        val uri = url?.let(Uri::parse) ?: return false
        if (uri.scheme != entry.scheme) return false
        return if (entry.scheme == "https") uri.host == entry.host && uri.port in setOf(-1, 443)
        else uri.authority == entry.authority && uri.path == entry.path
    }

    internal class Document(val allowed: Boolean)

    internal class Request(
        val page: WebAppDevicePage,
        private val document: Document,
        private val event: String,
        val id: String
    ) {
        val isCurrent: Boolean
            get() = !page.closed && page.document === document && page.view.isAttachedToWindow && page.allows(page.view.url)

        val canStart: Boolean
            get() = isCurrent && page.view.isShown && page.host.isResumed

        fun succeed(data: JSONObject): Boolean = deliver(data, null)

        fun succeed(vararg data: Pair<String, Any>): Boolean =
            succeed(JSONObject().apply { data.forEach { (key, value) -> put(key, value) } })

        fun fail(message: String) = deliver(null, message)

        private fun deliver(data: JSONObject?, error: String?): Boolean {
            if (!isCurrent) return false
            val detail = JSONObject.quote(response(data, error).put("requestId", id).toString())
            page.view.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('mantou:$event', {detail: JSON.parse($detail)}));",
                null
            )
            return true
        }
    }

    private companion object {
        val mainHandler = Handler(Looper.getMainLooper())

        fun response(data: JSONObject?, error: String?): JSONObject = JSONObject()
            .put("success", error == null)
            .put("data", data ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
    }
}
