package com.hfad.mantou.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

/**
 * 聊天会话实体
 */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val sessionId: Long = 0,
    
    val title: String,  // 保存该会话第一个用户问题
    
    val createTime: Long,  // 创建时间戳

    val isArchived: Boolean = false,

    @ColumnInfo(defaultValue = "'chat'")
    val taskType: String = TASK_TYPE_CHAT,

    @ColumnInfo(defaultValue = "NULL")
    val appHtmlPath: String? = null,

    @ColumnInfo(defaultValue = "0")
    val consumedTokens: Long = 0,

    @ColumnInfo(defaultValue = "0")
    val tokenUsageIncludesEstimate: Boolean = false
) {
    @get:Ignore
    val isGenerateTask: Boolean
        get() = taskType == TASK_TYPE_GENERATE

    companion object {
        const val TASK_TYPE_CHAT = "chat"
        const val TASK_TYPE_GENERATE = "generate"
    }
}





