package com.hfad.mantou.utils.harness

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hfad.mantou.utils.WebProjectContentServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class GeneratedAppWebViewInspectorInstrumentedTest {

    private lateinit var webView: WebView
    private lateinit var inspector: GeneratedAppWebViewInspector
    private val inspectionEvents = CopyOnWriteArrayList<WebInspectionEvent>()

    @Before
    fun setUp() {
        inspectionEvents.clear()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            inspector = GeneratedAppWebViewInspector(
                webView = webView,
                prepareWebView = ::prepareWebView,
                onEvent = inspectionEvents::add
            )
        }
    }

    @After
    fun tearDown() {
        if (!::webView.isInitialized) return
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            inspector.cancelCurrentInspection()
            webView.stopLoading()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    @Test
    fun validHtmlPassesBuildRuntimeSelfTestAndTestSuite() = runBlocking {
        val build = inspector.inspectBuild(VALID_HTML)
        val runtime = inspector.inspectRuntime(WebInspectionTarget.Html(VALID_HTML))
        val selfTest = inspector.runSelfTests(
            target = WebInspectionTarget.Html(VALID_HTML),
            testScript = GeneratedAppHarnessScripts.selfTest
        )
        val testSuite = inspector.runTestSuite(
            target = WebInspectionTarget.Html(VALID_HTML),
            testScript = GeneratedAppHarnessScripts.testSuite
        )

        assertPassed(build)
        assertPassed(runtime)
        assertPassed(selfTest)
        assertPassed(testSuite)
        assertEquals(WebInspectionStage.BUILD, build.stage)
        assertEquals(WebInspectionStage.RUNTIME, runtime.stage)
        assertEquals(WebInspectionStage.SELF_TEST, selfTest.stage)
        assertEquals(WebInspectionStage.TEST_SUITE, testSuite.stage)
        assertTrue(selfTest.selfTests.isNotEmpty())
        assertTrue(testSuite.selfTests.isNotEmpty())
        assertTrue(
            inspectionEvents.filterIsInstance<WebInspectionEvent.PageFinished>()
                .any { it.status == WebInspectionEvent.Status.SUCCEEDED }
        )
        assertTrue(
            inspectionEvents.filterIsInstance<WebInspectionEvent.ProbeStarted>()
                .map { it.probe }
                .containsAll(WebInspectionEvent.Probe.entries)
        )
        assertTrue(
            inspectionEvents.filterIsInstance<WebInspectionEvent.ProbeResult>()
                .filter { it.probe != WebInspectionEvent.Probe.RUNTIME_HARNESS }
                .all {
                    it.status == WebInspectionEvent.Status.SUCCEEDED &&
                        it.parseStatus == WebInspectionEvent.Status.SUCCEEDED
                }
        )
        assertEquals(
            2,
            inspectionEvents.filterIsInstance<WebInspectionEvent.SelfTestRoot>().size
        )
        assertTrue(
            inspectionEvents.filterIsInstance<WebInspectionEvent.SelfTestCaseResult>()
                .all { it.status == WebInspectionEvent.Status.SUCCEEDED }
        )
        assertEquals(
            4,
            inspectionEvents.filterIsInstance<WebInspectionEvent.Decision>()
                .count { it.status == WebInspectionEvent.Status.SUCCEEDED }
        )
    }

    @Test
    fun javascriptSyntaxErrorFailsBuild() = runBlocking {
        val invalidHtml = VALID_HTML.replace(
            "window.fixtureReady = true;",
            "const broken = ;"
        )

        val report = inspector.inspectBuild(invalidHtml)

        assertFalse(report.passed)
        assertTrue(report.diagnostics.any { it.code == "JS_SYNTAX_ERROR" })
    }

    @Test
    fun synchronousJavascriptErrorFailsRuntimeInspection() = runBlocking {
        val html = htmlWithScript("throw new Error('runtime boom');")

        val report = inspector.inspectRuntime(WebInspectionTarget.Html(html))

        assertFalse(report.passed)
        assertTrue(
            report.diagnostics.any {
                it.severity == WebDiagnosticSeverity.ERROR &&
                    it.message.contains("runtime boom")
            }
        )
    }

    @Test
    fun unhandledPromiseRejectionFailsRuntimeInspection() = runBlocking {
        val html = htmlWithScript("Promise.reject(new Error('promise boom'));")

        val report = inspector.inspectRuntime(
            target = WebInspectionTarget.Html(html),
            settleMillis = 750L
        )

        assertFalse(report.passed)
        assertTrue(
            report.diagnostics.any {
                it.code == "JS_UNHANDLED_REJECTION" && it.message.contains("promise boom")
            }
        )
    }

    @Test
    fun failedTestScriptProducesFailedTestSuiteReport() = runBlocking {
        val report = inspector.runTestSuite(
            target = WebInspectionTarget.Html(VALID_HTML),
            testScript = """
                return [{
                  name: 'forced-failure',
                  passed: false,
                  message: 'expected failure'
                }];
            """.trimIndent()
        )

        assertFalse(report.passed)
        assertEquals(WebInspectionStage.TEST_SUITE, report.stage)
        assertEquals(1, report.selfTests.size)
        assertFalse(report.selfTests.single().passed)
        assertEquals("forced-failure", report.selfTests.single().name)
        assertTrue(
            report.diagnostics.any {
                it.code == "SELF_TEST_FAILED" &&
                    it.category == WebDiagnosticCategory.TEST_SUITE
            }
        )
        val rootEvent = inspectionEvents.filterIsInstance<WebInspectionEvent.SelfTestRoot>()
            .single()
        val caseEvent = inspectionEvents.filterIsInstance<WebInspectionEvent.SelfTestCaseResult>()
            .single()
        val decisionEvent = inspectionEvents.filterIsInstance<WebInspectionEvent.Decision>()
            .single()
        assertEquals(WebInspectionEvent.Status.FAILED, rootEvent.status)
        assertEquals(WebInspectionEvent.Status.FAILED, caseEvent.status)
        assertEquals("SELF_TEST_FAILED", caseEvent.diagnosticCode)
        assertEquals("forced-failure".length, caseEvent.nameCharacterCount)
        assertFalse(caseEvent.toString().contains("forced-failure"))
        assertEquals(WebInspectionEvent.Status.FAILED, decisionEvent.status)
        assertTrue(decisionEvent.hasErrorDiagnostics)
        assertEquals(1, decisionEvent.failedSelfTestCount)
    }

    @Test
    fun projectUrlLoadsCssModulesAndJsonThroughRequestInterceptor() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.cacheDir, "web-project-${System.nanoTime()}")
        try {
            val entry = writeProjectFile(
                projectRoot,
                "index.html",
                """
                    <!DOCTYPE html>
                    <html lang="zh-CN">
                    <head>
                      <meta charset="UTF-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1.0">
                      <title>Multi-file fixture</title>
                      <link rel="stylesheet" href="./styles/app.css">
                      <script type="module" src="./scripts/app.js"></script>
                    </head>
                    <body><main><button id="action">Ready</button></main></body>
                    </html>
                """.trimIndent()
            )
            writeProjectFile(projectRoot, "styles/app.css", "body { color: rgb(1, 2, 3); }")
            writeProjectFile(
                projectRoot,
                "scripts/app.js",
                """
                    import { answer } from './module.js';
                    window.projectReady = fetch('./data/config.json')
                      .then(function (response) { return response.json(); })
                      .then(function (config) {
                        window.projectValue = answer + config.offset;
                        return window.projectValue;
                      });
                """.trimIndent()
            )
            writeProjectFile(projectRoot, "scripts/module.js", "export const answer = 40;")
            writeProjectFile(projectRoot, "data/config.json", "{\"offset\":2}")
            val server = WebProjectContentServer.create(
                projectRoot = projectRoot,
                entryFile = entry,
                projectId = "instrumented-project",
                htmlTransformer = GeneratedAppWebViewInspector::prepareProjectHtmlForInspection
            )
            val interceptor = WebRequestInterceptor(server::intercept)

            val runtime = inspector.inspectRuntime(
                target = WebInspectionTarget.Url(server.entryUrl),
                settleMillis = 1_000L,
                requestInterceptor = interceptor
            )
            val testSuite = inspector.runTestSuite(
                target = WebInspectionTarget.Url(server.entryUrl),
                testScript = """
                    return window.projectReady.then(function (value) {
                      return [{
                        name: 'multi-file-project-loaded',
                        passed: value === 42 && window.projectValue === 42,
                        message: 'Expected module and JSON result to equal 42'
                      }];
                    });
                """.trimIndent(),
                settleMillis = 500L,
                requestInterceptor = interceptor
            )

            assertPassed(runtime)
            assertPassed(testSuite)
            val interceptorEvents = inspectionEvents
                .filterIsInstance<WebInspectionEvent.InterceptorResult>()
            assertTrue(
                interceptorEvents.any { it.status == WebInspectionEvent.Status.INTERCEPTED }
            )
            assertTrue(
                interceptorEvents.all {
                    it.url == null || it.url.length <= WEB_INSPECTION_EVENT_URL_LIMIT
                }
            )
            assertTrue(interceptorEvents.filter { it.url != null }.all { it.urlSha256?.length == 64 })
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun projectRequestInterceptorBlocksExternalScripts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.cacheDir, "blocked-web-project-${System.nanoTime()}")
        try {
            val entry = writeProjectFile(
                projectRoot,
                "index.html",
                """
                    <!DOCTYPE html>
                    <html><head>
                      <meta name="viewport" content="width=device-width, initial-scale=1.0">
                      <title>Blocked fixture</title>
                      <script src="https://example.com/app.js"></script>
                    </head><body><button>Ready</button></body></html>
                """.trimIndent()
            )
            val server = WebProjectContentServer.create(
                projectRoot = projectRoot,
                entryFile = entry,
                htmlTransformer = GeneratedAppWebViewInspector::prepareProjectHtmlForInspection
            )

            val report = inspector.inspectRuntime(
                target = WebInspectionTarget.Url(server.entryUrl),
                settleMillis = 750L,
                requestInterceptor = WebRequestInterceptor(server::intercept)
            )

            assertFalse(report.passed)
            assertTrue(report.diagnostics.any { it.severity == WebDiagnosticSeverity.ERROR })
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    private fun prepareWebView(target: WebView) {
        target.settings.blockNetworkLoads = true
        target.settings.allowFileAccess = false
        target.settings.allowContentAccess = false
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        target.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
        )
        target.layout(0, 0, width, height)
    }

    private fun assertPassed(report: WebInspectionReport) {
        assertTrue(
            "${report.stage} failed: ${report.diagnostics.joinToString { diagnostic ->
                "${diagnostic.code}: ${diagnostic.message} [${diagnostic.location?.source}]"
            }}",
            report.passed
        )
        assertFalse(report.timedOut)
    }

    private fun htmlWithScript(script: String): String {
        return VALID_HTML.replace("window.fixtureReady = true;", script)
    }

    private fun writeProjectFile(root: File, path: String, content: String): File {
        return File(root, path).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }

    private companion object {
        val VALID_HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Harness fixture</title>
              <style>
                * { box-sizing: border-box; }
                html, body { width: 100%; margin: 0; }
                main { padding: 16px; }
              </style>
            </head>
            <body>
              <main id="fixture-root">
                <p>Harness fixture content</p>
                <button id="fixture-action" type="button">Run</button>
              </main>
              <script>
                window.fixtureReady = true;
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
