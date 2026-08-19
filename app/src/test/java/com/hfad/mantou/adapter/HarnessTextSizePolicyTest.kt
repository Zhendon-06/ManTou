package com.hfad.mantou.adapter

import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessTextSizePolicyTest {

    @Test
    fun `default chat size keeps harness hierarchy compact`() {
        val sizes = HarnessTextSizePolicy.resolve(14f)

        assertEquals(14f, sizes.bodySp)
        assertEquals(13f, sizes.eventSp)
        assertEquals(10f, sizes.metadataSp)
        assertEquals(12f, sizes.diagnosticsSp)
    }

    @Test
    fun `configured size is clamped and all harness text follows it`() {
        assertEquals(
            HarnessTextSizes(12f, 12f, 10f, 10f),
            HarnessTextSizePolicy.resolve(8f)
        )
        assertEquals(
            HarnessTextSizes(22f, 21f, 18f, 20f),
            HarnessTextSizePolicy.resolve(30f)
        )
    }
}
