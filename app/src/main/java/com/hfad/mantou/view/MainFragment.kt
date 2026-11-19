package com.hfad.mantou.view

import android.Manifest
import android.app.Dialog
import android.content.Context
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
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.MimeTypeMap
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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.slider.Slider
import com.hfad.mantou.R
import com.hfad.mantou.adapter.ChatAdapter
import com.hfad.mantou.adapter.DesktopAppAdapter
import com.hfad.mantou.adapter.SessionAdapter
import com.hfad.mantou.adapter.WorkspaceFileAdapter
import com.hfad.mantou.adapter.WorkspaceFileOpenMode
import com.hfad.mantou.adapter.WorkspaceFileOpenPolicy
import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.data.GenerateTaskState
import com.hfad.mantou.data.api.VoiceInputConfig
import com.hfad.mantou.data.api.VoiceTranscriptionApiService
import com.hfad.mantou.data.api.WavAudioRecorder
import com.hfad.mantou.data.database.AppDatabase
import com.hfad.mantou.data.database.ChatSessionEntity
import com.hfad.mantou.data.preferences.AppearanceSettingsStore
import com.hfad.mantou.data.preferences.ActiveModelStore
import com.hfad.mantou.data.preferences.ContextLimitStore
import com.hfad.mantou.data.preferences.HarnessSettingsStore
import com.hfad.mantou.data.preferences.VoiceInputModelStore
import com.hfad.mantou.data.preferences.WallpaperStore
import com.hfad.mantou.databinding.FragmentMainBinding
import com.hfad.mantou.databinding.LayoutChatPageBinding
import com.hfad.mantou.databinding.LayoutDesktopPageBinding
import com.hfad.mantou.databinding.LayoutWorkspacePageBinding
import com.hfad.mantou.databinding.SheetWorkspacePickerBinding
import com.hfad.mantou.data.repository.ProviderRepository
import com.hfad.mantou.tool.impl.CameraPhotoBridge
import com.hfad.mantou.tool.impl.WebAppDeviceHost
import com.hfad.mantou.tool.impl.CameraPhotoHost
import com.hfad.mantou.utils.AgentWorkspace
import com.hfad.mantou.utils.AutoContrastColor
import com.hfad.mantou.utils.ContextTokenCounter
import com.hfad.mantou.utils.DesktopAppScanner
import com.hfad.mantou.utils.WorkspaceNode
import com.hfad.mantou.utils.harness.HarnessValidationProfile
import com.hfad.mantou.utils.project.ConversationWorkspace
import com.hfad.mantou.utils.project.WorkspaceResourceDocuments
import com.hfad.mantou.view.dialog.ManTouDialog
import com.hfad.mantou.view.dialog.ManTouDialog.Action
import com.hfad.mantou.view.dialog.ManTouDialog.Option
import com.hfad.mantou.view.dialog.ManTouSheet
import com.hfad.mantou.view.dialog.dialogField
import com.hfad.mantou.view.dialog.dialogRipple
import com.hfad.mantou.view.dialog.dialogShape
import com.hfad.mantou.view.glass.LiquidGlass
import com.hfad.mantou.view.markdown.MarkdownTextView
import com.hfad.mantou.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        const val PENDING_RESOURCE_WORKSPACE = "pending_resource_workspace"
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
    private lateinit var mainPager: ViewPager2
    private var topBarBaseHeight = 0
    private var inputContainerBasePaddingBottom = 0
    private var lastImeVisible = false
    private var lastSystemBarBottomInset = 0
    private var lastAppliedBottomInset = Int.MIN_VALUE
    private var currentContextTokens = 0
    private var currentConsumedTokens = 0L
    private var currentTokenUsageIncludesEstimate = false
    private var cachedChatSystemPrompt: String? = null
    private var forceScrollToLatestMessage = false
    private var isChatScrollActive = false
    private var pendingMessagesWhileScrolling: List<ChatMessage>? = null
    private var latestChatMessages: List<ChatMessage> = emptyList()
    private var latestChatMessagesSessionId: Long? = null
    private var isTaskRunning = false
    private val selectedImageUris = mutableListOf<Uri>()
    private var activeAppWebView: WebView? = null
    private lateinit var cameraPhotoHost: CameraPhotoHost
    private lateinit var webAppDeviceHost: WebAppDeviceHost
    private var voiceRecorder: WavAudioRecorder? = null
    private var voiceInputFile: File? = null
    private var voiceRecordingConfig: VoiceInputConfig? = null
    private var isVoiceRecording = false
    private var isVoiceTranscribing = false
    private var pendingVoiceStartAfterPermission = false
    private var notificationPermissionRequestInFlight = false
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
    private var runningSessionIds: Set<Long> = emptySet()
    private var generateTaskStates: Map<Long, GenerateTaskState> = emptyMap()
    private var currentGenerateTaskState: GenerateTaskState? = null
    private var codeEditorDialog: CodeViewerDialog? = null
    private var actionMenuDialog: Dialog? = null
    private var modelPickerDialog: ManTouSheet? = null
    private var drawerSearchQuery: String = ""
    private var showArchivedSessions: Boolean = false
    private var adaptiveTextColor: Int = Color.rgb(32, 37, 53)
    private var pendingResourceWorkspace: String? = null
    private var pendingWorkspaceExport: String? = null

    private val workspaceExportPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = pendingWorkspaceExport
        pendingWorkspaceExport = null
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && path != null && uri != null) {
            val context = requireContext().applicationContext
            lifecycleScope.launch {
                val exported = withContext(Dispatchers.IO) {
                    runCatching {
                        val file = exportableWorkspaceFile(context, path) ?: error("文件已移走或删除")
                        file.inputStream().use { input ->
                            checkNotNull(context.contentResolver.openOutputStream(uri, "w")).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
                Toast.makeText(
                    context,
                    if (exported.isSuccess) "文件已导出" else "导出失败：${exported.exceptionOrNull()?.message.orEmpty()}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private val workspaceResourcePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val workspace = pendingResourceWorkspace
        pendingResourceWorkspace = null
        if (workspace != null && uris.isNotEmpty()) importWorkspaceResources(workspace, uris)
    }

    private val workspaceResourceFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val workspace = pendingResourceWorkspace
        pendingResourceWorkspace = null
        if (workspace != null && uri != null) importWorkspaceResourceFolder(workspace, uri)
    }

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

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationPermissionRequestInFlight = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingResourceWorkspace = savedInstanceState?.getString(PENDING_RESOURCE_WORKSPACE)
        pendingWorkspaceExport = savedInstanceState?.getString("pending_workspace_export")
        webAppDeviceHost = WebAppDeviceHost(requireActivity(), this, this)
        cameraPhotoHost = CameraPhotoHost(
            activity = requireActivity(),
            takePictureLauncher = takePictureLauncher,
            requestPermissionLauncher = requestCameraPermissionLauncher,
            webViewProvider = { activeAppWebView }
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PENDING_RESOURCE_WORKSPACE, pendingResourceWorkspace)
        outState.putString("pending_workspace_export", pendingWorkspaceExport)
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

        mainPager = obtainMainPager()
        binding.topBar.bringToFront()
        setupAppBar()
        setupMainPager()
        setupWorkspacePage()
        setupDesktopPage()
        
        // 初始化聊天 RecyclerView
        setupChatRecyclerView()
        CameraPhotoBridge.attach(this)
        
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
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(100)
                    val currentChatBinding = _chatBinding ?: return@launch
                    if (!currentChatBinding.etInput.hasFocus()) {
                        switchToIdleState()
                    }
                }
            }
        }

        chatBinding.ivAdd.setOnClickListener {
            openImagePicker()
        }

        chatBinding.workspaceSelector.setOnClickListener {
            showWorkspaceDirectoryPicker()
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

        chatBinding.generateCodeCard.setOnClickListener {
            showGenerateCodeEditor()
        }

        chatBinding.chipStartTask.setOnClickListener {
            fillInputWithAppPrompt()
        }

        chatBinding.chipLongTermMemory.setOnClickListener {
            fillInputWithMemoryPrompt()
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
        renderSelectedImagePreviews()
        updateCurrentContextTokens()
        updateSendButtonState(false)
        updateVoiceInputButtonState()
        applyWallpaper()

        LiquidGlass.refresh(binding.root)

        binding.root.post {
            if (_binding == null || !isAdded) return@post
            setupSessionRecyclerView()
            setupDrawerMenu()
            LiquidGlass.refresh(binding.root)
        }
    }

    override fun requestCameraPhoto(callbackName: String?) {
        cameraPhotoHost.requestCameraPhoto(callbackName)
    }

    private fun obtainMainPager(): ViewPager2 {
        findMainPager(binding.root)?.let { return it }

        return ViewPager2(requireContext()).apply {
            id = View.generateViewId()
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mt_background))
            layoutParams = ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
            binding.root.addView(this)
        }
    }

    private fun findMainPager(view: View): ViewPager2? {
        if (view is ViewPager2) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findMainPager(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun initInsets() {
        topBarBaseHeight = binding.topBar.layoutParams.height.takeIf { it > 0 } ?: dp(72)
        inputContainerBasePaddingBottom = chatBinding.inputContainer.paddingBottom
        updatePageTopScrollBoundaries(topBarBaseHeight)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            applyAppBarInsets(insets)
            applyChatInsets(insets)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            binding.root,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    applyAppBarInsets(insets)
                    applyChatInsets(insets)
                    return insets
                }
            }
        )
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun applyAppBarInsets(insets: WindowInsetsCompat) {
        val topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
        val targetHeight = topBarBaseHeight + topInset
        val params = binding.topBar.layoutParams
        if (params.height != targetHeight) {
            params.height = targetHeight
            binding.topBar.layoutParams = params
        }
        if (binding.topBar.paddingTop != topInset) {
            binding.topBar.updatePadding(top = topInset)
        }
        updatePageTopScrollBoundaries(targetHeight)
    }

    private fun updatePageTopScrollBoundaries(topBarHeight: Int) {
        val chatPaddingTop = topBarHeight + dp(10)
        if (chatBinding.rvChat.paddingTop != chatPaddingTop) {
            chatBinding.rvChat.updatePadding(top = chatPaddingTop)
        }

        val desktopPaddingTop = topBarHeight + dp(16)
        if (desktopBinding.rvDesktopApps.paddingTop != desktopPaddingTop) {
            desktopBinding.rvDesktopApps.updatePadding(top = desktopPaddingTop)
        }

        val workspacePathParams = workspaceBinding.workspacePathBar.layoutParams as ConstraintLayout.LayoutParams
        val workspaceMarginTop = topBarHeight + dp(20)
        if (workspacePathParams.topMargin != workspaceMarginTop) {
            workspacePathParams.topMargin = workspaceMarginTop
            workspaceBinding.workspacePathBar.layoutParams = workspacePathParams
        }
    }

    private fun applyChatInsets(insets: WindowInsetsCompat) {
        val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
        val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        val rawSystemBarBottomInset = systemBarsInsets.bottom
        if (rawSystemBarBottomInset > 0) {
            lastSystemBarBottomInset = rawSystemBarBottomInset
        }
        val systemBarBottomInset = if (rawSystemBarBottomInset > 0) {
            rawSystemBarBottomInset
        } else {
            lastSystemBarBottomInset
        }
        val imeBottomInset = imeInsets.bottom
        val bottomInset = if (imeVisible || imeBottomInset > systemBarBottomInset) {
            (imeBottomInset - systemBarBottomInset).coerceAtLeast(systemBarBottomInset)
        } else {
            systemBarBottomInset
        }
        val targetPaddingBottom = inputContainerBasePaddingBottom + bottomInset

        if (chatBinding.inputContainer.paddingBottom != targetPaddingBottom) {
            chatBinding.inputContainer.updatePadding(bottom = targetPaddingBottom)
        }
        updateChatListScrollBoundary()

        if (imeVisible && (!lastImeVisible || lastAppliedBottomInset != bottomInset)) {
            scrollChatToBottom()
        }

        lastImeVisible = imeVisible
        lastAppliedBottomInset = bottomInset
    }

    private fun updateChatListScrollBoundary() {
        val generateCardInset = if (chatBinding.generateCodeCard.visibility == View.VISIBLE) {
            val layoutParams = chatBinding.generateCodeCard.layoutParams as? ViewGroup.MarginLayoutParams
            val cardHeight = layoutParams?.height?.takeIf { it > 0 }
                ?: chatBinding.generateCodeCard.height
            cardHeight + (layoutParams?.bottomMargin ?: 0)
        } else {
            0
        }
        val targetPaddingBottom = dp(8) + chatBinding.inputContainer.height + generateCardInset
        if (chatBinding.rvChat.paddingBottom != targetPaddingBottom) {
            chatBinding.rvChat.updatePadding(bottom = targetPaddingBottom)
        }
    }

    private fun scrollChatToBottom() {
        if (!::chatAdapter.isInitialized) return
        val messageCount = chatAdapter.itemCount
        if (messageCount <= 0) return
        chatBinding.rvChat.post {
            scrollChatByRemainingDistanceToBottom()
            chatBinding.rvChat.post {
                scrollChatByRemainingDistanceToBottom()
            }
        }
    }

    private fun scrollChatByRemainingDistanceToBottom() {
        if (isSelectingChatText()) return
        val rvChat = chatBinding.rvChat
        val distanceToBottom = rvChat.computeVerticalScrollRange() -
                rvChat.computeVerticalScrollOffset() -
                rvChat.computeVerticalScrollExtent()
        if (distanceToBottom > 0) {
            rvChat.scrollBy(0, distanceToBottom)
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
        updateGreetingVisibility(
            messages.isNotEmpty() || currentGenerateTaskState?.harnessEvents?.isNotEmpty() == true
        )
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
        val displayMessages = buildDisplayMessages(messages)
        chatAdapter.submitList(displayMessages) {
            if (isChatScrollActive) {
                pendingMessagesWhileScrolling = messages
                return@submitList
            }
            if (isSelectingChatText()) return@submitList
            when {
                messages.isNotEmpty() && followNewMessages -> scrollChatToBottom()
                anchor != null -> restoreChatViewportAnchor(anchor)
            }
        }
        updateCurrentContextTokens(messages)
    }

    private fun isSelectingChatText(): Boolean =
        (chatBinding.rvChat.findFocus() as? MarkdownTextView)?.isSelectingText == true

    private fun buildDisplayMessages(messages: List<ChatMessage>): List<ChatMessage> {
        val taskState = currentGenerateTaskState ?: return messages
        if (taskState.sessionId != viewModel.currentSessionId.value) return messages
        if (taskState.harnessEvents.isEmpty()) return messages

        val placeholderId = -taskState.sessionId.coerceAtLeast(1L)
        val visibleMessages = messages.filterNot { message ->
            message.messageId == placeholderId && message.isStreaming && message.content.isBlank()
        }.toMutableList()
        val harnessState = taskState.copy(
            phase = when (taskState.phase) {
                GenerateTaskState.Phase.COMPLETED,
                GenerateTaskState.Phase.PAUSED,
                GenerateTaskState.Phase.ERROR -> taskState.phase
                else -> GenerateTaskState.Phase.REQUESTING_MODEL
            },
            code = "",
            filePath = null,
            status = if (!taskState.isRunning) taskState.status else "",
            diagnostics = emptyList(),
            updatedAt = taskState.harnessEvents.last().timestamp
        )
        val harnessMessage = ChatMessage(
            messageId = harnessMessageId(taskState.sessionId),
            role = ChatMessage.ROLE_HARNESS,
            content = "",
            timestamp = harnessState.updatedAt,
            isStreaming = harnessState.isRunning,
            generateTaskState = harnessState
        )
        val taskFilePath = taskState.filePath?.takeIf(String::isNotBlank)
        val previewIndex = visibleMessages.lastIndex.takeIf { index ->
            index >= 0 && taskFilePath != null &&
                visibleMessages[index].appHtmlPath == taskFilePath
        } ?: -1
        if (previewIndex >= 0) {
            visibleMessages.add(previewIndex, harnessMessage)
        } else {
            visibleMessages.add(harnessMessage)
        }
        return visibleMessages
    }

    private fun harnessMessageId(sessionId: Long): Long {
        return Long.MIN_VALUE + sessionId.coerceAtLeast(1L)
    }

    private fun captureChatViewportAnchor(): ChatViewportAnchor? {
        val layoutManager = chatBinding.rvChat.layoutManager as? LinearLayoutManager ?: return null
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return null
        val view = layoutManager.findViewByPosition(position) ?: return null
        val messageId = chatAdapter.currentList.getOrNull(position)?.messageId
        return ChatViewportAnchor(messageId, position, view.top - chatBinding.rvChat.paddingTop)
    }

    private fun restoreChatViewportAnchor(anchor: ChatViewportAnchor) {
        val layoutManager = chatBinding.rvChat.layoutManager as? LinearLayoutManager ?: return
        val lastPosition = (chatAdapter.itemCount - 1).coerceAtLeast(0)
        val anchoredPosition = anchor.messageId
            ?.let { messageId -> chatAdapter.currentList.indexOfFirst { it.messageId == messageId } }
            ?.takeIf { it != -1 }
            ?: anchor.position
        layoutManager.scrollToPositionWithOffset(
            anchoredPosition.coerceIn(0, lastPosition),
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
        binding.desktopTab.setOnClickListener {
            mainPager.setCurrentItem(PAGE_DESKTOP, true)
        }
        binding.chatTab.setOnClickListener {
            mainPager.setCurrentItem(PAGE_CHAT, true)
        }
        binding.workspaceTab.setOnClickListener {
            mainPager.setCurrentItem(PAGE_WORKSPACE, true)
        }
        binding.newChat.setOnClickListener {
            if (currentPagerPage == PAGE_CHAT) {
                createNewSession()
            } else {
                mainPager.setCurrentItem(PAGE_CHAT, true)
            }
        }
        updateAppBarAction(PAGE_CHAT)
        binding.appBarCapsule.post {
            updateAppBarTitleSlide(PAGE_CHAT, 0f)
        }
    }

    private fun setupMainPager() {
        mainPager.adapter = StaticPagerAdapter(
            listOf(desktopBinding.root, chatBinding.root, workspaceBinding.root)
        )
        mainPager.offscreenPageLimit = 3
        // 初始页保持 chat（index = 1），桌面在左、workspace 在右
        mainPager.setCurrentItem(PAGE_CHAT, false)
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
                } else if (position == PAGE_WORKSPACE && ::workspaceFileAdapter.isInitialized) {
                    refreshWorkspaceTree()
                }
            }
        }
        mainPager.registerOnPageChangeCallback(pagerCallback!!)
        binding.appBarCapsule.post {
            currentPagerPage = mainPager.currentItem
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

    private fun showWorkspaceDirectoryPicker() {
        val context = requireContext()
        val workspaces = ConversationWorkspace(context.filesDir)
        val picker = SheetWorkspacePickerBinding.inflate(layoutInflater)
        val dialog = ManTouSheet(context, picker.root, expandedHeightFraction = 0.82f)
        val running = viewModel.currentSessionId.value in runningSessionIds
        var path = viewModel.selectedWorkspace.value ?: ConversationWorkspace.ROOT_PATH
        val adapter = WorkspaceFileAdapter(
            onFileClick = ::handleWorkspaceFileClick,
            onFileLongClick = ::handleWorkspaceFileLongClick,
            onDirectoryClick = { node ->
                path = node.displayPath
                picker.tvDirectoryPath.text = path
                (picker.rvDirectoryTree.adapter as WorkspaceFileAdapter).selectFile(path)
            }
        )
        picker.rvDirectoryTree.layoutManager = LinearLayoutManager(context)
        picker.rvDirectoryTree.adapter = adapter
        adapter.updateTextColor(ContextCompat.getColor(context, R.color.mt_text_primary))
        fun focusDirectory() {
            picker.tvDirectoryPath.text = path
            adapter.selectFile(path)
            val position = adapter.expandDirectory(path)
            if (position >= 0) picker.rvDirectoryTree.scrollToPosition(position)
        }
        fun reloadTree() {
            viewLifecycleOwner.lifecycleScope.launch {
                val nodes = withContext(Dispatchers.IO) { AgentWorkspace.loadWorkspaceTree(context) }
                adapter.submitNodes(nodes)
                focusDirectory()
            }
        }
        picker.btnUseDirectory.isEnabled = !running
        picker.btnUseDirectory.setOnClickListener {
            if (runCatching { workspaces.directory(path) }.isSuccess) {
                viewModel.selectWorkspace(path)
                dialog.dismiss()
            } else {
                Toast.makeText(context, "该目录由应用管理", Toast.LENGTH_SHORT).show()
            }
        }
        picker.btnNewDirectory.setOnClickListener {
            showCreateWorkspaceDialog(workspaces, path) { created ->
                path = created
                reloadTree()
                refreshWorkspaceTree()
            }
        }
        picker.btnAddResource.setOnClickListener {
            ManTouDialog(
                context,
                title = "导入资源",
                message = "选择要添加到当前工作区的内容",
                options = listOf(
                    Option("选择文件", "可一次选择多个文件", R.drawable.ic_file_outline) {
                        pendingResourceWorkspace = path
                        dialog.dismiss()
                        workspaceResourcePicker.launch(arrayOf("*/*"))
                    },
                    Option("选择文件夹", "导入整个文件夹及其中的文件", R.drawable.ic_folder_outline) {
                        pendingResourceWorkspace = path
                        dialog.dismiss()
                        workspaceResourceFolderPicker.launch(null)
                    }
                ),
                secondary = Action("取消")
            ).show()
        }
        actionMenuDialog?.dismiss()
        actionMenuDialog = dialog
        dialog.setOnDismissListener { if (actionMenuDialog === dialog) actionMenuDialog = null }
        dialog.show()
        picker.tvDirectoryPath.text = path
        reloadTree()
    }

    private fun showCreateWorkspaceDialog(
        workspaces: ConversationWorkspace,
        parentPath: String,
        onCreated: (String) -> Unit
    ) {
        val context = requireContext()
        val nameInput = context.dialogField("目录名称")
        ManTouDialog(
            context,
            title = "新建文件夹",
            message = parentPath,
            content = nameInput,
            secondary = Action("取消"),
            primary = Action("创建", dismissOnClick = false) { dialog ->
                runCatching { workspaces.create(parentPath, nameInput.text.toString()) }
                    .onSuccess { path ->
                        onCreated(path)
                        dialog.dismiss()
                    }
                    .onFailure { dialog.showFieldError(it.message ?: "无法创建工作区") }
            }
        ).show()
    }

    private fun importWorkspaceResources(workspace: String, uris: List<Uri>) {
        val context = requireContext().applicationContext
        val resolver = context.contentResolver
        val workspaces = ConversationWorkspace(context.filesDir)
        viewLifecycleOwner.lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    runCatching {
                        val name = queryDisplayName(uri, context)
                        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri))
                        val fileName = if ('.' !in name && extension != null) "$name.$extension" else name
                        workspaces.importResource(workspace, fileName) {
                            resolver.openInputStream(uri) ?: error("无法读取“$fileName”")
                        }
                    }
                }
            }
            refreshWorkspaceTree()
            val imported = results.count { it.isSuccess }
            val failures = results.size - imported
            val message = if (failures == 0) {
                "已导入 $imported 个资源到 $workspace"
            } else {
                "已导入 $imported 个资源，$failures 个失败。 " +
                    results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }.orEmpty()
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun importWorkspaceResourceFolder(workspace: String, uri: Uri) {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val folder = WorkspaceResourceDocuments(context.contentResolver, uri).readFolder()
                    ConversationWorkspace(context.filesDir).importResourceFolder(workspace, folder)
                }
            }
            val message = result.fold(
                onSuccess = { imported ->
                    refreshWorkspaceTree()
                    val name = imported.path.substringAfterLast('/')
                    val summary = "已导入「$name」，共 ${imported.importedCount} 个资源"
                    if (imported.skippedCount == 0) summary
                    else "$summary，跳过 ${imported.skippedCount} 个不支持或隐藏的条目"
                },
                onFailure = { "文件夹导入失败：${it.message ?: "无法读取文件夹"}" }
            )
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleWorkspaceFileClick(node: WorkspaceNode) {
        val file = node.absolutePath?.let(::File) ?: return
        if (!file.exists()) {
            Toast.makeText(requireContext(), "文件不存在", Toast.LENGTH_SHORT).show()
            refreshWorkspaceTree()
            return
        }

        when (WorkspaceFileOpenPolicy.modeForWorkspacePath(node.displayPath, file.name)) {
            WorkspaceFileOpenMode.CODE_VIEWER -> openWorkspaceCodeViewer(file)
            WorkspaceFileOpenMode.WEB_APP -> {
                val intent = Intent(requireContext(), VirtualAppActivity::class.java).apply {
                    putExtra(VirtualAppActivity.EXTRA_HTML_PATH, file.absolutePath)
                }
                startActivity(intent)
            }
            WorkspaceFileOpenMode.JSON -> {
                val intent = Intent(requireContext(), JsonViewerActivity::class.java).apply {
                    putExtra(JsonViewerActivity.EXTRA_JSON_PATH, file.absolutePath)
                }
                startActivity(intent)
            }
            WorkspaceFileOpenMode.TEXT -> openTextFileEditor(file)
            WorkspaceFileOpenMode.IMAGE -> showWorkspaceImage(file)
            WorkspaceFileOpenMode.UNSUPPORTED -> showUnsupportedWorkspaceFile(file)
        }
    }

    private fun showWorkspaceImage(file: File) = showImagePreview(file, file.name)

    private fun showImagePreview(source: Any, title: String) {
        val image = ImageView(requireContext()).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = title
        }
        val preview = FrameLayout(requireContext()).apply {
            val height = (resources.configuration.screenHeightDp / 2).coerceAtMost(320)
            addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)))
        }
        val dialog = ManTouDialog(requireContext(), title = title, content = preview, primary = Action("关闭"))
        dialog.show()
        Glide.with(this).load(source).error(R.drawable.ic_input_image).into(image)
        dialog.setOnDismissListener { Glide.with(image).clear(image) }
    }

    private fun openWorkspaceCodeViewer(file: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            val contentResult = withContext(Dispatchers.IO) {
                runCatching { file.readText() }
            }
            val content = contentResult.getOrElse {
                Toast.makeText(requireContext(), "读取失败: ${it.message}", Toast.LENGTH_SHORT).show()
                return@launch
            }
            createCodeViewer().apply {
                showFile(file.name, content)
                show()
            }
        }
    }

    private fun showUnsupportedWorkspaceFile(file: File) {
        val typeName = file.extension.ifBlank { "该" }
        Toast.makeText(requireContext(), "暂不支持打开 $typeName 类型文件", Toast.LENGTH_SHORT).show()
    }

    private fun openTextFileEditor(file: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) { runCatching { file.readText() } }.getOrElse {
                Toast.makeText(requireContext(), "读取失败: ${it.message}", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val context = requireContext()
            val editor = context.dialogField(
                hint = "文件内容",
                value = content,
                input = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                multiline = true
            ).apply {
                typeface = Typeface.MONOSPACE
                maxHeight = dp((resources.configuration.screenHeightDp * 0.46f).toInt().coerceIn(120, 420))
                minLines = 4
            }
            ManTouDialog(
                context,
                title = file.name,
                content = editor,
                wide = true,
                secondary = Action("取消"),
                primary = Action("保存", dismissOnClick = false) { dialog ->
                    val button = checkNotNull(dialog.primaryButton)
                    val text = editor.text.toString()
                    button.isEnabled = false
                    viewLifecycleOwner.lifecycleScope.launch {
                        val saveResult = withContext(Dispatchers.IO) { runCatching { file.writeText(text) } }
                        button.isEnabled = true
                        saveResult.onSuccess {
                            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                            refreshWorkspaceTree()
                            dialog.dismiss()
                        }.onFailure {
                            Toast.makeText(context, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ).show()
        }
    }

    private fun handleWorkspaceFileLongClick(node: WorkspaceNode): Boolean {
        val resourceMarker = "/${ConversationWorkspace.IMPORTED_ASSET_PATH}/"
        val resourceWorkspace = node.displayPath.takeIf { resourceMarker in it }
            ?.substringBeforeLast(resourceMarker)
        if (resourceWorkspace == null && !WorkspaceFileOpenPolicy.isGeneratedAppPath(node.displayPath)) {
            val file = exportableWorkspaceFile(requireContext(), node.displayPath) ?: return false
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
            showActionMenu(listOf(Option("导出文件", icon = R.drawable.ic_file_outline) {
                pendingWorkspaceExport = node.displayPath
                workspaceExportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                    putExtra(Intent.EXTRA_TITLE, file.name)
                })
            }))
            return true
        }
        val file = node.absolutePath?.let(::File) ?: return false
        if (!file.exists()) {
            Toast.makeText(requireContext(), "文件不存在", Toast.LENGTH_SHORT).show()
            refreshWorkspaceTree()
            return true
        }

        if (resourceWorkspace != null) {
            val workspaces = ConversationWorkspace(requireContext().filesDir)
            confirmDeleteWorkspaceFile(file) { workspaces.deleteResource(resourceWorkspace, file) }
            return true
        }

        showActionMenu(
            listOf(
                Option("改名", icon = R.drawable.ic_edit_outline) {
                    showRenameWorkspaceFileDialog(file)
                },
                Option("删除", icon = R.drawable.ic_delete_outline, destructive = true) {
                    confirmDeleteWorkspaceFile(file)
                }
            )
        )
        return true
    }

    private fun exportableWorkspaceFile(context: Context, path: String): File? {
        val workspaces = ConversationWorkspace(context.filesDir)
        val root = workspaces.linuxDirectory(path.substringBeforeLast('/')) ?: return null
        return runCatching { workspaces.linuxFile(root, path.substringAfterLast('/')) }
            .getOrNull()?.takeIf(File::isFile)
    }

    private fun showRenameWorkspaceFileDialog(file: File) {
        val context = requireContext()
        val editor = context.dialogField(
            hint = "文件名",
            value = file.name,
            input = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        ).apply {
            setSelection(0, file.nameWithoutExtension.length.coerceAtMost(file.name.length))
        }
        ManTouDialog(
            context,
            title = "改名文件",
            content = editor,
            secondary = Action("取消"),
            primary = Action("保存", dismissOnClick = false) { dialog ->
                val newName = normalizeWorkspaceFileName(editor.text.toString(), file.extension)
                if (newName == null) {
                    dialog.showFieldError("文件名不能为空")
                    return@Action
                }
                if (newName == file.name) {
                    dialog.dismiss()
                    return@Action
                }
                val target = File(file.parentFile, newName)
                when {
                    target.exists() -> dialog.showFieldError("已存在同名文件")
                    file.renameTo(target) -> {
                        Toast.makeText(context, "已改名", Toast.LENGTH_SHORT).show()
                        refreshWorkspaceTree()
                        dialog.dismiss()
                    }
                    else -> dialog.showFieldError("改名失败")
                }
            }
        ).show()
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

    private fun confirmDeleteWorkspaceFile(file: File, delete: () -> Boolean = file::delete) {
        ManTouDialog(
            requireContext(),
            title = "删除文件",
            message = "确定要删除「${file.name}」吗？",
            secondary = Action("取消"),
            primary = Action("删除", destructive = true) {
                val deleted = runCatching(delete).getOrDefault(false)
                Toast.makeText(requireContext(), if (deleted) "已删除" else "删除失败", Toast.LENGTH_SHORT).show()
                refreshWorkspaceTree()
            }
        ).show()
    }

    private fun updateAppBarTitleSlide(position: Int, positionOffset: Float) {
        val globalProgress = (position + positionOffset).coerceIn(0f, 2f)
        val availableTitleWidth = binding.titleSwitcher.width
        if (availableTitleWidth <= 0) return

        val capsuleParams = binding.appBarCapsule.layoutParams as FrameLayout.LayoutParams
        val targetCapsuleWidth = dp(118).coerceAtMost(availableTitleWidth)
        if (capsuleParams.width != targetCapsuleWidth) {
            capsuleParams.width = targetCapsuleWidth
            binding.appBarCapsule.layoutParams = capsuleParams
            binding.appBarCapsule.post {
                updateAppBarSegmentIndicator(globalProgress)
            }
            return
        }

        updateAppBarSegmentIndicator(globalProgress)
    }

    private fun updateAppBarSegmentIndicator(globalProgress: Float) {
        val capsule = binding.appBarCapsule
        val availableWidth = capsule.width - capsule.paddingLeft - capsule.paddingRight
        val availableHeight = capsule.height - capsule.paddingTop - capsule.paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) return

        val segmentWidth = availableWidth / 3f
        val indicator = binding.appBarSegmentIndicator
        val indicatorParams = indicator.layoutParams as FrameLayout.LayoutParams
        val targetWidth = segmentWidth.roundToInt()
        if (indicatorParams.width != targetWidth || indicatorParams.height != availableHeight) {
            indicatorParams.width = targetWidth
            indicatorParams.height = availableHeight
            indicator.layoutParams = indicatorParams
        }

        indicator.translationX = segmentWidth * globalProgress
        updateAppBarTabTint(globalProgress.roundToInt().coerceIn(PAGE_DESKTOP, PAGE_WORKSPACE))
    }

    private fun updateAppBarTabTint(selectedPage: Int) {
        val selectedTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.mt_on_primary))
        val idleTint = ColorStateList.valueOf(ColorUtils.setAlphaComponent(adaptiveTextColor, 190))

        binding.ivDesktopTab.imageTintList = if (selectedPage == PAGE_DESKTOP) selectedTint else idleTint
        binding.ivChatTab.imageTintList = if (selectedPage == PAGE_CHAT) selectedTint else idleTint
        binding.ivWorkspaceTab.imageTintList = if (selectedPage == PAGE_WORKSPACE) selectedTint else idleTint

        binding.desktopTab.isSelected = selectedPage == PAGE_DESKTOP
        binding.chatTab.isSelected = selectedPage == PAGE_CHAT
        binding.workspaceTab.isSelected = selectedPage == PAGE_WORKSPACE
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
        val wallpaperBackground = binding.wallpaperBackground
        wallpaperBackground.visibility = View.VISIBLE
        if (!wallpaperBackground.showWallpaper(wallpaperUri) && wallpaperUri != null) {
            WallpaperStore.clearWallpaper(requireContext())
            Toast.makeText(requireContext(), "壁纸读取失败，已恢复默认壁纸", Toast.LENGTH_SHORT).show()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blur = appearanceSettings.backgroundBlur
            wallpaperBackground.setRenderEffect(
                if (blur > 0f) {
                    RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
                } else {
                    null
                }
            )
        }
        val analysis = AutoContrastColor.analyze(
            requireContext(),
            appearanceSettings,
            wallpaperBackground.drawable
        )
        binding.wallpaperMask.visibility =
            if (Color.alpha(analysis.maskColor) > 0) View.VISIBLE else View.GONE
        binding.wallpaperMask.setBackgroundColor(analysis.maskColor)
        mainPager.setBackgroundColor(Color.TRANSPARENT)
        chatBinding.root.setBackgroundColor(Color.TRANSPARENT)
        workspaceBinding.root.setBackgroundColor(Color.TRANSPARENT)
        desktopBinding.root.setBackgroundColor(Color.TRANSPARENT)
        updateNewChatAppearance()
        dispatchAutoTextColor(appearanceSettings, analysis)
    }

    private fun updateNewChatAppearance() {
        binding.newChat.tag = "glass:circle"
        binding.newChat.setBackgroundColor(Color.TRANSPARENT)
        binding.newChat.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), R.color.input_action_blue)
        )
        LiquidGlass.refresh(binding.root)
    }

    private fun dispatchAutoTextColor(
        settings: AppearanceSettingsStore.Settings,
        analysis: AutoContrastColor.Analysis
    ) {
        val autoColor = analysis.textColor
        val effective = if (settings.hasFixedTextColor) settings.chatTextColor else autoColor
        adaptiveTextColor = effective
        if (::chatAdapter.isInitialized) {
            chatAdapter.updateAutoTextColor(autoColor)
        }
        if (::desktopAppAdapter.isInitialized) {
            desktopAppAdapter.updateTextColor(effective)
        }
        if (::workspaceFileAdapter.isInitialized) {
            workspaceFileAdapter.updateTextColor(effective)
        }
        applyAdaptiveForeground(effective)
        applySystemBarContrast(
            analysis.statusBarTextColor,
            analysis.navigationBarTextColor
        )
    }

    private fun applyAdaptiveForeground(color: Int) {
        val secondaryColor = ColorUtils.setAlphaComponent(color, 184)
        chatBinding.etInput.setTextColor(color)
        chatBinding.etInput.setHintTextColor(secondaryColor)
        chatBinding.tvActiveModel.setTextColor(color)
        chatBinding.ivAdd.imageTintList = ColorStateList.valueOf(color)
        chatBinding.ivContextLimit.setForegroundColor(color)
        updateVoiceInputButtonState()
        chatBinding.tvGreeting.setTextColor(color)
        chatBinding.chipStartTask.setTextColor(color)
        chatBinding.chipLongTermMemory.setTextColor(color)
        chatBinding.tvConversationWorkspace.setTextColor(color)
        chatBinding.ivConversationWorkspace.imageTintList = ColorStateList.valueOf(secondaryColor)
        chatBinding.ivConversationWorkspaceChevron.imageTintList = ColorStateList.valueOf(secondaryColor)
        desktopBinding.tvDesktopEmpty.setTextColor(secondaryColor)
        workspaceBinding.tvWorkspacePath.setTextColor(color)
        workspaceBinding.ivWorkspacePathIcon.imageTintList = ColorStateList.valueOf(secondaryColor)
        binding.ivMenu.imageTintList = ColorStateList.valueOf(secondaryColor)
        updateAppBarTabTint(currentPagerPage)
    }

    private fun applySystemBarContrast(statusBarColor: Int, navigationBarColor: Int) {
        (activity as? MainActivity)?.updateSystemBarIconAppearance(
            darkStatusBarIcons = ColorUtils.calculateLuminance(statusBarColor) < 0.5,
            darkNavigationBarIcons = ColorUtils.calculateLuminance(navigationBarColor) < 0.5
        )
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
        ManTouDialog(
            requireContext(),
            title = "清空所有会话",
            message = "确定要删除所有历史会话吗？此操作不可恢复。",
            secondary = Action("取消"),
            primary = Action("清空", destructive = true) {
                viewModel.deleteAllSessions()
                Toast.makeText(requireContext(), "已清空所有会话", Toast.LENGTH_SHORT).show()
                (activity as? MainActivity)?.closeDrawer()
            }
        ).show()
    }

    /**
     * 创建新会话
     */
    private fun createNewSession() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!viewModel.createEmptySession()) return@launch
            chatBinding.etInput.text?.clear()
            clearSelectedImages()
            Toast.makeText(requireContext(), "已创建新会话", Toast.LENGTH_SHORT).show()
        }
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
            itemAnimator = null
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

        sessionAdapter.setRunningSessionIds(runningSessionIds)
        sessionAdapter.submitList(visibleSessions)
    }

    /**
     * 显示会话操作菜单
     */
    private fun showSessionActions(session: ChatSessionEntity) {
        val archiveLabel = if (session.isArchived) "取消归档" else "归档"
        showActionMenu(
            listOf(
                Option(archiveLabel, icon = R.drawable.ic_archive_outline) {
                    val nextArchived = !session.isArchived
                    viewModel.setSessionArchived(session.sessionId, nextArchived)
                    Toast.makeText(
                        requireContext(),
                        if (nextArchived) "已归档会话" else "已取消归档",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                Option("删除", icon = R.drawable.ic_delete_outline, destructive = true) {
                    showDeleteSessionDialog(session)
                }
            )
        )
    }

    /**
     * 显示删除会话确认对话框
     */
    private fun showDeleteSessionDialog(session: ChatSessionEntity) {
        ManTouDialog(
            requireContext(),
            title = "删除会话",
            message = "确定要删除会话「${session.title}」吗？",
            secondary = Action("取消"),
            primary = Action("删除", destructive = true) {
                viewModel.deleteSession(session.sessionId)
                Toast.makeText(requireContext(), "已删除会话", Toast.LENGTH_SHORT).show()
            }
        ).show()
    }

    private fun showMessageActions(message: ChatMessage) {
        val items = mutableListOf<Option>()

        if (message.role != ChatMessage.ROLE_USER) {
            items += Option(getString(R.string.chat_markdown_select_source), icon = R.drawable.ic_select_text) {
                showSelectableMessageText(message)
            }
        }

        items += Option("删除", icon = R.drawable.ic_delete_outline, destructive = true) {
            confirmDeleteMessage(message)
        }

        if (message.role == ChatMessage.ROLE_USER) {
            items += Option("修改", icon = R.drawable.ic_edit_outline) {
                showEditMessageDialog(message)
            }
        }

        showActionMenu(items)
    }

    private fun showSelectableMessageText(message: ChatMessage) {
        val text = message.content
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), "没有可复制的文本", Toast.LENGTH_SHORT).show()
            return
        }

        val context = requireContext()
        val textView = TextView(context).apply {
            this.text = text
            setTextIsSelectable(true)
            textSize = 15f
            setTextColor(context.getColor(R.color.mt_text_primary))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        ManTouDialog(
            context,
            title = getString(R.string.chat_markdown_select_source),
            content = textView,
            primary = Action("关闭")
        ).show()
    }

    private fun confirmDeleteMessage(message: ChatMessage) {
        if (message.messageId <= 0) {
            Toast.makeText(requireContext(), "该消息无法删除", Toast.LENGTH_SHORT).show()
            return
        }
        ManTouDialog(
            requireContext(),
            title = "删除消息",
            message = "确定要删除这条消息吗？",
            secondary = Action("取消"),
            primary = Action("删除", destructive = true) {
                viewModel.deleteMessage(message.messageId)
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
            }
        ).show()
    }

    private fun showEditMessageDialog(message: ChatMessage) {
        if (message.messageId <= 0) {
            Toast.makeText(requireContext(), "该消息无法修改", Toast.LENGTH_SHORT).show()
            return
        }
        if (message.role != ChatMessage.ROLE_USER) {
            Toast.makeText(requireContext(), "只能修改用户请求", Toast.LENGTH_SHORT).show()
            return
        }

        val context = requireContext()
        val editor = context.dialogField(
            hint = "消息内容",
            value = message.content,
            input = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            multiline = true
        ).apply {
            minLines = 4
            maxLines = 12
        }
        ManTouDialog(
            context,
            title = "修改消息",
            content = editor,
            secondary = Action("取消"),
            primary = Action("重新发送", dismissOnClick = false) { dialog ->
                val newContent = editor.text.toString().trim()
                if (newContent.isBlank()) {
                    dialog.showFieldError("消息不能为空")
                } else {
                    viewModel.editUserMessageAndRegenerate(message.messageId, newContent)
                    Toast.makeText(context, "已重新发送", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        ).show()
    }

    private fun showActionMenu(items: List<Option>) {
        if (items.isEmpty()) return
        val hostActivity = activity?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        actionMenuDialog?.dismiss()
        val dialog = ManTouDialog(hostActivity, options = items)
        actionMenuDialog = dialog
        dialog.setOnDismissListener { if (actionMenuDialog === dialog) actionMenuDialog = null }
        dialog.show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun dp(value: Float): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    /**
     * 初始化聊天 RecyclerView
     */
    private fun setupChatRecyclerView() {
        chatAdapter = ChatAdapter(
            deviceHost = webAppDeviceHost,
            onDataChanged = { itemCount ->
                updateGreetingVisibility(itemCount > 0)
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
        chatBinding.inputContainer.addOnLayoutChangeListener {
            _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            val shouldKeepLatestMessageVisible = shouldFollowNewMessages()
            updateChatListScrollBoundary()
            if (shouldKeepLatestMessageVisible) scrollChatToBottom()
        }
        chatAdapter.updateAppearance(AppearanceSettingsStore.getSettings(requireContext()))
    }

    private fun updateGreetingVisibility(hasMessages: Boolean) {
        val targetVisibility = if (hasMessages) View.GONE else View.VISIBLE
        if (chatBinding.flGreeting.visibility == targetVisibility) return
        chatBinding.flGreeting.visibility = targetVisibility
        LiquidGlass.refresh(binding.root)
    }

    /**
     * 观察 ViewModel 数据变化
     */
    private fun observeViewModel() {
        // 观察消息列表
        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            latestChatMessages = messages
            latestChatMessagesSessionId = viewModel.currentSessionId.value
            renderChatMessages(messages)
        }

        viewModel.currentSessionTokenUsage.observe(viewLifecycleOwner) { usage ->
            currentConsumedTokens = usage.totalTokens
            currentTokenUsageIncludesEstimate = usage.includesEstimate
            updateContextProgressIndicator()
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

        viewModel.runningSessionIds.observe(viewLifecycleOwner) { sessionIds ->
            runningSessionIds = sessionIds
            if (::sessionAdapter.isInitialized) {
                sessionAdapter.setRunningSessionIds(sessionIds)
            }
            val currentSessionId = viewModel.currentSessionId.value
            updateSendButtonState(currentSessionId != null && currentSessionId in sessionIds)
        }

        viewModel.isGeneratingApp.observe(viewLifecycleOwner) { isGeneratingApp ->
            setKeepScreenOn(isGeneratingApp)
            if (isGeneratingApp) ensureHarnessNotificationPermission()
        }

        viewModel.generateTaskStates.observe(viewLifecycleOwner) { states ->
            generateTaskStates = states
            renderGenerateTaskState(states[viewModel.currentSessionId.value])
            if (::chatAdapter.isInitialized) {
                renderChatMessages(latestChatMessages)
            }
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
            if (latestChatMessagesSessionId != sessionId) {
                latestChatMessages = emptyList()
                latestChatMessagesSessionId = sessionId
            }
            updateSendButtonState(sessionId != null && sessionId in runningSessionIds)
            renderGenerateTaskState(sessionId?.let(generateTaskStates::get))
            if (::chatAdapter.isInitialized) {
                renderChatMessages(latestChatMessages)
            }
            // 可以在这里更新 UI，显示当前会话信息
        }

        viewModel.selectedWorkspace.observe(viewLifecycleOwner) { path ->
            chatBinding.tvConversationWorkspace.text =
                "工作区 · ${path?.substringAfterLast('/') ?: "根目录"}"
            chatBinding.workspaceSelector.contentDescription = path ?: ConversationWorkspace.ROOT_PATH
        }

        // 观察当前活跃模型名称
        viewModel.activeModelName.observe(viewLifecycleOwner) { modelName ->
            val name = modelName?.takeIf { it.isNotBlank() }
            chatBinding.etInput.hint = if (name == null) "请先配置模型" else "输入消息"
            renderActiveModel(name)
            val providerId = ActiveModelStore.getActiveProviderId(requireContext())
            if (providerId != null && name != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val provider = providerRepository.getProviderById(providerId)
                    if (ActiveModelStore.getActiveProviderId(requireContext()) == providerId &&
                        ActiveModelStore.getActiveModelName(requireContext()) == name
                    ) {
                        renderActiveModel(name, provider?.name.orEmpty())
                    }
                }
            }
            binding.chatTab.contentDescription = modelName
                ?.let { "聊天机器人，当前模型 $it" }
                ?: "聊天机器人，请配置模型"
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

    private fun renderGenerateTaskState(taskState: GenerateTaskState?) {
        val wasCodePreviewVisible = chatBinding.generateCodeCard.visibility == View.VISIBLE
        val hasCodePreview = taskState != null &&
            (taskState.code.isNotBlank() || taskState.projectFiles.isNotEmpty())
        val visibilityChanged = wasCodePreviewVisible != hasCodePreview
        val shouldKeepLatestMessageVisible = visibilityChanged &&
            ::chatAdapter.isInitialized && shouldFollowNewMessages()
        currentGenerateTaskState = taskState
        codeEditorDialog?.takeIf { it.isShowing && it.sessionId != null }?.let { dialog ->
            if (taskState != null && taskState.sessionId == dialog.sessionId) {
                dialog.updateProject(taskState)
            } else {
                dialog.dismiss()
            }
        }
        if (!hasCodePreview) {
            chatBinding.generateCodeCard.visibility = View.GONE
            if (visibilityChanged) updateChatListScrollBoundary()
            if (shouldKeepLatestMessageVisible) scrollChatToBottom()
            return
        }

        chatBinding.generateCodeCard.visibility = View.VISIBLE
        val language = codePreviewLanguage(taskState)
        val metadata = codePreviewMetadata(taskState.activeFilePath, taskState.code)
        chatBinding.tvGenerateCodeLanguage.text = language
        chatBinding.tvGenerateCodeTitle.text = if (taskState.projectFiles.size > 1) {
            "项目源码 · ${taskState.projectFiles.size} 文件"
        } else {
            "源码预览"
        }
        chatBinding.tvGenerateCodeStatus.text = metadata
        chatBinding.tvGenerateCodePreview.text = buildGenerateCodePreview(taskState)
        chatBinding.generateCodeCard.contentDescription = "打开${chatBinding.tvGenerateCodeTitle.text}，$language，$metadata"
        if (visibilityChanged) updateChatListScrollBoundary()
        if (shouldKeepLatestMessageVisible) scrollChatToBottom()
    }

    private fun buildGenerateCodePreview(taskState: GenerateTaskState): String {
        if (taskState.code.isBlank()) {
            return taskState.status
        }
        return taskState.code
            .lineSequence()
            .filter { it.isNotBlank() }
            .takeLastLines(3)
            .joinToString("\n")
    }

    private fun Sequence<String>.takeLastLines(count: Int): List<String> {
        val lines = ArrayDeque<String>(count)
        forEach { line ->
            if (lines.size == count) lines.removeFirst()
            lines.addLast(line)
        }
        return lines.toList()
    }

    private fun showGenerateCodeEditor() {
        val taskState = currentGenerateTaskState ?: return
        createCodeViewer(taskState.sessionId).apply {
            updateProject(taskState)
            show()
        }
    }

    private fun createCodeViewer(sessionId: Long? = null): CodeViewerDialog {
        codeEditorDialog?.dismiss()
        val dialog = CodeViewerDialog(requireContext(), viewLifecycleOwner.lifecycleScope, sessionId)
        dialog.setOnDismissListener {
            if (codeEditorDialog === dialog) codeEditorDialog = null
        }
        codeEditorDialog = dialog
        return dialog
    }

    private fun openImagePicker() {
        imagePickerLauncher.launch(arrayOf("image/*"))
    }

    private fun fillInputWithAppPrompt() {
        val prompt = "生成一个应用："
        chatBinding.etInput.setText(prompt)
        chatBinding.etInput.setSelection(prompt.length)
        switchToActiveState()
    }

    private fun fillInputWithMemoryPrompt() {
        val prompt = "请记住："
        chatBinding.etInput.setText(prompt)
        chatBinding.etInput.setSelection(prompt.length)
        switchToActiveState()
    }

    private fun onImagesSelectedFromPicker(uris: List<Uri>) {
        if (uris.isEmpty()) return

        uris.forEach { uri ->
            persistReadPermission(uri)
            if (selectedImageUris.none { it == uri }) {
                selectedImageUris.add(uri)
            }
        }
        renderSelectedImagePreviews()
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

    private fun renderSelectedImagePreviews() {
        val imageStrip = chatBinding.selectedImageStrip
        val loader = Glide.with(this)
        for (index in 0 until imageStrip.childCount) {
            val thumbnail = (imageStrip.getChildAt(index) as FrameLayout).getChildAt(0) as ImageView
            loader.clear(thumbnail)
        }
        imageStrip.removeAllViews()
        chatBinding.selectedImageScroll.visibility =
            if (selectedImageUris.isEmpty()) View.GONE else View.VISIBLE

        val context = requireContext()
        selectedImageUris.forEachIndexed { index, uri ->
            val card = FrameLayout(context).apply {
                background = context.dialogShape(R.color.mt_dialog_control, 14)
                clipToOutline = true
            }
            val thumbnail = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "预览第 ${index + 1} 张图片"
                isFocusable = true
                setOnClickListener { showImagePreview(uri, queryDisplayName(uri)) }
            }
            card.addView(thumbnail, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            val remove = ImageButton(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xCC202535.toInt())
                }
                setImageResource(R.drawable.ic_dialog_close)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                contentDescription = "移除第 ${index + 1} 张图片"
                setOnClickListener {
                    selectedImageUris.remove(uri)
                    renderSelectedImagePreviews()
                    updateCurrentContextTokens()
                }
            }
            card.addView(remove, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(4)
                marginEnd = dp(4)
            })
            imageStrip.addView(card, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                if (index < selectedImageUris.lastIndex) marginEnd = dp(8)
            })
            loader.load(uri).centerCrop().error(R.drawable.ic_input_image).into(thumbnail)
        }
    }

    private fun clearSelectedImages() {
        selectedImageUris.clear()
        renderSelectedImagePreviews()
        updateCurrentContextTokens()
    }

    private fun renderActiveModel(modelName: String?, providerName: String = "") {
        chatBinding.tvActiveModel.text = modelName ?: "选择模型"
        chatBinding.ivActiveModelIcon.setImageResource(chatModelIcon(modelName.orEmpty(), providerName))
        chatBinding.ivModelPicker.contentDescription =
            modelName?.let { "选择模型，当前模型 $it" } ?: "选择模型"
    }

    private fun showModelPickerPanel() {
        if (modelPickerDialog?.isShowing == true) return
        hideKeyboard()
        chatBinding.etInput.clearFocus()

        val context = requireContext()
        val picker = ChatModelPicker(
            context,
            activeProviderId = ActiveModelStore.getActiveProviderId(context),
            activeModelName = ActiveModelStore.getActiveModelName(context),
            onConfigure = { startActivity(Intent(context, ModelSettingActivity::class.java)) },
            onSelected = { option ->
                ActiveModelStore.setActive(context, option.providerId, option.modelName)
                renderActiveModel(option.modelName, option.providerName)
                viewModel.refreshActiveModel()
            }
        )
        modelPickerDialog = picker.dialog
        picker.dialog.setOnDismissListener {
            if (modelPickerDialog === picker.dialog) modelPickerDialog = null
        }
        picker.dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val models = withContext(Dispatchers.IO) {
                providerRepository.getAllProvidersOnce().flatMap { provider ->
                    providerRepository.getModelsForProviderOnce(provider.providerId).map { model ->
                        ChatModelOption(provider.providerId, provider.name, model.modelName)
                    }
                }
            }
            if (picker.dialog.isShowing) picker.showModels(models)
        }
    }

    private fun showMimoVoiceKeyDialog(startAfterActivation: Boolean) {
        val context = requireContext()
        val apiKeyInput = context.dialogField(
            hint = "MiMo API Key",
            value = VoiceInputModelStore.getActiveApiKey(context),
            input = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
        ManTouDialog(
            context,
            title = "MiMo 语音输入",
            message = "输入可用的 MiMo API Key，校验通过后会激活 ${VoiceTranscriptionApiService.MIMO_ASR_MODEL}。",
            content = apiKeyInput,
            secondary = Action("取消"),
            primary = Action("激活", dismissOnClick = false) { dialog ->
                val apiKey = apiKeyInput.text?.toString().orEmpty()
                val button = checkNotNull(dialog.primaryButton)
                button.isEnabled = false
                button.text = "校验中…"
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = VoiceTranscriptionApiService.activateMimoAsr(apiKey)
                    if (!dialog.isShowing) return@launch
                    button.isEnabled = true
                    button.text = "激活"
                    result.onSuccess { config ->
                        VoiceInputModelStore.setActive(context, config)
                        Toast.makeText(context, "MiMo 语音输入已激活", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        if (startAfterActivation) startVoiceInputWithConfiguredModel()
                    }.onFailure { error ->
                        Toast.makeText(context, error.message ?: "MiMo API Key 校验失败", Toast.LENGTH_LONG).show()
                    }
                }
            }
        ).show()
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
                button.imageTintList = ColorStateList.valueOf(adaptiveTextColor)
                button.contentDescription = "语音输入"
            }
        }
    }

    private fun showContextLimitDialog() {
        ManTouSheet(requireContext(), buildContextLimitSheet()).show()
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
            setPadding(dp(20), dp(10), dp(20), dp(22))
        }
        scroll.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(
            TextView(requireContext()).apply {
                text = "上下文控制"
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(context.getColor(R.color.mt_text_primary))
                ViewCompat.setAccessibilityHeading(this, true)
            }
        )

        container.addView(
            TextView(requireContext()).apply {
                text = "当前上下文是下一次请求的预计占用；累计消耗会叠加本会话的每次模型请求。"
                textSize = 14f
                setTextColor(context.getColor(R.color.mt_dialog_secondary))
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
            background = context.dialogShape(R.color.mt_dialog_control, 14)
            setPadding(dp(4), dp(14), dp(4), dp(14))
        }
        val currentValue = createStatValue(formatNumber(currentContextTokens), Color.rgb(45, 52, 68))
        val consumedValue = createStatValue(formatConsumedTokens(), Color.rgb(190, 112, 46))
        val targetValue = createStatValue(formatNumber(currentLimit), Color.rgb(58, 132, 226))
        val percentValue = createStatValue(formatPercent(currentContextTokens, currentLimit), Color.rgb(65, 145, 126))
        statsRow.addView(createStatColumn("当前上下文", currentValue), statColumnParams())
        statsRow.addView(verticalDivider())
        statsRow.addView(createStatColumn("累计消耗", consumedValue), statColumnParams())
        statsRow.addView(verticalDivider())
        statsRow.addView(createStatColumn("目标阈值", targetValue), statColumnParams())
        statsRow.addView(verticalDivider())
        statsRow.addView(createStatColumn("占用比例", percentValue), statColumnParams())
        container.addView(statsRow)
        container.addView(buildValidationStrategyRow(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(12)
        })

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
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    width = 0
                    height = dp(48)
                    setMargins(dp(3), 0, dp(3), dp(8))
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

        val exactInput = requireContext().dialogField(
            hint = "精确阈值",
            value = currentLimit.toString(),
            input = InputType.TYPE_CLASS_NUMBER
        )
        container.addView(
            exactInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
        )

        container.addView(
            TextView(requireContext()).apply {
                text = "默认 ${formatNumber(ContextLimitStore.DEFAULT_TOKEN_LIMIT)}，" +
                    "范围 ${formatNumber(ContextLimitStore.MIN_TOKEN_LIMIT)} - " +
                    "${formatNumber(ContextLimitStore.MAX_TOKEN_LIMIT)}；≈ 表示供应商未返回 usage 时的估算值"
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
                background = context.dialogShape(R.color.mt_dialog_control, 13)
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
            consumedValue.text = formatConsumedTokens()
            targetValue.text = formatNumber(clamped)
            percentValue.text = formatPercent(currentContextTokens, clamped)
            optionButtons.forEach { (option, button) ->
                val selected = option == clamped
                button.isSelected = selected
                button.setTextColor(requireContext().getColor(
                    if (selected) R.color.mt_on_primary else R.color.mt_dialog_secondary
                ))
                button.background = requireContext().dialogRipple(
                    if (selected) R.color.mt_primary_dark else R.color.mt_dialog_control,
                    13,
                    onDark = selected
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

    private fun buildValidationStrategyRow(): View {
        val context = requireContext()
        return TextView(context).apply {
            fun updateLabel() {
                text = "验收策略 · ${HarnessSettingsStore.validation(context).label}"
            }

            updateLabel()
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(context.getColor(R.color.mt_text_primary))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_right, 0)
            compoundDrawablePadding = dp(8)
            background = context.dialogRipple(R.color.mt_dialog_control, 14)
            isFocusable = true
            setOnClickListener {
                val current = HarnessSettingsStore.validation(context)
                ManTouDialog(
                    context,
                    title = "验收策略",
                    message = "设置新任务的验收设备范围和页面自测要求。",
                    options = HarnessValidationProfile.entries.map { option ->
                        Option(option.label, option.description, checked = current == option) {
                            HarnessSettingsStore.setValidation(context, option)
                            updateLabel()
                        }
                    }
                ).show()
            }
        }
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
            "上下文控制，预计占用 ${formatNumber(currentContextTokens)} / ${formatNumber(limit)}，" +
                "本会话累计消耗 ${formatConsumedTokens()} token"
    }

    private fun createStatColumn(label: String, valueView: TextView): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(requireContext()).apply {
                text = label
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(122, 133, 153))
                setSingleLine(true)
            })
            addView(valueView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
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
            setSingleLine(true)
            gravity = Gravity.CENTER
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                12,
                22,
                1,
                TypedValue.COMPLEX_UNIT_SP
            )
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
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
        }
    }

    private fun formatNumber(value: Int): String {
        return String.format(Locale.US, "%,d", value)
    }

    private fun formatNumber(value: Long): String {
        return String.format(Locale.US, "%,d", value)
    }

    private fun formatConsumedTokens(): String {
        val prefix = if (currentTokenUsageIncludesEstimate) "≈" else ""
        return prefix + formatNumber(currentConsumedTokens)
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

    private fun queryDisplayName(uri: Uri, context: Context = requireContext()): String {
        return runCatching {
            context.contentResolver
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
        val backgroundColor = Color.rgb(44, 131, 216)
        val iconRes = if (taskRunning) R.drawable.ic_composer_stop else R.drawable.ic_send_up

        chatBinding.ivSend.isEnabled = true
        chatBinding.ivSend.isClickable = true
        chatBinding.ivSend.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        chatBinding.ivSend.setImageResource(iconRes)
        chatBinding.ivSend.imageTintList = ColorStateList.valueOf(Color.WHITE)
        chatBinding.ivSend.contentDescription = if (taskRunning) "停止请求" else "发送"
    }

    /**
     * 发送消息
     */
    private fun sendMessage() {
        if (isTaskRunning) {
            viewModel.stopStreaming()
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
        (activity as? MainActivity)?.dispatchHarnessNotificationIntent()
        viewModel.refreshActiveModel()
        refreshWorkspaceTree()
        refreshDesktopApps()
        applyWallpaper()
    }

    internal fun openHarnessSessionFromNotification(sessionId: Long) {
        viewModel.switchToSession(sessionId)
        if (::mainPager.isInitialized) {
            mainPager.setCurrentItem(PAGE_CHAT, false)
        }
    }

    override fun onDestroyView() {
        modelPickerDialog?.dismiss()
        modelPickerDialog = null
        actionMenuDialog?.dismiss()
        actionMenuDialog = null
        codeEditorDialog?.dismiss()
        codeEditorDialog = null
        super.onDestroyView()
        cancelVoiceInput()
        CameraPhotoBridge.detach(this)
        activeAppWebView = null
        setKeepScreenOn(false)
        if (::mainPager.isInitialized) {
            pagerCallback?.let { mainPager.unregisterOnPageChangeCallback(it) }
            mainPager.adapter = null
        }
        pagerCallback = null
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

    private fun ensureHarnessNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (notificationPermissionRequestInFlight) return
        notificationPermissionRequestInFlight = true
        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private data class ChatViewportAnchor(
        val messageId: Long?,
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
}
