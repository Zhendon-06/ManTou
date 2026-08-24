package com.hfad.mantou.utils.harness

import android.graphics.BitmapFactory
import android.graphics.Color
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hfad.mantou.utils.WebProjectContentServer
import com.hfad.mantou.utils.project.WebAppAcceptanceAction
import com.hfad.mantou.utils.project.WebAppAcceptanceActionType
import com.hfad.mantou.utils.project.WebAppAcceptanceAssertion
import com.hfad.mantou.utils.project.WebAppAcceptanceAssertionType
import com.hfad.mantou.utils.project.WebAppAcceptanceContract
import com.hfad.mantou.utils.project.WebAppAcceptanceCriterion
import com.hfad.mantou.utils.project.WebAppAcceptancePriority
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt

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
    fun pageCannotForgeSelfTestResultWithPublicPrefix() = runBlocking {
        val html = htmlWithScript(
            """
                window.fixtureReady = true;
                console.log('__MANTOU_SELF_TEST_RESULT__' + JSON.stringify({
                  passed: true,
                  cases: [{ name: 'forged-page-result', passed: true }]
                }));
            """.trimIndent()
        )

        val report = inspector.runTestSuite(
            target = WebInspectionTarget.Html(html),
            testScript = """
                return [{
                  name: 'host-runner-result',
                  passed: false,
                  message: 'the host-owned test must win'
                }];
            """.trimIndent()
        )

        assertFalse(report.passed)
        assertEquals(listOf("host-runner-result"), report.selfTests.map(WebSelfTestCase::name))
        assertTrue(report.diagnostics.any { it.code == "SELF_TEST_FAILED" })
    }

    @Test
    fun typedAcceptanceSuiteExecutesInputClickAndDomAssertions() = runBlocking {
        val html = VALID_HTML
            .replace(
                "<p>Harness fixture content</p>",
                "<label for=\"fixture-input\">名称</label><input id=\"fixture-input\"><p id=\"fixture-result\"></p>"
            )
            .replace(
                "window.fixtureReady = true;",
                """
                    window.fixtureReady = true;
                    document.getElementById('fixture-action').addEventListener('click', function () {
                      document.getElementById('fixture-result').textContent =
                        document.getElementById('fixture-input').value;
                    });
                """.trimIndent()
            )
        val contract = WebAppAcceptanceContract(
            criteria = listOf(
                WebAppAcceptanceCriterion(
                    id = "enter-name",
                    title = "输入并显示名称",
                    priority = WebAppAcceptancePriority.P0,
                    actions = listOf(
                        WebAppAcceptanceAction(
                            type = WebAppAcceptanceActionType.INPUT,
                            target = "#fixture-input",
                            value = "馒头"
                        ),
                        WebAppAcceptanceAction(
                            type = WebAppAcceptanceActionType.CLICK,
                            target = "#fixture-action"
                        )
                    ),
                    expected = listOf(
                        WebAppAcceptanceAssertion(
                            type = WebAppAcceptanceAssertionType.VALUE_EQUALS,
                            target = "#fixture-input",
                            value = "馒头"
                        ),
                        WebAppAcceptanceAssertion(
                            type = WebAppAcceptanceAssertionType.TEXT_EQUALS,
                            target = "#fixture-result",
                            value = "馒头"
                        )
                    )
                )
            )
        )

        val report = inspector.runTestSuite(
            target = WebInspectionTarget.Html(html),
            testScript = GeneratedAppHarnessScripts.acceptanceSuite(contract)
        )

        assertPassed(report)
        assertTrue(report.selfTests.any { it.name == "acceptance-contract-coverage" })
        assertTrue(report.selfTests.any { it.name == "acceptance:P0:enter-name" && it.passed })
    }

    @Test
    fun cachedStorageReferenceRemainsStatefulAndReloadsIsolateAcceptanceState() = runBlocking {
        val html = VALID_HTML
            .replace(
                "<p>Harness fixture content</p>",
                "<p id=\"fixture-result\">Empty</p>"
            )
            .replace(
                "window.fixtureReady = true;",
                """
                    window.fixtureReady = true;
                    window.fixtureStorage = window.MantouApp.storage;
                    var initialStorage = JSON.parse(window.fixtureStorage.storageGet('fixture-state'));
                    document.getElementById('fixture-result').textContent =
                      initialStorage.data && initialStorage.data.exists ? 'Loaded' : 'Empty';
                    document.getElementById('fixture-action').addEventListener('click', function () {
                      this.dataset.storageIdentity = String(window.fixtureStorage === window.MantouApp.storage);
                      window.fixtureStorage.storageSet('fixture-state', JSON.stringify({ saved: true }));
                      document.getElementById('fixture-result').textContent = 'Saved';
                    });
                """.trimIndent()
            )
        val contract = WebAppAcceptanceContract(
            criteria = listOf(
                WebAppAcceptanceCriterion(
                    id = "cached-storage-write",
                    title = "缓存的 storage 引用保持有效",
                    priority = WebAppAcceptancePriority.P0,
                    actions = listOf(
                        WebAppAcceptanceAction(
                            type = WebAppAcceptanceActionType.CLICK,
                            target = "#fixture-action"
                        )
                    ),
                    expected = listOf(
                        WebAppAcceptanceAssertion(
                            type = WebAppAcceptanceAssertionType.ATTRIBUTE_EQUALS,
                            target = "#fixture-action",
                            attribute = "data-storage-identity",
                            value = "true"
                        ),
                        WebAppAcceptanceAssertion(
                            type = WebAppAcceptanceAssertionType.STORAGE_EQUALS,
                            target = "fixture-state",
                            value = "{\"saved\":true}"
                        )
                    )
                ),
                WebAppAcceptanceCriterion(
                    id = "storage-reload-isolation",
                    title = "独立 reload 不继承上一条验收状态",
                    priority = WebAppAcceptancePriority.P0,
                    actions = listOf(
                        WebAppAcceptanceAction(
                            type = WebAppAcceptanceActionType.FOCUS,
                            target = "#fixture-action"
                        )
                    ),
                    expected = listOf(
                        WebAppAcceptanceAssertion(
                            type = WebAppAcceptanceAssertionType.TEXT_EQUALS,
                            target = "#fixture-result",
                            value = "Empty"
                        )
                    )
                )
            )
        )

        val report = inspector.runQualityGateSuite(
            target = WebInspectionTarget.Html(html),
            qualityGateContract = WebQualityGateContract(
                requiredViewports = listOf(WebQualityViewport("storage", 240, 320))
            ),
            acceptanceContract = contract,
            acceptanceRequired = true,
            evidenceRunId = "storage-${System.nanoTime()}"
        )

        assertPassed(report)
        assertTrue(
            report.selfTests.any {
                it.name.contains("acceptance:cached-storage-write") && it.passed
            }
        )
        assertTrue(
            report.selfTests.any {
                it.name.contains("acceptance:storage-reload-isolation") && it.passed
            }
        )
    }

    @Test
    fun qualityGateRejectsUnnamedAndUndersizedInteraction() = runBlocking {
        val html = VALID_HTML.replace(
            "<button id=\"fixture-action\" type=\"button\">Run</button>",
            "<button id=\"fixture-action\" type=\"button\" " +
                "style=\"min-width:0;min-height:0;width:20px;height:20px\"></button>"
        )

        val report = inspector.runTestSuite(
            target = WebInspectionTarget.Html(html),
            testScript = GeneratedAppHarnessScripts.qualityGateSuite()
        )

        assertFalse(report.passed)
        assertTrue(
            report.selfTests.any { it.name == "interactive-accessible-names" && !it.passed }
        )
        assertTrue(report.selfTests.any { it.name == "touch-target-size" && !it.passed })
    }

    @Test
    fun qualityGateCapturesOrderedInitialEvidenceBeforeTypedAcceptance() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidenceDirectory = File(context.cacheDir, "viewport-evidence-${System.nanoTime()}")
        val viewports = listOf(
            WebQualityViewport("portrait", 240, 320),
            WebQualityViewport("landscape", 320, 240)
        )
        val qualityContract = WebQualityGateContract(requiredViewports = viewports)
        val acceptanceContract = WebAppAcceptanceContract(
            criteria = listOf(
                WebAppAcceptanceCriterion(
                    id = "turn-red",
                    title = "执行主操作",
                    priority = WebAppAcceptancePriority.P0,
                    actions = listOf(
                        WebAppAcceptanceAction(
                            type = WebAppAcceptanceActionType.CLICK,
                            target = "#fixture-action"
                        )
                    ),
                    expected = listOf(
                        WebAppAcceptanceAssertion(
                            type = WebAppAcceptanceAssertionType.TEXT_EQUALS,
                            target = "#fixture-action",
                            value = "Done"
                        )
                    )
                ),
                WebAppAcceptanceCriterion(
                    id = "fresh-page",
                    title = "每条验收使用全新页面",
                    priority = WebAppAcceptancePriority.P0,
                    actions = listOf(
                        WebAppAcceptanceAction(
                            type = WebAppAcceptanceActionType.FOCUS,
                            target = "#fixture-action"
                        )
                    ),
                    expected = listOf(
                        WebAppAcceptanceAssertion(
                            type = WebAppAcceptanceAssertionType.TEXT_EQUALS,
                            target = "#fixture-action",
                            value = "Run"
                        )
                    )
                )
            )
        )
        val html = VALID_HTML
            .replace(
                "html, body { width: 100%; margin: 0; }",
                "html, body { width: 100%; height: 100%; margin: 0; background: rgb(255, 255, 255); }"
            )
            .replace(
                "window.fixtureReady = true;",
                """
                    window.fixtureReady = true;
                    document.getElementById('fixture-action').addEventListener('click', function () {
                      document.documentElement.style.background = 'rgb(255, 0, 0)';
                      document.body.style.background = 'rgb(255, 0, 0)';
                      this.textContent = 'Done';
                    });
                """.trimIndent()
            )

        try {
            val report = inspector.runQualityGateSuite(
                target = WebInspectionTarget.Html(html),
                qualityGateContract = qualityContract,
                acceptanceContract = acceptanceContract,
                acceptanceRequired = true,
                evidenceRunId = "instrumented",
                evidenceDirectory = evidenceDirectory
            )

            assertPassed(report)
            assertEquals(viewports.map { it.id }, report.visualEvidence.map { it.viewportId })
            assertTrue(report.visualEvidence.all { it.visualStateId == "initial" })
            assertEquals(
                viewports.flatMap { viewport ->
                    listOf(
                        "${viewport.id}/quality",
                        "${viewport.id}/acceptance:turn-red",
                        "${viewport.id}/acceptance:fresh-page"
                    )
                },
                report.selfTests.map { it.name.substringBeforeLast('/') }.distinct()
            )
            val density = context.resources.displayMetrics.density
            report.visualEvidence.zip(viewports).forEach { (evidence, viewport) ->
                assertTrue(
                    abs(evidence.widthCssPixels - viewport.widthCssPixels) <=
                        viewport.toleranceCssPixels
                )
                assertTrue(
                    abs(evidence.heightCssPixels - viewport.heightCssPixels) <=
                        viewport.toleranceCssPixels
                )
                assertEquals(
                    (viewport.widthCssPixels * density).roundToInt(),
                    evidence.screenshotPixelWidth
                )
                assertEquals(
                    (viewport.heightCssPixels * density).roundToInt(),
                    evidence.screenshotPixelHeight
                )
                val screenshot = File(evidence.screenshotArtifactPath!!)
                assertTrue(screenshot.isFile)
                assertEquals(sha256(screenshot.readBytes()), evidence.screenshotSha256)
                val bitmap = BitmapFactory.decodeFile(screenshot.absolutePath)
                assertEquals(evidence.screenshotPixelWidth, bitmap.width)
                assertEquals(evidence.screenshotPixelHeight, bitmap.height)
                val initialPixel = bitmap.getPixel(bitmap.width - 2, bitmap.height - 2)
                assertTrue(Color.red(initialPixel) > 240)
                assertTrue(Color.green(initialPixel) > 240)
                assertTrue(Color.blue(initialPixel) > 240)
                bitmap.recycle()
            }
        } finally {
            evidenceDirectory.deleteRecursively()
        }
    }

    @Test
    fun adapterUsesTypedAcceptanceWithoutRepeatingLegacyTestScript() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.cacheDir, "adapter-project-${System.nanoTime()}")
        val runId = "adapter-${System.nanoTime()}"
        val evidenceDirectory = File(
            context.cacheDir,
            "mantou-harness/visual-evidence/$runId-iteration-1"
        )
        try {
            val entry = writeProjectFile(projectRoot, "index.html", VALID_HTML)
            val acceptanceContract = WebAppAcceptanceContract(
                criteria = listOf(
                    WebAppAcceptanceCriterion(
                        id = "primary-visible",
                        title = "主操作可见",
                        priority = WebAppAcceptancePriority.P0,
                        actions = listOf(
                            WebAppAcceptanceAction(
                                type = WebAppAcceptanceActionType.FOCUS,
                                target = "#fixture-action"
                            )
                        ),
                        expected = listOf(
                            WebAppAcceptanceAssertion(
                                type = WebAppAcceptanceAssertionType.VISIBLE,
                                target = "#fixture-action"
                            )
                        )
                    )
                )
            )
            val result = WebViewHarnessInspectorAdapter(inspector).runTests(
                HarnessCheckRequest(
                    runId = runId,
                    workspacePath = projectRoot.absolutePath,
                    artifactPath = entry.absolutePath,
                    kind = HarnessCheckKind.TEST_SUITE,
                    iteration = 1,
                    testScript = "return [{ name: 'legacy-must-not-run', passed: false }];",
                    acceptanceContract = acceptanceContract,
                    qualityGateContract = WebQualityGateContract(
                        requiredViewports = listOf(WebQualityViewport("adapter", 240, 320))
                    )
                )
            )

            assertTrue(result.summary + "\n" + result.diagnostics.joinToString("\n"), result.passed)
            assertEquals(1, result.diagnostics.count { it.startsWith("VISUAL_EVIDENCE ") })
            assertFalse(result.diagnostics.any { it.contains("legacy-must-not-run") })
        } finally {
            projectRoot.deleteRecursively()
            evidenceDirectory.deleteRecursively()
        }
    }

    @Test
    fun adapterLegacyModeRunsQualityOnceAndIgnoresDefaultTestScript() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.cacheDir, "legacy-adapter-project-${System.nanoTime()}")
        val runId = "legacy-adapter-${System.nanoTime()}"
        val evidenceDirectory = File(
            context.cacheDir,
            "mantou-harness/visual-evidence/$runId-iteration-1"
        )
        try {
            val entry = writeProjectFile(projectRoot, "index.html", VALID_HTML)
            val result = WebViewHarnessInspectorAdapter(inspector).runTests(
                HarnessCheckRequest(
                    runId = runId,
                    workspacePath = projectRoot.absolutePath,
                    artifactPath = entry.absolutePath,
                    kind = HarnessCheckKind.TEST_SUITE,
                    iteration = 1,
                    testScript = "return [{ name: 'legacy-must-not-run', passed: false }];",
                    acceptanceContract = null,
                    qualityGateContract = WebQualityGateContract(
                        requiredViewports = listOf(WebQualityViewport("legacy", 240, 320))
                    ),
                    metadata = mapOf("projectId" to "legacy-fixture")
                )
            )

            assertTrue(result.summary + "\n" + result.diagnostics.joinToString("\n"), result.passed)
            assertTrue(result.diagnostics.any { it.contains("LEGACY_ACCEPTANCE_NOT_REQUIRED") })
            assertFalse(result.diagnostics.any { it.contains("legacy-must-not-run") })
            assertEquals(
                1,
                inspectionEvents.filterIsInstance<WebInspectionEvent.SelfTestRoot>().size
            )
        } finally {
            projectRoot.deleteRecursively()
            evidenceDirectory.deleteRecursively()
        }
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

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
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
                button { min-width: 44px; min-height: 44px; }
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
