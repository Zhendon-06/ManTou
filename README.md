# 馒头 AI (ManTou AI)

当前版本：**v2.0.1**

馒头 AI 是一个 Android 智能体应用。它支持多轮聊天、图片对话、模型自定义配置，也可以根据一句话生成可运行的网页 App，并在应用内直接预览、全屏使用和持久化数据。

项目的一个核心设计是 **Tool 系统**：开发者可以把 Android 原生能力封装成统一接口，让生成的网页 App 通过 `window.MantouApp.<toolName>.<methodName>(...)` 调用真实的系统功能，例如闹钟、日历、剪贴板、相机、震动、手电筒等。

## 主要能力

### 智能对话

- 支持多轮上下文对话
- 支持流式输出
- 支持图片对话，可查看聊天中的图片
- 支持历史会话管理、归档和搜索
- 支持多会话同时请求，互不阻塞
- 支持请求中途停止
- 支持消息复制、删除、编辑等长按操作
- 支持编辑用户消息后重新发送
- 支持上下文阈值配置
- 支持语音输入（适配 mimo 等模型）
- 请求失败时由 LLM 给出问题分析与解决方案，而非原始报错
- 详细请求日志查看页，便于排查接口问题

### 一句话生成网页 App

- 用户直接描述想法，例如“帮我做一个番茄钟”
- 馒头会自动判断这是普通聊天还是 App 生成需求
- 生成的网页 App 会保存在本地 workspace
- 支持聊天内预览和全屏打开
- 支持分享生成的 HTML；非馒头环境打开时会提示使用馒头 App
- 每个网页 App 自动绑定一个同名 JSON 数据文件，用于持久化待办、笔记、设置、进度等数据
- `generated_apps` 目录按项目分组：一个项目一个二级目录，HTML 和关联 JSON 放在同一个目录中

示例结构：

```text
generated_apps/
  todo_20260615_120000/
    todo_20260615_120000.html
    todo_20260615_120000.json
```

### Workspace 文件与记忆

- 内置 workspace 文件树，展示 `generated_apps`、`agent`、`memory`
- 支持打开生成的 HTML App
- 支持 JSON 富文本查看器，方便查看网页 App 的状态数据
- 支持编辑文本和 Markdown 文件
- 支持 Workspace 记忆设置页，可编辑：
  - `SOUL.md`：Agent 灵魂/身份
  - `CHAT.md`：纯聊天系统提示词
  - `MEMORY.md`：长期记忆

### 虚拟桌面

- 提供独立的"桌面"入口，集中展示所有已生成的网页 App
- 点击图标即可全屏运行对应 App，类似系统应用抽屉

### 设置与外观

- 侧边栏设置按钮进入统一设置页
- 模型配置、外观设置、Workspace 记忆设置集中管理
- 外观设置支持选择聊天和 Workspace 背景壁纸，并可恢复默认背景
- 文字颜色根据壁纸亮度自动反色，保证可读性

### 模型配置

- 不内置默认模型，需要用户自行配置 Provider 和模型
- 支持 OpenAI 兼容接口
- 支持 Anthropic 接口
- 支持从 Provider 拉取模型列表
- 支持切换当前使用的 Provider 和模型
- Base URL 可填写根地址、`/v1`，或完整的模型/聊天端点，应用会自动处理路径

## 使用方式

1. 启动应用后，打开侧边栏，进入设置页。
2. 打开“模型配置”，新增 Provider。
3. 填写名称、Base URL、API Key 和接口格式。
4. 点击拉取模型列表，选择要使用的模型。
5. 回到聊天页，直接发送消息、图片，或描述想生成的网页 App。

## Base URL 示例

以下写法都可以：

```text
https://api.example.com
https://api.example.com/v1
https://api.example.com/v1/models
https://api.example.com/v1/chat/completions
https://api.example.com/v1/messages
```

