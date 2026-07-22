package com.hfad.mantou.tool

import com.hfad.mantou.tool.generated.GeneratedMantouToolsDoc
import com.hfad.mantou.tool.generated.GeneratedToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedToolMetadataTest {

    @Test
    fun kspGeneratesDocumentAndRuntimeRegistry() {
        assertEquals(
            listOf("alarm", "calendar", "camera", "clipboard", "flashlight", "storage", "toast", "vibration"),
            GeneratedMantouToolsDoc.documentedToolNames
        )
        assertEquals(
            listOf("alarm", "calendar", "camera", "clipboard", "flashlight", "toast", "vibration"),
            GeneratedToolRegistry.toolNames
        )
        assertTrue(GeneratedMantouToolsDoc.markdown.contains("window.MantouApp.alarm.alarmSet"))
        assertTrue(GeneratedMantouToolsDoc.markdown.contains("window.MantouApp.storage.storageWrite"))
        assertEquals(GeneratedMantouToolsDoc.documentedToolNames, GeneratedMantouToolsDoc.tools.map { it.name })
        assertTrue(GeneratedMantouToolsDoc.tools.first { it.name == "camera" }.retrievalText.contains("拍照"))
        assertTrue(
            GeneratedMantouToolsDoc.tools
                .first { it.name == "calendar" }
                .markdown
                .contains("window.MantouApp.calendar.calendarAdd")
        )
        assertFalse(GeneratedToolRegistry.toolNames.contains("storage"))
    }
}
