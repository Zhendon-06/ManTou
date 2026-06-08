package com.hfad.mantou.utils

import android.content.Context
import android.content.res.Configuration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object AppGenerator {

    private const val BASE_SYSTEM_PROMPT = """你是一个专业的网页应用生成器。根据用户的一句话描述，生成一个完整的、可直接运行的HTML文件。

严格要求：
1. 必须生成一个完整的、自包含的HTML文件，所有CSS和JavaScript都内联在HTML中
2. 必须使用移动端App风格的布局设计：
   - 使用viewport meta标签适配移动端
   - 底部导航栏（如需要）
   - 顶部标题栏
   - 卡片式布局
   - 圆角、阴影等现代UI元素
   - 合适的字体大小和间距
   - 响应式设计
3. 页面要美观、交互完整、功能可用
4. 使用现代化的配色方案
5. 所有交互功能必须完整实现，不能有占位或空函数
6. 只返回HTML代码，不要有任何解释说明文字
7. 代码必须以<!DOCTYPE html>开头，以</html>结尾"""

    const val APP_GEN_MAX_TOKENS = 81920
    private const val WEB_APP_ID_NAME = "mantou-webapp-id"
    private val META_TAG_REGEX = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val WEB_APP_ID_NAME_REGEX = Regex("\\bname\\s*=\\s*['\"]$WEB_APP_ID_NAME['\"]", RegexOption.IGNORE_CASE)
    private val META_CONTENT_REGEX = Regex("\\bcontent\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)

    fun buildSystemPrompt(context: Context): String {
        val metrics = context.resources.displayMetrics
        val widthPx = metrics.widthPixels
        val heightPx = metrics.heightPixels
        val orientation = when (context.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "unknown"
        }

        return """
            $BASE_SYSTEM_PROMPT

            当前设备屏幕信息：
            - widthPx: $widthPx
            - heightPx: $heightPx
            - orientation: $orientation

            屏幕适配要求：
            1. 生成的网页 App 必须依据上述设备尺寸进行布局，让主界面在当前设备上尽量填满屏幕，看起来像原生移动 App。
            2. 必须设置 viewport：<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
            3. html、body 和主容器必须使用 min-height: 100vh 或 height: 100vh；主界面宽度应使用 100vw 或 width: 100%。
            4. 避免页面四周出现无意义的大留白，不要把主要内容压缩成居中的窄卡片。
            5. 如果需要滚动，只让内容区域滚动，顶部/底部关键操作区域应保持稳定可用。
            6. 需要考虑安全区和移动端浏览器环境，可使用 padding: env(safe-area-inset-*)。
        """.trimIndent()
    }

    fun extractHtml(content: String): String? {
        var trimmed = content.trim()

        val codeBlockRegex = Regex("```(?:html|HTML)?\\s*\\n?([\\s\\S]*?)\\n?```")
        val match = codeBlockRegex.find(trimmed)
        if (match != null) {
            trimmed = match.groupValues[1].trim()
        }

        if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
            return trimmed
        }
        val startIndex = trimmed.indexOf("<!DOCTYPE", ignoreCase = true)
        if (startIndex >= 0) return trimmed.substring(startIndex)
        val htmlStart = trimmed.indexOf("<html", ignoreCase = true)
        if (htmlStart >= 0) return trimmed.substring(htmlStart)
        return null
    }

    fun ensureWebAppIdentity(htmlContent: String): String {
        if (extractWebAppIdentity(htmlContent) != null) return htmlContent

        val metaTag = """<meta name="$WEB_APP_ID_NAME" content="${UUID.randomUUID()}">"""
        val headMatch = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(htmlContent)
        if (headMatch != null) {
            val insertAt = headMatch.range.last + 1
            return htmlContent.substring(0, insertAt) +
                    "\n    $metaTag" +
                    htmlContent.substring(insertAt)
        }

        val bodyMatch = Regex("<body(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(htmlContent)
        if (bodyMatch != null) {
            val insertAt = bodyMatch.range.first
            return htmlContent.substring(0, insertAt) +
                    "<head>\n    $metaTag\n</head>\n" +
                    htmlContent.substring(insertAt)
        }

        return "$metaTag\n$htmlContent"
    }

    fun extractWebAppIdentity(htmlContent: String): String? {
        return META_TAG_REGEX.findAll(htmlContent)
            .firstNotNullOfOrNull { match ->
                val tag = match.value
                if (!WEB_APP_ID_NAME_REGEX.containsMatchIn(tag)) return@firstNotNullOfOrNull null
                META_CONTENT_REGEX.find(tag)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            }
    }

    fun saveHtmlFile(context: Context, htmlContent: String, userMessage: String): File {
        AgentWorkspace.ensureWorkspace(context)
        val appDir = File(context.filesDir, AgentWorkspace.WEB_DIR)
        if (!appDir.exists()) appDir.mkdirs()
        val file = nextAvailableHtmlFile(appDir, userMessage)
        file.writeText(ensureWebAppIdentity(htmlContent))
        return file
    }

    private fun nextAvailableHtmlFile(appDir: File, userMessage: String): File {
        val stem = inferAppFileStem(userMessage)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        var file = File(appDir, "${stem}_$timestamp.html")
        var index = 2
        while (file.exists()) {
            file = File(appDir, "${stem}_${timestamp}_$index.html")
            index++
        }
        return file
    }

    private fun inferAppFileStem(userMessage: String): String {
        val message = userMessage.trim()
        val lower = message.lowercase(Locale.getDefault())

        APP_TYPE_KEYWORDS.firstOrNull { (keyword, _) ->
            lower.contains(keyword.lowercase(Locale.getDefault()))
        }?.let { (_, name) ->
            return sanitizeFileStem(name)
        }

        val cleaned = COMMON_REQUEST_WORDS.fold(message) { current, word ->
            current.replace(word, "", ignoreCase = true)
        }
            .replace(Regex("[，。！？、,.!?；;：:\\[\\]（）(){}]+"), "")
            .trim()

        return sanitizeFileStem(cleaned.ifBlank { "web_app" })
    }

    private fun sanitizeFileStem(rawName: String): String {
        val sanitized = rawName
            .replace(Regex("\\s+"), "")
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .trim('_', '-', '.', ' ')
            .take(24)

        return sanitized.ifBlank { "web_app" }
    }

    private val APP_TYPE_KEYWORDS = listOf(
        "pomodoro" to "番茄钟",
        "番茄钟" to "番茄钟",
        "番茄" to "番茄钟",
        "todo" to "待办清单",
        "待办" to "待办清单",
        "任务" to "任务清单",
        "weather" to "天气",
        "天气" to "天气",
        "calculator" to "计算器",
        "计算器" to "计算器",
        "calendar" to "日历",
        "日历" to "日历",
        "note" to "笔记",
        "笔记" to "笔记",
        "timer" to "计时器",
        "计时" to "计时器",
        "秒表" to "秒表",
        "clock" to "时钟",
        "时钟" to "时钟",
        "habit" to "习惯追踪",
        "习惯" to "习惯追踪",
        "budget" to "预算",
        "记账" to "记账",
        "预算" to "预算",
        "kanban" to "看板",
        "看板" to "看板",
        "2048" to "2048游戏",
        "snake" to "贪吃蛇",
        "贪吃蛇" to "贪吃蛇",
        "井字棋" to "井字棋",
        "扫雷" to "扫雷",
        "game" to "游戏",
        "游戏" to "游戏",
        "抽奖" to "抽奖",
        "转盘" to "转盘",
        "二维码" to "二维码",
        "简历" to "简历",
        "菜谱" to "菜谱",
        "健身" to "健身",
        "画板" to "画板"
    )

    private val COMMON_REQUEST_WORDS = listOf(
        "帮我生成一个",
        "帮我创建一个",
        "帮我制作一个",
        "帮我做一个",
        "生成一个",
        "创建一个",
        "制作一个",
        "做一个",
        "写一个",
        "我要一个",
        "我想要一个",
        "请帮我",
        "帮我",
        "生成",
        "创建",
        "制作",
        "网页应用",
        "web app",
        "website",
        "小程序",
        "应用",
        "网页",
        "工具",
        "app",
        "一个"
    )
}
