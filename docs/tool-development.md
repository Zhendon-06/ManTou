# Tool 开发指南

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

KSP 会生成完整方法列表。Debug 构建产物位于 `app/build/generated/ksp/debug/resources/mantou_tools.md`；运行时使用同批生成的 `GeneratedMantouToolsDoc`，根据用户请求通过关键词和本地 embedding 检索相关 Tool，只把命中的文档注入 Prompt。`toast` 和 `vibration` 作为基础交互体验能力始终注入新建应用的 Prompt。

可用下面的 Logcat 过滤器查看优化前后的系统 Prompt 长度、估算 Token、节省比例、检索耗时和命中的 Tool：

```bash
adb logcat -s ToolPromptOptimizer:I
```

每次请求会连续输出 `[Tool选择]` 和 `[Token对比]` 两条日志：前者明确列出实际注入及未注入的 Tool，后者用 `baselineTokens~` 表示旧版全量注入的估算 Token、`optimizedTokens~` 表示实际请求使用的估算 Token。两者使用项目统一的字符估算规则，不代表服务端精确 tokenizer 计数，但适合做同一请求的前后对比。

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

KSP 输出按变体位于 `app/build/generated/ksp/<variant>/`。`GeneratedMantouToolsDoc` 同时提供完整文档和按 Tool 拆分的检索文档，Markdown 文件用于人工检查或 CI 归档。

`AppGenerator` 会按用户需求选出相关 Tool 文档并注入网页 App 生成 Prompt，LLM 只接收当前请求可能需要的调用方式和返回结构；本地 embedding 不可用时会回退到完整 Tool 清单。

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
