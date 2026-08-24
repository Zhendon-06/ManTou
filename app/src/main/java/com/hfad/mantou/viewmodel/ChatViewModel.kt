package com.hfad.mantou.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.data.GenerateTaskState
import com.hfad.mantou.data.SessionTokenUsage
import com.hfad.mantou.data.api.ApiConfig
import com.hfad.mantou.data.api.ApiMessage
import com.hfad.mantou.data.api.ChatCallConfig
import com.hfad.mantou.data.api.ChatRequest
import com.hfad.mantou.data.api.ModelTokenUsage
import com.hfad.mantou.data.api.ContentPart
import com.hfad.mantou.data.api.ImageUrl
import com.hfad.mantou.data.api.StreamingApiService
import com.hfad.mantou.data.database.AppDatabase
import com.hfad.mantou.data.database.ChatMessageEntity
import com.hfad.mantou.data.database.ChatSessionEntity
import com.hfad.mantou.data.logging.ApiDiagnosticContext
import com.hfad.mantou.data.logging.ApiLogRedactor
import com.hfad.mantou.data.logging.HarnessTraceEvent
import com.hfad.mantou.data.logging.HarnessTraceLogger
import com.hfad.mantou.data.logging.HarnessTraceStatus
import com.hfad.mantou.data.logging.elapsedMillisSince
import com.hfad.mantou.data.logging.record
import com.hfad.mantou.data.preferences.ContextLimitStore
import com.hfad.mantou.data.repository.ChatRepository
import com.hfad.mantou.service.HarnessForegroundServiceController
import com.hfad.mantou.service.HarnessProgress
import com.hfad.mantou.service.HarnessProgressReporter
import com.hfad.mantou.service.HarnessProgressStatus
import com.hfad.mantou.service.HarnessServiceRequest
import com.hfad.mantou.utils.AgentWorkspace
import com.hfad.mantou.utils.AppGenerator
import com.hfad.mantou.utils.AppIntentDetector
import com.hfad.mantou.utils.ChatContextFormatter
import com.hfad.mantou.utils.ErrorAnalyzer
import com.hfad.mantou.utils.GenerationInputFilter
import com.hfad.mantou.utils.ImageUtils
import com.hfad.mantou.utils.LocalDiffFileTool
import com.hfad.mantou.utils.harness.AppHarnessOrchestrator
import com.hfad.mantou.utils.harness.GeneratedAppHarnessScripts
import com.hfad.mantou.utils.harness.GeneratedAppWebViewInspector
import com.hfad.mantou.utils.harness.HarnessBuilder
import com.hfad.mantou.utils.harness.HarnessCheckResult
import com.hfad.mantou.utils.harness.HarnessFileTask
import com.hfad.mantou.utils.harness.HarnessFileTool
import com.hfad.mantou.utils.harness.HarnessLimits
import com.hfad.mantou.utils.harness.HarnessRunRequest
import com.hfad.mantou.utils.harness.HarnessRunResult
import com.hfad.mantou.utils.harness.HarnessToolResult
import com.hfad.mantou.utils.harness.StreamingHarnessModelRepair
import com.hfad.mantou.utils.harness.WebInspectionReport
import com.hfad.mantou.utils.harness.WebInspectionEvent
import com.hfad.mantou.utils.harness.WebInspectionTarget
import com.hfad.mantou.utils.harness.WebQualityGateContract
import com.hfad.mantou.utils.harness.WebViewHarnessInspectorAdapter
import com.hfad.mantou.utils.harness.withDesignRequirements
import com.hfad.mantou.utils.project.StreamingWebProjectPlanner
import com.hfad.mantou.utils.project.WebAppProjectFileRole
import com.hfad.mantou.utils.project.WebAppProjectManifest
import com.hfad.mantou.utils.project.WebAppProjectResolver
import com.hfad.mantou.utils.project.WebAppProjectSnapshot
import com.hfad.mantou.utils.project.WebAppProjectSnapshotKind
import com.hfad.mantou.utils.project.WebAppProjectWorkspace
import com.hfad.mantou.utils.project.WebAppProjectValidator
import com.hfad.mantou.utils.project.WebAppSpecPolicy
import com.hfad.mantou.utils.project.WebProjectFileTool
import com.hfad.mantou.utils.project.WebProjectPlanCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.min

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ChatRepository(AppDatabase.getDatabase(application).chatDao())
    private val providerRepository = com.hfad.mantou.data.repository.ProviderRepository(
        AppDatabase.getDatabase(application).providerDao()
    )
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentSessionId = MutableLiveData<Long?>()
    val currentSessionId: LiveData<Long?> = _currentSessionId

    private val _messages = MutableLiveData<List<ChatMessage>>()
    val messages: LiveData<List<ChatMessage>> = _messages

    val allSessions: Flow<List<ChatSessionEntity>> = repository.getAllSessions()

    val archivedSessions: Flow<List<ChatSessionEntity>> = repository.getArchivedSessions()

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _runningSessionIds = MutableLiveData<Set<Long>>(emptySet())
    val runningSessionIds: LiveData<Set<Long>> = _runningSessionIds

    private val _isGeneratingApp = MutableLiveData(false)
    val isGeneratingApp: LiveData<Boolean> = _isGeneratingApp

    private val _generateTaskStates = MutableLiveData<Map<Long, GenerateTaskState>>(emptyMap())
    val generateTaskStates: LiveData<Map<Long, GenerateTaskState>> = _generateTaskStates

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _appGenerated = MutableLiveData<String?>()
    val appGenerated: LiveData<String?> = _appGenerated

    private val _activeModelName = MutableLiveData<String?>(null)
    val activeModelName: LiveData<String?> = _activeModelName

    private val _noModelConfigured = MutableLiveData(false)
    val noModelConfigured: LiveData<Boolean> = _noModelConfigured

    private val _currentSessionTokenUsage = MutableLiveData(SessionTokenUsage())
    val currentSessionTokenUsage: LiveData<SessionTokenUsage> = _currentSessionTokenUsage
    private var currentTokenUsageSessionId: Long? = null

    private var messagesJob: Job? = null
    private val streamingStates = mutableMapOf<Long, StreamingSessionState>()
    private val appGenerationProgressIntervalMs = 1_500L
    private val streamingUiUpdateIntervalMs = 120L
    private val reconnectWindowMs = 5_000L
    private val reconnectCountdownIntervalMs = 1_000L
    private val requestInterruptedFallback = "出错了，请稍后重试。"
    private val requestFailedSuffix = "\n\n出错了，请稍后重试。"
    private val assistantStreamingPlaceholder = "\u200B"
    private var serviceProgressStates: Map<String, HarnessProgress> = emptyMap()
    private val tokenUsageMutex = Mutex()

    init {
        AgentWorkspace.ensureWorkspace(application)
        refreshActiveModel()
        observeHarnessServiceProgress()
    }

    private fun observeHarnessServiceProgress() {
        viewModelScope.launch {
            HarnessForegroundServiceController.states.collect { states ->
                serviceProgressStates = states
                states.values.forEach(::mergeDetachedHarnessProgress)
                updateSessionLoadingIndicators()
            }
        }
    }

    private fun mergeDetachedHarnessProgress(progress: HarnessProgress) {
        val sessionId = progress.sessionId ?: return
        if (streamingStates.containsKey(sessionId)) return
        val current = _generateTaskStates.value.orEmpty()[sessionId]
        if (current != null && current.updatedAt >= progress.updatedAt) return

        val phase = when (progress.status) {
            HarnessProgressStatus.SUCCEEDED -> GenerateTaskState.Phase.COMPLETED
            HarnessProgressStatus.FAILED,
            HarnessProgressStatus.CANCELLED -> GenerateTaskState.Phase.ERROR
            HarnessProgressStatus.RUNNING -> progress.stage.toGenerateTaskPhase()
        }
        val outcome = when (progress.status) {
            HarnessProgressStatus.RUNNING -> if (
                progress.message.contains("重试") || progress.message.contains("修复")
            ) {
                GenerateTaskState.Outcome.RETRYING
            } else {
                GenerateTaskState.Outcome.RUNNING
            }
            HarnessProgressStatus.SUCCEEDED -> GenerateTaskState.Outcome.PASSED
            HarnessProgressStatus.FAILED,
            HarnessProgressStatus.CANCELLED -> GenerateTaskState.Outcome.FAILED
        }
        val eventStage = progress.stage?.let { stage ->
            runCatching { GenerateTaskState.Stage.valueOf(stage) }.getOrNull()
        }
        val base = current ?: GenerateTaskState(
            sessionId = sessionId,
            phase = phase,
            status = progress.message
        )
        var projected = base.copy(
            phase = phase,
            status = progress.message,
            harnessIteration = maxOf(base.harnessIteration, progress.iteration),
            diagnostics = progress.diagnostics,
            updatedAt = progress.updatedAt
        )
        val lastEvent = projected.harnessEvents.lastOrNull()
        if (eventStage != null && (
                lastEvent?.stage != eventStage ||
                    lastEvent.operation != progress.operation ||
                    lastEvent.outcome != outcome ||
                    lastEvent.message != progress.message
                )
        ) {
            projected = projected.appendHarnessEvent(
                GenerateTaskState.HarnessEvent(
                    stage = eventStage,
                    outcome = outcome,
                    message = progress.message,
                    iteration = progress.iteration,
                    operation = progress.operation,
                    diagnostics = progress.diagnostics,
                    timestamp = progress.updatedAt
                )
            )
        }
        val allStates = _generateTaskStates.value.orEmpty().toMutableMap()
        allStates[sessionId] = projected
        _generateTaskStates.value = allStates
    }

    private fun String?.toGenerateTaskPhase(): GenerateTaskState.Phase {
        return when (this) {
            GenerateTaskState.Stage.INPUT.name -> GenerateTaskState.Phase.SANITIZING
            GenerateTaskState.Stage.PROMPT.name -> GenerateTaskState.Phase.PROMPTING
            GenerateTaskState.Stage.MODEL.name -> GenerateTaskState.Phase.REQUESTING_MODEL
            GenerateTaskState.Stage.TOOL.name -> GenerateTaskState.Phase.APPLYING_TOOL
            GenerateTaskState.Stage.BUILD.name -> GenerateTaskState.Phase.BUILDING
            GenerateTaskState.Stage.INSPECT.name -> GenerateTaskState.Phase.INSPECTING
            GenerateTaskState.Stage.SELF_TEST.name -> GenerateTaskState.Phase.SELF_TESTING
            GenerateTaskState.Stage.TEST.name -> GenerateTaskState.Phase.TESTING
            GenerateTaskState.Stage.DELIVER.name -> GenerateTaskState.Phase.COMPLETED
            GenerateTaskState.Phase.SANITIZING.name -> GenerateTaskState.Phase.SANITIZING
            GenerateTaskState.Phase.PROMPTING.name -> GenerateTaskState.Phase.PROMPTING
            GenerateTaskState.Phase.PREPARING.name -> GenerateTaskState.Phase.PREPARING
            GenerateTaskState.Phase.REQUESTING_MODEL.name -> GenerateTaskState.Phase.REQUESTING_MODEL
            GenerateTaskState.Phase.WRITING_INITIAL.name -> GenerateTaskState.Phase.WRITING_INITIAL
            GenerateTaskState.Phase.WRITING_DIFF.name -> GenerateTaskState.Phase.WRITING_DIFF
            GenerateTaskState.Phase.APPLYING_DIFF.name -> GenerateTaskState.Phase.APPLYING_DIFF
            GenerateTaskState.Phase.APPLYING_TOOL.name -> GenerateTaskState.Phase.APPLYING_TOOL
            GenerateTaskState.Phase.BUILDING.name -> GenerateTaskState.Phase.BUILDING
            GenerateTaskState.Phase.INSPECTING.name -> GenerateTaskState.Phase.INSPECTING
            GenerateTaskState.Phase.SELF_TESTING.name -> GenerateTaskState.Phase.SELF_TESTING
            GenerateTaskState.Phase.TESTING.name -> GenerateTaskState.Phase.TESTING
            GenerateTaskState.Phase.REPAIRING.name -> GenerateTaskState.Phase.REPAIRING
            else -> GenerateTaskState.Phase.PREPARING
        }
    }

    fun refreshActiveModel() {
        viewModelScope.launch {
            val config = resolveActiveChatConfig()
            _activeModelName.value = config?.model
        }
    }

    fun createEmptySession() {
        messagesJob?.cancel()
        viewModelScope.launch {
            val sessionId = repository.createSession("新会话")
            _currentSessionId.value = sessionId
            _messages.value = emptyList()
            currentTokenUsageSessionId = sessionId
            _currentSessionTokenUsage.value = SessionTokenUsage()
            loadMessages(sessionId)
            updateSessionLoadingIndicators()
        }
    }

    fun switchToSession(sessionId: Long) {
        messagesJob?.cancel()
        _currentSessionId.value = sessionId
        currentTokenUsageSessionId = sessionId
        _currentSessionTokenUsage.value = SessionTokenUsage()
        loadCurrentSessionTokenUsage(sessionId)
        loadMessages(sessionId)
        restoreGenerateTaskState(sessionId)
        updateSessionLoadingIndicators()
    }

    private fun loadMessages(sessionId: Long) {
        messagesJob = viewModelScope.launch {
            repository.getMessagesBySessionId(sessionId).collect { entities ->
                if (_currentSessionId.value == sessionId) {
                    val dbMessages = entities.map { it.toChatMessage() }
                    publishMessages(sessionId, dbMessages)
                }
            }
        }
    }

    private fun publishMessages(sessionId: Long, dbMessages: List<ChatMessage>) {
        val streamingMessage = streamingStates[sessionId]?.placeholder
        _messages.value = if (streamingMessage != null) {
            dbMessages + streamingMessage
        } else {
            dbMessages
        }
    }

    private fun loadCurrentSessionTokenUsage(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = repository.getSessionById(sessionId)
            val usage = SessionTokenUsage(
                totalTokens = session?.consumedTokens ?: 0L,
                includesEstimate = session?.tokenUsageIncludesEstimate ?: false
            )
            withContext(Dispatchers.Main.immediate) {
                if (_currentSessionId.value == sessionId &&
                    currentTokenUsageSessionId == sessionId
                ) {
                    val displayed = _currentSessionTokenUsage.value ?: SessionTokenUsage()
                    if (usage.totalTokens >= displayed.totalTokens) {
                        _currentSessionTokenUsage.value = usage
                    }
                }
            }
        }
    }

    private fun trackedChatStream(
        sessionId: Long,
        config: ChatCallConfig,
        request: ChatRequest
    ): Flow<StreamingApiService.StreamEvent> {
        val trackedRequest = request.copy(
            tokenUsageListener = { usage -> recordSessionTokenUsage(sessionId, usage) }
        )
        return StreamingApiService.streamChatCompletion(config, trackedRequest)
    }

    private fun recordSessionTokenUsage(sessionId: Long, usage: ModelTokenUsage) {
        if (usage.totalTokens <= 0L) return
        fallbackScope.launch {
            tokenUsageMutex.withLock {
                repository.addSessionTokenUsage(
                    sessionId = sessionId,
                    tokens = usage.totalTokens,
                    estimated = usage.estimated
                )
                val session = repository.getSessionById(sessionId) ?: return@withLock
                withContext(Dispatchers.Main.immediate) {
                    if (_currentSessionId.value == sessionId &&
                        currentTokenUsageSessionId == sessionId
                    ) {
                        _currentSessionTokenUsage.value = SessionTokenUsage(
                            totalTokens = session.consumedTokens,
                            includesEstimate = session.tokenUsageIncludesEstimate
                        )
                    }
                }
            }
        }
    }

    fun sendMessage(content: String, imagePath: String? = null, imageUris: List<Uri>? = null) {
        val sessionId = _currentSessionId.value ?: run {
            createNewSessionAndSendMessage(content, imagePath, imageUris)
            return
        }

        cancelStreaming(sessionId, persistFallback = true)
        val state = StreamingSessionState(sessionId)
        streamingStates[sessionId] = state
        updateSessionLoadingIndicators()

        state.job = viewModelScope.launch {
            _errorMessage.value = null
            state.streamingContent.clear()
            try {
                withContext(Dispatchers.IO) {
                    AgentWorkspace.appendExplicitMemoryIfNeeded(getApplication(), content)
                }

                val config = resolveActiveChatConfig()
                if (config == null) {
                    _noModelConfigured.value = true
                    return@launch
                }

                _activeModelName.value = config.model

                val finalImagePath = imagePath ?: imageUris?.firstOrNull()?.toString()
                val imageBase64List = loadImageBase64List(imagePath, imageUris)

                repository.sendUserMessage(sessionId, content, finalImagePath)
                state.userMessagePersisted = true

                if (repository.getMessageCount(sessionId) == 1) {
                    repository.updateSessionTitle(sessionId, content.ifEmpty { "[图片]" })
                }

                generateResponseForPersistedUserMessage(state, config, content, imageBase64List)
            } finally {
                if (!state.isServiceOwned) {
                    finishStreamingState(state)
                }
            }
        }
    }

    private suspend fun loadImageBase64List(
        imagePath: String? = null,
        imageUris: List<Uri>? = null
    ): List<String> = withContext(Dispatchers.IO) {
        val imageBase64List = mutableListOf<String>()
        val urisToProcess = imageUris?.take(ApiConfig.MAX_IMAGE_COUNT)
            ?: listOfNotNull(imagePath?.let { Uri.parse(it) })

        urisToProcess.forEach { uri ->
            ImageUtils.uriToBase64(getApplication(), uri)?.let { imageBase64List.add(it) }
        }
        imageBase64List
    }

    private suspend fun generateResponseForPersistedUserMessage(
        state: StreamingSessionState,
        config: ChatCallConfig,
        content: String,
        imageBase64List: List<String>
    ) {
        val sessionRouting = withContext(Dispatchers.IO) {
            val session = repository.getSessionById(state.sessionId)
            val latestGeneratedPath = repository.getMessagesBySessionIdOnce(state.sessionId)
                .asReversed()
                .firstNotNullOfOrNull { it.appHtmlPath?.takeIf(String::isNotBlank) }
            val appHtmlPath = session?.appHtmlPath?.takeIf(String::isNotBlank) ?: latestGeneratedPath
            SessionRouting(
                isGenerateTask = session?.isGenerateTask == true || appHtmlPath != null,
                appHtmlPath = appHtmlPath
            )
        }

        if (sessionRouting.isGenerateTask) {
            repository.markSessionAsGenerate(state.sessionId, sessionRouting.appHtmlPath)
            val existingFile = sessionRouting.appHtmlPath
                ?.let(::File)
                ?.takeIf { it.exists() }
            launchAppGenerationService(state, config, content, existingFile)
            return
        }

        val isAppIntent = withContext(Dispatchers.IO) {
            AppIntentDetector.isAppGenerationIntent(
                context = getApplication(),
                config = config,
                userMessage = content,
                hasGeneratedAppInSession = false,
                tokenUsageListener = { usage -> recordSessionTokenUsage(state.sessionId, usage) },
                diagnosticContext = ApiDiagnosticContext(
                    runId = "session-${state.sessionId}",
                    operation = "intent.classify"
                ),
            )
        }

        if (!isAppIntent) {
            normalChatFlow(state, config, imageBase64List)
            return
        }

        repository.markSessionAsGenerate(state.sessionId)
        updateGenerateTaskState(
            GenerateTaskState(
                sessionId = state.sessionId,
                phase = GenerateTaskState.Phase.PREPARING,
                status = "正在准备初版应用"
            )
        )
        launchAppGenerationService(state, config, content)
    }

    private suspend fun launchAppGenerationService(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        existingFile: File? = null
    ) {
        if (state.serviceJob?.isActive == true) return

        val runId = "harness-${state.sessionId}-${System.currentTimeMillis()}"
        val traceLogger = createHarnessTraceLogger(state.sessionId, runId)
        val serviceStartedAt = System.nanoTime()
        val request = HarnessServiceRequest(
            runId = runId,
            sessionId = state.sessionId,
            title = "ManTou Harness",
            initialMessage = if (existingFile == null) {
                "正在后台生成应用"
            } else {
                "正在后台修改应用"
            },
            initialStage = "准备中"
        )
        state.serviceRunId = runId
        state.isServiceOwned = true
        state.isGeneratingApp = true
        updateSessionLoadingIndicators()
        traceLogger.record(
            runId = runId,
            component = "SERVICE",
            operation = "launch",
            status = HarnessTraceStatus.PROGRESS,
            message = "已请求启动 Harness 前台服务",
            iteration = 0,
            details = mapOf(
                "session_id" to state.sessionId.toString(),
                "is_modification" to (existingFile != null).toString(),
                "existing_artifact" to existingFile?.absolutePath.orEmpty()
            )
        )

        try {
            state.serviceJob = HarnessForegroundServiceController.launch(
                context = getApplication<Application>(),
                request = request
            ) { reporter ->
                state.harnessProgressReporter = reporter
                var workerFailure: Throwable? = null
                val workerStartedAt = System.nanoTime()
                traceLogger.record(
                    runId = runId,
                    component = "SERVICE",
                    operation = "worker",
                    status = HarnessTraceStatus.STARTED,
                    message = "Harness 后台任务开始执行",
                    iteration = 0,
                    durationMs = elapsedMillisSince(serviceStartedAt)
                )
                try {
                    generateWebProjectFlow(
                        state = state,
                        config = config,
                        userMessage = userMessage,
                        existingFile = existingFile,
                        runId = runId,
                        traceLogger = traceLogger
                    )
                } catch (error: Throwable) {
                    workerFailure = error
                    traceLogger.record(
                        runId = runId,
                        component = "SERVICE",
                        operation = "worker",
                        status = if (error is CancellationException) {
                            HarnessTraceStatus.CANCELLED
                        } else {
                            HarnessTraceStatus.FAILED
                        },
                        message = if (error is CancellationException) {
                            "Harness 后台任务已取消"
                        } else {
                            "Harness 后台任务失败"
                        },
                        iteration = 0,
                        durationMs = elapsedMillisSince(workerStartedAt),
                        details = mapOf("error_type" to error::class.java.simpleName)
                    )
                    throw error
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        state.harnessProgressReporter = null
                        state.serviceJob = null
                        state.isServiceOwned = false
                        state.serviceRunId = null
                        val unfinishedState = _generateTaskStates.value.orEmpty()[state.sessionId]
                        if (unfinishedState?.isRunning != false) {
                            val terminalMessage = when (workerFailure) {
                                is CancellationException -> workerFailure.message ?: "生成任务已停止"
                                null -> "生成任务未完成"
                                else -> workerFailure.message ?: "Harness 执行失败"
                            }
                            updateGenerateTaskState(
                                unfinishedState?.copy(
                                    phase = GenerateTaskState.Phase.ERROR,
                                    status = terminalMessage,
                                    updatedAt = System.currentTimeMillis()
                                ) ?: GenerateTaskState(
                                    sessionId = state.sessionId,
                                    phase = GenerateTaskState.Phase.ERROR,
                                    status = terminalMessage
                                )
                            )
                        }
                        if (workerFailure is CancellationException &&
                            state.userMessagePersisted &&
                            !state.hasFinalAssistantMessage
                        ) {
                            addFinalAssistantMessage(state, "生成任务已停止。")
                        }
                        try {
                            finishStreamingState(state)
                        } finally {
                            val taskState = _generateTaskStates.value.orEmpty()[state.sessionId]
                            when {
                                workerFailure is CancellationException -> reporter.cancel("生成任务已停止")
                                taskState?.phase == GenerateTaskState.Phase.COMPLETED -> reporter.succeed(
                                    message = taskState.status,
                                    diagnostics = taskState.diagnostics
                                )
                                taskState?.phase == GenerateTaskState.Phase.ERROR -> reporter.fail(
                                    message = taskState.status,
                                    diagnostics = taskState.diagnostics
                                )
                                workerFailure != null -> reporter.fail(
                                    message = workerFailure.message ?: "Harness 执行失败"
                                )
                                else -> reporter.fail("生成任务未完成")
                            }
                        }
                        if (workerFailure == null) {
                            val terminalState = _generateTaskStates.value.orEmpty()[state.sessionId]
                            val succeeded = terminalState?.phase == GenerateTaskState.Phase.COMPLETED
                            traceLogger.record(
                                runId = runId,
                                component = "SERVICE",
                                operation = "worker",
                                status = if (succeeded) {
                                    HarnessTraceStatus.SUCCEEDED
                                } else {
                                    HarnessTraceStatus.FAILED
                                },
                                message = if (succeeded) {
                                    "Harness 后台任务成功结束"
                                } else {
                                    "Harness 后台任务未成功完成"
                                },
                                iteration = terminalState?.harnessIteration ?: 0,
                                durationMs = elapsedMillisSince(workerStartedAt),
                                details = mapOf(
                                    "terminal_phase" to (terminalState?.phase?.name ?: "MISSING")
                                )
                            )
                        }
                    }
                }
            }
        } catch (error: Exception) {
            traceLogger.record(
                runId = runId,
                component = "SERVICE",
                operation = "launch",
                status = HarnessTraceStatus.FAILED,
                message = "Harness 前台服务启动失败，切回页面任务",
                iteration = 0,
                durationMs = elapsedMillisSince(serviceStartedAt),
                details = mapOf("error_type" to error::class.java.simpleName)
            )
            state.serviceRunId = null
            state.isServiceOwned = false
            state.isGeneratingApp = false
            updateSessionLoadingIndicators()
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.PREPARING,
                stage = GenerateTaskState.Stage.PROMPT,
                outcome = GenerateTaskState.Outcome.RETRYING,
                message = "后台服务启动失败，已切回当前页面继续执行",
                diagnostics = listOfNotNull(error.message)
            )
            generateWebProjectFlow(
                state = state,
                config = config,
                userMessage = userMessage,
                existingFile = existingFile,
                runId = runId,
                traceLogger = traceLogger
            )
        }
    }

    private suspend fun generateWebProjectFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        existingFile: File? = null,
        runId: String,
        traceLogger: HarnessTraceLogger
    ) {
        val application = getApplication<Application>()
        val flowStartedAt = System.nanoTime()
        traceLogger.record(
            runId = runId,
            component = "FLOW",
            operation = "generate_web_project",
            status = HarnessTraceStatus.STARTED,
            message = "Web 项目生成流程开始",
            iteration = 0,
            details = mapOf(
                "session_id" to state.sessionId.toString(),
                "is_modification" to (existingFile != null).toString(),
                "input_chars" to userMessage.length.toString(),
                "input_sha256" to traceSha256(userMessage),
                "existing_artifact" to existingFile?.absolutePath.orEmpty()
            )
        )
        val filterStartedAt = System.nanoTime()
        val filteredInput = GenerationInputFilter.filter(userMessage)
        traceLogger.record(
            runId = runId,
            component = "INPUT_FILTER",
            operation = "sanitize",
            status = HarnessTraceStatus.SUCCEEDED,
            message = "生成输入过滤完成",
            iteration = 0,
            durationMs = elapsedMillisSince(filterStartedAt),
            details = mapOf(
                "input_chars" to userMessage.length.toString(),
                "filtered_chars" to filteredInput.content.length.toString(),
                "filtered_sha256" to traceSha256(filteredInput.content),
                "notice_count" to filteredInput.notices.size.toString()
            )
        )
        val isModification = existingFile != null
        var inspector: GeneratedAppWebViewInspector? = null
        var inspectorWebView: WebView? = null

        state.isGeneratingApp = true
        withContext(Dispatchers.Main.immediate) {
            replaceGenerateTaskState(
                GenerateTaskState(
                    sessionId = state.sessionId,
                    phase = GenerateTaskState.Phase.SANITIZING,
                    status = if (isModification) {
                        "正在准备多文件项目修改"
                    } else {
                        "正在准备多文件项目规划"
                    },
                    isModification = isModification
                )
            )
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.SANITIZING,
                stage = GenerateTaskState.Stage.INPUT,
                outcome = GenerateTaskState.Outcome.PASSED,
                message = filteredInput.notices.firstOrNull() ?: "用户输入已过滤并建立安全边界",
                diagnostics = filteredInput.notices,
                isModification = isModification
            )
            state.thinkingContent.clear()
            addStreamingPlaceholder(
                state = state,
                status = if (isModification) "正在读取项目" else "正在规划项目",
                useLoadingLayout = true
            )
            updateStreamingThinking(
                state,
                if (isModification) {
                    "正在解析现有项目并创建可安全修改的草稿..."
                } else {
                    "正在发起独立项目规划请求，确定文件结构与职责..."
                },
                force = true
            )
        }

        try {
            val preparedProject = if (existingFile == null) {
                planAndCreateWebProject(
                    state = state,
                    config = config,
                    userRequirement = filteredInput.promptPayload,
                    runId = runId,
                    traceLogger = traceLogger
                )
            } else {
                val prepareStartedAt = System.nanoTime()
                traceLogger.record(
                    runId = runId,
                    component = "WORKSPACE",
                    operation = "prepare_existing",
                    status = HarnessTraceStatus.STARTED,
                    message = "开始解析现有 Web 项目",
                    iteration = 0,
                    details = mapOf("input_path" to existingFile.absolutePath)
                )
                try {
                    withContext(Dispatchers.IO) {
                        prepareExistingWebProject(existingFile)
                    }
                } catch (error: Throwable) {
                    traceLogger.record(
                        runId = runId,
                        component = "WORKSPACE",
                        operation = "prepare_existing",
                        status = if (error is CancellationException) {
                            HarnessTraceStatus.CANCELLED
                        } else {
                            HarnessTraceStatus.FAILED
                        },
                        message = if (error is CancellationException) {
                            "现有 Web 项目准备已取消"
                        } else {
                            "现有 Web 项目准备失败"
                        },
                        iteration = 0,
                        durationMs = elapsedMillisSince(prepareStartedAt),
                        details = mapOf("error_type" to error::class.java.simpleName)
                    )
                    throw error
                }.also { prepared ->
                    traceLogger.record(
                        runId = runId,
                        component = "WORKSPACE",
                        operation = "prepare_existing",
                        status = HarnessTraceStatus.SUCCEEDED,
                        message = "现有 Web 项目草稿准备完成",
                        iteration = 0,
                        durationMs = elapsedMillisSince(prepareStartedAt),
                        details = mapOf(
                            "project_root" to prepared.snapshot.projectRoot.absolutePath,
                            "content_root" to prepared.snapshot.contentRoot.absolutePath,
                            "entry" to prepared.snapshot.manifest.entryPoint,
                            "project_id" to prepared.snapshot.manifest.projectId,
                            "snapshot_kind" to prepared.snapshot.kind.name,
                            "draft_version" to prepared.snapshot.version.toString()
                        )
                    )
                }
            }
            val binding = MutableWebProjectBinding(
                workspace = preparedProject.workspace,
                projectRoot = preparedProject.snapshot.projectRoot,
                contentRoot = preparedProject.snapshot.contentRoot,
                manifest = preparedProject.snapshot.manifest,
                draftVersion = requireNotNull(preparedProject.snapshot.version) {
                    "多文件项目草稿缺少版本号"
                }
            )
            val projectFileTool = withContext(Dispatchers.IO) {
                WebProjectFileTool(binding.contentRoot).also { tool ->
                    ensureProjectEntryIdentity(binding, tool)
                }
            }
            val initialProjectFiles = withContext(Dispatchers.IO) {
                projectFilePaths(projectFileTool)
            }
            traceLogger.record(
                runId = runId,
                component = "WORKSPACE",
                operation = "draft_ready",
                status = HarnessTraceStatus.SUCCEEDED,
                message = "项目草稿和文件工具已就绪",
                iteration = 0,
                details = mapOf(
                    "project_root" to binding.projectRoot.absolutePath,
                    "content_root" to binding.contentRoot.absolutePath,
                    "entry" to binding.manifest.entryPoint,
                    "project_id" to binding.manifest.projectId,
                    "draft_version" to binding.draftVersion.toString(),
                    "file_count" to initialProjectFiles.size.toString()
                )
            )
            val initialPreview = withContext(Dispatchers.IO) {
                when {
                    binding.entryFile.isFile -> projectFileTool.read(binding.manifest.entryPoint).content
                    File(binding.contentRoot, PROJECT_PLAN_FILE_NAME).isFile -> {
                        projectFileTool.read(PROJECT_PLAN_FILE_NAME).content
                    }
                    else -> ""
                }
            }

            withContext(Dispatchers.Main.immediate) {
                updateProjectFileState(
                    state = state,
                    relativePath = if (binding.entryFile.isFile) {
                        binding.manifest.entryPoint
                    } else {
                        PROJECT_PLAN_FILE_NAME
                    },
                    content = initialPreview,
                    entryPath = binding.entryFile.absolutePath,
                    projectFiles = initialProjectFiles,
                    isModification = isModification
                )
                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.PROMPTING,
                    stage = GenerateTaskState.Stage.PROMPT,
                    outcome = GenerateTaskState.Outcome.RUNNING,
                    message = "正在组装项目计划、设备上下文和代码工具协议",
                    filePath = binding.entryFile.absolutePath,
                    isModification = isModification
                )
            }

            val promptStartedAt = System.nanoTime()
            traceLogger.record(
                runId = runId,
                component = "PROMPT",
                operation = "build_project_system_prompt",
                status = HarnessTraceStatus.STARTED,
                message = "开始组装项目系统提示词",
                iteration = 0
            )
            val projectSystemPrompt = try {
                withContext(Dispatchers.Default) {
                    AppGenerator.buildProjectSystemPrompt(
                        context = application,
                        userMessage = filteredInput.content,
                        isModification = isModification
                    )
                }
            } catch (error: Throwable) {
                traceLogger.record(
                    runId = runId,
                    component = "PROMPT",
                    operation = "build_project_system_prompt",
                    status = if (error is CancellationException) {
                        HarnessTraceStatus.CANCELLED
                    } else {
                        HarnessTraceStatus.FAILED
                    },
                    message = if (error is CancellationException) {
                        "项目系统提示词组装已取消"
                    } else {
                        "项目系统提示词组装失败"
                    },
                    iteration = 0,
                    durationMs = elapsedMillisSince(promptStartedAt),
                    details = mapOf("error_type" to error::class.java.simpleName)
                )
                throw error
            }
            traceLogger.record(
                runId = runId,
                component = "PROMPT",
                operation = "build_project_system_prompt",
                status = HarnessTraceStatus.SUCCEEDED,
                message = "项目系统提示词组装完成",
                iteration = 0,
                durationMs = elapsedMillisSince(promptStartedAt),
                details = mapOf(
                    "prompt_chars" to projectSystemPrompt.length.toString(),
                    "prompt_sha256" to traceSha256(projectSystemPrompt)
                )
            )
            val modelRepair = StreamingHarnessModelRepair(
                config = config,
                maxTokens = AppGenerator.resolveProjectFileOutputLimit(
                    ContextLimitStore.getTokenLimit(application)
                ),
                onThinking = { delta ->
                    withContext(Dispatchers.Main.immediate) {
                        state.thinkingContent.append(delta)
                        updateStreamingThinking(state, state.thinkingContent.toString())
                    }
                },
                onProgress = { progress ->
                    withContext(Dispatchers.Main.immediate) {
                        if (state.thinkingContent.isBlank()) {
                            updateStreamingThinking(
                                state,
                                "正在执行第 ${progress.iteration} 轮多文件编排 · " +
                                    "已接收 ${progress.receivedChars} 字符",
                                force = progress.receivedChars == 0
                            )
                        }
                        state.harnessProgressReporter?.report(
                            message = "模型正在编排项目文件 · ${progress.receivedChars} 字符",
                            stage = GenerateTaskState.Stage.MODEL.name,
                            iteration = progress.iteration,
                            progress = 25
                        )
                    }
                },
                traceLogger = traceLogger,
                streamChatCompletion = { callConfig, request ->
                    trackedChatStream(state.sessionId, callConfig, request)
                }
            )
            val harnessFileTool = createWebProjectHarnessFileTool(
                state = state,
                binding = binding,
                projectFileTool = projectFileTool,
                isModification = isModification
            )

            inspectorWebView = withContext(Dispatchers.Main.immediate) {
                WebView(application)
            }
            inspector = GeneratedAppWebViewInspector(
                webView = inspectorWebView,
                prepareWebView = ::prepareHarnessWebView,
                onEvent = { event ->
                    recordWebInspectionEvent(
                        traceLogger = traceLogger,
                        runId = runId,
                        iteration = currentHarnessIteration(state),
                        event = event
                    )
                }
            )
            val inspectionAdapter = WebViewHarnessInspectorAdapter(
                inspector = inspector,
                traceLogger = traceLogger
            )
            val projectBuilder = HarnessBuilder { checkRequest ->
                val validationStartedAt = System.nanoTime()
                traceLogger.record(
                    runId = runId,
                    component = "PROJECT_VALIDATOR",
                    operation = checkRequest.kind.name,
                    status = HarnessTraceStatus.STARTED,
                    message = "项目清单与本地依赖校验开始",
                    iteration = checkRequest.iteration,
                    details = mapOf(
                        "entry" to binding.manifest.entryPoint,
                        "declared_file_count" to binding.manifest.files.size.toString()
                    )
                )
                val validation = try {
                    withContext(Dispatchers.IO) {
                        WebAppProjectValidator().validate(binding.manifest, binding.contentRoot)
                    }
                } catch (error: Throwable) {
                    traceLogger.record(
                        runId = runId,
                        component = "PROJECT_VALIDATOR",
                        operation = checkRequest.kind.name,
                        status = if (error is CancellationException) {
                            HarnessTraceStatus.CANCELLED
                        } else {
                            HarnessTraceStatus.FAILED
                        },
                        message = if (error is CancellationException) {
                            "项目清单与本地依赖校验已取消"
                        } else {
                            "项目清单与本地依赖校验异常"
                        },
                        iteration = checkRequest.iteration,
                        durationMs = elapsedMillisSince(validationStartedAt),
                        details = mapOf("error_type" to error::class.java.simpleName)
                    )
                    throw error
                }
                traceLogger.record(
                    runId = runId,
                    component = "PROJECT_VALIDATOR",
                    operation = checkRequest.kind.name,
                    status = if (validation.passed) {
                        HarnessTraceStatus.SUCCEEDED
                    } else {
                        HarnessTraceStatus.FAILED
                    },
                    message = if (validation.passed) {
                        "项目清单与本地依赖校验通过"
                    } else {
                        "项目清单或本地依赖校验失败"
                    },
                    iteration = checkRequest.iteration,
                    durationMs = elapsedMillisSince(validationStartedAt),
                    details = mapOf(
                        "passed" to validation.passed.toString(),
                        "file_count" to validation.fileCount.toString(),
                        "total_bytes" to validation.totalBytes.toString(),
                        "diagnostic_count" to validation.diagnostics.size.toString(),
                        "diagnostic_codes" to validation.diagnostics
                            .map { it.code }
                            .distinct()
                            .joinToString(",")
                    )
                )
                if (!validation.passed) {
                    HarnessCheckResult(
                        passed = false,
                        summary = "项目清单或本地依赖未通过构建检查",
                        diagnostics = validation.diagnostics.map { diagnostic ->
                            buildString {
                                append(diagnostic.severity.name)
                                append(' ').append(diagnostic.code).append(": ")
                                append(diagnostic.message)
                                diagnostic.path?.let { append(" [").append(it).append(']') }
                            }
                        },
                        artifactPath = binding.entryFile.absolutePath
                    )
                } else {
                    inspectionAdapter.build(checkRequest)
                }
            }
            val orchestrator = AppHarnessOrchestrator(
                modelRepair = modelRepair,
                fileTool = harnessFileTool,
                builder = projectBuilder,
                inspector = inspectionAdapter,
                testRunner = inspectionAdapter,
                limits = HarnessLimits(
                    maxCodeIterations = GENERATED_APP_HARNESS_MAX_ITERATIONS
                ),
                eventLogger = { eventRunId, event ->
                    logHarnessEvent(state.sessionId, eventRunId, event)
                },
                traceLogger = traceLogger
            )
            val result = withContext(Dispatchers.Default) {
                orchestrator.run(
                    HarnessRunRequest(
                        runId = runId,
                        workspacePath = binding.contentRoot.absolutePath,
                        systemPrompt = projectSystemPrompt,
                        userInput = filteredInput.content,
                        artifactPath = binding.entryFile.absolutePath,
                        selfTestScript = GeneratedAppHarnessScripts.selfTest,
                        testSuiteScript = GeneratedAppHarnessScripts.testSuite,
                        acceptanceContract = binding.manifest.appSpec?.acceptanceContract,
                        acceptanceRequired = binding.manifest.appSpec != null,
                        qualityGateContract = binding.manifest.appSpec?.design?.let { design ->
                            WebQualityGateContract().withDesignRequirements(
                                viewportWidths = design.viewportWidths,
                                minTouchTargetCssPixels = design.minTouchTargetPx
                            )
                        } ?: WebQualityGateContract(),
                        planPath = PROJECT_PLAN_FILE_NAME.takeUnless { isModification },
                        fileTasks = if (isModification) {
                            emptyList()
                        } else {
                            binding.manifest.files
                                .filterNot { it.path == PROJECT_PLAN_FILE_NAME }
                                .map { file ->
                                    HarnessFileTask(
                                        path = file.path,
                                        description = file.description,
                                        dependsOn = file.dependsOn.filterNot {
                                            it == PROJECT_PLAN_FILE_NAME
                                        },
                                        criterionIds = file.ownsCriteria
                                    )
                                }
                        },
                        metadata = mapOf("projectId" to binding.manifest.projectId)
                    )
                ) { event ->
                    val projectFiles = withContext(Dispatchers.IO) {
                        projectFilePaths(projectFileTool)
                    }
                    withContext(Dispatchers.Main.immediate) {
                        updateProjectHarnessEvent(
                            state = state,
                            event = event,
                            isModification = isModification,
                            entryPath = binding.entryFile.absolutePath,
                            projectFiles = projectFiles
                        )
                    }
                }
            }

            when (result) {
                is HarnessRunResult.Delivered -> {
                    val publishStartedAt = System.nanoTime()
                    traceLogger.record(
                        runId = runId,
                        component = "PUBLISH",
                        operation = "publish_draft",
                        status = HarnessTraceStatus.STARTED,
                        message = "项目草稿开始原子发布",
                        iteration = result.iterations,
                        details = mapOf(
                            "project_root" to binding.projectRoot.absolutePath,
                            "draft_version" to binding.draftVersion.toString(),
                            "project_id" to binding.manifest.projectId
                        )
                    )
                    val release = try {
                        withContext(Dispatchers.IO) {
                            synchronizeProjectManifest(binding, projectFileTool)
                            ensureProjectEntryIdentity(binding, projectFileTool)
                            binding.workspace.publishDraft(
                                projectRoot = binding.projectRoot,
                                draftVersion = binding.draftVersion,
                                manifest = binding.manifest
                            )
                        }
                    } catch (error: Throwable) {
                        traceLogger.record(
                            runId = runId,
                            component = "PUBLISH",
                            operation = "publish_draft",
                            status = if (error is CancellationException) {
                                HarnessTraceStatus.CANCELLED
                            } else {
                                HarnessTraceStatus.FAILED
                            },
                            message = if (error is CancellationException) {
                                "项目草稿发布已取消"
                            } else {
                                "项目草稿发布失败"
                            },
                            iteration = result.iterations,
                            durationMs = elapsedMillisSince(publishStartedAt),
                            details = mapOf("error_type" to error::class.java.simpleName)
                        )
                        throw error
                    }
                    traceLogger.record(
                        runId = runId,
                        component = "PUBLISH",
                        operation = "publish_draft",
                        status = HarnessTraceStatus.SUCCEEDED,
                        message = "项目草稿原子发布完成",
                        iteration = result.iterations,
                        durationMs = elapsedMillisSince(publishStartedAt),
                        details = mapOf(
                            "release_version" to release.version.toString(),
                            "release_root" to release.contentRoot.absolutePath,
                            "entry" to release.entryFile.absolutePath,
                            "self_test_bypassed" to result.selfTestBypassed.toString()
                        )
                    )
                    completeGeneratedWebProject(
                        state = state,
                        release = release,
                        isModification = isModification,
                        iteration = result.iterations,
                        selfTestBypassed = result.selfTestBypassed
                    )
                    traceLogger.record(
                        runId = runId,
                        component = "FLOW",
                        operation = "generate_web_project",
                        status = HarnessTraceStatus.SUCCEEDED,
                        message = "Web 项目生成流程完成",
                        iteration = result.iterations,
                        durationMs = elapsedMillisSince(flowStartedAt),
                        details = mapOf(
                            "artifact" to release.entryFile.absolutePath,
                            "self_test_bypassed" to result.selfTestBypassed.toString()
                        )
                    )
                }

                is HarnessRunResult.Failed -> {
                    traceLogger.record(
                        runId = runId,
                        component = "FLOW",
                        operation = "generate_web_project",
                        status = HarnessTraceStatus.FAILED,
                        message = "Web 项目生成流程未通过 Harness",
                        iteration = result.iterations,
                        durationMs = elapsedMillisSince(flowStartedAt),
                        details = mapOf(
                            "artifact" to result.artifactPath.orEmpty(),
                            "diagnostic_count" to result.diagnostics.size.toString()
                        )
                    )
                    failGeneratedWebProject(
                        state = state,
                        message = result.reason,
                        diagnostics = result.diagnostics,
                        entryPath = result.artifactPath ?: binding.entryFile.absolutePath,
                        isModification = isModification
                    )
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                traceLogger.record(
                    runId = runId,
                    component = "FLOW",
                    operation = "generate_web_project",
                    status = HarnessTraceStatus.CANCELLED,
                    message = "Web 项目生成流程已取消",
                    iteration = currentHarnessIteration(state),
                    durationMs = elapsedMillisSince(flowStartedAt)
                )
                throw error
            }
            traceLogger.record(
                runId = runId,
                component = "FLOW",
                operation = "generate_web_project",
                status = HarnessTraceStatus.FAILED,
                message = "Web 项目生成流程异常",
                iteration = currentHarnessIteration(state),
                durationMs = elapsedMillisSince(flowStartedAt),
                details = mapOf("error_type" to error::class.java.simpleName)
            )
            failGeneratedWebProject(
                state = state,
                message = error.message ?: "多文件项目生成失败",
                diagnostics = listOf(error.message ?: error::class.java.simpleName),
                entryPath = existingFile?.absolutePath,
                isModification = isModification
            )
            throw error
        } finally {
            val cleanupStartedAt = System.nanoTime()
            traceLogger.record(
                runId = runId,
                component = "CLEANUP",
                operation = "webview",
                status = HarnessTraceStatus.STARTED,
                message = "Harness WebView 清理开始",
                iteration = currentHarnessIteration(state),
                details = mapOf("webview_created" to (inspectorWebView != null).toString())
            )
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                inspector?.cancelCurrentInspection()
                inspectorWebView?.let { webView ->
                    webView.stopLoading()
                    webView.removeAllViews()
                    webView.destroy()
                }
                state.isGeneratingApp = false
                updateSessionLoadingIndicators()
            }
            traceLogger.record(
                runId = runId,
                component = "CLEANUP",
                operation = "webview",
                status = HarnessTraceStatus.SUCCEEDED,
                message = "Harness WebView 清理完成",
                iteration = currentHarnessIteration(state),
                durationMs = elapsedMillisSince(cleanupStartedAt)
            )
        }
    }

    private suspend fun planAndCreateWebProject(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userRequirement: String,
        runId: String,
        traceLogger: HarnessTraceLogger
    ): PreparedWebProject {
        withContext(Dispatchers.Main.immediate) {
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.PROMPTING,
                stage = GenerateTaskState.Stage.PROMPT,
                outcome = GenerateTaskState.Outcome.RUNNING,
                message = "正在请求模型规划项目文件与依赖顺序"
            )
        }
        val planner = StreamingWebProjectPlanner(
            config = config,
            maxTokens = minOf(
                WEB_PROJECT_PLAN_MAX_TOKENS,
                ContextLimitStore.getTokenLimit(getApplication())
            ).coerceAtLeast(1),
            onThinking = { delta ->
                withContext(Dispatchers.Main.immediate) {
                    state.thinkingContent.append(delta)
                    updateStreamingThinking(state, state.thinkingContent.toString())
                }
            },
            onProgress = { receivedChars ->
                withContext(Dispatchers.Main.immediate) {
                    if (state.thinkingContent.isBlank()) {
                        updateStreamingThinking(
                            state,
                            "正在接收项目规划 · $receivedChars 字符"
                        )
                    }
                    state.harnessProgressReporter?.report(
                        message = "正在规划多文件项目 · $receivedChars 字符",
                        stage = GenerateTaskState.Stage.PROMPT.name,
                        iteration = 0,
                        progress = 12
                    )
                }
            },
            traceLogger = traceLogger,
            streamChatCompletion = { callConfig, request ->
                trackedChatStream(state.sessionId, callConfig, request)
            }
        )
        val planningResult = withContext(Dispatchers.Default) {
            planner.plan(userRequirement, runId)
        }
        val workspace = WebAppProjectWorkspace()
        val workspaceStartedAt = System.nanoTime()
        traceLogger.record(
            runId = runId,
            component = "WORKSPACE",
            operation = "create_draft",
            status = HarnessTraceStatus.STARTED,
            message = "开始创建规划后的项目草稿",
            iteration = 0,
            details = mapOf(
                "project_id" to planningResult.manifest.projectId,
                "entry" to planningResult.manifest.entryPoint,
                "declared_file_count" to planningResult.manifest.files.size.toString()
            )
        )
        val snapshot = try {
            withContext(Dispatchers.IO) {
                val projectRoot = nextWebProjectRoot(
                    displayName = planningResult.manifest.displayName,
                    projectId = planningResult.manifest.projectId
                )
                workspace.create(projectRoot, planningResult.manifest).also { draft ->
                    WebProjectFileTool(draft.contentRoot).write(
                        path = PROJECT_PLAN_FILE_NAME,
                        content = planningResult.projectPlanJson,
                        createOnly = true
                    )
                }
            }
        } catch (error: Throwable) {
            traceLogger.record(
                runId = runId,
                component = "WORKSPACE",
                operation = "create_draft",
                status = if (error is CancellationException) {
                    HarnessTraceStatus.CANCELLED
                } else {
                    HarnessTraceStatus.FAILED
                },
                message = if (error is CancellationException) {
                    "规划后的项目草稿创建已取消"
                } else {
                    "规划后的项目草稿创建失败"
                },
                iteration = 0,
                durationMs = elapsedMillisSince(workspaceStartedAt),
                details = mapOf("error_type" to error::class.java.simpleName)
            )
            throw error
        }
        traceLogger.record(
            runId = runId,
            component = "WORKSPACE",
            operation = "create_draft",
            status = HarnessTraceStatus.SUCCEEDED,
            message = "规划后的项目草稿创建完成",
            iteration = 0,
            durationMs = elapsedMillisSince(workspaceStartedAt),
            details = mapOf(
                "project_root" to snapshot.projectRoot.absolutePath,
                "content_root" to snapshot.contentRoot.absolutePath,
                "entry" to snapshot.entryFile.absolutePath,
                "draft_version" to snapshot.version.toString(),
                "project_plan_chars" to planningResult.projectPlanJson.length.toString(),
                "project_plan_sha256" to traceSha256(planningResult.projectPlanJson)
            )
        )
        withContext(Dispatchers.Main.immediate) {
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.APPLYING_TOOL,
                stage = GenerateTaskState.Stage.TOOL,
                outcome = GenerateTaskState.Outcome.PASSED,
                message = "项目计划已创建 · ${planningResult.manifest.files.size - 1} 个代码文件",
                filePath = snapshot.entryFile.absolutePath
            )
            updateProjectFileState(
                state = state,
                relativePath = PROJECT_PLAN_FILE_NAME,
                content = planningResult.projectPlanJson,
                entryPath = snapshot.entryFile.absolutePath,
                projectFiles = listOf(PROJECT_PLAN_FILE_NAME),
                isModification = false
            )
        }
        return PreparedWebProject(workspace, snapshot)
    }

    private fun prepareExistingWebProject(input: File): PreparedWebProject {
        val workspace = WebAppProjectWorkspace()
        val resolved = WebAppProjectResolver.resolve(input)
        val draft = when (resolved.kind) {
            WebAppProjectSnapshotKind.LEGACY -> {
                val adoptedDraft = workspace.adoptLegacy(resolved)
                workspace.publishDraft(
                    projectRoot = adoptedDraft.projectRoot,
                    draftVersion = requireNotNull(adoptedDraft.version),
                    manifest = adoptedDraft.manifest
                )
                workspace.createDraft(adoptedDraft.projectRoot)
            }
            WebAppProjectSnapshotKind.RELEASE -> workspace.createDraft(resolved.projectRoot)
            WebAppProjectSnapshotKind.DRAFT -> resolved
        }
        val synchronizedDraft = ensureVisibleProjectPlan(draft)
        return PreparedWebProject(workspace, synchronizedDraft)
    }

    private fun ensureVisibleProjectPlan(draft: WebAppProjectSnapshot): WebAppProjectSnapshot {
        val fileTool = WebProjectFileTool(draft.contentRoot)
        val visiblePlan = File(draft.contentRoot, PROJECT_PLAN_FILE_NAME)
        val parsedManifest = visiblePlan
            .takeIf(File::isFile)
            ?.let { planFile ->
                runCatching {
                    WebProjectPlanCodec.parse(planFile.readText()).manifest.copy(
                        projectId = draft.manifest.projectId,
                        stateFile = draft.manifest.stateFile
                    )
                }.getOrNull()
            }
            ?.takeIf { manifest ->
                manifest.entryPoint == draft.manifest.entryPoint &&
                    manifest.files.any { it.role == WebAppProjectFileRole.STYLE } &&
                    manifest.files.any { it.role == WebAppProjectFileRole.SCRIPT } &&
                    preservesHostOwnedAppSpec(draft.manifest, manifest)
            }

        val manifest = parsedManifest
            ?: draft.manifest.takeIf { it.appSpec != null }
            ?: buildMigrationManifest(draft, fileTool)
        val planJson = renderVisibleProjectPlan(manifest)
        if (!visiblePlan.isFile || visiblePlan.readText() != planJson) {
            fileTool.write(PROJECT_PLAN_FILE_NAME, planJson)
        }
        return draft.copy(
            manifest = manifest,
            entryFile = File(draft.contentRoot, manifest.entryPoint).canonicalFile
        )
    }

    private fun buildMigrationManifest(
        draft: WebAppProjectSnapshot,
        fileTool: WebProjectFileTool
    ): WebAppProjectManifest {
        val existingFiles = fileTool.list(".", recursive = true)
            .asSequence()
            .filterNot { it.directory }
            .map { it.path }
            .filterNot { it == PROJECT_PLAN_FILE_NAME }
            .distinct()
            .toMutableList()
        if (draft.manifest.entryPoint !in existingFiles) {
            existingFiles.add(0, draft.manifest.entryPoint)
        }
        if (existingFiles.none { it.endsWith(".css", ignoreCase = true) }) {
            existingFiles += DEFAULT_PROJECT_STYLE_PATH
        }
        if (existingFiles.none {
                it.endsWith(".js", ignoreCase = true) || it.endsWith(".mjs", ignoreCase = true)
            }
        ) {
            existingFiles += DEFAULT_PROJECT_SCRIPT_PATH
        }
        val prioritizedFiles = existingFiles
            .distinct()
            .sortedWith(
                compareBy<String> { it != draft.manifest.entryPoint }
                    .thenBy { projectRoleForPath(it).ordinal }
                    .thenBy(String::lowercase)
            )
            .take(WEB_PROJECT_MAX_PLANNED_FILES)
        val requiredPaths = buildList {
            add(draft.manifest.entryPoint)
            if (DEFAULT_PROJECT_STYLE_PATH in existingFiles) add(DEFAULT_PROJECT_STYLE_PATH)
            if (DEFAULT_PROJECT_SCRIPT_PATH in existingFiles) add(DEFAULT_PROJECT_SCRIPT_PATH)
            addAll(prioritizedFiles)
        }.distinct().take(WEB_PROJECT_MAX_PLANNED_FILES)
        return draft.manifest.copy(
            files = buildList {
                add(
                    com.hfad.mantou.utils.project.WebAppProjectFile(
                        PROJECT_PLAN_FILE_NAME,
                        WebAppProjectFileRole.OTHER
                    )
                )
                requiredPaths.forEach { path ->
                    add(
                        com.hfad.mantou.utils.project.WebAppProjectFile(
                            path = path,
                            role = if (path == draft.manifest.entryPoint) {
                                WebAppProjectFileRole.ENTRY
                            } else {
                                projectRoleForPath(path)
                            }
                        )
                    )
                }
            }
        )
    }

    private fun createWebProjectHarnessFileTool(
        state: StreamingSessionState,
        binding: MutableWebProjectBinding,
        projectFileTool: WebProjectFileTool,
        isModification: Boolean
    ): HarnessFileTool {
        return HarnessFileTool { request ->
            val call = request.call
            val requestedPath = call.arguments[WebProjectFileTool.ARG_PATH]
            val result = when {
                call.name == WebProjectFileTool.TOOL_DELETE_FILE &&
                    requestedPath == PROJECT_PLAN_FILE_NAME -> {
                    com.hfad.mantou.utils.project.WebProjectToolExecutionResult(
                        success = false,
                        output = "project.json 由编排器维护，不能删除",
                        diagnostics = listOf("PROJECT_PLAN_DELETE_FORBIDDEN"),
                        metadata = toolPolicyMetadata(
                            tool = call.name,
                            path = requestedPath,
                            policyCode = "PROJECT_PLAN_DELETE_FORBIDDEN"
                        )
                    )
                }

                call.name == WebProjectFileTool.TOOL_WRITE_FILE &&
                    requestedPath == PROJECT_PLAN_FILE_NAME -> {
                    updateVisibleProjectPlan(binding, projectFileTool, call.arguments)
                }

                call.name == WebProjectFileTool.TOOL_WRITE_FILE &&
                    requestedPath != null && requestedPath !in binding.declaredPaths -> {
                    com.hfad.mantou.utils.project.WebProjectToolExecutionResult(
                        success = false,
                        output = "写入新文件前必须先把路径加入 project.json：$requestedPath",
                        diagnostics = listOf("PROJECT_FILE_NOT_DECLARED: $requestedPath"),
                        metadata = toolPolicyMetadata(
                            tool = call.name,
                            path = requestedPath,
                            policyCode = "PROJECT_FILE_NOT_DECLARED"
                        )
                    )
                }

                call.name == WebProjectFileTool.TOOL_DELETE_FILE &&
                    requestedPath != null && requestedPath in binding.declaredPaths -> {
                    com.hfad.mantou.utils.project.WebProjectToolExecutionResult(
                        success = false,
                        output = "删除文件前必须先从 project.json 移除声明：$requestedPath",
                        diagnostics = listOf("PROJECT_FILE_STILL_DECLARED: $requestedPath"),
                        metadata = toolPolicyMetadata(
                            tool = call.name,
                            path = requestedPath,
                            policyCode = "PROJECT_FILE_STILL_DECLARED"
                        )
                    )
                }

                else -> {
                    val arguments = if (
                        call.name == WebProjectFileTool.TOOL_WRITE_FILE &&
                        requestedPath == binding.manifest.entryPoint
                    ) {
                        call.arguments + (
                            WebProjectFileTool.ARG_CONTENT to AppGenerator.ensureWebAppIdentity(
                                call.arguments[WebProjectFileTool.ARG_CONTENT].orEmpty()
                            )
                        )
                    } else {
                        call.arguments
                    }
                    projectFileTool.execute(call.name, arguments)
                }
            }

            if (result.success && result.changedFiles.isNotEmpty()) {
                val projectFiles = projectFilePaths(projectFileTool)
                val activePath = result.changedFiles.last()
                val activeContent = runCatching { projectFileTool.read(activePath).content }
                    .getOrDefault("")
                withContext(Dispatchers.Main.immediate) {
                    updateProjectFileState(
                        state = state,
                        relativePath = activePath,
                        content = activeContent,
                        entryPath = binding.entryFile.absolutePath,
                        projectFiles = projectFiles,
                        isModification = isModification
                    )
                }
            }
            HarnessToolResult(
                callId = call.id,
                success = result.success,
                output = result.output,
                diagnostics = result.diagnostics,
                changedFiles = result.changedFiles,
                artifactPath = binding.entryFile.absolutePath,
                metadata = result.metadata
            )
        }
    }

    private fun toolPolicyMetadata(
        tool: String,
        path: String?,
        policyCode: String
    ): Map<String, String> = buildMap {
        put("tool", tool)
        path?.let { put("path", it) }
        put("error_type", "PolicyRejected")
        put("failure_stage", "policy")
        put("policy_code", policyCode)
    }

    private fun updateVisibleProjectPlan(
        binding: MutableWebProjectBinding,
        projectFileTool: WebProjectFileTool,
        arguments: Map<String, String>
    ): com.hfad.mantou.utils.project.WebProjectToolExecutionResult {
        val content = arguments[WebProjectFileTool.ARG_CONTENT]
            ?: return com.hfad.mantou.utils.project.WebProjectToolExecutionResult(
                success = false,
                output = "write_file requires argument: content",
                diagnostics = listOf("PROJECT_PLAN_CONTENT_MISSING"),
                metadata = mapOf(
                    "tool" to WebProjectFileTool.TOOL_WRITE_FILE,
                    "path" to PROJECT_PLAN_FILE_NAME,
                    "error_type" to "MissingArgument",
                    "failure_stage" to "arguments",
                    "policy_code" to "PROJECT_PLAN_CONTENT_MISSING"
                )
            )
        val parsedManifest = runCatching {
            WebProjectPlanCodec.parse(content).manifest.copy(
                projectId = binding.manifest.projectId,
                stateFile = binding.manifest.stateFile
            )
        }.getOrElse { error ->
            return com.hfad.mantou.utils.project.WebProjectToolExecutionResult(
                success = false,
                output = error.message ?: "project.json 格式无效",
                diagnostics = listOf("PROJECT_PLAN_INVALID: ${error.message.orEmpty()}"),
                metadata = toolPolicyMetadata(
                    tool = WebProjectFileTool.TOOL_WRITE_FILE,
                    path = PROJECT_PLAN_FILE_NAME,
                    policyCode = "PROJECT_PLAN_INVALID"
                ) + ("error_type" to error::class.java.simpleName)
            )
        }
        if (parsedManifest.entryPoint != binding.manifest.entryPoint) {
            return com.hfad.mantou.utils.project.WebProjectToolExecutionResult(
                success = false,
                output = "当前任务不能改变项目入口 ${binding.manifest.entryPoint}",
                diagnostics = listOf("PROJECT_ENTRY_CHANGE_FORBIDDEN"),
                metadata = toolPolicyMetadata(
                    tool = WebProjectFileTool.TOOL_WRITE_FILE,
                    path = PROJECT_PLAN_FILE_NAME,
                    policyCode = "PROJECT_ENTRY_CHANGE_FORBIDDEN"
                )
            )
        }
        if (!preservesHostOwnedAppSpec(binding.manifest, parsedManifest)) {
            return com.hfad.mantou.utils.project.WebProjectToolExecutionResult(
                success = false,
                output = "当前任务不能删除或改写宿主已有的 AppSpec",
                diagnostics = listOf("PROJECT_APP_SPEC_CHANGE_FORBIDDEN"),
                metadata = toolPolicyMetadata(
                    tool = WebProjectFileTool.TOOL_WRITE_FILE,
                    path = PROJECT_PLAN_FILE_NAME,
                    policyCode = "PROJECT_APP_SPEC_CHANGE_FORBIDDEN"
                )
            )
        }

        val writeResult = projectFileTool.execute(
            WebProjectFileTool.TOOL_WRITE_FILE,
            arguments + (WebProjectFileTool.ARG_CONTENT to renderVisibleProjectPlan(parsedManifest))
        )
        if (!writeResult.success) return writeResult
        binding.manifest = parsedManifest
        return writeResult
    }

    private fun synchronizeProjectManifest(
        binding: MutableWebProjectBinding,
        projectFileTool: WebProjectFileTool
    ) {
        val planContent = projectFileTool.read(PROJECT_PLAN_FILE_NAME).content
        val parsedManifest = WebProjectPlanCodec.parse(planContent).manifest.copy(
            projectId = binding.manifest.projectId,
            stateFile = binding.manifest.stateFile
        )
        require(parsedManifest.entryPoint == binding.manifest.entryPoint) {
            "项目入口在发布前发生变化"
        }
        require(preservesHostOwnedAppSpec(binding.manifest, parsedManifest)) {
            "宿主已有的 AppSpec 在发布前发生变化"
        }
        binding.manifest = parsedManifest
    }

    private fun preservesHostOwnedAppSpec(
        baseline: WebAppProjectManifest,
        candidate: WebAppProjectManifest
    ): Boolean {
        return WebAppSpecPolicy.preservesBaseline(
            baseline = baseline.appSpec,
            candidate = candidate.appSpec
        )
    }

    private fun ensureProjectEntryIdentity(
        binding: MutableWebProjectBinding,
        projectFileTool: WebProjectFileTool
    ) {
        if (!binding.entryFile.isFile) return
        val current = projectFileTool.read(binding.manifest.entryPoint).content
        val identified = AppGenerator.ensureWebAppIdentity(current)
        if (identified != current) {
            projectFileTool.write(binding.manifest.entryPoint, identified)
        }
    }

    private suspend fun completeGeneratedWebProject(
        state: StreamingSessionState,
        release: WebAppProjectSnapshot,
        isModification: Boolean,
        iteration: Int,
        selfTestBypassed: Boolean = false
    ) {
        val projectFiles = withContext(Dispatchers.IO) {
            projectFilePaths(WebProjectFileTool(release.contentRoot))
        }
        val entryCode = withContext(Dispatchers.IO) { release.entryFile.readText() }
        repository.markSessionAsGenerate(state.sessionId, release.entryFile.absolutePath)
        withContext(Dispatchers.Main.immediate) {
            val current = _generateTaskStates.value.orEmpty()[state.sessionId]
            updateGenerateTaskState(
                (current ?: GenerateTaskState(
                    sessionId = state.sessionId,
                    phase = GenerateTaskState.Phase.COMPLETED,
                    status = "多文件项目已通过验证"
                )).copy(
                    phase = GenerateTaskState.Phase.COMPLETED,
                    code = entryCode,
                    filePath = release.entryFile.absolutePath,
                    activeFilePath = release.manifest.entryPoint,
                    projectFiles = projectFiles,
                    status = if (selfTestBypassed) {
                        "多文件项目已构建、运行检查和测试并发布（自测非阻塞放行）"
                    } else {
                        "多文件项目已构建、测试并发布"
                    },
                    isModification = isModification,
                    harnessIteration = maxOf(current?.harnessIteration ?: 0, iteration),
                    diagnostics = emptyList(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            removeStreamingPlaceholder(state)
            state.thinkingContent.clear()
        }
        addFinalAssistantMessage(
            state = state,
            content = if (isModification) {
                if (selfTestBypassed) {
                    "项目修改已完成，并通过构建、WebView 检查和测试集；自测非阻塞放行，点击下方查看 👇"
                } else {
                    "项目修改已完成，并通过构建、WebView 检查、自测和测试集，点击下方查看 👇"
                }
            } else {
                if (selfTestBypassed) {
                    "多文件 Web 应用已生成，并通过构建、WebView 检查和测试集；自测非阻塞放行，点击下方预览 👇"
                } else {
                    "多文件 Web 应用已生成，并通过完整 Harness，点击下方预览 👇"
                }
            },
            appHtmlPath = release.entryFile.absolutePath
        )
        withContext(Dispatchers.Main.immediate) {
            _appGenerated.value = release.entryFile.absolutePath
        }
    }

    private suspend fun failGeneratedWebProject(
        state: StreamingSessionState,
        message: String,
        diagnostics: List<String>,
        entryPath: String?,
        isModification: Boolean
    ) {
        val current = withContext(Dispatchers.Main.immediate) {
            val base = _generateTaskStates.value.orEmpty()[state.sessionId]
            val failed = (base ?: GenerateTaskState(
                sessionId = state.sessionId,
                phase = GenerateTaskState.Phase.ERROR,
                status = message
            )).copy(
                phase = GenerateTaskState.Phase.ERROR,
                filePath = entryPath ?: base?.filePath,
                status = message,
                isModification = isModification,
                diagnostics = diagnostics,
                updatedAt = System.currentTimeMillis()
            )
            updateGenerateTaskState(failed)
            removeStreamingPlaceholder(state)
            state.thinkingContent.clear()
            _errorMessage.value = message
            failed
        }
        if (!state.hasFinalAssistantMessage) {
            addFinalAssistantMessage(
                state = state,
                content = "多文件 Web 项目未能完成：${current.status}"
            )
        }
    }

    private fun prepareHarnessWebView(webView: WebView) {
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        webView.settings.blockNetworkLoads = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        val metrics = getApplication<Application>().resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        webView.layout(0, 0, width, height)
    }

    private fun nextWebProjectRoot(displayName: String, projectId: String): File {
        val generatedApps = File(getApplication<Application>().filesDir, AgentWorkspace.WEB_DIR)
            .apply { mkdirs() }
        val stem = displayName
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .trim('_', '-', '.', ' ')
            .take(WEB_PROJECT_DIRECTORY_NAME_MAX_CHARS)
            .ifBlank { "馒头Web应用" }
        val uniqueSuffix = projectId
            .filter(Char::isLetterOrDigit)
            .takeLast(WEB_PROJECT_DIRECTORY_ID_CHARS)
            .ifBlank {
                System.currentTimeMillis().toString().takeLast(WEB_PROJECT_DIRECTORY_ID_CHARS)
            }
        var candidate = File(generatedApps, "${stem}_$uniqueSuffix")
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(generatedApps, "${stem}_$suffix")
            suffix++
        }
        return candidate
    }

    private fun projectFilePaths(projectFileTool: WebProjectFileTool): List<String> {
        return projectFileTool.list(".", recursive = true)
            .asSequence()
            .filterNot { it.directory }
            .map { it.path }
            .sorted()
            .toList()
    }

    private fun renderVisibleProjectPlan(manifest: WebAppProjectManifest): String {
        return WebProjectPlanCodec.render(manifest)
    }

    private fun projectRoleForPath(path: String): WebAppProjectFileRole {
        return when (path.substringAfterLast('.', "").lowercase(Locale.US)) {
            "html", "htm" -> WebAppProjectFileRole.OTHER
            "css" -> WebAppProjectFileRole.STYLE
            "js", "mjs" -> WebAppProjectFileRole.SCRIPT
            "json" -> WebAppProjectFileRole.DATA
            "svg", "png", "jpg", "jpeg", "gif", "webp", "avif", "ico" -> {
                WebAppProjectFileRole.ASSET
            }
            else -> WebAppProjectFileRole.OTHER
        }
    }

    private suspend fun normalChatFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        imageBase64List: List<String>
    ) {
        val sessionId = state.sessionId
        val historyMessages = repository.getMessagesBySessionIdOnce(sessionId)
        val systemPrompt = withContext(Dispatchers.IO) {
            AgentWorkspace.buildSystemPrompt(getApplication())
        }
        val apiMessages = buildApiMessages(historyMessages, systemPrompt, imageBase64List)

        val request = ChatRequest(
            model = config.model,
            messages = apiMessages,
            stream = true
        )

        state.thinkingContent.clear()
        state.reconnectPrefixPending = ""
        addStreamingPlaceholder(state, status = "正在思考", useLoadingLayout = false)

        collectChatStreamWithReconnect(state, config, request)
    }

    private suspend fun collectChatStreamWithReconnect(
        state: StreamingSessionState,
        config: ChatCallConfig,
        request: ChatRequest
    ) {
        var reconnectDeadlineAt: Long? = null
        var activeRequest = request

        while (true) {
            var shouldRetry = false
            var finished = false
            var handledError = false

            trackedChatStream(state.sessionId, config, activeRequest)
                .catch { e ->
                    if (e is CancellationException) throw e
                    handledError = true
                    handleApiError(
                        state = state,
                        config = config,
                        rawError = e.message ?: "未知错误",
                        scene = "普通聊天",
                        partialContentToKeep = state.streamingContent.toString().trimEnd().ifEmpty { null }
                    )
                }
                .collect { event ->
                    when (event) {
                        is StreamingApiService.StreamEvent.Start -> {
                            if (reconnectDeadlineAt != null) {
                                clearReconnectStatus(state)
                            }
                        }

                        is StreamingApiService.StreamEvent.Thinking -> {
                            if (state.streamingContent.isEmpty()) {
                                state.thinkingContent.append(event.text)
                                updateStreamingThinking(state, state.thinkingContent.toString())
                            }
                        }

                        is StreamingApiService.StreamEvent.Content -> {
                            val chunk = consumeReconnectChunk(state, event.text)
                            if (chunk.isEmpty()) return@collect
                            state.streamingContent.append(chunk)
                            updateStreamingMessage(state, state.streamingContent.toString())
                        }

                        is StreamingApiService.StreamEvent.Done -> {
                            clearReconnectStatus(state)
                            val finalContent = state.streamingContent.toString().trimEnd()
                            removeStreamingPlaceholder(state)
                            if (finalContent.isNotEmpty()) {
                                addFinalAssistantMessage(state, finalContent)
                            }
                            state.streamingContent.clear()
                            state.thinkingContent.clear()
                            state.reconnectPrefixPending = ""
                            finished = true
                        }

                        is StreamingApiService.StreamEvent.Disconnected -> {
                            val deadlineAt = reconnectDeadlineAt
                                ?: (System.currentTimeMillis() + reconnectWindowMs).also {
                                    reconnectDeadlineAt = it
                                }
                            val remainingMs = deadlineAt - System.currentTimeMillis()
                            if (remainingMs <= 0L) {
                                persistPartialContentWithError(state)
                                finished = true
                            } else {
                                state.reconnectPrefixPending = state.streamingContent.toString()
                                shouldRetry = true
                                updateReconnectStatus(
                                    state,
                                    ceil(remainingMs / 1000.0).toInt().coerceAtLeast(1)
                                )
                            }
                        }

                        is StreamingApiService.StreamEvent.Error -> {
                            handledError = true
                            handleApiError(
                                state = state,
                                config = config,
                                rawError = event.message,
                                scene = "普通聊天",
                                partialContentToKeep = state.streamingContent.toString().trimEnd().ifEmpty { null }
                            )
                        }
                    }
                }

            when {
                finished || handledError -> return
                shouldRetry -> {
                    val deadlineAt = reconnectDeadlineAt ?: return
                    val remainingMs = deadlineAt - System.currentTimeMillis()
                    if (remainingMs <= 0L) {
                        persistPartialContentWithError(state)
                        return
                    }
                    activeRequest = buildReconnectRequest(
                        baseRequest = request,
                        partialContent = state.streamingContent.toString().trimEnd()
                    )
                    delay(min(reconnectCountdownIntervalMs, remainingMs))
                }

                else -> return
            }
        }
    }

    private fun buildReconnectRequest(
        baseRequest: ChatRequest,
        partialContent: String
    ): ChatRequest {
        if (partialContent.isBlank()) {
            return baseRequest
        }

        val reconnectPrompt = AgentWorkspace.buildReconnectResumePrompt(
            context = getApplication(),
            partialContent = partialContent
        )

        val reconnectMessages = baseRequest.messages.toMutableList().apply {
            add(
                ApiMessage(
                    role = ChatMessage.ROLE_ASSISTANT,
                    content = partialContent
                )
            )
            add(
                ApiMessage(
                    role = ChatMessage.ROLE_USER,
                    content = reconnectPrompt
                )
            )
        }

        return baseRequest.copy(messages = reconnectMessages)
    }

    private fun consumeReconnectChunk(state: StreamingSessionState, rawChunk: String): String {
        var chunk = rawChunk
        var pendingPrefix = state.reconnectPrefixPending
        if (pendingPrefix.isEmpty()) return chunk

        val overlap = pendingPrefix.commonPrefixWith(chunk)
        if (overlap.isNotEmpty()) {
            pendingPrefix = pendingPrefix.removePrefix(overlap)
            chunk = chunk.removePrefix(overlap)
        }

        if (chunk.isNotEmpty() && pendingPrefix.isNotEmpty() && chunk.startsWith(pendingPrefix)) {
            chunk = chunk.removePrefix(pendingPrefix)
            pendingPrefix = ""
        }

        state.reconnectPrefixPending = pendingPrefix
        return chunk
    }

    private suspend fun persistPartialContentWithError(state: StreamingSessionState) {
        val content = state.streamingContent.toString().trimEnd()
        clearReconnectStatus(state)
        removeStreamingPlaceholder(state)
        state.streamingContent.clear()
        state.thinkingContent.clear()
        state.reconnectPrefixPending = ""
        if (!state.userMessagePersisted || state.hasFinalAssistantMessage) return
        val finalText = if (content.isNotEmpty()) {
            content + requestFailedSuffix
        } else {
            requestInterruptedFallback
        }
        addFinalAssistantMessage(state, finalText)
    }

    private suspend fun generateAppFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        qualityRetryCount: Int = 0,
        requestMessage: String = userMessage
    ) {
        val sessionId = state.sessionId
        val filteredInput = GenerationInputFilter.filter(userMessage)
        val filteredRequest = GenerationInputFilter.filter(requestMessage)
        val harnessIteration = qualityRetryCount + 1
        state.isGeneratingApp = true
        updateSessionLoadingIndicators()
        try {
            if (qualityRetryCount == 0) {
                replaceGenerateTaskState(
                    GenerateTaskState(
                        sessionId = state.sessionId,
                        phase = GenerateTaskState.Phase.SANITIZING,
                        status = "正在过滤生成需求"
                    )
                )
            }
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.SANITIZING,
                stage = GenerateTaskState.Stage.INPUT,
                outcome = GenerateTaskState.Outcome.PASSED,
                message = filteredInput.notices.firstOrNull() ?: "用户输入已过滤并建立安全边界",
                iteration = harnessIteration,
                diagnostics = filteredInput.notices
            )
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.PROMPTING,
                stage = GenerateTaskState.Stage.PROMPT,
                outcome = GenerateTaskState.Outcome.RUNNING,
                message = "正在合并 System Prompt、设备上下文和工具文档",
                iteration = harnessIteration
            )
            state.thinkingContent.clear()
            addStreamingPlaceholder(state, status = "正在生成应用", useLoadingLayout = true)
            updateStreamingThinking(
                state,
                buildAppGenerationProgressText(elapsedSeconds = 0, receivedChars = 0)
            )

            val generationSystemPrompt = AppGenerator.buildSystemPrompt(
                getApplication(),
                filteredInput.content
            )
            val filteredSystemPrompt = GenerationInputFilter.filterSystemPrompt(
                generationSystemPrompt
            )
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.PROMPTING,
                stage = GenerateTaskState.Stage.PROMPT,
                outcome = GenerateTaskState.Outcome.PASSED,
                message = "System Prompt 与用户需求已完成组装",
                iteration = harnessIteration,
                diagnostics = filteredSystemPrompt.notices
            )
            val apiMessages = listOf(
                ApiMessage(
                    role = "system",
                    content = filteredSystemPrompt.content
                ),
                ApiMessage(role = ChatMessage.ROLE_USER, content = filteredRequest.promptPayload)
            )
            val request = ChatRequest(
                model = config.model,
                messages = apiMessages,
                stream = true,
                maxTokens = AppGenerator.resolveAppGenerationOutputLimit(
                    ContextLimitStore.getTokenLimit(getApplication())
                ),
                temperature = 0.45,
                topP = 0.9
            )

            val htmlBuffer = StringBuilder()
            startAppGenerationProgressHeartbeat(state, htmlBuffer)
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.REQUESTING_MODEL,
                stage = GenerateTaskState.Stage.MODEL,
                outcome = if (qualityRetryCount == 0) {
                    GenerateTaskState.Outcome.RUNNING
                } else {
                    GenerateTaskState.Outcome.RETRYING
                },
                message = if (qualityRetryCount == 0) {
                    "正在请求模型生成初版代码"
                } else {
                    "质量检查未通过，正在请求模型重新生成"
                },
                iteration = harnessIteration
            )

            trackedChatStream(state.sessionId, config, request)
                .catch { e ->
                    if (e is CancellationException) throw e
                    stopAppGenerationProgressHeartbeat(state)
                    markGenerateTaskError(state, e.message ?: "未知错误", htmlBuffer.toString())
                    handleApiError(state, config, e.message ?: "未知错误", "生成网页应用")
                }
                .collect { event ->
                    when (event) {
                        is StreamingApiService.StreamEvent.Thinking -> {
                            state.thinkingContent.append(event.text)
                            updateStreamingThinking(state, state.thinkingContent.toString())
                        }

                        is StreamingApiService.StreamEvent.Content -> {
                            htmlBuffer.append(event.text)
                            updateGenerateTaskCode(
                                state = state,
                                code = htmlBuffer,
                                phase = GenerateTaskState.Phase.WRITING_INITIAL,
                                status = "正在写入 HTML · ${htmlBuffer.length} 字符"
                            )
                            if (state.thinkingContent.isBlank()) {
                                updateStreamingThinking(
                                    state,
                                    buildAppGenerationProgressText(
                                        elapsedSeconds = null,
                                        receivedChars = htmlBuffer.length
                                    )
                                )
                            }
                        }

                        is StreamingApiService.StreamEvent.Done -> {
                            stopAppGenerationProgressHeartbeat(state)
                            updateHarnessStage(
                                state = state,
                                phase = GenerateTaskState.Phase.BUILDING,
                                stage = GenerateTaskState.Stage.MODEL,
                                outcome = GenerateTaskState.Outcome.PASSED,
                                message = "模型已完成本轮代码输出",
                                iteration = harnessIteration
                            )
                            if (state.thinkingContent.isBlank()) {
                                updateStreamingThinking(
                                    state,
                                    "模型已返回代码内容\n正在整理 HTML 并写入本地文件..."
                                )
                            }
                            try {
                                when (val decision = withContext(Dispatchers.Default) {
                                    AppGenerator.decideInitialGeneration(
                                        modelOutput = htmlBuffer.toString(),
                                        attempt = harnessIteration
                                    )
                                }) {
                                    is AppGenerator.InitialGenerationDecision.Retry -> {
                                        updateHarnessStage(
                                            state = state,
                                            phase = GenerateTaskState.Phase.BUILDING,
                                            stage = GenerateTaskState.Stage.BUILD,
                                            outcome = GenerateTaskState.Outcome.FAILED,
                                            message = "初始代码未达到可检查条件，准备受控重试",
                                            iteration = harnessIteration,
                                            diagnostics = decision.diagnostics
                                        )
                                        updateGenerateTaskCode(
                                            state = state,
                                            code = htmlBuffer.toString(),
                                            phase = GenerateTaskState.Phase.PREPARING,
                                            status = "初始代码不完整，准备第 ${harnessIteration + 1} 次生成",
                                            force = true
                                        )
                                        updateStreamingThinking(
                                            state,
                                            "初始代码未达到 Harness 可检查条件，正在要求模型从头重做...",
                                            force = true
                                        )
                                        generateAppFlow(
                                            state = state,
                                            config = config,
                                            userMessage = userMessage,
                                            qualityRetryCount = qualityRetryCount + 1,
                                            requestMessage = AppGenerator.buildQualityRetryUserPrompt(
                                                userMessage,
                                                decision.diagnostics
                                            )
                                        )
                                        return@collect
                                    }

                                    is AppGenerator.InitialGenerationDecision.Fail -> {
                                        updateHarnessStage(
                                            state = state,
                                            phase = GenerateTaskState.Phase.BUILDING,
                                            stage = GenerateTaskState.Stage.BUILD,
                                            outcome = GenerateTaskState.Outcome.FAILED,
                                            message = decision.reason,
                                            iteration = harnessIteration,
                                            diagnostics = decision.diagnostics
                                        )
                                        markGenerateTaskError(
                                            state,
                                            decision.reason,
                                            htmlBuffer.toString()
                                        )
                                        handleApiError(
                                            state,
                                            config,
                                            decision.reason,
                                            "生成网页应用"
                                        )
                                    }

                                    is AppGenerator.InitialGenerationDecision.RunHarness -> {
                                        val htmlContent = decision.html
                                        val hasQualityIssues = decision.diagnostics.isNotEmpty()
                                        updateHarnessStage(
                                            state = state,
                                            phase = GenerateTaskState.Phase.BUILDING,
                                            stage = GenerateTaskState.Stage.BUILD,
                                            outcome = if (hasQualityIssues) {
                                                GenerateTaskState.Outcome.RETRYING
                                            } else {
                                                GenerateTaskState.Outcome.PASSED
                                            },
                                            message = if (hasQualityIssues) {
                                                "候选 HTML 已提取，交由 Harness 诊断并修复"
                                            } else {
                                                "HTML、CSS 与 JavaScript 预构建检查通过"
                                            },
                                            iteration = harnessIteration,
                                            diagnostics = decision.diagnostics
                                        )
                                        updateHarnessStage(
                                            state = state,
                                            phase = GenerateTaskState.Phase.APPLYING_TOOL,
                                            stage = GenerateTaskState.Stage.TOOL,
                                            outcome = GenerateTaskState.Outcome.RUNNING,
                                            message = "正在调用本地文件工具写入代码",
                                            iteration = harnessIteration
                                        )
                                        val file = withContext(Dispatchers.IO) {
                                            AppGenerator.saveHtmlFile(
                                                getApplication(),
                                                htmlContent,
                                                filteredInput.content
                                            )
                                        }
                                        val savedCode = withContext(Dispatchers.IO) { file.readText() }
                                        repository.markSessionAsGenerate(sessionId, file.absolutePath)
                                        updateGenerateTaskCode(
                                            state = state,
                                            code = savedCode,
                                            phase = GenerateTaskState.Phase.APPLYING_TOOL,
                                            status = "代码工具已写入 ${file.name}",
                                            filePath = file.absolutePath,
                                            force = true
                                        )
                                        updateHarnessStage(
                                            state = state,
                                            phase = GenerateTaskState.Phase.APPLYING_TOOL,
                                            stage = GenerateTaskState.Stage.TOOL,
                                            outcome = GenerateTaskState.Outcome.PASSED,
                                            message = "本地代码文件已安全写入",
                                            iteration = harnessIteration,
                                            filePath = file.absolutePath
                                        )
                                        runGeneratedAppHarness(
                                            state = state,
                                            config = config,
                                            userMessage = filteredInput.content,
                                            htmlFile = file,
                                            isModification = false,
                                            startingIteration = harnessIteration,
                                            initialCode = savedCode
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                markGenerateTaskError(
                                    state,
                                    e.message ?: "保存 HTML 时出错",
                                    htmlBuffer.toString()
                                )
                                handleApiError(
                                    state,
                                    config,
                                    e.message ?: "保存 HTML 时出错",
                                    "生成网页应用"
                                )
                            }
                        }

                        is StreamingApiService.StreamEvent.Disconnected -> {
                            stopAppGenerationProgressHeartbeat(state)
                            markGenerateTaskError(state, event.message, htmlBuffer.toString())
                            handleApiError(state, config, event.message, "生成网页应用")
                        }

                        is StreamingApiService.StreamEvent.Error -> {
                            stopAppGenerationProgressHeartbeat(state)
                            markGenerateTaskError(state, event.message, htmlBuffer.toString())
                            handleApiError(state, config, event.message, "生成网页应用")
                        }

                        is StreamingApiService.StreamEvent.Start -> Unit
                    }
                }
        } finally {
            stopAppGenerationProgressHeartbeat(state)
            state.isGeneratingApp = false
            updateSessionLoadingIndicators()
        }
    }

    private suspend fun generateAppDiffFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        htmlFile: File,
        diffRetryCount: Int = 0,
        previousFailure: String? = null
    ) {
        val sessionId = state.sessionId
        val filteredInput = GenerationInputFilter.filter(userMessage)
        val harnessIteration = diffRetryCount + 1
        state.isGeneratingApp = true
        updateSessionLoadingIndicators()
        val diffBuffer = StringBuilder()

        try {
            val diffTool = withContext(Dispatchers.IO) {
                LocalDiffFileTool(getApplication<Application>().filesDir)
            }
            val snapshot = withContext(Dispatchers.IO) { diffTool.readSnapshot(htmlFile) }
            val initialTaskState =
                GenerateTaskState(
                    sessionId = sessionId,
                    phase = GenerateTaskState.Phase.SANITIZING,
                    code = snapshot.content,
                    filePath = htmlFile.absolutePath,
                    status = if (diffRetryCount == 0) "正在过滤修改需求" else "正在重新过滤修复需求",
                    isModification = true,
                    harnessIteration = harnessIteration
                )
            if (diffRetryCount == 0) {
                replaceGenerateTaskState(initialTaskState)
            } else {
                updateGenerateTaskState(initialTaskState)
            }
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.SANITIZING,
                stage = GenerateTaskState.Stage.INPUT,
                outcome = GenerateTaskState.Outcome.PASSED,
                message = "修改需求已过滤并建立安全边界",
                iteration = harnessIteration,
                diagnostics = filteredInput.notices,
                filePath = htmlFile.absolutePath,
                isModification = true
            )
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.PROMPTING,
                stage = GenerateTaskState.Stage.PROMPT,
                outcome = GenerateTaskState.Outcome.RUNNING,
                message = "正在组装当前文件上下文和修复提示词",
                iteration = harnessIteration,
                filePath = htmlFile.absolutePath,
                isModification = true
            )

            state.thinkingContent.clear()
            addStreamingPlaceholder(
                state,
                status = if (diffRetryCount == 0) "正在修改应用" else "正在修复补丁格式",
                useLoadingLayout = true
            )
            updateStreamingThinking(
                state,
                if (diffRetryCount == 0) {
                    "已读取当前 HTML，正在生成最小化 diff..."
                } else {
                    "上一份补丁格式不合法，正在基于原文件重新生成规范 unified diff..."
                },
                force = true
            )

            val modificationSystemPrompt = GenerationInputFilter.filterSystemPrompt(
                AppGenerator.buildModificationSystemPrompt(
                    context = getApplication(),
                    relativePath = snapshot.relativePath,
                    expectedSha256 = snapshot.sha256,
                    userMessage = filteredInput.content,
                    includeTools = AppGenerator.modificationNeedsTools(filteredInput.content)
                )
            )
            val request = ChatRequest(
                model = config.model,
                messages = listOf(
                    ApiMessage(
                        role = "system",
                        content = modificationSystemPrompt.content
                    ),
                    ApiMessage(
                        role = ChatMessage.ROLE_USER,
                        content = AppGenerator.buildModificationUserPrompt(
                            filteredInput.content,
                            snapshot,
                            previousFailure
                        )
                    )
                ),
                stream = true,
                maxTokens = AppGenerator.resolveAppDiffOutputLimit(
                    ContextLimitStore.getTokenLimit(getApplication())
                ),
                temperature = 0.2,
                topP = 0.9
            )

            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.PROMPTING,
                stage = GenerateTaskState.Stage.PROMPT,
                outcome = GenerateTaskState.Outcome.PASSED,
                message = "修复提示词已就绪",
                iteration = harnessIteration,
                diagnostics = modificationSystemPrompt.notices,
                filePath = htmlFile.absolutePath,
                isModification = true
            )
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.REQUESTING_MODEL,
                stage = GenerateTaskState.Stage.MODEL,
                outcome = if (diffRetryCount == 0) {
                    GenerateTaskState.Outcome.RUNNING
                } else {
                    GenerateTaskState.Outcome.RETRYING
                },
                message = if (diffRetryCount == 0) "正在请求模型生成代码补丁" else "正在请求模型重算修复补丁",
                iteration = harnessIteration,
                filePath = htmlFile.absolutePath,
                isModification = true
            )

            trackedChatStream(state.sessionId, config, request)
                .catch { error ->
                    if (error is CancellationException) throw error
                    val message = error.message ?: "生成 diff 时出错"
                    if (StreamingApiService.isRetryableNetworkFailure(message) &&
                        retryAppDiffFlow(
                            state = state,
                            config = config,
                            userMessage = userMessage,
                            htmlFile = htmlFile,
                            diffRetryCount = diffRetryCount,
                            failure = message,
                            diffCode = diffBuffer,
                            status = "网络连接超时，准备自动重试"
                        )
                    ) {
                        return@catch
                    }
                    markGenerateTaskError(
                        state,
                        message,
                        diffBuffer.toString(),
                        htmlFile.absolutePath,
                        isModification = true
                    )
                    handleApiError(state, config, message, "增量修改网页应用")
                }
                .collect { event ->
                    when (event) {
                        is StreamingApiService.StreamEvent.Thinking -> {
                            state.thinkingContent.append(event.text)
                            updateStreamingThinking(state, state.thinkingContent.toString())
                        }

                        is StreamingApiService.StreamEvent.Content -> {
                            diffBuffer.append(event.text)
                            updateGenerateTaskCode(
                                state = state,
                                code = diffBuffer,
                                phase = GenerateTaskState.Phase.WRITING_DIFF,
                                status = "正在写入 DIFF · ${diffBuffer.length} 字符",
                                filePath = htmlFile.absolutePath,
                                isModification = true
                            )
                        }

                        is StreamingApiService.StreamEvent.Done -> {
                            updateHarnessStage(
                                state = state,
                                phase = GenerateTaskState.Phase.APPLYING_TOOL,
                                stage = GenerateTaskState.Stage.MODEL,
                                outcome = GenerateTaskState.Outcome.PASSED,
                                message = "模型已完成本轮 DIFF 输出",
                                iteration = harnessIteration,
                                filePath = htmlFile.absolutePath,
                                isModification = true
                            )
                            updateGenerateTaskCode(
                                state = state,
                                code = diffBuffer.toString(),
                                phase = GenerateTaskState.Phase.APPLYING_DIFF,
                                status = "正在校验并应用 DIFF",
                                filePath = htmlFile.absolutePath,
                                isModification = true,
                                force = true
                            )
                            try {
                                val unifiedDiff = AppGenerator.extractUnifiedDiff(diffBuffer.toString())
                                    ?: throw LocalDiffFileTool.DiffException("模型没有返回合法 unified diff")
                                val applyResult = withContext(Dispatchers.IO) {
                                    diffTool.apply(htmlFile, snapshot.sha256, unifiedDiff) { patchedContent ->
                                        AppGenerator.validatePatchedWebApp(snapshot.content, patchedContent)
                                    }
                                }
                                val updatedCode = withContext(Dispatchers.IO) { htmlFile.readText() }
                                repository.markSessionAsGenerate(sessionId, htmlFile.absolutePath)
                                updateGenerateTaskCode(
                                    state = state,
                                    code = updatedCode,
                                    phase = GenerateTaskState.Phase.APPLYING_TOOL,
                                    status = "代码工具已应用 ${applyResult.hunkCount} 处修改 · +${applyResult.additions} -${applyResult.deletions}",
                                    filePath = htmlFile.absolutePath,
                                    isModification = true,
                                    force = true
                                )
                                updateHarnessStage(
                                    state,
                                    phase = GenerateTaskState.Phase.APPLYING_TOOL,
                                    stage = GenerateTaskState.Stage.TOOL,
                                    outcome = GenerateTaskState.Outcome.PASSED,
                                    message = "本地 diff 工具已应用修改",
                                    iteration = harnessIteration,
                                    filePath = htmlFile.absolutePath,
                                    isModification = true
                                )
                                runGeneratedAppHarness(
                                    state = state,
                                    config = config,
                                    userMessage = filteredInput.content,
                                    htmlFile = htmlFile,
                                    isModification = true,
                                    startingIteration = harnessIteration,
                                    initialCode = updatedCode
                                )
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                logGeneratedAppDiffFailure(
                                    state = state,
                                    htmlFile = htmlFile,
                                    diffRetryCount = diffRetryCount,
                                    diffCode = diffBuffer,
                                    error = error
                                )
                                if (error is LocalDiffFileTool.DiffException &&
                                    retryAppDiffFlow(
                                        state = state,
                                        config = config,
                                        userMessage = userMessage,
                                        htmlFile = htmlFile,
                                        diffRetryCount = diffRetryCount,
                                        failure = error.message ?: "补丁格式不合法",
                                        diffCode = diffBuffer,
                                        status = "补丁格式或上下文无效，准备自动重试"
                                    )
                                ) {
                                    return@collect
                                }
                                markGenerateTaskError(
                                    state,
                                    error.message ?: "应用 diff 时出错",
                                    diffBuffer.toString(),
                                    htmlFile.absolutePath,
                                    isModification = true
                                )
                                handleApiError(
                                    state,
                                    config,
                                    error.message ?: "应用 diff 时出错",
                                    "增量修改网页应用"
                                )
                            }
                        }

                        is StreamingApiService.StreamEvent.Disconnected -> {
                            if (retryAppDiffFlow(
                                    state = state,
                                    config = config,
                                    userMessage = userMessage,
                                    htmlFile = htmlFile,
                                    diffRetryCount = diffRetryCount,
                                    failure = event.message,
                                    diffCode = diffBuffer,
                                    status = "网络连接中断，准备自动重试"
                                )
                            ) {
                                return@collect
                            }
                            markGenerateTaskError(
                                state,
                                event.message,
                                diffBuffer.toString(),
                                htmlFile.absolutePath,
                                isModification = true
                            )
                            handleApiError(state, config, event.message, "增量修改网页应用")
                        }

                        is StreamingApiService.StreamEvent.Error -> {
                            if (StreamingApiService.isRetryableNetworkFailure(event.message) &&
                                retryAppDiffFlow(
                                    state = state,
                                    config = config,
                                    userMessage = userMessage,
                                    htmlFile = htmlFile,
                                    diffRetryCount = diffRetryCount,
                                    failure = event.message,
                                    diffCode = diffBuffer,
                                    status = "网络连接超时，准备自动重试"
                                )
                            ) {
                                return@collect
                            }
                            markGenerateTaskError(
                                state,
                                event.message,
                                diffBuffer.toString(),
                                htmlFile.absolutePath,
                                isModification = true
                            )
                            handleApiError(state, config, event.message, "增量修改网页应用")
                        }

                        is StreamingApiService.StreamEvent.Start -> Unit
                    }
                }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val currentTask = _generateTaskStates.value.orEmpty()[sessionId]
            if (currentTask?.phase != GenerateTaskState.Phase.ERROR) {
                markGenerateTaskError(
                    state,
                    error.message ?: "无法读取现有应用",
                    currentTask?.code?.ifBlank { null } ?: diffBuffer.toString(),
                    htmlFile.absolutePath,
                    isModification = true
                )
            }
            handleApiError(state, config, error.message ?: "无法读取现有应用", "增量修改网页应用")
        } finally {
            state.isGeneratingApp = false
            updateSessionLoadingIndicators()
        }
    }

    private suspend fun retryAppDiffFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        htmlFile: File,
        diffRetryCount: Int,
        failure: String,
        diffCode: CharSequence,
        status: String
    ): Boolean {
        if (diffRetryCount != 0) return false
        updateGenerateTaskCode(
            state = state,
            code = diffCode,
            phase = GenerateTaskState.Phase.PREPARING,
            status = status,
            filePath = htmlFile.absolutePath,
            isModification = true,
            force = true
        )
        generateAppDiffFlow(
            state = state,
            config = config,
            userMessage = userMessage,
            htmlFile = htmlFile,
            diffRetryCount = 1,
            previousFailure = failure
        )
        return true
    }

    private suspend fun runGeneratedAppHarness(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        htmlFile: File,
        isModification: Boolean,
        startingIteration: Int,
        initialCode: String? = null
    ) {
        var iteration = startingIteration.coerceAtLeast(1)
        var code = initialCode ?: withContext(Dispatchers.IO) { htmlFile.readText() }

        while (iteration <= GENERATED_APP_HARNESS_MAX_ITERATIONS) {
            val inspectorWebView = withContext(Dispatchers.Main) {
                WebView(getApplication<Application>())
            }
            val inspector = GeneratedAppWebViewInspector(
                webView = inspectorWebView,
                prepareWebView = { webView ->
                    webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
                    webView.settings.blockNetworkLoads = true
                    webView.settings.allowFileAccess = false
                    webView.settings.allowContentAccess = false
                    val metrics = getApplication<Application>().resources.displayMetrics
                    val width = metrics.widthPixels.coerceAtLeast(1)
                    val height = metrics.heightPixels.coerceAtLeast(1)
                    webView.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                    )
                    webView.layout(0, 0, width, height)
                }
            )

            try {
                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.BUILDING,
                    stage = GenerateTaskState.Stage.BUILD,
                    outcome = GenerateTaskState.Outcome.RUNNING,
                    message = "正在构建并检查 HTML/JavaScript",
                    iteration = iteration,
                    filePath = htmlFile.absolutePath,
                    isModification = isModification
                )
                val buildReport = inspector.inspectBuild(code)
                if (!buildReport.passed) {
                    val diagnostics = GeneratedAppHarnessScripts.diagnostics(buildReport)
                    updateHarnessFailure(
                        state = state,
                        phase = GenerateTaskState.Phase.BUILDING,
                        stage = GenerateTaskState.Stage.BUILD,
                        message = "构建失败，准备返回模型修复",
                        iteration = iteration,
                        diagnostics = diagnostics,
                        htmlFile = htmlFile,
                        isModification = isModification
                    )
                    code = repairGeneratedAppFromDiagnostics(
                        state = state,
                        config = config,
                        userMessage = userMessage,
                        htmlFile = htmlFile,
                        failedStage = "build",
                        diagnostics = diagnostics,
                        iteration = iteration,
                        isModification = isModification
                    )
                    iteration++
                    continue
                }
                updateHarnessReportPassed(
                    state,
                    GenerateTaskState.Phase.BUILDING,
                    GenerateTaskState.Stage.BUILD,
                    "构建成功",
                    iteration,
                    buildReport,
                    htmlFile,
                    isModification
                )

                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.INSPECTING,
                    stage = GenerateTaskState.Stage.INSPECT,
                    outcome = GenerateTaskState.Outcome.RUNNING,
                    message = "正在通过 WebView 检查器读取运行错误",
                    iteration = iteration,
                    filePath = htmlFile.absolutePath,
                    isModification = isModification
                )
                val runtimeReport = inspector.inspectRuntime(WebInspectionTarget.Html(code))
                if (!runtimeReport.passed) {
                    val diagnostics = GeneratedAppHarnessScripts.diagnostics(runtimeReport)
                    updateHarnessFailure(
                        state,
                        GenerateTaskState.Phase.INSPECTING,
                        GenerateTaskState.Stage.INSPECT,
                        "WebView 运行检查失败，准备返回模型修复",
                        iteration,
                        diagnostics,
                        htmlFile,
                        isModification
                    )
                    code = repairGeneratedAppFromDiagnostics(
                        state,
                        config,
                        userMessage,
                        htmlFile,
                        "runtime-inspection",
                        diagnostics,
                        iteration,
                        isModification
                    )
                    iteration++
                    continue
                }
                updateHarnessReportPassed(
                    state,
                    GenerateTaskState.Phase.INSPECTING,
                    GenerateTaskState.Stage.INSPECT,
                    "WebView 检查器未发现运行错误",
                    iteration,
                    runtimeReport,
                    htmlFile,
                    isModification
                )

                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.SELF_TESTING,
                    stage = GenerateTaskState.Stage.SELF_TEST,
                    outcome = GenerateTaskState.Outcome.RUNNING,
                    message = "正在运行页面自测",
                    iteration = iteration,
                    filePath = htmlFile.absolutePath,
                    isModification = isModification
                )
                val selfTestReport = inspector.runSelfTests(
                    target = WebInspectionTarget.Html(code),
                    testScript = GeneratedAppHarnessScripts.selfTest
                )
                if (!selfTestReport.passed) {
                    val diagnostics = GeneratedAppHarnessScripts.diagnostics(selfTestReport)
                    updateHarnessFailure(
                        state,
                        GenerateTaskState.Phase.SELF_TESTING,
                        GenerateTaskState.Stage.SELF_TEST,
                        "页面自测未通过，准备返回模型修复",
                        iteration,
                        diagnostics,
                        htmlFile,
                        isModification
                    )
                    code = repairGeneratedAppFromDiagnostics(
                        state,
                        config,
                        userMessage,
                        htmlFile,
                        "self-test",
                        diagnostics,
                        iteration,
                        isModification
                    )
                    iteration++
                    continue
                }
                updateHarnessReportPassed(
                    state,
                    GenerateTaskState.Phase.SELF_TESTING,
                    GenerateTaskState.Stage.SELF_TEST,
                    "页面自测通过",
                    iteration,
                    selfTestReport,
                    htmlFile,
                    isModification
                )

                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.BUILDING,
                    stage = GenerateTaskState.Stage.BUILD,
                    outcome = GenerateTaskState.Outcome.RUNNING,
                    message = "自测通过，正在执行交付前构建",
                    iteration = iteration,
                    filePath = htmlFile.absolutePath,
                    isModification = isModification
                )
                val finalBuildReport = inspector.inspectBuild(code)
                val finalBuildFailure = runCatching {
                    AppGenerator.validateGeneratedWebApp(code)
                }.exceptionOrNull()
                if (!finalBuildReport.passed || finalBuildFailure != null) {
                    val diagnostics = (
                        GeneratedAppHarnessScripts.diagnostics(finalBuildReport) +
                            listOfNotNull(
                                finalBuildFailure?.message ?: finalBuildFailure?.let {
                                    "交付前构建校验失败"
                                }
                            )
                        ).distinct()
                    updateHarnessFailure(
                        state,
                        GenerateTaskState.Phase.BUILDING,
                        GenerateTaskState.Stage.BUILD,
                        "交付前构建失败，准备返回模型修复",
                        iteration,
                        diagnostics,
                        htmlFile,
                        isModification
                    )
                    code = repairGeneratedAppFromDiagnostics(
                        state,
                        config,
                        userMessage,
                        htmlFile,
                        "final-build",
                        diagnostics,
                        iteration,
                        isModification
                    )
                    iteration++
                    continue
                }
                updateHarnessReportPassed(
                    state,
                    GenerateTaskState.Phase.BUILDING,
                    GenerateTaskState.Stage.BUILD,
                    "交付前构建通过",
                    iteration,
                    finalBuildReport,
                    htmlFile,
                    isModification
                )

                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.TESTING,
                    stage = GenerateTaskState.Stage.TEST,
                    outcome = GenerateTaskState.Outcome.RUNNING,
                    message = "正在运行独立测试集",
                    iteration = iteration,
                    filePath = htmlFile.absolutePath,
                    isModification = isModification
                )
                val testSuiteReport = inspector.runTestSuite(
                    target = WebInspectionTarget.Html(code),
                    testScript = GeneratedAppHarnessScripts.testSuite
                )
                if (!testSuiteReport.passed) {
                    val diagnostics = GeneratedAppHarnessScripts.diagnostics(testSuiteReport)
                    updateHarnessFailure(
                        state,
                        GenerateTaskState.Phase.TESTING,
                        GenerateTaskState.Stage.TEST,
                        "测试集未通过，准备返回代码阶段修复",
                        iteration,
                        diagnostics,
                        htmlFile,
                        isModification
                    )
                    code = repairGeneratedAppFromDiagnostics(
                        state,
                        config,
                        userMessage,
                        htmlFile,
                        "test-suite",
                        diagnostics,
                        iteration,
                        isModification
                    )
                    iteration++
                    continue
                }
                updateHarnessReportPassed(
                    state,
                    GenerateTaskState.Phase.TESTING,
                    GenerateTaskState.Stage.TEST,
                    "独立测试集通过",
                    iteration,
                    testSuiteReport,
                    htmlFile,
                    isModification
                )
                completeGeneratedAppHarness(
                    state = state,
                    htmlFile = htmlFile,
                    code = code,
                    isModification = isModification,
                    iteration = iteration
                )
                return
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    inspector.cancelCurrentInspection()
                    inspectorWebView.stopLoading()
                    inspectorWebView.removeAllViews()
                    inspectorWebView.destroy()
                }
            }
        }

        throw IllegalStateException("Harness 自动修复已达到 $GENERATED_APP_HARNESS_MAX_ITERATIONS 轮上限")
    }

    private fun updateHarnessFailure(
        state: StreamingSessionState,
        phase: GenerateTaskState.Phase,
        stage: GenerateTaskState.Stage,
        message: String,
        iteration: Int,
        diagnostics: List<String>,
        htmlFile: File,
        isModification: Boolean
    ) {
        updateHarnessStage(
            state = state,
            phase = phase,
            stage = stage,
            outcome = GenerateTaskState.Outcome.FAILED,
            message = message,
            iteration = iteration,
            diagnostics = diagnostics.ifEmpty { listOf(message) },
            filePath = htmlFile.absolutePath,
            isModification = isModification
        )
    }

    private fun updateHarnessReportPassed(
        state: StreamingSessionState,
        phase: GenerateTaskState.Phase,
        stage: GenerateTaskState.Stage,
        message: String,
        iteration: Int,
        report: WebInspectionReport,
        htmlFile: File,
        isModification: Boolean
    ) {
        val warnings = GeneratedAppHarnessScripts.diagnostics(report)
        updateHarnessStage(
            state = state,
            phase = phase,
            stage = stage,
            outcome = GenerateTaskState.Outcome.PASSED,
            message = "$message · ${report.durationMillis}ms",
            iteration = iteration,
            diagnostics = warnings,
            filePath = htmlFile.absolutePath,
            isModification = isModification
        )
    }

    private suspend fun completeGeneratedAppHarness(
        state: StreamingSessionState,
        htmlFile: File,
        code: String,
        isModification: Boolean,
        iteration: Int
    ) {
        updateGenerateTaskCode(
            state = state,
            code = code,
            phase = GenerateTaskState.Phase.COMPLETED,
            status = "构建、运行检查、自测与测试集均已通过",
            filePath = htmlFile.absolutePath,
            isModification = isModification,
            force = true
        )
        repository.markSessionAsGenerate(state.sessionId, htmlFile.absolutePath)
        removeStreamingPlaceholder(state)
        state.thinkingContent.clear()
        addFinalAssistantMessage(
            state = state,
            content = if (isModification) {
                "已完成增量修改，并通过构建、WebView 检查、自测和测试集，点击下方查看 👇"
            } else {
                "应用已生成，并通过构建、WebView 检查、自测和测试集，点击下方预览 👇"
            },
            appHtmlPath = htmlFile.absolutePath
        )
        _appGenerated.value = htmlFile.absolutePath
        updateHarnessStage(
            state = state,
            phase = GenerateTaskState.Phase.COMPLETED,
            stage = GenerateTaskState.Stage.DELIVER,
            outcome = GenerateTaskState.Outcome.PASSED,
            message = "全部 Harness 阶段通过，可以交付",
            iteration = iteration,
            filePath = htmlFile.absolutePath,
            isModification = isModification
        )
    }

    private suspend fun repairGeneratedAppFromDiagnostics(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        htmlFile: File,
        failedStage: String,
        diagnostics: List<String>,
        iteration: Int,
        isModification: Boolean
    ): String {
        val nextIteration = iteration + 1
        updateHarnessStage(
            state = state,
            phase = GenerateTaskState.Phase.REPAIRING,
            stage = GenerateTaskState.Stage.MODEL,
            outcome = GenerateTaskState.Outcome.RETRYING,
            message = "正在把 $failedStage 诊断返回模型修复",
            iteration = nextIteration,
            diagnostics = diagnostics,
            filePath = htmlFile.absolutePath,
            isModification = isModification
        )
        val diffTool = withContext(Dispatchers.IO) {
            LocalDiffFileTool(getApplication<Application>().filesDir)
        }
        var lastFailure: String? = null

        repeat(GENERATED_APP_REPAIR_PATCH_ATTEMPTS) { attempt ->
            val snapshot = withContext(Dispatchers.IO) { diffTool.readSnapshot(htmlFile) }
            val repairSystemPrompt = GenerationInputFilter.filterSystemPrompt(
                AppGenerator.buildModificationSystemPrompt(
                    context = getApplication(),
                    relativePath = snapshot.relativePath,
                    expectedSha256 = snapshot.sha256,
                    userMessage = userMessage,
                    includeTools = AppGenerator.modificationNeedsTools(userMessage)
                )
            )
            val request = ChatRequest(
                model = config.model,
                messages = listOf(
                    ApiMessage(
                        role = "system",
                        content = repairSystemPrompt.content
                    ),
                    ApiMessage(
                        role = ChatMessage.ROLE_USER,
                        content = AppGenerator.buildHarnessRepairUserPrompt(
                            userMessage = userMessage,
                            snapshot = snapshot,
                            failedStage = failedStage,
                            diagnostics = diagnostics + listOfNotNull(lastFailure),
                            iteration = nextIteration
                        )
                    )
                ),
                stream = true,
                maxTokens = AppGenerator.resolveAppDiffOutputLimit(
                    ContextLimitStore.getTokenLimit(getApplication())
                ),
                temperature = 0.15,
                topP = 0.9
            )
            val diffBuffer = StringBuilder()
            var streamFailure: String? = null

            trackedChatStream(state.sessionId, config, request)
                .catch { error ->
                    if (error is CancellationException) throw error
                    streamFailure = error.message ?: "模型修复请求失败"
                }
                .collect { event ->
                    when (event) {
                        is StreamingApiService.StreamEvent.Content -> {
                            diffBuffer.append(event.text)
                            updateGenerateTaskCode(
                                state = state,
                                code = diffBuffer,
                                phase = GenerateTaskState.Phase.WRITING_DIFF,
                                status = "正在写入 Harness 修复 DIFF · ${diffBuffer.length} 字符",
                                filePath = htmlFile.absolutePath,
                                isModification = true
                            )
                        }

                        is StreamingApiService.StreamEvent.Disconnected -> streamFailure = event.message
                        is StreamingApiService.StreamEvent.Error -> streamFailure = event.message
                        else -> Unit
                    }
                }
            if (streamFailure != null) {
                lastFailure = streamFailure
                if (attempt + 1 < GENERATED_APP_REPAIR_PATCH_ATTEMPTS) {
                    updateHarnessStage(
                        state = state,
                        phase = GenerateTaskState.Phase.REPAIRING,
                        stage = GenerateTaskState.Stage.MODEL,
                        outcome = GenerateTaskState.Outcome.RETRYING,
                        message = "模型修复请求中断，正在重试",
                        iteration = nextIteration,
                        diagnostics = listOf(streamFailure.orEmpty()),
                        filePath = htmlFile.absolutePath,
                        isModification = true
                    )
                }
                return@repeat
            }

            val unifiedDiff = AppGenerator.extractUnifiedDiff(diffBuffer.toString())
            if (unifiedDiff == null) {
                lastFailure = "模型没有返回合法 unified diff"
                return@repeat
            }
            val applyResult = runCatching {
                withContext(Dispatchers.IO) {
                    diffTool.apply(htmlFile, snapshot.sha256, unifiedDiff) { patchedContent ->
                        AppGenerator.validatePatchedWebApp(snapshot.content, patchedContent)
                    }
                }
            }
            if (applyResult.isSuccess) {
                val result = applyResult.getOrThrow()
                val updatedCode = withContext(Dispatchers.IO) { htmlFile.readText() }
                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.APPLYING_TOOL,
                    stage = GenerateTaskState.Stage.TOOL,
                    outcome = GenerateTaskState.Outcome.PASSED,
                    message = "修复工具已应用 ${result.hunkCount} 处修改，重新进入构建",
                    iteration = nextIteration,
                    filePath = htmlFile.absolutePath,
                    isModification = true
                )
                updateGenerateTaskCode(
                    state = state,
                    code = updatedCode,
                    phase = GenerateTaskState.Phase.BUILDING,
                    status = "修复已写入，正在重新构建",
                    filePath = htmlFile.absolutePath,
                    isModification = true,
                    force = true
                )
                return updatedCode
            }
            lastFailure = applyResult.exceptionOrNull()?.message ?: "修复补丁应用失败"
            if (attempt + 1 < GENERATED_APP_REPAIR_PATCH_ATTEMPTS) {
                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.REPAIRING,
                    stage = GenerateTaskState.Stage.TOOL,
                    outcome = GenerateTaskState.Outcome.RETRYING,
                    message = "修复补丁无效，正在要求模型重算 DIFF",
                    iteration = nextIteration,
                    diagnostics = listOf(lastFailure.orEmpty()),
                    filePath = htmlFile.absolutePath,
                    isModification = true
                )
            }
        }
        throw IllegalStateException(lastFailure ?: "Harness 修复补丁应用失败")
    }

    private fun logGeneratedAppDiffFailure(
        state: StreamingSessionState,
        htmlFile: File,
        diffRetryCount: Int,
        diffCode: CharSequence,
        error: Exception
    ) {
        val response = diffCode.toString()
        Log.e(
            WEB_APP_DIFF_LOG_TAG,
            "Generated app diff failed: sessionId=${state.sessionId}, " +
                "attempt=${diffRetryCount + 1}, file=${htmlFile.absolutePath}, " +
                "responseChars=${response.length}",
            error
        )
        Log.e(
            WEB_APP_DIFF_LOG_TAG,
            "Model diff response preview:\n${buildDiffLogPreview(response)}"
        )
    }

    private fun logHarnessEvent(
        sessionId: Long,
        runId: String,
        event: GenerateTaskState.HarnessEvent
    ) {
        val diagnosticCodes = event.diagnostics.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { it.substringBefore(':').substringBefore(' ').take(80) }
            .distinct()
            .joinToString(",")
        val logMessage = buildString {
            append("run_id=").append(runId)
            append(" session_id=").append(sessionId)
            append(" stage=").append(event.stage.name)
            append(" operation=").append(event.operation ?: "-")
            append(" outcome=").append(event.outcome.name)
            append(" iteration=").append(event.iteration)
            append(" trace_format=legacy_ui_event")
            append(" message_chars=").append(event.message.length)
            append(" message_sha256=").append(traceSha256(event.message))
            append(" diagnostic_count=").append(event.diagnostics.size)
            if (diagnosticCodes.isNotBlank()) {
                append(" diagnostic_codes=").append(diagnosticCodes)
            }
        }
        when (event.outcome) {
            GenerateTaskState.Outcome.FAILED -> Log.e(WEB_HARNESS_LOG_TAG, logMessage)
            GenerateTaskState.Outcome.RETRYING -> Log.w(WEB_HARNESS_LOG_TAG, logMessage)
            else -> Log.i(WEB_HARNESS_LOG_TAG, logMessage)
        }
    }

    private fun createHarnessTraceLogger(
        sessionId: Long,
        expectedRunId: String
    ): HarnessTraceLogger {
        val sequence = AtomicLong()
        return HarnessTraceLogger { event ->
            logHarnessTraceEvent(
                sessionId = sessionId,
                expectedRunId = expectedRunId,
                sequence = sequence.incrementAndGet(),
                event = event
            )
        }
    }

    private fun logHarnessTraceEvent(
        sessionId: Long,
        expectedRunId: String,
        sequence: Long,
        event: HarnessTraceEvent
    ) {
        val details = buildMap {
            putAll(event.details)
            if (event.runId != expectedRunId) put("expected_run_id", expectedRunId)
        }.toSortedMap().entries.joinToString(",") { (key, value) ->
            "${sanitizeHarnessLogValue(key)}=${sanitizeHarnessLogValue(value)}"
        }.take(HARNESS_TRACE_DETAILS_CHARS)
        val logMessage = buildString {
            append("run_id=").append(sanitizeHarnessLogValue(event.runId))
            append(" seq=").append(sequence)
            append(" session_id=").append(sessionId)
            append(" component=").append(sanitizeHarnessLogValue(event.component))
            append(" operation=").append(sanitizeHarnessLogValue(event.operation))
            append(" status=").append(event.status.name)
            append(" iteration=").append(event.iteration ?: 0)
            append(" duration_ms=").append(event.durationMs ?: -1)
            append(" message=").append(sanitizeHarnessLogValue(event.message))
            if (details.isNotBlank()) append(" details={").append(details).append('}')
        }
        when (event.status) {
            HarnessTraceStatus.FAILED -> Log.e(WEB_HARNESS_LOG_TAG, logMessage)
            HarnessTraceStatus.CANCELLED -> Log.w(WEB_HARNESS_LOG_TAG, logMessage)
            else -> Log.i(WEB_HARNESS_LOG_TAG, logMessage)
        }
    }

    private fun recordWebInspectionEvent(
        traceLogger: HarnessTraceLogger,
        runId: String,
        iteration: Int,
        event: WebInspectionEvent
    ) {
        when (event) {
            is WebInspectionEvent.StageStarted -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW",
                operation = "stage_started",
                status = HarnessTraceStatus.STARTED,
                message = "WebView 检查阶段开始",
                iteration = iteration,
                details = buildMap {
                    put("stage", event.stage.name)
                    event.timeoutMillis?.let { put("timeout_ms", it.toString()) }
                    event.settleMillis?.let { put("settle_ms", it.toString()) }
                }
            )

            is WebInspectionEvent.StageCancelled -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW",
                operation = "stage_cancelled",
                status = HarnessTraceStatus.CANCELLED,
                message = "WebView 检查阶段已取消",
                iteration = iteration,
                durationMs = event.durationMillis,
                details = mapOf(
                    "stage" to event.stage.name,
                    "diagnostic_code" to event.diagnosticCode
                )
            )

            is WebInspectionEvent.PageLoading -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW",
                operation = "page_started",
                status = HarnessTraceStatus.PROGRESS,
                message = "WebView 页面开始加载",
                iteration = iteration,
                details = mapOf(
                    "stage" to event.stage.name,
                    "url_chars" to event.urlCharacterCount.toString(),
                    "url_sha256" to event.urlSha256.orEmpty()
                )
            )

            is WebInspectionEvent.PageFinished -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_PAGE",
                operation = "finished",
                status = event.status.toHarnessTraceStatus(),
                message = "WebView 页面加载回调完成",
                iteration = iteration,
                details = buildMap {
                    put("stage", event.stage.name)
                    put("status", event.status.name)
                    put("url_chars", event.urlCharacterCount.toString())
                    put("url_sha256", event.urlSha256.orEmpty())
                    event.diagnosticCode?.let { put("diagnostic_code", it) }
                }
            )

            is WebInspectionEvent.ProbeStarted -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_PROBE",
                operation = event.probe.name,
                status = HarnessTraceStatus.STARTED,
                message = "WebView 探针开始执行",
                iteration = iteration,
                details = mapOf(
                    "stage" to event.stage.name,
                    "input_chars" to event.inputCharacterCount.toString(),
                    "input_sha256" to event.inputSha256
                )
            )

            is WebInspectionEvent.ProbeResult -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_PROBE",
                operation = event.probe.name,
                status = event.status.toHarnessTraceStatus(),
                message = "WebView 探针执行结果",
                iteration = iteration,
                details = mapOf(
                    "stage" to event.stage.name,
                    "status" to event.status.name,
                    "result_chars" to event.resultCharacterCount.toString(),
                    "result_sha256" to event.resultSha256.orEmpty(),
                    "decoded_chars" to event.decodedCharacterCount.toString(),
                    "decoded_sha256" to event.decodedSha256.orEmpty(),
                    "decode_status" to event.decodeStatus.name,
                    "parse_status" to event.parseStatus.name,
                    "diagnostic_codes" to event.diagnosticCodes.joinToString(",")
                )
            )

            is WebInspectionEvent.SelfTestRoot -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_TEST",
                operation = "root_result",
                status = event.status.toHarnessTraceStatus(),
                message = "WebView 自测根结果",
                iteration = iteration,
                details = buildMap {
                    put("stage", event.stage.name)
                    put("status", event.status.name)
                    put("case_count", event.caseCount.toString())
                    event.diagnosticCode?.let { put("diagnostic_code", it) }
                }
            )

            is WebInspectionEvent.SelfTestCaseResult -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_TEST",
                operation = "case_result",
                status = event.status.toHarnessTraceStatus(),
                message = "WebView 自测用例结果",
                iteration = iteration,
                details = buildMap {
                    put("stage", event.stage.name)
                    put("index", event.index.toString())
                    put("status", event.status.name)
                    put("name_chars", event.nameCharacterCount.toString())
                    put("name_sha256", event.nameSha256)
                    event.diagnosticCode?.let { put("diagnostic_code", it) }
                }
            )

            is WebInspectionEvent.InterceptorResult -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_INTERCEPTOR",
                operation = "request",
                status = event.status.toHarnessTraceStatus(),
                message = "WebView 请求拦截结果",
                iteration = iteration,
                details = mapOf(
                    "stage" to event.stage.name,
                    "status" to event.status.name,
                    "url_chars" to event.urlCharacterCount.toString(),
                    "url_sha256" to event.urlSha256.orEmpty(),
                    "response_status" to event.responseStatusCode.toString()
                )
            )

            is WebInspectionEvent.InterceptorFailure -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_INTERCEPTOR",
                operation = "request",
                status = HarnessTraceStatus.FAILED,
                message = "WebView 请求拦截执行失败",
                iteration = iteration,
                details = mapOf(
                    "stage" to event.stage.name,
                    "status" to event.status.name,
                    "url_chars" to event.urlCharacterCount.toString(),
                    "url_sha256" to event.urlSha256.orEmpty(),
                    "diagnostic_code" to event.diagnosticCode
                )
            )

            is WebInspectionEvent.Decision -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_DECISION",
                operation = event.stage.name,
                status = event.status.toHarnessTraceStatus(),
                message = "WebView 最终判定完成",
                iteration = iteration,
                durationMs = event.durationMillis,
                details = mapOf(
                    "timed_out" to event.timedOut.toString(),
                    "timeout_ok" to (!event.timedOut).toString(),
                    "has_error_diagnostics" to event.hasErrorDiagnostics.toString(),
                    "error_free" to (!event.hasErrorDiagnostics).toString(),
                    "all_self_tests_passed" to event.allSelfTestsPassed.toString(),
                    "diagnostic_count" to event.diagnosticCount.toString(),
                    "error_count" to event.errorDiagnosticCount.toString(),
                    "case_count" to event.selfTestCount.toString(),
                    "case_failed" to event.failedSelfTestCount.toString(),
                    "final_passed" to (event.status == WebInspectionEvent.Status.SUCCEEDED).toString(),
                    "diagnostic_codes" to event.diagnosticCodes.joinToString(",")
                )
            )

            is WebInspectionEvent.DiagnosticCaptured -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_DIAGNOSTIC",
                operation = "captured",
                status = if (
                    event.diagnostic.severity ==
                    com.hfad.mantou.utils.harness.WebDiagnosticSeverity.ERROR
                ) {
                    HarnessTraceStatus.FAILED
                } else {
                    HarnessTraceStatus.PROGRESS
                },
                message = "WebView 诊断已捕获",
                iteration = iteration,
                details = buildMap {
                    put("stage", event.stage.name)
                    put("severity", event.diagnostic.severity.name)
                    put("category", event.diagnostic.category.name)
                    put("code", event.diagnostic.code)
                    event.diagnostic.location?.source?.let { source ->
                        put("source_chars", source.length.toString())
                        put("source_sha256", traceSha256(source))
                    }
                    event.diagnostic.location?.line?.let { put("line", it.toString()) }
                    event.diagnostic.location?.column?.let { put("column", it.toString()) }
                }
            )

            is WebInspectionEvent.StageFinished -> {
                val errorCount = event.report.diagnostics.count {
                    it.severity == com.hfad.mantou.utils.harness.WebDiagnosticSeverity.ERROR
                }
                val failedCases = event.report.selfTests.count { !it.passed }
                traceLogger.record(
                    runId = runId,
                    component = "WEBVIEW",
                    operation = "stage_finished",
                    status = if (event.report.passed) {
                        HarnessTraceStatus.SUCCEEDED
                    } else {
                        HarnessTraceStatus.FAILED
                    },
                    message = "WebView 检查阶段完成",
                    iteration = iteration,
                    durationMs = event.report.durationMillis,
                    details = mapOf(
                        "stage" to event.stage.name,
                        "timed_out" to event.report.timedOut.toString(),
                        "error_count" to errorCount.toString(),
                        "diagnostic_count" to event.report.diagnostics.size.toString(),
                        "case_count" to event.report.selfTests.size.toString(),
                        "case_failed" to failedCases.toString(),
                        "final_passed" to event.report.passed.toString()
                    )
                )
            }
        }
    }

    private fun WebInspectionEvent.Status.toHarnessTraceStatus(): HarnessTraceStatus {
        return when (this) {
            WebInspectionEvent.Status.SUCCEEDED,
            WebInspectionEvent.Status.INTERCEPTED -> HarnessTraceStatus.SUCCEEDED

            WebInspectionEvent.Status.FAILED -> HarnessTraceStatus.FAILED
            WebInspectionEvent.Status.IGNORED,
            WebInspectionEvent.Status.PASSTHROUGH,
            WebInspectionEvent.Status.NOT_REQUIRED,
            WebInspectionEvent.Status.NOT_ATTEMPTED -> HarnessTraceStatus.PROGRESS
        }
    }

    private fun currentHarnessIteration(state: StreamingSessionState): Int {
        return _generateTaskStates.value.orEmpty()[state.sessionId]?.harnessIteration ?: 0
    }

    private fun sanitizeHarnessLogValue(value: String): String {
        return ApiLogRedactor.redactBody(value)
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(HARNESS_TRACE_VALUE_CHARS)
    }

    private fun traceSha256(content: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun buildDiffLogPreview(response: String): String {
        if (response.length <= APP_DIFF_LOG_PREVIEW_CHARS) return response
        val previewPartChars = APP_DIFF_LOG_PREVIEW_CHARS / 2
        return buildString(APP_DIFF_LOG_PREVIEW_CHARS + 80) {
            append(response.take(previewPartChars))
            append("\n… ${response.length - APP_DIFF_LOG_PREVIEW_CHARS} characters omitted …\n")
            append(response.takeLast(previewPartChars))
        }
    }

    private fun startAppGenerationProgressHeartbeat(
        state: StreamingSessionState,
        htmlBuffer: StringBuilder
    ) {
        stopAppGenerationProgressHeartbeat(state)
        state.appGenerationProgressJob = fallbackScope.launch(Dispatchers.Main.immediate) {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                val elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                if (state.thinkingContent.isBlank()) {
                    updateStreamingThinking(
                        state,
                        buildAppGenerationProgressText(
                            elapsedSeconds = elapsedSeconds,
                            receivedChars = htmlBuffer.length
                        )
                    )
                }
                state.harnessProgressReporter?.report(
                    message = if (htmlBuffer.isEmpty()) {
                        "模型处理中 · 已等待 ${elapsedSeconds}s"
                    } else {
                        "正在接收 HTML · ${htmlBuffer.length} 字符 · ${elapsedSeconds}s"
                    },
                    stage = GenerateTaskState.Stage.MODEL.name,
                    iteration = _generateTaskStates.value.orEmpty()[state.sessionId]
                        ?.harnessIteration
                        ?: 1,
                    progress = (25 + htmlBuffer.length / 1_000).coerceAtMost(39)
                )
                delay(appGenerationProgressIntervalMs)
            }
        }
    }

    private fun stopAppGenerationProgressHeartbeat(state: StreamingSessionState) {
        state.appGenerationProgressJob?.cancel()
        state.appGenerationProgressJob = null
    }

    private fun buildAppGenerationProgressText(
        elapsedSeconds: Int?,
        receivedChars: Int
    ): String {
        val elapsedLine = elapsedSeconds?.let { "已等待 ${it}s" } ?: "正在接收模型返回"
        val stageLine = when {
            receivedChars <= 0 -> "已提交生成请求，正在等待模型开始返回内容..."
            receivedChars < 2_000 -> "模型已开始返回内容，正在接收 HTML 代码..."
            receivedChars < 12_000 -> "正在持续接收页面结构、样式和交互代码..."
            else -> "已收到较多代码内容，正在等待模型完成收尾..."
        }
        val receivedLine = if (receivedChars > 0) {
            "已接收约 $receivedChars 个字符"
        } else {
            "如果模型没有思考过程，这里会持续显示生成状态"
        }
        return listOf(elapsedLine, stageLine, receivedLine).joinToString("\n")
    }

    private fun updateGenerateTaskCode(
        state: StreamingSessionState,
        code: CharSequence,
        phase: GenerateTaskState.Phase,
        status: String,
        filePath: String? = null,
        isModification: Boolean = false,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (!force && state.lastGenerateTaskUpdateAt != 0L &&
            now - state.lastGenerateTaskUpdateAt < streamingUiUpdateIntervalMs
        ) {
            return
        }
        state.lastGenerateTaskUpdateAt = now
        updateGenerateTaskState(
            GenerateTaskState(
                sessionId = state.sessionId,
                phase = phase,
                code = code.toString(),
                filePath = filePath,
                status = status,
                isModification = isModification,
                updatedAt = now
            )
        )
    }

    private fun updateGenerateTaskState(taskState: GenerateTaskState) {
        val states = _generateTaskStates.value.orEmpty().toMutableMap()
        val previous = states[taskState.sessionId]
        val storedState = if (previous != null && taskState.harnessEvents.isEmpty()) {
            taskState.copy(
                harnessIteration = maxOf(previous.harnessIteration, taskState.harnessIteration),
                harnessEvents = previous.harnessEvents,
                diagnostics = taskState.diagnostics.ifEmpty { previous.diagnostics }
            )
        } else {
            taskState
        }
        states[taskState.sessionId] = storedState
        _generateTaskStates.value = states
        reportHarnessProgress(storedState)
    }

    private fun replaceGenerateTaskState(taskState: GenerateTaskState) {
        val states = _generateTaskStates.value.orEmpty().toMutableMap()
        states[taskState.sessionId] = taskState
        _generateTaskStates.value = states
        reportHarnessProgress(taskState)
    }

    private fun reportHarnessProgress(taskState: GenerateTaskState) {
        val execution = streamingStates[taskState.sessionId] ?: return
        val reporter = execution.harnessProgressReporter ?: return
        val latestEvent = taskState.harnessEvents.lastOrNull()
        val fingerprint = buildString {
            append(taskState.phase.name).append('|')
            append(latestEvent?.stage?.name).append('|')
            append(latestEvent?.operation).append('|')
            append(latestEvent?.outcome?.name).append('|')
            append(latestEvent?.timestamp)
        }
        val now = System.currentTimeMillis()
        val isTerminal = taskState.phase == GenerateTaskState.Phase.COMPLETED ||
            taskState.phase == GenerateTaskState.Phase.ERROR
        val shouldReport = isTerminal ||
            fingerprint != execution.lastHarnessProgressFingerprint ||
            now - execution.lastHarnessProgressUpdateAt >= HARNESS_NOTIFICATION_UPDATE_INTERVAL_MS
        if (!shouldReport) return

        execution.lastHarnessProgressFingerprint = fingerprint
        execution.lastHarnessProgressUpdateAt = now
        when (taskState.phase) {
            GenerateTaskState.Phase.COMPLETED -> reporter.succeed(
                message = taskState.status,
                diagnostics = taskState.diagnostics
            )
            GenerateTaskState.Phase.ERROR -> reporter.fail(
                message = taskState.status,
                diagnostics = taskState.diagnostics
            )
            else -> reporter.report(
                message = taskState.status,
                stage = latestEvent?.stage?.name ?: taskState.phase.name,
                operation = latestEvent?.operation,
                iteration = maxOf(taskState.harnessIteration, latestEvent?.iteration ?: 0),
                progress = harnessProgressPercent(taskState, latestEvent),
                diagnostics = taskState.diagnostics
            )
        }
    }

    private fun harnessProgressPercent(
        taskState: GenerateTaskState,
        latestEvent: GenerateTaskState.HarnessEvent?
    ): Int {
        return when (latestEvent?.stage) {
            GenerateTaskState.Stage.INPUT -> 5
            GenerateTaskState.Stage.PROMPT -> 10
            GenerateTaskState.Stage.MODEL -> 25
            GenerateTaskState.Stage.TOOL -> 40
            GenerateTaskState.Stage.BUILD -> 55
            GenerateTaskState.Stage.INSPECT -> 68
            GenerateTaskState.Stage.SELF_TEST -> 78
            GenerateTaskState.Stage.TEST -> 92
            GenerateTaskState.Stage.DELIVER -> 100
            null -> when (taskState.phase) {
                GenerateTaskState.Phase.SANITIZING -> 5
                GenerateTaskState.Phase.PROMPTING,
                GenerateTaskState.Phase.PREPARING -> 10
                GenerateTaskState.Phase.REQUESTING_MODEL,
                GenerateTaskState.Phase.WRITING_INITIAL,
                GenerateTaskState.Phase.WRITING_DIFF,
                GenerateTaskState.Phase.REPAIRING -> 25
                GenerateTaskState.Phase.APPLYING_DIFF,
                GenerateTaskState.Phase.APPLYING_TOOL -> 40
                GenerateTaskState.Phase.BUILDING -> 55
                GenerateTaskState.Phase.INSPECTING -> 68
                GenerateTaskState.Phase.SELF_TESTING -> 78
                GenerateTaskState.Phase.TESTING -> 92
                GenerateTaskState.Phase.COMPLETED -> 100
                GenerateTaskState.Phase.ERROR -> 0
            }
        }
    }

    private fun updateHarnessStage(
        state: StreamingSessionState,
        phase: GenerateTaskState.Phase,
        stage: GenerateTaskState.Stage,
        outcome: GenerateTaskState.Outcome,
        message: String,
        iteration: Int = 1,
        diagnostics: List<String> = emptyList(),
        filePath: String? = null,
        isModification: Boolean = false
    ) {
        val current = _generateTaskStates.value.orEmpty()[state.sessionId]
        val base = current ?: GenerateTaskState(
            sessionId = state.sessionId,
            phase = phase,
            status = message,
            isModification = isModification
        )
        val next = base.copy(
            phase = phase,
            filePath = filePath ?: base.filePath,
            status = message,
            isModification = isModification || base.isModification,
            harnessIteration = maxOf(base.harnessIteration, iteration),
            diagnostics = diagnostics,
            updatedAt = System.currentTimeMillis()
        ).appendHarnessEvent(
            GenerateTaskState.HarnessEvent(
                stage = stage,
                outcome = outcome,
                message = message,
                iteration = iteration,
                diagnostics = diagnostics
            )
        )
        updateGenerateTaskState(next)
    }

    private fun updateProjectHarnessEvent(
        state: StreamingSessionState,
        event: GenerateTaskState.HarnessEvent,
        isModification: Boolean,
        entryPath: String? = null,
        projectFiles: List<String> = emptyList()
    ) {
        val phase = when (event.stage) {
            GenerateTaskState.Stage.INPUT -> GenerateTaskState.Phase.SANITIZING
            GenerateTaskState.Stage.PROMPT -> GenerateTaskState.Phase.PROMPTING
            GenerateTaskState.Stage.MODEL -> GenerateTaskState.Phase.REQUESTING_MODEL
            GenerateTaskState.Stage.TOOL -> GenerateTaskState.Phase.APPLYING_TOOL
            GenerateTaskState.Stage.BUILD -> GenerateTaskState.Phase.BUILDING
            GenerateTaskState.Stage.INSPECT -> GenerateTaskState.Phase.INSPECTING
            GenerateTaskState.Stage.SELF_TEST -> GenerateTaskState.Phase.SELF_TESTING
            GenerateTaskState.Stage.TEST -> GenerateTaskState.Phase.TESTING
            GenerateTaskState.Stage.DELIVER -> if (
                event.outcome == GenerateTaskState.Outcome.FAILED
            ) {
                GenerateTaskState.Phase.ERROR
            } else {
                GenerateTaskState.Phase.BUILDING
            }
        }
        val current = _generateTaskStates.value.orEmpty()[state.sessionId]
        val base = current ?: GenerateTaskState(
            sessionId = state.sessionId,
            phase = phase,
            status = event.message,
            isModification = isModification
        )
        updateGenerateTaskState(
            base.copy(
                phase = phase,
                filePath = entryPath ?: base.filePath,
                projectFiles = projectFiles.ifEmpty { base.projectFiles },
                status = event.message,
                isModification = isModification,
                harnessIteration = maxOf(base.harnessIteration, event.iteration),
                diagnostics = event.diagnostics,
                updatedAt = event.timestamp
            ).appendHarnessEvent(event)
        )
    }

    private fun updateProjectFileState(
        state: StreamingSessionState,
        relativePath: String,
        content: String,
        entryPath: String?,
        projectFiles: List<String>,
        isModification: Boolean
    ) {
        val current = _generateTaskStates.value.orEmpty()[state.sessionId]
        val base = current ?: GenerateTaskState(
            sessionId = state.sessionId,
            phase = GenerateTaskState.Phase.APPLYING_TOOL,
            status = "正在生成多文件项目",
            isModification = isModification
        )
        updateGenerateTaskState(
            base.copy(
                phase = GenerateTaskState.Phase.APPLYING_TOOL,
                code = content,
                filePath = entryPath ?: base.filePath,
                activeFilePath = relativePath,
                projectFiles = projectFiles,
                status = "已写入 $relativePath · ${projectFiles.size} 个项目文件",
                isModification = isModification,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun markGenerateTaskError(
        state: StreamingSessionState,
        message: String,
        code: String = "",
        filePath: String? = null,
        isModification: Boolean = false
    ) {
        updateGenerateTaskState(
            GenerateTaskState(
                sessionId = state.sessionId,
                phase = GenerateTaskState.Phase.ERROR,
                code = code,
                filePath = filePath,
                status = message,
                isModification = isModification
            )
        )
    }

    private fun restoreGenerateTaskState(sessionId: Long) {
        if (streamingStates[sessionId]?.isGeneratingApp == true) return
        serviceProgressStates.values
            .firstOrNull { it.sessionId == sessionId && it.isRunning }
            ?.let { progress ->
                mergeDetachedHarnessProgress(progress)
                return
            }
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                val session = repository.getSessionById(sessionId) ?: return@withContext null
                val legacyPath = repository.getMessagesBySessionIdOnce(sessionId)
                    .asReversed()
                    .firstNotNullOfOrNull { it.appHtmlPath?.takeIf(String::isNotBlank) }
                val path = session.appHtmlPath?.takeIf(String::isNotBlank) ?: legacyPath
                if (!session.isGenerateTask && path == null) return@withContext null
                val resolved = path
                    ?.let(::File)
                    ?.takeIf(File::exists)
                    ?.let { input -> runCatching { WebAppProjectResolver.resolve(input) }.getOrNull() }
                val managedProject = resolved?.takeUnless {
                    it.kind == WebAppProjectSnapshotKind.LEGACY
                }
                val restoredProject = resolved?.let { project ->
                    if (project.kind == WebAppProjectSnapshotKind.LEGACY) {
                        project
                    } else {
                        WebAppProjectWorkspace().activeRelease(project.projectRoot)
                    }
                }
                if (managedProject != null && restoredProject == null) {
                    return@withContext GenerateTaskState(
                        sessionId = sessionId,
                        phase = GenerateTaskState.Phase.ERROR,
                        status = "项目尚无可用的已发布版本"
                    )
                }
                val restoredPath = restoredProject?.entryFile?.absolutePath ?: path
                if (!session.isGenerateTask || restoredPath != session.appHtmlPath) {
                    repository.markSessionAsGenerate(sessionId, restoredPath)
                }
                val file = if (restoredProject != null) {
                    restoredProject.entryFile.takeIf(File::isFile)
                } else {
                    path?.let(::File)?.takeIf(File::isFile)
                }
                if (file == null) {
                    GenerateTaskState(
                        sessionId = sessionId,
                        phase = GenerateTaskState.Phase.ERROR,
                        filePath = restoredPath,
                        status = "生成任务尚无可用的 HTML 文件"
                    )
                } else {
                    val projectFiles = restoredProject?.let { project ->
                        projectFilePaths(WebProjectFileTool(project.contentRoot))
                    }.orEmpty()
                    GenerateTaskState(
                        sessionId = sessionId,
                        phase = GenerateTaskState.Phase.COMPLETED,
                        code = file.readText(),
                        filePath = file.absolutePath,
                        activeFilePath = restoredProject?.manifest?.entryPoint ?: file.name,
                        projectFiles = projectFiles,
                        status = "应用源码已载入"
                    )
                }
            }
            if (restored != null && streamingStates[sessionId]?.isGeneratingApp != true) {
                updateGenerateTaskState(restored)
            }
        }
    }

    private fun addStreamingPlaceholder(
        state: StreamingSessionState,
        status: String,
        useLoadingLayout: Boolean
    ) {
        state.lastStreamingContentUpdateAt = 0L
        state.lastStreamingThinkingUpdateAt = 0L
        state.placeholder = ChatMessage(
            messageId = streamingMessageId(state.sessionId),
            role = ChatMessage.ROLE_ASSISTANT,
            content = if (useLoadingLayout) "" else assistantStreamingPlaceholder,
            isStreaming = true,
            thinking = null,
            statusText = status,
            showStatusLoader = true
        )
        publishStreamingPlaceholder(state)
    }

    private fun updateStreamingThinking(
        state: StreamingSessionState,
        thinking: String,
        force: Boolean = false
    ) {
        if (!force && !shouldUpdateStreamingThinking(state)) return
        val placeholder = state.placeholder ?: return
        if (placeholder.isStreaming) {
            state.placeholder = placeholder.copy(thinking = thinking)
            publishStreamingPlaceholder(state)
        }
    }

    private fun updateStreamingMessage(
        state: StreamingSessionState,
        content: String,
        force: Boolean = false
    ) {
        if (!force && !shouldUpdateStreamingContent(state)) return
        val placeholder = state.placeholder ?: return
        state.placeholder = placeholder.copy(
            content = content,
            isStreaming = true,
            statusText = null,
            showStatusLoader = false
        )
        publishStreamingPlaceholder(state)
    }

    private fun shouldUpdateStreamingContent(state: StreamingSessionState): Boolean {
        val now = System.currentTimeMillis()
        if (state.lastStreamingContentUpdateAt == 0L ||
            now - state.lastStreamingContentUpdateAt >= streamingUiUpdateIntervalMs
        ) {
            state.lastStreamingContentUpdateAt = now
            return true
        }
        return false
    }

    private fun shouldUpdateStreamingThinking(state: StreamingSessionState): Boolean {
        val now = System.currentTimeMillis()
        if (state.lastStreamingThinkingUpdateAt == 0L ||
            now - state.lastStreamingThinkingUpdateAt >= streamingUiUpdateIntervalMs
        ) {
            state.lastStreamingThinkingUpdateAt = now
            return true
        }
        return false
    }

    private fun publishStreamingPlaceholder(state: StreamingSessionState) {
        if (_currentSessionId.value != state.sessionId) return
        val placeholder = state.placeholder ?: return
        val currentList = _messages.value?.toMutableList() ?: mutableListOf()
        currentList.removeAll { it.messageId == placeholder.messageId }
        currentList.add(placeholder)
        _messages.value = currentList
    }

    private fun removeStreamingPlaceholder(state: StreamingSessionState) {
        val messageId = state.placeholder?.messageId ?: streamingMessageId(state.sessionId)
        state.lastStreamingContentUpdateAt = 0L
        state.lastStreamingThinkingUpdateAt = 0L
        state.placeholder = null
        if (_currentSessionId.value != state.sessionId) return
        val currentList = _messages.value?.toMutableList() ?: return
        currentList.removeAll { it.messageId == messageId }
        _messages.value = currentList
    }

    private fun updateStreamingStatusText(state: StreamingSessionState, text: String) {
        val placeholder = state.placeholder ?: return
        state.placeholder = placeholder.copy(
            statusText = text,
            showStatusLoader = true,
            isStreaming = true
        )
        publishStreamingPlaceholder(state)
    }

    private fun updateReconnectStatus(state: StreamingSessionState, secondsRemaining: Int) {
        updateStreamingStatusText(state, "网络状态出错，尝试重连 ${secondsRemaining}秒")
    }

    private fun clearReconnectStatus(state: StreamingSessionState) {
        val placeholder = state.placeholder ?: return
        val hasVisibleContent = hasVisibleStreamingContent(placeholder.content)
        state.placeholder = placeholder.copy(
            statusText = if (hasVisibleContent) null else "正在思考",
            showStatusLoader = !hasVisibleContent,
            isStreaming = true
        )
        publishStreamingPlaceholder(state)
    }

    private fun hasVisibleStreamingContent(content: String): Boolean {
        return content.isNotBlank() && content != assistantStreamingPlaceholder
    }

    private suspend fun handleApiError(
        state: StreamingSessionState,
        config: ChatCallConfig?,
        rawError: String,
        scene: String,
        partialContentToKeep: String? = null
    ) {
        state.thinkingContent.clear()
        state.streamingContent.clear()
        state.reconnectPrefixPending = ""

        if (state.placeholder != null) {
            updateStreamingStatusText(state, "出错了，馒头正在拼命分析…")
        } else {
            addStreamingPlaceholder(state, "出错了，馒头正在拼命分析…", useLoadingLayout = false)
        }

        val analyzed = config?.let {
            ErrorAnalyzer.analyze(
                config = it,
                rawError = rawError,
                scene = scene,
                tokenUsageListener = { usage -> recordSessionTokenUsage(state.sessionId, usage) },
                diagnosticContext = ApiDiagnosticContext(
                    runId = state.serviceRunId ?: "session-${state.sessionId}",
                    operation = "error.analyze"
                )
            )
        }

        removeStreamingPlaceholder(state)

        partialContentToKeep?.takeIf { it.isNotEmpty() }?.let {
            addFinalAssistantMessage(state, it)
        }

        addFinalAssistantMessage(state, analyzed ?: requestInterruptedFallback)
    }

    fun stopStreaming() {
        _currentSessionId.value?.let { cancelStreaming(it, persistFallback = true) }
    }

    private fun cancelStreaming(sessionId: Long, persistFallback: Boolean = false) {
        val state = streamingStates.remove(sessionId)
        if (state == null) {
            serviceProgressStates.values
                .firstOrNull { it.sessionId == sessionId && it.isRunning }
                ?.let { progress ->
                    HarnessForegroundServiceController.cancel(
                        getApplication<Application>(),
                        progress.runId,
                        "生成任务已停止"
                    )
                }
            updateSessionLoadingIndicators()
            return
        }
        state.job?.cancel()
        state.job = null
        state.serviceRunId?.let { runId ->
            HarnessForegroundServiceController.cancel(
                getApplication<Application>(),
                runId,
                "生成任务已停止"
            )
        } ?: state.serviceJob?.cancel(CancellationException("生成任务已停止"))
        state.serviceJob = null
        state.serviceRunId = null
        state.isServiceOwned = false
        stopAppGenerationProgressHeartbeat(state)
        if (state.isGeneratingApp) {
            val task = _generateTaskStates.value.orEmpty()[sessionId]
            updateGenerateTaskState(
                task?.copy(
                    phase = GenerateTaskState.Phase.ERROR,
                    status = "生成任务已停止",
                    updatedAt = System.currentTimeMillis()
                ) ?: GenerateTaskState(
                    sessionId = sessionId,
                    phase = GenerateTaskState.Phase.ERROR,
                    status = "生成任务已停止"
                )
            )
        }
        state.isGeneratingApp = false
        if (persistFallback) {
            fallbackScope.launch {
                persistFallbackIfNeeded(state)
            }
        }
        state.streamingContent.clear()
        state.thinkingContent.clear()
        state.reconnectPrefixPending = ""
        removeStreamingPlaceholder(state)
        updateSessionLoadingIndicators()
    }

    private fun cancelAllStreaming(
        persistFallback: Boolean = false,
        includeServiceOwned: Boolean = true
    ) {
        streamingStates.keys.toList().forEach { sessionId ->
            if (!includeServiceOwned && streamingStates[sessionId]?.isServiceOwned == true) {
                return@forEach
            }
            cancelStreaming(sessionId, persistFallback = persistFallback)
        }
    }

    private suspend fun finishStreamingState(state: StreamingSessionState) {
        stopAppGenerationProgressHeartbeat(state)
        state.isGeneratingApp = false
        if (streamingStates[state.sessionId] === state) {
            persistFallbackIfNeeded(state)
            streamingStates.remove(state.sessionId)
            state.streamingContent.clear()
            state.thinkingContent.clear()
            state.reconnectPrefixPending = ""
            removeStreamingPlaceholder(state)
            updateSessionLoadingIndicators()
        }
    }

    private fun updateSessionLoadingIndicators() {
        val serviceRunningIds = serviceProgressStates.values
            .asSequence()
            .filter(HarnessProgress::isRunning)
            .mapNotNull(HarnessProgress::sessionId)
            .toSet()
        val runningIds = streamingStates.keys + serviceRunningIds
        _runningSessionIds.value = runningIds
        _isLoading.value = _currentSessionId.value?.let { it in runningIds } == true
        _isGeneratingApp.value = streamingStates.values.any { it.isGeneratingApp } ||
            serviceRunningIds.isNotEmpty()
    }

    private suspend fun addFinalAssistantMessage(
        state: StreamingSessionState,
        content: String,
        appHtmlPath: String? = null
    ): Long {
        state.hasFinalAssistantMessage = true
        return repository.addAssistantMessage(state.sessionId, content, appHtmlPath)
    }

    private suspend fun persistFallbackIfNeeded(state: StreamingSessionState) {
        if (!state.userMessagePersisted || state.hasFinalAssistantMessage) return
        val partialContent = state.streamingContent.toString().trimEnd()
        val fallback = if (partialContent.isNotEmpty()) {
            partialContent + requestFailedSuffix
        } else {
            requestInterruptedFallback
        }
        addFinalAssistantMessage(state, fallback)
    }

    private fun streamingMessageId(sessionId: Long): Long {
        return -sessionId.coerceAtLeast(1L)
    }

    private fun createNewSessionAndSendMessage(
        content: String,
        imagePath: String?,
        imageUris: List<Uri>?
    ) {
        messagesJob?.cancel()
        viewModelScope.launch {
            val sessionId = repository.createSession(content.ifEmpty { "[图片]" })
            _currentSessionId.value = sessionId
            _messages.value = emptyList()
            currentTokenUsageSessionId = sessionId
            _currentSessionTokenUsage.value = SessionTokenUsage()
            loadMessages(sessionId)
            delay(100)
            sendMessage(content, imagePath, imageUris)
        }
    }

    private suspend fun resolveActiveChatConfig(): ChatCallConfig? {
        val ctx = getApplication<Application>()
        val providerId = com.hfad.mantou.data.preferences.ActiveModelStore
            .getActiveProviderId(ctx) ?: return null
        val modelName = com.hfad.mantou.data.preferences.ActiveModelStore
            .getActiveModelName(ctx)?.takeIf { it.isNotBlank() } ?: return null
        val provider = withContext(Dispatchers.IO) {
            providerRepository.getProviderById(providerId)
        } ?: return null
        if (provider.baseUrl.isBlank()) return null
        return ChatCallConfig(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            model = modelName,
            apiFormat = provider.apiFormat
        )
    }

    private fun buildApiMessages(
        historyMessages: List<ChatMessageEntity>,
        systemPrompt: String,
        currentImageBase64List: List<String> = emptyList()
    ): List<ApiMessage> {
        val messages = mutableListOf<ApiMessage>()

        messages.add(
            ApiMessage(
                role = "system",
                content = systemPrompt
            )
        )

        historyMessages.forEachIndexed { index, entity ->
            val isLastUserMessage = index == historyMessages.lastIndex && entity.role == "user"
            val contextContent = ChatContextFormatter.contentForContext(entity)

            if (isLastUserMessage && currentImageBase64List.isNotEmpty()) {
                val contentParts = mutableListOf<ContentPart>()
                if (contextContent.isNotEmpty()) {
                    contentParts.add(ContentPart(type = "text", text = contextContent))
                }
                currentImageBase64List.forEach { base64 ->
                    contentParts.add(
                        ContentPart(
                            type = "image_url",
                            imageUrl = ImageUrl(url = base64)
                        )
                    )
                }
                messages.add(ApiMessage(role = entity.role, content = contentParts))
            } else {
                messages.add(ApiMessage(role = entity.role, content = contextContent))
            }
        }

        return messages
    }

    fun deleteSession(sessionId: Long) {
        cancelStreaming(sessionId)
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            _generateTaskStates.value = _generateTaskStates.value.orEmpty() - sessionId
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = null
                _messages.value = emptyList()
                currentTokenUsageSessionId = null
                _currentSessionTokenUsage.value = SessionTokenUsage()
            }
        }
    }

    fun setSessionArchived(sessionId: Long, isArchived: Boolean) {
        viewModelScope.launch {
            repository.setSessionArchived(sessionId, isArchived)
        }
    }

    fun deleteMessage(messageId: Long) {
        if (messageId <= 0) return
        viewModelScope.launch {
            repository.deleteMessage(messageId)
        }
    }

    fun updateMessageContent(messageId: Long, content: String) {
        if (messageId <= 0 || content.isBlank()) return
        viewModelScope.launch {
            repository.updateMessageContent(messageId, content)
        }
    }

    fun editUserMessageAndRegenerate(messageId: Long, content: String) {
        if (messageId <= 0 || content.isBlank()) return
        viewModelScope.launch {
            val originalMessage = repository.getMessageById(messageId) ?: return@launch
            if (originalMessage.role != ChatMessage.ROLE_USER) return@launch

            cancelStreaming(originalMessage.sessionId, persistFallback = true)
            val editedMessage = repository.updateMessageContentAndDeleteAfter(messageId, content)
                ?: return@launch
            if (repository.getMessageCount(editedMessage.sessionId) == 1) {
                repository.updateSessionTitle(editedMessage.sessionId, content.ifEmpty { "[图片]" })
                if (repository.getSessionById(editedMessage.sessionId)?.isGenerateTask == true) {
                    repository.clearGeneratedApp(editedMessage.sessionId)
                }
            }

            val state = StreamingSessionState(editedMessage.sessionId)
            streamingStates[editedMessage.sessionId] = state
            updateSessionLoadingIndicators()

            state.job = viewModelScope.launch {
                _errorMessage.value = null
                state.streamingContent.clear()
                try {
                    withContext(Dispatchers.IO) {
                        AgentWorkspace.appendExplicitMemoryIfNeeded(getApplication(), content)
                    }

                    val config = resolveActiveChatConfig()
                    if (config == null) {
                        _noModelConfigured.value = true
                        return@launch
                    }

                    _activeModelName.value = config.model
                    state.userMessagePersisted = true

                    val imageBase64List = loadImageBase64List(imagePath = editedMessage.imagePath)
                    generateResponseForPersistedUserMessage(state, config, content, imageBase64List)
                } finally {
                    if (!state.isServiceOwned) {
                        finishStreamingState(state)
                    }
                }
            }
        }
    }

    fun deleteAllSessions() {
        cancelAllStreaming()
        viewModelScope.launch {
            repository.deleteAllSessions()
            _currentSessionId.value = null
            _messages.value = emptyList()
            currentTokenUsageSessionId = null
            _currentSessionTokenUsage.value = SessionTokenUsage()
            _generateTaskStates.value = emptyMap()
        }
    }

    fun clearCurrentSession() {
        _currentSessionId.value?.let { cancelStreaming(it, persistFallback = true) }
        _currentSessionId.value = null
        _messages.value = emptyList()
        currentTokenUsageSessionId = null
        _currentSessionTokenUsage.value = SessionTokenUsage()
        updateSessionLoadingIndicators()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearAppGenerated() {
        _appGenerated.value = null
    }

    fun clearNoModelConfigured() {
        _noModelConfigured.value = false
    }

    override fun onCleared() {
        cancelAllStreaming(persistFallback = true, includeServiceOwned = false)
        messagesJob?.cancel()
        super.onCleared()
    }
}

