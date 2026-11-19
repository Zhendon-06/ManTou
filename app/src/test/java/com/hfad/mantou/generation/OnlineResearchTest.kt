package com.hfad.mantou.generation

import com.google.gson.JsonParser
import com.hfad.mantou.data.api.ChatCallConfig
import com.hfad.mantou.data.api.OnlineSearchApi
import com.hfad.mantou.data.api.StreamingApiService.StreamEvent
import com.hfad.mantou.data.database.ChatMessageEntity
import com.hfad.mantou.data.preferences.OnlineSearchSettings
import com.hfad.mantou.utils.project.WebAppProjectExporter
import com.hfad.mantou.utils.project.WebAppProjectManifest
import com.hfad.mantou.utils.project.WebAppProjectValidator
import com.hfad.mantou.utils.project.WebAppProjectWorkspace
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile

class OnlineResearchTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun webFindingsAreBoundedAndCredentialsStayInTheTavilyHeader() = runBlocking {
        var total = 0
        val research = OnlineResearch(fakeApi { req ->
            total++
            assertEquals("api.tavily.com", req.url.host)
            assertEquals("Bearer test-key", req.header("Authorization"))
            assertEquals(null, req.header("X-Tavily-Access-Mode"))
            val json = JsonParser.parseString(Buffer().apply { req.body!!.writeTo(this) }.readUtf8()).asJsonObject
            assertEquals("Android WebView 中文", json["query"].asString)
            assertEquals("basic", json["search_depth"].asString)
            assertFalse(json["auto_parameters"].asBoolean)
            assertFalse(json.toString().contains("test-key"))
            req.json("""{"results":[
                {"url":"https://developer.android.com/reference/android/webkit/WebView","title":"WebView","content":"Official documentation"},
                {"url":"javascript:alert(1)","title":"bad"}
            ]}""")
        })

        val result = find(research, "联网搜索 WebView 官方文档并修改 README.md", """{"web":["Android WebView 中文", "sk-private-value", "C:\\private"],"images":[]}""", key = "test-key")

