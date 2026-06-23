package com.hfad.mantou.view

import android.app.Dialog
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import com.hfad.mantou.R
import com.hfad.mantou.adapter.ChatAdapter
import com.hfad.mantou.adapter.SessionAdapter
import com.hfad.mantou.adapter.WorkspaceFileAdapter
import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.data.database.ChatSessionEntity
import com.hfad.mantou.data.preferences.ContextLimitStore
import com.hfad.mantou.data.preferences.WallpaperStore
import com.hfad.mantou.databinding.FragmentMainBinding
import com.hfad.mantou.databinding.LayoutChatPageBinding
import com.hfad.mantou.databinding.LayoutWorkspacePageBinding
import com.hfad.mantou.tool.impl.CameraPhotoBridge
import com.hfad.mantou.tool.impl.CameraPhotoHost
import com.hfad.mantou.utils.AgentWorkspace
import com.hfad.mantou.utils.ContextTokenCounter
import com.hfad.mantou.utils.WorkspaceNode
import com.hfad.mantou.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.compareTo
import kotlin.math.roundToInt

class MainFragment : Fragment(), CameraPhotoBridge.Host {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    private var _chatBinding: LayoutChatPageBinding? = null
    private val chatBinding get() = _chatBinding!!
    private var _workspaceBinding: LayoutWorkspacePageBinding? = null
    private val workspaceBinding get() = _workspaceBinding!!
    private var isInputActive = false
    private var currentPagerPage = 0
    private var pagerCallback: ViewPager2.OnPageChangeCallback? = null
    private var inputContainerBasePaddingBottom = 0
    private var lastImeVisible = false
    private var lastAppliedBottomInset = Int.MIN_VALUE
    private var currentContextTokens = 0
    private var cachedChatSystemPrompt: String? = null
    private var forceScrollToLatestMessage = false
    private var isChatScrollActive = false
    private var pendingMessagesWhileScrolling: List<ChatMessage>? = null
    private var isTaskRunning = false
    private val selectedImageUris = mutableListOf<Uri>()
    private var activeAppWebView: WebView? = null
    private lateinit var cameraPhotoHost: CameraPhotoHost

    // ViewModel
    private val viewModel: ChatViewModel by viewModels()

    // 聊天消息适配器
    private lateinit var chatAdapter: ChatAdapter

    // 会话列表适配器
    private lateinit var sessionAdapter: SessionAdapter

    // workspace 文件树适配器
    private lateinit var workspaceFileAdapter: WorkspaceFileAdapter

    private var activeSessions: List<ChatSessionEntity> = emptyList()
    private var archivedSessions: List<ChatSessionEntity> = emptyList()
    private var drawerSearchQuery: String = ""
    private var showArchivedSessions: Boolean = false

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        onImagesSelectedFromPicker(uris)
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraPhotoHost.onCameraPhotoResult(success)
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPhotoHost.onCameraPermissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraPhotoHost = CameraPhotoHost(
            activity = requireActivity(),
            takePictureLauncher = takePictureLauncher,
            requestPermissionLauncher = requestCameraPermissionLauncher,
            webViewProvider = { activeAppWebView }
        )
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
        CameraPhotoBridge.attach(this)
        
        // 初始化会话列表 RecyclerView
        setupSessionRecyclerView()
        
        initInsets()
        
        // 观察 ViewModel 数据
        observeViewModel()
        warmUpContextPromptCache()

        // 初始化模型名称显示
        viewModel.refreshActiveModel()

