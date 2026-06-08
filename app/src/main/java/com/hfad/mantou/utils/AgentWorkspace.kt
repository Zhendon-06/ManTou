package com.hfad.mantou.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AgentWorkspace {

    const val WEB_DIR = "generated_apps"
    private const val AGENT_DIR = "agent"
    private const val MEMORY_DIR = "memory"
    private const val SOUL_FILE = "SOUL.md"
    private const val CHAT_FILE = "CHAT.md"
    private const val MEMORY_FILE = "MEMORY.md"

    fun ensureWorkspace(context: Context) {
        webDir(context).mkdirs()
        agentDir(context).mkdirs()
        memoryDir(context).mkdirs()

        writeIfMissing(soulFile(context), DEFAULT_SOUL)
        writeIfMissing(chatFile(context), DEFAULT_CHAT)
        writeIfMissing(memoryFile(context), DEFAULT_MEMORY)
    }

    fun buildSystemPrompt(context: Context): String {
        ensureWorkspace(context)
        return listOf(
            "# Agent Identity",
            soulFile(context).readText(),
            "# Conversation Contract",
            chatFile(context).readText(),
            "# Memory",
            memoryFile(context).readText()
        ).joinToString("\n\n")
    }

    fun appendExplicitMemoryIfNeeded(context: Context, userMessage: String): Boolean {
        val memory = extractExplicitMemory(userMessage) ?: return false
        ensureWorkspace(context)

        val file = memoryFile(context)
        val current = file.readText()
        val recordedMemory = current.substringAfter("## 已记录记忆", "")
        if (recordedMemory.contains(memory)) return false

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        file.appendText("\n- $timestamp：$memory\n")
        return true
    }

    fun loadWorkspaceTree(context: Context): List<WorkspaceNode> {
        ensureWorkspace(context)

        val webNode = folderNode(
            name = WEB_DIR,
            displayPath = "/workspace/$WEB_DIR",
            level = 1,
            defaultExpanded = false,
            dir = webDir(context)
        )

        val agentNode = folderNode(
            name = AGENT_DIR,
            displayPath = "/workspace/$AGENT_DIR",
            level = 1,
            defaultExpanded = true,
            dir = agentDir(context)
        )

        val memoryNode = folderNode(
            name = MEMORY_DIR,
            displayPath = "/workspace/$MEMORY_DIR",
            level = 1,
            defaultExpanded = true,
            dir = memoryDir(context)
        )

        return listOf(
            WorkspaceNode(
                name = "workspace",
                displayPath = "/workspace",
                isDirectory = true,
                level = 0,
                defaultExpanded = true,
                children = listOf(webNode, agentNode, memoryNode)
            )
        )
    }

    private fun folderNode(
        name: String,
        displayPath: String,
        level: Int,
        defaultExpanded: Boolean,
        dir: File
    ): WorkspaceNode {
        val children = dir.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?.map { file ->
                if (file.isDirectory) {
                    folderNode(
                        name = file.name,
                        displayPath = "$displayPath/${file.name}",
                        level = level + 1,
                        defaultExpanded = false,
                        dir = file
                    )
                } else {
                    fileNode(file, "$displayPath/${file.name}", level + 1)
                }
            }
            .orEmpty()

        return WorkspaceNode(
            name = name,
            displayPath = displayPath,
            isDirectory = true,
            level = level,
            defaultExpanded = defaultExpanded,
            children = children
        )
    }

    private fun fileNode(file: File, displayPath: String, level: Int) = WorkspaceNode(
        name = file.name,
        displayPath = displayPath,
        isDirectory = false,
        level = level,
        absolutePath = file.absolutePath
    )

    private fun extractExplicitMemory(message: String): String? {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return null

        val negativePrefixes = listOf("不要记住", "别记住", "不用记住", "不要保存", "别保存")
        if (negativePrefixes.any { trimmed.startsWith(it) }) return null

        val triggers = listOf(
            "记住",
            "保存这个到记忆里",
            "保存到记忆",
            "写入记忆",
            "以后都",
            "以后请",
            "下次都",
            "remember this",
            "save this to memory"
        )
        val lower = trimmed.lowercase()
        if (triggers.none { lower.contains(it.lowercase()) }) return null

        val prefixes = listOf(
            "请你记住",
            "请记住",
            "帮我记住",
            "记住一下",
            "记住",
            "保存这个到记忆里",
            "保存到记忆里",
            "保存到记忆",
            "写入记忆",
            "remember this",
            "save this to memory"
        )

        var memory = trimmed
        prefixes.firstOrNull { memory.startsWith(it, ignoreCase = true) }?.let {
            memory = memory.removePrefix(it)
        }

        memory = memory.trim(' ', '\n', '\t', '：', ':', '。', '.', '，', ',')
        if (memory.isBlank()) memory = trimmed
        return memory.take(500)
    }

    private fun webDir(context: Context) = File(context.filesDir, WEB_DIR)
    private fun agentDir(context: Context) = File(context.filesDir, AGENT_DIR)
    private fun memoryDir(context: Context) = File(context.filesDir, MEMORY_DIR)
    private fun soulFile(context: Context) = File(agentDir(context), SOUL_FILE)
    private fun chatFile(context: Context) = File(agentDir(context), CHAT_FILE)
    private fun memoryFile(context: Context) = File(memoryDir(context), MEMORY_FILE)

    private fun writeIfMissing(file: File, content: String) {
        if (!file.exists()) file.writeText(content.trimIndent() + "\n")
    }

    private val DEFAULT_SOUL = """
        # SOUL

        ## 我是谁
        我是馒头，一个温和、清醒、可靠的 AI agent。我的核心任务是帮助用户把想法变成清楚、可执行、可验证的结果。

        ## 人格与语气
        - 默认使用自然、简洁、有温度的中文。
        - 面对复杂任务时保持耐心，先理解上下文，再给出稳妥行动。
        - 可以有轻松感，但不油滑、不夸张、不用空泛热情代替事实。

        ## 价值观
        - 准确优先：不知道就说明不确定，并主动验证。
        - 用户主权：尊重用户偏好，不擅自替用户做高风险决定。
        - 长期一致：把稳定偏好沉淀到记忆里，让后续对话更贴近用户。

        ## 行为边界
        - 不伪装成真人，不声称拥有现实世界经历。
        - 不编造文件、结果、引用或工具执行情况。
        - 遇到医疗、法律、金融等高风险内容时，要提醒用户寻求专业意见。
        - 涉及隐私、账号、密钥、破坏性操作时必须谨慎处理。
    """

    private val DEFAULT_CHAT = """
        # CHAT

        ## 怎么对话
        - 默认直接回答用户的问题，任务明确时直接执行。
        - 需要确认时只问最关键的问题，不把选择负担推给用户。
        - 回复结构要清楚，短任务短答，复杂任务分阶段说明。
        - 解释代码或改动时，优先说影响和使用方式，再补实现细节。

        ## 工具使用规范
        - 能从本地上下文确认的事情先查本地。
        - 涉及实时信息、版本、价格、规则、文档时必须验证最新来源。
        - 执行文件修改前先说明要改哪里和为什么。
        - 执行测试、构建或检查后，要把结果明确告诉用户。

        ## 记忆交互
        - 用户明确说“记住”“保存到记忆”“以后都”时，将稳定偏好写入 MEMORY.md。
        - 如果用户反复表达某个长期偏好，可以主动询问是否要记住。
        - 不记录一次性任务、敏感隐私、临时情绪或不稳定事实，除非用户明确要求。
    """

    private val DEFAULT_MEMORY = """
        # MEMORY

        ## 记忆写入规则

        ### 1. 显式触发：用户明确说「记住」
        当用户说：
        - “记住我喜欢用 TypeScript”
        - “以后都用 pnpm 别用 npm”
        - “保存这个到记忆里”

        Agent 会把关键信息写入 MEMORY.md。下次对话时，这条记忆会进入 prompt。

        ### 2. 隐式触发：Agent 主动记录
        当用户反复提到某个稳定偏好或长期事实时，Agent 可以主动询问：
        - “要不要我记住这个？”

        只有适合长期使用、低敏感度、对未来对话有帮助的信息才应该被记录。

        ## 已记录记忆
    """
}

data class WorkspaceNode(
    val name: String,
    val displayPath: String,
    val isDirectory: Boolean,
    val level: Int,
    val absolutePath: String? = null,
    val defaultExpanded: Boolean = false,
    val children: List<WorkspaceNode> = emptyList()
)
