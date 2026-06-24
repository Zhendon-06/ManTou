package com.hfad.mantou.view

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.hfad.mantou.R
import com.hfad.mantou.adapter.ProviderModelAdapter
import com.hfad.mantou.data.api.ApiEndpointResolver
import com.hfad.mantou.data.api.ModelListApiService
import com.hfad.mantou.data.database.AppDatabase
import com.hfad.mantou.data.database.ProviderEntity
import com.hfad.mantou.data.preferences.ActiveModelStore
import com.hfad.mantou.data.repository.ProviderRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ModelSettingActivity : AppCompatActivity() {

    private val providerRepository by lazy {
        ProviderRepository(AppDatabase.getDatabase(applicationContext).providerDao())
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var actvProvider: AutoCompleteTextView
    private lateinit var actvProvider2: AutoCompleteTextView
    private lateinit var btnDeleteProvider: ImageView
    private lateinit var btnAddProvider: ImageView
    private lateinit var etProviderName: TextInputEditText
    private lateinit var etBaseUrl: TextInputEditText
    private lateinit var etApiKey: TextInputEditText
    private lateinit var btnAddModel: ImageView
    private lateinit var btnRefreshModels: ImageView
    private lateinit var tvModelCount: TextView
    private lateinit var rvModelList: RecyclerView
    private lateinit var emptyModelState: View

    private lateinit var modelAdapter: ProviderModelAdapter

    private var allProviders: List<ProviderEntity> = emptyList()
    private var currentProviderId: Long? = null
    private var modelsJob: Job? = null
    private var isBindingProviderForm = false

    private val draftPrefs by lazy {
        getSharedPreferences(PREF_MODEL_SETTING_DRAFT, Context.MODE_PRIVATE)
    }

    private val apiFormatLabels = linkedMapOf(
        ProviderEntity.API_FORMAT_OPENAI to "OpenAI",
        ProviderEntity.API_FORMAT_ANTHROPIC to "Anthropic"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_model_setting)
        bindViews()
        applyInsets()
        setupToolbar()
        setupModelList()
        setupApiFormatDropdown()
        setupDraftPersistence()
        setupClickListeners()
        observeProviders()
    }

    // ===================================================================
    // 初始化
    // ===================================================================

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        actvProvider = findViewById(R.id.actvProvider)
        actvProvider2 = findViewById(R.id.actvProvider2)
        btnDeleteProvider = findViewById(R.id.btnDeleteProvider)
        btnAddProvider = findViewById(R.id.btnAddProvider)
        etProviderName = findViewById(R.id.etProviderName)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etApiKey = findViewById(R.id.etApiKey)
        btnAddModel = findViewById(R.id.btnAddModel)
        btnRefreshModels = findViewById(R.id.btnRefreshModels)
        tvModelCount = findViewById(R.id.tvModelCount)
        rvModelList = findViewById(R.id.rvModelList)
        emptyModelState = findViewById(R.id.emptyModelState)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupModelList() {
        modelAdapter = ProviderModelAdapter { modelName ->
            val pid = currentProviderId ?: return@ProviderModelAdapter
            ActiveModelStore.setActive(this, pid, modelName)
            Toast.makeText(this, "已选择模型：$modelName", Toast.LENGTH_SHORT).show()
        }
        rvModelList.layoutManager = LinearLayoutManager(this)
        rvModelList.setHasFixedSize(true)
        rvModelList.itemAnimator = null
        rvModelList.adapter = modelAdapter
    }

    private fun setupApiFormatDropdown() {
        val labels = apiFormatLabels.values.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        actvProvider2.setAdapter(adapter)
        actvProvider2.setOnClickListener { actvProvider2.showDropDown() }
        actvProvider2.setOnItemClickListener { _, _, position, _ ->
            val key = apiFormatLabels.keys.toList()[position]
            val label = labels[position]
            actvProvider2.setText(label, false)
            saveCurrentDraft()
        }
    }

    private fun setupDraftPersistence() {
        listOf(etProviderName, etBaseUrl, etApiKey, actvProvider2).forEach { input ->
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    saveCurrentDraft()
                }

                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
    }

    private fun setupClickListeners() {
        actvProvider.setOnClickListener { actvProvider.showDropDown() }
        actvProvider.setOnItemClickListener { _, _, position, _ ->
            allProviders.getOrNull(position)?.let { switchToProvider(it) }
        }

        btnAddProvider.setOnClickListener { showAddProviderDialog() }
        btnDeleteProvider.setOnClickListener { confirmDeleteCurrentProvider() }

        btnRefreshModels.setOnClickListener { refreshModelsFromApi() }

        btnAddModel.setOnClickListener { showAddModelDialog() }
    }

    // ===================================================================
    // Provider 列表观察
    // ===================================================================

    private fun observeProviders() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                providerRepository.getAllProviders().collectLatest { providers ->
                    val hadProvider = currentProviderId != null
                    allProviders = providers
                    updateProviderDropdown(providers)
                    val pid = currentProviderId ?: getSelectedProviderId()
                    when {
                        providers.isEmpty() -> {
                            // 仅当原本绑着 Provider（例如刚被删除）时才清空表单；
                            // 首次进入或回到前台时若用户正在编辑新 Provider，保留输入
                            if (hadProvider) {
                                resetForm()
                            } else {
                                applyEmptyProvidersState()
                            }
                        }
                        pid == null || providers.none { it.providerId == pid } -> {
                            switchToProvider(providers.first())
                        }
                        currentProviderId != pid -> {
                            providers.firstOrNull { it.providerId == pid }?.let { switchToProvider(it) }
                        }
                        else -> {
                            // 同步当前 Provider 最新内容（例如刚被 updateProvider 修改）
                            providers.firstOrNull { it.providerId == pid }?.let { fillProviderForm(it) }
                        }
                    }
                }
            }
        }
    }

    private fun updateProviderDropdown(providers: List<ProviderEntity>) {
        val names = providers.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        actvProvider.setAdapter(adapter)
    }

    private fun switchToProvider(provider: ProviderEntity) {
        currentProviderId = provider.providerId
        saveSelectedProviderId(provider.providerId)
        fillProviderForm(provider)
        observeModelsForProvider(provider.providerId)
    }

    private fun fillProviderForm(provider: ProviderEntity) {
        val draft = loadDraft(provider.providerId)
        setProviderForm(
            name = draft?.name ?: provider.name,
            baseUrl = draft?.baseUrl ?: provider.baseUrl,
            apiKey = draft?.apiKey ?: provider.apiKey,
            apiFormat = draft?.apiFormat ?: provider.apiFormat
        )
    }

    private fun resetForm() {
        currentProviderId = null
        clearSelectedProviderId()
        modelsJob?.cancel()
        setProviderForm(
            name = "",
            baseUrl = "",
            apiKey = "",
            apiFormat = ProviderEntity.API_FORMAT_OPENAI
        )
        modelAdapter.submitList(emptyList())
        modelAdapter.setSelected(null)
        updateModelCount(0)
        emptyModelState.visibility = View.VISIBLE
    }

    /** 仅同步“没有 Provider”的列表/模型计数状态，保留用户已输入的表单内容。 */
    private fun applyEmptyProvidersState() {
        currentProviderId = null
        clearSelectedProviderId()
        modelsJob?.cancel()
        loadDraft(null)?.let { draft ->
            setProviderForm(
                name = draft.name,
                baseUrl = draft.baseUrl,
                apiKey = draft.apiKey,
                apiFormat = draft.apiFormat
            )
        }
        modelAdapter.submitList(emptyList())
        modelAdapter.setSelected(null)
        updateModelCount(0)
        emptyModelState.visibility = View.VISIBLE
    }

    // ===================================================================
    // 模型列表观察 + 选中状态恢复
    // ===================================================================

    private fun observeModelsForProvider(providerId: Long) {
        modelsJob?.cancel()
        modelsJob = lifecycleScope.launch {
            providerRepository.getModelsForProvider(providerId).collectLatest { models ->
                val names = models.map { it.modelName }
                modelAdapter.submitList(names) {
                    rvModelList.requestLayout()
                }
                updateModelCount(names.size)
                emptyModelState.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE

                val activeProviderId = ActiveModelStore.getActiveProviderId(this@ModelSettingActivity)
                val activeModelName = ActiveModelStore.getActiveModelName(this@ModelSettingActivity)
                val selected = if (activeProviderId == providerId && activeModelName in names) {
                    activeModelName
                } else null
                modelAdapter.setSelected(selected)
            }
        }
    }

    private fun updateModelCount(count: Int) {
        tvModelCount.text = "共 $count 个模型"
    }

    // ===================================================================
    // 新增 / 删除 Provider
    // ===================================================================

    private fun showAddProviderDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_provider, null, false)
        val input: EditText = view.findViewById(R.id.etNewProviderName)
        val btnCancel: TextView = view.findViewById(R.id.btnDialogCancel)
        val btnConfirm: TextView = view.findViewById(R.id.btnDialogConfirm)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val name = input.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入 Provider 名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val newId = providerRepository.createProvider(name)
                providerRepository.getProviderById(newId)?.let { switchToProvider(it) }
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun confirmDeleteCurrentProvider() {
        val pid = currentProviderId
        if (pid == null) {
            Toast.makeText(this, "当前没有 Provider 可删除", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除 Provider")
            .setMessage("删除后该 Provider 及其模型将一并移除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    providerRepository.deleteProvider(pid)
                    ActiveModelStore.clearIfMatches(this@ModelSettingActivity, pid)
                    clearDraft(pid)
                    resetForm()
                    // observeProviders Flow 会回调，自动切到剩余第一个或 resetForm
                }
            }
            .show()
    }

    // ===================================================================
    // 拉取远端模型清单（点击 btnRefreshModels 时统一持久化所有字段）
    // ===================================================================

    private fun refreshModelsFromApi() {
        val name = etProviderName.text?.toString()?.trim().orEmpty()
        val rawBaseUrl = etBaseUrl.text?.toString()?.trim().orEmpty()
        val apiKey = etApiKey.text?.toString()?.trim().orEmpty()
        if (name.isEmpty() || rawBaseUrl.isEmpty()) {
            Toast.makeText(this, "请填写 Provider 名称和 Base URL", Toast.LENGTH_SHORT).show()
            return
        }
        val baseUrl = runCatching {
            ApiEndpointResolver.normalizeBaseUrl(rawBaseUrl)
        }.getOrElse { e ->
            Toast.makeText(this, e.message ?: "Base URL 无效", Toast.LENGTH_LONG).show()
            return
        }
        etBaseUrl.setText(baseUrl)
        val apiFormat = labelToApiFormat(actvProvider2.text?.toString())

        Toast.makeText(this, "拉取中…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = ModelListApiService.fetchModels(baseUrl, apiKey, apiFormat)
            result.onSuccess { modelIds ->
                val wasNewProvider = currentProviderId == null
                val provider = saveProviderConfig(name, baseUrl, apiKey, apiFormat)
                    ?: return@onSuccess
                clearDraft(provider.providerId)
                if (wasNewProvider) {
                    clearDraft(null)
                }
                switchToProvider(provider)

                if (modelIds.isEmpty()) {
                    Toast.makeText(this@ModelSettingActivity,
                        "未拉到任何模型，已保存 Provider 配置", Toast.LENGTH_SHORT).show()
                } else {
                    providerRepository.replaceModels(provider.providerId, modelIds)
                    Toast.makeText(this@ModelSettingActivity,
                        "已拉取 ${modelIds.size} 个模型", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                Toast.makeText(this@ModelSettingActivity,
                    "拉取失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ===================================================================
    // 手动新增模型（保留兜底入口）
    // ===================================================================

    private fun showAddModelDialog() {
        val providerId = currentProviderId
        if (providerId == null) {
            Toast.makeText(this, "请先选择 Provider", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "模型名，例如 gpt-4o"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("新增模型")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val n = input.text?.toString()?.trim().orEmpty()
                if (n.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val existing = providerRepository.getModelsForProviderOnce(providerId)
                        .map { it.modelName }
                    if (n !in existing) {
                        providerRepository.replaceModels(providerId, existing + n)
                    }
                }
            }
            .show()
    }

    private fun labelToApiFormat(label: String?): String {
        return apiFormatLabels.entries
            .firstOrNull { it.value.equals(label, ignoreCase = true) }
            ?.key ?: ProviderEntity.API_FORMAT_OPENAI
    }

    private suspend fun saveProviderConfig(
        name: String,
        baseUrl: String,
        apiKey: String,
        apiFormat: String
    ): ProviderEntity? {
        val existing = currentProviderId?.let { providerRepository.getProviderById(it) }
        val provider = if (existing != null) {
            existing.copy(
                name = name,
                baseUrl = baseUrl,
                apiKey = apiKey,
                apiFormat = apiFormat
            )
        } else {
            val newId = providerRepository.createProvider(name)
            val created = providerRepository.getProviderById(newId) ?: return null
            created.copy(
                name = name,
                baseUrl = baseUrl,
                apiKey = apiKey,
                apiFormat = apiFormat
            )
        }
        providerRepository.updateProvider(provider)
        return provider
    }

    private fun setProviderForm(
        name: String,
        baseUrl: String,
        apiKey: String,
        apiFormat: String
    ) {
        isBindingProviderForm = true
        actvProvider.setText(name, false)
        etProviderName.setText(name)
        etBaseUrl.setText(baseUrl)
        etApiKey.setText(apiKey)
        actvProvider2.setText(apiFormatLabels[apiFormat] ?: "OpenAI", false)
        isBindingProviderForm = false
    }

    private fun saveCurrentDraft() {
        if (isBindingProviderForm) return
        val suffix = draftSuffix(currentProviderId)
        draftPrefs.edit()
            .putString(draftKey(KEY_DRAFT_NAME, suffix), etProviderName.text?.toString().orEmpty())
            .putString(draftKey(KEY_DRAFT_BASE_URL, suffix), etBaseUrl.text?.toString().orEmpty())
            .putString(draftKey(KEY_DRAFT_API_KEY, suffix), etApiKey.text?.toString().orEmpty())
            .putString(draftKey(KEY_DRAFT_API_FORMAT, suffix), labelToApiFormat(actvProvider2.text?.toString()))
            .apply()
    }

    private fun loadDraft(providerId: Long?): ProviderFormDraft? {
        val suffix = draftSuffix(providerId)
        val nameKey = draftKey(KEY_DRAFT_NAME, suffix)
        val baseUrlKey = draftKey(KEY_DRAFT_BASE_URL, suffix)
        val apiKeyKey = draftKey(KEY_DRAFT_API_KEY, suffix)
        val apiFormatKey = draftKey(KEY_DRAFT_API_FORMAT, suffix)
        val hasDraft = draftPrefs.contains(nameKey) ||
            draftPrefs.contains(baseUrlKey) ||
            draftPrefs.contains(apiKeyKey) ||
            draftPrefs.contains(apiFormatKey)
        if (!hasDraft) return null

        return ProviderFormDraft(
            name = draftPrefs.getString(nameKey, "").orEmpty(),
            baseUrl = draftPrefs.getString(baseUrlKey, "").orEmpty(),
            apiKey = draftPrefs.getString(apiKeyKey, "").orEmpty(),
            apiFormat = draftPrefs.getString(apiFormatKey, ProviderEntity.API_FORMAT_OPENAI)
                ?: ProviderEntity.API_FORMAT_OPENAI
        )
    }

    private fun clearDraft(providerId: Long?) {
        val suffix = draftSuffix(providerId)
        draftPrefs.edit()
            .remove(draftKey(KEY_DRAFT_NAME, suffix))
            .remove(draftKey(KEY_DRAFT_BASE_URL, suffix))
            .remove(draftKey(KEY_DRAFT_API_KEY, suffix))
            .remove(draftKey(KEY_DRAFT_API_FORMAT, suffix))
            .apply()
    }

    private fun saveSelectedProviderId(providerId: Long) {
        draftPrefs.edit()
            .putLong(KEY_SELECTED_PROVIDER_ID, providerId)
            .apply()
    }

    private fun getSelectedProviderId(): Long? {
        if (!draftPrefs.contains(KEY_SELECTED_PROVIDER_ID)) return null
        val id = draftPrefs.getLong(KEY_SELECTED_PROVIDER_ID, -1L)
        return if (id > 0L) id else null
    }

    private fun clearSelectedProviderId() {
        draftPrefs.edit()
            .remove(KEY_SELECTED_PROVIDER_ID)
            .apply()
    }

    private fun draftSuffix(providerId: Long?): String =
        providerId?.takeIf { it > 0L }?.toString() ?: KEY_NEW_PROVIDER_DRAFT

    private fun draftKey(prefix: String, suffix: String): String = "${prefix}_$suffix"

    private data class ProviderFormDraft(
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val apiFormat: String
    )

    companion object {
        private const val PREF_MODEL_SETTING_DRAFT = "model_setting_draft_pref"
        private const val KEY_SELECTED_PROVIDER_ID = "selected_provider_id"
        private const val KEY_NEW_PROVIDER_DRAFT = "new_provider"
        private const val KEY_DRAFT_NAME = "draft_name"
        private const val KEY_DRAFT_BASE_URL = "draft_base_url"
        private const val KEY_DRAFT_API_KEY = "draft_api_key"
        private const val KEY_DRAFT_API_FORMAT = "draft_api_format"
    }
}
