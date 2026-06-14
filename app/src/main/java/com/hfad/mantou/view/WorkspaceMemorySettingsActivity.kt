package com.hfad.mantou.view

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.hfad.mantou.databinding.ActivityWorkspaceMemorySettingsBinding
import com.hfad.mantou.utils.AgentWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WorkspaceMemorySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWorkspaceMemorySettingsBinding
    private lateinit var soulFile: File
    private lateinit var chatFile: File
    private lateinit var memoryFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityWorkspaceMemorySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        bindWorkspaceFiles()
        loadDocuments()
        bindEditorScroll(binding.etSoul)
        bindEditorScroll(binding.etChat)
        bindEditorScroll(binding.etMemory)
        bindSave(binding.btnSaveSoul, binding.etSoul) { soulFile }
        bindSave(binding.btnSaveChat, binding.etChat) { chatFile }
        bindSave(binding.btnSaveMemory, binding.etMemory) { memoryFile }
    }

    private fun bindWorkspaceFiles() {
        val files = AgentWorkspace.memoryDocuments(this).associateBy { it.fileName }
        soulFile = requireNotNull(files["SOUL.md"]?.file)
        chatFile = requireNotNull(files["CHAT.md"]?.file)
        memoryFile = requireNotNull(files["MEMORY.md"]?.file)
    }

    private fun loadDocuments() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Triple(
                        soulFile.readText(),
                        chatFile.readText(),
                        memoryFile.readText()
                    )
                }
            }
            result
                .onSuccess { (soul, chat, memory) ->
                    binding.etSoul.setText(soul)
                    binding.etChat.setText(chat)
                    binding.etMemory.setText(memory)
                }
                .onFailure {
                    Toast.makeText(this@WorkspaceMemorySettingsActivity, "读取失败: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun bindSave(button: TextView, editor: EditText, fileProvider: () -> File) {
        button.setOnClickListener {
            val text = editor.text?.toString().orEmpty()
            val file = fileProvider()
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        file.parentFile?.mkdirs()
                        file.writeText(text)
                    }
                }
                result
                    .onSuccess {
                        Toast.makeText(this@WorkspaceMemorySettingsActivity, "已保存 ${file.name}", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure {
                        Toast.makeText(this@WorkspaceMemorySettingsActivity, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

    private fun bindEditorScroll(editor: EditText) {
        editor.isVerticalScrollBarEnabled = true
        editor.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        editor.setHorizontallyScrolling(false)
        editor.setOnTouchListener { view, event ->
            view.parent?.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }
}
