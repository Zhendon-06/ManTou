# 馒头 AI (ManTou AI)

馒头 AI 是一个 Android 智能体应用。它可以进行多轮聊天、识别图片，也可以根据一句话自动生成可运行的网页 App，并直接在应用内预览和全屏使用。

## 主要能力

### 智能对话
- 支持多轮上下文对话
- 支持流式输出
- 支持图片对话
- 支持历史会话管理
- 支持长按消息进行复制、删除、编辑

### 一句话生成网页 App
- 用户直接描述想法，例如“帮我做一个番茄钟”
- 馒头会自动判断这是普通聊天还是 App 生成需求
- 生成的网页 App 会保存在本地
- 支持聊天内预览和全屏打开
- 生成过程会显示状态；即使模型没有思考过程，也会显示正在请求、接收代码和整理文件的进度
- 馒头生成的网页 App 带有运行标识，分享出去后会提示使用馒头 App 打开

### 模型配置
- 不内置默认模型，需要用户自行配置 Provider 和模型
- 支持 OpenAI 兼容接口
- 支持 Anthropic 接口
- 支持从 Provider 拉取模型列表
- 支持切换当前使用的 Provider 和模型
- Base URL 可填写根地址、`/v1`，或完整的模型/聊天端点，应用会自动处理路径

### 文件与记忆
- 生成的网页 App 会出现在文件架构页
- 支持打开、编辑和管理部分本地文件
- 支持显式记忆写入，用于后续对话

## 使用方式

1. 启动应用后，打开侧边栏并进入模型配置页。
2. 新增 Provider，填写名称、Base URL、API Key 和接口格式。
3. 点击拉取模型列表，选择要使用的模型。
4. 回到聊天页，直接发送消息或描述想生成的 App。

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
|------|------|------|
| OpenAI | OpenAI 兼容聊天、生成 App、拉取模型 | `Authorization: Bearer <API Key>` |
| Anthropic | Claude / Anthropic 兼容聊天、生成 App、拉取模型 | `x-api-key: <API Key>` |

API Key 可以留空，适用于本地模型代理或无鉴权服务。

## 开发运行

- 语言：Kotlin
- 平台：Android
- 最低 SDK：26
- 目标 SDK：36

使用 Android Studio 打开项目，等待 Gradle Sync 完成后运行即可。

## 功能清单

- [x] 自定义 Provider / 模型配置
- [x] OpenAI / Anthropic 双接口格式
- [x] 模型列表自动拉取
- [x] 当前模型动态切换
- [x] 多轮聊天
- [x] 流式输出
- [x] 图片对话
- [x] 会话管理
- [x] 消息长按操作
- [x] 一句话生成网页 App
- [x] 生成 App 进度提示
- [x] 聊天内 WebView 预览
- [x] 全屏 WebView 使用
- [x] 生成 App 分享保护
- [x] 文件架构页
- [x] 显式记忆写入
- [x] 相机拍照
- [x] 相册图片选择
