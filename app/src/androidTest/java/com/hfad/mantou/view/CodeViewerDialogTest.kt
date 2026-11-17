package com.hfad.mantou.view

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hfad.mantou.R
import com.hfad.mantou.data.GenerateTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class CodeViewerDialogTest {
    @Test
    fun switchesNestedFilesAndKeepsSelectionWhenProjectUpdates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "source-preview").toFile()
        val draft = File(root, "draft")
        val release = File(root, "release")
        val script = "src/components/main.js"
        val entry = "pages/index.html"
        val state = GenerateTaskState(
            sessionId = 1,
            phase = GenerateTaskState.Phase.APPLYING_TOOL,
            code = "<main>Draft</main>",
            filePath = File(draft, entry).absolutePath,
            activeFilePath = entry,
            projectFiles = listOf(entry, script),
            status = "正在生成",
            projectContentRoot = draft.absolutePath
        )
        File(draft, script).apply {
            parentFile!!.mkdirs()
            writeText("const version = 'draft'")
        }
        File(release, script).apply {
            parentFile!!.mkdirs()
            writeText("const version = 'release'")
        }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var dialog: CodeViewerDialog
                scenario.onActivity { activity ->
                    dialog = CodeViewerDialog(activity, activity.lifecycleScope, state.sessionId)
                    dialog.updateProject(state)
                    dialog.show()
                }
                try {
                    scenario.onActivity {
                        assertEquals(state.code, dialog.codeText())
                        dialog.findViewById<View>(R.id.btnCodeEditorFiles).performClick()
                    }
                    clickTreeRow(scenario, dialog, "src")
                    clickTreeRow(scenario, dialog, "components")
                    clickTreeRow(scenario, dialog, "main.js")
                    awaitCode(scenario, dialog, "const version = 'draft'")

                    scenario.onActivity {
                        dialog.updateProject(state.copy(code = "<main>Updated draft</main>"))
                        assertEquals("const version = 'draft'", dialog.codeText())
                        dialog.updateProject(state.copy(
                            phase = GenerateTaskState.Phase.COMPLETED,
                            code = "<main>Published</main>",
                            filePath = File(release, entry).absolutePath,
                            projectContentRoot = release.absolutePath
                        ))
                    }
                    awaitCode(scenario, dialog, "const version = 'release'")

                    scenario.onActivity {
                        dialog.findViewById<View>(R.id.btnCodeEditorFiles).performClick()
                        dialog.onBackPressedDispatcher.onBackPressed()
                        assertTrue(dialog.isShowing)
                        assertFalse(dialog.findViewById<View>(R.id.codeEditorFilePanel).isShown)
                        dialog.findViewById<View>(R.id.btnCodeEditorFiles).performClick()
                        dialog.findViewById<View>(R.id.btnCodeEditorFollow).performClick()
                        assertEquals("<main>Published</main>", dialog.codeText())
                    }
                } finally {
                    scenario.onActivity { dialog.dismiss() }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun clickTreeRow(scenario: ActivityScenario<MainActivity>, dialog: CodeViewerDialog, name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity {
            val tree = dialog.findViewById<RecyclerView>(R.id.rvCodeEditorFiles)
            val row = (0 until tree.childCount).asSequence().map(tree::getChildAt).first { child ->
                child.findViewById<TextView>(R.id.tvNodeName).text.toString() == name
            }
            assertTrue(row.performClick())
        }
    }

    private fun awaitCode(scenario: ActivityScenario<MainActivity>, dialog: CodeViewerDialog, expected: String) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var actual = ""
        do {
            scenario.onActivity { actual = dialog.codeText() }
            if (actual == expected) return
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals(expected, actual)
    }

    private fun CodeViewerDialog.codeText(): String = findViewById<TextView>(R.id.tvCodeEditorContent).text.toString()
}
