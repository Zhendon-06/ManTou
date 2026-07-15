package com.hfad.mantou.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.data.GenerateTaskState
import com.hfad.mantou.data.api.ApiConfig
import com.hfad.mantou.data.api.ApiMessage
import com.hfad.mantou.data.api.ChatCallConfig
import com.hfad.mantou.data.api.ChatRequest
import com.hfad.mantou.data.api.ContentPart
import com.hfad.mantou.data.api.ImageUrl
import com.hfad.mantou.data.api.StreamingApiService
import com.hfad.mantou.data.database.AppDatabase
import com.hfad.mantou.data.database.ChatMessageEntity
import com.hfad.mantou.data.database.ChatSessionEntity
import com.hfad.mantou.data.preferences.ContextLimitStore
import com.hfad.mantou.data.repository.ChatRepository
import com.hfad.mantou.utils.AgentWorkspace
import com.hfad.mantou.utils.AppGenerator
import com.hfad.mantou.utils.AppIntentDetector
import com.hfad.mantou.utils.ChatContextFormatter
import com.hfad.mantou.utils.ErrorAnalyzer
import com.hfad.mantou.utils.ImageUtils
import com.hfad.mantou.utils.LocalDiffFileTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ChatRepository(AppDatabase.getDatabase(application).chatDao())
    private val providerRepository = com.hfad.mantou.data.repository.ProviderRepository(
        AppDatabase.getDatabase(application).providerDao()
    )
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentSessionId = MutableLiveData<Long?>()
    val currentSessionId: LiveData<Long?> = _currentSessionId

    private val _messages = MutableLiveData<List<ChatMessage>>()
    val messages: LiveData<List<ChatMessage>> = _messages

    val allSessions: Flow<List<ChatSessionEntity>> = repository.getAllSessions()

    val archivedSessions: Flow<List<ChatSessionEntity>> = repository.getArchivedSessions()

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _runningSessionIds = MutableLiveData<Set<Long>>(emptySet())
    val runningSessionIds: LiveData<Set<Long>> = _runningSessionIds

    private val _isGeneratingApp = MutableLiveData(false)
    val isGeneratingApp: LiveData<Boolean> = _isGeneratingApp

    private val _generateTaskStates = MutableLiveData<Map<Long, GenerateTaskState>>(emptyMap())
    val generateTaskStates: LiveData<Map<Long, GenerateTaskState>> = _generateTaskStates

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _appGenerated = MutableLiveData<String?>()
    val appGenerated: LiveData<String?> = _appGenerated

    private val _activeModelName = MutableLiveData<String?>(null)
    val activeModelName: LiveData<String?> = _activeModelName

    private val _noModelConfigured = MutableLiveData(false)
    val noModelConfigured: LiveData<Boolean> = _noModelConfigured

    private var messagesJob: Job? = null
    private val streamingStates = mutableMapOf<Long, StreamingSessionState>()
    private val appGenerationProgressIntervalMs = 1_500L
    private val streamingUiUpdateIntervalMs = 120L
    private val reconnectWindowMs = 5_000L
    private val reconnectCountdownIntervalMs = 1_000L
    private val requestInterruptedFallback = "出错了，请稍后重试。"
    private val requestFailedSuffix = "\n\n出错了，请稍后重试。"
    private val assistantStreamingPlaceholder = "\u200B"

    init {
        AgentWorkspace.ensureWorkspace(application)
        refreshActiveModel()
    }

    fun refreshActiveModel() {
        viewModelScope.launch {
            val config = resolveActiveChatConfig()
            _activeModelName.value = config?.model
        }
    }

    fun createEmptySession() {
        messagesJob?.cancel()
        viewModelScope.launch {
            val sessionId = repository.createSession("新会话")
            _currentSessionId.value = sessionId
            _messages.value = emptyList()
            loadMessages(sessionId)
            updateSessionLoadingIndicators()
        }
    }

    fun switchToSession(sessionId: Long) {
        messagesJob?.cancel()
        _currentSessionId.value = sessionId
        loadMessages(sessionId)
        restoreGenerateTaskState(sessionId)
        updateSessionLoadingIndicators()
    }

    private fun loadMessages(sessionId: Long) {
        messagesJob = viewModelScope.launch {
            repository.getMessagesBySessionId(sessionId).collect { entities ->
                if (_currentSessionId.value == sessionId) {
                    val dbMessages = entities.map { it.toChatMessage() }
                    publishMessages(sessionId, dbMessages)
                }
            }
        }
    }

    private fun publishMessages(sessionId: Long, dbMessages: List<ChatMessage>) {
        val streamingMessage = streamingStates[sessionId]?.placeholder
        _messages.value = if (streamingMessage != null) {
            dbMessages + streamingMessage
        } else {
            dbMessages
        }
    }

    fun sendMessage(content: String, imagePath: String? = null, imageUris: List<Uri>? = null) {
        val sessionId = _currentSessionId.value ?: run {
            createNewSessionAndSendMessage(content, imagePath, imageUris)
            return
        }

        cancelStreaming(sessionId, persistFallback = true)
        val state = StreamingSessionState(sessionId)
        streamingStates[sessionId] = state
        updateSessionLoadingIndicators()

        state.job = viewModelScope.launch {
            _errorMessage.value = null
            state.streamingContent.clear()
            try {
                withContext(Dispatchers.IO) {
                    AgentWorkspace.appendExplicitMemoryIfNeeded(getApplication(), content)
                }

                val config = resolveActiveChatConfig()
                if (config == null) {
                    _noModelConfigured.value = true
                    return@launch
                }

                _activeModelName.value = config.model

                val finalImagePath = imagePath ?: imageUris?.firstOrNull()?.toString()
                val imageBase64List = loadImageBase64List(imagePath, imageUris)

                repository.sendUserMessage(sessionId, content, finalImagePath)
                state.userMessagePersisted = true

                if (repository.getMessageCount(sessionId) == 1) {
                    repository.updateSessionTitle(sessionId, content.ifEmpty { "[图片]" })
                }

                generateResponseForPersistedUserMessage(state, config, content, imageBase64List)
            } finally {
                finishStreamingState(state)
            }
        }
    }

    private suspend fun loadImageBase64List(
        imagePath: String? = null,
        imageUris: List<Uri>? = null
    ): List<String> = withContext(Dispatchers.IO) {
        val imageBase64List = mutableListOf<String>()
        val urisToProcess = imageUris?.take(ApiConfig.MAX_IMAGE_COUNT)
            ?: listOfNotNull(imagePath?.let { Uri.parse(it) })

        urisToProcess.forEach { uri ->
            ImageUtils.uriToBase64(getApplication(), uri)?.let { imageBase64List.add(it) }
        }
        imageBase64List
    }

    private suspend fun generateResponseForPersistedUserMessage(
        state: StreamingSessionState,
        config: ChatCallConfig,
        content: String,
        imageBase64List: List<String>
    ) {
        val sessionRouting = withContext(Dispatchers.IO) {
            val session = repository.getSessionById(state.sessionId)
            val latestGeneratedPath = repository.getMessagesBySessionIdOnce(state.sessionId)
                .asReversed()
                .firstNotNullOfOrNull { it.appHtmlPath?.takeIf(String::isNotBlank) }
            val appHtmlPath = session?.appHtmlPath?.takeIf(String::isNotBlank) ?: latestGeneratedPath
            SessionRouting(
                isGenerateTask = session?.isGenerateTask == true || appHtmlPath != null,
                appHtmlPath = appHtmlPath
            )
        }

        if (sessionRouting.isGenerateTask) {
            repository.markSessionAsGenerate(state.sessionId, sessionRouting.appHtmlPath)
            val existingFile = sessionRouting.appHtmlPath
                ?.let(::File)
                ?.takeIf { it.isFile }
            if (existingFile != null) {
                generateAppDiffFlow(state, config, content, existingFile)
            } else {
                generateAppFlow(state, config, content)
            }
            return
        }

        val isAppIntent = withContext(Dispatchers.IO) {
            AppIntentDetector.isAppGenerationIntent(
                context = getApplication(),
                config = config,
                userMessage = content,
                hasGeneratedAppInSession = false,
            )
        }

        if (!isAppIntent) {
            normalChatFlow(state, config, imageBase64List)
            return
        }

        repository.markSessionAsGenerate(state.sessionId)
        updateGenerateTaskState(
            GenerateTaskState(
                sessionId = state.sessionId,
                phase = GenerateTaskState.Phase.PREPARING,
                status = "正在准备初版应用"
            )
        )
        generateAppFlow(state, config, content)
    }

    private suspend fun normalChatFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        imageBase64List: List<String>
    ) {
        val sessionId = state.sessionId
        val historyMessages = repository.getMessagesBySessionIdOnce(sessionId)
        val systemPrompt = withContext(Dispatchers.IO) {
            AgentWorkspace.buildSystemPrompt(getApplication())
        }
        val apiMessages = buildApiMessages(historyMessages, systemPrompt, imageBase64List)

        val request = ChatRequest(
            model = config.model,
            messages = apiMessages,
            stream = true
        )

        state.thinkingContent.clear()
        state.reconnectPrefixPending = ""
        addStreamingPlaceholder(state, status = "正在思考", useLoadingLayout = false)

        collectChatStreamWithReconnect(state, config, request)
    }

    private suspend fun collectChatStreamWithReconnect(
        state: StreamingSessionState,
        config: ChatCallConfig,
        request: ChatRequest
    ) {
        var reconnectDeadlineAt: Long? = null
        var activeRequest = request

        while (true) {
            var shouldRetry = false
            var finished = false
            var handledError = false

            StreamingApiService.streamChatCompletion(config, activeRequest)
                .catch { e ->
                    if (e is CancellationException) throw e
                    handledError = true
                    handleApiError(
                        state = state,
                        config = config,
                        rawError = e.message ?: "未知错误",
                        scene = "普通聊天",
                        partialContentToKeep = state.streamingContent.toString().trimEnd().ifEmpty { null }
                    )
                }
                .collect { event ->
                    when (event) {
                        is StreamingApiService.StreamEvent.Start -> {
                            if (reconnectDeadlineAt != null) {
                                clearReconnectStatus(state)
                            }
                        }

                        is StreamingApiService.StreamEvent.Thinking -> {
                            if (state.streamingContent.isEmpty()) {
                                state.thinkingContent.append(event.text)
                                updateStreamingThinking(state, state.thinkingContent.toString())
                            }
                        }

                        is StreamingApiService.StreamEvent.Content -> {
                            val chunk = consumeReconnectChunk(state, event.text)
                            if (chunk.isEmpty()) return@collect
                            state.streamingContent.append(chunk)
                            updateStreamingMessage(state, state.streamingContent.toString())
                        }

                        is StreamingApiService.StreamEvent.Done -> {
                            clearReconnectStatus(state)
                            val finalContent = state.streamingContent.toString().trimEnd()
                            removeStreamingPlaceholder(state)
                            if (finalContent.isNotEmpty()) {
                                addFinalAssistantMessage(state, finalContent)
                            }
                            state.streamingContent.clear()
                            state.thinkingContent.clear()
                            state.reconnectPrefixPending = ""
                            finished = true
                        }

                        is StreamingApiService.StreamEvent.Disconnected -> {
                            val deadlineAt = reconnectDeadlineAt
                                ?: (System.currentTimeMillis() + reconnectWindowMs).also {
                                    reconnectDeadlineAt = it
                                }
                            val remainingMs = deadlineAt - System.currentTimeMillis()
                            if (remainingMs <= 0L) {
                                persistPartialContentWithError(state)
                                finished = true
                            } else {
                                state.reconnectPrefixPending = state.streamingContent.toString()
                                shouldRetry = true
                                updateReconnectStatus(
                                    state,
                                    ceil(remainingMs / 1000.0).toInt().coerceAtLeast(1)
                                )
                            }
                        }

                        is StreamingApiService.StreamEvent.Error -> {
                            handledError = true
                            handleApiError(
                                state = state,
                                config = config,
                                rawError = event.message,
                                scene = "普通聊天",
                                partialContentToKeep = state.streamingContent.toString().trimEnd().ifEmpty { null }
                            )
                        }
                    }
                }

            when {
                finished || handledError -> return
                shouldRetry -> {
                    val deadlineAt = reconnectDeadlineAt ?: return
                    val remainingMs = deadlineAt - System.currentTimeMillis()
                    if (remainingMs <= 0L) {
                        persistPartialContentWithError(state)
                        return
                    }
                    activeRequest = buildReconnectRequest(
                        baseRequest = request,
                        partialContent = state.streamingContent.toString().trimEnd()
                    )
                    delay(min(reconnectCountdownIntervalMs, remainingMs))
                }

                else -> return
            }
        }
    }

    private fun buildReconnectRequest(
        baseRequest: ChatRequest,
        partialContent: String
    ): ChatRequest {
        if (partialContent.isBlank()) {
            return baseRequest
        }

        val reconnectPrompt = AgentWorkspace.buildReconnectResumePrompt(
            context = getApplication(),
            partialContent = partialContent
        )

        val reconnectMessages = baseRequest.messages.toMutableList().apply {
            add(
                ApiMessage(
                    role = ChatMessage.ROLE_ASSISTANT,
                    content = partialContent
                )
            )
            add(
                ApiMessage(
                    role = ChatMessage.ROLE_USER,
                    content = reconnectPrompt
                )
            )
        }

        return baseRequest.copy(messages = reconnectMessages)
    }

    private fun consumeReconnectChunk(state: StreamingSessionState, rawChunk: String): String {
        var chunk = rawChunk
        var pendingPrefix = state.reconnectPrefixPending
        if (pendingPrefix.isEmpty()) return chunk

        val overlap = pendingPrefix.commonPrefixWith(chunk)
        if (overlap.isNotEmpty()) {
            pendingPrefix = pendingPrefix.removePrefix(overlap)
            chunk = chunk.removePrefix(overlap)
        }

        if (chunk.isNotEmpty() && pendingPrefix.isNotEmpty() && chunk.startsWith(pendingPrefix)) {
            chunk = chunk.removePrefix(pendingPrefix)
            pendingPrefix = ""
        }

        state.reconnectPrefixPending = pendingPrefix
        return chunk
    }

    private suspend fun persistPartialContentWithError(state: StreamingSessionState) {
        val content = state.streamingContent.toString().trimEnd()
        clearReconnectStatus(state)
        removeStreamingPlaceholder(state)
        state.streamingContent.clear()
        state.thinkingContent.clear()
        state.reconnectPrefixPending = ""
        if (!state.userMessagePersisted || state.hasFinalAssistantMessage) return
        val finalText = if (content.isNotEmpty()) {
            content + requestFailedSuffix
        } else {
            requestInterruptedFallback
        }
        addFinalAssistantMessage(state, finalText)
    }

    private suspend fun generateAppFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        qualityRetryCount: Int = 0,
        requestMessage: String = userMessage
    ) {
        val sessionId = state.sessionId
        state.isGeneratingApp = true
        updateSessionLoadingIndicators()
        try {
            updateGenerateTaskState(
                GenerateTaskState(
                    sessionId = sessionId,
                    phase = GenerateTaskState.Phase.PREPARING,
                    status = "正在准备初版应用"
                )
            )
            state.thinkingContent.clear()
            addStreamingPlaceholder(state, status = "正在生成应用", useLoadingLayout = true)
            updateStreamingThinking(
                state,
                buildAppGenerationProgressText(elapsedSeconds = 0, receivedChars = 0)
            )

            val apiMessages = listOf(
                ApiMessage(
                    role = "system",
                    content = AppGenerator.buildSystemPrompt(getApplication())
                ),
                ApiMessage(role = ChatMessage.ROLE_USER, content = requestMessage)
            )
            val request = ChatRequest(
                model = config.model,
                messages = apiMessages,
                stream = true,
                maxTokens = AppGenerator.resolveAppGenerationOutputLimit(
                    ContextLimitStore.getTokenLimit(getApplication())
                ),
                temperature = 0.45,
                topP = 0.9
            )

            val htmlBuffer = StringBuilder()
            startAppGenerationProgressHeartbeat(state, htmlBuffer)

            StreamingApiService.streamChatCompletion(config, request)
                .catch { e ->
                    if (e is CancellationException) throw e
                    stopAppGenerationProgressHeartbeat(state)
                    markGenerateTaskError(state, e.message ?: "未知错误", htmlBuffer.toString())
                    handleApiError(state, config, e.message ?: "未知错误", "生成网页应用")
                }
                .collect { event ->
                    when (event) {
                        is StreamingApiService.StreamEvent.Thinking -> {
                            state.thinkingContent.append(event.text)
                            updateStreamingThinking(state, state.thinkingContent.toString())
                        }

                        is StreamingApiService.StreamEvent.Content -> {
                            htmlBuffer.append(event.text)
                            updateGenerateTaskCode(
                                state = state,
                                code = htmlBuffer,
                                phase = GenerateTaskState.Phase.WRITING_INITIAL,
                                status = "正在写入 HTML · ${htmlBuffer.length} 字符"
                            )
                            if (state.thinkingContent.isBlank()) {
                                updateStreamingThinking(
                                    state,
                                    buildAppGenerationProgressText(
                                        elapsedSeconds = null,
                                        receivedChars = htmlBuffer.length
                                    )
                                )
                            }
                        }

                        is StreamingApiService.StreamEvent.Done -> {
                            stopAppGenerationProgressHeartbeat(state)
                            if (state.thinkingContent.isBlank()) {
                                updateStreamingThinking(
                                    state,
                                    "模型已返回代码内容\n正在整理 HTML 并写入本地文件..."
                                )
                            }
                            try {
                                val htmlContent = withContext(Dispatchers.IO) {
                                    AppGenerator.extractHtml(htmlBuffer.toString())
                                }
                                if (htmlContent != null) {
                                    val qualityIssues = withContext(Dispatchers.Default) {
                                        AppGenerator.generatedWebAppQualityIssues(htmlContent)
                                    }
                                    if (qualityIssues.isNotEmpty() && qualityRetryCount == 0) {
                                        updateGenerateTaskCode(
                                            state = state,
                                            code = htmlBuffer.toString(),
                                            phase = GenerateTaskState.Phase.PREPARING,
                                            status = "质量检查未通过，准备重新生成",
                                            force = true
                                        )
                                        updateStreamingThinking(
                                            state,
                                            "初版缺少完整样式或交互，已自动要求模型从头重做...",
                                            force = true
                                        )
                                        generateAppFlow(
                                            state = state,
                                            config = config,
                                            userMessage = userMessage,
                                            qualityRetryCount = 1,
                                            requestMessage = AppGenerator.buildQualityRetryUserPrompt(
                                                userMessage,
                                                qualityIssues
                                            )
                                        )
                                        return@collect
                                    }
                                    AppGenerator.validateGeneratedWebApp(htmlContent)
                                    val file = withContext(Dispatchers.IO) {
                                        AppGenerator.saveHtmlFile(getApplication(), htmlContent, userMessage)
                                    }
                                    val savedCode = withContext(Dispatchers.IO) { file.readText() }
                                    repository.markSessionAsGenerate(sessionId, file.absolutePath)
                                    updateGenerateTaskState(
                                        GenerateTaskState(
                                            sessionId = sessionId,
                                            phase = GenerateTaskState.Phase.COMPLETED,
                                            code = savedCode,
                                            filePath = file.absolutePath,
                                            status = if (qualityRetryCount == 0) {
                                                "初版应用已写入本地"
                                            } else {
                                                "重生成应用已通过质量检查"
                                            }
                                        )
                                    )
                                    removeStreamingPlaceholder(state)
                                    state.thinkingContent.clear()
                                    addFinalAssistantMessage(
                                        state,
                                        "已为你生成网页应用，点击下方预览或全屏查看 👇",
                                        appHtmlPath = file.absolutePath
                                    )
                                    _appGenerated.value = file.absolutePath
                                } else {
                                    if (qualityRetryCount == 0) {
                                        val qualityIssues = listOf("模型返回内容中未找到完整 HTML")
                                        updateGenerateTaskCode(
                                            state = state,
                                            code = htmlBuffer.toString(),
                                            phase = GenerateTaskState.Phase.PREPARING,
                                            status = "HTML 不完整，准备重新生成",
                                            force = true
                                        )
                                        generateAppFlow(
                                            state = state,
                                            config = config,
                                            userMessage = userMessage,
                                            qualityRetryCount = 1,
                                            requestMessage = AppGenerator.buildQualityRetryUserPrompt(
                                                userMessage,
                                                qualityIssues
                                            )
                                        )
                                        return@collect
                                    }
                                    markGenerateTaskError(state, "模型返回内容中未找到合法 HTML", htmlBuffer.toString())
                                    handleApiError(
                                        state,
                                        config,
                                        "模型返回内容中未找到合法 HTML",
                                        "生成网页应用"
                                    )
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                markGenerateTaskError(
                                    state,
                                    e.message ?: "保存 HTML 时出错",
                                    htmlBuffer.toString()
                                )
                                handleApiError(
                                    state,
                                    config,
                                    e.message ?: "保存 HTML 时出错",
                                    "生成网页应用"
                                )
                            }
                        }

                        is StreamingApiService.StreamEvent.Disconnected -> {
                            stopAppGenerationProgressHeartbeat(state)
                            markGenerateTaskError(state, event.message, htmlBuffer.toString())
                            handleApiError(state, config, event.message, "生成网页应用")
                        }

                        is StreamingApiService.StreamEvent.Error -> {
                            stopAppGenerationProgressHeartbeat(state)
                            markGenerateTaskError(state, event.message, htmlBuffer.toString())
                            handleApiError(state, config, event.message, "生成网页应用")
                        }

                        is StreamingApiService.StreamEvent.Start -> Unit
                    }
                }
        } finally {
            stopAppGenerationProgressHeartbeat(state)
            state.isGeneratingApp = false
            updateSessionLoadingIndicators()
        }
    }

    private suspend fun generateAppDiffFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        htmlFile: File,
        diffRetryCount: Int = 0,
        previousFailure: String? = null
    ) {
        val sessionId = state.sessionId
        state.isGeneratingApp = true
        updateSessionLoadingIndicators()
        val diffBuffer = StringBuilder()

        try {
            val diffTool = withContext(Dispatchers.IO) {
                LocalDiffFileTool(getApplication<Application>().filesDir)
            }
            val snapshot = withContext(Dispatchers.IO) { diffTool.readSnapshot(htmlFile) }
            updateGenerateTaskState(
                GenerateTaskState(
                    sessionId = sessionId,
                    phase = GenerateTaskState.Phase.PREPARING,
                    code = snapshot.content,
                    filePath = htmlFile.absolutePath,
                    status = if (diffRetryCount == 0) "正在读取现有应用" else "正在重新生成规范 DIFF",
                    isModification = true
                )
            )

            state.thinkingContent.clear()
            addStreamingPlaceholder(
                state,
                status = if (diffRetryCount == 0) "正在修改应用" else "正在修复补丁格式",
                useLoadingLayout = true
            )
            updateStreamingThinking(
                state,
                if (diffRetryCount == 0) {
                    "已读取当前 HTML，正在生成最小化 diff..."
                } else {
                    "上一份补丁格式不合法，正在基于原文件重新生成规范 unified diff..."
                },
                force = true
            )

            val request = ChatRequest(
                model = config.model,
                messages = listOf(
                    ApiMessage(
                        role = "system",
                        content = AppGenerator.buildModificationSystemPrompt(
                            context = getApplication(),
                            relativePath = snapshot.relativePath,
                            expectedSha256 = snapshot.sha256,
                            includeTools = AppGenerator.modificationNeedsTools(userMessage)
                        )
                    ),
                    ApiMessage(
                        role = ChatMessage.ROLE_USER,
                        content = AppGenerator.buildModificationUserPrompt(
                            userMessage,
                            snapshot,
                            previousFailure
                        )
                    )
                ),
                stream = true,
                maxTokens = AppGenerator.resolveAppDiffOutputLimit(
                    ContextLimitStore.getTokenLimit(getApplication())
                ),
                temperature = 0.2,
                topP = 0.9
            )

            StreamingApiService.streamChatCompletion(config, request)
                .catch { error ->
                    if (error is CancellationException) throw error
                    val message = error.message ?: "生成 diff 时出错"
                    if (StreamingApiService.isRetryableNetworkFailure(message) &&
                        retryAppDiffFlow(
                            state = state,
                            config = config,
                            userMessage = userMessage,
                            htmlFile = htmlFile,
                            diffRetryCount = diffRetryCount,
                            failure = message,
                            diffCode = diffBuffer,
                            status = "网络连接超时，准备自动重试"
                        )
                    ) {
                        return@catch
                    }
                    markGenerateTaskError(
                        state,
                        message,
                        diffBuffer.toString(),
                        htmlFile.absolutePath,
                        isModification = true
                    )
                    handleApiError(state, config, message, "增量修改网页应用")
                }
                .collect { event ->
                    when (event) {
                        is StreamingApiService.StreamEvent.Thinking -> {
                            state.thinkingContent.append(event.text)
                            updateStreamingThinking(state, state.thinkingContent.toString())
                        }

                        is StreamingApiService.StreamEvent.Content -> {
                            diffBuffer.append(event.text)
                            updateGenerateTaskCode(
                                state = state,
                                code = diffBuffer,
                                phase = GenerateTaskState.Phase.WRITING_DIFF,
                                status = "正在写入 DIFF · ${diffBuffer.length} 字符",
                                filePath = htmlFile.absolutePath,
                                isModification = true
                            )
                        }

                        is StreamingApiService.StreamEvent.Done -> {
                            updateGenerateTaskCode(
                                state = state,
                                code = diffBuffer.toString(),
                                phase = GenerateTaskState.Phase.APPLYING_DIFF,
                                status = "正在校验并应用 DIFF",
                                filePath = htmlFile.absolutePath,
                                isModification = true,
                                force = true
                            )
                            try {
                                val unifiedDiff = AppGenerator.extractUnifiedDiff(diffBuffer.toString())
                                    ?: throw LocalDiffFileTool.DiffException("模型没有返回合法 unified diff")
                                val applyResult = withContext(Dispatchers.IO) {
                                    diffTool.apply(htmlFile, snapshot.sha256, unifiedDiff) { patchedContent ->
                                        AppGenerator.validatePatchedWebApp(snapshot.content, patchedContent)
                                    }
                                }
                                val updatedCode = withContext(Dispatchers.IO) { htmlFile.readText() }
                                repository.markSessionAsGenerate(sessionId, htmlFile.absolutePath)
                                updateGenerateTaskState(
                                    GenerateTaskState(
                                        sessionId = sessionId,
                                        phase = GenerateTaskState.Phase.COMPLETED,
                                        code = updatedCode,
                                        filePath = htmlFile.absolutePath,
                                        status = "已应用 ${applyResult.hunkCount} 处修改 · +${applyResult.additions} -${applyResult.deletions}",
                                        isModification = true
                                    )
                                )
                                removeStreamingPlaceholder(state)
                                state.thinkingContent.clear()
                                addFinalAssistantMessage(
                                    state,
                                    "已按你的要求增量修改应用，点击代码窗口可查看最新源码 👇",
                                    appHtmlPath = htmlFile.absolutePath
                                )
                                _appGenerated.value = htmlFile.absolutePath
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                logGeneratedAppDiffFailure(
                                    state = state,
                                    htmlFile = htmlFile,
                                    diffRetryCount = diffRetryCount,
                                    diffCode = diffBuffer,
                                    error = error
                                )
                                if (error is LocalDiffFileTool.DiffException &&
                                    retryAppDiffFlow(
                                        state = state,
                                        config = config,
                                        userMessage = userMessage,
                                        htmlFile = htmlFile,
                                        diffRetryCount = diffRetryCount,
                                        failure = error.message ?: "补丁格式不合法",
                                        diffCode = diffBuffer,
                                        status = "补丁格式或上下文无效，准备自动重试"
                                    )
                                ) {
                                    return@collect
                                }
                                markGenerateTaskError(
                                    state,
                                    error.message ?: "应用 diff 时出错",
                                    diffBuffer.toString(),
                                    htmlFile.absolutePath,
                                    isModification = true
                                )
                                handleApiError(
                                    state,
                                    config,
                                    error.message ?: "应用 diff 时出错",
                                    "增量修改网页应用"
                                )
                            }
                        }

                        is StreamingApiService.StreamEvent.Disconnected -> {
                            if (retryAppDiffFlow(
                                    state = state,
                                    config = config,
                                    userMessage = userMessage,
                                    htmlFile = htmlFile,
                                    diffRetryCount = diffRetryCount,
                                    failure = event.message,
                                    diffCode = diffBuffer,
                                    status = "网络连接中断，准备自动重试"
                                )
                            ) {
                                return@collect
                            }
                            markGenerateTaskError(
                                state,
                                event.message,
                                diffBuffer.toString(),
                                htmlFile.absolutePath,
                                isModification = true
                            )
                            handleApiError(state, config, event.message, "增量修改网页应用")
                        }

                        is StreamingApiService.StreamEvent.Error -> {
                            if (StreamingApiService.isRetryableNetworkFailure(event.message) &&
                                retryAppDiffFlow(
                                    state = state,
                                    config = config,
                                    userMessage = userMessage,
                                    htmlFile = htmlFile,
                                    diffRetryCount = diffRetryCount,
                                    failure = event.message,
                                    diffCode = diffBuffer,
                                    status = "网络连接超时，准备自动重试"
                                )
                            ) {
                                return@collect
                            }
                            markGenerateTaskError(
                                state,
                                event.message,
                                diffBuffer.toString(),
                                htmlFile.absolutePath,
                                isModification = true
                            )
                            handleApiError(state, config, event.message, "增量修改网页应用")
                        }

                        is StreamingApiService.StreamEvent.Start -> Unit
                    }
                }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            markGenerateTaskError(
                state,
                error.message ?: "无法读取现有应用",
                diffBuffer.toString(),
                htmlFile.absolutePath,
                isModification = true
            )
            handleApiError(state, config, error.message ?: "无法读取现有应用", "增量修改网页应用")
        } finally {
            state.isGeneratingApp = false
            updateSessionLoadingIndicators()
        }
    }

    private suspend fun retryAppDiffFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        htmlFile: File,
        diffRetryCount: Int,
        failure: String,
        diffCode: CharSequence,
        status: String
    ): Boolean {
        if (diffRetryCount != 0) return false
        updateGenerateTaskCode(
            state = state,
            code = diffCode,
            phase = GenerateTaskState.Phase.PREPARING,
            status = status,
            filePath = htmlFile.absolutePath,
            isModification = true,
            force = true
        )
        generateAppDiffFlow(
            state = state,
            config = config,
            userMessage = userMessage,
            htmlFile = htmlFile,
            diffRetryCount = 1,
            previousFailure = failure
        )
        return true
    }

    private fun logGeneratedAppDiffFailure(
        state: StreamingSessionState,
        htmlFile: File,
        diffRetryCount: Int,
        diffCode: CharSequence,
        error: Exception
    ) {
        val response = diffCode.toString()
        Log.e(
            WEB_APP_DIFF_LOG_TAG,
            "Generated app diff failed: sessionId=${state.sessionId}, " +
                "attempt=${diffRetryCount + 1}, file=${htmlFile.absolutePath}, " +
                "responseChars=${response.length}",
            error
        )
        Log.e(
            WEB_APP_DIFF_LOG_TAG,
            "Model diff response preview:\n${buildDiffLogPreview(response)}"
        )
    }

    private fun buildDiffLogPreview(response: String): String {
        if (response.length <= APP_DIFF_LOG_PREVIEW_CHARS) return response
        val previewPartChars = APP_DIFF_LOG_PREVIEW_CHARS / 2
        return buildString(APP_DIFF_LOG_PREVIEW_CHARS + 80) {
            append(response.take(previewPartChars))
            append("\n… ${response.length - APP_DIFF_LOG_PREVIEW_CHARS} characters omitted …\n")
            append(response.takeLast(previewPartChars))
        }
    }

    private fun startAppGenerationProgressHeartbeat(
        state: StreamingSessionState,
        htmlBuffer: StringBuilder
    ) {
        stopAppGenerationProgressHeartbeat(state)
        state.appGenerationProgressJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                if (state.thinkingContent.isBlank()) {
                    val elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                    updateStreamingThinking(
                        state,
                        buildAppGenerationProgressText(
                            elapsedSeconds = elapsedSeconds,
                            receivedChars = htmlBuffer.length
                        )
                    )
                }
                delay(appGenerationProgressIntervalMs)
            }
        }
    }

    private fun stopAppGenerationProgressHeartbeat(state: StreamingSessionState) {
        state.appGenerationProgressJob?.cancel()
        state.appGenerationProgressJob = null
    }

    private fun buildAppGenerationProgressText(
        elapsedSeconds: Int?,
        receivedChars: Int
    ): String {
        val elapsedLine = elapsedSeconds?.let { "已等待 ${it}s" } ?: "正在接收模型返回"
        val stageLine = when {
            receivedChars <= 0 -> "已提交生成请求，正在等待模型开始返回内容..."
            receivedChars < 2_000 -> "模型已开始返回内容，正在接收 HTML 代码..."
            receivedChars < 12_000 -> "正在持续接收页面结构、样式和交互代码..."
            else -> "已收到较多代码内容，正在等待模型完成收尾..."
        }
        val receivedLine = if (receivedChars > 0) {
            "已接收约 $receivedChars 个字符"
        } else {
            "如果模型没有思考过程，这里会持续显示生成状态"
        }
        return listOf(elapsedLine, stageLine, receivedLine).joinToString("\n")
    }

    private fun updateGenerateTaskCode(
        state: StreamingSessionState,
        code: CharSequence,
        phase: GenerateTaskState.Phase,
        status: String,
        filePath: String? = null,
        isModification: Boolean = false,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (!force && state.lastGenerateTaskUpdateAt != 0L &&
            now - state.lastGenerateTaskUpdateAt < streamingUiUpdateIntervalMs
        ) {
            return
        }
        state.lastGenerateTaskUpdateAt = now
        updateGenerateTaskState(
            GenerateTaskState(
                sessionId = state.sessionId,
                phase = phase,
                code = code.toString(),
                filePath = filePath,
                status = status,
                isModification = isModification,
                updatedAt = now
            )
        )
    }

    private fun updateGenerateTaskState(taskState: GenerateTaskState) {
        val states = _generateTaskStates.value.orEmpty().toMutableMap()
        states[taskState.sessionId] = taskState
        _generateTaskStates.value = states
    }

    private fun markGenerateTaskError(
        state: StreamingSessionState,
        message: String,
        code: String = "",
        filePath: String? = null,
        isModification: Boolean = false
    ) {
        updateGenerateTaskState(
            GenerateTaskState(
                sessionId = state.sessionId,
                phase = GenerateTaskState.Phase.ERROR,
                code = code,
                filePath = filePath,
                status = message,
                isModification = isModification
            )
        )
    }

    private fun restoreGenerateTaskState(sessionId: Long) {
        if (streamingStates[sessionId]?.isGeneratingApp == true) return
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                val session = repository.getSessionById(sessionId) ?: return@withContext null
                val legacyPath = repository.getMessagesBySessionIdOnce(sessionId)
                    .asReversed()
                    .firstNotNullOfOrNull { it.appHtmlPath?.takeIf(String::isNotBlank) }
                val path = session.appHtmlPath?.takeIf(String::isNotBlank) ?: legacyPath
                if (!session.isGenerateTask && path == null) return@withContext null
                if (!session.isGenerateTask) {
                    repository.markSessionAsGenerate(sessionId, path)
                }
                val file = path?.let(::File)?.takeIf { it.isFile }
                if (file == null) {
                    GenerateTaskState(
                        sessionId = sessionId,
                        phase = GenerateTaskState.Phase.ERROR,
                        filePath = path,
                        status = "生成任务尚无可用的 HTML 文件"
                    )
                } else {
                    GenerateTaskState(
                        sessionId = sessionId,
                        phase = GenerateTaskState.Phase.COMPLETED,
                        code = file.readText(),
                        filePath = file.absolutePath,
                        status = "应用源码已载入"
                    )
                }
            }
            if (restored != null && streamingStates[sessionId]?.isGeneratingApp != true) {
                updateGenerateTaskState(restored)
            }
        }
    }

    private fun addStreamingPlaceholder(
        state: StreamingSessionState,
        status: String,
        useLoadingLayout: Boolean
    ) {
        state.lastStreamingContentUpdateAt = 0L
        state.lastStreamingThinkingUpdateAt = 0L
        state.placeholder = ChatMessage(
            messageId = streamingMessageId(state.sessionId),
            role = ChatMessage.ROLE_ASSISTANT,
            content = if (useLoadingLayout) "" else assistantStreamingPlaceholder,
            isStreaming = true,
            thinking = null,
            statusText = status,
            showStatusLoader = true
        )
        publishStreamingPlaceholder(state)
    }

    private fun updateStreamingThinking(
        state: StreamingSessionState,
        thinking: String,
        force: Boolean = false
    ) {
        if (!force && !shouldUpdateStreamingThinking(state)) return
        val placeholder = state.placeholder ?: return
        if (placeholder.isStreaming) {
            state.placeholder = placeholder.copy(thinking = thinking)
            publishStreamingPlaceholder(state)
        }
    }

    private fun updateStreamingMessage(
        state: StreamingSessionState,
        content: String,
        force: Boolean = false
    ) {
        if (!force && !shouldUpdateStreamingContent(state)) return
        val placeholder = state.placeholder ?: return
        state.placeholder = placeholder.copy(
            content = content,
            isStreaming = true,
            statusText = null,
            showStatusLoader = false
        )
        publishStreamingPlaceholder(state)
    }

    private fun shouldUpdateStreamingContent(state: StreamingSessionState): Boolean {
        val now = System.currentTimeMillis()
        if (state.lastStreamingContentUpdateAt == 0L ||
            now - state.lastStreamingContentUpdateAt >= streamingUiUpdateIntervalMs
        ) {
            state.lastStreamingContentUpdateAt = now
            return true
        }
        return false
    }

    private fun shouldUpdateStreamingThinking(state: StreamingSessionState): Boolean {
        val now = System.currentTimeMillis()
        if (state.lastStreamingThinkingUpdateAt == 0L ||
            now - state.lastStreamingThinkingUpdateAt >= streamingUiUpdateIntervalMs
        ) {
            state.lastStreamingThinkingUpdateAt = now
            return true
        }
        return false
    }

    private fun publishStreamingPlaceholder(state: StreamingSessionState) {
        if (_currentSessionId.value != state.sessionId) return
        val placeholder = state.placeholder ?: return
        val currentList = _messages.value?.toMutableList() ?: mutableListOf()
        currentList.removeAll { it.messageId == placeholder.messageId }
        currentList.add(placeholder)
        _messages.value = currentList
    }

    private fun removeStreamingPlaceholder(state: StreamingSessionState) {
        val messageId = state.placeholder?.messageId ?: streamingMessageId(state.sessionId)
        state.lastStreamingContentUpdateAt = 0L
        state.lastStreamingThinkingUpdateAt = 0L
        state.placeholder = null
        if (_currentSessionId.value != state.sessionId) return
        val currentList = _messages.value?.toMutableList() ?: return
        currentList.removeAll { it.messageId == messageId }
        _messages.value = currentList
    }

    private fun updateStreamingStatusText(state: StreamingSessionState, text: String) {
        val placeholder = state.placeholder ?: return
        state.placeholder = placeholder.copy(
            statusText = text,
            showStatusLoader = true,
            isStreaming = true
        )
        publishStreamingPlaceholder(state)
    }

    private fun updateReconnectStatus(state: StreamingSessionState, secondsRemaining: Int) {
        updateStreamingStatusText(state, "网络状态出错，尝试重连 ${secondsRemaining}秒")
    }

    private fun clearReconnectStatus(state: StreamingSessionState) {
        val placeholder = state.placeholder ?: return
        val hasVisibleContent = hasVisibleStreamingContent(placeholder.content)
        state.placeholder = placeholder.copy(
            statusText = if (hasVisibleContent) null else "正在思考",
            showStatusLoader = !hasVisibleContent,
            isStreaming = true
        )
        publishStreamingPlaceholder(state)
    }

    private fun hasVisibleStreamingContent(content: String): Boolean {
        return content.isNotBlank() && content != assistantStreamingPlaceholder
    }

    private suspend fun handleApiError(
        state: StreamingSessionState,
        config: ChatCallConfig?,
        rawError: String,
        scene: String,
        partialContentToKeep: String? = null
    ) {
        state.thinkingContent.clear()
        state.streamingContent.clear()
        state.reconnectPrefixPending = ""

        if (state.placeholder != null) {
            updateStreamingStatusText(state, "出错了，馒头正在拼命分析…")
        } else {
            addStreamingPlaceholder(state, "出错了，馒头正在拼命分析…", useLoadingLayout = false)
        }

        val analyzed = config?.let { ErrorAnalyzer.analyze(it, rawError, scene) }

        removeStreamingPlaceholder(state)

        partialContentToKeep?.takeIf { it.isNotEmpty() }?.let {
            addFinalAssistantMessage(state, it)
        }

        addFinalAssistantMessage(state, analyzed ?: requestInterruptedFallback)
    }

    fun stopStreaming() {
        _currentSessionId.value?.let { cancelStreaming(it, persistFallback = true) }
    }

    private fun cancelStreaming(sessionId: Long, persistFallback: Boolean = false) {
        val state = streamingStates.remove(sessionId) ?: return
        state.job?.cancel()
        state.job = null
        stopAppGenerationProgressHeartbeat(state)
        if (state.isGeneratingApp) {
            val task = _generateTaskStates.value.orEmpty()[sessionId]
            updateGenerateTaskState(
                task?.copy(
                    phase = GenerateTaskState.Phase.ERROR,
                    status = "生成任务已停止",
                    updatedAt = System.currentTimeMillis()
                ) ?: GenerateTaskState(
                    sessionId = sessionId,
                    phase = GenerateTaskState.Phase.ERROR,
                    status = "生成任务已停止"
                )
            )
        }
        state.isGeneratingApp = false
        if (persistFallback) {
            fallbackScope.launch {
                persistFallbackIfNeeded(state)
            }
        }
        state.streamingContent.clear()
        state.thinkingContent.clear()
        state.reconnectPrefixPending = ""
        removeStreamingPlaceholder(state)
        updateSessionLoadingIndicators()
    }

    private fun cancelAllStreaming(persistFallback: Boolean = false) {
        streamingStates.keys.toList().forEach { sessionId ->
            cancelStreaming(sessionId, persistFallback = persistFallback)
        }
    }

    private suspend fun finishStreamingState(state: StreamingSessionState) {
        stopAppGenerationProgressHeartbeat(state)
        state.isGeneratingApp = false
        if (streamingStates[state.sessionId] === state) {
            persistFallbackIfNeeded(state)
            streamingStates.remove(state.sessionId)
            state.streamingContent.clear()
            state.thinkingContent.clear()
            state.reconnectPrefixPending = ""
            removeStreamingPlaceholder(state)
            updateSessionLoadingIndicators()
        }
    }

    private fun updateSessionLoadingIndicators() {
        val runningIds = streamingStates.keys.toSet()
        _runningSessionIds.value = runningIds
        _isLoading.value = _currentSessionId.value?.let { it in runningIds } == true
        _isGeneratingApp.value = streamingStates.values.any { it.isGeneratingApp }
    }

    private suspend fun addFinalAssistantMessage(
        state: StreamingSessionState,
        content: String,
        appHtmlPath: String? = null
    ): Long {
        state.hasFinalAssistantMessage = true
        return repository.addAssistantMessage(state.sessionId, content, appHtmlPath)
    }

    private suspend fun persistFallbackIfNeeded(state: StreamingSessionState) {
        if (!state.userMessagePersisted || state.hasFinalAssistantMessage) return
        val partialContent = state.streamingContent.toString().trimEnd()
        val fallback = if (partialContent.isNotEmpty()) {
            partialContent + requestFailedSuffix
        } else {
            requestInterruptedFallback
        }
        addFinalAssistantMessage(state, fallback)
    }

    private fun streamingMessageId(sessionId: Long): Long {
        return -sessionId.coerceAtLeast(1L)
    }

    private fun createNewSessionAndSendMessage(
        content: String,
        imagePath: String?,
        imageUris: List<Uri>?
    ) {
        messagesJob?.cancel()
        viewModelScope.launch {
            val sessionId = repository.createSession(content.ifEmpty { "[图片]" })
            _currentSessionId.value = sessionId
            _messages.value = emptyList()
            loadMessages(sessionId)
            delay(100)
            sendMessage(content, imagePath, imageUris)
        }
    }

    private suspend fun resolveActiveChatConfig(): ChatCallConfig? {
        val ctx = getApplication<Application>()
        val providerId = com.hfad.mantou.data.preferences.ActiveModelStore
            .getActiveProviderId(ctx) ?: return null
        val modelName = com.hfad.mantou.data.preferences.ActiveModelStore
            .getActiveModelName(ctx)?.takeIf { it.isNotBlank() } ?: return null
        val provider = withContext(Dispatchers.IO) {
            providerRepository.getProviderById(providerId)
        } ?: return null
        if (provider.baseUrl.isBlank()) return null
        return ChatCallConfig(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            model = modelName,
            apiFormat = provider.apiFormat
        )
    }

    private fun buildApiMessages(
        historyMessages: List<ChatMessageEntity>,
        systemPrompt: String,
        currentImageBase64List: List<String> = emptyList()
    ): List<ApiMessage> {
        val messages = mutableListOf<ApiMessage>()

        messages.add(
            ApiMessage(
                role = "system",
                content = systemPrompt
            )
        )

        historyMessages.forEachIndexed { index, entity ->
            val isLastUserMessage = index == historyMessages.lastIndex && entity.role == "user"
            val contextContent = ChatContextFormatter.contentForContext(entity)

            if (isLastUserMessage && currentImageBase64List.isNotEmpty()) {
                val contentParts = mutableListOf<ContentPart>()
                if (contextContent.isNotEmpty()) {
                    contentParts.add(ContentPart(type = "text", text = contextContent))
                }
                currentImageBase64List.forEach { base64 ->
                    contentParts.add(
                        ContentPart(
                            type = "image_url",
                            imageUrl = ImageUrl(url = base64)
                        )
                    )
                }
                messages.add(ApiMessage(role = entity.role, content = contentParts))
            } else {
                messages.add(ApiMessage(role = entity.role, content = contextContent))
            }
        }

        return messages
    }

    fun deleteSession(sessionId: Long) {
        cancelStreaming(sessionId)
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            _generateTaskStates.value = _generateTaskStates.value.orEmpty() - sessionId
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = null
                _messages.value = emptyList()
            }
        }
    }

    fun setSessionArchived(sessionId: Long, isArchived: Boolean) {
        viewModelScope.launch {
            repository.setSessionArchived(sessionId, isArchived)
        }
    }

    fun deleteMessage(messageId: Long) {
        if (messageId <= 0) return
        viewModelScope.launch {
            repository.deleteMessage(messageId)
        }
    }

    fun updateMessageContent(messageId: Long, content: String) {
        if (messageId <= 0 || content.isBlank()) return
        viewModelScope.launch {
            repository.updateMessageContent(messageId, content)
        }
    }

    fun editUserMessageAndRegenerate(messageId: Long, content: String) {
        if (messageId <= 0 || content.isBlank()) return
        viewModelScope.launch {
            val originalMessage = repository.getMessageById(messageId) ?: return@launch
            if (originalMessage.role != ChatMessage.ROLE_USER) return@launch

            cancelStreaming(originalMessage.sessionId, persistFallback = true)
            val editedMessage = repository.updateMessageContentAndDeleteAfter(messageId, content)
                ?: return@launch
            if (repository.getMessageCount(editedMessage.sessionId) == 1) {
                repository.updateSessionTitle(editedMessage.sessionId, content.ifEmpty { "[图片]" })
                if (repository.getSessionById(editedMessage.sessionId)?.isGenerateTask == true) {
                    repository.clearGeneratedApp(editedMessage.sessionId)
                }
            }

            val state = StreamingSessionState(editedMessage.sessionId)
            streamingStates[editedMessage.sessionId] = state
            updateSessionLoadingIndicators()

            state.job = viewModelScope.launch {
                _errorMessage.value = null
                state.streamingContent.clear()
                try {
                    withContext(Dispatchers.IO) {
                        AgentWorkspace.appendExplicitMemoryIfNeeded(getApplication(), content)
                    }

                    val config = resolveActiveChatConfig()
                    if (config == null) {
                        _noModelConfigured.value = true
                        return@launch
                    }

                    _activeModelName.value = config.model
                    state.userMessagePersisted = true

                    val imageBase64List = loadImageBase64List(imagePath = editedMessage.imagePath)
                    generateResponseForPersistedUserMessage(state, config, content, imageBase64List)
                } finally {
                    finishStreamingState(state)
                }
            }
        }
    }

    fun deleteAllSessions() {
        cancelAllStreaming()
        viewModelScope.launch {
            repository.deleteAllSessions()
            _currentSessionId.value = null
            _messages.value = emptyList()
            _generateTaskStates.value = emptyMap()
        }
    }

    fun clearCurrentSession() {
        _currentSessionId.value?.let { cancelStreaming(it, persistFallback = true) }
        _currentSessionId.value = null
        _messages.value = emptyList()
        updateSessionLoadingIndicators()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearAppGenerated() {
        _appGenerated.value = null
    }

    fun clearNoModelConfigured() {
        _noModelConfigured.value = false
    }

    override fun onCleared() {
        cancelAllStreaming(persistFallback = true)
        messagesJob?.cancel()
        super.onCleared()
    }
}

