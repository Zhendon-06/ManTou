package com.hfad.mantou.data.repository

import com.hfad.mantou.data.database.ChatDao
import com.hfad.mantou.data.database.ChatMessageEntity
import com.hfad.mantou.data.database.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 聊天数据仓库
 * 封装数据库操作，提供给 ViewModel 使用
 */
class ChatRepository(private val chatDao: ChatDao) {
    
    // ==================== 会话操作 ====================
    
    /**
     * 创建新会话
     * @param title 会话标题（第一个用户问题）
     * @return 新会话的 sessionId
     */
    suspend fun createSession(title: String): Long {
        val session = ChatSessionEntity(
            title = title,
            createTime = System.currentTimeMillis()
        )
        return chatDao.insertSession(session)
    }
    
    /**
     * 更新会话标题
     */
    suspend fun updateSessionTitle(sessionId: Long, newTitle: String) {
        val session = chatDao.getSessionById(sessionId)
        session?.let {
            chatDao.updateSession(it.copy(title = newTitle))
        }
    }
    
    /**
     * 获取所有会话（Flow，自动更新）
     */
    fun getAllSessions(): Flow<List<ChatSessionEntity>> {
        return chatDao.getAllSessions()
    }
    
    /**
     * 根据 ID 获取会话
     */
    suspend fun getSessionById(sessionId: Long): ChatSessionEntity? {
        return chatDao.getSessionById(sessionId)
    }
    
    /**
     * 删除会话
     */
    suspend fun deleteSession(sessionId: Long) {
        chatDao.deleteSession(sessionId)
    }
    
    /**
     * 删除所有会话
     */
    suspend fun deleteAllSessions() {
        chatDao.deleteAllSessions()
    }
    
    // ==================== 消息操作 ====================
    
    /**
     * 发送用户消息
     * @param sessionId 会话 ID
     * @param content 消息内容
     * @param imagePath 图片路径（可选）
     * @return 新消息的 messageId
     */
    suspend fun sendUserMessage(
        sessionId: Long,
        content: String,
        imagePath: String? = null
    ): Long {
        val message = ChatMessageEntity(
            sessionId = sessionId,
            role = "user",
            content = content,
            imagePath = imagePath,
            timestamp = System.currentTimeMillis()
        )
        return chatDao.insertMessage(message)
    }
    
    /**
     * 添加 AI 回复消息
     * @param sessionId 会话 ID
     * @param content 消息内容
     * @return 新消息的 messageId
     */
    suspend fun addAssistantMessage(sessionId: Long, content: String): Long {
        val message = ChatMessageEntity(
            sessionId = sessionId,
            role = "assistant",
            content = content,
            imagePath = null,
            timestamp = System.currentTimeMillis()
        )
        return chatDao.insertMessage(message)
    }
    
    /**
     * 获取某个会话的所有消息（Flow，自动更新）
     */
    fun getMessagesBySessionId(sessionId: Long): Flow<List<ChatMessageEntity>> {
        return chatDao.getMessagesBySessionId(sessionId)
    }
    
    /**
     * 获取某个会话的所有消息（一次性获取，用于 API 调用）
     */
    suspend fun getMessagesBySessionIdOnce(sessionId: Long): List<ChatMessageEntity> {
        return chatDao.getMessagesBySessionIdOnce(sessionId)
    }
    
    /**
     * 获取某个会话的消息数量
     */
    suspend fun getMessageCount(sessionId: Long): Int {
        return chatDao.getMessageCountBySessionId(sessionId)
    }
    
    /**
     * 删除消息
     */
    suspend fun deleteMessage(messageId: Long) {
        chatDao.deleteMessage(messageId)
    }
    
    /**
     * 获取会话的第一条用户消息（用于获取标题）
     */
    suspend fun getFirstUserMessage(sessionId: Long): ChatMessageEntity? {
        return chatDao.getFirstUserMessage(sessionId)
    }
}

