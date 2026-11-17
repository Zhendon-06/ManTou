package com.hfad.mantou.tool.impl

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.CountDownTimer
import android.os.SystemClock
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.hfad.mantou.tool.impl.WebAppDevicePage.Request
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

internal class WebAppRecording(
    private val activity: FragmentActivity,
    caller: ActivityResultCaller,
    private val owner: LifecycleOwner
) {
    private var requested: Request? = null
    private var askingPermission: Request? = null
    private var dialog: AlertDialog? = null
    private var recording: Recording? = null

    val isRecording: Boolean get() = recording != null

    private val permission = caller.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = askingPermission ?: return@registerForActivityResult
        askingPermission = null
        if (requested !== request) return@registerForActivityResult
        if (granted) {
            owner.lifecycleScope.launch { owner.lifecycle.withResumed { start(request) } }
        } else {
            requested = null
            request.fail("麦克风权限未授予")
        }
    }

    fun request(request: Request) {
        if (requested != null || askingPermission != null) {
            request.fail("请先结束当前录音操作")
            return
        }
        requested = request
        dialog = AlertDialog.Builder(activity)
            .setTitle("开始录音？")
            .setMessage("${request.page.displayName} 想使用麦克风。录音最多 5 分钟，可随时保存或取消。")
            .setPositiveButton("开始录音") { _, _ ->
                dialog = null
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    start(request)
                } else {
                    askingPermission = request
                    runCatching { permission.launch(Manifest.permission.RECORD_AUDIO) }.onFailure {
                        askingPermission = null
                        requested = null
                        request.fail("无法请求麦克风权限")
                    }
                }
            }
            .setNegativeButton("取消") { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .show()
    }

    fun interrupt(page: WebAppDevicePage? = null, keepPermissionRequest: Boolean = false) {
        val request = requested ?: return
        if (page != null && request.page !== page) return
        if (keepPermissionRequest && (askingPermission === request || dialog == null && recording == null)) return
        cancel()
    }

    private fun start(request: Request) {
        if (requested !== request) return
        if (!request.canStart) {
            cancel()
            return
        }
        var encoder: MediaRecorder? = null
        var temp: File? = null
        try {
            val file = File.createTempFile("web-record-", ".m4a", activity.cacheDir).also { temp = it }
            val recorder = createRecorder().also { encoder = it }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(64_000)
                setOutputFile(file.absolutePath)
                setOnErrorListener { _, _, _ ->
                    if (recording?.encoder === recorder) finish(save = false, error = "录音中断，请重试")
                }
                prepare()
                start()
            }
            val active = Recording(recorder, file, SystemClock.elapsedRealtime())
            recording = active
            dialog = AlertDialog.Builder(activity)
                .setTitle("正在录音")
                .setMessage("00:00 / 05:00")
                .setPositiveButton("停止并保存") { _, _ -> finish(save = true) }
                .setNegativeButton("取消") { _, _ -> cancel() }
                .setOnCancelListener { cancel() }
                .show()
            active.timer = object : CountDownTimer(MAX_DURATION, 1_000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val seconds = (SystemClock.elapsedRealtime() - active.startedAt) / 1_000L
                    dialog?.setMessage(String.format(Locale.ROOT, "%02d:%02d / 05:00", seconds / 60, seconds % 60))
                }

                override fun onFinish() = finish(save = true)
            }.start()
        } catch (_: Exception) {
            runCatching { encoder?.release() }
            temp?.delete()
            requested = null
            recording = null
            dialog?.dismiss()
            dialog = null
            request.fail("无法开始录音，请检查麦克风权限和占用状态")
        }
    }

    private fun cancel() = finish(save = false)

    private fun finish(save: Boolean, error: String? = null) {
        val request = requested ?: return
        requested = null
        val active = recording
        recording = null
        dialog?.dismiss()
        dialog = null
        active?.timer?.cancel()

        if (active == null) {
            request.succeed("cancelled" to true)
            return
        }
        val stopped = runCatching { active.encoder.stop() }.isSuccess
        runCatching { active.encoder.release() }
        try {
            when {
                error != null -> request.fail(error)
                !save -> request.succeed("cancelled" to true)
                !stopped || active.file.length() == 0L -> request.fail("录音太短或未能保存，请重试")
                request.isCurrent -> {
                    val target = request.page.media.newFile("m4a")
                    check(active.file.renameTo(target))
                    val result = request.page.media.describe(target)
                        .put("durationMs", SystemClock.elapsedRealtime() - active.startedAt)
                        .put("cancelled", false)
                    if (!request.succeed(result)) target.delete()
                }
            }
        } catch (_: Exception) {
            request.fail("无法保存录音")
        } finally {
            active.file.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(activity) else MediaRecorder()

    private data class Recording(
        val encoder: MediaRecorder,
        val file: File,
        val startedAt: Long,
        var timer: CountDownTimer? = null
    )

    private companion object {
        const val MAX_DURATION = 5 * 60_000L
    }
}
