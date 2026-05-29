# 馒头AI (ManTou AI)

一个基于Kotlin开发的Android智能聊天应用，支持多轮对话、流式输出、本地持久化存储等功能。

## 功能特性

- **智能对话**：支持多轮上下文对话，自动维护对话历史
- **流式输出**：AI回复实时显示，提升交互体验
- **本地存储**：使用Room数据库存储聊天记录，支持历史会话恢复
- **图片对话**：支持发送图片进行多模态对话
- **会话管理**：支持创建、切换、删除会话，自动使用首条消息作为会话标题
- **响应式设计**：使用Kotlin Flow实现数据变化的实时监听

## 技术栈

| 层级 | 技术 |
|------|------|
| **UI层** | ViewBinding、RecyclerView、ConstraintLayout |
| **架构层** | MVVM、ViewModel、LiveData |
| **数据层** | Room、Flow、Repository模式 |
| **网络层** | OkHttp、SSE（Server-Sent Events） |
| **图片加载** | Glide |
| **异步处理** | Kotlin Coroutines |

## 项目结构

```
app/src/main/java/com/hfad/mantou/
├── adapter/          # RecyclerView适配器
│   ├── ChatAdapter.kt        # 聊天消息适配器（左右对话效果）
│   └── SessionAdapter.kt     # 会话列表适配器
├── data/             # 数据模型
│   ├── ChatMessage.kt        # 消息数据类
│   └── ChatSession.kt        # 会话数据类
├── database/         # Room数据库
│   ├── AppDatabase.kt        # 数据库定义
│   ├── ChatDao.kt            # 数据访问对象
│   └── entity/               # 数据库实体
├── repository/       # 数据仓库
│   └── ChatRepository.kt     # 封装数据库操作
├── view/             # UI界面
│   └── MainFragment.kt       # 主界面
├── viewmodel/        # 视图模型
│   └── ChatViewModel.kt      # 聊天业务逻辑
└── api/              # 网络请求
    └── StreamingApiService.kt # SSE流式请求
```

## 核心亮点

### 1. 流式输出实现

使用SSE（Server-Sent Events）技术实现AI回复的实时显示：

```kotlin
// 通过Kotlin Flow实现流式数据传递
fun streamChatCompletion(messages: List<Message>): Flow<StreamEvent> = flow {
    // 建立SSE连接，实时接收数据片段
    // 每个数据片段通过Flow发射，UI实时更新
}
```

### 2. 左右对话效果

使用RecyclerView的多类型ViewHolder实现：

```kotlin
override fun getItemViewType(position: Int): Int {
    return when {
        message.isStreaming -> VIEW_TYPE_LOADING
        message.role == ROLE_USER -> VIEW_TYPE_USER      // 右边
        message.role == ROLE_ASSISTANT -> VIEW_TYPE_ASSISTANT  // 左边
    }
}
```

### 3. 本地数据持久化

使用Room数据库实现聊天记录的本地存储：

```kotlin
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    val sessionId: Long,    // 关联会话ID
    val role: String,       // user/assistant
    val content: String,
    val timestamp: Long
)
```

### 4. 响应式数据流

使用Kotlin Flow实现数据库变化的实时监听：

```kotlin
// DAO返回Flow类型
@Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId")
fun getMessagesBySessionId(sessionId: Long): Flow<List<ChatMessageEntity>>

// ViewModel收集Flow，自动更新UI
repository.getMessagesBySessionId(sessionId).collect { messages ->
    _messages.value = messages
}
```

## 架构设计

```
UI层 (View)
    ↓ 观察
ViewModel层
    ↓ 调用
Repository层
    ↓ 调用
数据层 (Room/Network)
```

- **单一职责**：每层只负责自己的功能
- **解耦合**：通过接口和抽象降低依赖
- **可测试**：每层可以独立测试
- **易维护**：清晰的职责边界

## 开发环境

- **语言**：Kotlin
- **最低SDK**：24 (Android 7.0)
- **目标SDK**：34 (Android 14)
- **构建工具**：Gradle 8.0+

## 依赖库

```kotlin
// Android核心
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.11.0")

// 架构组件
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

// Room数据库
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
kapt("androidx.room:room-compiler:2.6.1")

// 网络请求
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// 图片加载
implementation("com.github.bumptech.glide:glide:4.16.0")
```

## 快速开始

1. **克隆项目**
   ```bash
   git clone https://github.com/yourusername/ManTou.git
   ```

2. **配置API**
   在`local.properties`中添加你的API密钥：
   ```properties
   API_KEY=your_api_key_here
   ```

3. **构建运行**
   使用Android Studio打开项目，点击运行按钮即可。

## 项目截图

| 聊天界面 | 侧边栏 | 图片对话 |
|---------|--------|---------|
| ![Chat](screenshots/chat.png) | ![Sidebar](screenshots/sidebar.png) | ![Image](screenshots/image.png) |

## 技术博客

- [流式输出实现详解](docs/streaming.md)
- [Room数据库设计](docs/database.md)
- [MVVM架构实践](docs/architecture.md)

## 贡献

欢迎提交Issue和Pull Request！

## 许可证

```
Copyright 2024 ManTou AI

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## 致谢

- [Kotlin](https://kotlinlang.org/)
- [Android Jetpack](https://developer.android.com/jetpack)
- [OkHttp](https://square.github.io/okhttp/)
- [Glide](https://github.com/bumptech/glide)

---

<p align="center">
  Made with ❤️ by ManTou Team
</p>
