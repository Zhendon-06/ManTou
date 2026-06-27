package com.hfad.mantou.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.data.api.*
import com.hfad.mantou.data.database.AppDatabase
import com.hfad.mantou.data.database.ChatMessageEntity
import com.hfad.mantou.data.database.ChatSessionEntity
import com.hfad.mantou.data.repository.ChatRepository
import com.hfad.mantou.utils.AppGenerator
import com.hfad.mantou.utils.AgentWorkspace
import com.hfad.mantou.utils.ImageUtils
import com.hfad.mantou.utils.AppIntentDetector
import com.hfad.mantou.utils.ChatContextFormatter
import com.hfad.mantou.utils.ErrorAnalyzer
import com.hfad.mantou.data.preferences.ContextLimitStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ChatRepository(AppDatabase.getDatabase(application).chatDao())
    private val providerRepository = com.hfad.mantou.data.repository.ProviderRepository(
        AppDatabase.getDatabase(application).providerDao()
    )

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
    private val APP_GENERATION_PROGRESS_INTERVAL_MS = 1_500L
    private val STREAMING_UI_UPDATE_INTERVAL_MS = 120L
    private val REQUEST_INTERRUPTED_FALLBACK = "请求已中止或发生错误，请稍后重试。"

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
        val isAppIntent = withContext(Dispatchers.IO) {
            AppIntentDetector.isAppGenerationIntent(config, content)
        }

        if (isAppIntent) {
            generateAppFlow(state, config, content)
        } else {
            normalChatFlow(state, config, imageBase64List)
        }
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
        addStreamingPlaceholder(state, "正在思考")

        StreamingApiService.streamChatCompletion(config, request)
            .catch { e ->
                if (e is CancellationException) throw e
                handleApiError(state, config, e.message ?: "未知错误", "普通聊天")
            }
            .collect { event ->
                when (event) {
                    is StreamingApiService.StreamEvent.Start -> {}
                    is StreamingApiService.StreamEvent.Thinking -> {
                        state.thinkingContent.append(event.text)
                        updateStreamingThinking(state, state.thinkingContent.toString())
                    }
                    is StreamingApiService.StreamEvent.Content -> {
                        state.streamingContent.append(event.text)
                        updateStreamingMessage(state, state.streamingContent.toString())
                    }
                    is StreamingApiService.StreamEvent.Done -> {
                        val finalContent = state.streamingContent.toString()
                        removeStreamingPlaceholder(state)
                        if (finalContent.isNotEmpty()) {
                            addFinalAssistantMessage(state, finalContent)
                        }
                        state.streamingContent.clear()
                        state.thinkingContent.clear()
                    }
                    is StreamingApiService.StreamEvent.Error -> {
                        val partialContent = state.streamingContent.toString().ifEmpty { null }
                        handleApiError(state, config, event.message, "普通聊天", partialContent)
                    }
                }
            }
    }

    private suspend fun generateAppFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String
    ) {
        val sessionId = state.sessionId
        state.isGeneratingApp = true
        updateSessionLoadingIndicators()
        try {
            state.thinkingContent.clear()
            addStreamingPlaceholder(state, "正在生成应用")
            updateStreamingThinking(state, buildAppGenerationProgressText(elapsedSeconds = 0, receivedChars = 0))

            val historyMessages = repository.getMessagesBySessionIdOnce(sessionId)
            val apiMessages = buildApiMessages(
                historyMessages = historyMessages,
                systemPrompt = AppGenerator.buildSystemPrompt(getApplication())
            )
            val request = ChatRequest(
                model = config.model,
                messages = apiMessages,
                stream = true,
                maxTokens = ContextLimitStore.getTokenLimit(getApplication())
            )

            val htmlBuffer = StringBuilder()
            startAppGenerationProgressHeartbeat(state, htmlBuffer)

            StreamingApiService.streamChatCompletion(config, request)
                .catch { e ->
                    if (e is CancellationException) throw e
                    stopAppGenerationProgressHeartbeat(state)
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
                                    val file = withContext(Dispatchers.IO) {
                                        AppGenerator.saveHtmlFile(getApplication(), htmlContent, userMessage)
                                    }
                                    removeStreamingPlaceholder(state)
                                    state.thinkingContent.clear()
                                    addFinalAssistantMessage(
                                        state,
                                        "已为你生成网页应用，点击下方预览或全屏查看 👇",
                                        appHtmlPath = file.absolutePath
                                    )
                                    _appGenerated.value = file.absolutePath
                                } else {
                                    handleApiError(state, config, "模型返回内容中未找到合法 HTML", "生成网页应用")
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                handleApiError(state, config, e.message ?: "保存 HTML 时出错", "生成网页应用")
                            }
                        }
                        is StreamingApiService.StreamEvent.Error -> {
                            stopAppGenerationProgressHeartbeat(state)
                            handleApiError(state, config, event.message, "生成网页应用")
                        }
                        else -> {}
                    }
                }
        } finally {
            stopAppGenerationProgressHeartbeat(state)
            state.isGeneratingApp = false
            updateSessionLoadingIndicators()
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
                delay(APP_GENERATION_PROGRESS_INTERVAL_MS)
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

    private fun addStreamingPlaceholder(state: StreamingSessionState, status: String) {
        state.lastStreamingContentUpdateAt = 0L
        state.lastStreamingThinkingUpdateAt = 0L
        state.placeholder = ChatMessage(
            messageId = streamingMessageId(state.sessionId),
            role = ChatMessage.ROLE_ASSISTANT,
            content = status,
            isStreaming = true,
            thinking = null
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
            isStreaming = false
        )
        publishStreamingPlaceholder(state)
    }

    private fun shouldUpdateStreamingContent(state: StreamingSessionState): Boolean {
        val now = System.currentTimeMillis()
        if (state.lastStreamingContentUpdateAt == 0L ||
            now - state.lastStreamingContentUpdateAt >= STREAMING_UI_UPDATE_INTERVAL_MS) {
            state.lastStreamingContentUpdateAt = now
            return true
        }
        return false
    }

    private fun shouldUpdateStreamingThinking(state: StreamingSessionState): Boolean {
        val now = System.currentTimeMillis()
        if (state.lastStreamingThinkingUpdateAt == 0L ||
            now - state.lastStreamingThinkingUpdateAt >= STREAMING_UI_UPDATE_INTERVAL_MS) {
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
            content = text,
            thinking = null,
            isStreaming = true
        )
        publishStreamingPlaceholder(state)
    }

    private suspend fun handleApiError(
        state: StreamingSessionState,
        config: ChatCallConfig?,
        rawError: String,
        scene: String,
        partialContentToKeep: String? = null
    ) {
        val sessionId = state.sessionId
        state.thinkingContent.clear()
        state.streamingContent.clear()

        val hasPlaceholder = state.placeholder != null
        if (hasPlaceholder) {
            updateStreamingStatusText(state, "出错了，馒头正在拼命分析…")
        } else {
            addStreamingPlaceholder(state, "出错了，馒头正在拼命分析…")
        }

        val analyzed = config?.let { ErrorAnalyzer.analyze(it, rawError, scene) }

        removeStreamingPlaceholder(state)

        partialContentToKeep?.takeIf { it.isNotEmpty() }?.let {
            addFinalAssistantMessage(state, it)
        }

        val finalText = analyzed ?: "出错了，请稍后重试。"
        addFinalAssistantMessage(state, finalText)
    }

    fun stopStreaming() {
        _currentSessionId.value?.let { cancelStreaming(it, persistFallback = true) }
    }

    private fun cancelStreaming(sessionId: Long, persistFallback: Boolean = false) {
        val state = streamingStates.remove(sessionId) ?: return
        state.job?.cancel()
        state.job = null
        stopAppGenerationProgressHeartbeat(state)
        state.isGeneratingApp = false
        if (persistFallback) {
            viewModelScope.launch {
                persistFallbackIfNeeded(state)
            }
        }
        state.streamingContent.clear()
        state.thinkingContent.clear()
        removeStreamingPlaceholder(state)
        updateSessionLoadingIndicators()
    }

    private fun cancelAllStreaming() {
        streamingStates.keys.toList().forEach { sessionId ->
            cancelStreaming(sessionId)
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
        addFinalAssistantMessage(state, REQUEST_INTERRUPTED_FALLBACK)
    }

    private fun streamingMessageId(sessionId: Long): Long {
        return -sessionId.coerceAtLeast(1L)
    }

    private fun createNewSessionAndSendMessage(content: String, imagePath: String?, imageUris: List<Uri>?) {
        messagesJob?.cancel()
        viewModelScope.launch {
            val sessionId = repository.createSession(content.ifEmpty { "[图片]" })
            _currentSessionId.value = sessionId
            _messages.value = emptyList()
            loadMessages(sessionId)
            kotlinx.coroutines.delay(100)
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

        messages.add(ApiMessage(
            role = "system",
            content = systemPrompt
        ))

        historyMessages.forEachIndexed { index, entity ->
            val isLastUserMessage = index == historyMessages.lastIndex && entity.role == "user"
            val contextContent = ChatContextFormatter.contentForContext(entity)

            if (isLastUserMessage && currentImageBase64List.isNotEmpty()) {
                val contentParts = mutableListOf<ContentPart>()
                if (contextContent.isNotEmpty()) {
                    contentParts.add(ContentPart(type = "text", text = contextContent))
                }
                currentImageBase64List.forEach { base64 ->
                    contentParts.add(ContentPart(type = "image_url", imageUrl = ImageUrl(url = base64)))
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

    /** 删除单条消息（仅对持久化到 DB 的消息生效，流式占位 id=-1 会被忽略）。 */
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

            cancelStreaming(originalMessage.sessionId)
            val editedMessage = repository.updateMessageContentAndDeleteAfter(messageId, content)
                ?: return@launch
            if (repository.getMessageCount(editedMessage.sessionId) == 1) {
                repository.updateSessionTitle(editedMessage.sessionId, content.ifEmpty { "[图片]" })
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
        }
    }

    fun clearCurrentSession() {
        _currentSessionId.value?.let { cancelStreaming(it) }
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
        super.onCleared()
        cancelAllStreaming()
        messagesJob?.cancel()
    }
}

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
    var lastStreamingThinkingUpdateAt: Long = 0L
)

private fun ChatMessageEntity.toChatMessage() = ChatMessage(
    messageId = messageId,
    role = role,
    content = content,
    imagePath = imagePath,
    timestamp = timestamp,
    isStreaming = false,
    appHtmlPath = appHtmlPath
)
