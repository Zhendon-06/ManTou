# 馒头聊天应用 - 完整功能说明

## ✅ 已完成的所有约束

### 1. ✅ AI 接口
- 使用 `https://api.siliconflow.cn/v1/chat/completions`
- 完整的 Retrofit + OkHttp 封装
- 支持自定义模型、temperature、max_tokens 等参数

### 2. ✅ 聊天消息显示
- 使用 `RecyclerView (android:id="@+id/rvChat")`
- 左右气泡布局（user 右侧蓝色，assistant 左侧灰色）
- ListAdapter + DiffUtil 高效更新

### 3. ✅ 欢迎界面控制
- `flGreeting` 只在当前会话没有任何消息时显示
- 一旦 `rvChat` 有任意一条消息就自动隐藏
- 通过 ChatAdapter 的 `onDataChanged` 回调实现

### 4. ✅ 连续对话
- 每次发送消息前从数据库读取历史消息
- 组装为 API 的 messages 数组（包含 system + 历史）
- AI 基于完整上下文回复

### 5. ✅ 文本 + 图片输入
- 支持纯文本消息
- 支持文本 + 单张图片
- 超过 1 张自动只取第一张（`firstOrNull()`）

### 6. ✅ Room 数据库
- `ChatSessionEntity` - 会话表
- `ChatMessageEntity` - 消息表（外键关联）
- `ChatDao` - 完整的 CRUD 操作
- `ChatRepository` - 仓库层封装

### 7. ✅ 新建会话
- 点击 `new_chat` 创建新会话
- 清空消息列表
- 显示 `flGreeting` 欢迎界面
- 下次发送消息时自动创建数据库记录

### 8. ✅ 侧边栏历史会话
- 显示所有历史会话
- 显示内容为「该会话的第一个用户问题」
- 只显示一行（`maxLines="1"`, `ellipsize="end"`）
- 按创建时间倒序排列

### 9. ✅ 不破坏现有 UI ID
- 所有现有 ID 保持不变
- 只添加新的 ID（`rvSessions`, `tvClearHistory`）

### 10. ✅ 完整可运行代码
- 所有代码均为完整实现
- 无伪代码或 TODO 占位符
- 可直接编译运行

---

## 📁 完整文件结构

```
app/src/main/
├── java/com/hfad/mantou/
│   ├── adapter/
│   │   ├── ChatAdapter.kt ✅ 聊天消息适配器
│   │   ├── ImageSelectAdapter.kt ✅ 图片选择适配器
│   │   └── SessionAdapter.kt ✅ 会话列表适配器
│   ├── data/
│   │   ├── api/
│   │   │   ├── ApiConfig.kt ✅ API 配置
│   │   │   ├── ApiMessage.kt ✅ API 消息模型
│   │   │   ├── ChatRequest.kt ✅ API 请求
│   │   │   ├── ChatResponse.kt ✅ API 响应
│   │   │   ├── RetrofitClient.kt ✅ Retrofit 客户端
│   │   │   └── SiliconFlowApiService.kt ✅ API 服务接口
│   │   ├── database/
│   │   │   ├── AppDatabase.kt ✅ Room 数据库
│   │   │   ├── ChatDao.kt ✅ 数据访问对象
│   │   │   ├── ChatMessageEntity.kt ✅ 消息实体
│   │   │   └── ChatSessionEntity.kt ✅ 会话实体
│   │   ├── repository/
│   │   │   └── ChatRepository.kt ✅ 数据仓库
│   │   ├── ChatMessage.kt ✅ UI 消息模型
│   │   └── ImageItem.kt ✅ 图片项模型
│   ├── view/
│   │   ├── MainActivity.kt ✅ 主 Activity
│   │   └── MainFragment.kt ✅ 主 Fragment
│   └── viewmodel/
│       └── ChatViewModel.kt ✅ 聊天 ViewModel
├── res/
│   ├── layout/
│   │   ├── activity_main.xml ✅ 主布局（DrawerLayout）
│   │   ├── fragment_main.xml ✅ Fragment 布局
│   │   ├── item_chat_assistant.xml ✅ AI 消息布局
│   │   ├── item_chat_user.xml ✅ 用户消息布局
│   │   ├── item_image_select.xml ✅ 图片选择项布局
│   │   ├── item_session.xml ✅ 会话列表项布局
│   │   └── layout_drawer_menu.xml ✅ 侧边栏布局
│   └── drawable/
│       ├── bg_chat_assistant.xml ✅ AI 气泡背景
│       └── bg_chat_user.xml ✅ 用户气泡背景
└── AndroidManifest.xml ✅ 权限配置

build.gradle.kts ✅ 依赖配置
libs.versions.toml ✅ 版本管理
```

---

## 🎯 核心功能流程

### 发送消息流程

