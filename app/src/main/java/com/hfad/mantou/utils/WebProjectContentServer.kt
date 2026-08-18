package com.hfad.mantou.utils

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
    private val htmlTransformer: ((String) -> String)?
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
        return when (val resolution = resolve(request.url.toString(), request.method.orEmpty())) {
            Resolution.Passthrough -> null
            is Resolution.Error -> errorResponse(resolution)
            is Resolution.Resource -> resourceResponse(resolution)
        }
    }

    internal fun resolve(url: String, method: String = "GET"): Resolution {
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

        val normalizedMethod = method.ifBlank { "GET" }.uppercase(Locale.US)
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

    private fun resourceResponse(resource: Resolution.Resource): WebResourceResponse {
        val input = when {
            resource.headOnly -> ByteArrayInputStream(ByteArray(0))
            resource.transformHtml -> {
                val source = resource.projectFile.file.readText(Charsets.UTF_8)
                val transformed = requireNotNull(htmlTransformer).invoke(source)
                ByteArrayInputStream(transformed.toByteArray(Charsets.UTF_8))
            }
            else -> FileInputStream(resource.projectFile.file)
        }
        return WebResourceResponse(
            resource.mimeType,
            resource.encoding,
            200,
            "OK",
            resource.headers,
            input
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
            htmlTransformer: ((String) -> String)? = null
        ): WebProjectContentServer {
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

            val files = scanProject(canonicalRoot, limits)
            require(files.any { it.file == canonicalEntry }) {
                "Project entry was not found in the validated project tree"
            }
            val resolvedRevision = revision?.trim()?.takeIf(String::isNotEmpty)?.also { value ->
                require(REVISION_PATTERN.matches(value)) { "Project revision contains unsafe characters" }
            } ?: computeRevision(files)
            val identity = projectId?.trim()?.takeIf(String::isNotEmpty) ?: canonicalRoot.path
            val hostHash = sha256(identity.toByteArray(Charsets.UTF_8)).take(24)
            return WebProjectContentServer(
                projectRoot = canonicalRoot,
                entryFile = canonicalEntry,
                revision = resolvedRevision,
                originHost = "app-$hostHash.mantou.local",
                files = files,
                htmlTransformer = htmlTransformer
            )
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

        private const val HEX = "0123456789ABCDEF"
        private val REVISION_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
