package com.hfad.mantou.utils.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StreamingWebProjectPlannerTest {

    @Test
    fun validPlanProducesStableManifestAndVisibleProjectJson() {
        val result = WebProjectPlanParser.parse(
            planJson(
                name = "待办",
                files = listOf(
                    file("index.html", "entry", "页面入口"),
                    file("styles/app.css", "style", "视觉系统"),
                    file("scripts/app.js", "logic", "交互逻辑"),
                    file("data/seed.json", "data", "种子数据")
                )
            )
        )

        assertEquals("馒头待办", result.manifest.displayName)
        assertEquals("index.html", result.manifest.entryPoint)
        assertTrue(result.manifest.projectId.startsWith("app-"))
        assertEquals(
            listOf("project.json", "index.html", "styles/app.css", "scripts/app.js", "data/seed.json"),
            result.manifest.files.map(WebAppProjectFile::path)
        )
        assertEquals(
            WebAppProjectFileRole.SCRIPT,
            result.manifest.files.single { it.path == "scripts/app.js" }.role
        )
        assertTrue(result.projectPlanJson.contains("\"name\": \"馒头待办\""))
        assertTrue(result.projectPlanJson.endsWith("\n"))
    }

    @Test
    fun planRequiresIndependentStyleAndScriptFiles() {
        val withoutStyle = planJson(
            files = listOf(
                file("index.html", "entry"),
                file("scripts/app.js", "script"),
                file("data/seed.json", "data")
            )
        )
        val withoutScript = planJson(
            files = listOf(
                file("index.html", "entry"),
                file("styles/app.css", "style"),
                file("data/seed.json", "data")
            )
        )

        assertThrows(WebAppProjectException::class.java) {
            WebProjectPlanParser.parse(withoutStyle)
        }
        assertThrows(WebAppProjectException::class.java) {
            WebProjectPlanParser.parse(withoutScript)
        }
    }

    @Test
    fun planRejectsDuplicateAndEscapingPaths() {
        val duplicate = planJson(
            files = listOf(
                file("index.html", "entry"),
                file("styles/app.css", "style"),
                file("scripts/app.js", "script"),
                file("scripts/app.js", "script")
            )
        )
        val escaping = planJson(
            files = listOf(
                file("index.html", "entry"),
                file("styles/app.css", "style"),
                file("../scripts/app.js", "script")
            )
        )

        assertThrows(WebAppProjectException::class.java) {
            WebProjectPlanParser.parse(duplicate)
        }
        assertThrows(WebAppProjectException::class.java) {
            WebProjectPlanParser.parse(escaping)
        }
    }

    @Test
    fun hostValidatorReportsDeclaredFilesMissingFromDraft() {
        val contentRoot = Files.createTempDirectory("mantou-plan-validation").toFile()
        try {
            File(contentRoot, "index.html").writeText(
                "<!doctype html><html><head></head><body>ready</body></html>"
            )
            File(contentRoot, "styles").mkdirs()
            File(contentRoot, "styles/app.css").writeText("body { margin: 0; }")
            val manifest = WebAppProjectManifest(
                projectId = "missing-script",
                displayName = "馒头缺失脚本",
                files = listOf(
                    WebAppProjectFile("index.html", WebAppProjectFileRole.ENTRY),
                    WebAppProjectFile("styles/app.css", WebAppProjectFileRole.STYLE),
                    WebAppProjectFile("scripts/app.js", WebAppProjectFileRole.SCRIPT)
                )
            )

            val report = WebAppProjectValidator().validate(manifest, contentRoot)

            assertFalse(report.passed)
            assertTrue(report.diagnostics.any {
                it.code == "DECLARED_FILE_MISSING" && it.path == "scripts/app.js"
            })
        } finally {
            contentRoot.deleteRecursively()
        }
    }

    private fun planJson(
        name: String = "馒头测试",
        files: List<String>
    ): String {
        return """
            {
              "schemaVersion": 1,
              "name": "$name",
              "entry": "index.html",
              "files": [${files.joinToString(",")}]
            }
        """.trimIndent()
    }

    private fun file(
        path: String,
        role: String,
        description: String = path
    ): String {
        return """{"path":"$path","role":"$role","description":"$description"}"""
    }
}
