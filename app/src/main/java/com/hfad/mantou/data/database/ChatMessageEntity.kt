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
    
    val sessionId: Long,  // 外键，关联到 ChatSessionEntity
    
    val role: String,  // "user" 或 "assistant"
    
    val content: String,  // 文本内容
    
    val imagePath: String? = null,  // 可为空，本地图片路径
    
    val timestamp: Long  // 消息时间戳
)

