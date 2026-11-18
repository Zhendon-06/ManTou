package com.hfad.mantou.generation

import android.app.Application
import android.net.Uri
import android.view.View
import android.webkit.WebView
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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
import com.hfad.mantou.data.logging.HarnessTraceLogger
import com.hfad.mantou.data.logging.HarnessTraceStatus
import com.hfad.mantou.data.logging.elapsedMillisSince
import com.hfad.mantou.data.logging.record
import com.hfad.mantou.data.preferences.ContextLimitStore
import com.hfad.mantou.data.preferences.HarnessSettingsStore
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
import com.hfad.mantou.utils.harness.AppHarnessOrchestrator
import com.hfad.mantou.utils.harness.GeneratedAppHarnessScripts
import com.hfad.mantou.utils.harness.GeneratedAppWebViewInspector
import com.hfad.mantou.utils.harness.HarnessBuilder
import com.hfad.mantou.utils.harness.HarnessCheckResult
import com.hfad.mantou.utils.harness.HarnessFileTask
import com.hfad.mantou.utils.harness.HarnessFileTool
import com.hfad.mantou.utils.harness.HarnessRunRequest
import com.hfad.mantou.utils.harness.HarnessRunResult
import com.hfad.mantou.utils.harness.HarnessTaskComplexity
import com.hfad.mantou.utils.harness.HarnessToolResult
import com.hfad.mantou.utils.harness.StreamingHarnessModelRepair
import com.hfad.mantou.utils.harness.WebQualityGateContract
import com.hfad.mantou.utils.harness.WebViewHarnessInspectorAdapter
import com.hfad.mantou.utils.harness.withDesignRequirements
import com.hfad.mantou.utils.project.StreamingWebProjectPlanner
import com.hfad.mantou.utils.project.WebAppProjectResolver
import com.hfad.mantou.utils.project.WebAppProjectSnapshot
import com.hfad.mantou.utils.project.WebAppProjectSnapshotKind
import com.hfad.mantou.utils.project.WebAppProjectWorkspace
import com.hfad.mantou.utils.project.WebAppProjectValidator
import com.hfad.mantou.utils.project.WebProjectFileTool
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
import kotlin.math.ceil
import kotlin.math.min

class ChatSessionRuntime private constructor(private val application: Application) : ChatSession {
    private val checkpoints = GenerationCheckpointStore(File(application.filesDir, "generation_checkpoints"))
    private val checkpointRevision = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        @Volatile private var instance: ChatSessionRuntime? = null

