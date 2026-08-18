package com.hfad.mantou.utils

import android.content.Context
import com.hfad.mantou.R
import com.hfad.mantou.utils.project.WebAppProjectSnapshot
import com.hfad.mantou.utils.project.WebAppProjectWorkspace
import java.io.File
import java.nio.file.Files
import java.util.Locale

object DesktopAppScanner {

    private val ICON_RES = intArrayOf(
        R.drawable.webappicon1,
        R.drawable.webappicon2,
        R.drawable.webappicon3,
        R.drawable.webappicon4,
        R.drawable.webappicon5,
        R.drawable.webappicon6
    )

    // 形如 _20260625_123456 或 _20260625_123456_2 的后缀（AppGenerator 生成的时间戳）
    private val TIMESTAMP_SUFFIX_REGEX = Regex("_\\d{8}_\\d{6}(?:_\\d+)?$")

    data class DesktopAppItem(
        val displayName: String,
        val htmlPath: String,
        val iconRes: Int,
        val lastModified: Long
    )

    fun loadDesktopApps(context: Context): List<DesktopAppItem> {
        AgentWorkspace.ensureWorkspace(context)
        return scanWebDirectory(File(context.filesDir, AgentWorkspace.WEB_DIR))
    }

    internal fun scanWebDirectory(webDir: File): List<DesktopAppItem> {
        if (!webDir.exists()) return emptyList()

        val projectDirs = webDir.listFiles()
            ?.filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
            ?: return emptyList()

        return projectDirs
            .mapNotNull(::scanProjectDirectory)
            .sortedBy { it.sortKey.lowercase(Locale.US) }
            .map(ScannedDesktopApp::item)
    }

    private fun scanProjectDirectory(projectDir: File): ScannedDesktopApp? {
        if (WebAppProjectWorkspace.metadataDirectory(projectDir).exists()) {
            return scanManagedProject(projectDir)
        }
        val html = primaryHtmlFile(projectDir) ?: return null
        return ScannedDesktopApp(
            sortKey = projectDir.name,
            item = DesktopAppItem(
                displayName = sanitizeDisplayName(projectDir.name),
                htmlPath = html.absolutePath,
                iconRes = pickIconRes(projectDir.name),
                lastModified = html.lastModified()
            )
        )
    }

    private fun scanManagedProject(projectDir: File): ScannedDesktopApp? {
        val release = runCatching {
            WebAppProjectWorkspace().activeRelease(projectDir)
        }.getOrNull() ?: return null
        val entry = safeManagedEntry(release) ?: return null
        val displayName = release.manifest.displayName.trim()
            .takeIf(String::isNotEmpty)
            ?: sanitizeDisplayName(projectDir.name)
        return ScannedDesktopApp(
            sortKey = projectDir.name,
            item = DesktopAppItem(
                displayName = displayName,
                htmlPath = entry.absolutePath,
                iconRes = pickIconRes(release.manifest.projectId),
                lastModified = entry.lastModified()
            )
        )
    }

    private fun safeManagedEntry(snapshot: WebAppProjectSnapshot): File? {
        val contentRoot = runCatching { snapshot.contentRoot.canonicalFile }.getOrNull()
            ?: return null
        val entry = runCatching { snapshot.entryFile.canonicalFile }.getOrNull()
            ?: return null
        if (!entry.toPath().startsWith(contentRoot.toPath()) ||
            !entry.isFile ||
            Files.isSymbolicLink(snapshot.entryFile.toPath()) ||
            (!entry.extension.equals("html", true) && !entry.extension.equals("htm", true))
        ) {
            return null
        }
        return entry
    }

    private fun primaryHtmlFile(projectDir: File): File? {
        val files = projectDir.listFiles()?.filter {
            it.isFile &&
                !Files.isSymbolicLink(it.toPath()) &&
                (it.extension.equals("html", true) || it.extension.equals("htm", true))
        }?.sortedBy { it.name.lowercase(Locale.US) }.orEmpty()
        if (files.isEmpty()) return null
        return files.firstOrNull { it.nameWithoutExtension == projectDir.name }
            ?: files.firstOrNull { it.name.equals("index.html", true) }
            ?: files.first()
    }

    private fun sanitizeDisplayName(rawName: String): String {
        val stripped = TIMESTAMP_SUFFIX_REGEX.replace(rawName, "")
        return stripped.ifBlank { rawName }
    }

    private fun pickIconRes(seed: String): Int {
        val hash = seed.fold(0) { acc, c -> acc * 31 + c.code }
        val index = ((hash % ICON_RES.size) + ICON_RES.size) % ICON_RES.size
        return ICON_RES[index]
    }

    private data class ScannedDesktopApp(
        val sortKey: String,
        val item: DesktopAppItem
    )
}
