package com.hfad.mantou.data.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hfad.mantou.data.api.ChatHttpRequest.OpenAiChatBody
import com.hfad.mantou.data.api.ChatHttpRequest.postOpenAiBody
import okhttp3.Interceptor
import okhttp3.Response

internal class ChatParameterCompatibility : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val original = request.tag(OpenAiChatBody::class.java)
        var response = chain.proceed(request)
        if (original == null || !response.isValidationRejection) return response

        val payload = original.json.deepCopy()
        var tokenFieldSwitched = false
        // A retry is only possible after an HTTP validation rejection, before any stream was accepted.
        repeat(2) {
            if (!response.isValidationRejection || chain.call().isCanceled()) return response
            val errorBody = runCatching { response.peekBody(4096).string() }.getOrDefault("")
            val rejection = ParameterRejection(errorBody)
            val tokenField = if (payload.has("max_completion_tokens")) "max_completion_tokens" else "max_tokens"
            when {
                payload.has("stream_options") && rejection.rejects("stream_options", allowValueError = true) ->
                    payload.remove("stream_options")

                !tokenFieldSwitched && payload.has(tokenField) && rejection.rejects(tokenField) -> {
                    val alternate = if (tokenField == "max_tokens") "max_completion_tokens" else "max_tokens"
                    payload.add(alternate, payload.remove(tokenField))
                    tokenFieldSwitched = true
                }

                else -> return response
            }
            response.close()
            // Keep the diagnostic ID for the logical call. The logging interceptor records each attempt.
            response = chain.proceed(request.newBuilder().postOpenAiBody(payload).build())
        }
        return response
    }

    private class ParameterRejection(body: String) {
        private val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        private val error = root?.get("error")?.takeIf { it.isJsonObject }?.asJsonObject ?: root
        private val parameter = error?.string("param")?.substringBefore('.')?.substringBefore('[')
        private val code = error?.string("code") ?: error?.string("type")
        private val message = error?.string("message") ?: root?.string("detail") ?: body

        fun rejects(field: String, allowValueError: Boolean = false): Boolean {
            if (parameter != null && parameter != field) return false
            val namesField = parameter == field ||
                Regex("(?<![\\w.])${Regex.escape(field)}(?!\\w)", RegexOption.IGNORE_CASE).containsMatchIn(message)
            val rejectsValue = code in invalidValueCodes || valueError.containsMatchIn(message)
            val rejectsParameter = code in unsupportedCodes || unsupported.containsMatchIn(message)
            if (namesField && (allowValueError || !rejectsValue) &&
                (rejectsParameter || (allowValueError && code == "unsupported_value"))
            ) return true

            // Some OpenAI-compatible gateways return FastAPI's structured 422 error.
            val errors = root?.get("detail")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
            return errors.any { detail ->
                val item = detail.takeIf { it.isJsonObject }?.asJsonObject
                val location = item?.get("loc")?.takeIf { it.isJsonArray }?.asJsonArray
                item?.string("type") in extraFieldTypes && location != null &&
                    location.size() >= 2 && location[0].toString() == "\"body\"" &&
                    location[1].toString() == "\"$field\""
            }
        }

        private fun JsonObject.string(field: String): String? =
            get(field)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        private companion object {
            val unsupportedCodes = setOf("unsupported_parameter", "unknown_parameter", "unrecognized_parameter")
            val invalidValueCodes = setOf("unsupported_value", "invalid_value", "invalid_parameter_value")
            val extraFieldTypes = setOf("extra_forbidden", "value_error.extra")
            val unsupported = Regex(
                """\b(unsupported|unknown|unrecognized|unexpected|not supported|does not support|not allowed|not permitted)\b|不支持|未知参数""",
                RegexOption.IGNORE_CASE
            )
            val valueError = Regex(
                """\b(unsupported|invalid) value\b|\bnot allowed to (be|exceed)\b""",
                RegexOption.IGNORE_CASE
            )
        }
    }

    private val Response.isValidationRejection: Boolean get() = code == 400 || code == 422
}
