package com.hfad.mantou.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 聊天会话实体
 */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val sessionId: Long = 0,
    
    val title: String,  // 保存该会话第一个用户问题
    
    val createTime: Long  // 创建时间戳
)

