package com.hfad.mantou.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object HarnessForegroundServiceRuntime {

    private const val TERMINAL_STATE_RETENTION_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private val _states = MutableStateFlow<Map<String, HarnessProgress>>(emptyMap())

    val states: StateFlow<Map<String, HarnessProgress>> = _states.asStateFlow()

    fun attach(request: HarnessServiceRequest): HarnessProgress {
        val initial = HarnessProgress.initial(request)
        synchronized(lock) {
            jobs.remove(request.runId)?.cancel(
                CancellationException("被新的 Harness 任务替换")
            )
            _states.value = _states.value + (request.runId to initial)
        }
        return initial
    }

    fun launch(
        request: HarnessServiceRequest,
        worker: suspend (HarnessProgressReporter) -> Unit
    ): Job {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val reporter = HarnessProgressReporter(
                runId = request.runId,
                sessionId = request.sessionId,
                title = request.title
            )
            try {
                worker(reporter)
                if (isCurrent(request.runId, currentCoroutineContext()[Job])) {
                    val current = states.value[request.runId]
                    if (current?.status == HarnessProgressStatus.RUNNING) {
                        reporter.succeed()
                    }
                }
            } catch (error: CancellationException) {
                if (isCurrent(request.runId, currentCoroutineContext()[Job])) {
                    reporter.cancel()
                }
                throw error
            } catch (error: Throwable) {
                if (isCurrent(request.runId, currentCoroutineContext()[Job])) {
                    reporter.fail(
                        message = error.message ?: "Harness 执行失败",
                        diagnostics = listOf(error.stackTraceToString().take(MAX_DIAGNOSTIC_CHARS))
                    )
                }
            } finally {
                if (isCurrent(request.runId, currentCoroutineContext()[Job])) {
                    synchronized(lock) {
                        jobs.remove(request.runId)
                    }
                    retainTerminalState(request.runId)
                }
            }
        }
        synchronized(lock) {
            jobs.remove(request.runId)?.cancel(
                CancellationException("被新的 Harness 任务替换")
            )
            _states.value = _states.value + (request.runId to HarnessProgress.initial(request))
            jobs[request.runId] = job
        }
        job.start()
        return job
    }

    fun update(progress: HarnessProgress) {
        put(progress.copy(
            progress = progress.progress?.coerceIn(0, 100),
            message = progress.message.take(MAX_MESSAGE_CHARS),
            diagnostics = progress.diagnostics.take(MAX_DIAGNOSTICS)
                .map { it.take(MAX_DIAGNOSTIC_CHARS) },
            updatedAt = System.currentTimeMillis()
        ))
        if (progress.isTerminal) {
            retainTerminalState(progress.runId)
        }
    }

    fun cancel(runId: String, message: String = "Harness 已取消") {
        val job = synchronized(lock) { jobs[runId] }
        if (job != null && job.isActive) {
            job.cancel(CancellationException(message))
        } else {
            val current = states.value[runId]
            if (current != null && current.status == HarnessProgressStatus.RUNNING) {
                update(current.copy(
                    message = message,
                    status = HarnessProgressStatus.CANCELLED
                ))
            }
        }
    }

    fun remove(runId: String) {
        synchronized(lock) {
            jobs.remove(runId)?.cancel()
            _states.value = _states.value - runId
        }
    }

    fun stopAll(message: String = "Harness 已停止") {
        val jobsToCancel: List<Job>
        val terminalRunIds: List<String>
        synchronized(lock) {
            jobsToCancel = jobs.values.toList()
            jobs.clear()
            val now = System.currentTimeMillis()
            _states.value = _states.value.mapValues { (_, progress) ->
                if (progress.isRunning) {
                    progress.copy(
                        message = message,
                        status = HarnessProgressStatus.CANCELLED,
                        updatedAt = now
                    )
                } else {
                    progress
                }
            }
            terminalRunIds = _states.value.keys.toList()
        }
        jobsToCancel.forEach { it.cancel(CancellationException(message)) }
        terminalRunIds.forEach(::retainTerminalState)
    }

    fun job(runId: String): Job? = synchronized(lock) { jobs[runId] }

    private fun put(progress: HarnessProgress) {
        synchronized(lock) {
            _states.value = _states.value + (progress.runId to progress)
        }
    }

    private fun isCurrent(runId: String, candidate: Job?): Boolean {
        return candidate != null && synchronized(lock) { jobs[runId] === candidate }
    }

    private fun retainTerminalState(runId: String) {
        scope.launch {
            delay(TERMINAL_STATE_RETENTION_MS)
            if (!isActive) return@launch
            val current = states.value[runId] ?: return@launch
            if (current.isTerminal) {
                synchronized(lock) {
                    if (_states.value[runId] == current) {
                        _states.value = _states.value - runId
                    }
                }
            }
        }
    }

    private const val MAX_MESSAGE_CHARS = 160
    private const val MAX_DIAGNOSTICS = 8
    private const val MAX_DIAGNOSTIC_CHARS = 2_000
}
