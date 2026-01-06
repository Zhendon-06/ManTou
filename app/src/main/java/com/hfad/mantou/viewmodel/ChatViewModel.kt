package com.hfad.mantou.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.data.api.ApiConfig
import com.hfad.mantou.data.api.ApiMessage
import com.hfad.mantou.data.api.ChatRequest
import com.hfad.mantou.data.api.ContentPart
import com.hfad.mantou.data.api.ImageUrl
import com.hfad.mantou.data.api.StreamingApiService
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
 * 聊天 ViewModel
 * 管理聊天会话、消息和 AI API 调用
 * 支持流式输出
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "ChatViewModel"
    
    private val database = AppDatabase.getDatabase(application)
    private val repository = ChatRepository(database.chatDao())

    // 当前会话 ID
    private val _currentSessionId = MutableLiveData<Long?>()
    val currentSessionId: LiveData<Long?> = _currentSessionId

    // 当前会话的消息列表（包含流式输出中的临时消息）
    private val _messages = MutableLiveData<List<ChatMessage>>()
    val messages: LiveData<List<ChatMessage>> = _messages

    // 所有会话列表
    val allSessions: Flow<List<ChatSessionEntity>> = repository.getAllSessions()

    // 加载状态
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // 错误信息
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    // 流式输出任务
    private var streamingJob: Job? = null
    
    // 当前流式输出的内容
    private var streamingContent = StringBuilder()
    
    // 临时的流式消息 ID（用于 UI 更新）
    private val STREAMING_MESSAGE_ID = -1L

    /**
     * 创建新会话
     */
    fun createNewSession(firstUserMessage: String) {
        viewModelScope.launch {
            try {
                val sessionId = repository.createSession(firstUserMessage)
                _currentSessionId.value = sessionId
                loadMessages(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "创建会话失败", e)
                _errorMessage.value = "创建会话失败: ${e.message}"
            }
        }
    }

    /**
     * 创建空会话
     */
    fun createEmptySession() {
        viewModelScope.launch {
            try {
                val sessionId = repository.createSession("新会话")
                _currentSessionId.value = sessionId
                _messages.value = emptyList()
                Log.d(TAG, "创建空会话成功: $sessionId")
            } catch (e: Exception) {
                Log.e(TAG, "创建空会话失败", e)
                _errorMessage.value = "创建会话失败: ${e.message}"
            }
        }
    }

    /**
     * 切换到指定会话
     */
    fun switchToSession(sessionId: Long) {
        // 取消当前流式输出
        cancelStreaming()
        _currentSessionId.value = sessionId
        loadMessages(sessionId)
    }

    /**
     * 加载指定会话的消息
     */
    private fun loadMessages(sessionId: Long) {
        viewModelScope.launch {
            try {
                repository.getMessagesBySessionId(sessionId).collect { entities ->
                    _messages.value = entities.map { it.toChatMessage() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载消息失败", e)
                _errorMessage.value = "加载消息失败: ${e.message}"
            }
        }
    }

    /**
     * 发送用户消息并获取 AI 回复（流式输出）
     */
    fun sendMessage(content: String, imagePath: String? = null, imageUris: List<Uri>? = null) {
        val sessionId = _currentSessionId.value
        
        if (sessionId == null) {
            createNewSessionAndSendMessage(content, imagePath, imageUris)
            return
        }

        // 取消之前的流式输出
        cancelStreaming()

        streamingJob = viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null
                streamingContent.clear()

                // 1. 处理图片
                val imageBase64List = mutableListOf<String>()
                val finalImagePath = imagePath ?: imageUris?.firstOrNull()?.toString()
                
                if (imageUris != null && imageUris.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        imageUris.take(ApiConfig.MAX_IMAGE_COUNT).forEach { uri ->
                            try {
                                val base64 = ImageUtils.uriToBase64(getApplication(), uri)
                                if (base64 != null) {
                                    imageBase64List.add(base64)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "图片转换失败: $uri", e)
                            }
                        }
                    }
                } else if (imagePath != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            val uri = Uri.parse(imagePath)
                            val base64 = ImageUtils.uriToBase64(getApplication(), uri)
                            if (base64 != null) {
                                imageBase64List.add(base64)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "图片转换失败: $imagePath", e)
                        }
                    }
                }

                // 2. 保存用户消息到数据库
                repository.sendUserMessage(sessionId, content, finalImagePath)
                
                // 3. 更新会话标题
                val messageCount = repository.getMessageCount(sessionId)
                if (messageCount == 1) {
                    val title = if (content.isNotEmpty()) content else "[图片]"
                    repository.updateSessionTitle(sessionId, title)
                }

                // 4. 获取历史消息
                val historyMessages = repository.getMessagesBySessionIdOnce(sessionId)

                // 5. 组装 API 请求
                val hasImages = imageBase64List.isNotEmpty()
                val apiMessages = buildApiMessages(historyMessages, imageBase64List)
                val model = ApiConfig.getModelForRequest(hasImages)
                
                Log.d(TAG, "发送流式请求，模型: $model")

                val request = ChatRequest(
                    model = model,
                    messages = apiMessages,
                    maxTokens = ApiConfig.MAX_TOKENS,
                    temperature = ApiConfig.TEMPERATURE,
                    stream = true
                )

                // 6. 添加一个空的 AI 消息占位符（用于流式显示）
                addStreamingPlaceholder()

                // 7. 开始流式请求
                StreamingApiService.streamChatCompletion(request)
                    .catch { e ->
                        Log.e(TAG, "流式请求异常", e)
                        _errorMessage.value = "请求失败: ${e.message}"
                        removeStreamingPlaceholder()
                    }
                    .collect { event ->
                        when (event) {
                            is StreamingApiService.StreamEvent.Start -> {
                                Log.d(TAG, "流式输出开始")
                            }
                            is StreamingApiService.StreamEvent.Content -> {
                                // 追加内容并更新 UI
                                streamingContent.append(event.text)
                                updateStreamingMessage(streamingContent.toString())
                            }
                            is StreamingApiService.StreamEvent.Done -> {
                                Log.d(TAG, "流式输出完成")
                                // 保存完整消息到数据库
                                val finalContent = streamingContent.toString()
                                if (finalContent.isNotEmpty()) {
                                    repository.addAssistantMessage(sessionId, finalContent)
                                }
                                // 移除临时消息，数据库会自动刷新
                                streamingContent.clear()
                            }
                            is StreamingApiService.StreamEvent.Error -> {
                                Log.e(TAG, "流式输出错误: ${event.message}")
                                _errorMessage.value = event.message
                                // 如果有部分内容，保存它
                                val partialContent = streamingContent.toString()
                                if (partialContent.isNotEmpty()) {
                                    repository.addAssistantMessage(sessionId, partialContent)
                                } else {
                                    removeStreamingPlaceholder()
                                }
                                streamingContent.clear()
                            }
                        }
                    }

            } catch (e: Exception) {
                Log.e(TAG, "发送消息失败", e)
                _errorMessage.value = "发送失败: ${e.message}"
                removeStreamingPlaceholder()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 添加流式输出占位消息
     */
    private fun addStreamingPlaceholder() {
        val currentList = _messages.value?.toMutableList() ?: mutableListOf()
        val streamingMessage = ChatMessage(
            messageId = STREAMING_MESSAGE_ID,
            role = ChatMessage.ROLE_ASSISTANT,
            content = "",
            isStreaming = true
        )
        currentList.add(streamingMessage)
        _messages.value = currentList
    }

    /**
     * 更新流式输出消息内容
     */
    private fun updateStreamingMessage(content: String) {
        val currentList = _messages.value?.toMutableList() ?: return
        val index = currentList.indexOfFirst { it.messageId == STREAMING_MESSAGE_ID }
        if (index >= 0) {
            currentList[index] = currentList[index].copy(content = content)
            _messages.value = currentList
        }
    }

    /**
     * 移除流式输出占位消息
     */
    private fun removeStreamingPlaceholder() {
        val currentList = _messages.value?.toMutableList() ?: return
        currentList.removeAll { it.messageId == STREAMING_MESSAGE_ID }
        _messages.value = currentList
    }

    /**
     * 取消流式输出
     */
    private fun cancelStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        streamingContent.clear()
        removeStreamingPlaceholder()
    }

    /**
     * 创建新会话并发送消息
     */
    private fun createNewSessionAndSendMessage(content: String, imagePath: String?, imageUris: List<Uri>?) {
        viewModelScope.launch {
            try {
                val title = if (content.isNotEmpty()) content else "[图片]"
                val sessionId = repository.createSession(title)
                _currentSessionId.value = sessionId
                loadMessages(sessionId)
                
                // 延迟发送消息，确保会话已创建
                kotlinx.coroutines.delay(100)
                sendMessage(content, imagePath, imageUris)
            } catch (e: Exception) {
                Log.e(TAG, "创建会话并发送消息失败", e)
                _errorMessage.value = "创建会话失败: ${e.message}"
            }
        }
    }

    /**
     * 组装 API 请求的 messages
     */
    private fun buildApiMessages(
        historyMessages: List<ChatMessageEntity>,
        currentImageBase64List: List<String> = emptyList()
    ): List<ApiMessage> {
        val messages = mutableListOf<ApiMessage>()

        // 1. 添加 system 消息
        messages.add(
            ApiMessage(
                role = "system",
                content = "你是馒头，一个友好、专业的 AI 助手。请用简洁、准确的语言回答用户的问题。"
            )
        )

        // 2. 添加历史消息
        historyMessages.forEachIndexed { index, entity ->
            val isLastUserMessage = index == historyMessages.lastIndex && entity.role == "user"
            
            if (isLastUserMessage && currentImageBase64List.isNotEmpty()) {
                val contentParts = mutableListOf<ContentPart>()
                if (entity.content.isNotEmpty()) {
                    contentParts.add(ContentPart(type = "text", text = entity.content))
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
                messages.add(ApiMessage(role = entity.role, content = entity.content))
            }
        }

        return messages
    }

    /**
     * 删除会话
     */
    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteSession(sessionId)
                if (_currentSessionId.value == sessionId) {
                    _currentSessionId.value = null
                    _messages.value = emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "删除会话失败", e)
                _errorMessage.value = "删除会话失败: ${e.message}"
            }
        }
    }

    /**
     * 删除所有会话
     */
    fun deleteAllSessions() {
        viewModelScope.launch {
            try {
                repository.deleteAllSessions()
                _currentSessionId.value = null
                _messages.value = emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "删除所有会话失败", e)
                _errorMessage.value = "删除所有会话失败: ${e.message}"
            }
        }
    }

    /**
     * 清空当前会话
     */
    fun clearCurrentSession() {
        cancelStreaming()
        _currentSessionId.value = null
        _messages.value = emptyList()
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        cancelStreaming()
    }
}

/**
 * 扩展函数：将数据库实体转换为 UI 数据类
 */
private fun ChatMessageEntity.toChatMessage(): ChatMessage {
    return ChatMessage(
        messageId = this.messageId,
        role = this.role,
        content = this.content,
        imagePath = this.imagePath,
        timestamp = this.timestamp,
        isStreaming = false
    )
}
