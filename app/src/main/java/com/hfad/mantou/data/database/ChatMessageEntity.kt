package com.hfad.mantou.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 聊天消息实体
 */
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE  // 删除会话时级联删除消息
        )
    ],
    indices = [Index(value = ["sessionId"])]  // 为外键创建索引提升查询性能
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val messageId: Long = 0,
    
    val sessionId: Long,
    
    val role: String,
    
    val content: String,
    
    val imagePath: String? = null,
    
    val timestamp: Long,
    
    val appHtmlPath: String? = null
)









