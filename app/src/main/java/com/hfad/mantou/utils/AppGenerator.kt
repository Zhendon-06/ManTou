package com.hfad.mantou.utils

import android.content.Context
import java.io.File
import java.util.UUID

object AppGenerator {

    private const val APP_DIR = "generated_apps"

    const val SYSTEM_PROMPT = """你是一个专业的网页应用生成器。根据用户的一句话描述，生成一个完整的、可直接运行的HTML文件。

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

    const val APP_GEN_MAX_TOKENS = 8192

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

    fun saveHtmlFile(context: Context, htmlContent: String): File {
        val appDir = File(context.filesDir, APP_DIR)
        if (!appDir.exists()) appDir.mkdirs()
        val file = File(appDir, "app_${UUID.randomUUID()}.html")
        file.writeText(htmlContent)
        return file
    }
}
