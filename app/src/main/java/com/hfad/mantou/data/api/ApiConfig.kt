package com.hfad.mantou.data.api

import com.hfad.mantou.BuildConfig

/**
 * API 配置
 * 
 * 支持两种 API：
 * 1. DeepSeek - 纯文本对话（不支持图片）
 * 2. SiliconFlow - 支持视觉模型（Qwen-VL 等）
 * 
 * 使用说明：
 * 1. 在 local.properties 中配置 SILICONFLOW_API_KEY
 * 2. 根据需要切换 USE_VISION_API
 */
object ApiConfig {
    
    // 从 BuildConfig 读取 API Key（由 local.properties 注入）
    val API_KEY: String = BuildConfig.SILICONFLOW_API_KEY
    
    // ========== API 选择 ==========
    // true = 使用 SiliconFlow（支持图片识别）
    // false = 使用 DeepSeek（仅文本）
    const val USE_VISION_API = false  // 设置为 true 启用图片识别
    
    // ========== DeepSeek 配置 ==========
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/"
    const val DEEPSEEK_MODEL = "deepseek-chat"
    
    // ========== SiliconFlow 配置（支持视觉）==========
    const val SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/"
    // 视觉模型（支持图片识别）
    const val SILICONFLOW_VISION_MODEL = "Qwen/Qwen2-VL-72B-Instruct"
    // 文本模型
    const val SILICONFLOW_TEXT_MODEL = "Qwen/Qwen2.5-7B-Instruct"
    
    // ========== 当前使用的配置 ==========
    val BASE_URL: String
        get() = if (USE_VISION_API) SILICONFLOW_BASE_URL else DEEPSEEK_BASE_URL
    
    val DEFAULT_MODEL: String
        get() = if (USE_VISION_API) SILICONFLOW_TEXT_MODEL else DEEPSEEK_MODEL
    
    val VISION_MODEL: String
        get() = if (USE_VISION_API) SILICONFLOW_VISION_MODEL else DEEPSEEK_MODEL
    
    // 请求超时时间（秒）
    const val TIMEOUT_SECONDS = 120L  // 图片处理需要更长时间
    
    // 最大 tokens
    const val MAX_TOKENS = 4096
    
    // Temperature（0.0 - 2.0）
    const val TEMPERATURE = 0.7
    
    // Top P（0.0 - 1.0）
    const val TOP_P = 0.9
    
    // 图片相关配置
    const val MAX_IMAGE_SIZE = 1024  // 图片最大尺寸（像素）
    const val IMAGE_QUALITY = 85     // JPEG 压缩质量
    const val MAX_IMAGE_COUNT = 4    // 单次最多发送图片数量
    
    /**
     * 根据是否有图片选择合适的模型
     */
    fun getModelForRequest(hasImages: Boolean): String {
        return if (hasImages && USE_VISION_API) {
            VISION_MODEL
        } else {
            DEFAULT_MODEL
        }
    }
    
    /**
     * 检查当前配置是否支持图片
     */
    fun isVisionSupported(): Boolean = USE_VISION_API
}

