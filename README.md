# BanaMusic

🎵 一款现代Android音乐播放器应用，专注于提供流畅的音乐播放体验与精美的视觉设计。

## 🌟 功能特性

### 核心功能

- **多源音乐管理**：支持本地音乐扫描、网络音乐搜索及在线播放
- **系统级集成**：前台服务确保后台播放，支持锁屏控制、耳机线控
- **媒体通知**：实时显示歌曲信息、播放控制和进度条
- **歌词显示**：支持逐行高亮、平滑滚动和点击定位
- **播放控制**：上一首、下一首、播放/暂停、进度拖动
- **播放模式**：顺序播放、随机播放、单曲循环
- **收藏功能**：一键收藏喜欢的歌曲
- **历史记录**：自动记录播放历史

### 技术亮点

- **现代Android架构**：MVVM + Repository + Kotlin协程
- **响应式UI**：LiveData + Flow实现数据与UI的响应式解耦
- **图片加载**：Coil三级缓存策略，优化专辑封面加载
- **网络请求**：Retrofit + OkHttp实现网络音乐搜索
- **数据持久化**：Room数据库存储播放列表和音乐元数据
- **Jetpack Compose**：部分UI采用Compose实现
- **状态栏适配**：沉浸式状态栏，适配各种屏幕尺寸

## 🛠 技术栈

| 技术                | 版本      | 用途     |
| ----------------- | ------- | ------ |
| Kotlin            | 1.8.0+  | 主要开发语言 |
| Android SDK       | API 21+ | 应用开发框架 |
| MVVM              | -       | 架构模式   |
| Room              | 2.5.0+  | 本地数据库  |
| Retrofit          | 2.9.0+  | 网络请求   |
| Coil              | 2.3.0+  | 图片加载   |
| Kotlin Coroutines | 1.6.0+  | 异步处理   |
| Jetpack Compose   | 1.2.0+  | UI组件   |
| MediaSession      | -       | 媒体控制   |

## 📦 安装指南

### 环境要求

- Android Studio Arctic Fox 2020.3.1+
- Android SDK 21+ (Android 5.0+)
- JDK 11+

### 安装步骤

1. **克隆仓库**
   ```bash
   git clone https://github.com/yourusername/BanaMusic.git
   cd BanaMusic
   ```
2. **打开项目**
   - 在Android Studio中选择"Open an existing project"
   - 选择项目目录
3. **同步依赖**
   - 等待Gradle自动同步完成
   - 或手动点击"Sync Project with Gradle Files"
4. **运行项目**
   - 连接Android设备或启动模拟器
   - 点击"Run"按钮运行应用

## 🎮 使用说明

### 基本操作

1. **主界面**：显示音乐列表，支持滑动浏览
2. **播放控制**：点击歌曲进入全屏播放界面
3. **歌词显示**：在播放界面点击唱片切换到歌词视图
4. **播放模式**：点击模式按钮切换播放模式
5. **收藏歌曲**：点击爱心图标收藏/取消收藏

### 高级功能

- **歌词定位**：点击歌词任意行跳转到对应播放位置
- **进度拖动**：通过进度条调整播放进度
- **后台播放**：应用退到后台时音乐继续播放
- **锁屏控制**：在锁屏界面控制音乐播放

## 📁 项目结构

```
BanaMusic/
├── app/
│   ├── src/main/
│   │   ├── java/com/guet/stu/banamusic/
│   │   │   ├── adapter/         # 适配器
│   │   │   ├── model/            # 数据模型
│   │   │   ├── network/          # 网络请求
│   │   │   ├── service/          # 服务
│   │   │   ├── util/             # 工具类
│   │   │   ├── view/             # 视图
│   │   │   ├── viewmodel/        # 视图模型
│   │   │   └── MainApplication.kt
│   │   ├── res/                  # 资源文件
│   │   └── AndroidManifest.xml   # 应用配置
│   └── build.gradle              # 模块配置
├── build.gradle                  # 项目配置
└── README.md                     # 项目说明
```

## 🔧 核心模块

### 1. 音乐播放管理 (`MusicPlay`)

- 统一管理MediaPlayer生命周期
- 处理播放状态、进度更新
- 支持播放列表管理
- 集成前台服务

### 2. 前台服务 (`MusicPlaybackService`)

- 确保后台音乐持续播放
- 显示媒体通知
- 与系统MediaSession集成
- 支持耳机线控和锁屏控制

### 3. 歌词处理

- 异步加载歌词
- 实时高亮当前歌词
- 平滑滚动效果
- 点击定位功能

### 4. 网络音乐搜索

- 基于Retrofit的网络请求
- 音乐数据解析
- 异步加载专辑封面

## 🎨 设计特点

### 视觉设计

- **现代UI**：简洁美观的界面设计
- **动态效果**：平滑的过渡动画
- **响应式布局**：适配各种屏幕尺寸
- **深色模式**：支持系统深色模式

### 用户体验

- **流畅播放**：无卡顿的音乐播放体验
- **智能缓存**：减少网络请求，提升加载速度
- **直观操作**：简单易用的控制界面
- **系统集成**：与Android系统深度集成

## 🔍 常见问题

### Q: 为什么音乐无法后台播放？

A: 请确保应用具有后台运行权限，MIUI等定制系统可能需要额外设置。

### Q: 为什么某些歌曲没有歌词？

A: 歌词由网络API提供，部分歌曲可能没有歌词数据。

### Q: 如何添加本地音乐？

A: 应用会自动扫描设备中的音乐文件，确保音乐文件位于设备存储中。

### Q: 为什么专辑封面无法显示？

A: 可能是网络连接问题或API限制，应用会使用默认封面作为 fallback。

## 🤝 贡献指南

欢迎贡献代码、报告问题或提出建议！

### 贡献流程

1. Fork 项目
2. 创建功能分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 打开 Pull Request

### 代码规范

- 遵循Kotlin官方代码风格
- 提交前运行代码检查
- 编写清晰的注释
- 确保代码测试覆盖

## 📄 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

## 🙏 致谢

- [Coil](https://coil-kt.github.io/coil/) - 图片加载库
- [Retrofit](https://square.github.io/retrofit/) - 网络请求库
- [Room](https://developer.android.com/training/data-storage/room) - 本地数据库
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - UI工具包

## 📞 联系方式

- 作者：Your Name
- GitHub：[yourusername](https://github.com/yourusername)
- Email：<your.email@example.com>

***

**享受音乐，享受生活！🎵**
