# API 测试工具使用说明

## 🎯 功能说明

我已经创建了一个 API 测试工具，它会在应用启动时自动测试 SiliconFlow API 连接。

---

## 📋 测试内容

### 1. **API Key 检查**
- ✅ 检查 API Key 是否配置
- ✅ 检查 API Key 格式（是否以 'sk-' 开头）
- ✅ 检查 API Key 长度
- ✅ 检查是否包含空格或换行符

### 2. **最小测试请求**
```json
{
  "model": "Qwen/Qwen2.5-7B-Instruct",
  "messages": [
    {
      "role": "system",
      "content": "你是一个测试助手"
    },
    {
      "role": "user",
      "content": "你好"
    }
  ],
  "max_tokens": 100,
  "temperature": 0.7
}
```

### 3. **响应分析**
- ✅ HTTP 状态码
- ✅ 成功/失败判断
- ✅ 错误详情分析
- ✅ 401 错误专项诊断

---

## 🔍 使用方法

### 方法 1：自动测试（推荐）
应用启动时会自动测试，查看 Toast 提示：
- ✅ 成功：显示 "API 连接成功！"
- ❌ 失败：显示错误信息

### 方法 2：查看详细日志
在 Logcat 中筛选 `ApiTestHelper`，查看完整测试日志：

```
D/ApiTestHelper: ========== 开始 API 测试 ==========
D/ApiTestHelper: 1. 检查 API Key
D/ApiTestHelper:    - API Key 长度: 48
D/ApiTestHelper:    - API Key 前缀: sk-xxxxx
D/ApiTestHelper:    - 是否为默认值: false
D/ApiTestHelper: 2. 组装测试请求
D/ApiTestHelper:    - 模型: Qwen/Qwen2.5-7B-Instruct
D/ApiTestHelper:    - 消息数量: 2
D/ApiTestHelper: 3. 发送 API 请求
D/ApiTestHelper:    - URL: https://api.siliconflow.cn/v1/chat/completions
D/ApiTestHelper: 4. 分析响应
D/ApiTestHelper:    - HTTP 状态码: 200
D/ApiTestHelper:    - 是否成功: true
D/ApiTestHelper:    - ✅ 请求成功！
D/ApiTestHelper:    - 响应 ID: chatcmpl-xxxxx
D/ApiTestHelper:    - 模型: Qwen/Qwen2.5-7B-Instruct
D/ApiTestHelper:    - 回复内容: 你好！有什么我可以帮助你的吗？
D/ApiTestHelper: ===================================
```

---

## ✅ 成功示例

### HTTP 200 成功响应
```
D/ApiTestHelper: ✅ 请求成功！
D/ApiTestHelper: - 响应 ID: chatcmpl-xxxxx
D/ApiTestHelper: - 模型: Qwen/Qwen2.5-7B-Instruct
D/ApiTestHelper: - 回复内容: 你好！有什么我可以帮助你的吗？

Toast: ✅ API 连接成功！
```

---

## ❌ 错误诊断

### 401 认证失败

#### 可能原因 1：API Key 未配置
```
E/ApiTestHelper: ❌ 请求失败！
E/ApiTestHelper: - 错误码: 401
E/ApiTestHelper: - 错误信息: Invalid token

诊断结果：
- API Key 未配置！请在 local.properties 中设置 SILICONFLOW_API_KEY
```

**解决方法**：
1. 打开 `local.properties`
2. 添加：`SILICONFLOW_API_KEY=sk-your-real-key`
3. Sync Project
4. 重新运行

#### 可能原因 2：API Key 格式错误
```
诊断结果：
- API Key 格式错误（应以 'sk-' 开头）
- API Key 长度异常（可能不完整）
```

**解决方法**：
检查 API Key 是否完整复制，确保：
- ✅ 以 `sk-` 开头
- ✅ 长度通常为 40-50 个字符
- ✅ 没有多余的空格或换行符

#### 可能原因 3：API Key 无效或过期
```
诊断结果：
- API Key 无效或已过期
```

**解决方法**：
1. 访问 https://cloud.siliconflow.cn/account/ak
2. 检查 API Key 状态
3. 如果过期，生成新的 Key
4. 更新 `local.properties`

### 400 请求参数错误
```
E/ApiTestHelper: 400 请求参数错误
```

**可能原因**：
- 请求体格式错误
- 模型名称错误
- 参数超出范围

### 403 权限不足
```
E/ApiTestHelper: API Key 权限不足或已被禁用
```

**解决方法**：
检查 API Key 是否有访问该模型的权限

### 429 请求频率超限
```
E/ApiTestHelper: 请求频率超限，请稍后重试
```

**解决方法**：
等待一段时间后重试

### 500/502/503 服务器错误
```
E/ApiTestHelper: 服务器错误，请稍后重试
```

**解决方法**：
SiliconFlow 服务器问题，稍后重试

---

## 🔧 手动测试

如果需要手动触发测试，可以在任何地方调用：

```kotlin
lifecycleScope.launch {
    val result = ApiTestHelper.testApiConnection()
    
    when (result) {
        is ApiTestHelper.TestResult.Success -> {
            Log.d(TAG, "成功: ${result.message}")
            Log.d(TAG, "回复: ${result.response}")
        }
        is ApiTestHelper.TestResult.Error -> {
            Log.e(TAG, "失败: ${result.message}")
        }
    }
}
```

---

## 📊 完整诊断流程

```
1. 启动应用
   ↓
2. 自动执行 API 测试
   ↓
3. 检查 API Key 配置
   ├─ 未配置 → 显示错误
   ├─ 格式错误 → 显示错误
   └─ 配置正确 → 继续
   ↓
4. 组装测试请求
   ↓
5. 发送到 SiliconFlow API
   ↓
6. 分析响应
   ├─ 200 成功 → 显示成功 Toast
   ├─ 401 认证失败 → 详细诊断
   ├─ 400 参数错误 → 显示错误
   └─ 其他错误 → 显示错误
   ↓
7. 显示结果（Toast + Logcat）
```

---

## 🎯 常见问题排查

### Q1: 显示 "API Key 未配置"
**A**: 在 `local.properties` 中添加：
```properties
SILICONFLOW_API_KEY=sk-your-real-key
```

### Q2: 显示 "401 Invalid token"
**A**: 检查：
1. API Key 是否正确复制
2. 是否包含空格或换行符
3. API Key 是否已激活

### Q3: 显示 "网络异常"
**A**: 检查：
1. 设备是否联网
2. 是否能访问 https://api.siliconflow.cn
3. 防火墙是否拦截

### Q4: 没有任何提示
**A**: 查看 Logcat：
1. 筛选 `ApiTestHelper`
2. 查看详细错误日志
3. 筛选 `AuthInterceptor` 查看请求详情

---

## ✅ 验证清单

运行应用后，检查以下内容：

| 检查项 | 位置 | 预期结果 |
|--------|------|---------|
| Toast 提示 | 应用启动时 | "API 连接成功！" |
| ApiTestHelper 日志 | Logcat | 完整测试流程 |
| AuthInterceptor 日志 | Logcat | Authorization Header 正确 |
| HTTP 状态码 | Logcat | 200 |
| AI 回复内容 | Logcat | 有实际回复文本 |

---

## 🎉 成功标志

如果看到以下内容，说明配置完全正确：

```
✅ Toast: API 连接成功！
✅ Logcat: HTTP 状态码: 200
✅ Logcat: 回复内容: 你好！有什么我可以帮助你的吗？
```

现在可以正常使用聊天功能了！