private const val WEB_APP_DIFF_LOG_TAG = "WebAppDiff"
private const val WEB_HARNESS_LOG_TAG = "ManTouHarness"
private const val APP_DIFF_LOG_PREVIEW_CHARS = 3_000
private const val HARNESS_TRACE_VALUE_CHARS = 1_000
private const val HARNESS_TRACE_DETAILS_CHARS = 8_000
private const val GENERATED_APP_HARNESS_MAX_ITERATIONS = 8
private const val GENERATED_APP_REPAIR_PATCH_ATTEMPTS = 2
private const val HARNESS_NOTIFICATION_UPDATE_INTERVAL_MS = 750L
private const val PROJECT_PLAN_FILE_NAME = "project.json"
private const val DEFAULT_PROJECT_STYLE_PATH = "styles/app.css"
private const val DEFAULT_PROJECT_SCRIPT_PATH = "scripts/app.js"
private const val WEB_PROJECT_PLAN_MAX_TOKENS = 8_000
private const val WEB_PROJECT_MAX_PLANNED_FILES = 24
private const val WEB_PROJECT_DIRECTORY_NAME_MAX_CHARS = 64
private const val WEB_PROJECT_DIRECTORY_ID_CHARS = 8
private data class PreparedWebProject(
    val workspace: WebAppProjectWorkspace,
    val snapshot: WebAppProjectSnapshot
)

