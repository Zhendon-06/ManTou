package com.hfad.mantou.data.repository

import com.hfad.mantou.data.database.ChatDao
import com.hfad.mantou.data.database.ChatMessageEntity
import com.hfad.mantou.data.database.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * ============================================
 * 聊天数据仓库 - 核心类说明
 * ============================================
 * 
 * 这个类的作用：封装所有数据库操作，提供统一的数据访问接口
 * 
 * 为什么需要Repository层？
 * 1. 解耦合：ViewModel不直接依赖数据库，只依赖Repository
 * 2. 统一管理：集中管理所有数据操作
 * 3. 便于测试：可以创建假的Repository进行测试
 * 4. 易于维护：修改数据源不影响上层代码
 * 
 * 架构位置：
 * ViewModel → Repository → ChatDao → Room数据库
 * 
 * 核心概念：
 * - Repository：数据仓库，负责数据的存储和获取
 * - ChatDao：数据访问对象，直接操作数据库
 * - Flow：Kotlin的流，用于实现数据的实时更新
 */
class ChatRepository(private val chatDao: ChatDao) {

    // ==================== 会话操作 ====================

    /**
     * 创建新会话
     * 
     * 什么时候调用？
     * - 用户点击"新建会话"按钮
     * - 首次发送消息时自动创建
     * 
     * 参数title：会话标题（例如："新会话"、"关于AI的讨论"）
     * 返回值：新创建会话的sessionId（数据库自动生成的ID）
     * 
     * 实现细节：
     * - 创建ChatSessionEntity对象
     * - 设置标题和创建时间
     * - 调用chatDao.insertSession()插入数据库
     * - 返回数据库生成的sessionId
     */
    suspend fun createSession(title: String): Long {
        return chatDao.insertSession(ChatSessionEntity(
            title = title,
            createTime = System.currentTimeMillis()
        ))
    }

    /**
     * 更新会话标题
     * 
     * 什么时候调用？
     * - 用户修改会话标题
     * - 根据第一条消息自动更新标题
     * 
     * 参数sessionId：要更新的会话ID
     * 参数newTitle：新的标题
     * 
     * 实现细节：
     * - 先查询该会话是否存在
     * - 如果存在，复制并更新标题
     * - 调用chatDao.updateSession()更新数据库
     */
    suspend fun updateSessionTitle(sessionId: Long, newTitle: String) {
        chatDao.getSessionById(sessionId)?.let {
            chatDao.updateSession(it.copy(title = newTitle))
        }
    }

    /**
     * 获取所有会话（实时更新）
     * 
     * 什么时候调用？
     * - 显示会话列表时
     * - 需要监听会话变化时
     * 
     * 返回值：Flow<List<ChatSessionEntity>>，会话列表的流
     * 
     * 为什么返回Flow？
     * - Flow是Kotlin的异步流
     * - 当数据库中的会话变化时，Flow会自动发射新的数据
     * - 实现数据的实时更新，不需要手动刷新
     * 
     * 使用示例：
     * ```
     * repository.getAllSessions().collect { sessions ->
     *     // 当会话列表变化时，这个代码块会被执行
     *     adapter.submitList(sessions)
     * }
     * ```
     */
    fun getAllSessions(): Flow<List<ChatSessionEntity>> = chatDao.getAllSessions()

    /**
     * 删除会话
     * 
     * 什么时候调用？
     * - 用户删除某个会话
     * 
     * 参数sessionId：要删除的会话ID
     * 
     * 实现细节：
     * - 调用chatDao.deleteSession()删除会话
     * - 数据库配置了级联删除，会自动删除该会话的所有消息
     * 
     * 什么是级联删除？
     * - 删除会话时，自动删除该会话的所有消息
     * - 不需要手动删除消息
     * - 在Entity中通过@ForeignKey配置
     */
    suspend fun deleteSession(sessionId: Long) = chatDao.deleteSession(sessionId)

    /**
     * 删除所有会话
     * 
     * 什么时候调用？
     * - 用户清空所有聊天记录
     * - 测试时重置数据
     * 
     * 实现细节：
     * - 调用chatDao.deleteAllSessions()删除所有会话
     * - 会自动删除所有消息（级联删除）
     */
    suspend fun deleteAllSessions() = chatDao.deleteAllSessions()

    // ==================== 消息操作 ====================

