# ManTou · 馒头 AI

ManTou 是一款 Android 智能体应用。用自己的模型服务进行多轮对话，也可以用自然语言生成可运行的网页应用，在手机上预览、修改和使用。

## 下载安装

**[下载 ManTou v2.0.1（Android APK）](https://github.com/Zhendon-06/ManTou/releases/download/v2.0.1/ManTou-v2.0.1.apk)** · [查看发布记录](https://github.com/Zhendon-06/ManTou/releases)

支持 Android 8.0（API 26）及以上。首次使用时，在「设置 → 模型配置」添加自己的模型服务。网页应用和聊天共用当前选中的模型。服务地址、模型名称和 API Key 均在应用内配置。

## 主要功能

| 功能 | 说明 |
| --- | --- |
| 智能对话 | 流式回复、Markdown、图片附件、MiMo 语音转文字、历史会话搜索与归档 |
| 模型切换 | 支持 OpenAI Chat Completions 和 Anthropic Messages 兼容接口，聊天页可直接切换已配置模型 |
| 网页应用生成 | 根据需求规划、生成和修改多文件网页应用，在应用内检查、预览和发布 |
| 联网搜索 | 聊天和写代码时可查网页资料，也可以查找可复用图片并保存到工作区或项目 |
| Linux 工作区 | 可选的离线 Linux 执行环境，允许 Agent 用 Python 或 Shell 处理选定的工作区文件 |
| 工作区与桌面 | 从聊天中指定工作目录，管理项目文件、记忆和资源，在虚拟桌面打开已发布的应用 |
| 任务进度 | 生成任务显示当前步骤。支持相应系统功能的设备还可通过通知查看进度 |
| 外观与上下文 | 液态玻璃输入框，支持默认或自定义壁纸。聊天底部的上下文圆环可查看 Token 用量、调整阈值和选择验收策略 |

## 配置模型

1. 进入「设置 → 模型配置」，新增服务并填写 Base URL、接口格式和 API Key。
2. 拉取服务的模型列表，选择要使用的模型。
3. 返回聊天页发送消息，或输入「帮我做一个番茄钟」试用应用生成。

Base URL 可填服务根地址、`/v1` 或完整的聊天端点。OpenAI 兼容服务使用 `Authorization: Bearer`，Anthropic 兼容服务使用 `x-api-key`。模型使用服务端的默认采样设置。兼容接口明确拒绝输出上限字段或流式用量选项时，应用会尝试调整对应参数。

## 联网搜索

在「设置 → 联网搜索」开启。每条消息都会由当前模型结合最近的对话判断是否需要联网，能理解「那旧版本呢」「再找一个来源」这样的追问。需要搜索时才提炼关键词并发送给搜索服务。

- 网页用 Tavily。API Key 留空即可使用免密钥搜索，有每日额度限制。也可以在 [Tavily 控制台](https://app.tavily.com) 免费申请自己的密钥，使用每月 1,000 积分的账户额度。馒头使用每次 1 积分的基础搜索。[免密钥说明](https://www.tavily.com/blog/give-nanoclaw-a-live-view-of-the-web-with-tavily-no-api-key-required) · [账户额度说明](https://docs.tavily.com/documentation/api-credits)
- 图片用 Wikimedia Commons，无需 API Key。生成或编辑项目时，符合条件的下载图片保存在 `assets/search/`，每张图片旁带有署名文件，预览和 ZIP 导出都使用这些本地素材。[Wikimedia Commons 图片接口](https://www.mediawiki.org/wiki/API:Imageinfo)

可以试试「联网查一下 Android WebView 的官方文档，把注意事项补进 README.md」，或「做一个班夫旅行网页，找几张可用的真实风景照当配图」。

## Linux 工作区

在带有运行环境的安装包里，打开「设置 → Linux 工作区」，选择一个工作区并导入 CSV 或 TSV。可以直接问「用 Python 把工作区里的 CSV 按学号去重，保存为 clean.csv」，也可以要求用处理结果生成小应用。长按普通工作区的输出文件，选择「导出文件」保存到你选的位置。

每次调用从干净的 Alpine Linux 启动快照创建一台离线虚拟机。只有脚本明确声明的输入会被复制进去，脚本以普通用户运行。单次输入总计最多 8 MiB，脚本执行上限为 45 秒，Android 另有 90 秒总时限。成功后可保存一个指定的输出文件，其余来宾文件随执行结束清除。输出留在 ManTou 的私有工作区，或写入项目计划已声明的文件并随 WebApp 发布和导出。退出码和截断后的脚本日志供当前模型判断是否需要重试。

开发构建使用 `./gradlew assembleDebug -PwithLinuxRuntime=true`，Windows 可用 `gradlew.bat`。首次准备需要 Python 3、Node.js 24 和网络，构建脚本会校验上游资产的 SHA-256，并生成干净的启动快照。运行脚本时无需联网。来宾内置 Python 标准库、SQLite 和 BusyBox Shell。

Linux 来宾使用 [Alpine Linux 软件包](https://gitlab.alpinelinux.org/alpine/aports/-/tree/3.23-stable) 和 [v86](https://github.com/copy/v86/tree/315b2b0)。构建脚本把使用的 v86 许可证副本放入 APK 资产。

## 网页应用如何运行

生成任务会制定项目计划，再分步读取、写入和验收文件。工作区分别保存草稿和发布版本，项目可导出为 ZIP。应用通过项目专属的 `.mantou/app-state.json` 保存运行状态。

网页应用通过 `window.MantouApp` 调用 Android 能力，包括剪贴板、相机、录音、语音播报、通知、闹钟、日历和存储。具体能力取决于系统版本、应用权限和设备支持。生成阶段的检查在独立 WebView 中运行，工具调用使用模拟结果。

## 从源码构建

使用 Android Studio 打开项目，安装 Android SDK 37。本项目在 JDK 21 环境验证构建。

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Windows 使用 `gradlew.bat`。Debug APK 位于 `app/build/outputs/apk/debug/`。

- 运行时和工具实现位于 [app/src/main/java/com/hfad/mantou](app/src/main/java/com/hfad/mantou)。
- KSP 模块位于 [tool-ksp](tool-ksp)，生成 Tool 注册表及接口文档。扩展 Android 能力时可参考 [Tool 开发指南](docs/tool-development.md)。
- 端侧意图识别的可选模型资产可按 [m3e-small 导出说明](app/src/main/assets/embedding/m3e-small/README.md) 本地生成。缺少模型资产时，应用使用本地规则，并在必要时调用已配置模型。
