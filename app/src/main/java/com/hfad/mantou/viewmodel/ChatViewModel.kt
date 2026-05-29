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
import com.hfad.mantou.utils.ImageUtils
import com.hfad.mantou.utils.AppIntentDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ChatRepository(AppDatabase.getDatabase(application).chatDao())

    private val _currentSessionId = MutableLiveData<Long?>()
    val currentSessionId: LiveData<Long?> = _currentSessionId

    private val _messages = MutableLiveData<List<ChatMessage>>()
    val messages: LiveData<List<ChatMessage>> = _messages

    val allSessions: Flow<List<ChatSessionEntity>> = repository.getAllSessions()

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _appGenerated = MutableLiveData<String?>()
    val appGenerated: LiveData<String?> = _appGenerated

    private var streamingJob: Job? = null
    private var messagesJob: Job? = null
    private var thinkingCycleJob: Job? = null
    private var streamingContent = StringBuilder()
    private val STREAMING_MESSAGE_ID = -1L

    private val chatThinkingTexts = listOf(
        "正在理解你的问题...",
        "正在组织答案...",
        "正在斟酌措辞...",
        "马上就好..."
    )

    private val appGenThinkingTexts = listOf(
        "正在分析你的需求...",
        "正在设计页面结构...",
        "正在编写 HTML 代码...",
        "正在添加 CSS 样式...",
        "正在实现交互逻辑...",
        "正在优化界面细节...",
        "正在保存生成的文件...",
        "马上就好..."
    )

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
                AppIntentDetector.isAppGenerationIntent(content)
            }

            if (isAppIntent) {
                generateAppFlow(sessionId, content)
            } else {
                normalChatFlow(sessionId, imageBase64List)
            }

            _isLoading.value = false
        }
    }

    private suspend fun normalChatFlow(sessionId: Long, imageBase64List: List<String>) {
        val historyMessages = repository.getMessagesBySessionIdOnce(sessionId)
        val apiMessages = buildApiMessages(historyMessages, imageBase64List)
        val request = ChatRequest(
            model = ApiConfig.getModelForRequest(imageBase64List.isNotEmpty()),
            messages = apiMessages,
            stream = true
        )

        addStreamingPlaceholder(chatThinkingTexts.first())
        startThinkingCycle(chatThinkingTexts, intervalMs = 1500L)

        StreamingApiService.streamChatCompletion(request)
            .catch { e ->
                stopThinkingCycle()
                _errorMessage.value = "请求失败: ${e.message}"
                removeStreamingPlaceholder()
            }
            .collect { event ->
                when (event) {
                    is StreamingApiService.StreamEvent.Start -> {
                        // 保持 LoadingViewHolder + 思考文案，等到首个 Content 再切换
                    }
                    is StreamingApiService.StreamEvent.Content -> {
                        stopThinkingCycle()
                        streamingContent.append(event.text)
                        updateStreamingMessage(streamingContent.toString())
                    }
                    is StreamingApiService.StreamEvent.Done -> {
                        stopThinkingCycle()
                        val finalContent = streamingContent.toString()
                        removeStreamingPlaceholder()
                        if (finalContent.isNotEmpty()) {
                            repository.addAssistantMessage(sessionId, finalContent)
                        }
                        streamingContent.clear()
                    }
                    is StreamingApiService.StreamEvent.Error -> {
                        stopThinkingCycle()
                        _errorMessage.value = event.message
                        val partialContent = streamingContent.toString()
                        removeStreamingPlaceholder()
                        if (partialContent.isNotEmpty()) {
                            repository.addAssistantMessage(sessionId, partialContent)
                        }
                        streamingContent.clear()
                    }
                    else -> {}
                }
            }
    }

    private suspend fun generateAppFlow(sessionId: Long, userMessage: String) {
        addStreamingPlaceholder(appGenThinkingTexts.first())
        startThinkingCycle(appGenThinkingTexts, intervalMs = 2000L)

        val result = withContext(Dispatchers.IO) {
            AppGenerator.generateApp(getApplication(), userMessage)
        }

        stopThinkingCycle()
        removeStreamingPlaceholder()

        if (result.success && result.htmlPath != null) {
            repository.addAssistantMessage(
                sessionId,
                "已为你生成网页应用，点击下方预览或全屏查看 👇",
                appHtmlPath = result.htmlPath
            )
            _appGenerated.value = result.htmlPath
        } else {
            repository.addAssistantMessage(
                sessionId,
                "生成失败: ${result.error ?: "未知错误"}，请重试"
            )
            _errorMessage.value = result.error
        }
    }

    private fun addStreamingPlaceholder(initialThinking: String? = null) {
        val currentList = _messages.value?.toMutableList() ?: mutableListOf()
        currentList.add(ChatMessage(
            messageId = STREAMING_MESSAGE_ID,
            role = ChatMessage.ROLE_ASSISTANT,
            content = "",
            isStreaming = true,
            thinking = initialThinking
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

    private fun startThinkingCycle(texts: List<String>, intervalMs: Long) {
        thinkingCycleJob?.cancel()
        thinkingCycleJob = viewModelScope.launch {
            var i = 0
            while (isActive) {
                updateStreamingThinking(texts[i % texts.size])
                delay(intervalMs)
                i++
            }
        }
    }

    private fun stopThinkingCycle() {
        thinkingCycleJob?.cancel()
        thinkingCycleJob = null
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
        stopThinkingCycle()
        streamingJob?.cancel()
        streamingJob = null
        streamingContent.clear()
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

    private fun buildApiMessages(
        historyMessages: List<ChatMessageEntity>,
        currentImageBase64List: List<String> = emptyList()
    ): List<ApiMessage> {
        val messages = mutableListOf<ApiMessage>()

        messages.add(ApiMessage(
            role = "system",
            content = "你是馒头，一个友好、专业的 AI 助手。请用简洁、准确的语言回答用户的问题。"
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
