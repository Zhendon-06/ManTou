package com.hfad.mantou.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HarnessForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundNotificationId: Int? = null
    private var stateCollector: Job? = null
    private var pendingStopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId: Int = 0
    private val visibleNotificationIds = mutableSetOf<Int>()

    override fun onCreate() {
        super.onCreate()
        HarnessNotification.ensureChannel(this)
        stateCollector = serviceScope.launch {
            HarnessForegroundServiceRuntime.states.collectLatest { states ->
                pendingStopJob?.cancel()
                pendingStopJob = null
                val foregroundProgress = states.values
                    .filter(HarnessProgress::isRunning)
                    .maxByOrNull(HarnessProgress::updatedAt)
                    ?: states.values.maxByOrNull(HarnessProgress::updatedAt)
                if (foregroundProgress == null) {
                    releaseWakeLock()
                    scheduleStopWhenIdle()
                    return@collectLatest
                }
                showNotifications(states, foregroundProgress)
                if (states.values.any(HarnessProgress::isRunning)) {
                    acquireWakeLock()
                } else {
                    releaseWakeLock()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = maxOf(lastStartId, startId)
        pendingStopJob?.cancel()
        pendingStopJob = null
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_UPDATE -> handleUpdate(intent)
            ACTION_FINISH -> handleFinish(intent)
            ACTION_CANCEL -> {
                intent.getStringExtra(EXTRA_RUN_ID)?.let {
                    HarnessForegroundServiceRuntime.cancel(it, "已从通知停止 Harness")
                }
            }
            ACTION_STOP -> {
                HarnessForegroundServiceRuntime.stopAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundNotificationId = null
                cancelVisibleNotifications()
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        HarnessForegroundServiceRuntime.stopAll("后台 Harness 已达到系统运行时限")
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundNotificationId = null
        cancelVisibleNotifications()
        stopSelfResult(startId)
    }

    override fun onDestroy() {
        pendingStopJob?.cancel()
        stateCollector?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        if (HarnessForegroundServiceRuntime.states.value.values.any(HarnessProgress::isRunning)) {
            HarnessForegroundServiceRuntime.stopAll("前台 Harness 服务已停止")
        }
        foregroundNotificationId = null
        cancelVisibleNotifications()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleStart(intent: Intent) {
        val runId = intent.getStringExtra(EXTRA_RUN_ID).orEmpty()
        if (runId.isBlank()) return
        val state = HarnessForegroundServiceRuntime.states.value[runId]
            ?: HarnessForegroundServiceRuntime.attach(
                HarnessServiceRequest(
                    runId = runId,
                    sessionId = intent.getLongExtra(EXTRA_SESSION_ID, Long.MIN_VALUE)
                        .takeUnless { it == Long.MIN_VALUE },
                    title = intent.getStringExtra(EXTRA_TITLE) ?: "ManTou Harness",
                    initialMessage = intent.getStringExtra(EXTRA_MESSAGE) ?: "正在运行 Harness",
                    initialStage = intent.getStringExtra(EXTRA_STAGE) ?: "准备中"
                )
            )
        ensureForeground(state)
    }

    private fun handleUpdate(intent: Intent) {
        val runId = intent.getStringExtra(EXTRA_RUN_ID).orEmpty()
        if (runId.isBlank()) return
        HarnessForegroundServiceRuntime.update(
            HarnessProgress(
                runId = runId,
                sessionId = intent.getLongExtra(EXTRA_SESSION_ID, Long.MIN_VALUE)
                    .takeUnless { it == Long.MIN_VALUE },
                title = intent.getStringExtra(EXTRA_TITLE) ?: "ManTou Harness",
                message = intent.getStringExtra(EXTRA_MESSAGE) ?: "正在运行 Harness",
                stage = intent.getStringExtra(EXTRA_STAGE),
                iteration = intent.getIntExtra(EXTRA_ITERATION, 0),
                progress = intent.getIntExtra(EXTRA_PROGRESS, -1).takeUnless { it < 0 },
                diagnostics = intent.getStringArrayListExtra(EXTRA_DIAGNOSTICS).orEmpty()
            )
        )
        handleStart(intent)
    }

    private fun handleFinish(intent: Intent) {
        val runId = intent.getStringExtra(EXTRA_RUN_ID).orEmpty()
        if (runId.isBlank()) return
        val success = intent.getBooleanExtra(EXTRA_SUCCESS, false)
        HarnessForegroundServiceRuntime.update(
            HarnessProgress(
                runId = runId,
                sessionId = intent.getLongExtra(EXTRA_SESSION_ID, Long.MIN_VALUE)
                    .takeUnless { it == Long.MIN_VALUE },
                title = intent.getStringExtra(EXTRA_TITLE) ?: "ManTou Harness",
                message = intent.getStringExtra(EXTRA_MESSAGE)
                    ?: if (success) "Harness 已完成" else "Harness 执行失败",
                status = if (success) {
                    HarnessProgressStatus.SUCCEEDED
                } else {
                    HarnessProgressStatus.FAILED
                },
                diagnostics = intent.getStringArrayListExtra(EXTRA_DIAGNOSTICS).orEmpty()
            )
        )
    }

    private fun showNotifications(
        states: Map<String, HarnessProgress>,
        foregroundProgress: HarnessProgress
    ) {
        ensureForeground(foregroundProgress)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        states.values.forEach { progress ->
            val notificationId = HarnessNotification.notificationId(progress.runId)
            visibleNotificationIds += notificationId
            if (notificationId != foregroundNotificationId) {
                manager.notify(notificationId, HarnessNotification.build(this, progress))
            }
        }
    }

    private fun ensureForeground(progress: HarnessProgress) {
        val notificationId = HarnessNotification.notificationId(progress.runId)
        val notification = HarnessNotification.build(this, progress)
        visibleNotificationIds += notificationId
        if (foregroundNotificationId == notificationId) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(notificationId, notification)
            return
        }
        startForegroundCompat(notificationId, notification)
        foregroundNotificationId = notificationId
    }

    private fun scheduleStopWhenIdle() {
        if (pendingStopJob?.isActive == true) return
        val stopStartId = lastStartId
        pendingStopJob = serviceScope.launch {
            delay(SERVICE_STOP_GRACE_MS)
            if (HarnessForegroundServiceRuntime.states.value.isNotEmpty()) return@launch
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundNotificationId = null
            cancelVisibleNotifications()
            stopSelfResult(stopStartId)
        }
    }

    private fun cancelVisibleNotifications() {
        if (visibleNotificationIds.isEmpty()) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        visibleNotificationIds.forEach(manager::cancel)
        visibleNotificationIds.clear()
    }

    private fun startForegroundCompat(
        notificationId: Int,
        notification: android.app.Notification
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:HarnessForegroundService")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.hfad.mantou.service.action.START"
        const val ACTION_UPDATE = "com.hfad.mantou.service.action.UPDATE"
        const val ACTION_FINISH = "com.hfad.mantou.service.action.FINISH"
        const val ACTION_CANCEL = "com.hfad.mantou.service.action.CANCEL"
        const val ACTION_STOP = "com.hfad.mantou.service.action.STOP"

        const val EXTRA_RUN_ID = "com.hfad.mantou.service.extra.RUN_ID"
        const val EXTRA_SESSION_ID = "com.hfad.mantou.service.extra.SESSION_ID"
        const val EXTRA_TITLE = "com.hfad.mantou.service.extra.TITLE"
        const val EXTRA_MESSAGE = "com.hfad.mantou.service.extra.MESSAGE"
        const val EXTRA_STAGE = "com.hfad.mantou.service.extra.STAGE"
        const val EXTRA_ITERATION = "com.hfad.mantou.service.extra.ITERATION"
        const val EXTRA_PROGRESS = "com.hfad.mantou.service.extra.PROGRESS"
        const val EXTRA_DIAGNOSTICS = "com.hfad.mantou.service.extra.DIAGNOSTICS"
        const val EXTRA_SUCCESS = "com.hfad.mantou.service.extra.SUCCESS"

        private const val SERVICE_STOP_GRACE_MS = 250L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1_000L
    }
}

object HarnessForegroundServiceController {

    fun attach(context: android.content.Context, request: HarnessServiceRequest) {
        HarnessForegroundServiceRuntime.attach(request)
        startService(context, action = HarnessForegroundService.ACTION_START, request = request)
    }

    fun launch(
        context: android.content.Context,
        request: HarnessServiceRequest,
        worker: suspend (HarnessProgressReporter) -> Unit
    ): Job {
        val appContext = context.applicationContext
        HarnessForegroundServiceRuntime.attach(request)
        try {
            startService(appContext, HarnessForegroundService.ACTION_START, request)
        } catch (error: Throwable) {
            HarnessForegroundServiceRuntime.remove(request.runId)
            throw error
        }
        return HarnessForegroundServiceRuntime.launch(request, worker)
    }

    fun update(context: android.content.Context, progress: HarnessProgress) {
        val hadState = HarnessForegroundServiceRuntime.states.value.containsKey(progress.runId)
        HarnessForegroundServiceRuntime.update(progress)
        if (!hadState) {
            startService(
                context.applicationContext,
                HarnessForegroundService.ACTION_START,
                HarnessServiceRequest(
                    runId = progress.runId,
                    sessionId = progress.sessionId,
                    title = progress.title,
                    initialMessage = progress.message,
                    initialStage = progress.stage ?: "运行中"
                )
            )
        }
    }

    fun finish(
        context: android.content.Context,
        runId: String,
        success: Boolean,
        message: String,
        sessionId: Long? = null,
        title: String = "ManTou Harness",
        diagnostics: List<String> = emptyList()
    ) {
        val hadState = HarnessForegroundServiceRuntime.states.value.containsKey(runId)
        HarnessForegroundServiceRuntime.update(
            HarnessProgress(
                runId = runId,
                sessionId = sessionId,
                title = title,
                message = message,
                status = if (success) HarnessProgressStatus.SUCCEEDED else HarnessProgressStatus.FAILED,
                diagnostics = diagnostics
            )
        )
        if (!hadState) {
            startService(context.applicationContext, HarnessForegroundService.ACTION_FINISH,
                HarnessServiceRequest(runId, sessionId, title, message))
        }
    }

    fun cancel(context: android.content.Context, runId: String, message: String = "Harness 已取消") {
        HarnessForegroundServiceRuntime.cancel(runId, message)
        if (HarnessForegroundServiceRuntime.states.value.isEmpty()) {
            context.stopService(Intent(context, HarnessForegroundService::class.java))
        }
    }

    fun stop(context: android.content.Context) {
        HarnessForegroundServiceRuntime.stopAll()
        val intent = Intent(context.applicationContext, HarnessForegroundService::class.java).apply {
            action = HarnessForegroundService.ACTION_STOP
        }
        runCatching { context.stopService(intent) }
    }

    val states = HarnessForegroundServiceRuntime.states

    private fun startService(
        context: android.content.Context,
        action: String,
        request: HarnessServiceRequest
    ) {
        val intent = Intent(context, HarnessForegroundService::class.java).apply {
            this.action = action
            putExtra(HarnessForegroundService.EXTRA_RUN_ID, request.runId)
            request.sessionId?.let { putExtra(HarnessForegroundService.EXTRA_SESSION_ID, it) }
            putExtra(HarnessForegroundService.EXTRA_TITLE, request.title)
            putExtra(HarnessForegroundService.EXTRA_MESSAGE, request.initialMessage)
            putExtra(HarnessForegroundService.EXTRA_STAGE, request.initialStage)
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