        assertEquals(1, total)
        assertEquals("WebView", result.pages.single().title)
        assertTrue(result.chatContext().prompt.contains("<online_search_results>"))
        assertTrue(result.sourceLinks().contains("developer.android.com"))
        assertTrue(result.notices.isEmpty())
    }

    @Test
    fun webSearchWorksWithoutAnAccountKey() = runBlocking {
        val research = OnlineResearch(fakeApi { req ->
            assertEquals("api.tavily.com", req.url.host)
            assertEquals("keyless", req.header("X-Tavily-Access-Mode"))
            assertEquals(null, req.header("Authorization"))
            req.json("""{"results":[{"url":"https://developer.android.com/","title":"Android Developers","content":"Official documentation"}]}""")
        })

        val result = find(research, "联网搜索 Android 文档", """{"web":["Android documentation"],"images":[]}""", forCode = false)

        assertEquals("Android Developers", result.pages.single().title)
        assertTrue(result.notices.isEmpty())
    }

    @Test
    fun keylessLimitShowsAnOptionToRetryOrAddAKey() = runBlocking {
        val research = OnlineResearch(fakeApi { req ->
            assertEquals("keyless", req.header("X-Tavily-Access-Mode"))
            req.json("""{"error":{"code":"daily_cap_reached"}}""", code = 429)
        })

        val result = find(research, "联网搜索文档", """{"web":["documentation"],"images":[]}""", forCode = false)

        assertTrue(result.pages.isEmpty())
        assertTrue(result.chatContext().footer.contains("免密钥额度受限"))
        assertTrue(result.chatContext().footer.contains("可稍后重试"))
    }

    @Test
    fun commonsImagesArePresentInPublishedAndExportedProjectsWithCredits() = runBlocking {
        val research = OnlineResearch(fakeApi { req ->
            assertEquals(null, req.header("Authorization"))
            when (req.url.host) {
                "commons.wikimedia.org" -> {
                    assertEquals("moraine lake filetype:bitmap", req.url.queryParameter("gsrsearch"))
                    req.json("""{"query":{"pages":[${commonsPage(42, "cc-by-4.0")},${commonsPage(99, "cc-by-nc-4.0")},${commonsPage(100, "cc-by-4.0", "private.example")}]}}""")
                }
                "thumb.wikimedia.org" -> {
                    assertEquals(null, req.url.query)
                    req.respond("image/png", PNG)
                }
                else -> error("Unexpected search host")
            }
        })
        val findings = find(research, "做一个旅游网页，网上搜几张风景图片", """{"web":[],"images":["moraine lake"]}""")
        assertTrue(findings.notices.toString(), findings.notices.isEmpty())
        assertEquals(42L, findings.images.single().id)
        assertTrue(findings.planningContext().contains("assets/search/commons-42-"))

        val projectRoot = File(temporary.root, "app")
        val workspace = WebAppProjectWorkspace()
        val draft = workspace.create(projectRoot, WebAppProjectManifest(projectId = "travel", displayName = "Travel"))
        val context = findings.install { draft.contentRoot }
        val path = Regex("assets/search/[^\" ]+\\.png").find(context.prompt)!!.value
        assertTrue(context.footer.isBlank())
        val credit = File(draft.contentRoot, "$path.credits.txt")
        val existingCredit = credit.readText()
        assertTrue(existingCredit.contains("Example & Author"))
        assertTrue(existingCredit.contains("https://creativecommons.org/licenses/by/4.0"))
        assertTrue(existingCredit.contains("https://commons.wikimedia.org/wiki/Special:Redirect/page/42"))

        // A previous attempt may have saved the credits before finishing the image write.
        assertTrue(File(draft.contentRoot, path).delete())
        assertEquals(context, findings.install { draft.contentRoot })
        draft.entryFile.writeText("<!doctype html><html><body><img src=\"$path\"></body></html>")
        val validation = WebAppProjectValidator().validate(draft)
        assertTrue(validation.diagnostics.toString(), validation.passed)
        val release = workspace.publishDraft(projectRoot, requireNotNull(draft.version))
        val archive = WebAppProjectExporter.exportZip(release, File(temporary.root, "app.zip"))
        ZipFile(archive).use { zip ->
            assertArrayEquals(PNG, zip.getInputStream(zip.getEntry(path)).use { it.readBytes() })
            assertEquals(existingCredit, zip.getInputStream(zip.getEntry("$path.credits.txt")).bufferedReader().use { it.readText() })
        }
    }

    @Test
    fun disabledSearchSkipsPlanningAndOfflineRequestsSkipExternalSearchAfterPlanning() = runBlocking {
        val research = OnlineResearch(fakeApi { error("Search should not run") })
        val off = research.find("联网查一下", OnlineSearchSettings(false, ""), false, CONFIG, "test", { error("No model call") })
        assertTrue(off.notices.single().contains("已关闭"))
        val progress = mutableListOf<ResearchProgress>()
        val optedOut = research.find(
            "做一个旅游网页，不要联网搜索", OnlineSearchSettings(true, "key"), true, CONFIG, "test",
            { flowOf(StreamEvent.Content("""{"web":["travel page examples"],"images":[]}"""), StreamEvent.Done) },
            onProgress = { progress += it }
        )
        assertEquals(listOf(ResearchProgress.PLANNING), progress)
        assertTrue(optedOut.chatContext().prompt.isEmpty())
    }

    @Test
    fun plansEveryChatTurnAndLetsAContextualFollowUpSearchWithoutTriggerWords() = runBlocking {
        val research = OnlineResearch(fakeApi { req ->
            val query = JsonParser.parseString(Buffer().apply { req.body!!.writeTo(this) }.readUtf8()).asJsonObject
            assertEquals("Android 15 predictive back official documentation", query["query"].asString)
            req.json("""{"results":[{"url":"https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture","title":"Predictive back"}]}""")
        })
        val settings = OnlineSearchSettings(true, "test-key")
        val stages = mutableListOf<ResearchProgress>()
        val local = research.find("把句子改短一点", settings, false, CONFIG, "test", {
            flowOf(StreamEvent.Content("""{"web":[],"images":[]}"""), StreamEvent.Done)
        }, onProgress = { stages += it })
        assertEquals(listOf(ResearchProgress.PLANNING), stages)
        assertTrue(local.chatContext().prompt.isEmpty())

        stages.clear()
        val followUp = research.find(
            "那 15 呢？", settings, false, CONFIG, "test",
            { request ->
                val input = JsonParser.parseString(request.messages.last().content as String).asJsonObject
                assertEquals("那 15 呢？", input["currentRequest"].asString)
                val history = input.getAsJsonArray("recentConversation")
                assertEquals(2, history.size())
                assertEquals("Android 14 的预测性返回手势怎么接入？", history[0].asJsonObject["content"].asString)
                flowOf(StreamEvent.Content("""{"web":["Android 15 predictive back official documentation"],"images":[]}"""), StreamEvent.Done)
            },
            history = listOf(
                ChatMessageEntity(sessionId = 1, role = "user", content = "Android 14 的预测性返回手势怎么接入？", timestamp = 1),
                ChatMessageEntity(sessionId = 1, role = "assistant", content = "可以参考 Android 14 的官方接入文档。", timestamp = 2)
            ),
            onProgress = { stages += it }
        )
        assertEquals(listOf(ResearchProgress.PLANNING, ResearchProgress.SEARCHING), stages)
        assertEquals("Predictive back", followUp.pages.single().title)
    }

    @Test
    fun malformedSearchPlanIsReportedWithoutSendingAQuery() = runBlocking {
        val research = OnlineResearch(fakeApi { error("Search should not run") })
        val result = find(research, "那这个呢？", """{"web":true,"images":[]}""", forCode = false)
        assertTrue(result.chatContext().footer.contains("未能完成联网判断"))
    }

    @Test
    fun rejectedTavilyKeyProducesAVisibleNoticeForTheTurn() = runBlocking {
        val research = OnlineResearch(fakeApi { it.json("{}", code = 401) })
        val result = find(research, "联网搜索文档", """{"web":["documentation"],"images":[]}""", forCode = false, key = "invalid")
        assertTrue(result.pages.isEmpty())
        assertTrue(result.chatContext().footer.contains("请检查密钥"))
    }

    private suspend fun find(
        research: OnlineResearch, requirement: String, response: String, forCode: Boolean = true, key: String = ""
    ): ResearchFindings = research.find(requirement, OnlineSearchSettings(true, key), forCode, CONFIG, "test", {
        flowOf(StreamEvent.Content(response), StreamEvent.Done)
    })

    private fun fakeApi(respond: (Request) -> Response): OnlineSearchApi = OnlineSearchApi(
        OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()
    )

    private fun commonsPage(id: Long, license: String, host: String = "thumb.wikimedia.org") = """
        {"pageid":$id,"index":$id,"title":"File:Lake.png","imageinfo":[{
            "thumbmime":"image/png","thumburl":"https://$host/wikipedia/commons/thumb/a/a1/Lake.png?utm_source=test",
            "extmetadata":{
                "License":{"value":"$license"},"Artist":{"value":"<a>Example &amp; Author</a>"},
                "LicenseShortName":{"value":"CC BY 4.0"},"LicenseUrl":{"value":"https://creativecommons.org/licenses/by/4.0"}
            }
        }]}
    """.trimIndent()

    private fun Request.json(text: String, code: Int = 200) = respond("application/json", text.toByteArray(), code)

    private fun Request.respond(mime: String, bytes: ByteArray, code: Int = 200) = Response.Builder()
        .request(this).protocol(Protocol.HTTP_1_1).code(code).message("Test response")
        .body(bytes.toResponseBody(mime.toMediaType())).build()

    private companion object {
        val CONFIG = ChatCallConfig("https://model.example/v1", "", "test-model", "openai")
        val PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a4p8AAAAASUVORK5CYII=")
    }
}
