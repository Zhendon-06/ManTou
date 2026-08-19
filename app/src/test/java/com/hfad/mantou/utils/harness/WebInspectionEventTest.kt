package com.hfad.mantou.utils.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WebInspectionEventTest {

    @Test
    fun textSummaryStoresOnlyLengthAndSha256() {
        val summary = summarizeWebInspectionText("secret-content")

        assertEquals(14, summary.characterCount)
        assertEquals(
            "ca36af0056ea1b203c097393458357da986bae4cc88ac7bda03fe744c92685d3",
            summary.sha256
        )
        assertFalse(summary.toString().contains("secret-content"))
    }

    @Test
    fun urlSummaryLimitsValueButFingerprintsOriginalUrl() {
        val url = "https://mantou.local/" + "a".repeat(300) + "private-tail"

        val summary = summarizeWebInspectionUrl(url)

        assertEquals(WEB_INSPECTION_EVENT_URL_LIMIT, summary.value?.length)
        assertEquals(url.length, summary.characterCount)
        assertEquals(summarizeWebInspectionText(url).sha256, summary.sha256)
        assertFalse(summary.value.orEmpty().contains("private-tail"))
    }

    @Test
    fun nullUrlSummaryDoesNotInventMetadata() {
        val summary = summarizeWebInspectionUrl(null)

        assertNull(summary.value)
        assertNull(summary.characterCount)
        assertNull(summary.sha256)
    }
}
