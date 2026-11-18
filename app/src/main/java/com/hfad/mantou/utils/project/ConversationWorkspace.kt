package com.hfad.mantou.utils.project

import com.google.gson.Gson
import com.hfad.mantou.utils.LocalDiffFileTool
import com.hfad.mantou.utils.WebProjectContentServer
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

internal class ConversationWorkspace(private val filesDir: File) {
    fun create(parentPath: String?, name: String): String {
        val trimmed = name.trim()
        require(trimmed.matches(DIRECTORY_NAME)) { "请输入 1–40 个字母、数字、汉字、空格、下划线或连字符" }
        val parent = directory(parentPath)
        val directory = WebProjectPaths.resolve(parent, trimmed)
        require(isVisible(directory) && !isManagedInternal(directory)) { "该位置由应用管理" }
        check(directory.mkdir()) { "工作区已存在或无法创建" }
        return displayPath(directory)
    }

    fun existingApp(path: String?): File? {
        if (path == null) return null
        val folder = directory(path)
        return runCatching {
            val project = WebAppProjectResolver.resolve(folder)
            // A subfolder inside a snapshot is a file workspace, not an app entry.
            folder.takeIf { it == project.projectRoot || it == project.contentRoot }
        }.getOrNull()
    }

    fun newProjectParent(path: String?): File {
        val selected = directory(path)
        require(!isManagedInternal(selected, includeDrafts = true)) { "请在应用项目根目录生成或修改应用" }
        return selected
    }

    fun canUseAsProjectRoot(folder: File): Boolean =
        folder != filesDir.canonicalFile && folder.name !in CONTAINER_NAMES &&
            children(folder).none { it.isDirectory && (
                it.name in CONTAINER_NAMES || WebAppProjectWorkspace.manifestFile(it).isFile
            ) }

    fun containsProject(workspacePath: String?, path: String): Boolean {
        return runCatching {
            val projects = directory(workspacePath).toPath()
            val candidate = File(path).canonicalFile.toPath()
            candidate != projects && candidate.startsWith(projects)
        }.getOrDefault(false)
    }

    fun importResource(workspace: String?, displayName: String, open: () -> InputStream): String =
        synchronized(RESOURCE_LOCK) {
            val resourceDir = importDirectory(workspace)
            val files = resourceFiles(resourceDir)
            check(files.size < MAX_RESOURCES) { "工作区最多可保存 ${MAX_RESOURCES} 个资源" }
            val available = minOf(MAX_FILE_BYTES, MAX_RESOURCE_BYTES - files.sumOf(File::length))
            check(available > 0L) { "工作区资源已达到 16 MB 上限" }

            val fileName = displayName.substringAfterLast('/').substringAfterLast('\\')
            val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            require(extension in RESOURCE_EXTENSIONS) { "不支持导入“$fileName”的文件类型" }
            val stem = fileName.substringBeforeLast('.')
                .replace(UNSAFE_FILENAME, "_")
                .trim('_', '.', '-')
                .take(48)
                .ifBlank { "resource" }
            check(resourceDir.isDirectory || resourceDir.mkdirs()) { "无法创建资源目录" }
            val target = generateSequence(1) { it + 1 }
                .map { version ->
                    val suffix = if (version == 1) "" else "_$version"
                    File(resourceDir, "$stem$suffix.$extension")
                }
                .first { !it.exists() }

            val temporary = Files.createTempFile(filesDir.toPath(), ".import-", ".tmp").toFile()
            try {
                copyResource(temporary, available, open)
                WebProjectPaths.moveAtomically(temporary, target)
                "$IMPORTED_ASSET_PATH/${target.name}"
            } finally {
                temporary.delete()
            }
        }

