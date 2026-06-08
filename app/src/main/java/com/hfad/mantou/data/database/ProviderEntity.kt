package com.hfad.mantou.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 模型服务 Provider 实体
 *
 * apiFormat: "openai" 或 "anthropic"
 *   - openai     -> POST resolved chat/completions endpoint, Authorization: Bearer
 *   - anthropic  -> POST resolved messages endpoint, x-api-key + anthropic-version
 */
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true)
    val providerId: Long = 0,

    val name: String,

    val baseUrl: String = "",

    val apiKey: String = "",

    val apiFormat: String = API_FORMAT_OPENAI,

    val createTime: Long = System.currentTimeMillis()
) {
    companion object {
        const val API_FORMAT_OPENAI = "openai"
        const val API_FORMAT_ANTHROPIC = "anthropic"
    }
}
