package com.hfad.mantou.view

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hfad.mantou.databinding.ActivityJsonViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

class JsonViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJsonViewerBinding

    companion object {
        const val EXTRA_JSON_PATH = "json_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityJsonViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val jsonPath = intent.getStringExtra(EXTRA_JSON_PATH)
        val file = jsonPath?.let(::File)
        if (file == null || !file.exists()) {
            Toast.makeText(this, "JSON 文件不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.toolbar.title = file.name
        loadJson(file)
    }

    private fun loadJson(file: File) {
        lifecycleScope.launch {
            val contentResult = withContext(Dispatchers.IO) {
                runCatching { file.readText().ifBlank { "{}" } }
            }
            val content = contentResult.getOrElse {
                Toast.makeText(this@JsonViewerActivity, "读取失败: ${it.message}", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val prettyResult = runCatching { prettyPrintJson(content) }
            val displayText = prettyResult.getOrElse {
                Toast.makeText(this@JsonViewerActivity, "JSON 格式异常，已显示原始内容", Toast.LENGTH_SHORT).show()
                content
            }
            binding.tvJsonContent.text = formatJson(displayText)
        }
    }

    private fun prettyPrintJson(content: String): String {
        val tokener = JSONTokener(content.trim())
        val value = tokener.nextValue()
        if (tokener.nextClean().code != 0) {
            throw IllegalArgumentException("JSON 内容包含多余字符")
        }
        return when (value) {
            is JSONObject -> value.toString(2)
            is JSONArray -> value.toString(2)
            is String -> JSONObject.quote(value)
            null, JSONObject.NULL -> "null"
            else -> value.toString()
        }
    }

    private fun formatJson(json: String): CharSequence {
        val out = SpannableStringBuilder(json)
        if (out.isEmpty()) return out

        out.setSpan(TypefaceSpan("monospace"), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        applyRegexColor(out, JsonRegex.STRING, Color.rgb(42, 127, 86))
        applyRegexColor(out, JsonRegex.NUMBER, Color.rgb(125, 82, 178))
        applyRegexColor(out, JsonRegex.BOOLEAN_NULL, Color.rgb(204, 97, 47))
        applyRegexColor(out, JsonRegex.SYMBOL, Color.rgb(100, 111, 130))

        JsonRegex.KEY.findAll(json).forEach { match ->
            out.setSpan(
                ForegroundColorSpan(Color.rgb(35, 105, 204)),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            out.setSpan(
                StyleSpan(Typeface.BOLD),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return out
    }

    private fun applyRegexColor(out: SpannableStringBuilder, regex: Regex, color: Int) {
        regex.findAll(out).forEach { match ->
            out.setSpan(
                ForegroundColorSpan(color),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private object JsonRegex {
        val STRING = Regex(""""(?:\\.|[^"\\])*"""")
        val KEY = Regex(""""(?:\\.|[^"\\])*"(?=\s*:)""")
        val NUMBER = Regex("""(?<![\w.])-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?(?![\w.])""")
        val BOOLEAN_NULL = Regex("""\b(?:true|false|null)\b""")
        val SYMBOL = Regex("""[{}\[\],:]""")
    }
}
