package com.hfad.mantou.tool.impl

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.UUID

internal class WebAppMedia(
    private val directory: File,
    private val host: String,
    private val sameOrigin: Boolean
) {
    fun newFile(extension: String): File {
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
        return File(directory, "${UUID.randomUUID()}.$extension")
    }

    fun describe(file: File): JSONObject = JSONObject()
        .put("id", file.name)
        .put("url", "https://$host$MEDIA_PATH${file.name}")
        .put("mimeType", mimeTypes.getValue(file.extension))

    fun delete(id: String, recording: Boolean): Boolean {
        require(isMediaId(id) && (id.endsWith(".m4a") == recording))
        val file = File(directory, id)
        if (!file.exists()) return false
        check(file.delete())
        return true
    }

    fun importPhotos(resolver: ContentResolver, uris: List<Uri>): List<File> {
        val files = mutableListOf<File>()
        try {
            for (uri in uris) {
                val mimeType = resolver.getType(uri)
                val convert = mimeType in heifMimeTypes && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                val extension = if (convert) "jpg" else mimeTypes.entries
                    .firstOrNull { it.value == mimeType && it.key != "m4a" }?.key
                    ?: error("此照片格式暂不支持，请选择 JPEG、PNG、WebP、GIF 或 AVIF")
                val file = newFile(extension)
                files += file
                if (convert && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val original = File.createTempFile("import-", ".heic", directory)
                    try {
                        copyPhoto(resolver, uri, original)
                        convertHeifToJpeg(original, file)
                    } finally {
                        original.delete()
                    }
                } else {
                    copyPhoto(resolver, uri, file)
                }
            }
            return files
        } catch (error: Exception) {
            files.forEach(File::delete)
            throw error
        }
    }

    private fun copyPhoto(resolver: ContentResolver, uri: Uri, file: File) {
        val input = resolver.openInputStream(uri) ?: error("无法打开所选照片")
        input.use { source ->
            file.outputStream().use { target ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var size = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    size += count
                    check(size <= MAX_PHOTO_BYTES) { "单张照片不能超过 32 MB" }
                    target.write(buffer, 0, count)
                }
                check(size > 0) { "所选照片文件为空" }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun convertHeifToJpeg(original: File, target: File) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(original)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (width, height) = info.size.let { it.width to it.height }
            val largestSide = maxOf(width, height)
            if (largestSide > HEIF_MAX_SIDE) {
                decoder.setTargetSize(
                    (width.toLong() * HEIF_MAX_SIDE / largestSide).toInt().coerceAtLeast(1),
                    (height.toLong() * HEIF_MAX_SIDE / largestSide).toInt().coerceAtLeast(1)
                )
            }
        }
        try {
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    fun photoResults(files: List<File>): JSONArray = JSONArray().also { results ->
        files.forEach { results.put(describe(it)) }
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val path = uri.encodedPath.orEmpty()
        if (uri.scheme != "https" || uri.host != host || !path.startsWith(MEDIA_PATH)) return null

        val id = path.removePrefix(MEDIA_PATH)
        if (request.isForMainFrame || request.method !in setOf("GET", "HEAD") || !isMediaId(id)) {
            return emptyResponse(404, "Not Found")
        }
        val file = File(directory, id)
        if (!file.isFile || file.length() == 0L) return emptyResponse(404, "Not Found")
        return runCatching { serve(file, request) }.getOrElse { emptyResponse(404, "Not Found") }
    }

    private fun serve(file: File, request: WebResourceRequest): WebResourceResponse {
        val size = file.length()
        val header = request.requestHeaders.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
        val range = if (header == null) 0L until size else parseMediaRange(header, size)
            ?: return emptyResponse(416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$size"))
        val partial = header != null
        val headers = mutableMapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Accept-Ranges" to "bytes"
        )
        if (sameOrigin) headers["Cross-Origin-Resource-Policy"] = "same-origin"
        if (partial) headers["Content-Range"] = "bytes ${range.first}-${range.last}/$size"
        // WebView calculates Content-Length and skips to the requested start on the original stream.
        val stream = MediaStream(file, range.last + 1, headOnly = request.method == "HEAD")
        return WebResourceResponse(
            mimeTypes.getValue(file.extension), null, if (partial) 206 else 200,
            if (partial) "Partial Content" else "OK", headers, stream
        )
    }

    private class MediaStream(file: File, private val limit: Long, private val headOnly: Boolean) : FileInputStream(file) {

        override fun read(): Int {
            if (headOnly || channel.position() >= limit) return -1
            return super.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val remaining = limit - channel.position()
            if (headOnly || remaining <= 0L) return -1
            return super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        }
    }

    private fun emptyResponse(code: Int, message: String, headers: Map<String, String> = emptyMap()) =
        WebResourceResponse("text/plain", "utf-8", code, message, headers, ByteArrayInputStream(ByteArray(0)))

    private fun isMediaId(id: String): Boolean = MEDIA_ID.matches(id) && id.substringAfterLast('.') in mimeTypes

    private companion object {
        const val MEDIA_PATH = "/.mantou/media/"
        const val MAX_PHOTO_BYTES = 32L * 1024 * 1024
        const val HEIF_MAX_SIDE = 2_048
        val MEDIA_ID = Regex("[0-9a-f-]{36}\\.[a-z0-9]+")
        val heifMimeTypes = setOf("image/heic", "image/heif")
        val mimeTypes = mapOf(
            "jpg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
            "gif" to "image/gif", "avif" to "image/avif", "m4a" to "audio/mp4"
        )
    }
}

private val RANGE_HEADER = Regex("bytes=(\\d*)-(\\d*)")

internal fun parseMediaRange(header: String, size: Long): LongRange? {
    if (size <= 0) return null
    val (start, end) = RANGE_HEADER.matchEntire(header.trim())?.destructured ?: return null
    if (start.isEmpty()) {
        val length = end.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return (size - minOf(length, size)) until size
    }
    val first = start.toLongOrNull()?.takeIf { it < size } ?: return null
    val last = if (end.isEmpty()) size - 1 else end.toLongOrNull() ?: return null
    return (first..minOf(last, size - 1)).takeUnless(LongRange::isEmpty)
}
