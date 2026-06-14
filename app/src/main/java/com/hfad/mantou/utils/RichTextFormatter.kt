package com.hfad.mantou.utils

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

object RichTextFormatter {

    data class Palette(
        val textColor: Int,
        val secondaryColor: Int,
        val accentColor: Int,
        val codeBackgroundColor: Int,
        val codeTextColor: Int
    )

    fun format(raw: String, palette: Palette): CharSequence {
        if (raw.isEmpty()) return ""

        val out = SpannableStringBuilder()
        val lines = raw.replace("\r\n", "\n").split('\n')
        var inCodeBlock = false

        lines.forEachIndexed { index, originalLine ->
            val line = originalLine.trimEnd()
            if (line.trimStart().startsWith("```")) {
                inCodeBlock = !inCodeBlock
                if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                return@forEachIndexed
            }

            val lineStart = out.length
            if (inCodeBlock) {
                out.append(line.ifEmpty { " " })
                applyCodeSpan(out, lineStart, out.length, palette)
            } else {
                appendFormattedLine(out, line, palette)
            }

            if (index != lines.lastIndex) out.append('\n')
        }

        applyInlineMarks(out, palette)
        return out
    }

    private fun appendFormattedLine(
        out: SpannableStringBuilder,
        line: String,
        palette: Palette
    ) {
        val heading = Regex("^(#{1,6})\\s+(.+)$").find(line)
        if (heading != null) {
            val level = heading.groupValues[1].length
            val text = heading.groupValues[2]
            val start = out.length
            out.append(text)
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(
                RelativeSizeSpan(when (level) {
                    1 -> 1.24f
                    2 -> 1.16f
                    else -> 1.08f
                }),
                start,
                out.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            out.setSpan(ForegroundColorSpan(palette.accentColor), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }

        val quote = Regex("^>\\s?(.*)$").find(line)
        if (quote != null) {
            val start = out.length
            out.append("│ ").append(quote.groupValues[1])
            out.setSpan(ForegroundColorSpan(palette.secondaryColor), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(LeadingMarginSpan.Standard(8, 16), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }

        val bullet = Regex("^\\s*[-*+]\\s+(.+)$").find(line)
        if (bullet != null) {
            val start = out.length
            out.append("• ").append(bullet.groupValues[1])
            out.setSpan(LeadingMarginSpan.Standard(6, 22), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }

        val numbered = Regex("^\\s*(\\d+[.)])\\s+(.+)$").find(line)
        if (numbered != null) {
            val start = out.length
            out.append(numbered.groupValues[1]).append(' ').append(numbered.groupValues[2])
            out.setSpan(LeadingMarginSpan.Standard(6, 24), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }

        out.append(line)
    }

    private fun applyInlineMarks(out: SpannableStringBuilder, palette: Palette) {
        applyDelimitedSpan(out, Regex("\\*\\*(.+?)\\*\\*"), 2) { start, end ->
            out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        applyDelimitedSpan(out, Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), 1) { start, end ->
            out.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        applyDelimitedSpan(out, Regex("`([^`\\n]+?)`"), 1) { start, end ->
            applyCodeSpan(out, start, end, palette)
        }
    }

    private fun applyDelimitedSpan(
        out: SpannableStringBuilder,
        regex: Regex,
        delimiterLength: Int,
        apply: (Int, Int) -> Unit
    ) {
        var searchStart = 0
        while (searchStart < out.length) {
            val match = regex.find(out, searchStart) ?: break
            val start = match.range.first
            val endExclusive = match.range.last + 1
            if (out.getSpans(start, endExclusive, BackgroundColorSpan::class.java).isNotEmpty()) {
                searchStart = endExclusive
                continue
            }
            val contentStart = start + delimiterLength
            val contentEnd = endExclusive - delimiterLength
            out.delete(contentEnd, endExclusive)
            out.delete(start, contentStart)
            val newEnd = contentEnd - delimiterLength
            apply(start, newEnd)
            searchStart = newEnd
        }
    }

    private fun applyCodeSpan(
        out: SpannableStringBuilder,
        start: Int,
        end: Int,
        palette: Palette
    ) {
        if (start >= end) return
        out.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(palette.codeTextColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(BackgroundColorSpan(palette.codeBackgroundColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(RelativeSizeSpan(0.94f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
