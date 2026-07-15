package com.hfad.mantou.utils

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

class LocalDiffFileTool(
    workspaceRoot: File,
    private val maxTargetBytes: Long = DEFAULT_MAX_TARGET_BYTES,
    private val maxDiffBytes: Int = DEFAULT_MAX_DIFF_BYTES,
    private val maxHunks: Int = DEFAULT_MAX_HUNKS,
    private val maxOffsetLines: Int = DEFAULT_MAX_OFFSET_LINES
) {

    private val root: File = workspaceRoot.canonicalFile

    init {
        require(root.isDirectory) { "Workspace root must be an existing directory: ${root.path}" }
        require(maxTargetBytes > 0) { "maxTargetBytes must be positive" }
        require(maxDiffBytes > 0) { "maxDiffBytes must be positive" }
        require(maxHunks > 0) { "maxHunks must be positive" }
        require(maxOffsetLines >= 0) { "maxOffsetLines must not be negative" }
    }

    fun readSnapshot(target: File): FileSnapshot = synchronized(FILE_LOCK) {
        readSnapshot(resolveTarget(target))
    }

    fun readSnapshot(relativePath: String): FileSnapshot {
        validateRelativePath(relativePath, "Target path")
        return readSnapshot(File(relativePath))
    }

    fun apply(
        target: File,
        expectedSha256: String,
        unifiedDiff: String,
        validatePatchedContent: ((String) -> Unit)? = null
    ): ApplyResult = synchronized(FILE_LOCK) {
        val normalizedExpectedHash = normalizeSha256(expectedSha256)
        val resolvedTarget = resolveTarget(target)
        val before = readSnapshot(resolvedTarget)
        if (before.sha256 != normalizedExpectedHash) {
            throw DiffException("Target changed after it was read; expected $normalizedExpectedHash but found ${before.sha256}")
        }

        val parsedPatch = UnifiedDiffParser(
            maxDiffBytes = maxDiffBytes,
            maxHunks = maxHunks
        ).parse(unifiedDiff, resolvedTarget.relativePath)
        val patched = applyPatch(before.content, parsedPatch)
        val outputBytes = patched.content.toByteArray(Charsets.UTF_8)
        if (outputBytes.size.toLong() > maxTargetBytes) {
            throw DiffException("Patched file exceeds the $maxTargetBytes byte limit")
        }
        if (patched.content == before.content) {
            throw DiffException("Patch does not change the target file")
        }
        if (validatePatchedContent != null) {
            try {
                validatePatchedContent(patched.content)
            } catch (error: DiffException) {
                throw error
            } catch (error: Exception) {
                throw DiffException(error.message ?: "Patched content failed validation", error)
            }
        }

        val current = readSnapshot(resolveTarget(resolvedTarget.file))
        if (current.sha256 != normalizedExpectedHash) {
            throw DiffException("Target changed while the patch was being prepared")
        }

        writeAtomically(resolvedTarget.file, outputBytes)
        val afterSha256 = sha256(outputBytes)
        ApplyResult(
            file = resolvedTarget.file,
            relativePath = resolvedTarget.relativePath,
            beforeSha256 = before.sha256,
            afterSha256 = afterSha256,
            bytesWritten = outputBytes.size,
            hunkCount = parsedPatch.hunks.size,
            additions = patched.additions,
            deletions = patched.deletions
        )
    }

    fun apply(
        relativePath: String,
        expectedSha256: String,
        unifiedDiff: String,
        validatePatchedContent: ((String) -> Unit)? = null
    ): ApplyResult {
        validateRelativePath(relativePath, "Target path")
        return apply(File(relativePath), expectedSha256, unifiedDiff, validatePatchedContent)
    }

    private fun resolveTarget(target: File): ResolvedTarget {
        rejectTraversalSegments(target.path, "Target path")
        val unresolved = if (target.isAbsolute) target.absoluteFile else File(root, target.path).absoluteFile
        val rootPath = root.toPath()
        val unresolvedPath = unresolved.toPath().normalize()
        val canonical = unresolved.canonicalFile
        val canonicalPath = canonical.toPath()
        if (!canonicalPath.startsWith(rootPath)) {
            throw DiffException("Target is outside the workspace root")
        }

        val pathToInspect = if (unresolvedPath.startsWith(rootPath)) unresolvedPath else canonicalPath
        var currentPath = rootPath
        for (segment in rootPath.relativize(pathToInspect)) {
            currentPath = currentPath.resolve(segment)
            if (Files.isSymbolicLink(currentPath)) {
                throw DiffException("Symbolic links are not valid patch targets")
            }
        }
        if (!Files.isRegularFile(canonicalPath, LinkOption.NOFOLLOW_LINKS)) {
            throw DiffException("Target must be an existing regular file")
        }

        val relativePath = rootPath.relativize(canonicalPath)
            .joinToString("/") { it.toString() }
        validateRelativePath(relativePath, "Target path")
        return ResolvedTarget(canonical, relativePath)
    }

    private fun readSnapshot(target: ResolvedTarget): FileSnapshot {
        val declaredSize = target.file.length()
        if (declaredSize > maxTargetBytes) {
            throw DiffException("Target exceeds the $maxTargetBytes byte limit")
        }

        val bytes = runCatching { target.file.readBytes() }
            .getOrElse { throw DiffException("Unable to read target file", it) }
        if (bytes.size.toLong() > maxTargetBytes) {
            throw DiffException("Target exceeds the $maxTargetBytes byte limit")
        }

        val content = decodeUtf8(bytes)
        if (content.indexOf('\u0000') >= 0) {
            throw DiffException("Binary files are not supported")
        }
        return FileSnapshot(
            file = target.file,
            relativePath = target.relativePath,
            content = content,
            sha256 = sha256(bytes),
            sizeBytes = bytes.size.toLong()
        )
    }

    private fun applyPatch(original: String, patch: ParsedPatch): PatchedContent {
        val document = TextDocument.parse(original)
        if (patch.hasOldNoNewlineMarker && document.hasFinalNewline) {
            throw DiffException("Patch says the old file has no final newline, but the target does")
        }

        val output = mutableListOf<String>()
        var sourceCursor = 0
        var additions = 0
        var deletions = 0
        var markedNewOutputIndex: Int? = null

        patch.hunks.forEachIndexed { hunkIndex, hunk ->
            val declaredIndex = oldCoordinateIndex(hunk.oldStart, hunk.oldCount)
            val oldPattern = hunk.lines
                .asSequence()
                .filter { it.kind != LineKind.ADD }
                .map { it.content }
                .toList()
            val actualIndex = locateHunk(
                source = document.lines,
                sourceCursor = sourceCursor,
                declaredIndex = declaredIndex,
                oldPattern = oldPattern,
                hunkNumber = hunkIndex + 1
            )

            for (index in sourceCursor until actualIndex) {
                output.add(document.lines[index])
            }

            var sourceIndex = actualIndex
            for (line in hunk.lines) {
                when (line.kind) {
                    LineKind.CONTEXT -> {
                        requireSourceLine(document.lines, sourceIndex, line.content, hunkIndex + 1)
                        output.add(document.lines[sourceIndex])
                        sourceIndex++
                        if (line.noNewline) {
                            if (sourceIndex != document.lines.size) {
                                throw DiffException("No-newline marker in hunk ${hunkIndex + 1} is not at old EOF")
                            }
                            markedNewOutputIndex = recordNewNoNewlineMarker(markedNewOutputIndex, output.lastIndex)
                        }
                    }

                    LineKind.REMOVE -> {
                        requireSourceLine(document.lines, sourceIndex, line.content, hunkIndex + 1)
                        sourceIndex++
                        deletions++
                        if (line.noNewline && sourceIndex != document.lines.size) {
                            throw DiffException("No-newline marker in hunk ${hunkIndex + 1} is not at old EOF")
                        }
                    }

                    LineKind.ADD -> {
                        output.add(line.content)
                        additions++
                        if (line.noNewline) {
                            markedNewOutputIndex = recordNewNoNewlineMarker(markedNewOutputIndex, output.lastIndex)
                        }
                    }
                }
            }
            sourceCursor = sourceIndex
        }

        for (index in sourceCursor until document.lines.size) {
            output.add(document.lines[index])
        }
        if (markedNewOutputIndex != null && markedNewOutputIndex != output.lastIndex) {
            throw DiffException("No-newline marker is not at new EOF")
        }

        val hasFinalNewline = when {
            patch.hasNewNoNewlineMarker -> false
            patch.hasOldNoNewlineMarker -> true
            else -> document.hasFinalNewline
        }
        return PatchedContent(
            content = TextDocument.render(output, document.lineSeparator, hasFinalNewline),
            additions = additions,
            deletions = deletions
        )
    }

    private fun locateHunk(
        source: List<String>,
        sourceCursor: Int,
        declaredIndex: Int,
        oldPattern: List<String>,
        hunkNumber: Int
    ): Int {
        if (oldPattern.isEmpty()) {
            if (declaredIndex !in sourceCursor..source.size) {
                throw DiffException("Insertion hunk $hunkNumber points outside the target or overlaps an earlier hunk")
            }
            return declaredIndex
        }

        if (declaredIndex >= sourceCursor && matchesAt(source, oldPattern, declaredIndex)) {
            return declaredIndex
        }

        val maximumStart = source.size - oldPattern.size
        if (maximumStart < sourceCursor) {
            throw DiffException("Context for hunk $hunkNumber was not found")
        }

        val lowerBound = max(
            sourceCursor.toLong(),
            declaredIndex.toLong() - maxOffsetLines.toLong()
        ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val upperBound = min(
            maximumStart.toLong(),
            declaredIndex.toLong() + maxOffsetLines.toLong()
        ).coerceAtLeast(Int.MIN_VALUE.toLong()).toInt()
        if (lowerBound > upperBound) {
            throw DiffException("Context for hunk $hunkNumber was not found within the allowed line offset")
        }

        var matchIndex: Int? = null
        for (candidate in lowerBound..upperBound) {
            if (!matchesAt(source, oldPattern, candidate)) continue
            if (matchIndex != null) {
                throw DiffException("Context for hunk $hunkNumber is ambiguous after applying the line offset")
            }
            matchIndex = candidate
        }
        return matchIndex
            ?: throw DiffException("Context for hunk $hunkNumber does not match the target file")
    }

    private fun matchesAt(source: List<String>, pattern: List<String>, index: Int): Boolean {
        if (index < 0 || index + pattern.size > source.size) return false
        for (offset in pattern.indices) {
            if (source[index + offset] != pattern[offset]) return false
        }
        return true
    }

    private fun requireSourceLine(
        source: List<String>,
        index: Int,
        expected: String,
        hunkNumber: Int
    ) {
        if (index !in source.indices || source[index] != expected) {
            throw DiffException("Context mismatch while applying hunk $hunkNumber")
        }
    }

    private fun recordNewNoNewlineMarker(existing: Int?, outputIndex: Int): Int {
        if (existing != null) {
            throw DiffException("Patch contains more than one new-file no-newline marker")
        }
        return outputIndex
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw DiffException("Target has no parent directory")
        val temporaryPath = runCatching {
            Files.createTempFile(parent.toPath(), ".${target.name}.mantou-diff-", ".tmp")
        }.getOrElse { throw DiffException("Unable to create an atomic-write temporary file", it) }

        try {
            FileOutputStream(temporaryPath.toFile()).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temporaryPath,
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (error: Exception) {
            throw DiffException("Unable to atomically replace target file", error)
        } finally {
            runCatching { Files.deleteIfExists(temporaryPath) }
        }
    }

    private fun normalizeSha256(value: String): String {
        val normalized = value.trim().lowercase()
        if (!SHA256_REGEX.matches(normalized)) {
            throw DiffException("expectedSha256 must contain exactly 64 hexadecimal characters")
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
        }.getOrElse { throw DiffException("Target is not valid UTF-8 text", it) }
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    data class FileSnapshot(
        val file: File,
        val relativePath: String,
        val content: String,
        val sha256: String,
        val sizeBytes: Long
    )

    data class ApplyResult(
        val file: File,
        val relativePath: String,
        val beforeSha256: String,
        val afterSha256: String,
        val bytesWritten: Int,
        val hunkCount: Int,
        val additions: Int,
        val deletions: Int
    )

    class DiffException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

    private data class ResolvedTarget(val file: File, val relativePath: String)

    private data class PatchedContent(
        val content: String,
        val additions: Int,
        val deletions: Int
    )

    private data class ParsedPatch(
        val oldPath: String,
        val newPath: String,
        val hunks: List<Hunk>,
        val hasOldNoNewlineMarker: Boolean,
        val hasNewNoNewlineMarker: Boolean
    )

    private data class Hunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<HunkLine>
    )

    private data class HunkLine(
        val kind: LineKind,
        val content: String,
        val noNewline: Boolean = false
    )

    private enum class LineKind {
        CONTEXT,
        REMOVE,
        ADD
    }

    private class UnifiedDiffParser(
        private val maxDiffBytes: Int,
        private val maxHunks: Int
    ) {

        fun parse(rawDiff: String, expectedPath: String): ParsedPatch {
            val byteSize = rawDiff.toByteArray(Charsets.UTF_8).size
            if (byteSize > maxDiffBytes) {
                throw DiffException("Diff exceeds the $maxDiffBytes byte limit")
            }
            if (rawDiff.indexOf('\u0000') >= 0) {
                throw DiffException("Diff contains a NUL byte")
            }

            val content = unwrapFence(normalizeLineEndings(rawDiff))
            val lines = splitLines(content)
            if (lines.isEmpty()) throw DiffException("Diff is empty")

            var index = 0
            var gitHeaderCount = 0
            while (index < lines.size && !lines[index].startsWith("--- ")) {
                val line = lines[index]
                when {
                    line.startsWith("diff --git ") -> {
                        gitHeaderCount++
                        if (gitHeaderCount > 1) throw DiffException("Multiple file patches are not supported")
                    }

                    line.startsWith("index ") -> Unit
                    UNSUPPORTED_METADATA_PREFIXES.any(line::startsWith) -> {
                        throw DiffException("File creation, deletion, rename, mode, and binary patches are not supported")
                    }

                    else -> throw DiffException("Unexpected content before the file header: $line")
                }
                index++
            }

            if (index >= lines.size) throw DiffException("Missing old-file header")
            val oldPath = parseHeaderPath(lines[index], "--- ", "a/")
            index++
            if (index >= lines.size || !lines[index].startsWith("+++ ")) {
                throw DiffException("Missing new-file header")
            }
            val newPath = parseHeaderPath(lines[index], "+++ ", "b/")
            index++
            if (oldPath != expectedPath || newPath != expectedPath) {
                throw DiffException("Diff header path must exactly match $expectedPath")
            }

            val hunks = mutableListOf<Hunk>()
            var hasOldNoNewlineMarker = false
            var hasNewNoNewlineMarker = false
            var previousOldEnd = 0
            var previousNewEnd = 0

            while (index < lines.size) {
                val headerLine = lines[index]
                if (headerLine.startsWith("--- ") || headerLine.startsWith("diff --git ")) {
                    throw DiffException("Multiple file patches are not supported")
                }
                val headerMatch = HUNK_HEADER_REGEX.matchEntire(headerLine)
                    ?: throw DiffException(
                        "Expected a hunk header at diff line ${index + 1} but found: ${previewDiffLine(headerLine)}"
                    )
                if (hunks.size >= maxHunks) throw DiffException("Diff contains more than $maxHunks hunks")

                val oldStart = parseCoordinate(headerMatch.groupValues[1], "old start")
                val declaredOldCount = parseCount(headerMatch.groupValues[2], "old count")
                val newStart = parseCoordinate(headerMatch.groupValues[3], "new start")
                val declaredNewCount = parseCount(headerMatch.groupValues[4], "new count")
                validateCoordinate(oldStart, declaredOldCount, "old")
                validateCoordinate(newStart, declaredNewCount, "new")

                index++
                var consumedOld = 0
                var producedNew = 0
                var changedLines = 0
                val hunkLines = mutableListOf<HunkLine>()
                var canMarkPreviousLine = false

                while (index < lines.size && !HUNK_HEADER_REGEX.matches(lines[index])) {
                    val bodyLine = lines[index]
                    if (bodyLine == NO_NEWLINE_MARKER) {
                        if (!canMarkPreviousLine || hunkLines.isEmpty() || hunkLines.last().noNewline) {
                            throw DiffException("No-newline marker has no preceding hunk line")
                        }
                        val markedLine = hunkLines.last().copy(noNewline = true)
                        hunkLines[hunkLines.lastIndex] = markedLine
                        when (markedLine.kind) {
                            LineKind.CONTEXT -> {
                                if (hasOldNoNewlineMarker || hasNewNoNewlineMarker) {
                                    throw DiffException("Duplicate no-newline marker")
                                }
                                hasOldNoNewlineMarker = true
                                hasNewNoNewlineMarker = true
                            }

                            LineKind.REMOVE -> {
                                if (hasOldNoNewlineMarker) throw DiffException("Duplicate old-file no-newline marker")
                                hasOldNoNewlineMarker = true
                            }

                            LineKind.ADD -> {
                                if (hasNewNoNewlineMarker) throw DiffException("Duplicate new-file no-newline marker")
                                hasNewNoNewlineMarker = true
                            }
                        }
                        canMarkPreviousLine = false
                        index++
                        continue
                    }
                    if (bodyLine.isEmpty()) throw DiffException("Every hunk line must have a unified-diff prefix")

                    val hunkLine = when (bodyLine[0]) {
                        ' ' -> {
                            consumedOld++
                            producedNew++
                            HunkLine(LineKind.CONTEXT, bodyLine.substring(1))
                        }

                        '-' -> {
                            consumedOld++
                            changedLines++
                            HunkLine(LineKind.REMOVE, bodyLine.substring(1))
                        }

                        '+' -> {
                            producedNew++
                            changedLines++
                            HunkLine(LineKind.ADD, bodyLine.substring(1))
                        }

                        else -> throw DiffException(
                            "Invalid hunk line prefix '${bodyLine[0]}' at diff line ${index + 1}: " +
                                previewDiffLine(bodyLine)
                        )
                    }
                    hunkLines.add(hunkLine)
                    canMarkPreviousLine = true
                    index++
                }

                val hasFollowingHunk = index < lines.size && HUNK_HEADER_REGEX.matches(lines[index])
                if (!hasFollowingHunk &&
                    (consumedOld != declaredOldCount || producedNew != declaredNewCount)
                ) {
                    throw DiffException(
                        "Final hunk body count does not match its header: " +
                            "expected -$declaredOldCount/+$declaredNewCount but found -$consumedOld/+$producedNew"
                    )
                }
                if (changedLines == 0) throw DiffException("Hunk does not change any lines")
                validateCoordinate(oldStart, consumedOld, "old")
                validateCoordinate(newStart, producedNew, "new")

                val oldIndex = oldCoordinateIndex(oldStart, consumedOld)
                val newIndex = oldCoordinateIndex(newStart, producedNew)
                if (oldIndex < previousOldEnd || newIndex < previousNewEnd) {
                    throw DiffException("Hunks are out of order or overlap")
                }
                previousOldEnd = checkedEnd(oldIndex, consumedOld)
                previousNewEnd = checkedEnd(newIndex, producedNew)
                hunks.add(Hunk(oldStart, consumedOld, newStart, producedNew, hunkLines))
            }

            if (hunks.isEmpty()) throw DiffException("Diff contains no hunks")
            return ParsedPatch(
                oldPath = oldPath,
                newPath = newPath,
                hunks = hunks,
                hasOldNoNewlineMarker = hasOldNoNewlineMarker,
                hasNewNoNewlineMarker = hasNewNoNewlineMarker
            )
        }

        private fun parseHeaderPath(line: String, prefix: String, gitPrefix: String): String {
            if (!line.startsWith(prefix)) throw DiffException("Invalid file header")
            var path = line.removePrefix(prefix).substringBefore('\t')
            if (path == DEV_NULL) throw DiffException("File creation and deletion patches are not supported")
            if (path.startsWith('"') || path.endsWith('"')) {
                throw DiffException("Quoted diff paths are not supported")
            }
            if (path.startsWith(gitPrefix)) path = path.removePrefix(gitPrefix)
            validateRelativePath(path, "Diff header path")
            return path
        }

        private fun parseCoordinate(raw: String, label: String): Int {
            val value = raw.toLongOrNull() ?: throw DiffException("Invalid $label")
            if (value !in 0..Int.MAX_VALUE.toLong()) throw DiffException("$label is out of range")
            return value.toInt()
        }

        private fun parseCount(raw: String, label: String): Int {
            if (raw.isEmpty()) return 1
            return parseCoordinate(raw, label)
        }

        private fun validateCoordinate(start: Int, count: Int, side: String) {
            if (count > 0 && start == 0) throw DiffException("$side hunk start must be at least 1 when count is nonzero")
            checkedEnd(oldCoordinateIndex(start, count), count)
        }

        private fun checkedEnd(start: Int, count: Int): Int {
            val end = start.toLong() + count.toLong()
            if (end > Int.MAX_VALUE) throw DiffException("Hunk coordinates are out of range")
            return end.toInt()
        }

        private fun unwrapFence(normalizedDiff: String): String {
            val boundaryTrimmed = normalizedDiff.trim('\n')
            val lines = splitLines(boundaryTrimmed)
            if (lines.isEmpty()) return ""
            if (!lines.first().startsWith("```")) {
                if (lines.any { it.trimStart().startsWith("```") }) {
                    throw DiffException("Malformed diff code fence")
                }
                return boundaryTrimmed
            }

            val opening = lines.first().trim()
            if (!FENCE_REGEX.matches(opening)) throw DiffException("Only diff code fences are supported")
            if (lines.size < 2 || lines.last().trim() != "```") {
                throw DiffException("Diff code fence is not closed")
            }
            return lines.subList(1, lines.lastIndex).joinToString("\n")
        }
    }

    private data class TextDocument(
        val lines: List<String>,
        val lineSeparator: String,
        val hasFinalNewline: Boolean
    ) {
        companion object {
            fun parse(text: String): TextDocument {
                val lineSeparator = when {
                    text.contains("\r\n") -> "\r\n"
                    text.contains('\r') -> "\r"
                    else -> "\n"
                }
                val normalized = normalizeLineEndings(text)
                return TextDocument(
                    lines = splitLines(normalized),
                    lineSeparator = lineSeparator,
                    hasFinalNewline = normalized.endsWith('\n')
                )
            }

            fun render(lines: List<String>, lineSeparator: String, hasFinalNewline: Boolean): String {
                if (lines.isEmpty()) return ""
                return buildString {
                    append(lines.joinToString(lineSeparator))
                    if (hasFinalNewline) append(lineSeparator)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_TARGET_BYTES = 8L * 1024L * 1024L
        const val DEFAULT_MAX_DIFF_BYTES = 2 * 1024 * 1024
        const val DEFAULT_MAX_HUNKS = 512
        const val DEFAULT_MAX_OFFSET_LINES = 10_000

        private const val DIFF_LINE_PREVIEW_CHARS = 240
        private val FILE_LOCK = Any()
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
        private val HUNK_HEADER_REGEX = Regex(
            "^@@ -([0-9]+)(?:,([0-9]+))? \\+([0-9]+)(?:,([0-9]+))? @@(?: .*)?$"
        )
        private val FENCE_REGEX = Regex("^```(?:diff|patch)?$", RegexOption.IGNORE_CASE)
        private val WINDOWS_ABSOLUTE_PATH_REGEX = Regex("^[A-Za-z]:[/\\\\].*")
        private const val NO_NEWLINE_MARKER = "\\ No newline at end of file"
        private const val DEV_NULL = "/dev/null"
        private val UNSUPPORTED_METADATA_PREFIXES = listOf(
            "new file mode ",
            "deleted file mode ",
            "old mode ",
            "new mode ",
            "rename from ",
            "rename to ",
            "copy from ",
            "copy to ",
            "similarity index ",
            "dissimilarity index ",
            "GIT binary patch",
            "Binary files "
        )

        private fun normalizeLineEndings(text: String): String {
            return text.replace("\r\n", "\n").replace('\r', '\n')
        }

        private fun splitLines(text: String): List<String> {
            if (text.isEmpty()) return emptyList()
            val result = mutableListOf<String>()
            var lineStart = 0
            text.forEachIndexed { index, character ->
                if (character == '\n') {
                    result.add(text.substring(lineStart, index))
                    lineStart = index + 1
                }
            }
            if (lineStart < text.length) result.add(text.substring(lineStart))
            return result
        }

        private fun previewDiffLine(line: String): String {
            return if (line.length <= DIFF_LINE_PREVIEW_CHARS) {
                line
            } else {
                line.take(DIFF_LINE_PREVIEW_CHARS) + "…"
            }
        }

        private fun oldCoordinateIndex(start: Int, count: Int): Int {
            return if (count == 0) start else start - 1
        }

        private fun validateRelativePath(path: String, label: String) {
            if (path.isBlank()) throw DiffException("$label must not be blank")
            if (path.indexOf('\u0000') >= 0) throw DiffException("$label contains a NUL byte")
            if (path.startsWith('/') || path.startsWith('\\') || WINDOWS_ABSOLUTE_PATH_REGEX.matches(path)) {
                throw DiffException("$label must be relative")
            }
            if (path.contains('\\')) throw DiffException("$label must use forward slashes")
            rejectTraversalSegments(path, label)
            val segments = path.split('/')
            if (segments.any { it.isEmpty() }) throw DiffException("$label contains an empty path segment")
        }

        private fun rejectTraversalSegments(path: String, label: String) {
            val segments = path.replace('\\', '/').split('/')
            if (segments.any { it == "." || it == ".." }) {
                throw DiffException("$label contains a traversal segment")
            }
        }
    }
}
