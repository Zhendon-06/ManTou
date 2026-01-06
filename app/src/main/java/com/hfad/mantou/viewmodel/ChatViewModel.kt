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
import com.hfad.mantou.utils.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天 ViewModel - 管理聊天会话、消息和 AI API 调用
 */
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

    private var streamingJob: Job? = null
    private var streamingContent = StringBuilder()
    private val STREAMING_MESSAGE_ID = -1L

    /** 创建空会话 */
    fun createEmptySession() {
        viewModelScope.launch {
            val sessionId = repository.createSession("新会话")
            _currentSessionId.value = sessionId
            _messages.value = emptyList()
        }
    }

    /** 切换到指定会话 */
    fun switchToSession(sessionId: Long) {
        cancelStreaming()
        _currentSessionId.value = sessionId
        loadMessages(sessionId)
    }

    /** 加载指定会话的消息 */
    private fun loadMessages(sessionId: Long) {
        viewModelScope.launch {
            repository.getMessagesBySessionId(sessionId).collect { entities ->
                _messages.value = entities.map { it.toChatMessage() }
            }
        }
    }

    /** 发送用户消息并获取 AI 回复（流式输出） */
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

            // 1. 处理图片转 Base64
            val imageBase64List = mutableListOf<String>()
            val finalImagePath = imagePath ?: imageUris?.firstOrNull()?.toString()

            withContext(Dispatchers.IO) {
                val urisToProcess = imageUris?.take(ApiConfig.MAX_IMAGE_COUNT)
                    ?: listOfNotNull(imagePath?.let { Uri.parse(it) })
                
                urisToProcess.forEach { uri ->
                    ImageUtils.uriToBase64(getApplication(), uri)?.let { imageBase64List.add(it) }
                }
            }

            // 2. 保存用户消息
            repository.sendUserMessage(sessionId, content, finalImagePath)

            // 3. 更新会话标题（首条消息）
            if (repository.getMessageCount(sessionId) == 1) {
                repository.updateSessionTitle(sessionId, content.ifEmpty { "[图片]" })
            }

            // 4. 构建 API 请求
            val historyMessages = repository.getMessagesBySessionIdOnce(sessionId)
            val apiMessages = buildApiMessages(historyMessages, imageBase64List)
            val request = ChatRequest(
                model = ApiConfig.getModelForRequest(imageBase64List.isNotEmpty()),
                messages = apiMessages,
                stream = true
            )

            // 5. 添加流式占位消息
            addStreamingPlaceholder()

            // 6. 发起流式请求
            StreamingApiService.streamChatCompletion(request)
                .catch { e ->
                    _errorMessage.value = "请求失败: ${e.message}"
                    removeStreamingPlaceholder()
                }
                .collect { event ->
                    when (event) {
                        is StreamingApiService.StreamEvent.Content -> {
                            streamingContent.append(event.text)
                            updateStreamingMessage(streamingContent.toString())
                        }
                        is StreamingApiService.StreamEvent.Done -> {
                            val finalContent = streamingContent.toString()
                            if (finalContent.isNotEmpty()) {
                                repository.addAssistantMessage(sessionId, finalContent)
                            }
                            streamingContent.clear()
                        }
                        is StreamingApiService.StreamEvent.Error -> {
                            _errorMessage.value = event.message
                            val partialContent = streamingContent.toString()
                            if (partialContent.isNotEmpty()) {
                                repository.addAssistantMessage(sessionId, partialContent)
                            } else {
                                removeStreamingPlaceholder()
                            }
                            streamingContent.clear()
                        }
                        else -> {}
                    }
                }

            _isLoading.value = false
        }
    }

    /** 添加流式输出占位消息 */
    private fun addStreamingPlaceholder() {
        val currentList = _messages.value?.toMutableList() ?: mutableListOf()
        currentList.add(ChatMessage(
            messageId = STREAMING_MESSAGE_ID,
            role = ChatMessage.ROLE_ASSISTANT,
            content = "",
            isStreaming = true
        ))
        _messages.value = currentList
    }

    /** 更新流式输出消息内容 */
    private fun updateStreamingMessage(content: String) {
        val currentList = _messages.value?.toMutableList() ?: return
        val index = currentList.indexOfFirst { it.messageId == STREAMING_MESSAGE_ID }
        if (index >= 0) {
            currentList[index] = currentList[index].copy(content = content)
            _messages.value = currentList
        }
    }

    /** 移除流式输出占位消息 */
    private fun removeStreamingPlaceholder() {
        val currentList = _messages.value?.toMutableList() ?: return
        currentList.removeAll { it.messageId == STREAMING_MESSAGE_ID }
        _messages.value = currentList
    }

    /** 取消流式输出 */
    private fun cancelStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        streamingContent.clear()
        removeStreamingPlaceholder()
    }

    /** 创建新会话并发送消息 */
    private fun createNewSessionAndSendMessage(content: String, imagePath: String?, imageUris: List<Uri>?) {
        viewModelScope.launch {
            val sessionId = repository.createSession(content.ifEmpty { "[图片]" })
            _currentSessionId.value = sessionId
            loadMessages(sessionId)
            kotlinx.coroutines.delay(100)
            sendMessage(content, imagePath, imageUris)
        }
    }

    /** 组装 API 请求的 messages */
    private fun buildApiMessages(
        historyMessages: List<ChatMessageEntity>,
        currentImageBase64List: List<String> = emptyList()
    ): List<ApiMessage> {
        val messages = mutableListOf<ApiMessage>()

        // System 消息
        messages.add(ApiMessage(
            role = "system",
            content = "你是馒头，一个友好、专业的 AI 助手。请用简洁、准确的语言回答用户的问题。"
        ))

        // 历史消息
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

    /** 删除会话 */
    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = null
                _messages.value = emptyList()
            }
        }
    }

    /** 删除所有会话 */
    fun deleteAllSessions() {
        viewModelScope.launch {
            repository.deleteAllSessions()
            _currentSessionId.value = null
            _messages.value = emptyList()
        }
    }

    /** 清空当前会话 */
    fun clearCurrentSession() {
        cancelStreaming()
        _currentSessionId.value = null
        _messages.value = emptyList()
    }

    /** 清除错误消息 */
    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        cancelStreaming()
    }
}

/** 扩展函数：将数据库实体转换为 UI 数据类 */
private fun ChatMessageEntity.toChatMessage() = ChatMessage(
    messageId = messageId,
    role = role,
    content = content,
    imagePath = imagePath,
    timestamp = timestamp,
    isStreaming = false
)
