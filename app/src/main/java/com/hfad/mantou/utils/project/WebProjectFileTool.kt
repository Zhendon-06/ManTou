package com.hfad.mantou.utils.project

import com.google.gson.GsonBuilder
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

data class WebProjectFileToolPolicy(
    val maxFiles: Int = 512,
    val maxFileBytes: Long = 8L * 1024L * 1024L,
    val maxTotalBytes: Long = 32L * 1024L * 1024L,
    val allowedExtensions: Set<String> = DEFAULT_ALLOWED_EXTENSIONS,
    val writableExtensions: Set<String> = DEFAULT_WRITABLE_EXTENSIONS
) {
    init {
        require(maxFiles > 0)
        require(maxFileBytes > 0L)
        require(maxTotalBytes > 0L)
        require(allowedExtensions.isNotEmpty())
        require(writableExtensions.isNotEmpty())
        require(writableExtensions.all { it.lowercase(Locale.US) in allowedExtensions })
    }

    companion object {
        val DEFAULT_ALLOWED_EXTENSIONS = setOf(
            "html", "htm", "css", "js", "mjs", "json", "svg", "txt", "md",
            "png", "jpg", "jpeg", "gif", "webp", "avif", "ico",
            "woff", "woff2", "ttf", "otf", "wasm", "mp3", "wav", "ogg", "mp4", "webm"
        )
        val DEFAULT_WRITABLE_EXTENSIONS = setOf(
            "html", "htm", "css", "js", "mjs", "json", "svg", "txt", "md"
        )
    }
}

data class WebProjectFileInfo(
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long = 0L,
    val sha256: String? = null
)

data class WebProjectReadResult(
    val path: String,
    val content: String,
    val sizeBytes: Long,
    val sha256: String
)

data class WebProjectWriteResult(
    val path: String,
    val created: Boolean,
    val sizeBytes: Long,
    val beforeSha256: String?,
    val afterSha256: String
)

data class WebProjectDeleteResult(
    val path: String,
    val deletedSha256: String,
    val deletedBytes: Long
)

data class WebProjectToolExecutionResult(
    val success: Boolean,
    val output: String,
    val diagnostics: List<String> = emptyList(),
    val changedFiles: List<String> = emptyList()
)

