# 馒头AI (ManTou AI)

一个基于 Kotlin 开发的 Android 智能体应用，不仅能聊天，还能根据一句话自动生成可运行的网页 App。

## 功能特性

### 智能对话
- **多轮上下文对话**：自动维护对话历史，AI 基于完整上下文回复
- **流式输出**：AI 回复实时逐字显示，提升交互体验
- **多模态**：支持发送图片进行视觉对话
- **消息长按操作**：支持选字复制、删除、修改消息内容
- **生成思考面板**：生成 App 时以固定高度面板显示思考过程，内部自动滚动

### 一句话生成网页 App
- **意图自动识别**：系统自动判断用户是想聊天还是要生成 App，无需手动切换
- **LLM 生成代码**：调用当前配置的模型生成完整的、自包含的 HTML/CSS/JS 代码
- **文件写入执行**：将生成的代码写入内部存储，通过 WebView 渲染运行
- **语义化文件名**：根据用户要生成的 App 类型自动命名 HTML 文件，例如 `天气_20260608_160000.html`
- **聊天内预览**：生成的 App 在聊天气泡中以 WebView 预览
- **全屏体验**：点击全屏按钮进入沉浸式全屏模式，点击缩小按钮返回聊天

### 文件架构与 Agent Workspace
- **左右滑动切换**：聊天页和文件架构页通过 ViewPager2 横向切换，共用同一个 AppBar
- **文件树映射**：展示 `/workspace/generated_apps`、`/workspace/agent`、`/workspace/memory`
- **Agent 身份文件**：`SOUL.md` 定义身份、语气、价值观和行为边界
- **对话规则文件**：`CHAT.md` 定义回复格式、交互规则和工具使用规范
- **记忆文件**：`MEMORY.md` 记录显式或长期偏好，并进入后续对话 prompt
- **基本文件操作**：`md` / `txt` 可点击编辑保存，`html` 可点击用 WebView 打开
- **生成文件管理**：`generated_apps` 下的文件支持长按删除

### 多模型管理
- **用户自定义模型**：不内置任何默认模型，用户必须自行配置 Provider 和模型后才能使用
- **多 Provider 支持**：支持 OpenAI 兼容格式和 Anthropic 格式的 API 端点
- **动态切换**：在设置页面随时切换 Provider 和模型，顶部栏实时显示当前模型
- **模型列表拉取**：自动从 Provider API 拉取可用模型列表，无需手动输入模型名

### 会话管理
- **本地持久化**：Room 数据库存储所有聊天记录，支持历史会话恢复
- **侧边栏**：查看、切换、删除历史会话
- **自动标题**：使用首条用户消息作为会话标题
- **消息编辑**：长按消息可修改已保存消息内容

## 技术架构

```
┌──────────────────────────────────────────────────────┐
│                      UI 层                            │
│  MainFragment / VirtualAppActivity / ModelSettingAct  │
│  ViewPager2（聊天页 / 文件架构页）                      │
└──────────────────────┬───────────────────────────────┘
                       │ 观察
┌──────────────────────▼───────────────────────────────┐
│                   ViewModel 层                        │
│  ChatViewModel（意图分流 / 流式输出 / App生成）         │
└──────────────────────┬───────────────────────────────┘
                       │ ChatCallConfig
┌──────────────────────▼───────────────────────────────┐
│                    工具层                              │
│  AppIntentDetector / AppGenerator / AgentWorkspace    │
│  StreamingApiService / ImageUtils                     │
└──────────────────────┬───────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────┐
│                   数据层                              │
│  ChatRepository / ProviderRepository / Room / OkHttp  │
└──────────────────────────────────────────────────────┘
```

## 一句话生成 App 流程

```
用户输入 "帮我做一个计算器"
         │
         ▼
  resolveActiveChatConfig() → ChatCallConfig
         │
         │  未配置模型 → Toast: "请先配置模型后再使用"
         │
         ▼  已配置模型
  AppIntentDetector（当前模型快速判断）
     ┌───┴───┐
     │       │
  普通聊天  生成App
     │       │
     ▼       ▼
  流式对话  AppGenerator（当前模型生成 HTML）
              │
              ▼
         写入文件系统
     filesDir/generated_apps/类型_时间戳.html
              │
              ▼
         WebView 加载渲染
     ┌────────┴────────┐
     │                 │
  聊天气泡预览      全屏模式
  (app_webView)   (VirtualAppActivity)
  btnFullscreen→  ←btnSmallscreen
```

