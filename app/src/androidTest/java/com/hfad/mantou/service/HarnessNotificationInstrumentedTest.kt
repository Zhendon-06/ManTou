package com.hfad.mantou.service

import android.app.Notification
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.BAKLAVA)
class HarnessNotificationInstrumentedTest {

    @Test
    fun liveUpdateTracksTheCurrentStageAndEndsWithTheTask() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val starting = HarnessProgress(runId = "notification-test")
        val generating = starting.copy(stage = "MODEL", progress = 25)

        val initial = HarnessNotification.build(context, starting)
        val updated = HarnessNotification.build(context, generating)
        val completed = HarnessNotification.build(
            context,
            generating.copy(status = HarnessProgressStatus.SUCCEEDED, progress = null)
        )

        val initialStyle = Notification.Builder.recoverBuilder(context, initial).style
            as Notification.ProgressStyle
        val updatedStyle = Notification.Builder.recoverBuilder(context, updated).style
            as Notification.ProgressStyle

        assertTrue(initialStyle.isProgressIndeterminate)
        assertFalse(updatedStyle.isProgressIndeterminate)
        assertEquals(25, updatedStyle.progress)
        assertEquals("模型生成", updated.shortCriticalText)
        assertTrue(NotificationCompat.isRequestPromotedOngoing(updated))
        assertTrue(updated.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(NotificationCompat.isRequestPromotedOngoing(completed))
        assertEquals(0, completed.flags and Notification.FLAG_ONGOING_EVENT)
    }
}
