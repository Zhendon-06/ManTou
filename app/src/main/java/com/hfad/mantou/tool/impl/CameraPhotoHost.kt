package com.hfad.mantou.tool.impl

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraPhotoHost(
    private val activity: FragmentActivity,
    private val takePictureLauncher: ActivityResultLauncher<Uri>,
    private val requestPermissionLauncher: ActivityResultLauncher<String>,
    private val webViewProvider: () -> WebView?
) {
    private var pendingCameraPhotoFile: File? = null
    private var pendingCameraPhotoCallback: String? = null

    fun requestCameraPhoto(callbackName: String?) {
        activity.runOnUiThread {
            pendingCameraPhotoCallback = callbackName?.trim()?.takeIf { it.isNotBlank() }
            pendingCameraPhotoFile = createCameraPhotoFile()
            if (pendingCameraPhotoFile == null) {
                Toast.makeText(activity, "创建照片文件失败", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }

            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCameraForPendingRequest()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (granted) {
            launchCameraForPendingRequest()
        } else {
            Toast.makeText(activity, "未授予相机权限，无法拍照", Toast.LENGTH_SHORT).show()
            clearPendingRequest()
        }
    }

    fun onCameraPhotoResult(success: Boolean) {
        val file = pendingCameraPhotoFile
        val callbackName = pendingCameraPhotoCallback
        clearPendingRequest()

        if (!success || file == null || !file.exists() || file.length() <= 0L) {
            Toast.makeText(activity, "未获取到照片", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file
        ).toString()

        val dataUrl = runCatching {
            val jpegBytes = decodePreviewJpeg(file)
            val base64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        }.getOrElse {
            Toast.makeText(activity, "读取照片失败: ${it.message}", Toast.LENGTH_SHORT).show()
            return
        }

        dispatchCameraPhotoToWeb(callbackName, dataUrl, uri)
    }

    private fun launchCameraForPendingRequest() {
        val file = pendingCameraPhotoFile
        if (file == null) {
            Toast.makeText(activity, "照片文件未准备好", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file
        )
        runCatching {
            takePictureLauncher.launch(uri)
        }.onFailure {
            Toast.makeText(activity, "找不到系统相机", Toast.LENGTH_SHORT).show()
            clearPendingRequest()
        }
    }

    private fun dispatchCameraPhotoToWeb(callbackName: String?, dataUrl: String, uri: String) {
        val webView = webViewProvider()
        if (webView == null) {
            Toast.makeText(activity, "当前页面不可接收照片", Toast.LENGTH_SHORT).show()
            return
        }
        val callbackPath = callbackName?.takeIf { it.matches(CALLBACK_NAME_REGEX) }
            ?: DEFAULT_CAMERA_PHOTO_CALLBACK
        val script = """
            (function () {
                var dataUrl = ${JSONObject.quote(dataUrl)};
                var uri = ${JSONObject.quote(uri)};
                var callbackPath = ${JSONObject.quote(callbackPath)};
                var callback = callbackPath.split(".").reduce(function (target, key) {
                    return target && target[key];
                }, window);
                if (typeof callback === "function") {
                    try {
                        callback(dataUrl, uri);
                    } catch (error) {}
                }
                window.dispatchEvent(new CustomEvent("mantou-camera-photo", {
                    detail: { dataUrl: dataUrl, uri: uri }
                }));
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun createCameraPhotoFile(): File? {
        return runCatching {
            val dir = File(activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "camera_tool").apply {
                mkdirs()
            }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            File(dir, "photo_$timestamp.jpg")
        }.getOrNull()
    }

    private fun decodePreviewJpeg(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, CAMERA_PREVIEW_MAX_SIZE)
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: throw IllegalStateException("无法解码照片")

        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, CAMERA_PREVIEW_JPEG_QUALITY, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sampleSize = 1
        var scaledWidth = width
        var scaledHeight = height
        while (scaledWidth / 2 >= maxSize || scaledHeight / 2 >= maxSize) {
            sampleSize *= 2
            scaledWidth /= 2
            scaledHeight /= 2
        }
        return sampleSize
    }

    private fun clearPendingRequest() {
        pendingCameraPhotoFile = null
        pendingCameraPhotoCallback = null
    }

    private companion object {
        private const val DEFAULT_CAMERA_PHOTO_CALLBACK = "window.MantouApp.onCameraPhoto"
        private const val CAMERA_PREVIEW_MAX_SIZE = 1600
        private const val CAMERA_PREVIEW_JPEG_QUALITY = 88
        private val CALLBACK_NAME_REGEX = Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*""")
    }
}
