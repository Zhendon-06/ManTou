package com.hfad.mantou.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.hfad.mantou.R
import com.hfad.mantou.view.MainActivity

internal object HarnessNotification {

    const val CHANNEL_ID = "harness_progress"

    fun notificationId(runId: String): Int {
        val bucket = (runId.hashCode() and Int.MAX_VALUE) % NOTIFICATION_ID_BUCKETS
        return NOTIFICATION_ID_BASE + bucket
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.harness_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.harness_notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    fun build(context: Context, progress: HarnessProgress): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(HarnessForegroundService.EXTRA_RUN_ID, progress.runId)
            progress.sessionId?.let { putExtra(HarnessForegroundService.EXTRA_SESSION_ID, it) }
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            progress.runId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stop_square)
            .setContentTitle(progress.title.take(MAX_TITLE_CHARS))
            .setContentText(progress.message.take(MAX_MESSAGE_CHARS))
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)

        val subText = buildList {
            progress.stage?.takeIf(String::isNotBlank)?.let { add(stageLabel(it)) }
            progress.iteration.takeIf { it > 0 }?.let { add("第 ${it} 轮") }
        }.joinToString(" · ")
        if (subText.isNotBlank()) {
            builder.setSubText(subText)
        }

        if (progress.isRunning) {
            builder
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(
                    R.drawable.ic_stop_square,
                    context.getString(R.string.harness_notification_cancel),
                    cancelPendingIntent(context, progress.runId)
                )
        } else {
            builder
                .setOngoing(false)
                .setAutoCancel(true)
                .setSmallIcon(
                    if (progress.status == HarnessProgressStatus.SUCCEEDED) {
                        R.drawable.ic_check_circle
                    } else {
                        R.drawable.ic_harness_error
                    }
                )
        }

        val boundedProgress = progress.progress?.coerceIn(0, 100)
        if (progress.isRunning || boundedProgress != null) {
            builder.setProgress(100, boundedProgress ?: 0, boundedProgress == null)
        } else {
            builder.setProgress(0, 0, false)
        }
        return builder.build()
    }

    private fun stageLabel(stage: String): String {
        return when (stage) {
            "INPUT", "SANITIZING" -> "输入过滤"
            "PROMPT", "PROMPTING", "PREPARING" -> "提示词准备"
            "MODEL", "REQUESTING_MODEL", "WRITING_INITIAL", "WRITING_DIFF", "REPAIRING" -> "模型生成"
            "TOOL", "APPLYING_DIFF", "APPLYING_TOOL" -> "代码工具"
            "BUILD", "BUILDING" -> "构建检查"
            "INSPECT", "INSPECTING" -> "WebView 检查"
            "SELF_TEST", "SELF_TESTING" -> "页面自测"
            "TEST", "TESTING" -> "独立测试"
            "DELIVER", "COMPLETED" -> "交付"
            "ERROR" -> "失败"
            else -> stage
        }
    }

    private fun cancelPendingIntent(context: Context, runId: String): PendingIntent {
        val intent = Intent(context, HarnessForegroundService::class.java).apply {
            action = HarnessForegroundService.ACTION_CANCEL
            putExtra(HarnessForegroundService.EXTRA_RUN_ID, runId)
        }
        return PendingIntent.getService(
            context,
            runId.hashCode() xor CANCEL_REQUEST_CODE_SALT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private const val MAX_TITLE_CHARS = 64
    private const val MAX_MESSAGE_CHARS = 240
    private const val CANCEL_REQUEST_CODE_SALT = 0x4D54
    private const val NOTIFICATION_ID_BASE = 4_201
    private const val NOTIFICATION_ID_BUCKETS = 1_000_000
}
