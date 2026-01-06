package com.hfad.mantou.data.repository

import com.hfad.mantou.data.database.ChatDao
import com.hfad.mantou.data.database.ChatMessageEntity
import com.hfad.mantou.data.database.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 聊天数据仓库 - 封装数据库操作
 */
class ChatRepository(private val chatDao: ChatDao) {

    // ==================== 会话操作 ====================

    suspend fun createSession(title: String): Long {
        return chatDao.insertSession(ChatSessionEntity(
            title = title,
            createTime = System.currentTimeMillis()
        ))
    }

    suspend fun updateSessionTitle(sessionId: Long, newTitle: String) {
        chatDao.getSessionById(sessionId)?.let {
            chatDao.updateSession(it.copy(title = newTitle))
        }
    }

    fun getAllSessions(): Flow<List<ChatSessionEntity>> = chatDao.getAllSessions()

    suspend fun deleteSession(sessionId: Long) = chatDao.deleteSession(sessionId)

    suspend fun deleteAllSessions() = chatDao.deleteAllSessions()

    // ==================== 消息操作 ====================

    suspend fun sendUserMessage(sessionId: Long, content: String, imagePath: String? = null): Long {
        return chatDao.insertMessage(ChatMessageEntity(
            sessionId = sessionId,
            role = "user",
            content = content,
            imagePath = imagePath,
            timestamp = System.currentTimeMillis()
        ))
    }

    suspend fun addAssistantMessage(sessionId: Long, content: String): Long {
        return chatDao.insertMessage(ChatMessageEntity(
            sessionId = sessionId,
            role = "assistant",
            content = content,
            timestamp = System.currentTimeMillis()
        ))
    }

    fun getMessagesBySessionId(sessionId: Long): Flow<List<ChatMessageEntity>> {
        return chatDao.getMessagesBySessionId(sessionId)
    }

    suspend fun getMessagesBySessionIdOnce(sessionId: Long): List<ChatMessageEntity> {
        return chatDao.getMessagesBySessionIdOnce(sessionId)
    }

    suspend fun getMessageCount(sessionId: Long): Int {
        return chatDao.getMessageCountBySessionId(sessionId)
    }
}

