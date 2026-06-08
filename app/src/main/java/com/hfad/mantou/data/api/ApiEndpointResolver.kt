package com.hfad.mantou.data.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

object ApiEndpointResolver {

    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    private val knownEndpointSuffixes = listOf(
        EndpointSuffix("v1/chat/completions", versioned = true),
        EndpointSuffix("v1/messages", versioned = true),
        EndpointSuffix("v1/models", versioned = true),
        EndpointSuffix("chat/completions", versioned = false),
        EndpointSuffix("messages", versioned = false),
        EndpointSuffix("models", versioned = false)
    )

    fun openAiChatCompletionsUrl(baseUrl: String): String {
        return resolveEndpoint(
            baseUrl = baseUrl,
            defaultPath = "v1/chat/completions",
            noVersionPath = "chat/completions"
        )
    }

    fun anthropicMessagesUrl(baseUrl: String): String {
        return resolveEndpoint(
            baseUrl = baseUrl,
            defaultPath = "v1/messages",
            noVersionPath = "messages"
        )
    }

    fun modelListUrl(baseUrl: String): String {
        return resolveEndpoint(
            baseUrl = baseUrl,
            defaultPath = "v1/models",
            noVersionPath = "models"
        )
    }

    fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "Base URL 不能为空" }
        val normalized = if (schemeRegex.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        require(normalized.toHttpUrlOrNull() != null) { "Base URL 无效: $normalized" }
        return normalized
    }

    private fun resolveEndpoint(
        baseUrl: String,
        defaultPath: String,
        noVersionPath: String
    ): String {
        val normalized = normalizeBaseUrl(baseUrl)
        val baseWithoutQuery = stripQueryAndFragment(normalized).trimEnd('/')
        val lowerBase = baseWithoutQuery.lowercase(Locale.US)
        if (endsWithPath(lowerBase, defaultPath) || endsWithPath(lowerBase, noVersionPath)) {
            return baseWithoutQuery
        }

        val matchedEndpoint = knownEndpointSuffixes.firstOrNull { suffix ->
            endsWithPath(lowerBase, suffix.path)
        }
        val endpointBase = matchedEndpoint?.let { suffix ->
            baseWithoutQuery.dropLast(suffix.path.length + 1)
        } ?: baseWithoutQuery
        val endpointBaseLower = endpointBase.lowercase(Locale.US)
        val pathToAppend = when {
            endsWithPath(endpointBaseLower, "v1") -> defaultPath.removePrefix("v1/")
            matchedEndpoint?.versioned == false -> noVersionPath
            else -> defaultPath
        }
        return joinPath(endpointBase, pathToAppend)
    }

    private fun stripQueryAndFragment(url: String): String {
        val queryIndex = url.indexOf('?').takeIf { it >= 0 }
        val fragmentIndex = url.indexOf('#').takeIf { it >= 0 }
        val cutIndex = listOfNotNull(queryIndex, fragmentIndex).minOrNull() ?: url.length
        return url.substring(0, cutIndex)
    }

    private fun endsWithPath(value: String, path: String): Boolean {
        return value.endsWith("/${path.trim('/')}")
    }

    private fun joinPath(baseUrl: String, path: String): String {
        return "${baseUrl.trimEnd('/')}/${path.trim('/')}"
    }

    private data class EndpointSuffix(
        val path: String,
        val versioned: Boolean
    )
}
