package com.hfad.mantou.data.preferences

import android.content.Context
import com.hfad.mantou.utils.AppGenerator

/**
 * App 生成上下文 token 阈值配置。
 */
object ContextLimitStore {
    const val MIN_TOKEN_LIMIT = 10_000
    const val MAX_TOKEN_LIMIT = 1_000_000
    const val DEFAULT_TOKEN_LIMIT = AppGenerator.APP_GEN_MAX_TOKENS
    val TOKEN_OPTIONS = intArrayOf(32_000, 64_000, 128_000, 256_000, 512_000, 1_000_000)

    private const val PREF_NAME = "context_limit_pref"
    private const val KEY_TOKEN_LIMIT = "token_limit"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getTokenLimit(context: Context): Int {
        val saved = prefs(context).getInt(KEY_TOKEN_LIMIT, DEFAULT_TOKEN_LIMIT)
        return saved.coerceIn(MIN_TOKEN_LIMIT, MAX_TOKEN_LIMIT)
    }

    fun setTokenLimit(context: Context, limit: Int) {
        prefs(context).edit()
            .putInt(KEY_TOKEN_LIMIT, limit.coerceIn(MIN_TOKEN_LIMIT, MAX_TOKEN_LIMIT))
            .apply()
    }
}