## Runtime Workspace

App 启动或首次生成 App 时，会在应用私有目录中维护一个运行时 workspace：

```
/workspace
├── generated_apps/
│   └── 天气_20260608_160000.html       # 生成的网页 App
├── agent/
│   ├── SOUL.md                         # 我是谁：人格、语气、价值观、边界
│   └── CHAT.md                         # 怎么对话：格式、规则、工具规范
└── memory/
    └── MEMORY.md                       # 长期记忆与记忆写入规则
```

其中 `SOUL.md`、`CHAT.md`、`MEMORY.md` 会被合并进普通聊天的 system prompt。用户明确说“记住”“保存到记忆”“以后都...”时，关键信息会追加到 `MEMORY.md`。

## 项目结构

```
app/src/main/java/com/hfad/mantou/
├── adapter/
│   ├── ChatAdapter.kt              # 聊天消息适配器（含 WebView 预览）
│   ├── ImageSelectAdapter.kt       # 图片选择适配器
│   ├── ProviderModelAdapter.kt     # 模型列表适配器（单选高亮）
│   ├── SessionAdapter.kt           # 会话列表适配器
│   └── WorkspaceFileAdapter.kt     # 文件架构页文件树适配器
├── data/
│   ├── api/
│   │   ├── ApiConfig.kt            # API 通用参数（超时、Token上限等）
│   │   ├── ApiMessage.kt           # API 消息格式（支持多模态）
│   │   ├── ChatCallConfig.kt       # 动态聊天调用配置（baseUrl/apiKey/model/format）
│   │   ├── ChatRequest.kt          # API 请求体
│   │   ├── ChatResponse.kt         # API 响应体（含流式 StreamChunk）
│   │   ├── ModelListApiService.kt  # 模型列表拉取服务
│   │   └── StreamingApiService.kt  # SSE 流式调用（OpenAI/Anthropic 双协议）
│   ├── database/
│   │   ├── AppDatabase.kt          # Room 数据库
│   │   ├── ChatDao.kt              # 聊天 DAO 接口
│   │   ├── ChatMessageEntity.kt    # 消息实体（含 appHtmlPath）
│   │   ├── ChatSessionEntity.kt    # 会话实体
│   │   ├── ProviderDao.kt          # Provider + Model DAO 接口
│   │   └── ProviderEntity.kt       # Provider 实体（OpenAI/Anthropic 格式）
│   ├── preferences/
│   │   └── ActiveModelStore.kt     # 活跃模型持久化（SharedPreferences）
│   ├── repository/
│   │   ├── ChatRepository.kt       # 聊天数据仓库
│   │   └── ProviderRepository.kt   # Provider 数据仓库
│   ├── ChatMessage.kt              # UI 消息数据类
│   └── ImageItem.kt                # 图片选择项数据类
├── utils/
│   ├── AgentWorkspace.kt           # Runtime workspace / prompt 文件 / 记忆写入
│   ├── AppIntentDetector.kt        # 意图识别（关键词 + LLM 判断）
│   ├── AppGenerator.kt             # App 生成器（LLM生成HTML + 语义化命名写入文件）
│   └── ImageUtils.kt               # 图片处理工具
├── view/
│   ├── MainActivity.kt             # 主 Activity（DrawerLayout）
│   ├── MainFragment.kt             # 主 Fragment（共享AppBar + 聊天/文件架构分页）
│   ├── ModelSettingActivity.kt     # 模型配置页面（Provider/Model 管理）
│   └── VirtualAppActivity.kt       # 全屏 WebView Activity
└── viewmodel/
    └── ChatViewModel.kt            # 聊天 ViewModel（意图分流 + 动态模型解析）
```

## 技术栈

| 层级 | 技术 |
|------|------|
| **UI 层** | ViewBinding、RecyclerView、ViewPager2、WebView、ConstraintLayout |
| **架构层** | MVVM、ViewModel、LiveData、Navigation |
| **数据层** | Room、Kotlin Flow、SharedPreferences、Repository 模式 |
| **网络层** | OkHttp、SSE（Server-Sent Events）、Gson |
| **AI 接入** | OpenAI 兼容格式、Anthropic 格式、用户自定义 Provider |
| **图片加载** | Glide |
| **异步处理** | Kotlin Coroutines |

## 模型配置

项目**不内置任何默认模型**，用户必须自行配置后才能使用。

### 配置流程

