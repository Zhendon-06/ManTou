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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

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

    private var streamingJob: Job? = null
    private var messagesJob: Job? = null
    private var streamingContent = StringBuilder()
    private var thinkingContent = StringBuilder()
    private val STREAMING_MESSAGE_ID = -1L

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
        cancelStreaming()
        messagesJob?.cancel()
        viewModelScope.launch {
            val sessionId = repository.createSession("新会话")
            _currentSessionId.value = sessionId
            _messages.value = emptyList()
            loadMessages(sessionId)
        }
    }

    fun switchToSession(sessionId: Long) {
        cancelStreaming()
        messagesJob?.cancel()
        _currentSessionId.value = sessionId
        loadMessages(sessionId)
    }

    private fun loadMessages(sessionId: Long) {
        messagesJob = viewModelScope.launch {
            repository.getMessagesBySessionId(sessionId).collect { entities ->
                if (_currentSessionId.value == sessionId) {
                    val dbMessages = entities.map { it.toChatMessage() }

                    val currentList = _messages.value
                    val streamingMessage = currentList?.find { it.messageId == STREAMING_MESSAGE_ID }

                    _messages.value = if (streamingMessage != null) {
                        dbMessages + streamingMessage
                    } else {
                        dbMessages
                    }
                }
            }
        }
    }

    fun sendMessage(content: String, imagePath: String? = null, imageUris: List<Uri>? = null) {
        val sessionId = _currentSessionId.value ?: run {
            createNewSessionAndSendMessage(content, imagePath, imageUris)
            return
        }

        cancelStreaming()

        streamingJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            streamingContent.clear()

            withContext(Dispatchers.IO) {
                AgentWorkspace.appendExplicitMemoryIfNeeded(getApplication(), content)
            }

            val config = resolveActiveChatConfig()
            if (config == null) {
                _noModelConfigured.value = true
                _isLoading.value = false
                return@launch
            }

            _activeModelName.value = config.model

            val imageBase64List = mutableListOf<String>()
            val finalImagePath = imagePath ?: imageUris?.firstOrNull()?.toString()

            withContext(Dispatchers.IO) {
                val urisToProcess = imageUris?.take(ApiConfig.MAX_IMAGE_COUNT)
                    ?: listOfNotNull(imagePath?.let { Uri.parse(it) })

                urisToProcess.forEach { uri ->
                    ImageUtils.uriToBase64(getApplication(), uri)?.let { imageBase64List.add(it) }
                }
            }

            repository.sendUserMessage(sessionId, content, finalImagePath)

            if (repository.getMessageCount(sessionId) == 1) {
                repository.updateSessionTitle(sessionId, content.ifEmpty { "[图片]" })
            }

            val isAppIntent = withContext(Dispatchers.IO) {
                AppIntentDetector.isAppGenerationIntent(config, content)
            }

            if (isAppIntent) {
                generateAppFlow(sessionId, config, content)
            } else {
                normalChatFlow(sessionId, config, imageBase64List)
            }

            _isLoading.value = false
        }
    }

    private suspend fun normalChatFlow(sessionId: Long, config: ChatCallConfig, imageBase64List: List<String>) {
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

        thinkingContent.clear()
        addStreamingPlaceholder("正在思考")

        StreamingApiService.streamChatCompletion(config, request)
            .catch { e ->
                _errorMessage.value = "请求失败: ${e.message}"
                removeStreamingPlaceholder()
            }
            .collect { event ->
                when (event) {
                    is StreamingApiService.StreamEvent.Start -> {}
                    is StreamingApiService.StreamEvent.Thinking -> {
                        thinkingContent.append(event.text)
                        updateStreamingThinking(thinkingContent.toString())
                    }
                    is StreamingApiService.StreamEvent.Content -> {
                        streamingContent.append(event.text)
                        updateStreamingMessage(streamingContent.toString())
                    }
                    is StreamingApiService.StreamEvent.Done -> {
                        val finalContent = streamingContent.toString()
                        removeStreamingPlaceholder()
                        if (finalContent.isNotEmpty()) {
                            repository.addAssistantMessage(sessionId, finalContent)
                        }
                        streamingContent.clear()
                        thinkingContent.clear()
                    }
                    is StreamingApiService.StreamEvent.Error -> {
                        _errorMessage.value = event.message
                        val partialContent = streamingContent.toString()
                        removeStreamingPlaceholder()
                        if (partialContent.isNotEmpty()) {
                            repository.addAssistantMessage(sessionId, partialContent)
                        }
                        streamingContent.clear()
                        thinkingContent.clear()
                    }
                }
            }
    }

    private suspend fun generateAppFlow(sessionId: Long, config: ChatCallConfig, userMessage: String) {
        _isGeneratingApp.value = true
        try {
            thinkingContent.clear()
            addStreamingPlaceholder("正在生成应用")

            val apiMessages = listOf(
                ApiMessage(role = "system", content = AppGenerator.buildSystemPrompt(getApplication())),
                ApiMessage(role = "user", content = userMessage)
            )
            val request = ChatRequest(
                model = config.model,
                messages = apiMessages,
                stream = true,
                maxTokens = AppGenerator.APP_GEN_MAX_TOKENS
            )

            val htmlBuffer = StringBuilder()

            StreamingApiService.streamChatCompletion(config, request)
                .catch { e ->
                    removeStreamingPlaceholder()
                    thinkingContent.clear()
                    repository.addAssistantMessage(sessionId, "生成失败: ${e.message}，请重试")
                    _errorMessage.value = e.message
                }
                .collect { event ->
                    when (event) {
                        is StreamingApiService.StreamEvent.Thinking -> {
                            thinkingContent.append(event.text)
                            updateStreamingThinking(thinkingContent.toString())
                        }
                        is StreamingApiService.StreamEvent.Content -> {
                            htmlBuffer.append(event.text)
                        }
                        is StreamingApiService.StreamEvent.Done -> {
                            removeStreamingPlaceholder()
                            thinkingContent.clear()
                            val htmlContent = withContext(Dispatchers.IO) {
                                AppGenerator.extractHtml(htmlBuffer.toString())
                            }
                            if (htmlContent != null) {
                                val file = withContext(Dispatchers.IO) {
                                    AppGenerator.saveHtmlFile(getApplication(), htmlContent, userMessage)
                                }
                                repository.addAssistantMessage(
                                    sessionId,
                                    "已为你生成网页应用，点击下方预览或全屏查看 👇",
                                    appHtmlPath = file.absolutePath
                                )
                                _appGenerated.value = file.absolutePath
                            } else {
                                repository.addAssistantMessage(
                                    sessionId,
                                    "生成失败: 无法提取 HTML 内容，请重试"
                                )
                                _errorMessage.value = "无法提取 HTML 内容"
                            }
                        }
                        is StreamingApiService.StreamEvent.Error -> {
                            removeStreamingPlaceholder()
                            thinkingContent.clear()
                            repository.addAssistantMessage(sessionId, "生成失败: ${event.message}，请重试")
                            _errorMessage.value = event.message
                        }
                        else -> {}
                    }
                }
        } finally {
            _isGeneratingApp.value = false
        }
    }

    private fun addStreamingPlaceholder(status: String) {
        val currentList = _messages.value?.toMutableList() ?: mutableListOf()
        currentList.add(ChatMessage(
            messageId = STREAMING_MESSAGE_ID,
            role = ChatMessage.ROLE_ASSISTANT,
            content = status,
            isStreaming = true,
            thinking = null
        ))
        _messages.value = currentList
    }

    private fun updateStreamingThinking(thinking: String) {
        val currentList = _messages.value?.toMutableList() ?: return
        val index = currentList.indexOfFirst { it.messageId == STREAMING_MESSAGE_ID }
        if (index >= 0 && currentList[index].isStreaming) {
            currentList[index] = currentList[index].copy(thinking = thinking)
            _messages.value = currentList
        }
    }

    private fun updateStreamingMessage(content: String) {
        val currentList = _messages.value?.toMutableList() ?: return
        val index = currentList.indexOfFirst { it.messageId == STREAMING_MESSAGE_ID }
        if (index >= 0) {
            currentList[index] = currentList[index].copy(
                content = content,
                isStreaming = false
            )
            _messages.value = currentList
        }
    }

    private fun removeStreamingPlaceholder() {
        val currentList = _messages.value?.toMutableList() ?: return
        currentList.removeAll { it.messageId == STREAMING_MESSAGE_ID }
        _messages.value = currentList
    }

    private fun cancelStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        _isGeneratingApp.value = false
        streamingContent.clear()
        thinkingContent.clear()
        removeStreamingPlaceholder()
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

            if (isLastUserMessage && currentImageBase64List.isNotEmpty()) {
                val contentParts = mutableListOf<ContentPart>()
                if (entity.content.isNotEmpty()) {
                    contentParts.add(ContentPart(type = "text", text = entity.content))
                }
                currentImageBase64List.forEach { base64 ->
                    contentParts.add(ContentPart(type = "image_url", imageUrl = ImageUrl(url = base64)))
                }
                messages.add(ApiMessage(role = entity.role, content = contentParts))
            } else {
                messages.add(ApiMessage(role = entity.role, content = entity.content))
            }
        }

        return messages
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = null
                _messages.value = emptyList()
            }
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

    fun deleteAllSessions() {
        viewModelScope.launch {
            repository.deleteAllSessions()
            _currentSessionId.value = null
            _messages.value = emptyList()
        }
    }

    fun clearCurrentSession() {
        cancelStreaming()
        _currentSessionId.value = null
        _messages.value = emptyList()
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
        cancelStreaming()
        messagesJob?.cancel()
    }
}

private fun ChatMessageEntity.toChatMessage() = ChatMessage(
    messageId = messageId,
    role = role,
    content = content,
    imagePath = imagePath,
    timestamp = timestamp,
    isStreaming = false,
    appHtmlPath = appHtmlPath
)
