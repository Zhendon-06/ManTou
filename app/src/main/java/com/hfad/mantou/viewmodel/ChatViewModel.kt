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
import com.hfad.mantou.data.api.RetrofitClient
import com.hfad.mantou.data.database.AppDatabase
import com.hfad.mantou.data.database.ChatMessageEntity
import com.hfad.mantou.data.database.ChatSessionEntity
import com.hfad.mantou.data.repository.ChatRepository
import com.hfad.mantou.utils.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天 ViewModel
 * 管理聊天会话、消息和 AI API 调用
 * 支持文本和图片消息
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "ChatViewModel"
    
    private val database = AppDatabase.getDatabase(application)
    private val repository = ChatRepository(database.chatDao())
    private val apiService = RetrofitClient.apiService

    // 当前会话 ID
    private val _currentSessionId = MutableLiveData<Long?>()
    val currentSessionId: LiveData<Long?> = _currentSessionId

    // 当前会话的消息列表
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
     * 创建空会话（不插入任何消息）
     * 用于 new_chat 按钮
     */
    fun createEmptySession() {
        viewModelScope.launch {
            try {
                // 创建一个临时标题的会话
                val sessionId = repository.createSession("新会话")
                _currentSessionId.value = sessionId
                _messages.value = emptyList()  // 清空消息列表
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
     * 发送用户消息并获取 AI 回复
     * @param content 文本内容
     * @param imagePath 图片路径（可选）
     * @param imageUris 图片 URI 列表（可选，用于多图）
     */
    fun sendMessage(content: String, imagePath: String? = null, imageUris: List<Uri>? = null) {
        val sessionId = _currentSessionId.value
        
        if (sessionId == null) {
            // 如果没有当前会话，创建新会话
            createNewSessionAndSendMessage(content, imagePath, imageUris)
            return
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                // 1. 处理图片（如果有）
                val imageBase64List = mutableListOf<String>()
                val finalImagePath = imagePath ?: imageUris?.firstOrNull()?.toString()
                
                // 将图片转换为 Base64
                if (imageUris != null && imageUris.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        imageUris.take(ApiConfig.MAX_IMAGE_COUNT).forEach { uri ->
                            try {
                                val base64 = ImageUtils.uriToBase64(getApplication(), uri)
                                if (base64 != null) {
                                    imageBase64List.add(base64)
                                    Log.d(TAG, "图片转换成功: ${uri}, Base64长度: ${base64.length}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "图片转换失败: $uri", e)
                            }
                        }
                    }
                } else if (imagePath != null) {
                    // 单张图片
                    withContext(Dispatchers.IO) {
                        try {
                            val uri = Uri.parse(imagePath)
                            val base64 = ImageUtils.uriToBase64(getApplication(), uri)
                            if (base64 != null) {
                                imageBase64List.add(base64)
                                Log.d(TAG, "图片转换成功: $imagePath, Base64长度: ${base64.length}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "图片转换失败: $imagePath", e)
                        }
                    }
                }

                // 2. 保存用户消息到数据库
                val userMessageId = repository.sendUserMessage(sessionId, content, finalImagePath)
                
                // 3. 更新会话标题（如果是第一条消息）
                val messageCount = repository.getMessageCount(sessionId)
                if (messageCount == 1) {
                    // 第一条消息，更新会话标题
                    val title = if (content.isNotEmpty()) content else "[图片]"
                    repository.updateSessionTitle(sessionId, title)
                }

                // 4. 从数据库读取历史消息
                val historyMessages = repository.getMessagesBySessionIdOnce(sessionId)

                // 5. 组装 API 请求的 messages
                val hasImages = imageBase64List.isNotEmpty()
                val apiMessages = buildApiMessages(historyMessages, imageBase64List)

                // 6. 调用 AI API
                Log.d(TAG, "发送请求，包含图片: $hasImages, 图片数量: ${imageBase64List.size}")
                
                // 根据是否有图片选择合适的模型
                val model = ApiConfig.getModelForRequest(hasImages)
                Log.d(TAG, "使用模型: $model, 视觉支持: ${ApiConfig.isVisionSupported()}")
                
                val response = apiService.chatCompletion(
                    request = ChatRequest(
                        model = model,
                        messages = apiMessages,
                        maxTokens = ApiConfig.MAX_TOKENS,
                        temperature = ApiConfig.TEMPERATURE
                    )
                )

                if (response.isSuccessful && response.body() != null) {
                    val chatResponse = response.body()!!
                    val assistantContent = chatResponse.choices.firstOrNull()?.message?.content as? String
                        ?: "抱歉，我无法生成回复。"

                    // 7. 保存 AI 回复到数据库
                    repository.addAssistantMessage(sessionId, assistantContent)

                    Log.d(TAG, "AI 回复成功: $assistantContent")
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "API 调用失败: ${response.code()} - $errorBody")
                    
                    // 解析错误信息
                    val errorMsg = parseErrorMessage(response.code(), errorBody)
                    _errorMessage.value = errorMsg
                    
                    // 如果是图片相关错误，添加提示消息
                    if (hasImages && (errorBody?.contains("image") == true || errorBody?.contains("vision") == true)) {
                        repository.addAssistantMessage(sessionId, "抱歉，当前模型不支持图片识别。请使用纯文本对话，或切换到支持视觉的模型。")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "发送消息失败", e)
                _errorMessage.value = "发送失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 解析错误信息
     */
    private fun parseErrorMessage(code: Int, errorBody: String?): String {
        return when (code) {
            400 -> {
                when {
                    errorBody?.contains("image") == true -> "当前模型不支持图片，请使用纯文本"
                    errorBody?.contains("Model Not Exist") == true -> "模型不存在，请检查配置"
                    else -> "请求参数错误: $errorBody"
                }
            }
            401 -> "API Key 无效或已过期"
            403 -> "没有访问权限"
            429 -> "请求过于频繁，请稍后重试"
            500, 502, 503 -> "服务器错误，请稍后重试"
            else -> "请求失败 ($code): $errorBody"
        }
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
                
                // 发送消息
                sendMessage(content, imagePath, imageUris)
            } catch (e: Exception) {
                Log.e(TAG, "创建会话并发送消息失败", e)
                _errorMessage.value = "创建会话失败: ${e.message}"
            }
        }
    }

    /**
     * 组装 API 请求的 messages
     * 支持文本和图片混合消息
     * 
     * @param historyMessages 历史消息列表
     * @param currentImageBase64List 当前消息的图片 Base64 列表（只用于最后一条用户消息）
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
                content = "你是馒头，一个友好、专业的 AI 助手。请用简洁、准确的语言回答用户的问题。如果用户发送了图片，请仔细分析图片内容并给出详细的描述和回答。"
            )
        )

        // 2. 添加历史消息
        historyMessages.forEachIndexed { index, entity ->
            val isLastUserMessage = index == historyMessages.lastIndex && entity.role == "user"
            
            if (isLastUserMessage && currentImageBase64List.isNotEmpty()) {
                // 最后一条用户消息且有图片，使用多模态格式
                val contentParts = mutableListOf<ContentPart>()
                
                // 添加文本部分
                if (entity.content.isNotEmpty()) {
                    contentParts.add(ContentPart(type = "text", text = entity.content))
                }
                
                // 添加图片部分
                currentImageBase64List.forEach { base64 ->
                    contentParts.add(
                        ContentPart(
                            type = "image_url",
                            imageUrl = ImageUrl(url = base64)
                        )
                    )
                }
                
                messages.add(
                    ApiMessage(
                        role = entity.role,
                        content = contentParts
                    )
                )
            } else {
                // 普通文本消息
                messages.add(
                    ApiMessage(
                        role = entity.role,
                        content = entity.content
                    )
                )
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
     * 清空当前会话（用于创建新会话）
     */
    fun clearCurrentSession() {
        _currentSessionId.value = null
        _messages.value = emptyList()
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _errorMessage.value = null
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
        timestamp = this.timestamp
    )
}

