package com.hfad.mantou.view

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
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
import android.view.Window
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
import android.app.ActivityOptions
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
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
import com.hfad.mantou.adapter.DesktopAppAdapter
import com.hfad.mantou.adapter.SessionAdapter
import com.hfad.mantou.adapter.WorkspaceFileAdapter
import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.data.api.VoiceInputConfig
import com.hfad.mantou.data.api.VoiceTranscriptionApiService
import com.hfad.mantou.data.api.WavAudioRecorder
import com.hfad.mantou.data.database.AppDatabase
import com.hfad.mantou.data.database.ChatSessionEntity
import com.hfad.mantou.data.database.ProviderEntity
import com.hfad.mantou.data.preferences.AppearanceSettingsStore
import com.hfad.mantou.data.preferences.ActiveModelStore
import com.hfad.mantou.data.preferences.ContextLimitStore
import com.hfad.mantou.data.preferences.VoiceInputModelStore
import com.hfad.mantou.data.preferences.WallpaperStore
import com.hfad.mantou.databinding.FragmentMainBinding
import com.hfad.mantou.databinding.LayoutChatPageBinding
import com.hfad.mantou.databinding.LayoutDesktopPageBinding
import com.hfad.mantou.databinding.LayoutWorkspacePageBinding
import com.hfad.mantou.data.repository.ProviderRepository
import com.hfad.mantou.tool.impl.CameraPhotoBridge
import com.hfad.mantou.tool.impl.CameraPhotoHost
import com.hfad.mantou.utils.AgentWorkspace
import com.hfad.mantou.utils.AutoContrastColor
import com.hfad.mantou.utils.ContextTokenCounter
import com.hfad.mantou.utils.DesktopAppScanner
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

    private companion object {
        const val PAGE_DESKTOP = 0
        const val PAGE_CHAT = 1
        const val PAGE_WORKSPACE = 2
    }


    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    private var _chatBinding: LayoutChatPageBinding? = null
    private val chatBinding get() = _chatBinding!!
    private var _workspaceBinding: LayoutWorkspacePageBinding? = null
    private val workspaceBinding get() = _workspaceBinding!!
    private var _desktopBinding: LayoutDesktopPageBinding? = null
    private val desktopBinding get() = _desktopBinding!!
    private var isInputActive = false
    private var currentPagerPage = PAGE_CHAT
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
    private var voiceRecorder: WavAudioRecorder? = null
    private var voiceInputFile: File? = null
    private var voiceRecordingConfig: VoiceInputConfig? = null
    private var isVoiceRecording = false
    private var isVoiceTranscribing = false
    private var pendingVoiceStartAfterPermission = false
    private val providerRepository by lazy {
        ProviderRepository(AppDatabase.getDatabase(requireContext().applicationContext).providerDao())
    }

    // ViewModel
    private val viewModel: ChatViewModel by viewModels()

    // 聊天消息适配器
    private lateinit var chatAdapter: ChatAdapter

    // 会话列表适配器
    private lateinit var sessionAdapter: SessionAdapter

    // workspace 文件树适配器
    private lateinit var workspaceFileAdapter: WorkspaceFileAdapter

    // 桌面应用适配器
    private lateinit var desktopAppAdapter: DesktopAppAdapter

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

    private val requestAudioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingVoiceStartAfterPermission) {
            pendingVoiceStartAfterPermission = false
            startVoiceInputWithConfiguredModel()
        } else if (!granted) {
            pendingVoiceStartAfterPermission = false
            Toast.makeText(requireContext(), "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
        }
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
        _desktopBinding = LayoutDesktopPageBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupAppBar()
        setupMainPager()
        setupWorkspacePage()
        setupDesktopPage()
        
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

        chatBinding.ivModelPicker.setOnClickListener {
            showModelPickerPanel()
        }

        chatBinding.ivVoiceInput.setOnClickListener {
            onVoiceInputClicked()
        }
        chatBinding.ivVoiceInput.setOnLongClickListener {
            if (isVoiceRecording || isVoiceTranscribing) {
                Toast.makeText(requireContext(), "请先结束当前语音输入", Toast.LENGTH_SHORT).show()
            } else {
                showMimoVoiceKeyDialog(startAfterActivation = false)
            }
            true
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
        updateVoiceInputButtonState()
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
            systemBarsInsets.bottom
        }
        val targetPaddingBottom = inputContainerBasePaddingBottom + bottomInset

        if (chatBinding.inputContainer.paddingBottom != targetPaddingBottom) {
            chatBinding.inputContainer.updatePadding(bottom = targetPaddingBottom)
        }
        val targetListPaddingBottom = dp(8) + bottomInset
        if (chatBinding.rvChat.paddingBottom != targetListPaddingBottom) {
            chatBinding.rvChat.updatePadding(bottom = targetListPaddingBottom)
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
            if (currentPagerPage == PAGE_CHAT) {
                createNewSession()
            } else {
                binding.mainPager.setCurrentItem(PAGE_CHAT, true)
            }
        }
        updateAppBarAction(PAGE_CHAT)
    }

    private fun setupMainPager() {
        binding.mainPager.adapter = StaticPagerAdapter(
            listOf(desktopBinding.root, chatBinding.root, workspaceBinding.root)
        )
        binding.mainPager.offscreenPageLimit = 3
        // 初始页保持 chat（index = 1），桌面在左、workspace 在右
        binding.mainPager.setCurrentItem(PAGE_CHAT, false)
        currentPagerPage = PAGE_CHAT

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
                if (position == PAGE_DESKTOP) {
                    refreshDesktopApps()
                }
            }
        }
        binding.mainPager.registerOnPageChangeCallback(pagerCallback!!)
        binding.titleSwitcher.post {
            currentPagerPage = binding.mainPager.currentItem
            updateAppBarAction(currentPagerPage)
            updateAppBarTitleSlide(currentPagerPage, 0f)
        }
    }

    private fun setupDesktopPage() {
        desktopAppAdapter = DesktopAppAdapter { item, sourceView ->
            openWebAppWithAnimation(item.htmlPath, sourceView)
        }
        desktopBinding.rvDesktopApps.apply {
            layoutManager = GridLayoutManager(requireContext(), 4)
            adapter = desktopAppAdapter
            itemAnimator = null
            setHasFixedSize(false)
        }
        refreshDesktopApps()
    }

    private fun refreshDesktopApps() {
        if (!::desktopAppAdapter.isInitialized) return
        val items = DesktopAppScanner.loadDesktopApps(requireContext())
        desktopAppAdapter.submit(items)
        if (_desktopBinding != null) {
            desktopBinding.tvDesktopEmpty.visibility =
                if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun openWebAppWithAnimation(htmlPath: String, sourceView: View) {
        val file = File(htmlPath)
        if (!file.exists()) {
            Toast.makeText(requireContext(), "文件不存在", Toast.LENGTH_SHORT).show()
            refreshDesktopApps()
            return
        }

        val intent = Intent(requireContext(), VirtualAppActivity::class.java).apply {
            putExtra(VirtualAppActivity.EXTRA_HTML_PATH, file.absolutePath)
        }
        val options = ActivityOptions.makeScaleUpAnimation(
            sourceView,
            0,
            0,
            sourceView.width,
            sourceView.height
        )
        startActivity(intent, options.toBundle())
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

        val globalProgress = (position + positionOffset).coerceIn(0f, 2f)
        applyTitleGroup(binding.desktopTitleGroup, 0, globalProgress, width)
        applyTitleGroup(binding.chatTitleGroup, 1, globalProgress, width)
        applyTitleGroup(binding.workspaceTitleGroup, 2, globalProgress, width)
    }

    private fun applyTitleGroup(view: View, index: Int, globalProgress: Float, width: Int) {
        val delta = index - globalProgress
        view.translationX = delta * width
        view.alpha = (1f - kotlin.math.abs(delta)).coerceIn(0f, 1f)
    }

    private fun updateAppBarAction(position: Int) {
        binding.newChat.contentDescription = when (position) {
            PAGE_CHAT -> "新建会话"
            else -> "返回聊天"
        }
    }

    private fun refreshWorkspaceTree() {
        if (::workspaceFileAdapter.isInitialized) {
            workspaceFileAdapter.submitNodes(AgentWorkspace.loadWorkspaceTree(requireContext()))
        }
    }

    private fun applyWallpaper() {
        val wallpaperUri = WallpaperStore.getWallpaperUri(requireContext())
        val appearanceSettings = AppearanceSettingsStore.getSettings(requireContext())
        if (::chatAdapter.isInitialized) {
            chatAdapter.updateAppearance(appearanceSettings)
        }
        val defaultBackground = ContextCompat.getColor(requireContext(), R.color.mt_background)
        if (wallpaperUri == null) {
            binding.wallpaperBackground.visibility = View.GONE
            binding.wallpaperBackground.setImageDrawable(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                binding.wallpaperBackground.setRenderEffect(null)
            }
            binding.wallpaperMask.visibility = View.GONE
            binding.mainPager.setBackgroundColor(defaultBackground)
            chatBinding.root.setBackgroundColor(defaultBackground)
            workspaceBinding.root.setBackgroundColor(defaultBackground)
            desktopBinding.root.setBackgroundColor(defaultBackground)
            dispatchAutoTextColor(appearanceSettings, null)
            return
        }

        binding.wallpaperBackground.visibility = View.VISIBLE
        val wallpaperLoaded = runCatching {
            binding.wallpaperBackground.setImageURI(wallpaperUri)
        }.isSuccess
        if (!wallpaperLoaded) {
            WallpaperStore.clearWallpaper(requireContext())
            binding.wallpaperBackground.visibility = View.GONE
            binding.wallpaperBackground.setImageDrawable(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                binding.wallpaperBackground.setRenderEffect(null)
            }
            binding.wallpaperMask.visibility = View.GONE
            binding.mainPager.setBackgroundColor(defaultBackground)
            chatBinding.root.setBackgroundColor(defaultBackground)
            workspaceBinding.root.setBackgroundColor(defaultBackground)
            desktopBinding.root.setBackgroundColor(defaultBackground)
            dispatchAutoTextColor(appearanceSettings, null)
            Toast.makeText(requireContext(), "壁纸读取失败，已恢复默认背景", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blur = appearanceSettings.backgroundBlur
            binding.wallpaperBackground.setRenderEffect(
                if (blur > 0f) {
                    RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
                } else {
                    null
                }
            )
        }
        binding.wallpaperMask.visibility = View.VISIBLE
        binding.wallpaperMask.setBackgroundColor(AppearanceSettingsStore.maskColor(appearanceSettings))
        binding.mainPager.setBackgroundColor(Color.TRANSPARENT)
        chatBinding.root.setBackgroundColor(Color.TRANSPARENT)
        workspaceBinding.root.setBackgroundColor(Color.TRANSPARENT)
        desktopBinding.root.setBackgroundColor(Color.TRANSPARENT)
        dispatchAutoTextColor(appearanceSettings, binding.wallpaperBackground.drawable)
    }

    private fun dispatchAutoTextColor(
        settings: AppearanceSettingsStore.Settings,
        wallpaperDrawable: android.graphics.drawable.Drawable?
    ) {
        val autoColor = AutoContrastColor.resolve(requireContext(), settings, wallpaperDrawable)
        val effective = if (settings.hasFixedTextColor) settings.chatTextColor else autoColor
        if (::chatAdapter.isInitialized) {
            chatAdapter.updateAutoTextColor(autoColor)
        }
        if (::desktopAppAdapter.isInitialized) {
            desktopAppAdapter.updateTextColor(effective)
        }
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
        chatAdapter.updateAppearance(AppearanceSettingsStore.getSettings(requireContext()))
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

    private fun showModelPickerPanel() {
        showModelPickerPanel(
            activeProviderId = ActiveModelStore.getActiveProviderId(requireContext()),
            activeModelName = ActiveModelStore.getActiveModelName(requireContext()),
            providerFilter = { true },
            onSelected = { provider, modelName ->
                ActiveModelStore.setActive(requireContext(), provider.providerId, modelName)
                viewModel.refreshActiveModel()
                Toast.makeText(requireContext(), "已切换到 $modelName", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showMimoVoiceKeyDialog(startAfterActivation: Boolean) {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(buildMimoVoiceKeyContent(dialog, startAfterActivation))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener {
            configureModelPickerWindow(dialog)
        }
        dialog.show()
    }

    private fun buildMimoVoiceKeyContent(dialog: Dialog, startAfterActivation: Boolean): View {
        val currentApiKey = VoiceInputModelStore.getActiveApiKey(requireContext())
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_dialog_glass)
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }

        root.addView(
            TextView(requireContext()).apply {
                text = "MiMo 语音输入"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(45, 52, 68))
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            TextView(requireContext()).apply {
                text = "输入可用的 MiMo API Key，校验通过后会激活 ${VoiceTranscriptionApiService.MIMO_ASR_MODEL}。"
                textSize = 13f
                setTextColor(Color.rgb(112, 124, 145))
                setPadding(0, dp(8), 0, 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val apiKeyInput = buildVoiceConfigInput(
            hint = "MiMo API Key",
            text = currentApiKey,
            inputTypeValue = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )

        root.addView(apiKeyInput, voiceConfigInputLayoutParams(topMargin = dp(16)))

        val buttonRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            TextView(requireContext()).apply {
                text = "取消"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(97, 113, 137))
                background = roundedStrokeBackground(
                    fillColor = Color.argb(140, 248, 251, 255),
                    strokeColor = Color.argb(180, 218, 228, 244),
                    radius = 14f
                )
                foreground = ContextCompat.getDrawable(requireContext(), selectableItemBackgroundRes())
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(0, dp(46), 1f)
        )
        buttonRow.addView(
            TextView(requireContext()).apply {
                text = "激活"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = roundedStrokeBackground(
                    fillColor = Color.rgb(44, 131, 216),
                    strokeColor = Color.rgb(44, 131, 216),
                    radius = 14f
                )
                foreground = ContextCompat.getDrawable(requireContext(), selectableItemBackgroundRes())
                setOnClickListener {
                    val apiKey = apiKeyInput.text?.toString().orEmpty()
                    val button = this
                    button.isEnabled = false
                    button.alpha = 0.65f
                    button.text = "校验中..."
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = VoiceTranscriptionApiService.activateMimoAsr(apiKey)
                        if (!dialog.isShowing) return@launch
                        button.isEnabled = true
                        button.alpha = 1f
                        button.text = "激活"
                        result.onSuccess { config ->
                            VoiceInputModelStore.setActive(requireContext(), config)
                            Toast.makeText(requireContext(), "MiMo 语音输入已激活", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            if (startAfterActivation) {
                                startVoiceInputWithConfiguredModel()
                            }
                        }.onFailure { error ->
                            Toast.makeText(
                                requireContext(),
                                error.message ?: "MiMo API Key 校验失败",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(12)
            }
        )
        root.addView(
            buttonRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
            }
        )

        return root
    }

    private fun buildVoiceConfigInput(
        hint: String,
        text: String,
        inputTypeValue: Int
    ): EditText {
        return EditText(requireContext()).apply {
            this.hint = hint
            setText(text)
            textSize = 14f
            setSingleLine(true)
            setTextColor(Color.rgb(45, 52, 68))
            setHintTextColor(Color.rgb(150, 162, 182))
            inputType = inputTypeValue
            background = roundedStrokeBackground(
                fillColor = Color.argb(160, 248, 251, 255),
                strokeColor = Color.WHITE,
                radius = 14f
            )
            setPadding(dp(14), 0, dp(14), 0)
        }
    }

    private fun voiceConfigInputLayoutParams(topMargin: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(50)
        ).apply {
            this.topMargin = topMargin
        }
    }

    private fun showModelPickerPanel(
        activeProviderId: Long?,
        activeModelName: String?,
        providerFilter: (ProviderEntity) -> Boolean,
        onSelected: (ProviderEntity, String) -> Unit
    ) {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val loadingView = buildModelPickerLoadingView()
        dialog.setContentView(loadingView)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener {
            configureModelPickerWindow(dialog)
        }
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) {
                loadModelPickerGroups(providerFilter)
            }
            if (!dialog.isShowing) return@launch
            dialog.setContentView(
                buildModelPickerContent(
                    dialog = dialog,
                    groups = groups,
                    activeProviderId = activeProviderId,
                    activeModelName = activeModelName,
                    onSelected = onSelected
                )
            )
            configureModelPickerWindow(dialog)
        }
    }

    private suspend fun loadModelPickerGroups(
        providerFilter: (ProviderEntity) -> Boolean
    ): List<ModelProviderGroup> {
        val providers = providerRepository.getAllProvidersOnce()
        return providers.filter(providerFilter).map { provider ->
            ModelProviderGroup(
                provider = provider,
                models = providerRepository.getModelsForProviderOnce(provider.providerId)
                    .map { it.modelName }
            )
        }
    }

    private fun configureModelPickerWindow(dialog: Dialog) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)
            setWindowAnimations(R.style.MtDialogAnimation)
            setLayout(
                (resources.displayMetrics.widthPixels - dp(40)).coerceAtLeast(dp(280)),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            attributes = attributes.apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = chatBinding.inputContainer.height + dp(8)
            }
        }
    }

    private fun buildModelPickerLoadingView(): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_dialog_glass)
            setPadding(dp(24), dp(34), dp(24), dp(34))
            addView(
                TextView(requireContext()).apply {
                    text = "正在加载模型..."
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(112, 124, 145))
                }
            )
        }
    }

    private fun buildModelPickerContent(
        dialog: Dialog,
        groups: List<ModelProviderGroup>,
        activeProviderId: Long?,
        activeModelName: String?,
        onSelected: (ProviderEntity, String) -> Unit
    ): View {
        val expandedProviderIds = groups.map { it.provider.providerId }.toMutableSet()

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_dialog_glass)
            setPadding(dp(14), dp(14), dp(14), dp(12))
        }

        val searchInput = EditText(requireContext()).apply {
            hint = "搜索模型 ID"
            textSize = 15f
            setSingleLine(true)
            setTextColor(Color.rgb(45, 52, 68))
            setHintTextColor(Color.rgb(160, 170, 188))
            background = roundedStrokeBackground(
                fillColor = Color.argb(160, 248, 251, 255),
                strokeColor = Color.WHITE,
                radius = 16f
            )
            setPadding(dp(14), 0, dp(14), 0)
            compoundDrawablePadding = dp(10)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search_outline, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(
            searchInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            )
        )

        val listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(requireContext()).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                listContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.46f).toInt().coerceAtLeast(dp(260))
            ).apply {
                topMargin = dp(12)
            }
        )

        fun render(query: String) {
            listContainer.removeAllViews()
            val normalizedQuery = query.trim().lowercase(Locale.getDefault())
            val filteredGroups = groups.map { group ->
                if (normalizedQuery.isBlank()) {
                    group
                } else {
                    group.copy(
                        models = group.models.filter {
                            it.lowercase(Locale.getDefault()).contains(normalizedQuery)
                        }
                    )
                }
            }.filter { normalizedQuery.isBlank() || it.models.isNotEmpty() }

            if (filteredGroups.isEmpty()) {
                listContainer.addView(buildModelPickerEmptyView())
                return
            }

            filteredGroups.forEach { group ->
                val isExpanded = group.provider.providerId in expandedProviderIds
                listContainer.addView(
                    buildModelProviderHeader(group, isExpanded) {
                        val providerId = group.provider.providerId
                        if (providerId in expandedProviderIds) {
                            expandedProviderIds.remove(providerId)
                        } else {
                            expandedProviderIds.add(providerId)
                        }
                        render(searchInput.text?.toString().orEmpty())
                    }
                )

                if (isExpanded) {
                    if (group.models.isEmpty()) {
                        listContainer.addView(buildEmptyProviderModelsRow())
                    } else {
                        group.models.forEach { modelName ->
                            val selected = activeProviderId == group.provider.providerId &&
                                activeModelName == modelName
                            listContainer.addView(
                                buildModelPickerModelRow(
                                    modelName = modelName,
                                    selected = selected
                                ) {
                                    onSelected(group.provider, modelName)
                                    dialog.dismiss()
                                }
                            )
                        }
                    }
                }
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                render(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render("")

        return root
    }

    private fun buildModelProviderHeader(
        group: ModelProviderGroup,
        expanded: Boolean,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(14), dp(8), dp(10))
            foreground = ContextCompat.getDrawable(requireContext(), selectableItemBackgroundRes())
            setOnClickListener { onClick() }
        }

        row.addView(
            TextView(requireContext()).apply {
                text = group.provider.name
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(97, 113, 137))
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        row.addView(
            TextView(requireContext()).apply {
                text = group.models.size.toString()
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(150, 162, 182))
                includeFontPadding = false
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        row.addView(
            ImageView(requireContext()).apply {
                setImageResource(if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down)
                imageTintList = ColorStateList.valueOf(Color.rgb(150, 162, 182))
            },
            LinearLayout.LayoutParams(dp(24), dp(24))
        )

        return row
    }

    private fun buildModelPickerModelRow(
        modelName: String,
        selected: Boolean,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(12), 0)
            foreground = ContextCompat.getDrawable(requireContext(), selectableItemBackgroundRes())
            background = if (selected) {
                roundedStrokeBackground(
                    fillColor = Color.argb(110, 229, 241, 255),
                    strokeColor = Color.argb(150, 83, 146, 236),
                    radius = 14f
                )
            } else {
                ColorDrawable(Color.TRANSPARENT)
            }
            setOnClickListener { onClick() }
        }

        row.addView(
            TextView(requireContext()).apply {
                text = modelName
                textSize = 15f
                setTextColor(if (selected) Color.rgb(36, 101, 196) else Color.rgb(92, 108, 132))
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        if (selected) {
            row.addView(
                ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_check_blue)
                    imageTintList = ColorStateList.valueOf(Color.rgb(44, 131, 216))
                },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    marginStart = dp(10)
                }
            )
        }

        return FrameLayout(requireContext()).apply {
            setPadding(0, dp(2), 0, dp(2))
            addView(
                row,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    dp(46)
                )
            )
        }
    }

    private fun buildEmptyProviderModelsRow(): View {
        return TextView(requireContext()).apply {
            text = "该 Provider 暂无可选模型"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(160, 170, 188))
            setPadding(0, dp(12), 0, dp(18))
        }
    }

    private fun buildModelPickerEmptyView(): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(42), dp(12), dp(42))
            addView(
                ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_package_empty)
                    alpha = 0.55f
                },
                LinearLayout.LayoutParams(dp(54), dp(54))
            )
            addView(
                TextView(requireContext()).apply {
                    text = "没有找到可选模型"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(150, 162, 182))
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(12)
                }
            )
        }
    }

    private fun onVoiceInputClicked() {
        when {
            isVoiceTranscribing -> {
                Toast.makeText(requireContext(), "正在识别语音，请稍等", Toast.LENGTH_SHORT).show()
            }
            isVoiceRecording -> {
                stopVoiceInputAndTranscribe()
            }
            VoiceInputModelStore.getActiveConfig(requireContext()) == null -> {
                showMimoVoiceKeyDialog(startAfterActivation = true)
            }
            else -> {
                startVoiceInputWithConfiguredModel()
            }
        }
    }

    private fun startVoiceInputWithConfiguredModel() {
        if (isVoiceRecording || isVoiceTranscribing) return

        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingVoiceStartAfterPermission = true
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val config = resolveVoiceInputConfig()
            if (config == null) {
                showMimoVoiceKeyDialog(startAfterActivation = true)
                return@launch
            }
            startVoiceRecording(config)
        }
    }

    private fun resolveVoiceInputConfig(): VoiceInputConfig? {
        return VoiceInputModelStore.getActiveConfig(requireContext())
    }

    private fun startVoiceRecording(config: VoiceInputConfig) {
        val outputFile = File(requireContext().cacheDir, "voice-input-${System.currentTimeMillis()}.wav")
        val recorder = WavAudioRecorder(outputFile)

        runCatching {
            recorder.start()
        }.onSuccess {
            voiceRecorder = recorder
            voiceInputFile = outputFile
            voiceRecordingConfig = config
            isVoiceRecording = true
            updateVoiceInputButtonState()
            hideKeyboard()
            Toast.makeText(requireContext(), "正在语音输入，再次点击结束", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            runCatching { recorder.cancel() }
            outputFile.delete()
            voiceRecorder = null
            voiceInputFile = null
            voiceRecordingConfig = null
            isVoiceRecording = false
            updateVoiceInputButtonState()
            Toast.makeText(requireContext(), "无法开始录音: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopVoiceInputAndTranscribe() {
        val recorder = voiceRecorder ?: return
        val audioFile = voiceInputFile
        val config = voiceRecordingConfig

        isVoiceRecording = false
        isVoiceTranscribing = true
        voiceRecorder = null
        voiceInputFile = null
        voiceRecordingConfig = null
        updateVoiceInputButtonState()

        val pcmBytes = runCatching { recorder.stop() }.getOrDefault(-1L)

        if (pcmBytes <= 0L || audioFile == null || config == null || audioFile.length() <= 0L) {
            audioFile?.delete()
            isVoiceTranscribing = false
            updateVoiceInputButtonState()
            Toast.makeText(requireContext(), "录音时间太短，请重试", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "正在识别语音...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = VoiceTranscriptionApiService.transcribe(config, audioFile)
            audioFile.delete()
            isVoiceTranscribing = false
            updateVoiceInputButtonState()
            result.onSuccess { text ->
                appendVoiceTextToInput(text)
            }.onFailure { error ->
                Toast.makeText(requireContext(), error.message ?: "语音识别失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun appendVoiceTextToInput(text: String) {
        val current = chatBinding.etInput.text?.toString().orEmpty()
        val separator = if (current.isBlank() || current.endsWith("\n") || current.endsWith(" ")) "" else " "
        val next = current + separator + text.trim()
        chatBinding.etInput.setText(next)
        chatBinding.etInput.setSelection(chatBinding.etInput.text?.length ?: 0)
        updateCurrentContextTokens()
    }

    private fun cancelVoiceInput(deleteFile: Boolean = true) {
        runCatching { voiceRecorder?.cancel() }
        if (deleteFile) {
            voiceInputFile?.delete()
        }
        voiceRecorder = null
        voiceInputFile = null
        voiceRecordingConfig = null
        isVoiceRecording = false
        isVoiceTranscribing = false
        pendingVoiceStartAfterPermission = false
        if (_chatBinding != null) {
            updateVoiceInputButtonState()
        }
    }

    private fun updateVoiceInputButtonState() {
        val button = chatBinding.ivVoiceInput
        when {
            isVoiceRecording -> {
                button.isEnabled = true
                button.alpha = 1f
                button.imageTintList = ColorStateList.valueOf(Color.rgb(230, 83, 83))
                button.contentDescription = "结束语音输入"
            }
            isVoiceTranscribing -> {
                button.isEnabled = false
                button.alpha = 0.55f
                button.imageTintList = ColorStateList.valueOf(Color.rgb(112, 124, 145))
                button.contentDescription = "正在识别语音"
            }
            else -> {
                button.isEnabled = true
                button.alpha = 1f
                button.imageTintList = ColorStateList.valueOf(Color.rgb(44, 131, 216))
                button.contentDescription = "语音输入"
            }
        }
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
        refreshDesktopApps()
        applyWallpaper()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cancelVoiceInput()
        CameraPhotoBridge.detach(this)
        activeAppWebView = null
        setKeepScreenOn(false)
        pagerCallback?.let { binding.mainPager.unregisterOnPageChangeCallback(it) }
        pagerCallback = null
        binding.mainPager.adapter = null
        _workspaceBinding = null
        _chatBinding = null
        _desktopBinding = null
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

    private data class ModelProviderGroup(
        val provider: ProviderEntity,
        val models: List<String>
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