馒头会根据当前接口格式自动补齐或替换请求路径，避免出现重复 `/v1/v1/...` 的问题。

## 支持的接口格式

| 格式 | 用途 | 认证 |
| --- | --- | --- |
| OpenAI | OpenAI 兼容聊天、生成 App、拉取模型 | `Authorization: Bearer <API Key>` |
| Anthropic | Claude / Anthropic 兼容聊天、生成 App、拉取模型 | `x-api-key: <API Key>` |

API Key 可以留空，适用于本地模型代理或无鉴权服务。

## Web App 运行时桥

生成的网页 App 在馒头 WebView 中运行时，可以访问：

```js
window.MantouApp.isMantouApp()
window.MantouApp.<toolName>.<methodName>(...args)
window.MantouApp.storage.storageRead()
window.MantouApp.storage.storageWrite(jsonContent)
```

调用前建议先判断运行环境：

```js
if (window.MantouApp && window.MantouApp.isMantouApp && window.MantouApp.isMantouApp()) {
  var raw = window.MantouApp.toast.toastShort("保存成功");
  var result = JSON.parse(raw);
  if (!result.success) {
    console.log(result.error);
  }
}
```

### Storage 持久化

每个生成的 HTML App 都会绑定一个同名 JSON 文件。网页可以通过 `window.MantouApp.storage` 读写它。

常用方法：

| 方法 | 说明 |
| --- | --- |
| `storageRead()` | 读取完整 JSON 文件内容 |
| `storageWrite(jsonContent)` | 写入完整 JSON 内容 |
| `storageGet(key)` | 从根对象读取字段 |
| `storageSet(key, valueJson)` | 写入根对象字段 |
| `storageRemove(key)` | 删除根对象字段 |
| `storageClear()` | 清空为 `{}` |

示例：

```js
function loadState() {
  var raw = window.MantouApp.storage.storageRead();
  var result = JSON.parse(raw);
  return result.success ? JSON.parse(result.data.content || "{}") : {};
}

function saveState(state) {
  return JSON.parse(
    window.MantouApp.storage.storageWrite(JSON.stringify(state))
  );
}
```

## Tool 开发指南

Tool 是馒头提供给生成网页 App 的 Android 原生能力桥。它不是远端 LLM function calling，而是 Android `WebView.addJavascriptInterface` 暴露给本地 HTML 的同步接口。

典型调用路径：

```text
HTML/JS
  -> window.MantouApp.<toolName>.<methodName>(...args)
  -> WebView JavaScriptInterface
  -> Kotlin Tool
  -> Android 原生 API / Intent / System Service
```

### 目录结构

Tool 相关代码位于：

```text
app/src/main/java/com/hfad/mantou/tool/
  BaseTool.kt
  MantouTool.kt
  ToolRegistry.kt
  impl/
    AlarmTool.kt
    CalendarTool.kt
    CameraTool.kt
    ClipboardTool.kt
    FlashlightTool.kt
    ToastTool.kt
    VibrationTool.kt
```

当前内置 Tool：

| Tool | 能力 |
| --- | --- |
| `alarm` | 打开系统闹钟、设置单次/重复闹钟、启动倒计时 |
| `calendar` | 打开系统日历、预填日程和提醒 |
| `camera` | 打开系统相机、拍照、录像，并可将照片回调到网页 App 显示 |
| `clipboard` | 读取、写入、清空系统剪贴板 |
| `flashlight` | 打开、关闭、切换手电筒 |
| `toast` | 弹出 Android 原生 Toast 提示 |
| `vibration` | 单次振动、模式振动、停止振动 |
| `storage` | 当前网页 App 专属 JSON 文件读写，运行时注入 |

完整方法列表会自动生成到 `app/src/main/assets/mantou_tools.md`，可以把它当作 Tool API 参考文档。

### 开发约定

新增 Tool 时必须遵守：

