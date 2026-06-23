package com.hfad.mantou.view

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.hfad.mantou.databinding.VirtualappBinding
import com.hfad.mantou.tool.impl.CameraPhotoBridge
import com.hfad.mantou.tool.impl.CameraPhotoHost
import com.hfad.mantou.utils.AgentWorkspace
import com.hfad.mantou.utils.AppGenerator
import com.hfad.mantou.utils.MantouWebViewRuntime
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VirtualAppActivity : AppCompatActivity(), CameraPhotoBridge.Host {

    private lateinit var binding: VirtualappBinding
    private lateinit var webView: WebView
    private lateinit var cameraPhotoHost: CameraPhotoHost
    private var currentHtmlPath: String? = null

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraPhotoHost.onCameraPhotoResult(success)
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPhotoHost.onCameraPermissionResult(granted)
    }

    companion object {
        const val EXTRA_HTML_PATH = "html_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = VirtualappBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CameraPhotoBridge.attach(this)
        cameraPhotoHost = CameraPhotoHost(
            activity = this,
            takePictureLauncher = takePictureLauncher,
            requestPermissionLauncher = requestCameraPermissionLauncher,
            webViewProvider = { if (::webView.isInitialized) webView else null }
        )
        webView = WebView(this).also { view ->
            binding.webViewHost.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        val decorView = window.decorView
        val flags = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        decorView.systemUiVisibility = flags

        val htmlPath = intent.getStringExtra(EXTRA_HTML_PATH)

        webView.apply {
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
        }

        currentHtmlPath = (htmlPath ?: resolveSharedHtmlPath(intent))?.let { path ->
            ensureStoredWebAppIdentity(File(path), "打开准备失败")?.absolutePath
        }
        if (!currentHtmlPath.isNullOrEmpty()) {
            MantouWebViewRuntime.install(webView, File(currentHtmlPath!!))
            webView.loadUrl("file://$currentHtmlPath")
        } else {
            Toast.makeText(this, "没有可打开的网页应用", Toast.LENGTH_SHORT).show()
        }

        binding.btnSmallscreen.setOnClickListener {
            finish()
        }

        binding.btnShare.setOnClickListener {
            shareCurrentWebApp()
        }
    }

    override fun requestCameraPhoto(callbackName: String?) {
        cameraPhotoHost.requestCameraPhoto(callbackName)
    }

    private fun resolveSharedHtmlPath(intent: Intent): String? {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            else -> null
        } ?: return null

        return copySharedHtmlToLocal(uri)
    }

    private fun copySharedHtmlToLocal(uri: Uri): String? {
        AgentWorkspace.ensureWorkspace(this)
        val appDir = File(filesDir, AgentWorkspace.WEB_DIR).apply {
            if (!exists()) mkdirs()
        }
        val displayName = queryDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
        if (!isProbablyHtml(uri, displayName)) {
            Toast.makeText(this, "仅支持打开 HTML 网页应用", Toast.LENGTH_SHORT).show()
            return null
        }
        val fileName = sanitizeHtmlFileName(displayName)

        return runCatching {
            val htmlBytes = contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: error("无法读取文件")
            val htmlText = String(htmlBytes, Charsets.UTF_8)

            findExistingHtmlFile(appDir, fileName, htmlText, htmlBytes)?.let { existingFile ->
                Toast.makeText(this, "已存在相同网页应用，直接打开", Toast.LENGTH_SHORT).show()
                return@runCatching existingFile.absolutePath
            }

            val target = nextAvailableFile(appDir, fileName)
            target.parentFile?.mkdirs()
            target.writeText(AppGenerator.ensureWebAppIdentity(htmlText))
            AppGenerator.ensureWebAppDataFile(target)
            target.absolutePath
        }.getOrElse {
            Toast.makeText(this, "导入失败: ${it.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun shareCurrentWebApp() {
        val htmlPath = currentHtmlPath
        if (htmlPath.isNullOrEmpty()) {
            Toast.makeText(this, "没有可分享的网页应用", Toast.LENGTH_SHORT).show()
            return
        }

        val htmlFile = File(htmlPath)
        if (!htmlFile.exists()) {
            Toast.makeText(this, "文件不存在，无法分享", Toast.LENGTH_SHORT).show()
            return
        }

        val shareFile = ensureStoredWebAppIdentity(htmlFile, "分享准备失败") ?: return
        val htmlUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            shareFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, htmlUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(shareIntent, "分享网页应用"))
    }

    private fun ensureStoredWebAppIdentity(file: File, failurePrefix: String): File? {
        return runCatching {
            val htmlContent = file.readText()
            val identifiedContent = AppGenerator.ensureWebAppIdentity(htmlContent)
            if (identifiedContent != htmlContent) {
                file.writeText(identifiedContent)
            }
            AppGenerator.ensureWebAppDataFile(file)
            file
        }.getOrElse {
            Toast.makeText(this, "$failurePrefix: ${it.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }

    private fun isProbablyHtml(uri: Uri, displayName: String?): Boolean {
        if (displayName?.endsWith(".html", true) == true || displayName?.endsWith(".htm", true) == true) {
            return true
        }
        val mimeType = contentResolver.getType(uri)
        return mimeType?.contains("html", ignoreCase = true) == true
    }

    private fun sanitizeHtmlFileName(displayName: String?): String {
        val fallback = "shared_webapp_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.html"
        val baseName = displayName
            ?.takeIf { it.isNotBlank() }
            ?: fallback
        val sanitized = baseName
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .trim('_', '-', '.', ' ')
            .ifBlank { fallback }

        return if (sanitized.endsWith(".html", true) || sanitized.endsWith(".htm", true)) {
            sanitized
        } else {
            "$sanitized.html"
        }
    }

    private fun nextAvailableFile(dir: File, fileName: String): File {
        val stem = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "html")
        var projectDir = File(dir, stem)
        var index = 2
        while (projectDir.exists()) {
            projectDir = File(dir, "${stem}_$index")
            index++
        }
        return File(projectDir, "$stem.$extension")
    }

    private fun findExistingHtmlFile(
        dir: File,
        fileName: String,
        htmlText: String,
        htmlBytes: ByteArray
    ): File? {
        val htmlFiles = listHtmlFiles(dir)

        AppGenerator.extractWebAppIdentity(htmlText)?.let { incomingId ->
            htmlFiles.firstOrNull { file ->
                runCatching {
                    AppGenerator.extractWebAppIdentity(file.readText()) == incomingId
                }.getOrDefault(false)
            }?.let { return it }
        }

        val incomingHash = sha256(htmlBytes)
        htmlFiles
            .filter { file -> file.length() == htmlBytes.size.toLong() }
            .firstOrNull { file ->
                runCatching {
                    sha256(file.readBytes()).contentEquals(incomingHash)
                }.getOrDefault(false)
            }?.let { return it }

        val normalizedIncomingHash = sha256(normalizeHtmlForCompare(htmlText).toByteArray(Charsets.UTF_8))
        htmlFiles.firstOrNull { file ->
            runCatching {
                val normalizedFileHash = sha256(normalizeHtmlForCompare(file.readText()).toByteArray(Charsets.UTF_8))
                normalizedFileHash.contentEquals(normalizedIncomingHash)
            }.getOrDefault(false)
        }?.let { return it }

        return File(dir, fileName).takeIf { file ->
            isHtmlFile(file)
        }
    }

    private fun listHtmlFiles(dir: File): List<File> {
        return dir.walkTopDown()
            .filter(::isHtmlFile)
            .toList()
    }

    private fun isHtmlFile(file: File): Boolean {
        return file.isFile &&
                (file.extension.equals("html", true) || file.extension.equals("htm", true))
    }

    private fun normalizeHtmlForCompare(htmlContent: String): String {
        return htmlContent
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    private fun sha256(bytes: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    override fun onDestroy() {
        CameraPhotoBridge.detach(this)
        webView.apply {
            stopLoading()
            settings.javaScriptEnabled = false
            loadUrl("about:blank")
            destroy()
        }
        super.onDestroy()
    }
}
