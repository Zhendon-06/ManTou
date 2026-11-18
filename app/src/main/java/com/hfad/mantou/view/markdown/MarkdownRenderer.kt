package com.hfad.mantou.view.markdown

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.TextView
import android.widget.Toast
import com.hfad.mantou.R
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.CoreProps
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.core.factory.LinkSpanFactory
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.parser.Parser
import kotlin.math.roundToInt

internal fun createMarkdownRenderer(context: Context): Markwon = Markwon.builder(context)
    .usePlugin(CorePlugin.create().hasExplicitMovementMethod(true))
    .usePlugin(StrikethroughPlugin.create())
    .usePlugin(TaskListPlugin.create(context))
    .usePlugin(ChatMarkdownPlugin(context))
    .bufferType(TextView.BufferType.SPANNABLE)
    .fallbackToRawInputWhenEmpty(true)
    .build()

private class ChatMarkdownPlugin(context: Context) : AbstractMarkwonPlugin() {
    private val density = context.resources.displayMetrics.density
    private val codeTextColor = context.getColor(R.color.mt_code_text)
    private val codeBackgroundPaint = Paint().apply { color = context.getColor(R.color.mt_code_bg) }

    override fun configureParser(builder: Parser.Builder) {
        builder.extensions(listOf(TablesExtension.create()))
    }

    override fun configureTheme(builder: MarkwonTheme.Builder) {
        builder
            .blockMargin((16 * density).roundToInt())
            .blockQuoteWidth((3 * density).roundToInt())
            .headingTextSizeMultipliers(floatArrayOf(1.34f, 1.24f, 1.16f, 1.10f, 1.06f, 1.02f))
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(SoftLineBreak::class.java) { visitor, _ -> visitor.ensureNewLine() }
        builder.on(Code::class.java) { visitor, code ->
            appendCode(visitor, code.literal)
        }
        builder.on(FencedCodeBlock::class.java) { visitor, code ->
            appendCodeBlock(visitor, code, code.literal)
        }
        builder.on(IndentedCodeBlock::class.java) { visitor, code ->
            appendCodeBlock(visitor, code, code.literal)
        }
        builder.on(HtmlInline::class.java) { visitor, html ->
            visitor.builder().append(html.literal)
        }
        builder.on(HtmlBlock::class.java) { visitor, html ->
            visitor.blockStart(html)
            visitor.builder().append(html.literal)
            visitor.blockEnd(html)
        }
        // Keep every cell as text in the same selection buffer as the surrounding paragraphs.
        builder.on(TableBlock::class.java) { visitor, table ->
            visitor.blockStart(table)
            visitor.visitChildren(table)
            visitor.blockEnd(table)
        }
        builder.on(TableRow::class.java) { visitor, row ->
            visitor.ensureNewLine()
            visitor.visitChildren(row)
        }
        builder.on(TableCell::class.java) { visitor, cell ->
            if (cell.previous != null) visitor.builder().append("  │  ")
            val start = visitor.length()
            visitor.visitChildren(cell)
            if (cell.isHeader && start < visitor.length()) {
                visitor.builder().setSpan(
                    StyleSpan(Typeface.BOLD), start, visitor.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun appendCode(visitor: MarkwonVisitor, code: String) {
        val start = visitor.length()
        visitor.builder().append(code)
        visitor.setSpans(
            start,
            arrayOf<Any>(TypefaceSpan("monospace"), ForegroundColorSpan(codeTextColor))
        )
    }

    private fun appendCodeBlock(visitor: MarkwonVisitor, node: Node, literal: String) {
        visitor.blockStart(node)
        val start = visitor.length()
        appendCode(visitor, literal.removeSuffix("\n"))
        // Draw in the layout's background pass so the native selection remains visible on code.
        val background = LineBackgroundSpan { canvas, _, left, right, top, _, bottom, _, _, _, _ ->
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), codeBackgroundPaint)
        }
        visitor.setSpans(start, arrayOf<Any>(background, LeadingMarginSpan.Standard((10 * density).roundToInt())))
        visitor.blockEnd(node)
    }

    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        val linkFactory = LinkSpanFactory()
        builder.setFactory(Link::class.java) { configuration, props ->
            val destination = CoreProps.LINK_DESTINATION.require(props)
            if (webUri(destination) != null) linkFactory.getSpans(configuration, props) else null
        }
    }

    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
        builder.linkResolver { view, link ->
            val uri = webUri(link) ?: return@linkResolver
            val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            try {
                view.context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(view.context, R.string.chat_markdown_cannot_open_link, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun webUri(destination: String): Uri? = Uri.parse(destination).normalizeScheme().takeIf {
        (it.scheme == "https" || it.scheme == "http") && !it.host.isNullOrBlank()
    }
}