    fun importResourceFolder(workspace: String?, folder: WorkspaceResourceEntry.Folder): ImportedResourceFolder =
        synchronized(RESOURCE_LOCK) {
            val resourceDir = importDirectory(workspace)
            val folderName = resourceEntryName(folder.name)
            val existing = resourceFiles(resourceDir)
            val target = generateSequence(1) { it + 1 }
                .map { version -> File(resourceDir, if (version == 1) folderName else "${folderName}_$version") }
                .first { !it.exists() }
            val path = "$IMPORTED_ASSET_PATH/${target.name}"
            check(path.length <= WebProjectContentServer.DEFAULT_MAX_RELATIVE_PATH_CHARS) { "资源路径太长：$path" }
            val staging = Files.createTempDirectory(filesDir.toPath(), ".import-folder-").toFile()
            var importedCount = 0
            var skippedCount = 0
            var remainingBytes = MAX_RESOURCE_BYTES - existing.sumOf(File::length)
            fun copyFolder(source: WorkspaceResourceEntry.Folder, parent: File, path: String, depth: Int) {
                for (entry in source.children()) {
                    if (entry.name.startsWith('.') || entry is WorkspaceResourceEntry.Resource &&
                        File(entry.name).extension.lowercase(Locale.ROOT) !in RESOURCE_EXTENSIONS
                    ) {
                        skippedCount++
                        continue
                    }
                    val name = resourceEntryName(entry.name)
                    val relativePath = "$path/$name"
                    check(depth < WebProjectContentServer.DEFAULT_MAX_DEPTH &&
                        relativePath.length <= WebProjectContentServer.DEFAULT_MAX_RELATIVE_PATH_CHARS
                    ) { "资源目录太深或路径太长：$relativePath" }
                    val destination = File(parent, name)
                    check(!destination.exists()) { "文件夹内有同名条目：$relativePath" }
                    when (entry) {
                        is WorkspaceResourceEntry.Folder -> {
                            check(destination.mkdir()) { "无法创建资源子目录：$relativePath" }
                            copyFolder(entry, destination, relativePath, depth + 1)
                        }
                        is WorkspaceResourceEntry.Resource -> {
                            check(existing.size + importedCount < MAX_RESOURCES) {
                                "工作区最多可保存 ${MAX_RESOURCES} 个资源"
                            }
                            remainingBytes -= copyResource(destination, minOf(MAX_FILE_BYTES, remainingBytes), entry.open)
                            importedCount++
                        }
                    }
                }
            }
            try {
                copyFolder(folder, staging, path, depth = path.count { it == '/' } + 1)
                WebProjectPaths.moveAtomically(staging, target)
                ImportedResourceFolder(path, importedCount, skippedCount)
            } finally {
                staging.deleteRecursively()
            }
        }

    private fun importDirectory(workspace: String?): File {
        val folder = WebProjectPaths.resolve(directory(workspace), IMPORTED_ASSET_PATH)
        require(!isManagedInternal(folder)) { "请将资源导入到应用项目根目录" }
        return folder
    }

    private fun resourceEntryName(name: String): String {
        require(name.isNotBlank() && !name.startsWith('.') &&
            name.none { it in "\\/:*?\"<>|" || it.isISOControl() }
        ) { "无效的资源名称：$name" }
        return name
    }

