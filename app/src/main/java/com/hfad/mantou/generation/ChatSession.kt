package com.hfad.mantou.generation

import android.net.Uri
import androidx.lifecycle.LiveData
import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.data.GenerateTaskState
import com.hfad.mantou.data.SessionTokenUsage
import com.hfad.mantou.data.database.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

interface ChatSession {
    val currentSessionId: LiveData<Long?>
    val selectedWorkspace: LiveData<String?>
    val messages: LiveData<List<ChatMessage>>
    val allSessions: Flow<List<ChatSessionEntity>>
    val archivedSessions: Flow<List<ChatSessionEntity>>
    val isLoading: LiveData<Boolean>
    val runningSessionIds: LiveData<Set<Long>>
    val isGeneratingApp: LiveData<Boolean>
    val generateTaskStates: LiveData<Map<Long, GenerateTaskState>>
    val errorMessage: LiveData<String?>
    val appGenerated: LiveData<String?>
    val activeModelName: LiveData<String?>
    val noModelConfigured: LiveData<Boolean>
    val currentSessionTokenUsage: LiveData<SessionTokenUsage>
    fun refreshActiveModel()
    suspend fun createEmptySession(): Boolean
    fun switchToSession(sessionId: Long)
    fun selectWorkspace(name: String?)
    fun sendMessage(content: String, imagePath: String? = null, imageUris: List<Uri>? = null)
    fun stopStreaming()
    fun deleteSession(sessionId: Long)
    fun setSessionArchived(sessionId: Long, isArchived: Boolean)
    fun deleteMessage(messageId: Long)
    fun updateMessageContent(messageId: Long, content: String)
    fun editUserMessageAndRegenerate(messageId: Long, content: String)
    fun deleteAllSessions()
    fun clearCurrentSession()
    fun clearError()
    fun clearAppGenerated()
    fun clearNoModelConfigured()
}

