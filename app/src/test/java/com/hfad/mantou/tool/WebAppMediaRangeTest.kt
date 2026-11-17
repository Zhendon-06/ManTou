package com.hfad.mantou.tool

import com.hfad.mantou.tool.impl.parseMediaRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebAppMediaRangeTest {
    @Test
    fun servesOpenEndedExplicitAndSuffixByteRanges() {
        assertEquals(0L..199L, parseMediaRange("bytes=0-", 200))
        assertEquals(40L..59L, parseMediaRange("bytes=40-59", 200))
        assertEquals(40L..199L, parseMediaRange("bytes=40-300", 200))
        assertEquals(190L..199L, parseMediaRange("bytes=-10", 200))
    }

    @Test
    fun rejectsRangesOutsideFileAndMultipleRanges() {
        assertNull(parseMediaRange("bytes=200-", 200))
        assertNull(parseMediaRange("bytes=100-40", 200))
        assertNull(parseMediaRange("bytes=0-1,3-4", 200))
        assertNull(parseMediaRange("bytes=-0", 200))
    }
}
