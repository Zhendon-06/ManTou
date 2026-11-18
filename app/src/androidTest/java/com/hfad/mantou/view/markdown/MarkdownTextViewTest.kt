package com.hfad.mantou.view.markdown

import android.app.Dialog
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hfad.mantou.R
import com.hfad.mantou.databinding.ItemChatAssistantBinding
import com.hfad.mantou.view.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownTextViewTest {
    @Test
    fun keepsMarkdownTableAndCodeInTheSelectableText() {
        val markdown = """
            # 标题

            **加粗**、*斜体*、~~删除线~~、`name`

            | 字段 | 说明 |
            | --- | --- |
            | 名称 | [主页](https://example.com) |

            ```kotlin
            val name = "**示例**"
            ```

            [本地文件](file:///some/path)
        """.trimIndent()
        withMessage(markdown) { scenario, view ->
            scenario.onActivity {
                val content = view.text as Spanned
                assertTrue(view.isTextSelectable)
                assertTrue(content.contains("加粗、斜体、删除线、name"))
                assertTrue(content.contains("字段  │  说明\n名称  │  主页"))
                assertTrue(content.contains("val name = \"**示例**\""))
                val link = content.getSpans(0, content.length, ClickableSpan::class.java).single()
                assertEquals("主页", content.subSequence(content.getSpanStart(link), content.getSpanEnd(link)).toString())
                assertTrue(content.contains("本地文件"))
            }
        }
    }

    @Test
    fun longPressSelectsLinkTextAndCopiesItWhileNewContentWaits() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
            },
            Instrumentation.ActivityResult(0, null),
            true
        )
        try {
            val markdown = "**Alpha** [Website](https://example.com)"
            withMessage(markdown) { scenario, view ->
                touchWebsite(Tap.LONG)
                scenario.onActivity { activity ->
                    assertTrue(view.hasSelection())
                    assertEquals(0, monitor.hits)
                    view.renderMarkdown("$markdown\n\n更新完成")
                    assertEquals("Alpha Website", view.text.toString())
                    assertTrue(view.onTextContextMenuItem(android.R.id.copy))
                    val clipboard = activity.getSystemService(ClipboardManager::class.java)
                    assertEquals("Website", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertFalse(view.hasSelection())
                    assertTrue(view.text.endsWith("更新完成"))
                }
                touchWebsite(Tap.SINGLE)
                assertEquals(1, monitor.hits)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun touchWebsite(tap: Tap) {
        onView(withId(R.id.tvMessage)).inRoot(isDialog()).perform(
            GeneralClickAction(tap, { rawView ->
                val view = rawView as MarkdownTextView
                val offset = view.text.indexOf("Website") + 2
                val line = view.layout.getLineForOffset(offset)
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                floatArrayOf(
                    location[0] + view.totalPaddingLeft + view.layout.getPrimaryHorizontal(offset),
                    (location[1] + view.totalPaddingTop +
                        (view.layout.getLineTop(line) + view.layout.getLineBottom(line)) / 2f)
                )
            }, Press.FINGER, 0, 0)
        )
    }

    private fun withMessage(
        markdown: String,
        verify: (ActivityScenario<MainActivity>, MarkdownTextView) -> Unit
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: Dialog
            lateinit var view: MarkdownTextView
            scenario.onActivity { activity ->
                val binding = ItemChatAssistantBinding.inflate(activity.layoutInflater)
                view = binding.tvMessage
                view.renderMarkdown(markdown)
                dialog = Dialog(activity).apply {
                    setContentView(binding.root)
                    show()
                    window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }
            try {
                verify(scenario, view)
            } finally {
                scenario.onActivity { dialog.dismiss() }
            }
        }
    }
}
