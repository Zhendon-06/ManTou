package com.hfad.mantou.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiEndpointResolverTest {

    @Test
    fun modelListUrlAppendsDefaultPathForRootBaseUrl() {
        assertEquals(
            "https://api.example.com/v1/models",
            ApiEndpointResolver.modelListUrl("https://api.example.com")
        )
    }

    @Test
    fun modelListUrlDoesNotDuplicateVersionSegment() {
        assertEquals(
            "https://api.example.com/v1/models",
            ApiEndpointResolver.modelListUrl("https://api.example.com/v1")
        )
    }

    @Test
    fun openAiChatUrlReplacesModelEndpoint() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            ApiEndpointResolver.openAiChatCompletionsUrl("https://api.example.com/v1/models")
        )
    }

    @Test
    fun modelListUrlReplacesOpenAiChatEndpoint() {
        assertEquals(
            "https://api.example.com/v1/models",
            ApiEndpointResolver.modelListUrl("https://api.example.com/v1/chat/completions")
        )
    }

    @Test
    fun anthropicUrlReplacesModelEndpoint() {
        assertEquals(
            "https://api.example.com/v1/messages",
            ApiEndpointResolver.anthropicMessagesUrl("https://api.example.com/v1/models")
        )
    }

    @Test
    fun urlWithoutSchemeDefaultsToHttps() {
        assertEquals(
            "https://api.example.com/v1/models",
            ApiEndpointResolver.modelListUrl("api.example.com/v1")
        )
    }

    @Test
    fun noVersionEndpointKeepsNoVersionStyleWhenSwitchingEndpoint() {
        assertEquals(
            "https://proxy.example.com/chat/completions",
            ApiEndpointResolver.openAiChatCompletionsUrl("https://proxy.example.com/models")
        )
    }
}
