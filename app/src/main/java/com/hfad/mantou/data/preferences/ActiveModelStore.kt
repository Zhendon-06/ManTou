package com.hfad.mantou.data.preferences

import android.content.Context

/**
 * 全局活跃模型持久化（SharedPreferences）。
 *
 * 记录用户在 ModelSettingActivity 中选中的 Provider + Model，
 * 聊天发起前 ChatViewModel 会读取这里决定走 OpenAI / Anthropic 哪条路径。
 */
object ActiveModelStore {
    private const val PREF_NAME = "active_model_pref"
    private const val KEY_PROVIDER_ID = "active_provider_id"
    private const val KEY_MODEL_NAME = "active_model_name"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun setActive(context: Context, providerId: Long, modelName: String) {
        prefs(context).edit()
            .putLong(KEY_PROVIDER_ID, providerId)
            .putString(KEY_MODEL_NAME, modelName)
            .apply()
    }

    fun getActiveProviderId(context: Context): Long? {
        val p = prefs(context)
        if (!p.contains(KEY_PROVIDER_ID)) return null
        val id = p.getLong(KEY_PROVIDER_ID, -1L)
        return if (id <= 0L) null else id
    }

    fun getActiveModelName(context: Context): String? =
        prefs(context).getString(KEY_MODEL_NAME, null)

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** 若当前 active 指向被删的 Provider 就清掉。 */
    fun clearIfMatches(context: Context, providerId: Long) {
        if (getActiveProviderId(context) == providerId) clear(context)
    }
}