private class MutableWebProjectBinding(
    val workspace: WebAppProjectWorkspace,
    val projectRoot: File,
    val contentRoot: File,
    manifest: WebAppProjectManifest,
    val draftVersion: Long
) {
    var manifest: WebAppProjectManifest = manifest

    val entryFile: File
        get() = File(contentRoot, manifest.entryPoint).absoluteFile

    val declaredPaths: Set<String>
        get() = manifest.files.mapTo(linkedSetOf()) { it.path }
}

private data class StreamingSessionState(
    val sessionId: Long,
    var job: Job? = null,
    var serviceJob: Job? = null,
    var serviceRunId: String? = null,
    var isServiceOwned: Boolean = false,
    var harnessProgressReporter: HarnessProgressReporter? = null,
    var appGenerationProgressJob: Job? = null,
    var placeholder: ChatMessage? = null,
    val streamingContent: StringBuilder = StringBuilder(),
    val thinkingContent: StringBuilder = StringBuilder(),
    var isGeneratingApp: Boolean = false,
    var userMessagePersisted: Boolean = false,
    var hasFinalAssistantMessage: Boolean = false,
    var lastStreamingContentUpdateAt: Long = 0L,
    var lastStreamingThinkingUpdateAt: Long = 0L,
    var lastGenerateTaskUpdateAt: Long = 0L,
    var lastHarnessProgressUpdateAt: Long = 0L,
    var lastHarnessProgressFingerprint: String = "",
    var reconnectPrefixPending: String = ""
)

private data class SessionRouting(
    val isGenerateTask: Boolean,
    val appHtmlPath: String?
)

private fun ChatMessageEntity.toChatMessage() = ChatMessage(
    messageId = messageId,
    role = role,
    content = content,
    imagePath = imagePath,
    timestamp = timestamp,
    isStreaming = false,
    appHtmlPath = appHtmlPath,
    statusText = null,
    showStatusLoader = false
)
