package com.hfad.mantou.utils

import org.junit.Assert.assertFalse
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
}
