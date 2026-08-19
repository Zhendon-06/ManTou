package com.hfad.mantou.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hfad.mantou.data.api.ApiEndpointResolver
import com.hfad.mantou.data.api.ChatCallConfig
import com.hfad.mantou.data.api.ModelTokenUsage
import com.hfad.mantou.data.api.ModelTokenUsageResolver
import com.hfad.mantou.data.logging.ApiDiagnosticContext
import com.hfad.mantou.data.logging.ApiRequestTrace
import com.hfad.mantou.data.logging.ApiLoggingInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object AppIntentDetector {

    private const val TAG = "AppIntentDetector"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val INTENT_MAX_TOKENS = 20
    private const val INTENT_SYSTEM_PROMPT = """你是一个严格的二分类路由器。判断用户当前是否要求直接生成或修改一个可运行、可交互的网页应用。
返回 {"intent":"generate_app"}：用户明确或隐式要求产出网页、App、小程序、工具、游戏、交互页面，或在已有网页应用上继续修改功能和界面。
返回 {"intent":"chat"}：用户只是在询问开发方法、解释/分析/推荐应用、排查已有代码、生成文章或代码片段，或明确说不要生成应用。
示例："给我整一个能记账的" -> {"intent":"generate_app"}
示例："怎么开发一个记账 App" -> {"intent":"chat"}
示例："不要生成网页，只说实现思路" -> {"intent":"chat"}
只返回上述一个 JSON 对象，不要解释，不要输出其他值。"""

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(ApiLoggingInterceptor())
        .build()

    private val createActionWords = listOf(
        "生成", "做一个", "做个", "搞一个", "搞个", "来一个", "来个",
        "弄一个", "弄个", "帮我做", "创建", "制作", "帮我生成",
        "帮我创建", "写一个", "写个", "开发一个", "开发个", "搭建",
        "搭一个", "搭个", "实现一个", "实现个", "整一个", "整个 ",
        "给我做", "给我写", "设计并实现", "generate", "create", "build",
        "develop", "make", "code"
    )
    private val appTargetWords = listOf(
        "app", "application", "应用", "网页", "网站", "页面", "工具", "游戏",
        "小游戏", "小程序", "计算器", "todo", "h5", "html", "看板", "仪表盘",
        "表单", "播放器", "转换器", "编辑器", "生成器", "计时器", "秒表",
        "番茄钟", "白板", "测验", "website", "web app", "webapp", "page",
        "dashboard", "form", "calculator", "game", "tool"
    )
    private val strongAppTargetWords = listOf(
        "app", "application", "应用", "网页", "网站", "页面", "工具", "游戏",
        "小程序", "h5", "html", "website", "web app", "webapp", "page",
        "dashboard", "form", "game", "tool"
    )
    private val nonGenerationContextWords = listOf(
        "怎么做", "如何做", "怎样做", "怎么制作", "如何制作", "怎么开发",
        "如何开发", "怎样开发", "为什么", "有哪些", "哪些技术", "用什么技术",
        "什么框架", "要学什么", "教我", "哪个公司", "谁制作", "谁开发",
        "要多久", "需要多久", "多少钱",
        "what is", "why", "how to", "how do i", "how can i", "how should i"
    )
    private val positionalNonGenerationContextWords = listOf(
        "解释", "分析", "推荐", "比较", "开发流程", "实现思路", "区别", "优缺点",
        "报错", "教程", "建议", "技术方案", "成本", "explain", "compare", "recommend"
    )
    private val negativeGenerationPhrases = listOf(
        "不要生成", "别生成", "不用生成", "无需生成", "不要做成", "别做成",
        "不用做成", "不需要做成", "不要创建", "别创建", "不用创建",
        "不要制作", "别制作", "不要开发", "别开发", "不要修改", "别修改",
        "不是让你做", "不是要你做", "不是让你生成", "不想做成",
        "只需要解释", "只要解释", "只需要分析", "只要分析", "只说思路",
        "只需要方案", "只要方案", "do not create", "don't create",
        "do not build", "don't build"
    )
    private val interactiveCapabilityWords = listOf(
        "能", "可以", "支持", "用来", "用于", "记录", "管理", "追踪", "打卡",
        "计算", "随机", "倒计时", "提醒", "查询", "转换", "编辑", "播放",
        "可视化", "签到", "点名", "统计", "保存", "收藏", "搜索", "抽签", "选择",
        "上传", "导出", "that can", "to track", "to manage", "interactive"
    )
    private val followUpActionWords = listOf(
        "修改", "改成", "换成", "增加", "加上", "添加", "再加", "删掉", "删除",
        "去掉", "调整", "优化", "完善", "修复", "继续", "重做", "换个", "改一下",
        "再大", "再小", "大一点", "小一点", "用深色", "用浅色", "深色模式",
        "浅色模式", "add", "change", "update", "remove", "fix", "continue"
    )

    private fun isAppGenerationByKeywords(
        message: String,
        hasGeneratedAppInSession: Boolean,
    ): Boolean {
        val signals = analyzeLocalSignals(message)
        if (signals.hasNegativeContext) return false
        if (hasGeneratedAppInSession && signals.hasFollowUpAction) return true
        if (signals.hasNonGenerationContext) return false
        return signals.hasCreateAction && signals.hasAppTarget
    }

    suspend fun isAppGenerationIntent(
        context: Context,
        config: ChatCallConfig,
        userMessage: String,
        hasGeneratedAppInSession: Boolean = false,
        tokenUsageListener: ((ModelTokenUsage) -> Unit)? = null,
        diagnosticContext: ApiDiagnosticContext? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val totalStartMs = System.currentTimeMillis()
        Log.d(TAG, "start at=${formatTimestamp(totalStartMs)} message=${preview(userMessage)}")

        if (analyzeLocalSignals(userMessage).hasNegativeContext) {
            Log.d(TAG, "done result=chat source=explicit_negative total=${elapsedSince(totalStartMs)}ms")
            return@withContext false
        }

        val keywordStartMs = System.currentTimeMillis()
        val keywordResult = isAppGenerationByKeywords(userMessage, hasGeneratedAppInSession)
        Log.d(
            TAG,
            "keyword result=$keywordResult elapsed=${elapsedSince(keywordStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
        )
        if (keywordResult) {
            Log.d(TAG, "done result=generate_app source=keyword total=${elapsedSince(totalStartMs)}ms")
            return@withContext true
        }

        val embeddingStartMs = System.currentTimeMillis()
        when (val embeddingDecision = LocalEmbeddingIntentDetector.detect(context, userMessage)) {
            LocalEmbeddingIntentDetector.Decision.GenerateApp -> {
                Log.d(
                    TAG,
                    "embedding result=generate_app elapsed=${elapsedSince(embeddingStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
                )
                Log.d(TAG, "done result=generate_app source=embedding total=${elapsedSince(totalStartMs)}ms")
                return@withContext true
            }
            LocalEmbeddingIntentDetector.Decision.Chat -> {
                Log.d(
                    TAG,
                    "embedding result=chat elapsed=${elapsedSince(embeddingStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
                )
                Log.d(TAG, "done result=chat source=embedding total=${elapsedSince(totalStartMs)}ms")
                return@withContext false
            }
            LocalEmbeddingIntentDetector.Decision.Uncertain -> {
                Log.d(
                    TAG,
                    "embedding result=${embeddingDecision.name} elapsed=${elapsedSince(embeddingStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
                )
                val localGate = shouldUseLlmFallback(userMessage, hasGeneratedAppInSession)
                Log.d(TAG, "localGate llmFallback=${localGate.useLlm} reason=${localGate.reason}")
                if (!localGate.useLlm) {
                    Log.d(TAG, "done result=chat source=local_chat_gate total=${elapsedSince(totalStartMs)}ms")
                    return@withContext false
                }
            }
        }

        try {
            val llmStartMs = System.currentTimeMillis()
            val builder = buildIntentRequest(
                config = config,
                userMessage = userMessage,
                hasGeneratedAppInSession = hasGeneratedAppInSession,
                diagnosticContext = diagnosticContext,
            )
            client.newCall(builder.build()).execute().use { response ->
                val responseBody = response.body?.string()
                if (responseBody == null) {
                    Log.d(
                        TAG,
                        "llm body=null result=chat elapsed=${elapsedSince(llmStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
                    )
                    Log.d(TAG, "done result=chat source=llm_empty_body total=${elapsedSince(totalStartMs)}ms")
                    return@withContext false
                }
                if (!response.isSuccessful) {
                    Log.d(
                        TAG,
                        "llm http=${response.code} result=chat elapsed=${elapsedSince(llmStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
                    )
                    Log.d(TAG, "done result=chat source=llm_http_error total=${elapsedSince(totalStartMs)}ms")
                    return@withContext false
                }

                val content = parseIntentContent(responseBody, config.isAnthropic)
                tokenUsageListener?.let { listener ->
                    val usage = ModelTokenUsageResolver.resolve(
                        responseBody = responseBody,
                        requestTexts = listOf(
                            INTENT_SYSTEM_PROMPT,
                            routingMessage(userMessage, hasGeneratedAppInSession)
                        ),
                        responseText = content.orEmpty()
                    )
                    runCatching { listener(usage) }
                }
                if (content == null) {
                    Log.d(
                        TAG,
                        "llm parsed=null result=chat elapsed=${elapsedSince(llmStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
                    )
                    Log.d(TAG, "done result=chat source=llm_parse_empty total=${elapsedSince(totalStartMs)}ms")
                    return@withContext false
                }

                val result = parseIntentResult(content) == true
                Log.d(
                    TAG,
                    "llm result=${if (result) "generate_app" else "chat"} elapsed=${elapsedSince(llmStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
                )
                Log.d(
                    TAG,
                    "done result=${if (result) "generate_app" else "chat"} source=llm total=${elapsedSince(totalStartMs)}ms"
                )
                result
            }
        } catch (e: Exception) {
            val fallback = isAppGenerationByKeywords(userMessage, hasGeneratedAppInSession)
            Log.d(
                TAG,
                "error=${e.javaClass.simpleName}:${e.message.orEmpty()} fallback=$fallback total=${elapsedSince(totalStartMs)}ms"
            )
            Log.d(TAG, "done result=${if (fallback) "generate_app" else "chat"} source=error_keyword_fallback total=${elapsedSince(totalStartMs)}ms")
            fallback
        }
    }

    private fun elapsedSince(startMs: Long): Long = System.currentTimeMillis() - startMs

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
    }

    private fun preview(message: String): String {
        val compact = message.replace(Regex("\\s+"), " ").take(48)
        return "\"$compact\" len=${message.length}"
    }

    internal fun shouldUseLlmFallbackForTest(
        message: String,
        hasGeneratedAppInSession: Boolean = false,
    ): LocalGateResult = shouldUseLlmFallback(message, hasGeneratedAppInSession)

    internal fun isAppGenerationByKeywordsForTest(
        message: String,
        hasGeneratedAppInSession: Boolean = false,
    ): Boolean = isAppGenerationByKeywords(message, hasGeneratedAppInSession)

    internal fun parseIntentResultForTest(content: String): Boolean? = parseIntentResult(content)

    private fun shouldUseLlmFallback(
        message: String,
        hasGeneratedAppInSession: Boolean,
    ): LocalGateResult {
        val signals = analyzeLocalSignals(message)

        return when {
            signals.hasNegativeContext -> LocalGateResult(
                useLlm = false,
                reason = "negative_generation_context"
            )
            signals.hasNonGenerationContext -> LocalGateResult(
                useLlm = false,
                reason = "tutorial_or_discussion_context"
            )
            hasGeneratedAppInSession && signals.hasFollowUpAction -> LocalGateResult(
                useLlm = true,
                reason = "generated_app_follow_up"
            )
            signals.hasCreateAction && signals.hasAppTarget -> LocalGateResult(
                useLlm = true,
                reason = "create_action_and_app_target"
            )
            signals.hasStrongAppTarget -> LocalGateResult(
                useLlm = true,
                reason = "strong_app_target_without_clear_action"
            )
            signals.hasCreateAction && signals.hasInteractiveCapability -> LocalGateResult(
                useLlm = true,
                reason = "implicit_interactive_creation"
            )
            signals.hasAppTarget -> LocalGateResult(
                useLlm = false,
                reason = "weak_app_target_without_create_action"
            )
            signals.hasCreateAction -> LocalGateResult(
                useLlm = false,
                reason = "create_action_without_app_target"
            )
            else -> LocalGateResult(
                useLlm = false,
                reason = "plain_chat_default"
            )
        }
    }

    private fun analyzeLocalSignals(message: String): LocalSignals {
        val lower = message.lowercase(Locale.ROOT)
        val createActionIndex = lower.firstTermIndex(createActionWords)
        val positionalContextIndex = lower.firstTermIndex(positionalNonGenerationContextWords)
        val lastAppTargetIndex = lower.lastTermIndex(appTargetWords)
        val hasCreateAction = createActionIndex >= 0
        val hasAppTarget = lastAppTargetIndex >= 0
        val hasStrongAppTarget = lower.containsAnyTerm(strongAppTargetWords)
        val hasNegativeContext = lower.containsAnyTerm(negativeGenerationPhrases)
        val hasPositionalNonGenerationContext = positionalContextIndex >= 0 && (
            createActionIndex < 0 ||
                positionalContextIndex < createActionIndex ||
                lastAppTargetIndex < positionalContextIndex
            )
        val hasMetaCapabilityQuestion = isMetaCapabilityQuestion(
            lower = lower,
            hasCreateAction = hasCreateAction,
            hasAppTarget = hasAppTarget,
        )
        return LocalSignals(
            hasCreateAction = hasCreateAction,
            hasAppTarget = hasAppTarget,
            hasStrongAppTarget = hasStrongAppTarget,
            hasNonGenerationContext = hasMetaCapabilityQuestion ||
                lower.containsAnyTerm(nonGenerationContextWords) ||
                hasPositionalNonGenerationContext,
            hasNegativeContext = hasNegativeContext,
            hasInteractiveCapability = lower.containsAnyTerm(interactiveCapabilityWords),
            hasFollowUpAction = lower.containsAnyTerm(followUpActionWords) ||
                lower.containsCompactRewritePhrase(),
        )
    }

    private fun String.containsCompactRewritePhrase(): Boolean {
        val objectMarker = indexOf("把")
        if (objectMarker < 0) return false
        val changeMarker = indexOf("改", objectMarker + 1)
        return changeMarker in (objectMarker + 1)..(objectMarker + 24)
    }

    private fun isMetaCapabilityQuestion(
        lower: String,
        hasCreateAction: Boolean,
        hasAppTarget: Boolean,
    ): Boolean {
        val normalized = lower.trimStart()
        val withoutQuestionPrefix = listOf("请问一下", "请问", "想问一下")
            .firstOrNull { normalized.startsWith(it) }
            ?.let { normalized.removePrefix(it).trimStart() }
            ?: normalized
        if (!hasCreateAction || !hasAppTarget || !withoutQuestionPrefix.startsWith("你")) return false
        val hasDirectRequest = listOf("帮我", "给我", "替我", "为我", "我要", "我想", "我需要")
            .any { lower.contains(it) }
        val looksLikeQuestion = lower.trimEnd().let { text ->
            text.endsWith("吗") || text.endsWith("吗？") || text.endsWith("吗?") ||
                text.endsWith("么") || text.endsWith("么？") || text.endsWith("么?")
        }
        return looksLikeQuestion && !hasDirectRequest
    }

    private fun String.containsAnyTerm(terms: List<String>): Boolean = firstTermIndex(terms) >= 0

    private fun String.firstTermIndex(terms: List<String>): Int {
        var firstIndex = -1
        terms.forEach { term ->
            val index = if (term.any { it.code > 127 }) {
                indexOf(term)
            } else {
                indexOfAsciiTerm(term)
            }
            if (index >= 0 && (firstIndex < 0 || index < firstIndex)) {
                firstIndex = index
            }
        }
        return firstIndex
    }

    private fun String.lastTermIndex(terms: List<String>): Int {
        var lastIndex = -1
        terms.forEach { term ->
            val index = if (term.any { it.code > 127 }) {
                lastIndexOf(term)
            } else {
                lastIndexOfAsciiTerm(term)
            }
            if (index > lastIndex) lastIndex = index
        }
        return lastIndex
    }

    private fun String.indexOfAsciiTerm(term: String): Int {
        var start = indexOf(term)
        while (start >= 0) {
            val end = start + term.length
            val hasLeftBoundary = start == 0 || !this[start - 1].isAsciiWordCharacter()
            val hasRightBoundary = end == length || !this[end].isAsciiWordCharacter()
            if (hasLeftBoundary && hasRightBoundary) return start
            start = indexOf(term, start + 1)
        }
        return -1
    }

    private fun String.lastIndexOfAsciiTerm(term: String): Int {
        var start = indexOf(term)
        var lastIndex = -1
        while (start >= 0) {
            val end = start + term.length
            val hasLeftBoundary = start == 0 || !this[start - 1].isAsciiWordCharacter()
            val hasRightBoundary = end == length || !this[end].isAsciiWordCharacter()
            if (hasLeftBoundary && hasRightBoundary) lastIndex = start
            start = indexOf(term, start + 1)
        }
        return lastIndex
    }

    private fun Char.isAsciiWordCharacter(): Boolean =
        this in 'a'..'z' || this in '0'..'9'

    private fun buildIntentRequest(
        config: ChatCallConfig,
        userMessage: String,
        hasGeneratedAppInSession: Boolean,
        diagnosticContext: ApiDiagnosticContext? = null,
    ): Request.Builder {
        val routingMessage = routingMessage(userMessage, hasGeneratedAppInSession)
        val requestJson = if (config.isAnthropic) {
            gson.toJson(mapOf(
                "model" to config.model,
                "system" to INTENT_SYSTEM_PROMPT,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to routingMessage)
                ),
                "stream" to false,
                "max_tokens" to INTENT_MAX_TOKENS,
            ))
        } else {
            gson.toJson(mapOf(
                "model" to config.model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to INTENT_SYSTEM_PROMPT),
                    mapOf("role" to "user", "content" to routingMessage)
                ),
                "stream" to false,
                "max_tokens" to INTENT_MAX_TOKENS,
            ))
        }

        val requestBody = requestJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder()
            .tag(ApiRequestTrace::class.java, ApiRequestTrace.create(diagnosticContext))
            .url(
                if (config.isAnthropic) {
                    ApiEndpointResolver.anthropicMessagesUrl(config.baseUrl)
                } else {
                    ApiEndpointResolver.openAiChatCompletionsUrl(config.baseUrl)
                }
            )
            .addHeader("Content-Type", "application/json")
            .post(requestBody)

        if (config.isAnthropic) {
            builder.addHeader("anthropic-version", ANTHROPIC_VERSION)
            if (config.apiKey.isNotEmpty()) {
                builder.addHeader("x-api-key", config.apiKey)
            }
        } else if (config.apiKey.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }
        return builder
    }

    private fun routingMessage(
        userMessage: String,
        hasGeneratedAppInSession: Boolean
    ): String {
        return if (hasGeneratedAppInSession) {
            "[会话状态：已经生成过网页应用，修改或继续完善类请求应判为 generate_app]\n$userMessage"
        } else {
            userMessage
        }
    }

    private fun parseIntentResult(content: String): Boolean? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching {
            JsonParser.parseString(content.substring(start, end + 1))
        }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val intent = root.get("intent")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?: return null
        return when (intent) {
            "generate_app" -> true
            "chat" -> false
            else -> null
        }
    }

    private fun parseIntentContent(responseBody: String, isAnthropic: Boolean): String? {
        if (!isAnthropic) {
            val chatResponse = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
            return chatResponse.choices?.firstOrNull()?.message?.content
        }

        val root = JsonParser.parseString(responseBody)
        if (!root.isJsonObject) return null
        val content = root.asJsonObject.get("content") ?: return null
        return when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray.joinToString("") { el ->
                if (el.isJsonObject) {
                    el.asJsonObject.get("text")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        .orEmpty()
                } else {
                    ""
                }
            }.ifBlank { null }
            else -> null
        }
    }

    private data class ChatCompletionResponse(
        val choices: List<Choice>? = null
    )

    internal data class LocalGateResult(
        val useLlm: Boolean,
        val reason: String
    )

    private data class LocalSignals(
        val hasCreateAction: Boolean,
        val hasAppTarget: Boolean,
        val hasStrongAppTarget: Boolean,
        val hasNonGenerationContext: Boolean,
        val hasNegativeContext: Boolean,
        val hasInteractiveCapability: Boolean,
        val hasFollowUpAction: Boolean,
    )

    private data class Choice(
        val message: MessageData? = null
    )

    private data class MessageData(
        val content: String? = null
    )
}
