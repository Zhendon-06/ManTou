package com.hfad.mantou.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIntentDetectorTest {

    @Test
    fun localGateTreatsQuestionAsChat() {
        val result = AppIntentDetector.shouldUseLlmFallbackForTest(
            "你说模型有的漆上错了，是不是可以用洗笔液擦掉"
        )

        assertFalse(result.useLlm)
    }

    @Test
    fun localGateFallsBackForAmbiguousStrongAppTarget() {
        val result = AppIntentDetector.shouldUseLlmFallbackForTest("搞个 app 怎么样")

        assertTrue(result.useLlm)
    }

    @Test
    fun localGateSkipsFallbackForWeakTargetWithoutCreateAction() {
        val result = AppIntentDetector.shouldUseLlmFallbackForTest("天气为什么突然变冷了")

        assertFalse(result.useLlm)
    }

    @Test
    fun keywordGateRecognizesDirectGenerationRequests() {
        val messages = listOf(
            "帮我生成一个番茄钟网页应用",
            "能不能开发个记账网页",
            "build a habit tracker web app",
            "Can you build a web app?",
            "整个 H5 quiz",
            "写一个 todo application",
        )

        messages.forEach { message ->
            assertTrue(message, AppIntentDetector.isAppGenerationByKeywordsForTest(message))
        }
    }

    @Test
    fun keywordGateRejectsHardNegativeRequests() {
        val messages = listOf(
            "不要生成网页，只告诉我实现思路",
            "我在做一个游戏时遇到报错",
            "帮我分析做一个游戏需要哪些技术",
            "解释如何写一个计算器 app",
            "写一个天气分析报告",
            "这个游戏是哪个公司制作的",
            "你能生成 app 吗",
            "请问你能生成 app 吗",
            "make me happy",
            "How do I build a habit tracker app?",
            "整个应用看起来很漂亮",
        )

        messages.forEach { message ->
            assertFalse(message, AppIntentDetector.isAppGenerationByKeywordsForTest(message))
        }
    }

    @Test
    fun featureWordsAfterCreationActionRemainGenerationRequests() {
        val messages = listOf(
            "帮我做一个能分析股票的网页",
            "创建一个推荐电影的网页",
            "做个比较两组数据的工具",
            "创建一个教程网站",
            "做个报错查询工具",
            "做一个成本计算器",
        )

        messages.forEach { message ->
            assertTrue(message, AppIntentDetector.isAppGenerationByKeywordsForTest(message))
        }
        assertFalse(
            AppIntentDetector.isAppGenerationByKeywordsForTest("帮我分析做一个游戏需要哪些技术")
        )
        assertFalse(
            AppIntentDetector.isAppGenerationByKeywordsForTest("开发一个网站大概要多少钱")
        )
    }

    @Test
    fun generatedAppContextRecognizesShortFollowUp() {
        assertFalse(AppIntentDetector.isAppGenerationByKeywordsForTest("再加一个导出按钮"))
        assertTrue(
            AppIntentDetector.isAppGenerationByKeywordsForTest(
                message = "再加一个导出按钮",
                hasGeneratedAppInSession = true,
            )
        )
        assertTrue(
            AppIntentDetector.isAppGenerationByKeywordsForTest(
                message = "修复上个应用的报错",
                hasGeneratedAppInSession = true,
            )
        )
        assertTrue(
            AppIntentDetector.isAppGenerationByKeywordsForTest(
                message = "把刚才按钮改红",
                hasGeneratedAppInSession = true,
            )
        )
        assertFalse(
            AppIntentDetector.isAppGenerationByKeywordsForTest(
                message = "不要修改，先解释一下",
                hasGeneratedAppInSession = true,
            )
        )
        assertFalse(
            AppIntentDetector.isAppGenerationByKeywordsForTest(
                message = "解释一下按钮为什么这么小",
                hasGeneratedAppInSession = true,
            )
        )
    }

    @Test
    fun localGateFallsBackForImplicitInteractiveCreation() {
        val result = AppIntentDetector.shouldUseLlmFallbackForTest("给我整一个能记录喝水的")

        assertTrue(result.useLlm)
    }

    @Test
    fun parseIntentResultAcceptsOnlyExactEnum() {
        assertEquals(
            true,
            AppIntentDetector.parseIntentResultForTest("{\"intent\":\"generate_app\"}")
        )
        assertEquals(
            true,
            AppIntentDetector.parseIntentResultForTest(
                "```json\n{\"intent\":\"generate_app\"}\n```"
            )
        )
        assertEquals(false, AppIntentDetector.parseIntentResultForTest("{\"intent\":\"chat\"}"))
        assertNull(AppIntentDetector.parseIntentResultForTest("{\"intent\":\"not_generate_app\"}"))
        assertNull(AppIntentDetector.parseIntentResultForTest("generate_app"))
    }
}