        chatBinding.etInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                enterInputActiveState()
            } else if (isInputActive) {
                view.postDelayed({
                    if (!chatBinding.etInput.hasFocus()) {
                        switchToIdleState()
                    }
                }, 100)
            }
        }

        chatBinding.ivAdd.setOnClickListener {
            openImagePicker()
        }

        chatBinding.ivContextLimit.setOnClickListener {
            showContextLimitDialog()
        }

        chatBinding.rvChat.setOnClickListener {
            chatBinding.etInput.clearFocus()
        }

        // 输入框点击：显示输入态
        chatBinding.etInput.setOnClickListener {
            switchToActiveState()
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

        chatBinding.etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateCurrentContextTokens()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateCurrentContextTokens()
        updateSendButtonState(false)
        applyWallpaper()

        // 侧边栏清空历史按钮
        setupDrawerMenu()


    }

    override fun requestCameraPhoto(callbackName: String?) {
        cameraPhotoHost.requestCameraPhoto(callbackName)
    }
    private fun initInsets() {
        inputContainerBasePaddingBottom = chatBinding.inputContainer.paddingBottom

        ViewCompat.setWindowInsetsAnimationCallback(
            binding.root,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    applyChatInsets(insets)
                    return insets
                }
            }
        )
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun applyChatInsets(insets: WindowInsetsCompat) {
        val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
        val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        val bottomInset = if (imeVisible) {
            (imeInsets.bottom - systemBarsInsets.bottom).coerceAtLeast(0)
        } else {
            0
        }
        val targetPaddingBottom = inputContainerBasePaddingBottom + bottomInset

        if (chatBinding.inputContainer.paddingBottom != targetPaddingBottom) {
            chatBinding.inputContainer.updatePadding(bottom = targetPaddingBottom)
        }

        if (imeVisible && (!lastImeVisible || lastAppliedBottomInset != bottomInset)) {
            scrollChatToBottom()
        }

        lastImeVisible = imeVisible
        lastAppliedBottomInset = bottomInset
    }

    private fun scrollChatToBottom() {
        if (!::chatAdapter.isInitialized) return
        val messageCount = chatAdapter.itemCount
        if (messageCount <= 0) return
        chatBinding.rvChat.post {
            chatBinding.rvChat.scrollToPosition(messageCount - 1)
        }
    }

    private fun shouldFollowNewMessages(): Boolean {
        val itemCount = chatAdapter.itemCount
        if (itemCount <= 0) return true
        val distanceToBottom = chatBinding.rvChat.computeVerticalScrollRange() -
                chatBinding.rvChat.computeVerticalScrollOffset() -
                chatBinding.rvChat.computeVerticalScrollExtent()
        return distanceToBottom <= dp(96)
    }

    private fun renderChatMessages(messages: List<ChatMessage>) {
        if (forceScrollToLatestMessage) {
            pendingMessagesWhileScrolling = null
        }
        if (isChatScrollActive && !forceScrollToLatestMessage) {
            pendingMessagesWhileScrolling = messages
            updateCurrentContextTokens(messages)
            return
        }

        val followNewMessages = forceScrollToLatestMessage || shouldFollowNewMessages()
        forceScrollToLatestMessage = false
        val anchor = if (followNewMessages) null else captureChatViewportAnchor()
        chatAdapter.submitList(messages) {
            if (isChatScrollActive) {
                pendingMessagesWhileScrolling = messages
                return@submitList
            }
            when {
                messages.isNotEmpty() && followNewMessages -> scrollChatToBottom()
                anchor != null -> restoreChatViewportAnchor(anchor)
            }
        }
        updateCurrentContextTokens(messages)
    }

    private fun captureChatViewportAnchor(): ChatViewportAnchor? {
        val layoutManager = chatBinding.rvChat.layoutManager as? LinearLayoutManager ?: return null
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return null
        val view = layoutManager.findViewByPosition(position) ?: return null
        return ChatViewportAnchor(position, view.top - chatBinding.rvChat.paddingTop)
    }

    private fun restoreChatViewportAnchor(anchor: ChatViewportAnchor) {
        val layoutManager = chatBinding.rvChat.layoutManager as? LinearLayoutManager ?: return
        val lastPosition = (chatAdapter.itemCount - 1).coerceAtLeast(0)
        layoutManager.scrollToPositionWithOffset(
            anchor.position.coerceAtMost(lastPosition),
            anchor.topOffset
        )
    }

    private fun warmUpContextPromptCache() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val prompt = withContext(Dispatchers.IO) {
                AgentWorkspace.buildSystemPrompt(appContext)
            }
            cachedChatSystemPrompt = prompt
            updateCurrentContextTokens()
        }
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
            "json" -> {
                val intent = Intent(requireContext(), JsonViewerActivity::class.java).apply {
                    putExtra(JsonViewerActivity.EXTRA_JSON_PATH, file.absolutePath)
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

    private fun applyWallpaper() {
        val wallpaperUri = WallpaperStore.getWallpaperUri(requireContext())
        val defaultBackground = ContextCompat.getColor(requireContext(), R.color.mt_background)
        if (wallpaperUri == null) {
            binding.wallpaperBackground.visibility = View.GONE
            binding.wallpaperBackground.setImageDrawable(null)
            binding.mainPager.setBackgroundColor(defaultBackground)
            chatBinding.root.setBackgroundColor(defaultBackground)
            workspaceBinding.root.setBackgroundColor(defaultBackground)
            return
        }

        binding.wallpaperBackground.visibility = View.VISIBLE
        runCatching {
            binding.wallpaperBackground.setImageURI(wallpaperUri)
        }.onFailure {
            WallpaperStore.clearWallpaper(requireContext())
            binding.wallpaperBackground.visibility = View.GONE
            Toast.makeText(requireContext(), "壁纸读取失败，已恢复默认背景", Toast.LENGTH_SHORT).show()
        }
        binding.mainPager.setBackgroundColor(Color.TRANSPARENT)
        chatBinding.root.setBackgroundColor(Color.TRANSPARENT)
        workspaceBinding.root.setBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * 设置侧边栏菜单
     */
    private fun setupDrawerMenu() {
        val drawerMenu = (activity as? MainActivity)?.findViewById<View>(com.hfad.mantou.R.id.drawerMenu)
        drawerMenu?.findViewById<View>(com.hfad.mantou.R.id.tvClearHistory)?.setOnClickListener {
            showClearHistoryDialog()
        }
        drawerMenu?.findViewById<View>(com.hfad.mantou.R.id.btn_setting)?.setOnClickListener {
            (activity as? MainActivity)?.closeDrawer()
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        drawerMenu?.findViewById<ImageButton>(com.hfad.mantou.R.id.btnArchiveToggle)?.setOnClickListener {
            showArchivedSessions = !showArchivedSessions
            refreshDrawerSessionList()
        }
        drawerMenu?.findViewById<EditText>(com.hfad.mantou.R.id.etSessionSearch)
            ?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    drawerSearchQuery = s?.toString().orEmpty().trim()
                    refreshDrawerSessionList()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
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
        
        clearSelectedImages()
        
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
                showSessionActions(session)
            }
        )

        val drawerMenu = (activity as? MainActivity)?.findViewById<View>(com.hfad.mantou.R.id.drawerMenu)
        val rvSessions = drawerMenu?.findViewById<androidx.recyclerview.widget.RecyclerView>(com.hfad.mantou.R.id.rvSessions)
        
        rvSessions?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = sessionAdapter
        }
        refreshDrawerSessionList()
    }

    private fun refreshDrawerSessionList() {
        if (!::sessionAdapter.isInitialized) return

        val drawerMenu = (activity as? MainActivity)?.findViewById<View>(com.hfad.mantou.R.id.drawerMenu)
        val titleView = drawerMenu?.findViewById<TextView>(com.hfad.mantou.R.id.tvSessionSectionTitle)
        val emptyView = drawerMenu?.findViewById<TextView>(com.hfad.mantou.R.id.tvEmptySessions)
        val archiveButton = drawerMenu?.findViewById<ImageButton>(com.hfad.mantou.R.id.btnArchiveToggle)

        val source = if (drawerSearchQuery.isNotBlank()) {
            (activeSessions + archivedSessions).distinctBy { it.sessionId }
        } else if (showArchivedSessions) {
            archivedSessions
        } else {
            activeSessions
        }
        val visibleSessions = if (drawerSearchQuery.isBlank()) {
            source
        } else {
            source.filter { it.title.contains(drawerSearchQuery, ignoreCase = true) }
        }

        titleView?.text = when {
            drawerSearchQuery.isNotBlank() -> "搜索结果"
            showArchivedSessions -> "归档对话"
            else -> "最近对话"
        }
        emptyView?.text = when {
            drawerSearchQuery.isNotBlank() -> "没有匹配的会话"
            showArchivedSessions -> "暂无归档会话"
            else -> "暂无会话"
        }
        emptyView?.visibility = if (visibleSessions.isEmpty()) View.VISIBLE else View.GONE

        val accentColor = if (showArchivedSessions) {
            ContextCompat.getColor(requireContext(), R.color.mt_primary)
        } else {
            ContextCompat.getColor(requireContext(), R.color.mt_text_primary)
        }
        archiveButton?.imageTintList = ColorStateList.valueOf(accentColor)
        archiveButton?.contentDescription = if (showArchivedSessions) "返回最近对话" else "查看归档"

        sessionAdapter.submitList(visibleSessions)
    }

    /**
     * 显示会话操作菜单
     */
    private fun showSessionActions(session: ChatSessionEntity) {
        val archiveLabel = if (session.isArchived) "取消归档" else "归档"
        showActionMenu(
            listOf(
                ActionMenuItem(archiveLabel, R.drawable.ic_archive_outline) {
                    val nextArchived = !session.isArchived
                    viewModel.setSessionArchived(session.sessionId, nextArchived)
                    Toast.makeText(
                        requireContext(),
                        if (nextArchived) "已归档会话" else "已取消归档",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                ActionMenuItem("删除", R.drawable.ic_delete_outline) {
                    showDeleteSessionDialog(session)
                }
            )
        )
    }

    /**
     * 显示删除会话确认对话框
     */
    private fun showDeleteSessionDialog(session: ChatSessionEntity) {
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

    private fun dp(value: Float): Int {
        return (value * resources.displayMetrics.density).roundToInt()
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
            },
            onActiveWebViewChanged = { webView ->
                activeAppWebView = webView
            },
            onWebViewReleased = { webView ->
                if (activeAppWebView === webView) {
                    activeAppWebView = null
                }
            }
        )

        chatBinding.rvChat.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = chatAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    isChatScrollActive = newState != RecyclerView.SCROLL_STATE_IDLE
                    if (!isChatScrollActive) {
                        pendingMessagesWhileScrolling?.let { pendingMessages ->
                            pendingMessagesWhileScrolling = null
                            renderChatMessages(pendingMessages)
                        }
                    }
                }
            })
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
            renderChatMessages(messages)
        }

        // 观察所有会话列表
        lifecycleScope.launch {
            viewModel.allSessions.collect { sessions ->
                activeSessions = sessions
                refreshDrawerSessionList()
            }
        }

        // 观察已归档会话列表
        lifecycleScope.launch {
            viewModel.archivedSessions.collect { sessions ->
                archivedSessions = sessions
                refreshDrawerSessionList()
            }
        }

        // 观察加载状态
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            updateSendButtonState(isLoading)
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
            pendingMessagesWhileScrolling = null
            forceScrollToLatestMessage = true
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

    private fun openImagePicker() {
        imagePickerLauncher.launch(arrayOf("image/*"))
    }

    private fun onImagesSelectedFromPicker(uris: List<Uri>) {
        if (uris.isEmpty()) return

        uris.forEach { uri ->
            persistReadPermission(uri)
            if (selectedImageUris.none { it == uri }) {
                selectedImageUris.add(uri)
            }
        }
        renderSelectedImageChips()
        updateCurrentContextTokens()
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun renderSelectedImageChips() {
        chatBinding.selectedImageChipGroup.removeAllViews()
        chatBinding.selectedImageChipScroll.visibility =
            if (selectedImageUris.isEmpty()) View.GONE else View.VISIBLE

        selectedImageUris.forEach { uri ->
            val chip = Chip(requireContext()).apply {
                text = queryDisplayName(uri)
                isCloseIconVisible = true
                isClickable = true
                isCheckable = false
                setTextColor(Color.rgb(44, 83, 122))
                textSize = 13f
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.rgb(238, 245, 255))
                chipStrokeColor = android.content.res.ColorStateList.valueOf(Color.rgb(187, 216, 255))
                chipStrokeWidth = dp(1).toFloat()
                closeIconTint = android.content.res.ColorStateList.valueOf(Color.rgb(44, 131, 216))
                setOnCloseIconClickListener {
                    selectedImageUris.remove(uri)
                    renderSelectedImageChips()
                    updateCurrentContextTokens()
                }
            }
            chatBinding.selectedImageChipGroup.addView(chip)
        }
    }

    private fun clearSelectedImages() {
        selectedImageUris.clear()
        renderSelectedImageChips()
        updateCurrentContextTokens()
    }

    private fun showContextLimitDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val content = buildContextLimitSheet()
        dialog.setContentView(content)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            sheet?.background = ColorDrawable(Color.TRANSPARENT)
        }
        dialog.show()
    }

    private fun buildContextLimitSheet(): View {
        var syncing = false
        var currentLimit = ContextLimitStore.getTokenLimit(requireContext())
        lateinit var saveLimitAction: (Int, Boolean) -> Unit

        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, topLeft = 28f, topRight = 28f)
            setPadding(dp(22), dp(12), dp(22), dp(22))
        }
        scroll.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(
            View(requireContext()).apply {
                background = roundedBackground(Color.rgb(220, 226, 238), 2.5f)
            },
            LinearLayout.LayoutParams(dp(46), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(26)
            }
        )

        container.addView(
            TextView(requireContext()).apply {
                text = "调整上下文阈值"
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(45, 52, 68))
                includeFontPadding = false
            }
        )

        container.addView(
            TextView(requireContext()).apply {
                text = "修改后自动保存，新的阈值会立刻应用"
                textSize = 15f
                setTextColor(Color.rgb(111, 119, 137))
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
        )

        container.addView(divider(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        ).apply {
            topMargin = dp(26)
            bottomMargin = dp(20)
        })

        val statsRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val currentValue = createStatValue(formatNumber(currentContextTokens), Color.rgb(45, 52, 68))
        val targetValue = createStatValue(formatNumber(currentLimit), Color.rgb(58, 132, 226))
        val percentValue = createStatValue(formatPercent(currentContextTokens, currentLimit), Color.rgb(65, 145, 126))
        statsRow.addView(createStatColumn("当前上下文", currentValue), statColumnParams())
        statsRow.addView(verticalDivider())
        statsRow.addView(createStatColumn("目标阈值", targetValue), statColumnParams())
        statsRow.addView(verticalDivider())
        statsRow.addView(createStatColumn("占用比例", percentValue), statColumnParams())
        container.addView(statsRow)

        container.addView(divider(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        ).apply {
            topMargin = dp(22)
            bottomMargin = dp(28)
        })

        val slider = Slider(requireContext()).apply {
            valueFrom = ContextLimitStore.MIN_TOKEN_LIMIT.toFloat()
            valueTo = ContextLimitStore.MAX_TOKEN_LIMIT.toFloat()
            value = currentLimit.toFloat()
        }
        container.addView(slider)

        val optionsGrid = GridLayout(requireContext()).apply {
            columnCount = 4
            useDefaultMargins = false
        }
        val optionButtons = mutableMapOf<Int, TextView>()
        ContextLimitStore.TOKEN_OPTIONS.forEach { option ->
            val button = TextView(requireContext()).apply {
                text = formatCompactToken(option)
                gravity = Gravity.CENTER
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setOnClickListener {
                    saveLimitAction(option, true)
                }
            }
            optionButtons[option] = button
            optionsGrid.addView(
                button,
                GridLayout.LayoutParams().apply {
                    width = dp(70)
                    height = dp(48)
                    setMargins(0, 0, dp(10), dp(10))
                }
            )
        }
        container.addView(
            optionsGrid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
            }
        )

        container.addView(
            TextView(requireContext()).apply {
                text = "精确阈值"
                textSize = 15f
                setTextColor(Color.rgb(111, 119, 137))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                leftMargin = dp(16)
            }
        )

        val exactInput = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            textSize = 22f
            setTextColor(Color.rgb(45, 52, 68))
            setText(currentLimit.toString())
            setPadding(dp(18), 0, dp(18), 0)
            background = roundedStrokeBackground(
                fillColor = Color.rgb(244, 247, 255),
                strokeColor = Color.rgb(225, 231, 243),
                radius = 16f
            )
        }
        container.addView(
            exactInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64)
            ).apply {
                topMargin = dp(4)
            }
        )

        container.addView(
            TextView(requireContext()).apply {
                text = "默认 ${formatNumber(ContextLimitStore.DEFAULT_TOKEN_LIMIT)}，范围 ${formatNumber(ContextLimitStore.MIN_TOKEN_LIMIT)} - ${formatNumber(ContextLimitStore.MAX_TOKEN_LIMIT)}"
                textSize = 14f
                setTextColor(Color.rgb(150, 158, 176))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                leftMargin = dp(16)
            }
        )

        val savedRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        savedRow.addView(
            TextView(requireContext()).apply {
                text = "✓"
                gravity = Gravity.CENTER
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(112, 124, 145))
                background = roundedStrokeBackground(
                    fillColor = Color.TRANSPARENT,
                    strokeColor = Color.rgb(112, 124, 145),
                    radius = 13f
                )
            },
            LinearLayout.LayoutParams(dp(27), dp(27))
        )
        savedRow.addView(
            TextView(requireContext()).apply {
                text = "已自动保存"
                textSize = 17f
                setTextColor(Color.rgb(112, 124, 145))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(12)
            }
        )
        container.addView(
            savedRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(22)
            }
        )

        fun refreshSheet(limit: Int) {
            val clamped = limit.coerceIn(
                ContextLimitStore.MIN_TOKEN_LIMIT,
                ContextLimitStore.MAX_TOKEN_LIMIT
            )
            currentLimit = clamped
            currentValue.text = formatNumber(currentContextTokens)
            targetValue.text = formatNumber(clamped)
            percentValue.text = formatPercent(currentContextTokens, clamped)
            optionButtons.forEach { (option, button) ->
                val selected = option == clamped
                button.setTextColor(if (selected) Color.WHITE else Color.rgb(122, 133, 153))
                button.background = roundedStrokeBackground(
                    fillColor = if (selected) Color.rgb(58, 132, 226) else Color.rgb(245, 247, 252),
                    strokeColor = if (selected) Color.rgb(58, 132, 226) else Color.rgb(228, 233, 243),
                    radius = 24f
                )
            }
            updateContextProgressIndicator()
        }

        saveLimitAction = { limit, updateInput ->
            if (!syncing) {
                val clamped = limit.coerceIn(
                    ContextLimitStore.MIN_TOKEN_LIMIT,
                    ContextLimitStore.MAX_TOKEN_LIMIT
                )
                ContextLimitStore.setTokenLimit(requireContext(), clamped)
                syncing = true
                if (slider.value.roundToInt() != clamped) {
                    slider.value = clamped.toFloat()
                }
                if (updateInput && exactInput.text?.toString() != clamped.toString()) {
                    exactInput.setText(clamped.toString())
                    exactInput.setSelection(exactInput.text?.length ?: 0)
                }
                syncing = false
                refreshSheet(clamped)
            }
        }

        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                saveLimitAction(value.roundToInt(), true)
            }
        }
        exactInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (syncing) return
                val parsed = s?.toString()?.toIntOrNull() ?: return
                saveLimitAction(parsed, false)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refreshSheet(currentLimit)

        return scroll
    }

    private fun updateCurrentContextTokens(messages: List<ChatMessage>? = null) {
        val sourceMessages = messages ?: viewModel.messages.value.orEmpty()
        currentContextTokens = ContextTokenCounter.estimateChatMessages(
            messages = sourceMessages,
            draftText = chatBinding.etInput.text?.toString().orEmpty(),
            selectedImageCount = selectedImageUris.size,
            systemPrompt = cachedChatSystemPrompt
        )
        updateContextProgressIndicator()
    }

    private fun updateContextProgressIndicator() {
        val limit = ContextLimitStore.getTokenLimit(requireContext())
        val fraction = if (limit <= 0) 0f else currentContextTokens.toFloat() / limit.toFloat()
        chatBinding.ivContextLimit.setProgressFraction(fraction)
        chatBinding.ivContextLimit.contentDescription =
            "上下文限制，已使用 ${formatNumber(currentContextTokens)} / ${formatNumber(limit)}"
    }

    private fun createStatColumn(label: String, valueView: TextView): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(requireContext()).apply {
                text = label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(122, 133, 153))
            })
            addView(valueView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            })
        }
    }

    private fun createStatValue(textValue: String, color: Int): TextView {
        return TextView(requireContext()).apply {
            text = textValue
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            includeFontPadding = false
        }
    }

    private fun statColumnParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun divider(): View {
        return View(requireContext()).apply {
            background = ColorDrawable(Color.rgb(232, 236, 244))
        }
    }

    private fun verticalDivider(): View {
        return View(requireContext()).apply {
            background = ColorDrawable(Color.rgb(232, 236, 244))
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(58)).apply {
                leftMargin = dp(12)
                rightMargin = dp(20)
            }
        }
    }

    private fun roundedBackground(color: Int, radius: Float = 0f, topLeft: Float = radius, topRight: Float = radius): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(
                dp(topLeft).toFloat(), dp(topLeft).toFloat(),
                dp(topRight).toFloat(), dp(topRight).toFloat(),
                0f, 0f,
                0f, 0f
            )
        }
    }

    private fun roundedStrokeBackground(fillColor: Int, strokeColor: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            setCornerRadius(dp(radius).toFloat())
            setStroke(dp(1), strokeColor)
        }
    }

    private fun formatNumber(value: Int): String {
        return String.format(Locale.US, "%,d", value)
    }

    private fun formatPercent(current: Int, limit: Int): String {
        if (limit <= 0) return "0%"
        return String.format(Locale.US, "%.1f%%", current * 100f / limit)
    }

    private fun formatCompactToken(value: Int): String {
        return if (value >= 1_000_000) {
            "${value / 1_000_000}M"
        } else {
            "${value / 1_000}k"
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        return runCatching {
            requireContext().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else {
                        null
                    }
                }
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "已选择图片"
    }

    /**
     * 切换到输入态
     */
    private fun switchToActiveState() {
        isInputActive = true
        if (!chatBinding.etInput.hasFocus()) {
            chatBinding.etInput.requestFocus()
        }
        showKeyboard()
    }

    /**
     * 切换到搜索态
     */
    private fun enterInputActiveState() {
        isInputActive = true
    }

    private fun switchToIdleState() {
        if (!isInputActive) return
        isInputActive = false
        hideKeyboard()

        chatBinding.etInput.clearFocus()
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

    private fun updateSendButtonState(taskRunning: Boolean) {
        isTaskRunning = taskRunning
        val enabled = !taskRunning
        val backgroundColor = if (enabled) {
            Color.rgb(44, 131, 216)
        } else {
            Color.rgb(209, 229, 255)
        }
        val iconColor = if (enabled) {
            Color.WHITE
        } else {
            Color.argb(185, 255, 255, 255)
        }

        chatBinding.ivSend.isEnabled = enabled
        chatBinding.ivSend.isClickable = enabled
        chatBinding.ivSend.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        chatBinding.ivSend.imageTintList = ColorStateList.valueOf(iconColor)
        chatBinding.ivSend.contentDescription = if (enabled) "发送" else "正在处理，暂不可发送"
    }

    /**
     * 发送消息
     */
    private fun sendMessage() {
        if (isTaskRunning) {
            return
        }
        val text = chatBinding.etInput.text?.toString()?.trim() ?: ""
        val imageUris = selectedImageUris.toList()
        
        // 检查是否有内容
        if (text.isEmpty() && imageUris.isEmpty()) {
            Toast.makeText(requireContext(), "请输入消息内容", Toast.LENGTH_SHORT).show()
            return
        }
        updateSendButtonState(true)
        forceScrollToLatestMessage = true

        val firstImagePath = imageUris.firstOrNull()?.toString()

        // 清空输入框和图片选择
        chatBinding.etInput.text?.clear()
        clearSelectedImages()
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
        applyWallpaper()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        CameraPhotoBridge.detach(this)
        activeAppWebView = null
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

    private data class ChatViewportAnchor(
        val position: Int,
        val topOffset: Int
    )

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
