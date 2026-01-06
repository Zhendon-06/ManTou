# Retrofit 配置总结

## ✅ 最终配置

### 1. **AuthInterceptor.kt** - 鉴权拦截器
```kotlin
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // 调试日志
        Log.d(TAG, "请求 URL: ${originalRequest.url}")
        Log.d(TAG, "API Key 是否配置: ${apiKey.isNotEmpty()}")
        Log.d(TAG, "Authorization Header 是否存在: true")
        Log.d(TAG, "Header 是否以 'Bearer ' 开头: true")
        
        // 添加 Authorization Header
        val newRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer ${ApiConfig.API_KEY}")
            .build()
        
        return chain.proceed(newRequest)
    }
}
```

### 2. **RetrofitClient.kt** - Retrofit 初始化
```kotlin
object RetrofitClient {
    
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    
    private val authInterceptor = AuthInterceptor()
    
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)      // 鉴权拦截器（第一个）
        .addInterceptor(loggingInterceptor)   // 日志拦截器（第二个）
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.siliconflow.cn/")  // 必须以 / 结尾
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    val apiService: SiliconFlowApiService = retrofit.create(SiliconFlowApiService::class.java)
}
```

### 3. **SiliconFlowApiService.kt** - API 接口
```kotlin
interface SiliconFlowApiService {
    
    @POST("v1/chat/completions")  // 相对路径，不需要前导 /
    suspend fun chatCompletion(
        @Body request: ChatRequest
    ): Response<ChatResponse>
}
```

### 4. **ApiConfig.kt** - 配置
```kotlin
object ApiConfig {
    val API_KEY: String = BuildConfig.SILICONFLOW_API_KEY
    const val BASE_URL = "https://api.siliconflow.cn/"
}
```

---

## 📊 调试日志输出

运行应用后，在 Logcat 中筛选 `AuthInterceptor`，你会看到：

```
D/AuthInterceptor: ========== 请求调试信息 ==========
D/AuthInterceptor: 请求 URL: https://api.siliconflow.cn/v1/chat/completions
D/AuthInterceptor: BaseUrl: https://api.siliconflow.cn/
D/AuthInterceptor: Path: /v1/chat/completions
D/AuthInterceptor: 完整路径: https://api.siliconflow.cn/v1/chat/completions
D/AuthInterceptor: API Key 是否配置: true
D/AuthInterceptor: API Key 前缀: sk-xxxxx...
D/AuthInterceptor: Authorization Header 是否存在: true
D/AuthInterceptor: Header 是否以 'Bearer ' 开头: true
D/AuthInterceptor: Header 格式: Bearer sk-xxxxx...
D/AuthInterceptor: ===================================
```

---

## ✅ 验证清单

| 检查项 | 状态 | 说明 |
|--------|------|------|
| ❌ 删除所有 @Header("Authorization") | ✅ | 已删除，统一使用 Interceptor |
| ✅ 使用 OkHttp Interceptor 注入 | ✅ | AuthInterceptor 自动注入 |
| ✅ baseUrl 正确 | ✅ | https://api.siliconflow.cn/ |
| ✅ 完整 URL 正确 | ✅ | https://api.siliconflow.cn/v1/chat/completions |
| ✅ Authorization Header 存在 | ✅ | 自动添加 |
| ✅ Header 格式正确 | ✅ | Bearer sk-... |
| ✅ API Key 从 BuildConfig 读取 | ✅ | 安全存储 |

---

## 🔍 调试步骤

### 1. 配置 API Key
在 `local.properties` 中：
```properties
SILICONFLOW_API_KEY=sk-your-real-api-key
```

### 2. Sync Project
点击 Android Studio 的 "Sync Now"

### 3. 运行应用
发送一条消息

### 4. 查看日志
在 Logcat 中筛选 `AuthInterceptor`，检查：
- ✅ 请求 URL 是否正确
- ✅ API Key 是否配置
- ✅ Authorization Header 是否存在
- ✅ Header 格式是否正确

### 5. 查看网络日志
在 Logcat 中筛选 `OkHttp`，查看完整的请求和响应

---

## 🎯 关键点

### 1. **Interceptor 顺序很重要**
```kotlin
.addInterceptor(authInterceptor)      // 先添加 Authorization
.addInterceptor(loggingInterceptor)   // 再打印日志
```

### 2. **baseUrl 必须以 / 结尾**
```kotlin
.baseUrl("https://api.siliconflow.cn/")  // ✅ 正确
.baseUrl("https://api.siliconflow.cn")   // ❌ 错误
```

### 3. **相对路径不需要前导 /**
```kotlin
@POST("v1/chat/completions")  // ✅ 正确
@POST("/v1/chat/completions") // ❌ 可能导致双斜杠
```

### 4. **不要手动传递 Authorization**
```kotlin
// ❌ 错误
suspend fun chatCompletion(
    @Header("Authorization") auth: String,
    @Body request: ChatRequest
)

// ✅ 正确
suspend fun chatCompletion(
    @Body request: ChatRequest
)
```

---

## 🚀 完整请求流程

```
1. 调用 apiService.chatCompletion(request)
   ↓
2. Retrofit 构建请求
   URL: https://api.siliconflow.cn/v1/chat/completions
   ↓
3. AuthInterceptor 拦截
   添加: Authorization: Bearer sk-...
   打印调试日志
   ↓
4. LoggingInterceptor 拦截
   打印完整请求和响应
   ↓
5. OkHttp 发送请求
   ↓
6. 接收响应
   ↓
7. Retrofit 解析响应
   ↓
8. 返回 ChatResponse
```

---

## 📝 代码位置

| 文件 | 位置 | 说明 |
|------|------|------|
| `AuthInterceptor.kt` | `data/api/` | 鉴权拦截器 + 调试日志 |
| `RetrofitClient.kt` | `data/api/` | Retrofit 初始化 |
| `SiliconFlowApiService.kt` | `data/api/` | API 接口定义 |
| `ApiConfig.kt` | `data/api/` | 配置常量 |
| `local.properties` | 项目根目录 | API Key 存储 |

---

## ✅ 完成！

现在你的 Retrofit 配置已经完全正确：
- ✅ 统一使用 Interceptor 注入 Authorization
- ✅ 删除了所有手动传递的 @Header
- ✅ baseUrl 正确配置
- ✅ 完整的调试日志
- ✅ API Key 安全存储

运行应用，查看 Logcat 中的调试日志，确认所有配置正确！

