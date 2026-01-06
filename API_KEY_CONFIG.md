# 馒头聊天应用 - API Key 配置说明

## 📝 配置步骤

### 1. 获取 SiliconFlow API Key

访问：https://cloud.siliconflow.cn/account/ak

### 2. 配置 local.properties

在项目根目录的 `local.properties` 文件中添加：

```properties
# SiliconFlow API Key
SILICONFLOW_API_KEY=sk-your-actual-api-key-here
```

**注意**：
- ⚠️ `local.properties` 文件不会被提交到 Git
- ⚠️ 不要在代码中直接写死 API Key
- ⚠️ 替换 `sk-your-actual-api-key-here` 为你的真实 Key

### 3. Sync Project

点击 Android Studio 的 "Sync Now" 同步项目

### 4. 运行应用

API Key 会自动注入到 `BuildConfig.SILICONFLOW_API_KEY`

---

## 🔧 技术实现

### build.gradle.kts

```kotlin
defaultConfig {
    // 从 local.properties 读取 API Key
    val properties = java.util.Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        properties.load(localPropertiesFile.inputStream())
    }
    
    val apiKey = properties.getProperty("SILICONFLOW_API_KEY") ?: ""
    buildConfigField("String", "SILICONFLOW_API_KEY", "\"$apiKey\"")
}

buildFeatures {
    buildConfig = true  // 启用 BuildConfig
}
```

### ApiConfig.kt

```kotlin
object ApiConfig {
    // 从 BuildConfig 读取 API Key
    val API_KEY: String = BuildConfig.SILICONFLOW_API_KEY
}
```

### AuthInterceptor.kt

```kotlin
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val newRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer ${ApiConfig.API_KEY}")
            .build()
        return chain.proceed(newRequest)
    }
}
```

---

## ✅ 优势

1. **安全性**：API Key 不会被提交到 Git
2. **灵活性**：每个开发者可以使用自己的 Key
3. **统一管理**：所有配置集中在 `local.properties`
4. **类型安全**：通过 BuildConfig 访问，编译时检查

---

## 🚫 禁止的做法

❌ 不要在代码中直接写死：
```kotlin
const val API_KEY = "sk-xxxxx"  // 错误！
```

✅ 正确的做法：
```kotlin
val API_KEY: String = BuildConfig.SILICONFLOW_API_KEY  // 正确！
```

---

## 📂 文件说明

| 文件 | 是否提交到 Git | 说明 |
|------|---------------|------|
| `local.properties` | ❌ 否 | 包含敏感信息，已在 .gitignore |
| `build.gradle.kts` | ✅ 是 | 读取配置的逻辑 |
| `ApiConfig.kt` | ✅ 是 | 使用 BuildConfig 读取 |

---

## 🔍 验证配置

运行应用后，在 Logcat 中搜索 `Authorization`，应该看到：

```
Authorization: Bearer sk-your-actual-api-key-here
```

如果看到空字符串或 `YOUR_API_KEY_HERE`，说明配置未生效，请检查：
1. `local.properties` 中是否正确配置
2. 是否执行了 Gradle Sync
3. 是否重新编译了项目

---

## 🎉 完成！

现在你的 API Key 已经安全地存储在 `local.properties` 中，不会被提交到版本控制系统。