        fun get(application: Application): ChatSessionRuntime =
            instance ?: synchronized(this) {
                instance ?: ChatSessionRuntime(application).also { instance = it }
            }
    }

    private val repository = ChatRepository(AppDatabase.getDatabase(application).chatDao())
    private val providerRepository = com.hfad.mantou.data.repository.ProviderRepository(
        AppDatabase.getDatabase(application).providerDao()
    )
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentSessionId = MutableLiveData<Long?>()
    override val currentSessionId: LiveData<Long?> = _currentSessionId

    private val _messages = MutableLiveData<List<ChatMessage>>()
    override val messages: LiveData<List<ChatMessage>> = _messages

    override val allSessions: Flow<List<ChatSessionEntity>> = repository.getAllSessions()

    override val archivedSessions: Flow<List<ChatSessionEntity>> = repository.getArchivedSessions()

    private val _isLoading = MutableLiveData(false)
    override val isLoading: LiveData<Boolean> = _isLoading

    private val _runningSessionIds = MutableLiveData<Set<Long>>(emptySet())
    override val runningSessionIds: LiveData<Set<Long>> = _runningSessionIds

    private val _isGeneratingApp = MutableLiveData(false)
    override val isGeneratingApp: LiveData<Boolean> = _isGeneratingApp

    private val _generateTaskStates = MutableLiveData<Map<Long, GenerateTaskState>>(emptyMap())
    override val generateTaskStates: LiveData<Map<Long, GenerateTaskState>> = _generateTaskStates

    private val _errorMessage = MutableLiveData<String?>()
    override val errorMessage: LiveData<String?> = _errorMessage

    private val _appGenerated = MutableLiveData<String?>()
    override val appGenerated: LiveData<String?> = _appGenerated

    private val _activeModelName = MutableLiveData<String?>(null)
    override val activeModelName: LiveData<String?> = _activeModelName

    private val _noModelConfigured = MutableLiveData(false)
    override val noModelConfigured: LiveData<Boolean> = _noModelConfigured

    private val _currentSessionTokenUsage = MutableLiveData(SessionTokenUsage())
    override val currentSessionTokenUsage: LiveData<SessionTokenUsage> = _currentSessionTokenUsage
    private var currentTokenUsageSessionId: Long? = null

    private var messagesJob: Job? = null
    private val streamingStates = mutableMapOf<Long, StreamingSessionState>()
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
        sessionScope.launch {
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

    override fun refreshActiveModel() {
        sessionScope.launch {
            val config = resolveActiveChatConfig()
            _activeModelName.value = config?.model
        }
    }

    override fun createEmptySession() {
        messagesJob?.cancel()
        sessionScope.launch {
            val sessionId = repository.createSession("新会话")
            _currentSessionId.value = sessionId
            _messages.value = emptyList()
            currentTokenUsageSessionId = sessionId
            _currentSessionTokenUsage.value = SessionTokenUsage()
            loadMessages(sessionId)
            updateSessionLoadingIndicators()
        }
    }

    override fun switchToSession(sessionId: Long) {
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
        messagesJob = sessionScope.launch {
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
        sessionScope.launch(Dispatchers.IO) {
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

    override fun sendMessage(content: String, imagePath: String?, imageUris: List<Uri>?) {
        val sessionId = _currentSessionId.value ?: run {
            createNewSessionAndSendMessage(content, imagePath, imageUris)
            return
        }

        val previous = streamingStates[sessionId]
        val previousJob = previous?.serviceJob ?: previous?.job
        cancelStreaming(sessionId, persistFallback = true)
        val state = StreamingSessionState(sessionId)
        streamingStates[sessionId] = state
        updateSessionLoadingIndicators()

        state.job = sessionScope.launch {
            previousJob?.join()
            _errorMessage.value = null
            state.streamingContent.clear()
            try {
                withContext(Dispatchers.IO) {
                    AgentWorkspace.appendExplicitMemoryIfNeeded(application, content)
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
            ImageUtils.uriToBase64(application, uri)?.let { imageBase64List.add(it) }
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

        val checkpoint = withContext(Dispatchers.IO) {
            checkpoints.read(state.sessionId)?.takeUnless { it.completed }
        }
        if (checkpoint != null) {
            val draft = checkpoint.draftPath?.let(::File)?.takeIf(File::exists)
            if (checkpoint.draftPath != null && draft == null) {
                addFinalAssistantMessage(state, "已保存的草稿目录不存在，无法继续生成。请检查应用数据是否被清理。")
                return
            }
            val requirement = checkpoint.requirement + "\n\n继续完成以上需求。用户补充：\n" + content
            launchAppGenerationService(state, config, requirement, draft)
            return
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
                context = application,
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
        val traceLogger = GenerationTrace.createHarnessTraceLogger(state.sessionId, runId)
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
                context = application,
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
                        val replacement = streamingStates[state.sessionId]
                        if (replacement != null && replacement !== state) return@withContext
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
        state.checkpointRunId = runId
        withContext(NonCancellable + Dispatchers.IO) {
            checkpoints.begin(GenerationCheckpoint(
                sessionId = state.sessionId,
                runId = runId,
                requirement = userMessage,
                draftPath = existingFile?.absolutePath
            ))
        }
        val application = application
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
                "input_sha256" to GenerationTrace.traceSha256(userMessage),
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
                "filtered_sha256" to GenerationTrace.traceSha256(filteredInput.content),
                "notice_count" to filteredInput.notices.size.toString()
            )
        )
        val isModification = existingFile != null
        var inspector: GeneratedAppWebViewInspector? = null
        var inspectorWebView: WebView? = null
        val pendingResponse = StringBuilder()

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
                    withContext(NonCancellable + Dispatchers.IO) {
                        ProjectWorkspaceOperations.prepareExistingWebProject(existingFile).also {
                            checkpoints.attachDraft(state.sessionId, runId, it.snapshot.contentRoot)
                        }
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
            withContext(NonCancellable + Dispatchers.IO) {
                checkpoints.attachDraft(state.sessionId, runId, preparedProject.snapshot.contentRoot)
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
                    ProjectWorkspaceOperations.ensureProjectEntryIdentity(binding, tool)
                }
            }
            val initialProjectFiles = withContext(Dispatchers.IO) {
                ProjectWorkspaceOperations.projectFilePaths(projectFileTool)
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
                    contentRoot = binding.contentRoot,
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
            val preparedPrompt = try {
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
            // A saved draft represents the original task. A published project's full plan
            // describes the app, so it should not inflate the scope of a follow-up edit.
            val taskPlan = binding.manifest.takeIf { !isModification || existingFile.isDirectory }
            val complexity = HarnessTaskComplexity.assess(taskPlan, preparedPrompt.relevantTools)
            val validation = HarnessSettingsStore.validation(application)
            val scopeSummary = "${complexity.level.label} · ${complexity.evidence.joinToString("、")}"
            val projectSystemPrompt = "# 本轮实现重点\n${complexity.level.guidance}\n\n" + preparedPrompt.text
            traceLogger.record(
                runId = runId,
                component = "ORCHESTRATOR",
                operation = "assess_task_scope",
                status = HarnessTraceStatus.SUCCEEDED,
                message = "任务范围已评估",
                iteration = 0,
                details = mapOf(
                    "complexity" to complexity.level.name,
                    "scope" to scopeSummary,
                    "validation_profile" to validation.name
                )
            )
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
                    "prompt_sha256" to GenerationTrace.traceSha256(projectSystemPrompt)
                )
            )
            var lastPartialSaveAt = 0L
            val modelRepair = StreamingHarnessModelRepair(
                config = config,
                maxTokens = AppGenerator.resolveProjectFileOutputLimit(
                    ContextLimitStore.getTokenLimit(application)
                ),
                onThinking = { delta ->
                    withContext(Dispatchers.Main.immediate) {
                        state.thinkingContent.append(delta)
                        updateStreamingThinking(state, state.thinkingContent)
                    }
                },
                onProgress = { progress ->
                    when (progress.phase) {
                        com.hfad.mantou.utils.harness.HarnessModelStreamPhase.STARTED -> pendingResponse.clear()
                        com.hfad.mantou.utils.harness.HarnessModelStreamPhase.RECEIVING -> pendingResponse.append(progress.delta)
                        else -> Unit
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastPartialSaveAt >= 1_000L ||
                        progress.phase == com.hfad.mantou.utils.harness.HarnessModelStreamPhase.COMPLETED ||
                        progress.phase == com.hfad.mantou.utils.harness.HarnessModelStreamPhase.FAILED
                    ) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            checkpoints.savePartial(state.sessionId, runId, pendingResponse.toString())
                        }
                        lastPartialSaveAt = now
                    }
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
                    GenerationTrace.recordWebInspectionEvent(
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
                limits = complexity.limits(validation),
                eventLogger = { eventRunId, event ->
                    GenerationTrace.logHarnessEvent(state.sessionId, eventRunId, event)
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
                        selfTestScript = if (validation.shallowAcceptance) {
                            GeneratedAppHarnessScripts.basicScenarios(binding.manifest.appSpec?.acceptanceContract)
                        } else {
                            GeneratedAppHarnessScripts.selfTest
                        },
                        testSuiteScript = GeneratedAppHarnessScripts.testSuite,
                        acceptanceContract = binding.manifest.appSpec?.acceptanceContract,
                        acceptanceRequired = binding.manifest.appSpec != null,
                        qualityGateContract = validation.qualityGate(binding.manifest.appSpec?.design?.let { design ->
                            WebQualityGateContract().withDesignRequirements(
                                viewportWidths = design.viewportWidths,
                                minTouchTargetCssPixels = design.minTouchTargetPx
                            )
                        } ?: WebQualityGateContract()),
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
                        shallowAcceptance = validation.shallowAcceptance,
                        metadata = mapOf(
                            "projectId" to binding.manifest.projectId,
                            "task_complexity" to complexity.level.name,
                            "task_scope_summary" to scopeSummary,
                            "validation_profile" to validation.name
                        )
                    )
                ) { event ->
                    val projectFiles = withContext(Dispatchers.IO) {
                        ProjectWorkspaceOperations.projectFilePaths(projectFileTool)
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
                    val savedState = withContext(Dispatchers.Main.immediate) {
                        _generateTaskStates.value.orEmpty()[state.sessionId]
                    }
                    if (savedState != null) {
                        val revision = checkpointRevision.incrementAndGet()
                        withContext(Dispatchers.IO) {
                            checkpoints.saveState(state.sessionId, runId, savedState, revision)
                        }
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
                        withContext(NonCancellable + Dispatchers.IO) {
                            ProjectWorkspaceOperations.synchronizeProjectManifest(binding, projectFileTool)
                            ProjectWorkspaceOperations.ensureProjectEntryIdentity(binding, projectFileTool)
                            binding.workspace.publishDraft(
                                projectRoot = binding.projectRoot,
                                draftVersion = binding.draftVersion,
                                manifest = binding.manifest
                            ).also { release ->
                                repository.markSessionAsGenerate(state.sessionId, release.entryFile.absolutePath)
                            }
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
                        result = result
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
                _generateTaskStates.value.orEmpty()[state.sessionId]?.let { savedState ->
                    val revision = checkpointRevision.incrementAndGet()
                    val saved = withContext(Dispatchers.IO) {
                        runCatching {
                            checkpoints.saveState(state.sessionId, runId, savedState, revision)
                            checkpoints.savePartial(state.sessionId, runId, pendingResponse.toString())
                        }
                    }
                    saved.onFailure { _errorMessage.value = "生成进度保存失败：${it.message}" }
                }
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
            maxTokens = WEB_PROJECT_PLAN_MAX_TOKENS,
            onThinking = { delta ->
                withContext(Dispatchers.Main.immediate) {
                    state.thinkingContent.append(delta)
                    updateStreamingThinking(state, state.thinkingContent)
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
            onRetry = { message ->
                withContext(Dispatchers.Main.immediate) {
                    state.thinkingContent.clear()
                    updateStreamingThinking(state, message, force = true)
                    updateHarnessStage(
                        state = state,
                        phase = GenerateTaskState.Phase.PROMPTING,
                        stage = GenerateTaskState.Stage.PROMPT,
                        outcome = GenerateTaskState.Outcome.RETRYING,
                        message = message
                    )
                }
            },
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
            withContext(NonCancellable + Dispatchers.IO) {
                val projectRoot = nextWebProjectRoot(
                    displayName = planningResult.manifest.displayName,
                    projectId = planningResult.manifest.projectId
                )
                workspace.create(projectRoot, planningResult.manifest).also { draft ->
                    checkpoints.attachDraft(state.sessionId, runId, draft.contentRoot)
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
                "project_plan_sha256" to GenerationTrace.traceSha256(planningResult.projectPlanJson)
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
                contentRoot = snapshot.contentRoot,
                projectFiles = listOf(PROJECT_PLAN_FILE_NAME),
                isModification = false
            )
        }
        return PreparedWebProject(workspace, snapshot)
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
                        metadata = ProjectWorkspaceOperations.toolPolicyMetadata(
                            tool = call.name,
                            path = requestedPath,
                            policyCode = "PROJECT_PLAN_DELETE_FORBIDDEN"
                        )
                    )
                }

                call.name == WebProjectFileTool.TOOL_WRITE_FILE &&
                    requestedPath == PROJECT_PLAN_FILE_NAME -> {
                    ProjectWorkspaceOperations.updateVisibleProjectPlan(binding, projectFileTool, call.arguments)
                }

                call.name == WebProjectFileTool.TOOL_WRITE_FILE &&
                    requestedPath != null && requestedPath !in binding.declaredPaths -> {
                    com.hfad.mantou.utils.project.WebProjectToolExecutionResult(
                        success = false,
                        output = "写入新文件前必须先把路径加入 project.json：$requestedPath",
                        diagnostics = listOf("PROJECT_FILE_NOT_DECLARED: $requestedPath"),
                        metadata = ProjectWorkspaceOperations.toolPolicyMetadata(
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
                        metadata = ProjectWorkspaceOperations.toolPolicyMetadata(
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
                val projectFiles = ProjectWorkspaceOperations.projectFilePaths(projectFileTool)
                val activePath = result.changedFiles.last()
                val activeContent = runCatching { projectFileTool.read(activePath).content }
                    .getOrDefault("")
                withContext(Dispatchers.Main.immediate) {
                    updateProjectFileState(
                        state = state,
                        relativePath = activePath,
                        content = activeContent,
                        entryPath = binding.entryFile.absolutePath,
                        contentRoot = binding.contentRoot,
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

    private suspend fun completeGeneratedWebProject(
        state: StreamingSessionState,
        release: WebAppProjectSnapshot,
        isModification: Boolean,
        result: HarnessRunResult.Delivered
    ) {
        val testSummary = result.testCounts?.summary()
        val releaseSummary = when {
            testSummary != null -> "浅验收通过，已发布。$testSummary"
            result.selfTestBypassed -> "多文件项目已构建、运行检查和测试并发布（自测非阻塞放行）"
            else -> "多文件项目已构建、测试并发布"
        }
        val projectFiles = withContext(Dispatchers.IO) {
            ProjectWorkspaceOperations.projectFilePaths(WebProjectFileTool(release.contentRoot))
        }
        val entryCode = withContext(Dispatchers.IO) { release.entryFile.readText() }
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
                    projectContentRoot = release.contentRoot.absolutePath,
                    status = releaseSummary,
                    isModification = isModification,
                    harnessIteration = maxOf(current?.harnessIteration ?: 0, result.iterations),
                    diagnostics = emptyList(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            removeStreamingPlaceholder(state)
            state.thinkingContent.clear()
        }
        addFinalAssistantMessage(
            state = state,
            content = if (testSummary != null) {
                "$releaseSummary。点击下方预览 👇"
            } else if (isModification) {
                if (result.selfTestBypassed) {
                    "项目修改已完成，并通过构建、WebView 检查和测试集；自测非阻塞放行，点击下方查看 👇"
                } else {
                    "项目修改已完成，并通过构建、WebView 检查、自测和测试集，点击下方查看 👇"
                }
            } else {
                if (result.selfTestBypassed) {
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
        val metrics = application.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        webView.layout(0, 0, width, height)
    }

    private fun nextWebProjectRoot(displayName: String, projectId: String): File {
        val generatedApps = File(application.filesDir, AgentWorkspace.WEB_DIR)
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

    private suspend fun normalChatFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        imageBase64List: List<String>
    ) {
        val sessionId = state.sessionId
        val historyMessages = repository.getMessagesBySessionIdOnce(sessionId)
        val systemPrompt = withContext(Dispatchers.IO) {
            AgentWorkspace.buildSystemPrompt(application)
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
                                updateStreamingThinking(state, state.thinkingContent)
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
            context = application,
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

    private fun currentHarnessIteration(state: StreamingSessionState): Int {
        return _generateTaskStates.value.orEmpty()[state.sessionId]?.harnessIteration ?: 0
    }

    private fun stopAppGenerationProgressHeartbeat(state: StreamingSessionState) {
        state.appGenerationProgressJob?.cancel()
        state.appGenerationProgressJob = null
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

    private fun persistGenerationState(taskState: GenerateTaskState) {
        val runId = streamingStates[taskState.sessionId]?.checkpointRunId ?: return
        val revision = checkpointRevision.incrementAndGet()
        sessionScope.launch(Dispatchers.IO) {
            runCatching { checkpoints.saveState(taskState.sessionId, runId, taskState, revision) }
                .onFailure {
                    withContext(Dispatchers.Main.immediate) {
                        _errorMessage.value = "生成进度保存失败：${it.message}"
                    }
                }
        }
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
        persistGenerationState(storedState)
        reportHarnessProgress(storedState)
    }

    private fun replaceGenerateTaskState(taskState: GenerateTaskState) {
        val states = _generateTaskStates.value.orEmpty().toMutableMap()
        states[taskState.sessionId] = taskState
        _generateTaskStates.value = states
        persistGenerationState(taskState)
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
                GenerateTaskState.Phase.PAUSED -> 0
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
        contentRoot: File,
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
                projectContentRoot = contentRoot.absolutePath,
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
        sessionScope.launch {
            val restored = withContext(Dispatchers.IO) {
                val session = repository.getSessionById(sessionId) ?: return@withContext null
                checkpoints.read(sessionId)?.let { checkpoint ->
                    return@withContext checkpoint.restoredState()
                }
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
                    val projectFiles = when (restoredProject?.kind) {
                        null -> emptyList()
                        WebAppProjectSnapshotKind.LEGACY -> restoredProject.manifest.files.map { it.path }
                        else -> ProjectWorkspaceOperations.projectFilePaths(WebProjectFileTool(restoredProject.contentRoot))
                    }
                    GenerateTaskState(
                        sessionId = sessionId,
                        phase = GenerateTaskState.Phase.COMPLETED,
                        code = file.readText(),
                        filePath = file.absolutePath,
                        activeFilePath = restoredProject?.manifest?.entryPoint ?: file.name,
                        projectFiles = projectFiles,
                        projectContentRoot = restoredProject?.contentRoot?.absolutePath,
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
        thinking: CharSequence,
        force: Boolean = false
    ) {
        if (!force && !shouldUpdateStreamingThinking(state)) return
        val placeholder = state.placeholder ?: return
        if (placeholder.isStreaming) {
            state.placeholder = placeholder.copy(thinking = thinking.toString())
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

    override fun stopStreaming() {
        _currentSessionId.value?.let { cancelStreaming(it, persistFallback = true) }
    }

    private fun cancelStreaming(sessionId: Long, persistFallback: Boolean = false) {
        val state = streamingStates.remove(sessionId)
        if (state == null) {
            serviceProgressStates.values
                .firstOrNull { it.sessionId == sessionId && it.isRunning }
                ?.let { progress ->
                    HarnessForegroundServiceController.cancel(
                        application,
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
                application,
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
        sessionScope.launch {
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
        val ctx = application
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

    override fun deleteSession(sessionId: Long) {
        val execution = streamingStates[sessionId]
        val job = execution?.serviceJob ?: execution?.job
        cancelStreaming(sessionId)
        sessionScope.launch {
            job?.join()
            repository.deleteSession(sessionId)
            withContext(Dispatchers.IO) { checkpoints.remove(sessionId) }
            _generateTaskStates.value = _generateTaskStates.value.orEmpty() - sessionId
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = null
                _messages.value = emptyList()
                currentTokenUsageSessionId = null
                _currentSessionTokenUsage.value = SessionTokenUsage()
            }
        }
    }

    override fun setSessionArchived(sessionId: Long, isArchived: Boolean) {
        sessionScope.launch {
            repository.setSessionArchived(sessionId, isArchived)
        }
    }

    override fun deleteMessage(messageId: Long) {
        if (messageId <= 0) return
        sessionScope.launch {
            repository.deleteMessage(messageId)
        }
    }

    override fun updateMessageContent(messageId: Long, content: String) {
        if (messageId <= 0 || content.isBlank()) return
        sessionScope.launch {
            repository.updateMessageContent(messageId, content)
        }
    }

    override fun editUserMessageAndRegenerate(messageId: Long, content: String) {
        if (messageId <= 0 || content.isBlank()) return
        sessionScope.launch {
            val originalMessage = repository.getMessageById(messageId) ?: return@launch
            if (originalMessage.role != ChatMessage.ROLE_USER) return@launch

            val previous = streamingStates[originalMessage.sessionId]
            val previousJob = previous?.serviceJob ?: previous?.job
            cancelStreaming(originalMessage.sessionId, persistFallback = true)
            previousJob?.join()
            val editedMessage = repository.updateMessageContentAndDeleteAfter(messageId, content)
                ?: return@launch
            if (repository.getMessageCount(editedMessage.sessionId) == 1) {
                repository.updateSessionTitle(editedMessage.sessionId, content.ifEmpty { "[图片]" })
                if (repository.getSessionById(editedMessage.sessionId)?.isGenerateTask == true) {
                    repository.clearGeneratedApp(editedMessage.sessionId)
                    withContext(Dispatchers.IO) { checkpoints.remove(editedMessage.sessionId) }
                }
            }

            val state = StreamingSessionState(editedMessage.sessionId)
            streamingStates[editedMessage.sessionId] = state
            updateSessionLoadingIndicators()

            state.job = sessionScope.launch {
                _errorMessage.value = null
                state.streamingContent.clear()
                try {
                    withContext(Dispatchers.IO) {
                        AgentWorkspace.appendExplicitMemoryIfNeeded(application, content)
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

    override fun deleteAllSessions() {
        val jobs = streamingStates.values.mapNotNull { it.serviceJob ?: it.job }
        cancelAllStreaming()
        sessionScope.launch {
            jobs.forEach { it.join() }
            repository.deleteAllSessions()
            withContext(Dispatchers.IO) { checkpoints.clear() }
            _currentSessionId.value = null
            _messages.value = emptyList()
            currentTokenUsageSessionId = null
            _currentSessionTokenUsage.value = SessionTokenUsage()
            _generateTaskStates.value = emptyMap()
        }
    }

    override fun clearCurrentSession() {
        _currentSessionId.value?.let { cancelStreaming(it, persistFallback = true) }
        _currentSessionId.value = null
        _messages.value = emptyList()
        currentTokenUsageSessionId = null
        _currentSessionTokenUsage.value = SessionTokenUsage()
        updateSessionLoadingIndicators()
    }

    override fun clearError() {
        _errorMessage.value = null
    }

    override fun clearAppGenerated() {
        _appGenerated.value = null
    }

    override fun clearNoModelConfigured() {
        _noModelConfigured.value = false
    }

    fun detachUi() {
        cancelAllStreaming(persistFallback = true, includeServiceOwned = false)
    }
}

private const val HARNESS_NOTIFICATION_UPDATE_INTERVAL_MS = 750L
private const val PROJECT_PLAN_FILE_NAME = "project.json"
private const val WEB_PROJECT_PLAN_MAX_TOKENS = 8_000
private const val WEB_PROJECT_DIRECTORY_NAME_MAX_CHARS = 64
private const val WEB_PROJECT_DIRECTORY_ID_CHARS = 8
private data class StreamingSessionState(
    val sessionId: Long,
    var job: Job? = null,
    var serviceJob: Job? = null,
    var serviceRunId: String? = null,
    var checkpointRunId: String? = null,
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