```
用户输入文本/选择图片
    ↓
点击键盘"发送"按钮
    ↓
MainFragment.sendMessage()
    ↓
ChatViewModel.sendMessage(content, imagePath)
    ↓
1. 保存用户消息到数据库
    ↓
2. 更新会话标题（如果是第一条消息）
    ↓
3. 从数据库读取历史消息
    ↓
4. 组装 API messages（system + 历史）
    ↓
5. 调用 SiliconFlow API
    ↓
6. 保存 AI 回复到数据库
    ↓
7. LiveData 自动通知 UI
    ↓
8. RecyclerView 自动更新显示
```

### 会话管理流程

```
点击 new_chat
    ↓
ChatViewModel.clearCurrentSession()
    ↓
清空消息列表，显示 flGreeting
    ↓
用户发送第一条消息
    ↓
自动创建新会话（保存到数据库）
    ↓
会话标题 = 第一条用户消息
    ↓
侧边栏自动显示新会话
```

### 切换会话流程

```
打开侧边栏
    ↓
点击历史会话
    ↓
ChatViewModel.switchToSession(sessionId)
    ↓
加载该会话的所有消息
    ↓
RecyclerView 显示历史消息
    ↓
隐藏 flGreeting（因为有消息）
    ↓
关闭侧边栏
```

---

## 🔧 使用说明

### 1. 配置 API Key

打开 `ApiConfig.kt`：

```kotlin
object ApiConfig {
    const val API_KEY = "sk-your-api-key-here"  // 替换为你的 API Key
}
```

获取 API Key：https://cloud.siliconflow.cn/account/ak

### 2. 编译运行

```bash
1. Sync Project with Gradle Files
2. Build → Clean Project
3. Build → Rebuild Project
4. Run 'app'
```

### 3. 功能测试

#### 发送消息
1. 输入文本
2. 点击键盘"发送"按钮
3. 等待 AI 回复

#### 发送图片
1. 点击 + 按钮打开图片面板
2. 选择图片（可多选，自动只取第一张）
3. 输入文本（可选）
4. 点击发送

#### 新建会话
1. 点击右上角"新建会话"按钮
2. 显示欢迎界面
3. 发送消息自动创建新会话

#### 查看历史会话
1. 点击左上角菜单按钮
2. 侧边栏显示所有历史会话
3. 点击会话切换
4. 长按会话删除

#### 清空历史
1. 打开侧边栏
2. 点击底部"清空所有会话"
3. 确认删除

---

## 📊 数据库结构

### ChatSessionEntity（会话表）

| 字段 | 类型 | 说明 |
|------|------|------|
| sessionId | Long | 主键，自增 |
| title | String | 会话标题（第一个用户问题） |
| createTime | Long | 创建时间戳 |

### ChatMessageEntity（消息表）

| 字段 | 类型 | 说明 |
|------|------|------|
| messageId | Long | 主键，自增 |
| sessionId | Long | 外键，关联会话 |
| role | String | "user" 或 "assistant" |
| content | String | 消息内容 |
| imagePath | String? | 图片路径（可为空） |
| timestamp | Long | 消息时间戳 |

---

## 🎨 UI 特性

### 聊天气泡
- **用户消息**：右侧，蓝色背景 (#4A90E2)，白色文字
- **AI 消息**：左侧，灰色背景 (#F0F0F0)，深色文字，带头像

### 侧边栏
- **宽度**：280dp
- **背景**：浅灰色 (#FAFAFA)
- **会话项**：显示标题 + 时间，只显示一行
- **操作**：点击切换，长按删除

### 欢迎界面
- **显示条件**：当前会话无消息
- **隐藏条件**：有任意一条消息
- **内容**：馒头图标 + "今天有什么可以帮您?"

---

## 🚀 扩展功能建议

1. **流式输出**：支持 AI 回复逐字显示
2. **消息重发**：长按消息重新发送
3. **复制消息**：长按复制消息内容
4. **会话重命名**：自定义会话标题
5. **导出会话**：导出为文本文件
6. **语音输入**：集成语音识别
7. **多模态**：支持更多图片格式

---

## 📝 注意事项

1. **API Key 安全**：不要将 API Key 提交到 Git
2. **网络权限**：确保已添加 INTERNET 权限
3. **图片权限**：运行时请求相册和相机权限
4. **数据库迁移**：生产环境使用 Migration 而非 fallbackToDestructiveMigration
5. **错误处理**：查看 Logcat 中的错误日志

---

## ✅ 功能清单

- [x] SiliconFlow API 集成
- [x] 聊天消息显示（左右气泡）
- [x] 欢迎界面自动控制
- [x] 连续对话支持
- [x] 文本 + 图片输入
- [x] Room 数据库存储
- [x] 新建会话
- [x] 侧边栏历史会话
- [x] 会话切换
- [x] 会话删除
- [x] 清空所有会话
- [x] 键盘发送按钮
- [x] 图片选择（多选自动压缩为 1 张）
- [x] 相机拍照
- [x] 自动滚动到最新消息

---

## 🎉 完成！

所有 10 个约束已全部实现，代码完整可运行。
配置好 API Key 后即可使用！

