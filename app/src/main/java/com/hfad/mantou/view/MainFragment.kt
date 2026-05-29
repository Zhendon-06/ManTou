package com.hfad.mantou.view

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.hfad.mantou.adapter.ChatAdapter
import com.hfad.mantou.adapter.ImageSelectAdapter
import com.hfad.mantou.adapter.SessionAdapter
import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.data.ImageItem
import com.hfad.mantou.databinding.FragmentMainBinding
import com.hfad.mantou.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    private var isInputActive = false

    // ViewModel
    private val viewModel: ChatViewModel by viewModels()

    // 聊天消息适配器
    private lateinit var chatAdapter: ChatAdapter

    // 会话列表适配器
    private lateinit var sessionAdapter: SessionAdapter

    // 图片选择适配器
    private lateinit var imageSelectAdapter: ImageSelectAdapter

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
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
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
        binding.etInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && isInputActive) {
                // 延迟检查，避免点击其他按钮时误触发
                view.postDelayed({
                    if (!binding.etInput.hasFocus() && binding.functionPanel.visibility != View.VISIBLE) {
                        switchToIdleState()
                    }
                }, 100)
            }
        }

        // 添加按钮点击：显示/隐藏功能面板
        binding.ivAdd.setOnClickListener {
            toggleFunctionPanel()
        }

        // 点击聊天列表：隐藏功能面板
        binding.rvChat.setOnClickListener {
            hideFunctionPanel()
            binding.etInput.clearFocus()
        }

        // 输入框点击：显示输入态
        binding.etInput.setOnClickListener {
            if (!isInputActive) {
                switchToActiveState()
            }
            hideFunctionPanel()
        }

        // 菜单按钮点击：打开侧边菜单
        binding.ivMenu.setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }

        // 相机按钮点击：打开相机
        binding.ivCamera.setOnClickListener {
            checkCameraPermissionAndOpen()
        }

        // 发送按钮点击：发送消息
        binding.ivSend.setOnClickListener {
            sendMessage()
        }

        // 输入框回车键监听：发送消息
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

        // 新建会话按钮点击
        binding.newChat.setOnClickListener {
            createNewSession()
        }

        // 侧边栏清空历史按钮
        setupDrawerMenu()
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
        binding.etInput.text?.clear()
        
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

    /**
     * 长按消息：弹出"复制 / 删除"菜单
     */
    private fun showMessageActions(message: ChatMessage) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setItems(arrayOf("复制", "删除")) { _, which ->
                when (which) {
                    0 -> copyMessageToClipboard(message)
                    1 -> confirmDeleteMessage(message)
                }
            }
            .show()
    }

    private fun copyMessageToClipboard(message: ChatMessage) {
        val text = message.content
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), "没有可复制的文本", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = requireContext()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("chat_message", text))
        Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show()
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

    /**
     * 初始化聊天 RecyclerView
     */
    private fun setupChatRecyclerView() {
        chatAdapter = ChatAdapter(
            onDataChanged = { itemCount ->
                if (itemCount > 0) {
                    binding.flGreeting.visibility = View.GONE
                } else {
                    binding.flGreeting.visibility = View.VISIBLE
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

        binding.rvChat.apply {
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
                binding.rvChat.post {
                    binding.rvChat.scrollToPosition(messages.size - 1)
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
            binding.curModel.text = modelName ?: "请配置你的模型"
        }

        // 观察未配置模型事件
        viewModel.noModelConfigured.observe(viewLifecycleOwner) { noModel ->
            if (noModel) {
                Toast.makeText(requireContext(), "请先配置模型后再使用", Toast.LENGTH_LONG).show()
                viewModel.clearNoModelConfigured()
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
        binding.functionPanel.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false // 返回 false 让 RecyclerView 继续处理滚动
        }

        // 在输入区域设置触摸监听（用于上滑打开图片选择器）
        binding.inputCard.setOnTouchListener { _, event ->
            if (binding.functionPanel.visibility == View.VISIBLE) {
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
        if (binding.functionPanel.visibility == View.VISIBLE) {
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

        binding.functionPanel.apply {
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
        binding.etInput.postDelayed({
            binding.etInput.requestFocus()
            showKeyboard()
        }, 100)
    }

    /**
     * 切换到搜索态
     */
    private fun switchToIdleState() {
        if (!isInputActive) return
        
        // 如果功能面板正在显示，不切换
        if (binding.functionPanel.visibility == View.VISIBLE) {
            return
        }
        
        isInputActive = false
        hideKeyboard()
        hideFunctionPanel()
        
        // 清空输入框
        binding.etInput.clearFocus()
        binding.etInput.text?.clear()
    }

    /**
     * 显示/隐藏功能面板
     */
    private fun toggleFunctionPanel() {
        if (binding.functionPanel.visibility == View.VISIBLE) {
            hideFunctionPanel()
        } else {
            showFunctionPanel()
        }
    }

    /**
     * 显示功能面板
     */
    private fun showFunctionPanel() {
        binding.functionPanel.visibility = View.VISIBLE
        // 隐藏键盘
        hideKeyboard()
        binding.etInput.clearFocus()
        
        // 检查权限并加载图片
        checkAndRequestPermission()
    }

    /**
     * 隐藏功能面板
     */
    private fun hideFunctionPanel() {
        binding.functionPanel.visibility = View.GONE
    }

    /**
     * 显示键盘
     */
    private fun showKeyboard() {
        binding.etInput.let {
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
        binding.etInput.let {
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
        val text = binding.etInput.text?.toString()?.trim() ?: ""
        
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
        binding.etInput.text?.clear()
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
