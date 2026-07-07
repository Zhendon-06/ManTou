package com.hfad.mantou.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 聊天数据访问对象
 */
@Dao
interface ChatDao {
    
    // ==================== 会话相关操作 ====================
    
    /**
     * 插入新会话
     * @return 新插入会话的 sessionId
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity): Long
    
    /**
     * 更新会话（例如更新 title）
     */
    @Update
    suspend fun updateSession(session: ChatSessionEntity)
    
    /**
     * 查询所有会话，按创建时间倒序排列
     */
    @Query("SELECT * FROM chat_sessions WHERE isArchived = 0 ORDER BY createTime DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>

    /**
     * 查询所有已归档会话，按创建时间倒序排列
     */
    @Query("SELECT * FROM chat_sessions WHERE isArchived = 1 ORDER BY createTime DESC")
    fun getArchivedSessions(): Flow<List<ChatSessionEntity>>
    
    /**
     * 根据 sessionId 查询会话
     */
    @Query("SELECT * FROM chat_sessions WHERE sessionId = :sessionId")
    suspend fun getSessionById(sessionId: Long): ChatSessionEntity?
    
    /**
     * 删除会话（会级联删除该会话的所有消息）
     */
    @Query("DELETE FROM chat_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    /**
     * 设置会话归档状态
     */
    @Query("UPDATE chat_sessions SET isArchived = :isArchived WHERE sessionId = :sessionId")
    suspend fun setSessionArchived(sessionId: Long, isArchived: Boolean)
    
    /**
     * 删除所有会话
     */
    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllSessions()
    
    // ==================== 消息相关操作 ====================
    
    /**
     * 插入新消息
     * @return 新插入消息的 messageId
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long
    
    /**
     * 批量插入消息
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)
    
    /**
     * 查询某个会话的所有消息，按时间戳升序排列
     */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySessionId(sessionId: Long): Flow<List<ChatMessageEntity>>
    
    /**
     * 查询某个会话的所有消息（非 Flow，用于一次性获取）
     */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesBySessionIdOnce(sessionId: Long): List<ChatMessageEntity>

    /**
     * 根据 messageId 查询单条消息
     */
    @Query("SELECT * FROM chat_messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: Long): ChatMessageEntity?
    
    /**
     * 查询某个会话的消息数量
     */
    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun getMessageCountBySessionId(sessionId: Long): Int
    
    /**
     * 删除某条消息
     */
    @Query("DELETE FROM chat_messages WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: Long)

    /**
     * 更新某条消息内容
     */
    @Query("UPDATE chat_messages SET content = :content WHERE messageId = :messageId")
    suspend fun updateMessageContent(messageId: Long, content: String)

    /**
     * 删除同一会话中位于某条消息之后的所有消息，用于编辑用户请求后重新生成分支
     */
    @Query(
        """
        DELETE FROM chat_messages
        WHERE sessionId = :sessionId
          AND (
            timestamp > :timestamp
            OR (timestamp = :timestamp AND messageId > :messageId)
          )
        """
    )
    suspend fun deleteMessagesAfter(sessionId: Long, timestamp: Long, messageId: Long)
    
    /**
     * 删除某个会话的所有消息
     */
    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySessionId(sessionId: Long)
    
    // ==================== 组合查询 ====================
    
    /**
     * 查询某个会话的第一条用户消息（用于获取会话标题）
     */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId AND role = 'user' ORDER BY timestamp ASC LIMIT 1")
    suspend fun getFirstUserMessage(sessionId: Long): ChatMessageEntity?
    
    /**
     * 查询某个会话的最后一条消息
     */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(sessionId: Long): ChatMessageEntity?
}








