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
- 支持语音输入（适配mimo，输入apikey之后自动连接）
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
示例：<img width="300" height="669" alt="c249d138e3ea57e4dd90c5872294ee3e" src="https://github.com/user-attachments/assets/359fda27-2463-4942-975b-8c363cf1ec64" />

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
示例：<img width="300" height="669" alt="44e27f3fd2723c3aa21ebf28019b8a82" src="https://github.com/user-attachments/assets/2af757b4-43c5-48b8-8b11-96985225deef" />

### 虚拟桌面

- 提供独立的"桌面"入口，集中展示所有已生成的网页 App
- 点击图标即可全屏运行对应 App，类似系统应用抽屉
示例：<img width="300" height="669" alt="002be394cfb7bcd16f1037fb9c22f180" src="https://github.com/user-attachments/assets/aef72467-9578-424c-ad66-679f8914d63f" />

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
<img width="300" height="669" alt="ed43d748ac0ad99432e66f15ec8ca302" src="https://github.com/user-attachments/assets/c0238adc-684e-410b-8121-08c17b145343" />
<img width="300" height="669" alt="848d75669c2027f50d7cdf1688e53ffa" src="https://github.com/user-attachments/assets/78b06b75-03e7-4a8b-a8fa-198dc86cbbdf" />
<img width="300" height="669" alt="1dc9f11ad87a2c67ba1e80df2de6ffaf" src="https://github.com/user-attachments/assets/0a6b9da1-6acd-438a-a11a-dad5f0cfd140" />
<img width="300" height="669" alt="66451ba6de1e477f431bba8187f34a53" src="https://github.com/user-attachments/assets/441d8ed9-908f-40ae-8a9d-b192e3bfee3a" />
<img width="300" height="669" alt="cd4567f5d5564541cd616c56963d24ae" src="https://github.com/user-attachments/assets/f8ff8549-dfa5-430d-8868-926241d1d7b9" />

## 开发者构建与部署

馒头使用端侧 embedding 和预计算的示例向量索引做高频意图识别。开发者首次构建或更新模型部署前，需要先用 `uv` 下载并导出 `m3e-small` 模型资产，再运行 Android 构建。

在项目根目录执行：

```bash
uv venv --python 3.11 .venv-m3e
uv pip install --python .venv-m3e/bin/python -r scripts/m3e-small-requirements.txt
.venv-m3e/bin/python scripts/export_m3e_small_onnx.py
./gradlew :app:compileDebugKotlin
```

导出脚本会从 Hugging Face 下载 `moka-ai/m3e-small`，并生成：

```text
app/src/main/assets/embedding/m3e-small/model.onnx
app/src/main/assets/embedding/m3e-small/vocab.txt
app/src/main/assets/embedding/m3e-small/intent_vectors.json
```

这些文件位于 `assets`，会随 APK 一起打包。`intent_vectors.json` 由 `scripts/intent_samples.json` 中的分层样例预计算生成，运行时使用 top-k 原型相似度判定，不会在首个请求时重复计算全部样例。缺少任一资产时，应用会回落到本地高精度规则，并在有必要时调用云端 LLM 识别。

如果只想重新导出模型资产，可以重新执行：

```bash
.venv-m3e/bin/python scripts/export_m3e_small_onnx.py
```

如果只修改了 `scripts/intent_samples.json`，可以仅重新生成示例向量索引：

```bash
.venv-m3e/bin/python scripts/export_m3e_small_onnx.py --vectors-only
```

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

KSP 会生成完整方法列表。Debug 构建产物位于 `app/build/generated/ksp/debug/resources/mantou_tools.md`，运行时使用同批生成的 `GeneratedMantouToolsDoc` 注入 Prompt。

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
| `autoRegister` | 是否进入 KSP 生成的全局注册表；需要页面级上下文的 Tool 设为 `false` |

#### `@ToolMethod`

描述一个可被 JS 调用的方法。

```kotlin
@ToolMethod(
    description = "弹一个短 Toast",
    example = "window.MantouApp.toast.toastShort('已复制');"
)
```

#### `@ToolParam`

描述方法公开参数名和用途，KSP 会将其写入 API 文档。

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

KSP 会按注解全限定名读取符号，因此注解顺序不影响生成。编译期还会检查三件套是否完整、返回值是否为非空 `String`、参数类型是否受 JSBridge 支持，以及 Tool 名是否重复。

### 注册 Tool

普通 Tool 不需要手动注册。KSP 会为所有 `autoRegister = true` 且具有 `public constructor(Context)` 的 Tool 生成直接构造代码：

```kotlin
GeneratedToolRegistry.createAll(context)
```

像 `StorageTool` 这样依赖当前 HTML 文件、必须按 WebView 创建的能力，需要设置 `autoRegister = false`，并由宿主运行时单独实例化。

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

`tool-ksp` 模块实现了 `SymbolProcessor`，在 Kotlin 编译期读取 Tool 注解并生成：

```text
GeneratedMantouToolsDoc.kt
GeneratedToolRegistry.kt
mantou_tools.md
```

KSP 输出按变体位于 `app/build/generated/ksp/<variant>/`。`GeneratedMantouToolsDoc` 直接提供运行时 Prompt 文档，Markdown 文件用于人工检查或 CI 归档。

这份文档会被 `AppGenerator` 注入到网页 App 生成 prompt 中，LLM 才知道当前 App 有哪些 Tool、怎么调用、返回什么结构。

如果只想刷新 Debug Tool 生成结果，可以运行：

```bash
./gradlew :app:kspDebugKotlin
```

### Tool 设计建议

- 方法名建议带 Tool 前缀，避免语义过短，例如 `clipboardRead`、`alarmSet`
- 参数要少而明确，复杂结构传 JSON 字符串
- 返回 `data` 尽量稳定，方便网页和 LLM 依赖
- 错误信息写给用户和 LLM 都能理解
- 需要用户确认的系统操作，优先跳系统页面而不是静默执行
- 对平台版本差异进行兼容，例如 Android 版本不支持时返回明确错误
