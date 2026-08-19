package com.hfad.mantou.utils

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.hfad.mantou.data.logging.HarnessTraceLogger
import com.hfad.mantou.data.logging.HarnessTraceStatus
import com.hfad.mantou.data.logging.elapsedMillisSince
import com.hfad.mantou.data.logging.record
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

class WebProjectContentServer private constructor(
    val projectRoot: File,
    val entryFile: File,
    val revision: String,
    val originHost: String,
    val files: List<ProjectFile>,
    private val htmlTransformer: ((String) -> String)?,
    private val traceRunId: String,
    private val traceIteration: Int?,
    private val traceLogger: HarnessTraceLogger
) {

    data class Limits(
        val maxFiles: Int = DEFAULT_MAX_FILES,
        val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
        val maxProjectBytes: Long = DEFAULT_MAX_PROJECT_BYTES,
        val maxDepth: Int = DEFAULT_MAX_DEPTH,
        val maxRelativePathChars: Int = DEFAULT_MAX_RELATIVE_PATH_CHARS
    ) {
        init {
            require(maxFiles > 0)
            require(maxFileBytes > 0)
            require(maxProjectBytes > 0)
            require(maxDepth > 0)
            require(maxRelativePathChars > 0)
        }
    }

    data class ProjectFile(
        val file: File,
        val relativePath: String,
        val sizeBytes: Long
    )

    internal sealed interface Resolution {
        data object Passthrough : Resolution

        data class Resource(
            val projectFile: ProjectFile,
            val mimeType: String,
            val encoding: String?,
            val headers: Map<String, String>,
            val transformHtml: Boolean,
            val headOnly: Boolean
        ) : Resolution

        data class Error(
            val statusCode: Int,
            val reasonPhrase: String,
            val message: String
        ) : Resolution
    }

    val origin: String = "https://$originHost"
    val revisionBasePath: String = "/$MOUNT_SEGMENT/$revision/"
    val entryRelativePath: String = files.first { it.file == entryFile }.relativePath
    val entryUrl: String = origin + revisionBasePath + encodeRelativePath(entryRelativePath)

    private val filesByRelativePath = files.associateBy(ProjectFile::relativePath)

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val startedAt = System.nanoTime()
        var requestUrl = ""
        var normalizedMethod = "GET"
        var resolution: Resolution? = null
        return try {
            requestUrl = request.url.toString()
            normalizedMethod = normalizeMethod(request.method.orEmpty())
            val resolved = resolveInternal(requestUrl, normalizedMethod)
            resolution = resolved
            val response = when (resolved) {
                Resolution.Passthrough -> ServedResponse(response = null, bytes = 0L)
                is Resolution.Error -> ServedResponse(
                    response = errorResponse(resolved),
                    bytes = errorBodyBytes(resolved)
                )
                is Resolution.Resource -> resourceResponse(resolved)
            }
            traceResolution(
                operation = "intercept",
                url = requestUrl,
                method = normalizedMethod,
                resolution = resolved,
                durationMs = elapsedMillisSince(startedAt),
                responseBytes = response.bytes
            )
            response.response
        } catch (error: Exception) {
            traceResolutionException(
                operation = "intercept",
                url = requestUrl,
                method = normalizedMethod,
                resolution = resolution,
                durationMs = elapsedMillisSince(startedAt),
                error = error
            )
            throw error
        }
    }

    internal fun resolve(url: String, method: String = "GET"): Resolution {
        val startedAt = System.nanoTime()
        val normalizedMethod = normalizeMethod(method)
        var resolution: Resolution? = null
        return try {
            val resolved = resolveInternal(url, normalizedMethod)
            resolution = resolved
            traceResolution(
                operation = "resolve",
                url = url,
                method = normalizedMethod,
                resolution = resolved,
                durationMs = elapsedMillisSince(startedAt)
            )
            resolved
        } catch (error: Exception) {
            traceResolutionException(
                operation = "resolve",
                url = url,
                method = normalizedMethod,
                resolution = resolution,
                durationMs = elapsedMillisSince(startedAt),
                error = error
            )
            throw error
        }
    }

    private fun resolveInternal(url: String, normalizedMethod: String): Resolution {
        val uri = runCatching { URI(url) }.getOrElse {
            return Resolution.Error(400, "Bad Request", "Invalid project resource URL")
        }
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme in PASSTHROUGH_SCHEMES) return Resolution.Passthrough
        if (scheme != "https") {
            return Resolution.Error(403, "Forbidden", "Only the project HTTPS origin is allowed")
        }
        if (!uri.host.equals(originHost, ignoreCase = true) || uri.port !in setOf(-1, 443)) {
            return Resolution.Error(403, "Forbidden", "External network requests are blocked")
        }

        if (normalizedMethod != "GET" && normalizedMethod != "HEAD") {
            return Resolution.Error(405, "Method Not Allowed", "Only GET and HEAD are supported")
        }

        val decodedSegments = decodePathSegments(uri.rawPath.orEmpty())
            ?: return Resolution.Error(403, "Forbidden", "Unsafe project resource path")
        val revisionScoped = decodedSegments.firstOrNull() == MOUNT_SEGMENT
        val resourceSegments = if (revisionScoped) {
            if (decodedSegments.size < 3 || decodedSegments[1] != revision) {
                return Resolution.Error(404, "Not Found", "Unknown project revision")
            }
            decodedSegments.drop(2)
        } else {
            decodedSegments
        }
        if (resourceSegments.isEmpty() || resourceSegments.any(::isBlockedSegment)) {
            return Resolution.Error(404, "Not Found", "Project resource was not found")
        }

        val relativePath = resourceSegments.joinToString("/")
        val projectFile = filesByRelativePath[relativePath]
            ?: return Resolution.Error(404, "Not Found", "Project resource was not found")
        val mimeType = mimeTypeFor(projectFile.relativePath)
        return Resolution.Resource(
            projectFile = projectFile,
            mimeType = mimeType,
            encoding = if (isTextMimeType(mimeType)) "UTF-8" else null,
            headers = responseHeaders(
                projectFile = projectFile,
                revisionScoped = revisionScoped,
                html = mimeType == MIME_HTML
            ),
            transformHtml = mimeType == MIME_HTML && htmlTransformer != null,
            headOnly = normalizedMethod == "HEAD"
        )
    }

    fun resolveProjectReference(fromRelativePath: String, reference: String): ProjectFile? {
        val rawReference = reference.trim()
        if (rawReference.isEmpty() || rawReference.startsWith('#')) return null
        val parsed = runCatching { URI(rawReference) }.getOrNull()
        if (parsed?.isAbsolute == true || rawReference.startsWith("//")) return null
        val path = rawReference.substringBefore('#').substringBefore('?')
        if (path.isBlank()) return null
        val baseSegments = if (path.startsWith('/')) {
            emptyList()
        } else {
            fromRelativePath.substringBeforeLast('/', "")
                .split('/')
                .filter(String::isNotEmpty)
        }
        val referenceSegments = decodeReferencePathSegments(path)
            ?: return null
        val normalized = normalizeSegments(baseSegments + referenceSegments)
            ?: return null
        return filesByRelativePath[normalized.joinToString("/")]
    }

    private fun resourceResponse(resource: Resolution.Resource): ServedResponse {
        val body = when {
            resource.headOnly -> ResourceBody(
                input = ByteArrayInputStream(ByteArray(0)),
                bytes = 0L
            )
            resource.transformHtml -> {
                val source = resource.projectFile.file.readText(Charsets.UTF_8)
                val transformed = requireNotNull(htmlTransformer).invoke(source)
                val bytes = transformed.toByteArray(Charsets.UTF_8)
                ResourceBody(
                    input = ByteArrayInputStream(bytes),
                    bytes = bytes.size.toLong()
                )
            }
            else -> ResourceBody(
                input = FileInputStream(resource.projectFile.file),
                bytes = resource.projectFile.sizeBytes
            )
        }
        return ServedResponse(
            response = WebResourceResponse(
                resource.mimeType,
                resource.encoding,
                200,
                "OK",
                resource.headers,
                body.input
            ),
            bytes = body.bytes
        )
    }

    private fun errorResponse(error: Resolution.Error): WebResourceResponse {
        val body = error.message.toByteArray(Charsets.UTF_8)
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            error.statusCode,
            error.reasonPhrase,
            mapOf(
                "Cache-Control" to "no-store",
                "Content-Security-Policy" to DEFAULT_CONTENT_SECURITY_POLICY,
                "X-Content-Type-Options" to "nosniff"
            ),
            ByteArrayInputStream(body)
        )
    }

    private fun traceResolution(
        operation: String,
        url: String,
        method: String,
        resolution: Resolution,
        durationMs: Long,
        responseBytes: Long? = null
    ) {
        val details = linkedMapOf(
            "method" to method,
            "target" to safeRequestTarget(url)
        )
        val status: HarnessTraceStatus
        val message: String
        when (resolution) {
            Resolution.Passthrough -> {
                status = HarnessTraceStatus.SUCCEEDED
                message = "资源请求交由 WebView 处理"
                details += mapOf(
                    "resolution" to "passthrough",
                    "response_status" to "passthrough",
                    "mime" to "none",
                    "bytes" to "0",
                    "transform_html" to "false",
                    "head" to (method == "HEAD").toString()
                )
            }
            is Resolution.Resource -> {
                status = HarnessTraceStatus.SUCCEEDED
                message = "项目资源请求已解析"
                details += mapOf(
                    "resolution" to "served",
                    "relative_path" to resolution.projectFile.relativePath,
                    "response_status" to "200",
                    "mime" to resolution.mimeType,
                    "bytes" to (responseBytes ?: resolutionBodyBytes(resolution)).toString(),
                    "bytes_kind" to if (responseBytes != null || !resolution.transformHtml) {
                        "response"
                    } else {
                        "source"
                    },
                    "transform_html" to resolution.transformHtml.toString(),
                    "head" to resolution.headOnly.toString()
                )
            }
            is Resolution.Error -> {
                status = HarnessTraceStatus.FAILED
                message = "项目资源请求解析失败"
                details += mapOf(
                    "resolution" to "error",
                    "response_status" to resolution.statusCode.toString(),
                    "reason" to resolution.reasonPhrase,
                    "mime" to "text/plain",
                    "bytes" to (responseBytes ?: errorBodyBytes(resolution)).toString(),
                    "transform_html" to "false",
                    "head" to (method == "HEAD").toString()
                )
            }
        }
        traceLogger.record(
            runId = traceRunId,
            component = TRACE_COMPONENT,
            operation = operation,
            status = status,
            message = message,
            iteration = traceIteration,
            durationMs = durationMs,
            details = details
        )
    }

    private fun traceResolutionException(
        operation: String,
        url: String,
        method: String,
        resolution: Resolution?,
        durationMs: Long,
        error: Exception
    ) {
        traceLogger.record(
            runId = traceRunId,
            component = TRACE_COMPONENT,
            operation = operation,
            status = HarnessTraceStatus.FAILED,
            message = "项目资源请求处理异常",
            iteration = traceIteration,
            durationMs = durationMs,
            details = mapOf(
                "method" to method,
                "target" to safeRequestTarget(url),
                "resolution" to "error",
                "response_status" to "exception",
                "mime" to when (resolution) {
                    is Resolution.Resource -> resolution.mimeType
                    is Resolution.Error -> "text/plain"
                    else -> "none"
                },
                "bytes" to "0",
                "transform_html" to ((resolution as? Resolution.Resource)?.transformHtml ?: false).toString(),
                "head" to ((resolution as? Resolution.Resource)?.headOnly ?: (method == "HEAD")).toString(),
                "error_type" to error::class.java.simpleName
            )
        )
    }

    private fun safeRequestTarget(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "invalid-url"
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return "missing-scheme"
        if (scheme in PASSTHROUGH_SCHEMES) return "scheme:$scheme"
        if (scheme != "https") return "scheme:$scheme"
        if (!uri.host.equals(originHost, ignoreCase = true) || uri.port !in setOf(-1, 443)) {
            return "external-origin"
        }
        val segments = decodePathSegments(uri.rawPath.orEmpty()) ?: return "same-origin:unsafe-path"
        val relativeSegments = if (segments.firstOrNull() == MOUNT_SEGMENT && segments.size >= 2) {
            segments.drop(2)
        } else {
            segments
        }
        return "project:/" + relativeSegments.joinToString("/")
    }

    private data class ResourceBody(
        val input: java.io.InputStream,
        val bytes: Long
    )

    private data class ServedResponse(
        val response: WebResourceResponse?,
        val bytes: Long
    )

    private fun responseHeaders(
        projectFile: ProjectFile,
        revisionScoped: Boolean,
        html: Boolean
    ): Map<String, String> = buildMap {
        put(
            "Cache-Control",
            if (revisionScoped) "public, max-age=31536000, immutable" else "no-cache"
        )
        put("ETag", "\"$revision-${shortHash(projectFile.relativePath)}\"")
        put("X-Content-Type-Options", "nosniff")
        put("Cross-Origin-Resource-Policy", "same-origin")
        put("Referrer-Policy", "no-referrer")
        if (html) {
            put("Content-Security-Policy", DEFAULT_CONTENT_SECURITY_POLICY)
            put("X-Frame-Options", "DENY")
            put("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        }
    }

    companion object {
        const val MIME_HTML = "text/html"
        const val DEFAULT_MAX_FILES = 512
        const val DEFAULT_MAX_DEPTH = 16
        const val DEFAULT_MAX_RELATIVE_PATH_CHARS = 512
        const val DEFAULT_MAX_FILE_BYTES = 8L * 1024L * 1024L
        const val DEFAULT_MAX_PROJECT_BYTES = 32L * 1024L * 1024L

        private const val MOUNT_SEGMENT = "__mantou_project__"
        private const val DEFAULT_CONTENT_SECURITY_POLICY =
            "default-src 'self' data: blob:; " +
                "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: blob:; " +
                "font-src 'self' data:; " +
                "media-src 'self' data: blob:; " +
                "connect-src 'self'; " +
                "worker-src 'self' blob:; " +
                "frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
        private const val MIME_JAVASCRIPT = "application/javascript"
        private const val MIME_JSON = "application/json"
        private const val MIME_BINARY = "application/octet-stream"

        private val PASSTHROUGH_SCHEMES = setOf("about", "data", "blob", "javascript")
        private val BLOCKED_PATH_SEGMENTS = setOf(".mantou", ".git", MOUNT_SEGMENT)

        fun create(
            projectRoot: File,
            entryFile: File,
            projectId: String? = null,
            revision: String? = null,
            limits: Limits = Limits(),
            htmlTransformer: ((String) -> String)? = null,
            runId: String = UNTRACKED_RUN_ID,
            iteration: Int? = null,
            traceLogger: HarnessTraceLogger = NO_OP_TRACE_LOGGER
        ): WebProjectContentServer {
            val normalizedRunId = runId.ifBlank { UNTRACKED_RUN_ID }
            val createStartedAt = System.nanoTime()
            traceLogger.record(
                runId = normalizedRunId,
                component = TRACE_COMPONENT,
                operation = "create",
                status = HarnessTraceStatus.STARTED,
                message = "开始创建项目内容服务器",
                iteration = iteration,
                details = mapOf(
                    "project_root" to projectRoot.path,
                    "entry_path" to entryFile.path,
                    "html_transformer" to (htmlTransformer != null).toString()
                )
            )
            return try {
                require(projectRoot.exists() && projectRoot.isDirectory) {
                    "Project root must be an existing directory"
                }
                require(!Files.isSymbolicLink(projectRoot.toPath())) {
                    "Project root must not be a symbolic link"
                }
                val canonicalRoot = projectRoot.canonicalFile
                val requestedEntry = if (entryFile.isAbsolute) entryFile else File(canonicalRoot, entryFile.path)
                val canonicalEntry = requestedEntry.canonicalFile
                require(canonicalEntry.toPath().startsWith(canonicalRoot.toPath())) {
                    "Project entry must stay inside the project root"
                }
                require(canonicalEntry.isFile && !Files.isSymbolicLink(requestedEntry.toPath())) {
                    "Project entry must be an existing regular file"
                }
                require(canonicalEntry.extension.equals("html", ignoreCase = true) ||
                    canonicalEntry.extension.equals("htm", ignoreCase = true)) {
                    "Project entry must be an HTML file"
                }

                val scanStartedAt = System.nanoTime()
                traceLogger.record(
                    runId = normalizedRunId,
                    component = TRACE_COMPONENT,
                    operation = "scan_project",
                    status = HarnessTraceStatus.STARTED,
                    message = "开始扫描项目文件",
                    iteration = iteration,
                    details = mapOf(
                        "project_root" to canonicalRoot.path,
                        "max_files" to limits.maxFiles.toString(),
                        "max_file_bytes" to limits.maxFileBytes.toString(),
                        "max_project_bytes" to limits.maxProjectBytes.toString(),
                        "max_depth" to limits.maxDepth.toString(),
                        "max_path_chars" to limits.maxRelativePathChars.toString()
                    )
                )
                val files = try {
                    scanProject(canonicalRoot, limits)
                } catch (error: Exception) {
                    traceLogger.record(
                        runId = normalizedRunId,
                        component = TRACE_COMPONENT,
                        operation = "scan_project",
                        status = HarnessTraceStatus.FAILED,
                        message = "项目文件扫描失败",
                        iteration = iteration,
                        durationMs = elapsedMillisSince(scanStartedAt),
                        details = mapOf("error_type" to error::class.java.simpleName)
                    )
                    throw error
                }
                val totalBytes = files.sumOf(ProjectFile::sizeBytes)
                traceLogger.record(
                    runId = normalizedRunId,
                    component = TRACE_COMPONENT,
                    operation = "scan_project",
                    status = HarnessTraceStatus.SUCCEEDED,
                    message = "项目文件扫描完成",
                    iteration = iteration,
                    durationMs = elapsedMillisSince(scanStartedAt),
                    details = mapOf(
                        "file_count" to files.size.toString(),
                        "total_bytes" to totalBytes.toString()
                    )
                )
                require(files.any { it.file == canonicalEntry }) {
                    "Project entry was not found in the validated project tree"
                }
                val resolvedRevision = revision?.trim()?.takeIf(String::isNotEmpty)?.also { value ->
                    require(REVISION_PATTERN.matches(value)) { "Project revision contains unsafe characters" }
                } ?: computeRevision(files)
                val identity = projectId?.trim()?.takeIf(String::isNotEmpty) ?: canonicalRoot.path
                val hostHash = sha256(identity.toByteArray(Charsets.UTF_8)).take(24)
                val server = WebProjectContentServer(
                    projectRoot = canonicalRoot,
                    entryFile = canonicalEntry,
                    revision = resolvedRevision,
                    originHost = "app-$hostHash.mantou.local",
                    files = files,
                    htmlTransformer = htmlTransformer,
                    traceRunId = normalizedRunId,
                    traceIteration = iteration,
                    traceLogger = traceLogger
                )
                traceLogger.record(
                    runId = normalizedRunId,
                    component = TRACE_COMPONENT,
                    operation = "create",
                    status = HarnessTraceStatus.SUCCEEDED,
                    message = "项目内容服务器创建完成",
                    iteration = iteration,
                    durationMs = elapsedMillisSince(createStartedAt),
                    details = mapOf(
                        "entry_relative_path" to server.entryRelativePath,
                        "file_count" to files.size.toString(),
                        "total_bytes" to totalBytes.toString(),
                        "revision" to resolvedRevision,
                        "origin_host" to server.originHost,
                        "html_transformer" to (htmlTransformer != null).toString()
                    )
                )
                server
            } catch (error: Exception) {
                traceLogger.record(
                    runId = normalizedRunId,
                    component = TRACE_COMPONENT,
                    operation = "create",
                    status = HarnessTraceStatus.FAILED,
                    message = "项目内容服务器创建失败",
                    iteration = iteration,
                    durationMs = elapsedMillisSince(createStartedAt),
                    details = mapOf("error_type" to error::class.java.simpleName)
                )
                throw error
            }
        }

        internal fun mimeTypeFor(path: String): String {
            return when (path.substringAfterLast('.', "").lowercase(Locale.US)) {
                "html", "htm" -> MIME_HTML
                "css" -> "text/css"
                "js", "mjs", "cjs" -> MIME_JAVASCRIPT
                "json", "map" -> MIME_JSON
                "webmanifest" -> "application/manifest+json"
                "txt", "md" -> "text/plain"
                "xml" -> "application/xml"
                "svg" -> "image/svg+xml"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "avif" -> "image/avif"
                "ico" -> "image/x-icon"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                "ttf" -> "font/ttf"
                "otf" -> "font/otf"
                "wasm" -> "application/wasm"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "mp4" -> "video/mp4"
                "webm" -> "video/webm"
                else -> MIME_BINARY
            }
        }

        private fun scanProject(root: File, limits: Limits): List<ProjectFile> {
            val files = mutableListOf<ProjectFile>()
            val caseInsensitivePaths = mutableSetOf<String>()
            var totalBytes = 0L

            fun visit(directory: File, relativeDirectory: String, depth: Int) {
                require(depth <= limits.maxDepth) { "Project directory depth exceeds ${limits.maxDepth}" }
                val children = directory.listFiles()
                    ?: throw IllegalArgumentException("Unable to read project directory: $relativeDirectory")
                for (child in children.sortedBy { it.name }) {
                    require(!Files.isSymbolicLink(child.toPath())) {
                        "Symbolic links are not allowed in web projects"
                    }
                    val relativePath = listOf(relativeDirectory, child.name)
                        .filter(String::isNotEmpty)
                        .joinToString("/")
                    require(relativePath.length <= limits.maxRelativePathChars) {
                        "Project path exceeds ${limits.maxRelativePathChars} characters"
                    }
                    require(child.name != "." && child.name != ".." && '\u0000' !in child.name) {
                        "Project contains an unsafe path"
                    }
                    if (child.isDirectory) {
                        visit(child, relativePath, depth + 1)
                    } else {
                        require(child.isFile) { "Project contains a non-regular file: $relativePath" }
                        require(child.length() <= limits.maxFileBytes) {
                            "Project file exceeds ${limits.maxFileBytes} bytes: $relativePath"
                        }
                        require(files.size < limits.maxFiles) {
                            "Project contains more than ${limits.maxFiles} files"
                        }
                        totalBytes += child.length()
                        require(totalBytes <= limits.maxProjectBytes) {
                            "Project exceeds ${limits.maxProjectBytes} bytes"
                        }
                        require(caseInsensitivePaths.add(relativePath.lowercase(Locale.US))) {
                            "Project contains case-conflicting paths: $relativePath"
                        }
                        files += ProjectFile(child.canonicalFile, relativePath, child.length())
                    }
                }
            }

            visit(root, "", 0)
            require(files.isNotEmpty()) { "Project does not contain any files" }
            return files
        }

        private fun computeRevision(files: List<ProjectFile>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            for (projectFile in files.sortedBy(ProjectFile::relativePath)) {
                digest.update(projectFile.relativePath.toByteArray(Charsets.UTF_8))
                digest.update(0)
                FileInputStream(projectFile.file).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.update(0)
            }
            return digest.digest().toHex().take(24)
        }

        private fun decodePathSegments(rawPath: String): List<String>? {
            val rawSegments = rawPath.removePrefix("/").split('/').filter(String::isNotEmpty)
            val decoded = ArrayList<String>(rawSegments.size)
            for (rawSegment in rawSegments) {
                val segment = decodePathSegment(rawSegment) ?: return null
                if (segment.isEmpty() || segment == "." || segment == ".." ||
                    '/' in segment || '\\' in segment || '\u0000' in segment
                ) {
                    return null
                }
                decoded += segment
            }
            return decoded
        }

        private fun decodeReferencePathSegments(rawPath: String): List<String>? {
            val rawSegments = rawPath.removePrefix("/").split('/').filter(String::isNotEmpty)
            return rawSegments.map { rawSegment ->
                val segment = decodePathSegment(rawSegment) ?: return null
                if ('/' in segment || '\\' in segment || '\u0000' in segment) return null
                segment
            }
        }

        private fun decodePathSegment(rawSegment: String): String? {
            return runCatching {
                URLDecoder.decode(rawSegment.replace("+", "%2B"), Charsets.UTF_8.name())
            }.getOrNull()
        }

        private fun normalizeSegments(segments: List<String>): List<String>? {
            val normalized = mutableListOf<String>()
            for (segment in segments) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (normalized.isEmpty()) return null else normalized.removeAt(normalized.lastIndex)
                    else -> {
                        if ('/' in segment || '\\' in segment || '\u0000' in segment || isBlockedSegment(segment)) {
                            return null
                        }
                        normalized += segment
                    }
                }
            }
            return normalized
        }

        private fun encodeRelativePath(relativePath: String): String {
            val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
            return relativePath.split('/').joinToString("/") { segment ->
                buildString {
                    for (byte in segment.toByteArray(Charsets.UTF_8)) {
                        val value = byte.toInt() and 0xff
                        val character = value.toChar()
                        if (character in unreserved) {
                            append(character)
                        } else {
                            append('%')
                            append(HEX[value ushr 4])
                            append(HEX[value and 0x0f])
                        }
                    }
                }
            }
        }

        private fun isBlockedSegment(segment: String): Boolean {
            return segment.lowercase(Locale.US) in BLOCKED_PATH_SEGMENTS
        }

        private fun isTextMimeType(mimeType: String): Boolean {
            return mimeType.startsWith("text/") ||
                mimeType == MIME_JAVASCRIPT ||
                mimeType == MIME_JSON ||
                mimeType.endsWith("+json") ||
                mimeType.endsWith("+xml") ||
                mimeType == "application/xml" ||
                mimeType == "image/svg+xml"
        }

        private fun shortHash(value: String): String {
            return sha256(value.toByteArray(Charsets.UTF_8)).take(12)
        }

        private fun sha256(bytes: ByteArray): String {
            return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        }

        private fun ByteArray.toHex(): String {
            return joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        private fun normalizeMethod(method: String): String {
            return method.ifBlank { "GET" }.uppercase(Locale.US)
        }

        private fun resolutionBodyBytes(resource: Resolution.Resource): Long {
            return if (resource.headOnly) 0L else resource.projectFile.sizeBytes
        }

        private fun errorBodyBytes(error: Resolution.Error): Long {
            return error.message.toByteArray(Charsets.UTF_8).size.toLong()
        }

        private const val HEX = "0123456789ABCDEF"
        private const val TRACE_COMPONENT = "CONTENT_SERVER"
        private const val UNTRACKED_RUN_ID = "untracked"
        private val REVISION_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        private val NO_OP_TRACE_LOGGER = HarnessTraceLogger {}
    }
}
