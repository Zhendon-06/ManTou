package com.hfad.mantou.tool.impl

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.hfad.mantou.R
import com.hfad.mantou.tool.impl.WebAppDevicePage.Request
import com.hfad.mantou.view.VirtualAppActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale

internal class WebAppDeviceHost(
    private val activity: FragmentActivity,
    caller: ActivityResultCaller,
    private val owner: LifecycleOwner
) : DefaultLifecycleObserver {
    private val recording = WebAppRecording(activity, caller, owner)
    private val main = Handler(Looper.getMainLooper())
    private var photoPick: PhotoPick? = null
    private var notificationPermission: NotificationRequest? = null
    private var speechEngine: TextToSpeech? = null
    private var speechReady = false
    private var speech: Speech? = null

    val isResumed: Boolean get() = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private val pickOne = caller.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        handlePhotos(listOfNotNull(uri))
    }
    private val pickMany = caller.registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5), ::handlePhotos)
    private val requestNotifications = caller.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = notificationPermission ?: return@registerForActivityResult
        notificationPermission = null
        if (!pending.request.isCurrent) return@registerForActivityResult
        if (granted) {
            owner.lifecycleScope.launch {
                owner.lifecycle.withResumed {
                    if (pending.request.canStart) postNotification(pending)
                    else pending.request.fail("请在页面可见时发送通知")
                }
            }
        } else {
            pending.request.fail("通知权限未授予，可在 Android 设置中开启")
        }
    }

    init {
        owner.lifecycle.addObserver(this)
    }

    fun pickPhotos(request: Request, maxPhotos: Int) {
        if (photoPick != null) {
            request.fail("已有照片选择器打开")
            return
        }
        photoPick = PhotoPick(request, maxPhotos)
        val pickerRequest = PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
        runCatching {
            if (maxPhotos == 1) pickOne.launch(pickerRequest.build())
            else pickMany.launch(pickerRequest.setMaxItems(maxPhotos).build())
        }.onFailure {
            photoPick = null
            request.fail("无法打开系统照片选择器")
        }
    }

    private fun handlePhotos(uris: List<Uri>) {
        val pick = photoPick ?: return
        photoPick = null
        if (!pick.request.isCurrent) return
        if (uris.isEmpty()) {
            pick.request.succeed("cancelled" to true, "photos" to JSONArray())
            return
        }
        if (uris.size > pick.maxPhotos) {
            pick.request.fail("最多可保存 ${pick.maxPhotos} 张照片，请重新选择")
            return
        }
        val media = pick.request.page.media
        owner.lifecycleScope.launch {
            // Once copying starts, finish it or remove the copies even if the page closes during a read.
            withContext(NonCancellable) {
                val files = withContext(Dispatchers.IO) {
                    runCatching { media.importPhotos(activity.contentResolver, uris) }
                }.getOrElse {
                    pick.request.fail(it.message ?: "无法读取所选照片")
                    return@withContext
                }
                if (!pick.request.succeed("cancelled" to false, "photos" to media.photoResults(files))) {
                    withContext(Dispatchers.IO) { files.forEach { it.delete() } }
                }
            }
        }
    }

    fun showNotification(request: Request, id: String, title: String, message: String) {
        val pending = NotificationRequest(request, id, title, message)
        if (!needsNotificationPermission()) {
            postNotification(pending)
            return
        }
        if (notificationPermission != null) {
            request.fail("请先完成系统通知权限确认")
            return
        }
        notificationPermission = pending
        runCatching { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }.onFailure {
            notificationPermission = null
            request.fail("无法请求通知权限")
        }
    }

    fun cancelNotification(request: Request, id: String) {
        NotificationManagerCompat.from(activity).cancel(notificationTag(request.page, id), WEB_NOTIFICATION_ID)
        request.succeed("id" to id)
    }

    private fun needsNotificationPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun postNotification(pending: NotificationRequest) {
        val (request, id, title, message) = pending
        val notifications = NotificationManagerCompat.from(activity)
        val manager = activity.getSystemService(NotificationManager::class.java)
        if (needsNotificationPermission() || !notifications.areNotificationsEnabled()) {
            request.fail("通知已在 Android 设置中关闭")
            return
        }
        runCatching {
            manager.createNotificationChannel(NotificationChannel(
                WEB_NOTIFICATION_CHANNEL,
                activity.getString(R.string.web_app_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ))
            check(manager.getNotificationChannel(WEB_NOTIFICATION_CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) {
                "小应用通知已在 Android 设置中关闭"
            }
            val openApp = Intent(activity, VirtualAppActivity::class.java).apply {
                data = Uri.Builder().scheme("mantou-notification").authority("web-app")
                    .appendPath(request.page.appKey).appendPath(id).build()
                putExtra(VirtualAppActivity.EXTRA_HTML_PATH, request.page.openTarget.absolutePath)
            }
            val open = PendingIntent.getActivity(activity, 0, openApp, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(activity, WEB_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_web_app_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setSubText(request.page.displayName)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            notifications.notify(notificationTag(request.page, id), WEB_NOTIFICATION_ID, notification)
            request.succeed("id" to id)
        }.onFailure { request.fail(it.message ?: "通知发送失败") }
    }

    fun requestRecording(request: Request) {
        if (speech != null) {
            request.fail("请先结束语音播报再录音")
        } else {
            recording.request(request)
        }
    }

    fun speak(request: Request, text: String, languageTag: String) {
        if (speech != null || recording.isRecording) {
            request.fail("请先结束当前播报或录音")
            return
        }
        val locale = if (languageTag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(languageTag)
        if (locale.language.isEmpty()) {
            request.fail("播报语言无效")
            return
        }
        speech = Speech(request, text, locale)
        if (speechReady) {
            playSpeech()
        } else if (speechEngine == null) {
            val engine = TextToSpeech(activity.applicationContext) { status ->
                main.post {
                    if (owner.lifecycle.currentState != Lifecycle.State.DESTROYED) onSpeechInitialized(status)
                }
            }
            speechEngine = engine
        }
    }

    fun stopSpeech(request: Request) {
        val stopped = speech?.request?.page === request.page
        if (stopped) interruptSpeech(request.page)
        request.succeed("stopped" to stopped)
    }

    private fun onSpeechInitialized(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            speech?.request?.fail("无法启动系统语音引擎，请检查 Android 文字转语音设置")
            speech = null
            speechEngine?.shutdown()
            speechEngine = null
            return
        }
        val engine = speechEngine ?: return
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = finish(utteranceId, null)

            @Deprecated("Deprecated in Android")
            override fun onError(utteranceId: String?) = finish(utteranceId, "系统语音播报失败")

            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, "播报已停止")

            private fun finish(id: String?, error: String?) {
                main.post {
                    val pending = speech ?: return@post
                    if (pending.request.id != id) return@post
                    speech = null
                    if (error == null) pending.request.succeed("spoken" to true) else pending.request.fail(error)
                }
            }
        })
        speechReady = true
        playSpeech()
    }

    private fun playSpeech() {
        val pending = speech ?: return
        val engine = speechEngine ?: return
        val result = runCatching {
            check(pending.request.canStart) { "请在页面可见时使用语音播报" }
            check(engine.setLanguage(pending.locale) >= TextToSpeech.LANG_AVAILABLE) {
                "系统语音引擎缺少此语言，请在 Android 文字转语音设置中下载语音数据"
            }
            check(engine.speak(pending.text, TextToSpeech.QUEUE_FLUSH, null, pending.request.id) == TextToSpeech.SUCCESS) {
                "系统语音播报失败"
            }
        }
        result.onFailure {
            speech = null
            pending.request.fail(it.message ?: "系统语音播报失败")
        }
    }

    fun interruptAudio(page: WebAppDevicePage) {
        recording.interrupt(page)
        interruptSpeech(page)
    }

    private fun interruptSpeech(page: WebAppDevicePage? = null) {
        val pending = speech ?: return
        if (page != null && pending.request.page !== page) return
        speech = null
        speechEngine?.stop()
        pending.request.fail("播报已停止")
    }

    override fun onPause(owner: LifecycleOwner) {
        recording.interrupt(keepPermissionRequest = true)
        interruptSpeech()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        recording.interrupt()
        interruptSpeech()
        speechEngine?.shutdown()
        speechEngine = null
        photoPick = null
        notificationPermission = null
    }

    private data class PhotoPick(val request: Request, val maxPhotos: Int)
    private data class Speech(val request: Request, val text: String, val locale: Locale)
    private data class NotificationRequest(val request: Request, val id: String, val title: String, val message: String)

    private companion object {
        const val WEB_NOTIFICATION_CHANNEL = "generated_web_apps"
        const val WEB_NOTIFICATION_ID = 1

        fun notificationTag(page: WebAppDevicePage, id: String): String = "web-app:${page.appKey}:$id"
    }
}
