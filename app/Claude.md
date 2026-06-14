
---

## 六、写代码时的硬规则（Claude 必须遵守）

### 6.1 Tool 开发规则

> 概念说明：这里的 "Tool" 不是 LLM agent 的 "tool calling"，而是馒头 App
> 自定义的「Android 系统能力桥」。开发者按统一接口包装一个 Android 能力
> （闹钟、日历、定位…），生成的网页在 WebView 里通过
> `window.MantouApp.<toolName>.<method>(...)` 调用真实的 Android API，
> 让 LLM 生成的网页不只是空壳，而能驱动系统功能。

1. **只能用基本类型参数**：`String`, `Int`, `Long`, `Boolean`, `Double`。JS → Java 桥不支持复杂对象。
2. **必须返回 JSON 字符串或 void**：统一 schema `{"success": bool, "data": any, "error": string|null}`。
3. **必须有 @MantouTool 类注解**：name 必须唯一，是 JS 方法名的前缀（如 `alarm` → `alarmSet`）。
4. **必须有 @ToolMethod 和 @ToolReturns 方法注解**：生成 API 文档时用。
5. **参数必须有 @ToolParam 注解**：指定 name（Kotlin 反射运行时拿不到参数名）。
6. **必须在 ToolRegistry.toolClasses 列表手动注册**：加一行 `AlarmTool::class`。
7. **不做耗时操作**：JSBridge 在主线程调用，需要耗时请内部起协程/Handler。
8. **权限敏感操作必须先检查**：没有权限时返回 error JSON，网页侧提示用户去设置。

### 6.2 LLM Prompt 规则

1. **路由层只做路由**：不输出业务内容，只输出 JSON。
2. **web_app 分支必须注入 tool API 文档**：LLM 必须知道能调什么（文档由 `ToolRegistry.generateMarkdownDoc()` 生成）。
3. **生成 HTML 时必须遵守 MantouWebViewRuntime 的门禁**：HTML 里检查 `window.MantouApp.isMantouApp()`，不在馒头 App 中打开时展示引导页（已有的 runtime guard 机制保留）。

### 6.3 文件系统规则