- Tool 类放在 `app/src/main/java/com/hfad/mantou/tool/impl/`
- Tool 类继承 `BaseTool`
- Tool 类添加 `@MantouTool`
- 构造函数必须是 `class XxxTool(context: Context) : BaseTool(context)`
- 暴露给 JS 的方法必须返回 `String`
- 暴露给 JS 的方法必须添加：
  - `@JavascriptInterface`
  - `@ToolMethod`
  - `@ToolReturns`
- 每个参数必须添加 `@ToolParam`
- 参数只使用 JSBridge 支持的基本类型：
  - `String`
  - `Int`
  - `Long`
  - `Boolean`
  - `Double`
- 不要把复杂对象、数组、函数直接作为参数；需要复杂数据时传 JSON 字符串
- 返回值统一使用 JSON 字符串：

```json
{"success": true, "data": {}, "error": null}
```

```json
{"success": false, "data": null, "error": "错误信息"}
```

`BaseTool` 已提供两个辅助方法：

```kotlin
success("key" to value)
error("错误信息")
```

### 注解说明

#### `@MantouTool`

标注一个 Tool 的名称和用途。

```kotlin
@MantouTool(
    name = "toast",
    description = "弹一个原生 Toast 提示，用于轻量级即时反馈",
    usageScenario = "网页里给用户操作完成 / 失败 / 复制成功等即时反馈"
)
class ToastTool(context: Context) : BaseTool(context)
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `name` | JS 调用时的工具名，例如 `window.MantouApp.toast` |
| `description` | Tool 能力描述 |
| `usageScenario` | 适合使用该 Tool 的场景，给 LLM 判断何时调用 |

#### `@ToolMethod`

描述一个可被 JS 调用的方法。

```kotlin
@ToolMethod(
    description = "弹一个短 Toast",
    example = "window.MantouApp.toast.toastShort('已复制');"
)
```

#### `@ToolParam`

描述方法参数。Kotlin 运行时不一定能稳定保留参数名，因此每个参数都要写。

```kotlin
@ToolParam(name = "message", description = "提示文本")
```

#### `@ToolReturns`

描述返回值结构，帮助 LLM 生成正确的解析代码。

```kotlin
@ToolReturns(
    description = "是否成功调度 Toast",
    jsonExample = "{\"success\": true, \"data\": {\"shown\": true}, \"error\": null}"
)
```

### 最小 Tool 示例

```kotlin
package com.hfad.mantou.tool.impl

import android.content.Context
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.hfad.mantou.tool.BaseTool
import com.hfad.mantou.tool.MantouTool
import com.hfad.mantou.tool.ToolMethod
import com.hfad.mantou.tool.ToolParam
import com.hfad.mantou.tool.ToolReturns

@MantouTool(
    name = "toast",
    description = "弹一个原生 Toast 提示",
    usageScenario = "网页需要给用户轻量反馈时"
)
class ToastTool(context: Context) : BaseTool(context) {