    /**
     * 发送用户消息
     * 
     * 什么时候调用？
     * - 用户点击发送按钮
     * - 用户发送图片
     * 
     * 参数sessionId：消息所属的会话ID
     * 参数content：消息内容（文本）
     * 参数imagePath：图片路径（可选，如果没有图片则为null）
     * 返回值：新插入消息的messageId（数据库自动生成的ID）
     * 
     * 实现细节：
     * - 创建ChatMessageEntity对象
     * - 设置会话ID、角色（user）、内容、图片路径、时间戳
     * - 调用chatDao.insertMessage()插入数据库
     * - 返回数据库生成的messageId
     * 
     * 为什么需要imagePath参数？
     * - 用户可以发送纯文本消息（imagePath=null）
     * - 用户也可以发送图片消息（imagePath=图片路径）
     * - Repository统一处理这两种情况
     */
    suspend fun sendUserMessage(sessionId: Long, content: String, imagePath: String? = null): Long {
        return chatDao.insertMessage(ChatMessageEntity(
            sessionId = sessionId,
            role = "user",
            content = content,
            imagePath = imagePath,
            timestamp = System.currentTimeMillis()
        ))
    }

    /**
     * 添加AI助手消息
     * 
     * 什么时候调用？
     * - AI回复完成时
     * - 流式输出结束时
     * 
     * 参数sessionId：消息所属的会话ID
     * 参数content：AI回复的内容
     * 返回值：新插入消息的messageId（数据库自动生成的ID）
     * 
     * 实现细节：
     * - 创建ChatMessageEntity对象
     * - 设置会话ID、角色（assistant）、内容、时间戳
     * - 注意：AI消息通常没有图片（imagePath=null）
     * - 调用chatDao.insertMessage()插入数据库
     * - 返回数据库生成的messageId
     * 
     * 为什么和sendUserMessage分开？
     * - 用户消息可能有图片，AI消息通常没有
     * - 语义上更清晰：发送用户消息 vs 添加AI回复
     * - 便于后续扩展（比如AI消息可能需要特殊处理）
     */
    suspend fun addAssistantMessage(sessionId: Long, content: String, appHtmlPath: String? = null): Long {
        return chatDao.insertMessage(ChatMessageEntity(
            sessionId = sessionId,
            role = "assistant",
            content = content,
            timestamp = System.currentTimeMillis(),
            appHtmlPath = appHtmlPath
        ))
    }

    /**
     * 获取某个会话的所有消息（实时更新）
     * 
     * 什么时候调用？
     * - 切换到某个会话时
     * - 需要监听消息变化时
     * 
     * 参数sessionId：要查询的会话ID
     * 返回值：Flow<List<ChatMessageEntity>>，消息列表的流
     * 
     * 为什么返回Flow？
     * - 当该会话有新消息时，Flow会自动发射新的数据
     * - 实现消息的实时更新
     * - 不需要手动刷新列表
     * 
     * 使用示例：
     * ```
     * repository.getMessagesBySessionId(sessionId).collect { messages ->
     *     // 当消息列表变化时，这个代码块会被执行
     *     adapter.submitList(messages)
     * }
     * ```
     * 
     * 消息顺序：
     * - 按时间戳升序排列（最早的在前，最新的在后）
     * - 符合聊天的阅读习惯
     */
    fun getMessagesBySessionId(sessionId: Long): Flow<List<ChatMessageEntity>> {
        return chatDao.getMessagesBySessionId(sessionId)
    }

    /**
     * 获取某个会话的所有消息（一次性获取）
     * 
     * 什么时候调用？
     * - 只需要获取一次消息，不需要监听变化
     * - 导出聊天记录时
     * 
     * 参数sessionId：要查询的会话ID
     * 返回值：List<ChatMessageEntity>>，消息列表
     * 
     * 和getMessagesBySessionId的区别？
     * - getMessagesBySessionId：返回Flow，实时更新
     * - getMessagesBySessionIdOnce：返回List，一次性获取
     * 
     * 使用场景：
     * - 如果需要实时监听消息变化，用getMessagesBySessionId
     * - 如果只需要获取一次，用getMessagesBySessionIdOnce
     */
    suspend fun getMessagesBySessionIdOnce(sessionId: Long): List<ChatMessageEntity> {
        return chatDao.getMessagesBySessionIdOnce(sessionId)
    }

    /**
     * 获取某个会话的消息数量
     * 
     * 什么时候调用？
     * - 统计消息数量
     * - 判断会话是否为空
     * 
     * 参数sessionId：要查询的会话ID
     * 返回值：Int，消息数量
     * 
     * 使用场景：
     * - 显示消息数量统计
     * - 判断是否需要显示欢迎界面
     * - 判断会话是否为空
     */
    suspend fun getMessageCount(sessionId: Long): Int {
        return chatDao.getMessageCountBySessionId(sessionId)
    }

    /** 删除单条消息（长按菜单调用）。 */
    suspend fun deleteMessage(messageId: Long) = chatDao.deleteMessage(messageId)

    /** 更新单条消息内容（长按菜单调用）。 */
    suspend fun updateMessageContent(messageId: Long, content: String) {
        chatDao.updateMessageContent(messageId, content)
    }
}
