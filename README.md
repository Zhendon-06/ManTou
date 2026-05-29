# 馒头AI (ManTou AI)

一个基于 Kotlin 开发的 Android 智能体应用，不仅能聊天，还能根据一句话自动生成可运行的网页 App。

## 功能特性

### 智能对话
- **多轮上下文对话**：自动维护对话历史，AI 基于完整上下文回复
- **流式输出**：AI 回复实时逐字显示，提升交互体验
- **多模态**：支持发送图片进行视觉对话（SiliconFlow 视觉模型）

### 一句话生成网页 App
- **意图自动识别**：系统自动判断用户是想聊天还是要生成 App，无需手动切换
- **LLM 生成代码**：调用 DeepSeek 生成完整的、自包含的 HTML/CSS/JS 代码
- **文件写入执行**：将生成的代码写入内部存储，通过 WebView 渲染运行
- **聊天内预览**：生成的 App 在聊天气泡中以 WebView 预览
- **全屏体验**：点击全屏按钮进入沉浸式全屏模式，点击缩小按钮返回聊天

### 会话管理
- **本地持久化**：Room 数据库存储所有聊天记录，支持历史会话恢复
- **侧边栏**：查看、切换、删除历史会话
- **自动标题**：使用首条用户消息作为会话标题

## 技术架构

```
┌─────────────────────────────────────────────────┐
│                   UI 层                          │
│  MainFragment / VirtualAppActivity / ChatAdapter │
└──────────────────────┬──────────────────────────┘
                       │ 观察
┌──────────────────────▼──────────────────────────┐
│                 ViewModel 层                     │
│  ChatViewModel（意图分流 / 流式输出 / App生成）    │
└──────────────────────┬──────────────────────────┘
                       │ 调用
┌──────────────────────▼──────────────────────────┐
│                  工具层                          │
│  AppIntentDetector / AppGenerator / StreamingApi │
└──────────────────────┬──────────────────────────┘
                       │ 调用
┌──────────────────────▼──────────────────────────┐
│                 数据层                           │
│  ChatRepository / Room / OkHttp                  │
└─────────────────────────────────────────────────┘
```

## 一句话生成 App 流程

```
用户输入 "帮我做一个计算器"
         │
         ▼
  AppIntentDetector（Qwen2.5-7B 快速判断）
     ┌───┴───┐
     │       │
  普通聊天  生成App
     │       │
     ▼       ▼
  流式对话  AppGenerator（DeepSeek 生成 HTML）
              │
              ▼
         写入文件系统
     filesDir/generated_apps/app_xxx.html
              │
              ▼
         WebView 加载渲染
     ┌────────┴────────┐
     │                 │
  聊天气泡预览      全屏模式
  (app_webView)   (VirtualAppActivity)
  btnFullscreen→  ←btnSmallscreen
```

## 项目结构

```
app/src/main/java/com/hfad/mantou/
├── adapter/
│   ├── ChatAdapter.kt           # 聊天消息适配器（含 WebView 预览）
│   ├── ImageSelectAdapter.kt    # 图片选择适配器
│   └── SessionAdapter.kt        # 会话列表适配器
├── data/
│   ├── api/
│   │   ├── ApiConfig.kt         # API 配置（DeepSeek / SiliconFlow / 意图检测 / App生成）
│   │   ├── ApiMessage.kt        # API 消息格式（支持多模态）
│   │   ├── ChatRequest.kt       # API 请求体
│   │   ├── ChatResponse.kt      # API 响应体（含流式 StreamChunk）
│   │   └── StreamingApiService.kt  # SSE 流式调用服务
│   ├── database/
│   │   ├── AppDatabase.kt       # Room 数据库
│   │   ├── ChatDao.kt           # DAO 接口
│   │   ├── ChatMessageEntity.kt # 消息实体（含 appHtmlPath）
│   │   └── ChatSessionEntity.kt # 会话实体
│   ├── repository/
│   │   └── ChatRepository.kt    # 数据仓库
│   ├── ChatMessage.kt           # UI 消息数据类
│   └── ImageItem.kt             # 图片选择项数据类
├── utils/
│   ├── AppIntentDetector.kt     # 意图识别（快速模型判断是否生成App）
│   ├── AppGenerator.kt          # App 生成器（LLM生成HTML + 写入文件）
│   └── ImageUtils.kt            # 图片处理工具
├── view/
│   ├── MainActivity.kt          # 主 Activity（DrawerLayout）
│   ├── MainFragment.kt          # 主 Fragment（聊天界面）
│   └── VirtualAppActivity.kt    # 全屏 WebView Activity
└── viewmodel/
    └── ChatViewModel.kt         # 聊天 ViewModel（意图分流 + App生成流程）
```

## 技术栈

| 层级 | 技术 |
|------|------|
| **UI 层** | ViewBinding、RecyclerView、WebView、ConstraintLayout |
| **架构层** | MVVM、ViewModel、LiveData、Navigation |
| **数据层** | Room、Kotlin Flow、Repository 模式 |
| **网络层** | OkHttp、SSE（Server-Sent Events）、Gson |
| **AI 模型** | DeepSeek（对话 + App生成）、SiliconFlow Qwen2.5-7B（意图检测） |
| **图片加载** | Glide |
| **异步处理** | Kotlin Coroutines |

## API 配置

项目支持多个 LLM API 端点，按用途分工：

| 用途 | API | 模型 | 说明 |
|------|-----|------|------|
| 日常对话 | DeepSeek | deepseek-chat | 流式输出，高质量对话 |
| 意图检测 | SiliconFlow | Qwen2.5-7B-Instruct | 快速轻量，判断用户意图 |
| App 生成 | DeepSeek | deepseek-chat | 生成完整 HTML 代码 |
| 视觉对话 | SiliconFlow | Qwen2-VL-72B-Instruct | 图片理解（需开启） |

在 `local.properties` 中配置 API Key：

```properties
SILICONFLOW_API_KEY=sk-your-api-key-here
```

## 数据库结构

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

2. **配置 API Key**

   在 `local.properties` 中添加：
   ```properties
   SILICONFLOW_API_KEY=sk-your-api-key-here
   ```

3. **构建运行**

   使用 Android Studio 打开项目，Sync Gradle 后点击运行即可。

## 功能清单

- [x] DeepSeek / SiliconFlow 双 API 支持
- [x] SSE 流式输出
- [x] 多轮上下文对话
- [x] 图片对话（多模态）
- [x] Room 数据库本地持久化
- [x] 会话管理（创建/切换/删除）
- [x] 侧边栏历史会话
- [x] 一句话生成网页 App
- [x] 意图自动识别（聊天 vs 生成App）
- [x] 聊天气泡内 WebView 预览
- [x] 全屏 WebView 体验
- [x] 相机拍照
- [x] 图片选择（相册）
- [x] 自动滚动到最新消息
