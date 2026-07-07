package com.hfad.mantou.utils

import android.content.Context
import com.hfad.mantou.R
import java.io.File

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
        val webDir = File(context.filesDir, AgentWorkspace.WEB_DIR)
        if (!webDir.exists()) return emptyList()

        val projectDirs = webDir.listFiles()
            ?.filter { it.isDirectory }
            ?: return emptyList()

        return projectDirs
            .mapNotNull { dir -> primaryHtmlFile(dir)?.let { dir to it } }
            .sortedBy { (dir, _) -> dir.name.lowercase() }
            .map { (dir, html) ->
                DesktopAppItem(
                    displayName = sanitizeDisplayName(dir.name),
                    htmlPath = html.absolutePath,
                    iconRes = pickIconRes(dir.name),
                    lastModified = html.lastModified()
                )
            }
    }

    private fun primaryHtmlFile(projectDir: File): File? {
        val files = projectDir.listFiles()?.filter {
            it.isFile && (it.extension.equals("html", true) || it.extension.equals("htm", true))
        }.orEmpty()
        if (files.isEmpty()) return null
        return files.firstOrNull { it.nameWithoutExtension == projectDir.name } ?: files.first()
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
}
