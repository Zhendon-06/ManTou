package com.hfad.mantou.utils

import android.content.Context
import android.content.res.Configuration
import com.hfad.mantou.tool.generated.GeneratedMantouToolsDoc
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object AppGenerator {

    private const val BASE_SYSTEM_PROMPT = """你是一名资深移动端产品设计师和前端工程师。根据用户描述，交付一个完整、精致、可直接运行的单文件 HTML 应用，而不是页面草稿或组件演示。

严格要求：
1. 必须生成一个完整的、自包含的HTML文件，所有CSS和JavaScript都内联在HTML中
2. 必须先完成信息架构再实现界面：明确主任务、内容区、主要操作、空状态和必要的导航，不要把控件直接堆在页面左上角
3. 必须建立完整视觉系统：在 :root 定义颜色、字号、圆角、阴影和间距变量；使用 CSS reset；至少设计 6 个有明确职责的组件/区域样式
4. 所有 button、input、textarea、select 都必须覆盖浏览器默认外观，并实现合适的尺寸、圆角、字体、间距，以及 focus、active 或 disabled 等状态
5. 必须使用移动端 App 风格的响应式布局，包含清晰的顶部区域、可滚动内容区和稳定可用的关键操作区域；只有确有需要时才使用底部导航
6. 使用现代且克制的配色、层级、留白、卡片、圆角和阴影；触控目标不小于 44px；不能使用未排版的默认控件或大面积无意义留白
7. 所有用户可见操作必须由 JavaScript 完整实现，包括新增、编辑、删除、筛选、搜索、切换、关闭等适用交互；必须提供空状态和即时反馈，不能有占位、TODO、伪代码或空函数
8. 不能依赖 CDN、网络字体、外部 CSS、外部 JavaScript 或远程图片；离线打开也必须完整可用
9. 输出前在内部检查 HTML、CSS 和 JavaScript 是否闭合，控件是否已美化，主要操作是否能从初始状态完成；不要输出检查过程
10. 只返回HTML代码，不要有任何解释说明文字；代码必须以<!DOCTYPE html>开头，以</html>结尾
11. 应用命名必须使用“馒头xxx”的形式，其中 xxx 是应用本身的自然名称，例如番茄钟叫“馒头番茄钟”，记事本叫“馒头记事本”；HTML 的 title 和主标题应优先使用这个名称。"""

    const val APP_GEN_MAX_TOKENS = 256000
    const val APP_GEN_MAX_OUTPUT_TOKENS = 128000
    const val APP_DIFF_MAX_OUTPUT_TOKENS = 32_000
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
    private val STYLE_BLOCK_REGEX = Regex("<style\\b[^>]*>([\\s\\S]*?)</style\\s*>", RegexOption.IGNORE_CASE)
    private val CSS_RULE_REGEX = Regex("[^{}]+\\{[^{}]+\\}")
    private val SCRIPT_BLOCK_REGEX = Regex("<script\\b[^>]*>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE)
    private val RAW_TEXT_BLOCK_REGEX = Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val HTML_COMMENT_REGEX = Regex("<!--[\\s\\S]*?-->")
    private val HTML_TAG_TOKEN_REGEX = Regex("<\\s*(/?)\\s*([a-z][a-z0-9:-]*)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SUSPICIOUS_ATTRIBUTE_TEXT_REGEX = Regex(
        ">\\s*=[\"'][^<>\\n]*(?:\\bid|\\bclass|\\btype)\\s*=",
        RegexOption.IGNORE_CASE
    )
    private val INTERACTIVE_ELEMENT_REGEX = Regex("<(?:button|input|textarea|select)\\b", RegexOption.IGNORE_CASE)
    private val EVENT_BINDING_REGEX = Regex(
        "(?:addEventListener\\s*\\(|on(?:click|input|change|submit|keydown|pointerdown|touchstart)\\s*=)",
        RegexOption.IGNORE_CASE
    )
    private val BALANCED_HTML_TAGS = setOf(
        "article", "button", "canvas", "dialog", "div", "footer", "form", "header",
        "li", "main", "nav", "ol", "p", "section", "select", "textarea", "ul"
    )

    fun buildSystemPrompt(context: Context): String {
        val metrics = context.resources.displayMetrics
        val widthPx = metrics.widthPixels
        val heightPx = metrics.heightPixels
        val orientation = when (context.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "unknown"
        }

        val basePrompt = """
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

            持久化数据要求：
            1. 每个生成的网页 App 都会伴随一个同名 JSON 数据文件，数据必须写入这个 JSON 文件，不能只存在内存变量里。
            2. 如果 App 有待办、笔记、记录、设置、历史、分数、进度等需要下次打开仍保留的数据，必须使用 `window.MantouApp.storage`。
            3. 读取：`var r = JSON.parse(window.MantouApp.storage.storageRead()); var state = r.success ? JSON.parse(r.data.content || "{}") : {};`
            4. 写入：`window.MantouApp.storage.storageWrite(JSON.stringify(state));`
            5. `storageWrite` 的参数必须是合法 JSON 字符串，建议把整个应用状态组织成一个对象后整体写入。
        """.trimIndent()

        return basePrompt + buildToolsSection()
    }

    fun buildModificationSystemPrompt(
        context: Context,
        relativePath: String,
        expectedSha256: String,
        includeTools: Boolean = false
    ): String {
        val toolsRequirement = if (includeTools) {
            "11. 用户要求新增或修改 Android 系统能力，必须遵守下方 Tools 的调用约定。"
        } else {
            "11. 本次不新增 Android 系统能力；保留现有 MantouApp bridge 调用，不要改写其接口。"
        }
        val basePrompt = """
            你正在增量修改一个已经存在、可直接运行的自包含 HTML 网页应用。

            严格要求：
            1. 只返回 unified diff，不要返回完整 HTML，不要解释，不要使用 Markdown 标题。
            2. diff 只能修改一个已有文件，不能创建、删除、重命名或复制文件。
            3. 文件头必须严格使用：
               --- a/$relativePath
               +++ b/$relativePath
            4. 文件头之后必须立即输出至少一个 `@@ -oldStart,oldCount +newStart,newCount @@` hunk 头；禁止直接输出 CSS、HTML 或 JavaScript 源码。
            5. hunk 内每一行都必须有 unified diff 前缀：上下文行前缀为空格，删除行前缀为 `-`，新增行前缀为 `+`。原始 CSS 变量行若以 `--font-xs` 开头，上下文必须写成 ` --font-xs`，删除必须写成 `---font-xs`，新增必须写成 `+--font-xs`，绝不能原样输出无前缀的 `--font-xs`。
            6. 每个 hunk 必须包含准确的上下文行，只修改完成用户需求所必需的最小范围；hunk 头的 oldCount 必须等于“上下文行 + 删除行”数量，newCount 必须等于“上下文行 + 新增行”数量，多段 hunk 分别计算。
            7. 当前文件 SHA-256 为 $expectedSha256；不要假设文件中存在未展示的代码。
            8. 必须保留 mantou-webapp-id meta、mantou-webapp-runtime-guard 区块和现有持久化数据兼容性。
            9. 修改后仍须是完整、合法、可直接运行的 HTML，所有 CSS 和 JavaScript 保持内联。
            10. 新增交互必须完整实现，不能留下占位、伪代码或空函数。
            $toolsRequirement

            合法输出示例：
            --- a/$relativePath
            +++ b/$relativePath
            @@ -10,3 +10,3 @@
             <header>
            -  <h1>旧标题</h1>
            +  <h1>新标题</h1>
             </header>
        """.trimIndent()

        return basePrompt + if (includeTools) buildToolsSection() else ""
    }

    internal fun modificationNeedsTools(userMessage: String): Boolean {
        val normalized = userMessage.lowercase(Locale.US)
        return DIRECT_MODIFICATION_TOOL_KEYWORDS.any(normalized::contains) ||
                (AMBIGUOUS_MODIFICATION_TOOL_KEYWORDS.any(normalized::contains) &&
                        MODIFICATION_TOOL_ACTIONS.any(normalized::contains))
    }

    fun buildModificationUserPrompt(
        userMessage: String,
        snapshot: LocalDiffFileTool.FileSnapshot,
        previousFailure: String? = null
    ): String {
        val retrySection = previousFailure?.let {
            """
                上一次补丁未通过本地解析或校验：
                $it
                请丢弃上一份补丁，重新计算行号并输出一份全新的、完整的 unified diff。

            """.trimIndent()
        }.orEmpty()
        return """
            用户本轮修改要求：
            $userMessage

            当前文件：${snapshot.relativePath}
            当前 SHA-256：${snapshot.sha256}

            $retrySection
            补丁格式提醒：文件头后必须先出现 `@@` hunk 头；hunk 中每行必须以空格、`-` 或 `+` 开头；每段 hunk 头的 oldCount/newCount 必须与该段正文的实际旧行数/新行数完全一致。
            CSS 自定义变量原文 `--font-xs: 12px;` 作为上下文行时必须输出为 ` --font-xs: 12px;`，删除时为 `---font-xs: 12px;`，新增时为 `+--font-xs: 12px;`。

            当前完整文件内容如下。请只输出针对它的 unified diff：
            <current_file>
            ${snapshot.content}
            </current_file>
        """.trimIndent()
    }

    fun buildQualityRetryUserPrompt(userMessage: String, qualityIssues: List<String>): String {
        return """
            原始需求：
            $userMessage

            上一次结果未通过应用质量检查：
            ${qualityIssues.joinToString("\n") { "- $it" }}

            请从头重新生成完整 HTML，不要输出 diff，不要解释。必须补齐视觉样式和真实交互，不能复用上一次的半成品结构。
        """.trimIndent()
    }

    internal fun resolveAppGenerationOutputLimit(contextTokenLimit: Int): Int {
        return contextTokenLimit.coerceIn(1, APP_GEN_MAX_OUTPUT_TOKENS)
    }

    internal fun resolveAppDiffOutputLimit(contextTokenLimit: Int): Int {
        return contextTokenLimit.coerceIn(1, APP_DIFF_MAX_OUTPUT_TOKENS)
    }

    private fun buildToolsSection(): String {
        val doc = GeneratedMantouToolsDoc.markdown.takeIf { it.isNotBlank() } ?: return ""

        return "\n\n" + """
            ---

            # Android 系统能力 (Tools)

            当用户需求涉及"调用安卓系统功能"（闹钟、日历、Toast、跳转系统设置 等）时，
            生成的 HTML 必须使用下面声明的 Tools 桥接调用真实 Android API，
            **不要**只写一个纯前端模拟。

            统一调用步骤：
            1. 入口先判断：`if (window.MantouApp && window.MantouApp.isMantouApp && window.MantouApp.isMantouApp()) { ... }`
            2. 调用：`var raw = window.MantouApp.<toolName>.<methodName>(...args); var r = JSON.parse(raw);`
            3. 判断：`if (r.success) { 用 r.data } else { 提示 r.error }`
            4. 不在馒头 App 中时给降级方案（如 alert / 纯前端模拟）。

            # 持久化存储 (Storage)

            需要永久保存的数据必须使用 `window.MantouApp.storage`；可以把 localStorage 作为浏览器外的降级方案，但在馒头 App 内优先写 JSON 文件。完整方法签名见下方 KSP 自动生成文档。

            # 相机拍照结果回显

            如果网页 App 需要拍照并把照片显示在 HTML 页面中，必须使用异步回调：
            1. 先定义回调：`window.MantouApp.onCameraPhoto = function(dataUrl, uri) { document.querySelector("img").src = dataUrl; };`
            2. 再调用：`window.MantouApp.camera.cameraTakePhoto();`
            3. `dataUrl` 是 `data:image/jpeg;base64,...`，可直接赋给 `<img>` 的 `src`，也可以写入 storage 做持久化。
            4. 也可以调用 `cameraTakePhotoWithCallback("window.handlePhoto")` 指定自己的全局回调函数。

            $doc
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

    fun extractUnifiedDiff(content: String): String? {
        val trimmed = content.trim()
        val fenced = Regex(
            "```(?:diff|patch)\\s*\\n([\\s\\S]*?)\\n```",
            RegexOption.IGNORE_CASE
        ).find(trimmed)?.groupValues?.getOrNull(1)?.trim()
        if (!fenced.isNullOrEmpty()) return fenced

        return trimmed.takeIf {
            it.startsWith("diff --git ") || it.startsWith("--- ")
        }
    }

    fun generatedWebAppQualityIssues(htmlContent: String): List<String> {
        val issues = mutableListOf<String>()
        val html = extractHtml(htmlContent)
        if (html == null || !html.trimEnd().endsWith("</html>", ignoreCase = true)) {
            return listOf("HTML 文档不完整")
        }

        val structuralHtml = HTML_COMMENT_REGEX.replace(RAW_TEXT_BLOCK_REGEX.replace(html, ""), "")
        val unbalancedTags = findUnbalancedHtmlTags(structuralHtml)
        if (unbalancedTags.isNotEmpty()) {
            issues += "HTML 标签未正确闭合：${unbalancedTags.joinToString("、")}"
        }
        if (SUSPICIOUS_ATTRIBUTE_TEXT_REGEX.containsMatchIn(structuralHtml)) {
            issues += "HTML 中存在缺失标签名的属性片段"
        }

        val styleContent = STYLE_BLOCK_REGEX.findAll(html)
            .joinToString("\n") { it.groupValues[1] }
            .trim()
        val styleRuleCount = CSS_RULE_REGEX.findAll(styleContent).count()
        if (styleContent.length < 500 || styleRuleCount < 6) {
            issues += "CSS 过少，未形成完整的移动端视觉系统"
        }

        val scriptContent = SCRIPT_BLOCK_REGEX.findAll(html)
            .joinToString("\n") { it.groupValues[1] }
            .trim()
        if (scriptContent.length < 250) {
            issues += "JavaScript 过少，主要交互可能没有完整实现"
        }
        if (!INTERACTIVE_ELEMENT_REGEX.containsMatchIn(html)) {
            issues += "页面缺少可执行主要任务的交互控件"
        }
        if (!EVENT_BINDING_REGEX.containsMatchIn(html)) {
            issues += "页面没有绑定用户交互事件"
        }
        if (Regex("\\b(?:TODO|FIXME|placeholder implementation)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(html)
        ) {
            issues += "代码中仍包含占位实现"
        }
        return issues
    }

    private fun findUnbalancedHtmlTags(structuralHtml: String): List<String> {
        val balances = mutableMapOf<String, Int>()
        HTML_TAG_TOKEN_REGEX.findAll(structuralHtml).forEach { match ->
            val tagName = match.groupValues[2].lowercase(Locale.US)
            if (tagName !in BALANCED_HTML_TAGS) return@forEach
            val delta = if (match.groupValues[1].isEmpty()) 1 else -1
            balances[tagName] = balances.getOrDefault(tagName, 0) + delta
        }
        return balances.filterValues { it != 0 }.keys.sorted()
    }

    fun validateGeneratedWebApp(htmlContent: String) {
        val issues = generatedWebAppQualityIssues(htmlContent)
        require(issues.isEmpty()) {
            "生成质量检查未通过：${issues.joinToString("；")}"
        }
    }

    fun validatePatchedWebApp(originalContent: String, patchedContent: String) {
        require(extractHtml(patchedContent) != null) {
            "增量修改后的文件不是合法 HTML"
        }
        require(patchedContent.trimEnd().endsWith("</html>", ignoreCase = true)) {
            "增量修改后的 HTML 缺少结束标签"
        }

        val originalIdentity = extractWebAppIdentity(originalContent)
        val patchedIdentity = extractWebAppIdentity(patchedContent)
        require(originalIdentity != null && patchedIdentity == originalIdentity) {
            "增量修改不能删除或替换网页应用标识"
        }
        require(patchedContent.countOccurrences(WEB_APP_RUNTIME_GUARD_START) == 1 &&
                patchedContent.countOccurrences(WEB_APP_RUNTIME_GUARD_END) == 1
        ) {
            "增量修改不能删除或复制馒头运行时保护区块"
        }
        require(extractRuntimeGuard(originalContent) == extractRuntimeGuard(patchedContent)) {
            "增量修改不能更改馒头运行时保护区块"
        }
    }

    private fun extractRuntimeGuard(content: String): String? {
        val start = content.indexOf(WEB_APP_RUNTIME_GUARD_START)
        if (start < 0) return null
        val end = content.indexOf(WEB_APP_RUNTIME_GUARD_END, start)
        if (end < 0) return null
        return content.substring(start, end + WEB_APP_RUNTIME_GUARD_END.length)
    }

    private fun String.countOccurrences(value: String): Int {
        var count = 0
        var fromIndex = 0
        while (true) {
            val index = indexOf(value, fromIndex)
            if (index < 0) return count
            count++
            fromIndex = index + value.length
        }
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
        file.parentFile?.mkdirs()
        file.writeText(ensureWebAppIdentity(htmlContent))
        ensureWebAppDataFile(file)
        return file
    }

    fun dataFileForHtml(htmlFile: File): File {
        val name = htmlFile.name
        val stem = if (htmlFile.extension.isNotBlank()) {
            name.substringBeforeLast('.')
        } else {
            name
        }
        return File(htmlFile.parentFile ?: File("."), "$stem.json")
    }

    fun ensureWebAppDataFile(htmlFile: File): File {
        val dataFile = dataFileForHtml(htmlFile)
        if (!dataFile.exists()) {
            dataFile.parentFile?.mkdirs()
            dataFile.writeText("{}")
        }
        return dataFile
    }

    private fun nextAvailableHtmlFile(appDir: File, userMessage: String): File {
        val stem = inferAppFileStem(userMessage)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val projectName = "${stem}_$timestamp"
        var projectDir = File(appDir, projectName)
        var index = 2
        while (projectDir.exists()) {
            projectDir = File(appDir, "${projectName}_$index")
            index++
        }
        return File(projectDir, "$projectName.html")
    }

    internal fun inferAppFileStem(userMessage: String): String {
        val message = userMessage.trim()
        val lower = message.lowercase(Locale.getDefault())

        APP_TYPE_KEYWORDS.firstOrNull { (keyword, _) ->
            lower.contains(keyword.lowercase(Locale.getDefault()))
        }?.let { (_, name) ->
            return sanitizeFileStem(withMantouPrefix(name))
        }

        val cleaned = COMMON_REQUEST_WORDS.fold(message) { current, word ->
            current.replace(word, "", ignoreCase = true)
        }
            .replace(Regex("[，。！？、,.!?；;：:\\[\\]（）(){}]+"), "")
            .trim()

        return sanitizeFileStem(withMantouPrefix(cleaned.ifBlank { "应用" }))
    }

    private fun withMantouPrefix(appName: String): String {
        val normalized = appName.trim()
        return if (normalized.startsWith(MANTOU_APP_NAME_PREFIX)) {
            normalized
        } else {
            "$MANTOU_APP_NAME_PREFIX$normalized"
        }
    }

    private fun sanitizeFileStem(rawName: String): String {
        val sanitized = rawName
            .replace(Regex("\\s+"), "")
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .trim('_', '-', '.', ' ')
            .take(24)

        return sanitized.ifBlank { "web_app" }
    }

    private const val MANTOU_APP_NAME_PREFIX = "馒头"

    private val APP_TYPE_KEYWORDS = listOf(
        "pomodoro" to "番茄钟",
        "番茄钟" to "番茄钟",
        "番茄" to "番茄钟",
        "notepad" to "记事本",
        "记事本" to "记事本",
        "记事" to "记事本",
        "备忘录" to "备忘录",
        "便签" to "便签",
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

    private val DIRECT_MODIFICATION_TOOL_KEYWORDS = listOf(
        "mantouapp", "android", "安卓", "系统能力", "相机", "拍照", "录像", "定位", "震动",
        "振动", "手电筒", "剪贴板", "拨号", "短信", "系统设置", "toast"
    )

    private val AMBIGUOUS_MODIFICATION_TOOL_KEYWORDS = listOf("闹钟", "日历", "通知", "分享")

    private val MODIFICATION_TOOL_ACTIONS = listOf(
        "调用", "接入", "唤起", "写入系统", "打开系统", "创建事件", "添加事件", "设置闹钟",
        "发送通知", "系统分享"
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
                // 把每个 MantouApp_<toolName> bridge 挂到 window.MantouApp.<toolName> 下，
                // 让生成的网页写 window.MantouApp.toast.toastShow(...) 这种自然形式。
                try {
                    if (window.MantouApp && typeof window.MantouApp.getToolNames === "function") {
                        var raw = window.MantouApp.getToolNames();
                        var names = JSON.parse(raw);
                        for (var i = 0; i < names.length; i++) {
                            var bridge = window["MantouApp_" + names[i]];
                            if (bridge) {
                                window.MantouApp[names[i]] = bridge;
                            }
                        }
                    }
                } catch (error) { /* 别名表搭建失败时，调用方 try/catch 自行兜底 */ }
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