1. **Agent 写文件仅限 workspace 白名单目录**：`/generated_apps/`, `/agent/tools/`, `/agent/store/`。
2. **file_edit Tool 修改文件前必须先读**：让用户能看到 diff 再确认。
3. **HTML 文件由 AppGenerator 写入 /generated_apps/**：文件名格式 `<stem>_<timestamp>.html`。

---

## 七、当前已存在的代码资产（Claude 改代码前请读这些文件）

| 文件 | 作用 | 改动策略 |
|------|------|---------|
| [ChatViewModel.kt](file:///d:/AndroidProject/ManTou/app/src/main/java/com/hfad/mantou/viewmodel/ChatViewModel.kt) | 主控制流 | **改**: 用 AgentOrchestrator 替换手动 if-else |
| [AgentWorkspace.kt](file:///d:/AndroidProject/ManTou/app/src/main/java/com/hfad/mantou/utils/AgentWorkspace.kt) | 文件系统 + prompt 构造 | **扩展**: 增加 tools 目录扫描 |
| [AppGenerator.kt](file:///d:/AndroidProject/ManTou/app/src/main/java/com/hfad/mantou/utils/AppGenerator.kt) | Web App 生成器 | **扩展**: prompt 注入 tool API 文档 |
| [AppIntentDetector.kt](file:///d:/AndroidProject/ManTou/app/src/main/java/com/hfad/mantou/utils/AppIntentDetector.kt) | 旧二元意图识别 | **降级**: 保留关键词兜底，不再做主路由 |
| [MantouWebViewRuntime.kt](file:///d:/AndroidProject/ManTou/app/src/main/java/com/hfad/mantou/utils/MantouWebViewRuntime.kt) | WebView JSBridge | **重构**: 注入 JSBridgeManager |
| [StreamingApiService.kt](file:///d:/AndroidProject/ManTou/app/src/main/java/com/hfad/mantou/data/api/StreamingApiService.kt) | 流式 LLM 调用 | **不动** |

---

## 八、开发新 Tool 的标准流程（给 Claude 照抄的 checklist）

当我说"帮我加一个 XX Tool"时，请按以下步骤:

1. **新建文件**: `app/src/main/java/com/hfad/mantou/tool/impl/XxxTool.kt`
2. **类上写** `@MantouTool(name = "xxx", description = "...", usageScenario = "...")`
3. **继承** `BaseTool(context)`
4. **每个公开方法上写** `@JavascriptInterface + @ToolMethod + @ToolReturns`
5. **每个参数上写** `@ToolParam(name = "...", description = "...")`
6. **方法签名**: 参数用基本类型，返回 `String`（JSON）或 `void`
7. **返回值 schema**: `{"success": bool, "data": any, "error": string|null}`
8. **在 ToolRegistry.toolClasses 列表里加一行**: `XxxTool::class`
9. **测试**: 手动构造一个调用该 Tool 的 HTML，确认 JS → Kotlin 通了

---

## 九、新增/修改文件清单（实现完整架构时的对照）

按优先级排序:

| # | 文件 | 状态 | 说明 |
|---|------|------|------|
| 1 | `tool/MantouTool.kt` | ✅ 已创建 | 注解定义（MantouTool/ToolMethod/ToolParam/ToolReturns） |
| 2 | `tool/BaseTool.kt` | ✅ 已创建 | Tool 基类，提供 success/error JSON 助手 |
| 3 | `tool/ToolRegistry.kt` | ✅ 已创建 | 注册表 + 生成 LLM 用的 md 文档 |
| 4 | `tool/impl/AlarmTool.kt` | ✅ 已创建 | 系统闹钟/倒计时器示例 |
| 5 | `tool/impl/CalendarTool.kt` | ✅ 已创建 | 系统日历事件示例 |
| 6 | `tool/impl/ToastTool.kt` | ✅ 已创建 | Toast 桥连通性示例 |
| 7 | `tool/JSBridgeManager.kt` | ❌ 已不再需要 | 改由 `MantouWebViewRuntime.install` 直接把 `ToolRegistry.instances()` 逐个 `addJavascriptInterface` 为 `MantouApp_<toolName>` |
| 7.1 | `app/build.gradle.kts` 的 `generateToolsDoc` 任务 | ✅ 已创建 | 编译期扫描 `tool/impl/*.kt`，把 MD 文档写到 `src/main/assets/mantou_tools.md`，挂到 `preBuild` |
| 7.2 | `src/main/assets/mantou_tools.md` | ✅ 自动生成 | LLM 用的 Android 能力文档；运行时由 AppGenerator 拼进 system prompt |
| 8 | `orchestrator/AgentOrchestrator.kt` | 待创建 | LLM JSON 路由 + Tool 调度 |
| 9 | `orchestrator/RouteDecision.kt` | 待创建 | 路由决策数据类 |
| 10 | `storage/AgentJsonStore.kt` | 待创建 | JSON 文件存储 |
| 11 | `utils/MantouWebViewRuntime.kt` | ✅ 已重构 | `install()` 注册主桥 + 把每个 Tool 注册为 `MantouApp_<toolName>`；主桥含 `getToolNames()` 供 JS 别名表使用 |
| 12 | `utils/AppGenerator.kt` | ✅ 已扩展 | `buildSystemPrompt` 拼接 `assets/mantou_tools.md`；`WEB_APP_RUNTIME_GUARD` 增加 JS 别名表逻辑，把 `MantouApp_<name>` 挂到 `MantouApp.<name>` |
| 13 | `utils/AgentWorkspace.kt` | 待扩展 | tools 目录落盘 |
| 14 | `viewmodel/ChatViewModel.kt` | 待修改 | 接入 AgentOrchestrator |
| 15 | `utils/AppIntentDetector.kt` | 降级 | 保留关键词兜底 |

---

> 文档结束。以上为本项目的架构总览，新增/修改代码请遵守本文档的分层与命名约定。