package com.hfad.mantou.view

import android.Manifest
import android.app.Dialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.hfad.mantou.R
import com.hfad.mantou.adapter.ChatAdapter
import com.hfad.mantou.adapter.ImageSelectAdapter
import com.hfad.mantou.adapter.SessionAdapter
import com.hfad.mantou.adapter.WorkspaceFileAdapter
import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.data.ImageItem
import com.hfad.mantou.databinding.FragmentMainBinding
import com.hfad.mantou.databinding.LayoutChatPageBinding
import com.hfad.mantou.databinding.LayoutWorkspacePageBinding
import com.hfad.mantou.utils.AgentWorkspace
import com.hfad.mantou.utils.WorkspaceNode
import com.hfad.mantou.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    private var _chatBinding: LayoutChatPageBinding? = null
    private val chatBinding get() = _chatBinding!!
    private var _workspaceBinding: LayoutWorkspacePageBinding? = null
    private val workspaceBinding get() = _workspaceBinding!!
    private var isInputActive = false
    private var currentPagerPage = 0
    private var pagerCallback: ViewPager2.OnPageChangeCallback? = null

    // ViewModel
    private val viewModel: ChatViewModel by viewModels()

    // 聊天消息适配器
    private lateinit var chatAdapter: ChatAdapter

    // 会话列表适配器
    private lateinit var sessionAdapter: SessionAdapter

    // 图片选择适配器
    private lateinit var imageSelectAdapter: ImageSelectAdapter

    // workspace 文件树适配器
    private lateinit var workspaceFileAdapter: WorkspaceFileAdapter

    // 手势检测器（用于上滑检测）
    private lateinit var gestureDetector: GestureDetector

    // 相机拍照的临时 Uri
    private var cameraImageUri: Uri? = null

    // 权限请求启动器
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadRecentImages()
        } else {
            Toast.makeText(requireContext(), "需要相册权限才能加载图片", Toast.LENGTH_SHORT).show()
        }
    }

    // 相机权限请求启动器
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openCamera()
        } else {
            Toast.makeText(requireContext(), "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
        }
    }

    // 相机拍照结果处理
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraImageUri?.let { uri ->
                onPhotoTaken(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        _chatBinding = LayoutChatPageBinding.inflate(inflater)
        _workspaceBinding = LayoutWorkspacePageBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupAppBar()
        setupMainPager()
        setupWorkspacePage()
        
        // 初始化聊天 RecyclerView
        setupChatRecyclerView()
        
        // 初始化会话列表 RecyclerView
        setupSessionRecyclerView()
        
        // 初始化图片选择 RecyclerView
        setupImageSelectRecyclerView()
        
        // 初始化手势检测器
        setupGestureDetector()
        
        // 观察 ViewModel 数据
        observeViewModel()

        // 初始化模型名称显示
        viewModel.refreshActiveModel()

        // 输入框失去焦点时：切换回搜索态
        chatBinding.etInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && isInputActive) {
                // 延迟检查，避免点击其他按钮时误触发
                view.postDelayed({
                    if (!chatBinding.etInput.hasFocus() && chatBinding.functionPanel.visibility != View.VISIBLE) {
                        switchToIdleState()
                    }
                }, 100)
            }
        }

        // 添加按钮点击：显示/隐藏功能面板
        chatBinding.ivAdd.setOnClickListener {
            toggleFunctionPanel()
        }

        // 点击聊天列表：隐藏功能面板
        chatBinding.rvChat.setOnClickListener {
            hideFunctionPanel()
            chatBinding.etInput.clearFocus()
        }

        // 输入框点击：显示输入态
        chatBinding.etInput.setOnClickListener {
            if (!isInputActive) {
                switchToActiveState()
            }
            hideFunctionPanel()
        }

        // 相机按钮点击：打开相机
        chatBinding.ivCamera.setOnClickListener {
            checkCameraPermissionAndOpen()
        }

        // 发送按钮点击：发送消息
        chatBinding.ivSend.setOnClickListener {
            sendMessage()
        }

        // 输入框回车键监听：发送消息
        chatBinding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

        // 侧边栏清空历史按钮
        setupDrawerMenu()
    }

    private fun setupAppBar() {
        binding.ivMenu.setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }
        binding.newChat.setOnClickListener {
            if (currentPagerPage == 0) {
                createNewSession()
            } else {
                binding.mainPager.setCurrentItem(0, true)
            }
        }
        updateAppBarAction(0)
    }

    private fun setupMainPager() {
        binding.mainPager.adapter = StaticPagerAdapter(
            listOf(chatBinding.root, workspaceBinding.root)
        )
        binding.mainPager.offscreenPageLimit = 2

        pagerCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
                updateAppBarTitleSlide(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                currentPagerPage = position
                updateAppBarAction(position)
            }
        }
        binding.mainPager.registerOnPageChangeCallback(pagerCallback!!)
        binding.titleSwitcher.post {
            currentPagerPage = binding.mainPager.currentItem
            updateAppBarAction(currentPagerPage)
            updateAppBarTitleSlide(currentPagerPage, 0f)
        }
    }

    private fun setupWorkspacePage() {
        AgentWorkspace.ensureWorkspace(requireContext())
        workspaceFileAdapter = WorkspaceFileAdapter(
            onFileClick = { node -> handleWorkspaceFileClick(node) },
            onFileLongClick = { node -> handleWorkspaceFileLongClick(node) }
        )
        workspaceBinding.rvWorkspaceTree.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = workspaceFileAdapter
            itemAnimator = null
        }
        refreshWorkspaceTree()
    }

    private fun handleWorkspaceFileClick(node: WorkspaceNode) {
        val file = node.absolutePath?.let(::File) ?: return
        if (!file.exists()) {
            Toast.makeText(requireContext(), "文件不存在", Toast.LENGTH_SHORT).show()
            refreshWorkspaceTree()
            return
        }

        when (file.extension.lowercase(Locale.getDefault())) {
            "html", "htm" -> {
                val intent = Intent(requireContext(), VirtualAppActivity::class.java).apply {
                    putExtra(VirtualAppActivity.EXTRA_HTML_PATH, file.absolutePath)
                }
                startActivity(intent)
            }
            "md", "txt" -> openTextFileEditor(file)
            else -> {
                val typeName = file.extension.ifBlank { "该" }
                Toast.makeText(requireContext(), "暂不支持打开 $typeName 类型文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openTextFileEditor(file: File) {
        lifecycleScope.launch {
            val contentResult = withContext(Dispatchers.IO) {
                runCatching { file.readText() }
            }
            val content = contentResult.getOrElse {
                Toast.makeText(requireContext(), "读取失败: ${it.message}", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val editor = EditText(requireContext()).apply {
                setText(content)
                setSelection(text?.length ?: 0)
                minLines = 12
                maxLines = 18
                inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setHorizontallyScrolling(false)
                textSize = 14f
            }

            val paddingHorizontal = (16 * resources.displayMetrics.density).toInt()
            val paddingTop = (8 * resources.displayMetrics.density).toInt()
            val editorContainer = FrameLayout(requireContext()).apply {
                setPadding(paddingHorizontal, paddingTop, paddingHorizontal, 0)
                addView(
                    editor,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(file.name)
                .setView(editorContainer)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener {
                        lifecycleScope.launch {
                            val saveResult = withContext(Dispatchers.IO) {
                                runCatching { file.writeText(editor.text.toString()) }
                            }
                            saveResult
                                .onSuccess {
                                    Toast.makeText(requireContext(), "已保存", Toast.LENGTH_SHORT).show()
                                    refreshWorkspaceTree()
                                    dialog.dismiss()
                                }
                                .onFailure { error ->
                                    Toast.makeText(requireContext(), "保存失败: ${error.message}", Toast.LENGTH_SHORT).show()
                                }
                        }
                    }
            }
            dialog.show()
        }
    }

    private fun handleWorkspaceFileLongClick(node: WorkspaceNode): Boolean {
        if (!node.displayPath.startsWith("/workspace/${AgentWorkspace.WEB_DIR}/")) {
            return false
        }
        val file = node.absolutePath?.let(::File) ?: return false
        if (!file.exists()) {
            Toast.makeText(requireContext(), "文件不存在", Toast.LENGTH_SHORT).show()
            refreshWorkspaceTree()
            return true
        }

        showActionMenu(
            listOf(
                ActionMenuItem("改名", R.drawable.ic_edit_outline) {
                    showRenameWorkspaceFileDialog(file)
                },
                ActionMenuItem("删除", R.drawable.ic_delete_outline) {
                    confirmDeleteWorkspaceFile(file)
                }
            )
        )
        return true
    }

    private fun showRenameWorkspaceFileDialog(file: File) {
        val editor = EditText(requireContext()).apply {
            setText(file.name)
            setSelection(0, file.nameWithoutExtension.length.coerceAtMost(file.name.length))
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 15f
        }

        val paddingHorizontal = (16 * resources.displayMetrics.density).toInt()
        val paddingTop = (8 * resources.displayMetrics.density).toInt()
        val editorContainer = FrameLayout(requireContext()).apply {
            setPadding(paddingHorizontal, paddingTop, paddingHorizontal, 0)
            addView(
                editor,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("改名文件")
            .setView(editorContainer)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val newName = normalizeWorkspaceFileName(
                        editor.text.toString(),
                        file.extension
                    )
                    if (newName == null) {
                        Toast.makeText(requireContext(), "文件名不能为空", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (newName == file.name) {
                        dialog.dismiss()
                        return@setOnClickListener
                    }
                    val target = File(file.parentFile, newName)
                    if (target.exists()) {
                        Toast.makeText(requireContext(), "已存在同名文件", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (file.renameTo(target)) {
                        Toast.makeText(requireContext(), "已改名", Toast.LENGTH_SHORT).show()
                        refreshWorkspaceTree()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(requireContext(), "改名失败", Toast.LENGTH_SHORT).show()
                    }
                }
        }
        dialog.show()
    }

    private fun normalizeWorkspaceFileName(input: String, originalExtension: String): String? {
        val sanitized = input
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .trim('_', '-', '.', ' ')

        if (sanitized.isBlank()) return null
        if (sanitized.contains('.')) return sanitized
        return originalExtension
            .takeIf { it.isNotBlank() }
            ?.let { "$sanitized.$it" }
            ?: sanitized
    }

    private fun confirmDeleteWorkspaceFile(file: File) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("删除文件")
            .setMessage("确定要删除「${file.name}」吗？")
            .setPositiveButton("删除") { _, _ ->
                val deleted = file.delete()
                if (deleted) {
                    Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                }
                refreshWorkspaceTree()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateAppBarTitleSlide(position: Int, positionOffset: Float) {
        val width = binding.titleSwitcher.width
        if (width == 0) return

        val progress = when {
            position <= 0 -> positionOffset
            else -> 1f
        }.coerceIn(0f, 1f)

        binding.chatTitleGroup.translationX = -progress * width
        binding.chatTitleGroup.alpha = 1f - progress
        binding.workspaceTitleGroup.translationX = (1f - progress) * width
        binding.workspaceTitleGroup.alpha = progress
    }

    private fun updateAppBarAction(position: Int) {
        binding.newChat.contentDescription = if (position == 0) {
            "新建会话"
        } else {
            "返回聊天"
        }
    }

    private fun refreshWorkspaceTree() {
        if (::workspaceFileAdapter.isInitialized) {
            workspaceFileAdapter.submitNodes(AgentWorkspace.loadWorkspaceTree(requireContext()))
        }
    }

    /**
     * 设置侧边栏菜单
     */
    private fun setupDrawerMenu() {
        val drawerMenu = (activity as? MainActivity)?.findViewById<View>(com.hfad.mantou.R.id.drawerMenu)
        drawerMenu?.findViewById<android.widget.TextView>(com.hfad.mantou.R.id.tvClearHistory)?.setOnClickListener {
            showClearHistoryDialog()
        }
        drawerMenu?.findViewById<ImageButton>(com.hfad.mantou.R.id.btn_setting)?.setOnClickListener {
            (activity as? MainActivity)?.closeDrawer()
            startActivity(Intent(requireContext(), ModelSettingActivity::class.java))
        }
    }

    /**
     * 显示清空历史确认对话框
     */
    private fun showClearHistoryDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("清空所有会话")
            .setMessage("确定要删除所有历史会话吗？此操作不可恢复。")
            .setPositiveButton("确定") { _, _ ->
                viewModel.deleteAllSessions()
                Toast.makeText(requireContext(), "已清空所有会话", Toast.LENGTH_SHORT).show()
                (activity as? MainActivity)?.closeDrawer()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 创建新会话
     */
    private fun createNewSession() {
        // 通过 ViewModel 创建空会话
        viewModel.createEmptySession()
        
        // 清空输入框
        chatBinding.etInput.text?.clear()
        
        // 清空图片选择
        clearImageSelection()
        
        // 隐藏功能面板
        hideFunctionPanel()
        
        Toast.makeText(requireContext(), "已创建新会话", Toast.LENGTH_SHORT).show()
    }

    /**
     * 初始化会话列表 RecyclerView
     */
    private fun setupSessionRecyclerView() {
        sessionAdapter = SessionAdapter(
            onSessionClick = { session ->
                // 点击会话：切换到该会话
                viewModel.switchToSession(session.sessionId)
                (activity as? MainActivity)?.closeDrawer()
                Toast.makeText(requireContext(), "已切换到: ${session.title}", Toast.LENGTH_SHORT).show()
            },
            onSessionLongClick = { session ->
                // 长按会话：显示删除确认对话框
                showDeleteSessionDialog(session)
            }
        )

        val drawerMenu = (activity as? MainActivity)?.findViewById<View>(com.hfad.mantou.R.id.drawerMenu)
        val rvSessions = drawerMenu?.findViewById<androidx.recyclerview.widget.RecyclerView>(com.hfad.mantou.R.id.rvSessions)
        
        rvSessions?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = sessionAdapter
        }
    }

    /**
     * 显示删除会话确认对话框
     */
    private fun showDeleteSessionDialog(session: com.hfad.mantou.data.database.ChatSessionEntity) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("删除会话")
            .setMessage("确定要删除会话「${session.title}」吗？")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteSession(session.sessionId)
                Toast.makeText(requireContext(), "已删除会话", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMessageActions(message: ChatMessage) {
        showActionMenu(
            listOf(
                ActionMenuItem("选字复制", R.drawable.ic_select_text) {
                    showSelectableMessageText(message)
                },
                ActionMenuItem("删除", R.drawable.ic_delete_outline) {
                    confirmDeleteMessage(message)
                },
                ActionMenuItem("修改", R.drawable.ic_edit_outline) {
                    showEditMessageDialog(message)
                }
            )
        )
    }

    private fun showSelectableMessageText(message: ChatMessage) {
        val text = message.content
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), "没有可复制的文本", Toast.LENGTH_SHORT).show()
            return
        }

        val textView = TextView(requireContext()).apply {
            this.text = text
            setTextIsSelectable(true)
            textSize = 15f
            setTextColor(Color.rgb(31, 41, 55))
            setLineSpacing(2f * resources.displayMetrics.density, 1f)
        }
        val paddingHorizontal = (18 * resources.displayMetrics.density).toInt()
        val paddingVertical = (10 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(requireContext()).apply {
            setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, 0)
            addView(
                textView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("选字复制")
            .setView(container)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun confirmDeleteMessage(message: ChatMessage) {
        if (message.messageId <= 0) {
            Toast.makeText(requireContext(), "该消息无法删除", Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("删除消息")
            .setMessage("确定要删除这条消息吗？")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteMessage(message.messageId)
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditMessageDialog(message: ChatMessage) {
        if (message.messageId <= 0) {
            Toast.makeText(requireContext(), "该消息无法修改", Toast.LENGTH_SHORT).show()
            return
        }

        val editor = EditText(requireContext()).apply {
            setText(message.content)
            setSelection(text?.length ?: 0)
            minLines = 4
            maxLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false)
            textSize = 15f
        }

        val paddingHorizontal = (16 * resources.displayMetrics.density).toInt()
        val paddingTop = (8 * resources.displayMetrics.density).toInt()
        val editorContainer = FrameLayout(requireContext()).apply {
            setPadding(paddingHorizontal, paddingTop, paddingHorizontal, 0)
            addView(
                editor,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("修改消息")
            .setView(editorContainer)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val newContent = editor.text.toString().trim()
                    if (newContent.isBlank()) {
                        Toast.makeText(requireContext(), "消息不能为空", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    viewModel.updateMessageContent(message.messageId, newContent)
                    Toast.makeText(requireContext(), "已修改", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
        }
        dialog.show()
    }

    private fun showActionMenu(items: List<ActionMenuItem>) {
        if (items.isEmpty()) return

        val dialog = Dialog(requireContext())
        val density = resources.displayMetrics.density
        val menu = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_action_menu)
        }

        items.forEachIndexed { index, item ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), 0, dp(24), 0)
                minimumHeight = dp(72)
                foreground = ContextCompat.getDrawable(requireContext(), selectableItemBackgroundRes())
                setOnClickListener {
                    dialog.dismiss()
                    item.onClick()
                }
            }

            val icon = ImageView(requireContext()).apply {
                setImageResource(item.iconRes)
                colorFilter = android.graphics.PorterDuffColorFilter(
                    Color.rgb(17, 24, 39),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            }
            row.addView(
                icon,
                LinearLayout.LayoutParams(dp(30), dp(30))
            )

            val label = TextView(requireContext()).apply {
                text = item.label
                textSize = 22f
                setTextColor(Color.rgb(17, 24, 39))
                includeFontPadding = false
            }
            row.addView(
                label,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = dp(24)
                }
            )

            menu.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(72)
                )
            )

            if (index != items.lastIndex) {
                menu.addView(
                    View(requireContext()).apply {
                        background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_action_menu_divider)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * density).toInt().coerceAtLeast(1)
                    )
                )
            }
        }

        dialog.setContentView(menu)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.72f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun selectableItemBackgroundRes(): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(
            android.R.attr.selectableItemBackground,
            typedValue,
            true
        )
        return typedValue.resourceId
    }

    /**
     * 初始化聊天 RecyclerView
     */
    private fun setupChatRecyclerView() {
        chatAdapter = ChatAdapter(
            onDataChanged = { itemCount ->
                if (itemCount > 0) {
                    chatBinding.flGreeting.visibility = View.GONE
                } else {
                    chatBinding.flGreeting.visibility = View.VISIBLE
                }
            },
            onFullscreenClick = { htmlPath ->
                val intent = Intent(requireContext(), VirtualAppActivity::class.java).apply {
                    putExtra(VirtualAppActivity.EXTRA_HTML_PATH, htmlPath)
                }
                startActivity(intent)
            },
            onMessageLongClick = { message ->
                showMessageActions(message)
            }
        )

        chatBinding.rvChat.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = chatAdapter
            // 设置 item 动画（可选）
            itemAnimator = null  // 禁用动画以避免闪烁，或使用自定义动画
        }
    }

    /**
     * 观察 ViewModel 数据变化
     */
    private fun observeViewModel() {
        // 观察消息列表
        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            chatAdapter.submitList(messages)
            
            // 滚动到最新消息
            if (messages.isNotEmpty()) {
                chatBinding.rvChat.post {
                    chatBinding.rvChat.scrollToPosition(messages.size - 1)
                }
            }
        }

        // 观察所有会话列表
        lifecycleScope.launch {
            viewModel.allSessions.collect { sessions ->
                sessionAdapter.submitList(sessions)
            }
        }

        // 观察加载状态
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            // 可以在这里显示/隐藏加载指示器
            // 例如：binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.isGeneratingApp.observe(viewLifecycleOwner) { isGeneratingApp ->
            setKeepScreenOn(isGeneratingApp)
        }

        // 观察错误消息
        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        // 观察当前会话 ID
        viewModel.currentSessionId.observe(viewLifecycleOwner) { sessionId ->
            // 可以在这里更新 UI，显示当前会话信息
        }

        // 观察当前活跃模型名称
        viewModel.activeModelName.observe(viewLifecycleOwner) { modelName ->
            binding.tvChatSubtitle.text = modelName ?: "请配置你的模型"
        }

        // 观察未配置模型事件
        viewModel.noModelConfigured.observe(viewLifecycleOwner) { noModel ->
            if (noModel) {
                Toast.makeText(requireContext(), "请先配置模型后再使用", Toast.LENGTH_LONG).show()
                viewModel.clearNoModelConfigured()
            }
        }

        viewModel.appGenerated.observe(viewLifecycleOwner) { htmlPath ->
            if (htmlPath != null) {
                refreshWorkspaceTree()
                viewModel.clearAppGenerated()
            }
        }
    }

    /**
     * 初始化手势检测器（用于上滑进入图片选择器）
     */
    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                
                val diffY = e1.y - e2.y // 上滑时 diffY > 0
                val diffX = e2.x - e1.x

                // 检测上滑手势
                if (abs(diffY) > abs(diffX) && 
                    diffY > SWIPE_THRESHOLD && 
                    abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    // 上滑 - 打开图片选择器
                    openImagePicker()
                    return true
                }
                return false
            }
        })

        // 在功能面板上设置触摸监听
        chatBinding.functionPanel.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false // 返回 false 让 RecyclerView 继续处理滚动
        }

        // 在输入区域设置触摸监听（用于上滑打开图片选择器）
        chatBinding.inputCard.setOnTouchListener { _, event ->
            if (chatBinding.functionPanel.visibility == View.VISIBLE) {
                gestureDetector.onTouchEvent(event)
            }
            false
        }
    }

    /**
     * 打开系统图片选择器
     */
    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        imagePickerLauncher.launch(intent)
    }

    // 图片选择器结果处理
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val selectedUris = mutableListOf<android.net.Uri>()
            
            // 处理多选
            data?.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i).uri?.let { uri ->
                        selectedUris.add(uri)
                    }
                }
            }
            
            // 处理单选
            if (selectedUris.isEmpty()) {
                data?.data?.let { uri ->
                    selectedUris.add(uri)
                }
            }
            
            if (selectedUris.isNotEmpty()) {
                // 处理选中的图片
                onImagesSelectedFromPicker(selectedUris)
            }
        }
    }

    /**
     * 从系统选择器选中图片后的回调
     */
    private fun onImagesSelectedFromPicker(uris: List<android.net.Uri>) {
        // 可以在这里处理从系统选择器选中的图片
        Toast.makeText(requireContext(), "已选择 ${uris.size} 张图片", Toast.LENGTH_SHORT).show()
    }

    /**
     * 检查相机权限并打开相机
     */
    private fun checkCameraPermissionAndOpen() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                openCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Toast.makeText(requireContext(), "需要相机权限来拍照", Toast.LENGTH_SHORT).show()
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    /**
     * 打开相机拍照
     */
    private fun openCamera() {
        val uri = createImageUri()
        if (uri != null) {
            cameraImageUri = uri
            takePictureLauncher.launch(uri)
        } else {
            Toast.makeText(requireContext(), "无法创建图片文件", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 创建用于保存拍照图片的 Uri
     */
    private fun createImageUri(): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}.jpg"
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 使用 MediaStore
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, imageFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            requireContext().contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
        } else {
            // Android 9 及以下使用 FileProvider
            val storageDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val imageFile = File(storageDir, imageFileName)
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                imageFile
            )
        }
    }

    /**
     * 拍照完成后的回调
     */
    private fun onPhotoTaken(uri: Uri) {
        Toast.makeText(requireContext(), "拍照成功", Toast.LENGTH_SHORT).show()
        // 可以在这里处理拍摄的照片，例如显示预览或发送
        // 刷新图片列表以显示新拍摄的照片
        if (chatBinding.functionPanel.visibility == View.VISIBLE) {
            loadRecentImages()
        }
    }

    /**
     * 初始化图片选择 RecyclerView
     */
    private fun setupImageSelectRecyclerView() {
        imageSelectAdapter = ImageSelectAdapter { selectedImages ->
            // 选中状态变化回调
            onImageSelectionChanged(selectedImages)
        }

        chatBinding.functionPanel.apply {
            layoutManager = GridLayoutManager(requireContext(), 4) // 4列
            adapter = imageSelectAdapter
            setHasFixedSize(true)
        }
    }

    /**
     * 图片选中状态变化回调
     */
    private fun onImageSelectionChanged(selectedImages: List<ImageItem>) {
        // 可以在这里处理选中图片变化，例如更新UI显示选中数量
        // Toast.makeText(requireContext(), "已选择 ${selectedImages.size} 张图片", Toast.LENGTH_SHORT).show()
    }

    /**
     * 获取选中的图片列表
     */
    fun getSelectedImages(): List<ImageItem> {
        return if (::imageSelectAdapter.isInitialized) {
            imageSelectAdapter.getSelectedImages()
        } else {
            emptyList()
        }
    }

    /**
     * 清除图片选中状态
     */
    fun clearImageSelection() {
        if (::imageSelectAdapter.isInitialized) {
            imageSelectAdapter.clearSelection()
        }
    }

    /**
     * 检查并请求相册权限
     */
    private fun checkAndRequestPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用 READ_MEDIA_IMAGES
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            // Android 12 及以下使用 READ_EXTERNAL_STORAGE
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                permission
            ) == PackageManager.PERMISSION_GRANTED -> {
                // 已有权限，直接加载图片
                loadRecentImages()
            }
            shouldShowRequestPermissionRationale(permission) -> {
                // 需要解释为什么需要权限
                Toast.makeText(requireContext(), "需要相册权限来选择图片", Toast.LENGTH_SHORT).show()
                requestPermissionLauncher.launch(permission)
            }
            else -> {
                // 请求权限
                requestPermissionLauncher.launch(permission)
            }
        }
    }

    /**
     * 从 MediaStore 加载最新 12 张图片
     */
    private fun loadRecentImages() {
        val imageList = mutableListOf<ImageItem>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val query = requireContext().contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )

        query?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            var count = 0

            while (cursor.moveToNext() && count < 12) {
                val id = cursor.getLong(idColumn)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                imageList.add(ImageItem(id, contentUri))
                count++
            }
        }

        // 更新适配器数据
        imageSelectAdapter.setImages(imageList)
    }

    /**
     * 切换到输入态
     */
    private fun switchToActiveState() {
        if (isInputActive) return
        
        isInputActive = true
        
        // 延迟获取焦点，确保布局已更新
        chatBinding.etInput.postDelayed({
            chatBinding.etInput.requestFocus()
            showKeyboard()
        }, 100)
    }

    /**
     * 切换到搜索态
     */
    private fun switchToIdleState() {
        if (!isInputActive) return
        
        // 如果功能面板正在显示，不切换
        if (chatBinding.functionPanel.visibility == View.VISIBLE) {
            return
        }
        
        isInputActive = false
        hideKeyboard()
        hideFunctionPanel()
        
        // 清空输入框
        chatBinding.etInput.clearFocus()
        chatBinding.etInput.text?.clear()
    }

    /**
     * 显示/隐藏功能面板
     */
    private fun toggleFunctionPanel() {
        if (chatBinding.functionPanel.visibility == View.VISIBLE) {
            hideFunctionPanel()
        } else {
            showFunctionPanel()
        }
    }

    /**
     * 显示功能面板
     */
    private fun showFunctionPanel() {
        chatBinding.functionPanel.visibility = View.VISIBLE
        // 隐藏键盘
        hideKeyboard()
        chatBinding.etInput.clearFocus()
        
        // 检查权限并加载图片
        checkAndRequestPermission()
    }

    /**
     * 隐藏功能面板
     */
    private fun hideFunctionPanel() {
        chatBinding.functionPanel.visibility = View.GONE
    }

    /**
     * 显示键盘
     */
    private fun showKeyboard() {
        chatBinding.etInput.let {
            val imm = ContextCompat.getSystemService(
                requireContext(),
                InputMethodManager::class.java
            )
            imm?.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * 隐藏键盘
     */
    private fun hideKeyboard() {
        chatBinding.etInput.let {
            val imm = ContextCompat.getSystemService(
                requireContext(),
                InputMethodManager::class.java
            )
            imm?.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    /**
     * 发送消息
     */
    private fun sendMessage() {
        val text = chatBinding.etInput.text?.toString()?.trim() ?: ""
        
        // 获取选中的图片
        val selectedImages = getSelectedImages()
        
        // 检查是否有内容
        if (text.isEmpty() && selectedImages.isEmpty()) {
            Toast.makeText(requireContext(), "请输入消息内容", Toast.LENGTH_SHORT).show()
            return
        }

        // 获取图片 URI 列表
        val imageUris = selectedImages.map { it.uri }
        val firstImagePath = imageUris.firstOrNull()?.toString()

        // 清空输入框和图片选择
        chatBinding.etInput.text?.clear()
        clearImageSelection()
        hideFunctionPanel()
        hideKeyboard()

        // 通过 ViewModel 发送消息（支持多图）
        viewModel.sendMessage(
            content = text.ifEmpty { if (imageUris.isNotEmpty()) "请帮我分析这张图片" else "" },
            imagePath = firstImagePath,
            imageUris = imageUris
        )
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshActiveModel()
        refreshWorkspaceTree()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        setKeepScreenOn(false)
        pagerCallback?.let { binding.mainPager.unregisterOnPageChangeCallback(it) }
        pagerCallback = null
        binding.mainPager.adapter = null
        _workspaceBinding = null
        _chatBinding = null
        _binding = null
    }

    private fun setKeepScreenOn(keepScreenOn: Boolean) {
        val window = activity?.window ?: return
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private class StaticPagerAdapter(
        private val pages: List<View>
    ) : RecyclerView.Adapter<StaticPagerAdapter.PageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.MATCH_PARENT
                )
            }
            return PageViewHolder(container)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val page = pages[position]
            (page.parent as? ViewGroup)?.removeView(page)
            holder.container.removeAllViews()
            holder.container.addView(
                page,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        override fun getItemCount(): Int = pages.size

        class PageViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)
    }

    private data class ActionMenuItem(
        val label: String,
        val iconRes: Int,
        val onClick: () -> Unit
    )
}