1. 打开侧边栏，点击设置按钮进入模型配置页面
2. 添加 Provider（填写名称、Base URL、API Key、API 格式）
3. 拉取模型列表，选择要使用的模型
4. 返回聊天界面，顶部栏显示当前选中的模型名称

### 支持的 API 格式

| 格式 | 认证方式 | 端点 | 兼容服务商 |
|------|----------|------|------------|
| OpenAI | `Authorization: Bearer {key}` | `POST {baseUrl}v1/chat/completions` | DeepSeek、SiliconFlow、OpenAI、Groq 等 |
| Anthropic | `x-api-key: {key}` + `anthropic-version` | `POST {baseUrl}v1/messages` | Claude、Anthropic 兼容端点 |

### 动态模型解析

所有功能（聊天、意图检测、App 生成）统一使用用户配置的模型：

```
ActiveModelStore (SharedPreferences)
    │  providerId + modelName
    ▼
ChatViewModel.resolveActiveChatConfig()
    │  查询 ProviderEntity → ChatCallConfig
    ▼
StreamingApiService.streamChatCompletion(config, request)
    │  config.isAnthropic ? streamAnthropic() : streamOpenAi()
    ▼
AppIntentDetector / AppGenerator 同样使用 ChatCallConfig
```

## 数据库结构

### providers（服务商表）

| 字段 | 类型 | 说明 |
|------|------|------|
| providerId | Long | 主键，自增 |
| name | String | Provider 名称 |
| baseUrl | String | API 基础地址 |
| apiKey | String | API 密钥 |
| apiFormat | String | "openai" 或 "anthropic" |
| createTime | Long | 创建时间戳 |

### provider_models（模型表）

| 字段 | 类型 | 说明 |
|------|------|------|
| modelId | Long | 主键，自增 |
| providerId | Long | 外键，关联 Provider |
| modelName | String | 模型名称 |
| createTime | Long | 创建时间戳 |

### chat_sessions（会话表）

| 字段 | 类型 | 说明 |
|------|------|------|
| sessionId | Long | 主键，自增 |
| title | String | 会话标题 |
| createTime | Long | 创建时间戳 |

### chat_messages（消息表）

| 字段 | 类型 | 说明 |
|------|------|------|
| messageId | Long | 主键，自增 |
| sessionId | Long | 外键，关联会话（级联删除） |
| role | String | "user" 或 "assistant" |
| content | String | 消息内容 |
| imagePath | String? | 图片路径 |
| timestamp | Long | 消息时间戳 |
| appHtmlPath | String? | 生成的 App HTML 文件路径 |

## 开发环境

- **语言**：Kotlin 2.0.21
- **最低 SDK**：26 (Android 8.0)
- **目标 SDK**：36
- **JVM Target**：11
- **构建工具**：AGP 8.13.2 + KSP

## 快速开始

1. **克隆项目**
   ```bash
   git clone https://github.com/yourusername/ManTou.git
   ```

2. **构建运行**

   使用 Android Studio 打开项目，Sync Gradle 后点击运行。

3. **配置模型**

   首次启动后，打开侧边栏 → 点击设置按钮 → 添加你的 API Provider 和模型。

## 功能清单

- [x] 用户自定义 Provider / 模型配置
- [x] OpenAI / Anthropic 双协议支持
- [x] 模型列表自动拉取
- [x] 动态模型切换（顶部栏实时显示）
- [x] SSE 流式输出
- [x] 多轮上下文对话
- [x] 图片对话（多模态）
- [x] 消息长按操作（选字复制/删除/修改）
- [x] Room 数据库本地持久化
- [x] 会话管理（创建/切换/删除）
- [x] 侧边栏历史会话
- [x] 一句话生成网页 App
- [x] 意图自动识别（聊天 vs 生成App）
- [x] 生成 App 文件语义化命名
- [x] 生成 App 思考过程固定面板滚动
- [x] 聊天气泡内 WebView 预览
- [x] 全屏 WebView 体验
- [x] 文件架构页（agent / memory / generated_apps）
- [x] `SOUL.md` / `CHAT.md` / `MEMORY.md` prompt 注入
- [x] 显式记忆写入 MEMORY.md
- [x] 文件点击编辑 / WebView 打开
- [x] generated_apps 文件长按删除
- [x] 聊天页与文件架构页共享 AppBar 滑动切换
- [x] 相机拍照
- [x] 图片选择（相册）
- [x] 自动滚动到最新消息