private const val WEB_APP_DIFF_LOG_TAG = "WebAppDiff"
private const val APP_DIFF_LOG_PREVIEW_CHARS = 3_000

private data class StreamingSessionState(
    val sessionId: Long,
    var job: Job? = null,
    var appGenerationProgressJob: Job? = null,
    var placeholder: ChatMessage? = null,
    val streamingContent: StringBuilder = StringBuilder(),
    val thinkingContent: StringBuilder = StringBuilder(),
    var isGeneratingApp: Boolean = false,
    var userMessagePersisted: Boolean = false,
    var hasFinalAssistantMessage: Boolean = false,
    var lastStreamingContentUpdateAt: Long = 0L,
    var lastStreamingThinkingUpdateAt: Long = 0L,
    var lastGenerateTaskUpdateAt: Long = 0L,
    var reconnectPrefixPending: String = ""
)

private data class SessionRouting(
    val isGenerateTask: Boolean,
    val appHtmlPath: String?
)

private fun ChatMessageEntity.toChatMessage() = ChatMessage(
    messageId = messageId,
    role = role,
    content = content,
    imagePath = imagePath,
    timestamp = timestamp,
    isStreaming = false,
    appHtmlPath = appHtmlPath,
    statusText = null,
    showStatusLoader = false
)
