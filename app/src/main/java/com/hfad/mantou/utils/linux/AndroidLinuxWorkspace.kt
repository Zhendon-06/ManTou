package com.hfad.mantou.utils.linux

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Base64
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hfad.mantou.utils.project.WebProjectPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.SecureRandom

internal data class LinuxWorkspaceResult(val exitCode: Int, val log: String, val output: ByteArray?)

internal object AndroidLinuxWorkspace {
    private val guestLock = Mutex()
    private val origin = Uri.parse("https://mantou-linux.invalid")
    private val assetTypes = mapOf(
        "/index.html" to "text/html",
        "/machine.js" to "application/javascript",
        "/bin/libv86.js" to "application/javascript",
        "/bin/v86.wasm" to "application/wasm",
        "/bin/v86-fallback.wasm" to "application/wasm",
        "/bin/seabios.bin" to "application/octet-stream",
        "/bin/vgabios.bin" to "application/octet-stream",
        "/bin/state.bin" to "application/octet-stream"
    )
    private const val JOB_PATH = "/job.json"
    private const val MAX_REPLY_CHARS = 12 * 1024 * 1024

    fun isAvailable(context: Context): Boolean = runCatching {
        assetTypes.keys.all { path -> context.assets.open("linux-runtime$path").use { true } }
    }.getOrDefault(false)

    suspend fun run(context: Context, root: File, job: LinuxWorkspaceJob): LinuxWorkspaceResult {
        require(isAvailable(context)) { "此版本未安装 Linux 工作区运行环境" }
        val result = guestLock.withLock {
            val request = withContext(Dispatchers.IO) { encodeJob(root, job) }
            withTimeoutOrNull(90_000) {
                withContext(Dispatchers.Main.immediate) { runWebView(context.applicationContext, request) }
            } ?: return LinuxWorkspaceResult(124, "Linux 启动或任务执行超时", null)
        }
        return withContext(Dispatchers.Default) {
            val response = JsonParser.parseString(result).asJsonObject
            val exitCode = response["exitCode"].asInt
            val log = response["log"].asString.take(8192).ifBlank {
                if (exitCode == 124) "脚本执行超时" else ""
            }
            val output = response["output"].takeUnless { it.isJsonNull }?.asString?.let {
                Base64.decode(it, Base64.NO_WRAP).also { bytes ->
                    require(bytes.size <= LINUX_OUTPUT_BYTES) { "Linux 输出文件超过 8 MiB" }
                }
            }
            LinuxWorkspaceResult(exitCode, log, output)
        }
    }

    private fun encodeJob(root: File, job: LinuxWorkspaceJob): ByteArray {
        var remaining = LINUX_OUTPUT_BYTES
        val inputs = job.inputs.map { path ->
            val file = WebProjectPaths.resolve(root, path).toPath()
            require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "找不到输入文件：$path" }
            require(Files.size(file) <= remaining) { "Linux 单次执行的输入总计不能超过 8 MiB" }
            val data = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use {
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = it.read(chunk)
                    if (count < 0) break
                    remaining -= count
                    require(remaining >= 0) { "Linux 单次执行的输入总计不能超过 8 MiB" }
                    buffer.write(chunk, 0, count)
                }
                buffer.toByteArray()
            }
            mapOf("path" to path, "data" to Base64.encodeToString(data, Base64.NO_WRAP))
        }
        return Gson().newBuilder().serializeNulls().create().toJson(
            mapOf(
                "language" to job.language, "script" to job.script, "inputs" to inputs,
                "output" to job.output, "now" to System.currentTimeMillis() / 1000,
                "entropy" to Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), Base64.NO_WRAP)
            )
        ).toByteArray(Charsets.UTF_8)
    }

    @Suppress("SetJavaScriptEnabled")
    private suspend fun runWebView(context: Context, job: ByteArray): String {
        val result = CompletableDeferred<String>()
        val entry = "$origin/index.html"
        val browser = WebView(context)
        var received = StringBuilder()
        var port: WebMessagePort? = null
        try {
            browser.settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                domStorageEnabled = false
                blockNetworkLoads = true
            }
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val url = request.url
                    if (request.method == "GET" && url.scheme == origin.scheme &&
                        url.encodedAuthority == origin.encodedAuthority && url.query == null
                    ) {
                        val path = url.encodedPath
                        if (path == JOB_PATH && !request.isForMainFrame) {
                            return WebResourceResponse(
                                "application/json", "UTF-8", 200, "OK",
                                mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(job)
                            )
                        }
                        assetTypes[path]?.let { type ->
                            return WebResourceResponse(type, null, context.assets.open("linux-runtime$path"))
                        }
                    }
                    return WebResourceResponse(
                        "text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf())
                    )
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (url != entry || port != null || result.isCompleted) return
                    val ports = view.createWebMessageChannel()
                    port = ports[0]
                    ports[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                        override fun onMessage(port: WebMessagePort, message: WebMessage) {
                            val data = message.data.orEmpty()
                            if (result.isCompleted) return
                            when {
                                data == "done" -> result.complete(received.toString())
                                data.startsWith("!") -> result.completeExceptionally(
                                    IllegalStateException(data.drop(1).take(400))
                                )
                                data.startsWith("+") && received.length + data.length <= MAX_REPLY_CHARS ->
                                    received.append(data, 1, data.length)
                                else -> result.completeExceptionally(IllegalStateException("Linux 返回数据无效"))
                            }
                        }
                    })
                    val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    view.postWebMessage(WebMessage(if (debug) "run-debug" else "run", arrayOf(ports[1])), origin)
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    result.completeExceptionally(IllegalStateException("Linux 运行进程退出"))
                    return true
                }
            }
            browser.loadUrl(entry)
            return result.await()
        } finally {
            runCatching { port?.close() }
            received = StringBuilder()
            browser.stopLoading()
            browser.destroy()
        }
    }
}
