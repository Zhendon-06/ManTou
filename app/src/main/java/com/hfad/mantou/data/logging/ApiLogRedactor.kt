package com.hfad.mantou.data.logging

import okhttp3.HttpUrl

object ApiLogRedactor {

    private val sensitiveFieldPattern = Regex(
        "(?i)([\\\"](?:api[_-]?key|authorization|access[_-]?token|token|secret|password)[\\\"]\\s*:\\s*[\\\"])([^\\\"]*)([\\\"])"
    )
    private val dataUrlPattern = Regex("(?i)data:[^,\\\"\\s]+,[A-Za-z0-9+/=_-]*")
    private val base64DataFieldPattern = Regex(
        "(?i)([\\\"]data[\\\"]\\s*:\\s*[\\\"])([A-Za-z0-9+/=_-]{128,})(?=[\\\"]|$)"
    )
    private val sensitiveQueryNames = setOf(
        "api_key",
        "apikey",
        "key",
        "token",
        "access_token",
        "authorization",
        "secret",
        "password"
    )

    fun redactBody(value: String): String {
        return value
            .replace(sensitiveFieldPattern, "$1***$3")
            .replace(dataUrlPattern, "[data URL 已省略]")
            .replace(base64DataFieldPattern, "$1[Base64 已省略]")
    }

    fun redactUrl(url: HttpUrl): String {
        if (url.queryParameterNames.none(::isSensitiveQueryName)) return url.toString()
        val builder = url.newBuilder()
        url.queryParameterNames.forEach { name ->
            if (isSensitiveQueryName(name)) {
                builder.setQueryParameter(name, "***")
            }
        }
        return builder.build().toString()
    }

    private fun isSensitiveQueryName(name: String): Boolean {
        return name.lowercase() in sensitiveQueryNames
    }
}