    @JavascriptInterface
    @ToolMethod(
        description = "弹一个短 Toast",
        example = "window.MantouApp.toast.toastShort('已保存');"
    )
    @ToolReturns(
        description = "是否成功调度 Toast",
        jsonExample = "{\"success\": true, \"data\": {\"shown\": true}, \"error\": null}"
    )
    fun toastShort(
        @ToolParam(name = "message", description = "提示文本") message: String
    ): String {
        if (message.isBlank()) return error("message 不能为空")
        runOnMain {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        return success("shown" to true)
    }
}
```

> 注意：编译期文档生成任务会扫描源码注解。为了让文档稳定生成，建议保持注解顺序为 `@JavascriptInterface`、`@ToolMethod`、`@ToolReturns`、`fun ...`。

### 注册 Tool

新增 Tool 后，在 `ToolRegistry.kt` 的 `toolClasses` 中注册：

```kotlin
private val toolClasses: List<KClass<out BaseTool>> = listOf(
    com.hfad.mantou.tool.impl.AlarmTool::class,
    com.hfad.mantou.tool.impl.CalendarTool::class,
    com.hfad.mantou.tool.impl.ToastTool::class,
    com.hfad.mantou.tool.impl.YourTool::class,
)
```

运行时 `MantouWebViewRuntime.install(...)` 会读取 `ToolRegistry.instances()`，并把每个 Tool 注入 WebView：

```text
MantouApp_<toolName> -> window.MantouApp.<toolName>
```

最终 JS 侧调用形式：

```js
var raw = window.MantouApp.toast.toastShort("hello");
var result = JSON.parse(raw);
```

### 权限与系统能力

如果 Tool 需要 Android 权限，请在 `AndroidManifest.xml` 中声明，并在 Tool 内自行检查权限状态。

建议：

- 无权限时返回 `error("缺少 xxx 权限")`
- 需要跳系统页面时优先使用 Intent，并通过 `BaseTool.launchIntent(...)` 包装
- UI、Toast、Clipboard、Vibrator 等主线程敏感 API 使用 `runOnMain { ... }`
- 耗时任务不要阻塞 JSBridge 调用线程

### Tool 文档生成

项目在 `app/build.gradle.kts` 中定义了 `generateToolsDoc` 任务：

```text
扫描 app/src/main/java/com/hfad/mantou/tool/impl/
生成 app/src/main/assets/mantou_tools.md
```

`preBuild` 会依赖该任务，因此正常构建 APK 时会自动刷新文档。

这份文档会被 `AppGenerator` 注入到网页 App 生成 prompt 中，LLM 才知道当前 App 有哪些 Tool、怎么调用、返回什么结构。

如果你只想刷新 Tool 文档，可以运行：

```bash
./gradlew :app:generateToolsDoc
```

Windows：

```powershell
.\gradlew.bat :app:generateToolsDoc
```

### Tool 设计建议

- 方法名建议带 Tool 前缀，避免语义过短，例如 `clipboardRead`、`alarmSet`
- 参数要少而明确，复杂结构传 JSON 字符串
- 返回 `data` 尽量稳定，方便网页和 LLM 依赖
- 错误信息写给用户和 LLM 都能理解
- 需要用户确认的系统操作，优先跳系统页面而不是静默执行
- 对平台版本差异进行兼容，例如 Android 版本不支持时返回明确错误

## 开发运行

- 语言：Kotlin
- 平台：Android
- 最低 SDK：26
- 目标 SDK：36
- 构建：Gradle / Android Gradle Plugin

使用 Android Studio 打开项目，等待 Gradle Sync 完成后运行即可。

## 功能清单

- [x] 自定义 Provider / 模型配置
- [x] OpenAI / Anthropic 双接口格式
- [x] 模型列表自动拉取
- [x] 当前模型动态切换
- [x] 多轮聊天
- [x] 流式输出
- [x] 图片对话
- [x] 会话管理、归档、搜索
- [x] 多会话同时请求
- [x] 请求中途停止
- [x] 消息长按操作（复制、删除、编辑重发）
- [x] 聊天图片查看
- [x] 语音输入
- [x] 详细请求日志
- [x] 智能错误分析与建议
- [x] 上下文阈值设置
- [x] 一句话生成网页 App
- [x] 生成 App 进度提示
- [x] 聊天内 WebView 预览
- [x] 全屏 WebView 使用
- [x] Web App 分享保护
- [x] Web App JSON 持久化
- [x] JSON 富文本查看器
- [x] `generated_apps` 项目化目录结构
- [x] Workspace 文件结构页
- [x] Workspace 记忆编辑
- [x] 设置总页
- [x] 背景壁纸设置
- [x] 壁纸自动反色文字
- [x] 虚拟桌面（已生成 App 一览）
- [x] Android Tool 桥接系统
- [x] Tool 文档自动生成
- [x] 相机拍照
- [x] 相册图片选择

## License

请根据你的开源计划补充 License。