    private fun copyResource(file: File, limit: Long, open: () -> InputStream): Long {
        var bytes = 0L
        open().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    check(bytes <= limit) { "文件超过单个 8 MB 或工作区总计 16 MB 的限制" }
                    output.write(buffer, 0, count)
                }
            }
        }
        check(bytes > 0) { "资源文件为空" }
        return bytes
    }

    fun assetPaths(path: String?): List<String> = synchronized(RESOURCE_LOCK) {
        resourceSources(path).flatMap { source ->
            val folder = WebProjectPaths.resolve(directory(source), IMPORTED_ASSET_PATH)
            resourceFiles(folder).map { "$IMPORTED_ASSET_PATH/${WebProjectPaths.relativePath(folder, it)}" }
        }.distinct()
    }

    fun copyResources(name: String?, paths: List<String>, contentRoot: File): List<String> {
        if (paths.isEmpty()) return emptyList()
        return synchronized(RESOURCE_LOCK) {
            val sourceRoots = resourceSources(name).map(::directory)
            paths.filter { path ->
                val source = sourceRoots.firstNotNullOfOrNull { root ->
                    WebProjectPaths.resolve(root, path).takeIf(File::isFile)
                }
                val target = WebProjectPaths.resolve(contentRoot, path)
                if (source != null && source != target) {
                    check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
                        "无法创建项目资源目录"
                    }
                    val temporary = Files.createTempFile(filesDir.toPath(), ".asset-", ".tmp").toFile()
                    try {
                        source.copyTo(temporary, overwrite = true)
                        WebProjectPaths.moveAtomically(temporary, target, replaceExisting = true)
                    } finally {
                        temporary.delete()
                    }
                }
                target.isFile
            }
        }
    }

    fun deleteResource(name: String?, file: File): Boolean = synchronized(RESOURCE_LOCK) {
        !isManagedInternal(file) && file in resourceFiles(importDirectory(name)) && file.delete()
    }

    fun directory(path: String?): File {
        if (path == null || path == ROOT_PATH) return filesDir.canonicalFile
        require(path.startsWith("$ROOT_PATH/")) { "无效的工作区目录" }
        val target = WebProjectPaths.resolve(filesDir, path.removePrefix("$ROOT_PATH/"))
        check(target.isDirectory && isVisible(target)) { "工作区目录不存在" }
        return target
    }

    fun children(directory: File): List<File> = directory.listFiles().orEmpty()
        .filter { !Files.isSymbolicLink(it.toPath()) && isVisible(it) }
        .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })

    fun displayPath(file: File): String {
        val relative = WebProjectPaths.relativePath(filesDir.canonicalFile, file.canonicalFile)
        return if (relative.isEmpty()) ROOT_PATH else "$ROOT_PATH/$relative"
    }

    fun execute(scope: String?, tool: String, args: Map<String, String>): String {
        val root = directory(scope)
        val path = args[WebProjectFileTool.ARG_PATH].orEmpty()
        val target = if (path == ".") root else WebProjectPaths.resolve(root, path)
        require(isVisible(target)) { "路径不在工作区内" }
        return when (tool) {
            WebProjectFileTool.TOOL_LIST_FILES -> {
                require(target.isDirectory) { "目录不存在" }
                val entries = children(target)
                GSON.toJson(mapOf(
                    "entries" to entries.take(150).map { if (it.isDirectory) "${it.name}/" else it.name },
                    "total" to entries.size
                ))
            }
            WebProjectFileTool.TOOL_READ_FILE -> {
                requireTextFile(target)
                readLines(LocalDiffFileTool(root).readSnapshot(path), args)
            }
            WebProjectFileTool.TOOL_WRITE_FILE -> synchronized(RESOURCE_LOCK) {
                require(!isManagedInternal(target)) { "已发布版本由应用管理，请选择应用项目根目录" }
                require(!target.exists()) { "文件已存在，请先读取，再用 diff 修改" }
                val content = args.getValue(WebProjectFileTool.ARG_CONTENT)
                val parent = target.parentFile ?: error("无效的文件路径")
                requireTextFile(target)
                require('\u0000' !in content && content.toByteArray().size <= MAX_FILE_BYTES) { "不支持写入此内容" }
                check(parent.isDirectory || parent.mkdirs()) { "无法创建文件目录" }
                WebProjectPaths.writeTextAtomically(target, content)
                GSON.toJson(mapOf("path" to path, "written" to true))
            }
            WorkspaceFileActionProtocol.PATCH_FILE -> synchronized(RESOURCE_LOCK) {
                require(!isManagedInternal(target)) { "已发布版本由应用管理，请选择应用项目根目录" }
                requireTextFile(target)
                val result = LocalDiffFileTool(root).apply(
                    relativePath = path,
                    expectedSha256 = args.getValue(WebProjectFileTool.ARG_EXPECTED_SHA256),
                    unifiedDiff = args.getValue(WebProjectFileTool.ARG_CONTENT)
                )
                GSON.toJson(mapOf(
                    "path" to result.relativePath,
                    "applied" to true,
                    "sha256" to result.afterSha256,
                    "hunks" to result.hunkCount,
                    "additions" to result.additions,
                    "deletions" to result.deletions
                ))
            }
            else -> error("不支持该文件操作")
        }
    }

    private fun requireTextFile(target: File) {
        require(target.isFile || !target.exists()) { "请使用文件路径" }
        require(target.extension.lowercase(Locale.ROOT) !in BINARY_EXTENSIONS) { "仅支持 UTF-8 文本文件" }
    }

    private fun readLines(snapshot: LocalDiffFileTool.FileSnapshot, args: Map<String, String>): String {
        fun positiveArgument(key: String, default: Int): Int =
            args[key]?.let { raw ->
                requireNotNull(raw.toIntOrNull()?.takeIf { it > 0 }) { "行号和行数必须是正整数" }
            } ?: default

        val start = positiveArgument(WorkspaceFileActionProtocol.START_LINE, 1)
        val count = positiveArgument(WorkspaceFileActionProtocol.LINE_COUNT, 200).coerceAtMost(400)
        val text = snapshot.content
        val newlineSize = when {
            text.endsWith("\r\n") -> 2
            text.endsWith('\n') || text.endsWith('\r') -> 1
            else -> 0
        }
        val page = ArrayList<String>(count)
        var totalLines = 0
        var remainingChars = MAX_TOOL_FILE_CHARS
        var partialLastLine = false
        if (text.isNotEmpty()) {
            for (line in text.dropLast(newlineSize).lineSequence()) {
                totalLines++
                if (totalLines < start || page.size == count || remainingChars <= 0) continue
                if (line.length > remainingChars && page.isNotEmpty()) {
                    remainingChars = 0
                    continue
                }
                partialLastLine = line.length > remainingChars
                page.add(line.take(remainingChars))
                remainingChars -= line.length + 1
            }
        }
        val lastLine = start.toLong() - 1 + page.size
        return GSON.toJson(mapOf(
            "path" to snapshot.relativePath,
            "sha256" to snapshot.sha256,
            "startLine" to start,
            "totalLines" to totalLines,
            "content" to page.joinToString("\n"),
            "partialLastLine" to partialLastLine,
            "endsWithNewline" to (newlineSize > 0),
            "nextStartLine" to (lastLine + 1).takeIf { lastLine < totalLines }
        ))
    }

    private fun isVisible(file: File): Boolean {
        val segments = WebProjectPaths.relativePath(filesDir.canonicalFile, file.absoluteFile).split('/')
        return segments.firstOrNull() != PRIVATE_CHECKPOINTS &&
            segments.none { it.startsWith('.') && it != WebAppProjectWorkspace.METADATA_DIRECTORY_NAME }
    }

    private fun isManagedInternal(file: File, includeDrafts: Boolean = false): Boolean {
        var parent = file.parentFile
        var child = file
        while (parent != null && parent != filesDir.parentFile) {
            if (WebAppProjectWorkspace.manifestFile(parent).isFile &&
                (child.name in MANAGED_INTERNALS ||
                    (includeDrafts && child.name == WebAppProjectWorkspace.DRAFTS_DIRECTORY_NAME))
            ) return true
            child = parent
            parent = parent.parentFile
        }
        return false
    }

    private fun resourceSources(path: String?): List<String?> {
        val selected = directory(path)
        val project = runCatching { WebAppProjectResolver.resolve(selected) }.getOrNull()
        val projectPath = project?.projectRoot?.let(::displayPath)
        val legacyParent = project?.projectRoot?.parentFile?.parentFile
            ?.takeIf { project.projectRoot.parentFile?.name == COMPILED_PROJECTS &&
                it.parentFile?.name == DIRECTORY }?.let(::displayPath)
        return listOfNotNull(projectPath, legacyParent).plus(path).distinct()
    }

    private fun resourceFiles(directory: File): List<File> {
        if (!directory.isDirectory) return emptyList()
        return Files.walk(directory.toPath()).use { paths ->
            paths.iterator().asSequence()
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted()
                .map { it.toFile() }
                .toList()
        }
    }

    companion object {
        const val ROOT_PATH = "/workspace"
        const val DIRECTORY = "workspaces"
        const val COMPILED_PROJECTS = "generated_apps"
        const val PRIVATE_CHECKPOINTS = "generation_checkpoints"
        const val IMPORTED_ASSET_PATH = "assets/workspace"
        val RESOURCE_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "avif", "ico", "svg",
            "woff", "woff2", "ttf", "otf", "mp3", "wav", "ogg", "mp4", "webm"
        )
        private val BINARY_EXTENSIONS = (RESOURCE_EXTENSIONS - "svg") + setOf(
            "wasm", "zip", "apk", "pdf", "docx", "xlsx", "pptx", "db", "sqlite", "bin"
        )
        private val DIRECTORY_NAME = Regex("[\\p{L}\\p{N}_ -]{1,40}")
        private val UNSAFE_FILENAME = Regex("[^\\p{L}\\p{N}_.-]+")
        private val RESOURCE_LOCK = Any()
        private val GSON = Gson()
        private val CONTAINER_NAMES = setOf(COMPILED_PROJECTS, DIRECTORY, "agent", "memory", "ManTou_Tool")
        private val MANAGED_INTERNALS = setOf(
            WebAppProjectWorkspace.METADATA_DIRECTORY_NAME,
            WebAppProjectWorkspace.RELEASES_DIRECTORY_NAME
        )
        private const val MAX_RESOURCES = 64
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024
        private const val MAX_RESOURCE_BYTES = 16L * 1024 * 1024
        private const val MAX_TOOL_FILE_CHARS = 16_000

        fun restoreSelection(saved: String?): String? = when {
            saved == null || saved == ROOT_PATH -> null
            saved.startsWith("$ROOT_PATH/") -> saved
            else -> "$ROOT_PATH/$DIRECTORY/$saved"
        }

        fun checkpointKey(path: String?): String? {
            if (path == null) return null
            val legacyName = path.removePrefix("$ROOT_PATH/$DIRECTORY/")
            if (legacyName != path && '/' !in legacyName) return legacyName
            return "path-${WebProjectPaths.sha256(path.toByteArray())}"
        }
    }
}

internal sealed interface WorkspaceResourceEntry {
    val name: String

    data class Folder(override val name: String, val children: () -> List<WorkspaceResourceEntry>) : WorkspaceResourceEntry
    data class Resource(override val name: String, val open: () -> InputStream) : WorkspaceResourceEntry
}

internal data class ImportedResourceFolder(val path: String, val importedCount: Int, val skippedCount: Int)