class WebProjectFileTool(
    workspaceRoot: File,
    private val policy: WebProjectFileToolPolicy = WebProjectFileToolPolicy(),
    private val allowMutations: Boolean = !isManagedReleaseRoot(workspaceRoot)
) {
    val workspaceRoot: File = workspaceRoot.canonicalFile

    init {
        require(this.workspaceRoot.isDirectory) {
            "Workspace root must be an existing directory: ${this.workspaceRoot.path}"
        }
    }

    fun list(path: String = ".", recursive: Boolean = true): List<WebProjectFileInfo> =
        synchronized(FILE_TOOL_LOCK) {
            val target = resolve(path, allowRoot = true)
            if (!target.exists()) throw WebAppProjectException("Path does not exist: $path")
            val paths = when {
                target.isFile -> listOf(target.toPath())
                recursive -> Files.walk(target.toPath()).use { stream ->
                    stream.iterator().asSequence().drop(1).toList()
                }
                else -> Files.list(target.toPath()).use { stream ->
                    stream.iterator().asSequence().toList()
                }
            }
            paths.sortedBy { candidate ->
                WebProjectPaths.relativePath(workspaceRoot, candidate.toFile())
            }.map { candidate ->
                if (Files.isSymbolicLink(candidate)) {
                    throw WebAppProjectException("Symbolic links are not allowed in project paths")
                }
                val relativePath = WebProjectPaths.relativePath(workspaceRoot, candidate.toFile())
                if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    WebProjectFileInfo(path = relativePath, directory = true)
                } else if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    val size = Files.size(candidate)
                    WebProjectFileInfo(
                        path = relativePath,
                        directory = false,
                        sizeBytes = size,
                        sha256 = if (size <= policy.maxFileBytes) {
                            WebProjectPaths.sha256(Files.readAllBytes(candidate))
                        } else {
                            null
                        }
                    )
                } else {
                    throw WebAppProjectException("Unsupported project path: $relativePath")
                }
            }
        }

    fun read(path: String): WebProjectReadResult = synchronized(FILE_TOOL_LOCK) {
        val target = resolve(path)
        requireAllowedFile(target, path)
        if (!target.isFile) throw WebAppProjectException("File does not exist: $path")
        val bytes = target.readBytes()
        if (bytes.size.toLong() > policy.maxFileBytes) {
            throw WebAppProjectException("File exceeds ${policy.maxFileBytes} bytes: $path")
        }
        WebProjectReadResult(
            path = WebProjectPaths.relativePath(workspaceRoot, target),
            content = decodeUtf8(bytes),
            sizeBytes = bytes.size.toLong(),
            sha256 = WebProjectPaths.sha256(bytes)
        )
    }

    fun write(
        path: String,
        content: String,
        expectedSha256: String? = null,
        createOnly: Boolean = false
    ): WebProjectWriteResult = synchronized(FILE_TOOL_LOCK) {
        requireMutableWorkspace()
        val target = resolve(path)
        requireAllowedTextFile(target, path)
        if (target.isDirectory) throw WebAppProjectException("Write target is a directory: $path")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > policy.maxFileBytes) {
            throw WebAppProjectException("File exceeds ${policy.maxFileBytes} bytes: $path")
        }

        val existed = target.isFile
        if (createOnly && existed) throw WebAppProjectException("File already exists: $path")
        val previousBytes = if (existed) target.readBytes() else null
        val beforeSha256 = previousBytes?.let(WebProjectPaths::sha256)
        normalizeExpectedHash(expectedSha256)?.let { expected ->
            if (beforeSha256 == null) {
                throw WebAppProjectException("Expected SHA-256 was provided but file does not exist: $path")
            }
            if (beforeSha256 != expected) {
                throw WebAppProjectException(
                    "File changed after it was read; expected $expected but found $beforeSha256"
                )
            }
        }

        val stats = projectStats()
        val nextFileCount = stats.fileCount + if (existed) 0 else 1
        val nextTotalBytes = stats.totalBytes - (previousBytes?.size ?: 0) + bytes.size
        if (nextFileCount > policy.maxFiles) {
            throw WebAppProjectException("Project file count would exceed ${policy.maxFiles}")
        }
        if (nextTotalBytes > policy.maxTotalBytes) {
            throw WebAppProjectException("Project size would exceed ${policy.maxTotalBytes} bytes")
        }

        WebProjectPaths.writeTextAtomically(target, content)
        WebProjectWriteResult(
            path = WebProjectPaths.relativePath(workspaceRoot, target),
            created = !existed,
            sizeBytes = bytes.size.toLong(),
            beforeSha256 = beforeSha256,
            afterSha256 = WebProjectPaths.sha256(bytes)
        )
    }

    fun delete(
        path: String,
        expectedSha256: String? = null
    ): WebProjectDeleteResult = synchronized(FILE_TOOL_LOCK) {
        requireMutableWorkspace()
        val target = resolve(path)
        requireAllowedFile(target, path)
        if (!target.isFile) throw WebAppProjectException("File does not exist: $path")
        val bytes = target.readBytes()
        val currentSha256 = WebProjectPaths.sha256(bytes)
        normalizeExpectedHash(expectedSha256)?.let { expected ->
            if (currentSha256 != expected) {
                throw WebAppProjectException(
                    "File changed after it was read; expected $expected but found $currentSha256"
                )
            }
        }
        Files.delete(target.toPath())
        removeEmptyParents(target.parentFile)
        WebProjectDeleteResult(
            path = path,
            deletedSha256 = currentSha256,
            deletedBytes = bytes.size.toLong()
        )
    }

    fun execute(
        toolName: String,
        arguments: Map<String, String>
    ): WebProjectToolExecutionResult {
        return runCatching {
            when (toolName) {
                TOOL_LIST_FILES -> {
                    val path = arguments[ARG_PATH].orEmpty().ifBlank { "." }
                    val recursive = arguments[ARG_RECURSIVE]?.toBooleanStrictOrNull() ?: true
                    WebProjectToolExecutionResult(
                        success = true,
                        output = TOOL_GSON.toJson(list(path, recursive))
                    )
                }

                TOOL_READ_FILE -> {
                    val path = arguments.requireArgument(ARG_PATH, toolName)
                    WebProjectToolExecutionResult(
                        success = true,
                        output = TOOL_GSON.toJson(read(path))
                    )
                }

                TOOL_WRITE_FILE -> {
                    val path = arguments.requireArgument(ARG_PATH, toolName)
                    val content = arguments[ARG_CONTENT]
                        ?: throw WebAppProjectException("$toolName requires argument: $ARG_CONTENT")
                    val result = write(
                        path = path,
                        content = content,
                        expectedSha256 = arguments.expectedSha256(),
                        createOnly = arguments[ARG_CREATE_ONLY]?.toBooleanStrictOrNull() ?: false
                    )
                    WebProjectToolExecutionResult(
                        success = true,
                        output = TOOL_GSON.toJson(result),
                        changedFiles = listOf(result.path)
                    )
                }

                TOOL_DELETE_FILE -> {
                    val path = arguments.requireArgument(ARG_PATH, toolName)
                    val result = delete(path, arguments.expectedSha256())
                    WebProjectToolExecutionResult(
                        success = true,
                        output = TOOL_GSON.toJson(result),
                        changedFiles = listOf(result.path)
                    )
                }

                else -> throw WebAppProjectException("Unsupported project tool: $toolName")
            }
        }.getOrElse { error ->
            WebProjectToolExecutionResult(
                success = false,
                output = error.message ?: "Project file tool failed",
                diagnostics = listOf(error.message ?: error::class.java.simpleName)
            )
        }
    }

    private fun resolve(path: String, allowRoot: Boolean = false): File {
        val normalized = WebProjectPaths.normalizeRelativePath(path, allowRoot)
        return if (normalized.isEmpty()) workspaceRoot else WebProjectPaths.resolve(workspaceRoot, normalized)
    }

    private fun requireAllowedTextFile(target: File, displayPath: String) {
        val extension = target.extension.lowercase(Locale.US)
        if (extension !in policy.writableExtensions) {
            throw WebAppProjectException("File extension .$extension is not writable: $displayPath")
        }
    }

    private fun requireAllowedFile(target: File, displayPath: String) {
        val extension = target.extension.lowercase(Locale.US)
        if (extension !in policy.allowedExtensions) {
            throw WebAppProjectException("File extension .$extension is not supported: $displayPath")
        }
    }

    private fun requireMutableWorkspace() {
        if (!allowMutations) {
            throw WebAppProjectException("Published release workspaces are immutable")
        }
    }

    private fun projectStats(): ProjectStats {
        var fileCount = 0
        var totalBytes = 0L
        Files.walk(workspaceRoot.toPath()).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (path == workspaceRoot.toPath()) continue
                if (Files.isSymbolicLink(path)) {
                    throw WebAppProjectException("Symbolic links are not allowed in project paths")
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    fileCount++
                    totalBytes += Files.size(path)
                }
            }
        }
        return ProjectStats(fileCount, totalBytes)
    }

    private fun removeEmptyParents(start: File?) {
        var current = start
        while (current != null && current != workspaceRoot) {
            val empty = Files.list(current.toPath()).use { stream -> !stream.findAny().isPresent }
            if (!empty) return
            Files.deleteIfExists(current.toPath())
            current = current.parentFile
        }
    }

    private fun normalizeExpectedHash(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val normalized = value.trim().lowercase(Locale.US)
        if (!SHA256_REGEX.matches(normalized)) {
            throw WebAppProjectException("Expected SHA-256 must contain 64 hexadecimal characters")
        }
        return normalized
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse { error ->
            throw WebAppProjectException("File is not valid UTF-8 text", error)
        }.also { content ->
            if ('\u0000' in content) throw WebAppProjectException("Binary files are not supported")
        }
    }

    private fun Map<String, String>.expectedSha256(): String? =
        this[ARG_EXPECTED_SHA256] ?: this[ARG_EXPECTED_SHA256_CAMEL]

    private fun Map<String, String>.requireArgument(name: String, toolName: String): String =
        this[name]?.takeIf(String::isNotBlank)
            ?: throw WebAppProjectException("$toolName requires argument: $name")

    private data class ProjectStats(val fileCount: Int, val totalBytes: Long)

    companion object {
        const val TOOL_LIST_FILES = "list_files"
        const val TOOL_READ_FILE = "read_file"
        const val TOOL_WRITE_FILE = "write_file"
        const val TOOL_DELETE_FILE = "delete_file"

        const val ARG_PATH = "path"
        const val ARG_CONTENT = "content"
        const val ARG_RECURSIVE = "recursive"
        const val ARG_CREATE_ONLY = "createOnly"
        const val ARG_EXPECTED_SHA256 = "expected_sha256"
        const val ARG_EXPECTED_SHA256_CAMEL = "expectedSha256"

        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
        private val TOOL_GSON = GsonBuilder().disableHtmlEscaping().create()
        private val FILE_TOOL_LOCK = Any()

        private fun isManagedReleaseRoot(workspaceRoot: File): Boolean {
            val root = workspaceRoot.canonicalFile
            val releasesDirectory = root.parentFile ?: return false
            val projectRoot = releasesDirectory.parentFile ?: return false
            return releasesDirectory.name == WebAppProjectWorkspace.RELEASES_DIRECTORY_NAME &&
                WebAppProjectWorkspace.parseVersion(root.name) != null &&
                WebAppProjectWorkspace.manifestFile(projectRoot).isFile &&
                WebAppProjectWorkspace.workspaceStateFile(projectRoot).isFile
        }
    }
}
