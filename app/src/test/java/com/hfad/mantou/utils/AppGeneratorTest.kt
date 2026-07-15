package com.hfad.mantou.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppGeneratorTest {

    @Test
    fun ensureWebAppIdentityAddsIdAndRuntimeGuard() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"><title>Test</title></head>
            <body><main>App</main></body>
            </html>
        """.trimIndent()

        val result = AppGenerator.ensureWebAppIdentity(html)

        assertTrue(result.contains("name=\"mantou-webapp-id\""))
        assertTrue(result.contains("mantou-webapp-runtime-guard:start"))
        assertTrue(result.contains("MantouApp/1"))
        assertTrue(result.contains("请用馒头App打开"))
        assertTrue(result.indexOf("<meta charset=\"utf-8\">") < result.indexOf("mantou-webapp-runtime-guard:start"))
    }

    @Test
    fun ensureWebAppIdentityAddsGuardToExistingIdentifiedFile() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><meta name="mantou-webapp-id" content="existing-id"></head>
            <body>App</body>
            </html>
        """.trimIndent()

        val result = AppGenerator.ensureWebAppIdentity(html)

        assertEquals("existing-id", AppGenerator.extractWebAppIdentity(result))
        assertTrue(result.contains("mantou-webapp-runtime-guard:start"))
    }

    @Test
    fun ensureWebAppIdentityIsIdempotent() {
        val html = """
            <!DOCTYPE html>
            <html><head></head><body>App</body></html>
        """.trimIndent()

        val once = AppGenerator.ensureWebAppIdentity(html)
        val twice = AppGenerator.ensureWebAppIdentity(once)

        assertEquals(once, twice)
        assertEquals(1, Regex("mantou-webapp-runtime-guard:start").findAll(twice).count())
    }

    @Test
    fun withMantouWebAppUserAgentAppendsTokenOnce() {
        val userAgent = AppGenerator.withMantouWebAppUserAgent("BaseUA")

        assertEquals("BaseUA MantouApp/1", userAgent)
        assertEquals(userAgent, AppGenerator.withMantouWebAppUserAgent(userAgent))
    }

    @Test
    fun dataFileForHtmlUsesSameStemWithJsonExtension() {
        val htmlFile = File("generated_apps/todo_20260614_210000.html")

        val dataFile = AppGenerator.dataFileForHtml(htmlFile)

        assertEquals("todo_20260614_210000.json", dataFile.name)
    }

    @Test
    fun inferAppFileStemAddsMantouPrefixForKnownAppTypes() {
        assertEquals("馒头番茄钟", AppGenerator.inferAppFileStem("帮我生成一个番茄钟"))
        assertEquals("馒头记事本", AppGenerator.inferAppFileStem("生成一个记事本"))
    }

    @Test
    fun inferAppFileStemDoesNotDuplicateMantouPrefix() {
        assertEquals("馒头习惯追踪", AppGenerator.inferAppFileStem("帮我做一个馒头习惯追踪"))
    }

    @Test
    fun extractsUnifiedDiffFromFence() {
        val response = """
            ```diff
            --- a/generated_apps/app.html
            +++ b/generated_apps/app.html
            @@ -1 +1 @@
            -old
            +new
            ```
        """.trimIndent()

        assertEquals(
            """
                --- a/generated_apps/app.html
                +++ b/generated_apps/app.html
                @@ -1 +1 @@
                -old
                +new
            """.trimIndent(),
            AppGenerator.extractUnifiedDiff(response)
        )
    }

    @Test
    fun modificationRetryPromptExplainsHunkHeadersAndCssVariablePrefixes() {
        val snapshot = LocalDiffFileTool.FileSnapshot(
            file = File("generated_apps/app.html"),
            relativePath = "generated_apps/app.html",
            content = ":root {\n--font-xs: 12px;\n}\n",
            sha256 = "a".repeat(64),
            sizeBytes = 31
        )

        val prompt = AppGenerator.buildModificationUserPrompt(
            userMessage = "调大字体",
            snapshot = snapshot,
            previousFailure = "Expected a hunk header but found: --font-xs: 12px;"
        )

        assertTrue(prompt.contains("上一次补丁未通过"))
        assertTrue(prompt.contains("必须先出现 `@@` hunk 头"))
        assertTrue(prompt.contains("oldCount/newCount 必须与该段正文的实际旧行数/新行数完全一致"))
        assertTrue(prompt.contains(" ` --font-xs: 12px;`"))
        assertTrue(prompt.contains("`---font-xs: 12px;`"))
        assertTrue(prompt.contains("`+--font-xs: 12px;`"))
    }

    @Test
    fun smallVisualModificationDoesNotLoadAndroidToolCatalog() {
        assertFalse(AppGenerator.modificationNeedsTools("把开始按钮颜色改成红色"))
        assertFalse(AppGenerator.modificationNeedsTools("调整日历卡片的位置"))
        assertTrue(AppGenerator.modificationNeedsTools("点击按钮后调用相机拍照"))
        assertTrue(AppGenerator.modificationNeedsTools("增加按钮并写入系统日历"))
    }

    @Test
    fun patchedWebAppMustKeepIdentityAndRuntimeGuard() {
        val original = AppGenerator.ensureWebAppIdentity(
            "<!DOCTYPE html><html><head><title>old</title></head><body></body></html>"
        )
        val valid = original.replace("<title>old</title>", "<title>new</title>")

        AppGenerator.validatePatchedWebApp(original, valid)

        val identity = AppGenerator.extractWebAppIdentity(original)!!
        val invalid = valid.replace(identity, "replaced-id")
        runCatching { AppGenerator.validatePatchedWebApp(original, invalid) }
            .onSuccess { throw AssertionError("Expected identity validation to fail") }

        val changedGuard = valid.replace("MantouApp/1", "ChangedRuntime/1")
        runCatching { AppGenerator.validatePatchedWebApp(original, changedGuard) }
            .onSuccess { throw AssertionError("Expected runtime guard validation to fail") }
    }

    @Test
    fun rejectsBareGeneratedPageWithoutEnoughStyleOrInteraction() {
        val html = """
            <!DOCTYPE html>
            <html><head><style>body { background: #fff; } h1 { color: #111; }</style></head>
            <body><h1>馒头记事本</h1><input><button>添加</button>
            <script>document.querySelector('button').onclick = function () {};</script></body></html>
        """.trimIndent()

        val issues = AppGenerator.generatedWebAppQualityIssues(html)

        assertTrue(issues.any { it.contains("CSS") })
        assertTrue(issues.any { it.contains("JavaScript") })
    }

    @Test
    fun cssQualityCheckAcceptsBalancedRuleBracesWithoutRegexSyntaxError() {
        val html = """
            <!DOCTYPE html>
            <html><head><style>body { color: #111; }</style></head>
            <body><button>开始</button><script>document.querySelector('button').onclick = function () { document.body.dataset.started = 'true'; };</script></body></html>
        """.trimIndent()

        val issues = AppGenerator.generatedWebAppQualityIssues(html)

        assertTrue(issues.any { it.contains("CSS") })
    }

    @Test
    fun rejectsGeneratedHtmlWithDroppedOpeningButtonFragment() {
        val html = """
            <!DOCTYPE html>
            <html><head><style>body { color: #111; }</style></head>
            <body><p id="description">="primary-button" id="overlayAction" type="button">开始游戏</button>
            <script>document.getElementById('overlayAction').onclick = function () { document.body.dataset.started = 'true'; };</script></body></html>
        """.trimIndent()

        val issues = AppGenerator.generatedWebAppQualityIssues(html)

        assertTrue(issues.any { it.contains("标签未正确闭合") })
        assertTrue(issues.any { it.contains("缺失标签名") })
    }

    @Test
    fun acceptsStyledInteractiveGeneratedPage() {
        val repeatedCss = (1..8).joinToString("\n") { index ->
            ".card-$index { display: flex; min-height: 48px; padding: 16px; margin: 8px; border-radius: 12px; background: #fff; color: #111; }"
        }
        val repeatedScript = (1..12).joinToString("\n") { index ->
            "function update$index() { state.value = $index; render(); }"
        }
        val html = """
            <!DOCTYPE html>
            <html><head><style>$repeatedCss</style></head>
            <body><main class="card-1"><input id="note"><button id="save">保存</button></main>
            <script>
            var state = { value: 0 };
            function render() { document.body.dataset.value = String(state.value); }
            var ignoredTemplate = '<p>="primary-button" id="ignored"><button>';
            $repeatedScript
            document.getElementById('save').addEventListener('click', function () { update1(); });
            </script></body></html>
        """.trimIndent()

        assertTrue(AppGenerator.generatedWebAppQualityIssues(html).isEmpty())
    }

    @Test
    fun appGenerationOutputLimitIsIndependentFromLargeContextSetting() {
        assertEquals(AppGenerator.APP_GEN_MAX_OUTPUT_TOKENS, AppGenerator.resolveAppGenerationOutputLimit(512_000))
        assertFalse(AppGenerator.resolveAppGenerationOutputLimit(10_000) > 10_000)
        assertEquals(AppGenerator.APP_DIFF_MAX_OUTPUT_TOKENS, AppGenerator.resolveAppDiffOutputLimit(512_000))
        assertFalse(AppGenerator.resolveAppDiffOutputLimit(10_000) > 10_000)
    }
}
