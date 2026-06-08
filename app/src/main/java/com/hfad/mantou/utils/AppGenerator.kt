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
    const val WEB_APP_BRIDGE_NAME = "MantouApp"
    const val WEB_APP_USER_AGENT_TOKEN = "MantouApp/1"
    private const val WEB_APP_ID_NAME = "mantou-webapp-id"
    private const val WEB_APP_RUNTIME_GUARD_START = "<!-- mantou-webapp-runtime-guard:start -->"
    private const val WEB_APP_RUNTIME_GUARD_END = "<!-- mantou-webapp-runtime-guard:end -->"
    private val META_TAG_REGEX = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val HEAD_TAG_REGEX = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val HEAD_END_TAG_REGEX = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
    private val HTML_TAG_REGEX = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val BODY_TAG_REGEX = Regex("<body(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val WEB_APP_ID_NAME_REGEX = Regex("\\bname\\s*=\\s*['\"]$WEB_APP_ID_NAME['\"]", RegexOption.IGNORE_CASE)
    private val META_CONTENT_REGEX = Regex("\\bcontent\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
    private val META_CHARSET_REGEX = Regex("\\bcharset\\s*=", RegexOption.IGNORE_CASE)

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
        val withIdentity = ensureWebAppId(htmlContent)
        return ensureWebAppRuntimeGuard(withIdentity)
    }

    fun withMantouWebAppUserAgent(userAgent: String?): String {
        val baseUserAgent = userAgent.orEmpty()
        return if (baseUserAgent.contains(WEB_APP_USER_AGENT_TOKEN)) {
            baseUserAgent
        } else {
            "$baseUserAgent $WEB_APP_USER_AGENT_TOKEN".trim()
        }
    }

    private fun ensureWebAppId(htmlContent: String): String {
        if (extractWebAppIdentity(htmlContent) != null) return htmlContent

        val metaTag = """<meta name="$WEB_APP_ID_NAME" content="${UUID.randomUUID()}">"""
        return insertIntoHead(htmlContent, metaTag)
    }

    private fun ensureWebAppRuntimeGuard(htmlContent: String): String {
        if (htmlContent.contains(WEB_APP_RUNTIME_GUARD_START)) return htmlContent
        return insertIntoHead(htmlContent, WEB_APP_RUNTIME_GUARD)
    }

    private fun insertIntoHead(htmlContent: String, block: String): String {
        val indentedBlock = block.replace("\n", "\n    ")
        val headMatch = HEAD_TAG_REGEX.find(htmlContent)
        if (headMatch != null) {
            val insertAt = insertionPointInHead(htmlContent, headMatch.range.last + 1)
            return htmlContent.substring(0, insertAt) +
                    "\n    $indentedBlock" +
                    htmlContent.substring(insertAt)
        }

        val headBlock = "<head>\n    $indentedBlock\n</head>\n"
        val bodyMatch = BODY_TAG_REGEX.find(htmlContent)
        if (bodyMatch != null) {
            return htmlContent.substring(0, bodyMatch.range.first) +
                    headBlock +
                    htmlContent.substring(bodyMatch.range.first)
        }

        val htmlMatch = HTML_TAG_REGEX.find(htmlContent)
        if (htmlMatch != null) {
            val insertAt = htmlMatch.range.last + 1
            return htmlContent.substring(0, insertAt) +
                    "\n$headBlock" +
                    htmlContent.substring(insertAt)
        }

        return "$block\n$htmlContent"
    }

    private fun insertionPointInHead(htmlContent: String, headContentStart: Int): Int {
        val headEnd = HEAD_END_TAG_REGEX.find(htmlContent, headContentStart)?.range?.first
            ?: htmlContent.length
        val charsetMeta = META_TAG_REGEX.findAll(htmlContent, headContentStart)
            .firstOrNull { match ->
                match.range.first < headEnd && META_CHARSET_REGEX.containsMatchIn(match.value)
            }
        return charsetMeta?.range?.last?.plus(1) ?: headContentStart
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

    private val WEB_APP_RUNTIME_GUARD = """
        $WEB_APP_RUNTIME_GUARD_START
        <meta name="mantou-webapp-runtime" content="1">
        <script>
        (function () {
            var bridgeAllowed = false;
            try {
                bridgeAllowed = !!(
                    window.MantouApp &&
                    typeof window.MantouApp.isMantouApp === "function" &&
                    window.MantouApp.isMantouApp()
                );
            } catch (error) {
                bridgeAllowed = false;
            }

            var userAgentAllowed = (navigator.userAgent || "").indexOf("MantouApp/1") !== -1;
            if (bridgeAllowed || userAgentAllowed) {
                window.__MANTOU_WEBAPP_ALLOWED__ = true;
                return;
            }

            window.__MANTOU_WEBAPP_ALLOWED__ = false;
            var lockId = "mantou-open-gate";
            var styleId = "mantou-open-gate-style";
            var gateMarkup = '<section class="mantou-open-card"><h1 class="mantou-open-title">请用馒头App打开</h1><p class="mantou-open-text">这是由馒头生成的网页应用，只能在馒头App内运行。</p></section>';
            var rendering = false;

            function installStyle() {
                if (document.getElementById(styleId)) return;
                var style = document.createElement("style");
                style.id = styleId;
                style.textContent = [
                    'html[data-mantou-locked="true"],html[data-mantou-locked="true"] body{margin:0!important;min-height:100vh!important;background:#f7f8fa!important;color:#141414!important;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif!important;}',
                    'html[data-mantou-locked="true"] body>*:not(#mantou-open-gate){display:none!important;}',
                    '#mantou-open-gate{box-sizing:border-box!important;display:flex!important;min-height:100vh!important;align-items:center!important;justify-content:center!important;padding:28px!important;text-align:center!important;background:#f7f8fa!important;}',
                    '#mantou-open-gate .mantou-open-card{box-sizing:border-box!important;width:min(100%,360px)!important;border:1px solid #e2e6ea!important;border-radius:18px!important;background:#fff!important;padding:28px 22px!important;box-shadow:0 16px 40px rgba(20,28,38,.12)!important;}',
                    '#mantou-open-gate .mantou-open-title{margin:0 0 10px!important;font-size:22px!important;line-height:1.25!important;font-weight:800!important;color:#111827!important;}',
                    '#mantou-open-gate .mantou-open-text{margin:0!important;font-size:15px!important;line-height:1.7!important;color:#5b6472!important;}'
                ].join("");
                (document.head || document.documentElement).appendChild(style);
            }

            function renderGate() {
                if (rendering) return;
                rendering = true;
                document.documentElement.setAttribute("data-mantou-locked", "true");
                installStyle();
                if (!document.body) {
                    rendering = false;
                    return;
                }
                var gate = document.getElementById(lockId);
                if (!gate) {
                    gate = document.createElement("main");
                    gate.id = lockId;
                }
                if (document.body.children.length !== 1 || document.body.firstElementChild !== gate) {
                    document.body.innerHTML = "";
                    document.body.appendChild(gate);
                }
                if (gate.innerHTML !== gateMarkup) {
                    gate.innerHTML = gateMarkup;
                }
                rendering = false;
            }

            installStyle();
            renderGate();
            if (document.readyState === "loading") {
                document.addEventListener("DOMContentLoaded", renderGate, { once: true });
            }
            if (window.MutationObserver) {
                new MutationObserver(renderGate).observe(document.documentElement, {
                    childList: true,
                    subtree: true
                });
            }
            window.setInterval(renderGate, 500);
        })();
        </script>
        $WEB_APP_RUNTIME_GUARD_END
    """.trimIndent()
}
