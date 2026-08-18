package com.hfad.mantou.utils.project

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object WebAppProjectExporter {

    fun exportZip(snapshot: WebAppProjectSnapshot, destination: File): File {
        require(snapshot.kind != WebAppProjectSnapshotKind.LEGACY) {
            "Only managed web project snapshots can be exported as ZIP"
        }

        val projectRoot = snapshot.projectRoot.canonicalFile
        val contentRoot = snapshot.contentRoot.canonicalFile
        require(contentRoot.isDirectory) { "Project content root does not exist" }
        require(!Files.isSymbolicLink(snapshot.contentRoot.toPath())) {
            "Project content root must not be a symbolic link"
        }
        require(contentRoot.toPath().startsWith(projectRoot.toPath())) {
            "Project content root must stay inside the managed project"
        }

        val canonicalEntry = snapshot.entryFile.canonicalFile
        require(canonicalEntry.isFile && canonicalEntry.toPath().startsWith(contentRoot.toPath())) {
            "Project entry must be a regular file inside the content root"
        }

        val output = destination.absoluteFile
        val outputParent = output.parentFile
            ?: throw IllegalArgumentException("Archive destination must have a parent directory")
        if (!outputParent.exists() && !outputParent.mkdirs()) {
            throw IllegalStateException("Unable to create archive directory: ${outputParent.path}")
        }
        require(outputParent.isDirectory) { "Archive destination parent is not a directory" }
        require(!Files.isSymbolicLink(output.toPath())) {
            "Archive destination must not be a symbolic link"
        }
        require(!output.canonicalFile.toPath().startsWith(contentRoot.toPath())) {
            "Archive destination must stay outside the project content root"
        }

        val sources = collectSources(contentRoot)
        val temporary = File(outputParent, ".${output.name}.${UUID.randomUUID()}.tmp")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
                sources.forEach { source ->
                    val entry = ZipEntry(source.archivePath).apply {
                        time = ZIP_ENTRY_TIME_MILLIS
                    }
                    zip.putNextEntry(entry)
                    if (!source.directory) {
                        BufferedInputStream(FileInputStream(source.file)).use { input ->
                            input.copyTo(zip)
                        }
                    }
                    zip.closeEntry()
                }
            }
            moveIntoPlace(temporary, output)
            return output
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun collectSources(contentRoot: File): List<ArchiveSource> {
        val rootPath = contentRoot.toPath()
        val sources = mutableListOf<ArchiveSource>()
        Files.walk(rootPath).use { paths ->
            paths.skip(1).forEach { path ->
                require(!Files.isSymbolicLink(path)) {
                    "Symbolic links are not allowed in exported web projects"
                }
                val relative = rootPath.relativize(path)
                val segments = (0 until relative.nameCount).map { index ->
                    relative.getName(index).toString()
                }
                require(segments.isNotEmpty() && segments.none(::isUnsafeSegment)) {
                    "Project contains an unsafe archive path"
                }
                require(path.toFile().canonicalFile.toPath().startsWith(rootPath)) {
                    "Project file escapes the content root"
                }

                val directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                require(directory || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    "Project contains an unsupported filesystem entry"
                }
                val relativePath = segments.joinToString("/") + if (directory) "/" else ""
                sources += ArchiveSource(path.toFile(), relativePath, directory)
            }
        }
        return sources.sortedWith(
            compareBy<ArchiveSource> { it.archivePath.removeSuffix("/") }
                .thenByDescending(ArchiveSource::directory)
        )
    }

    private fun isUnsafeSegment(segment: String): Boolean {
        return segment.isEmpty() || segment == "." || segment == ".." ||
            '\u0000' in segment || '/' in segment || '\\' in segment
    }

    private fun moveIntoPlace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private data class ArchiveSource(
        val file: File,
        val archivePath: String,
        val directory: Boolean
    )

    private const val ZIP_ENTRY_TIME_MILLIS = 0L
}
