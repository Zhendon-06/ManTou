package com.hfad.mantou.view.glass

import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hfad.mantou.view.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassHostLifecycleInstrumentedTest {
    @Test
    fun temporaryWindowDetachKeepsContentAndHostReusable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            repeat(3) {
                lateinit var host: GlassHostView
                scenario.onActivity { activity ->
                    host = generateSequence(activity.drawerLayout.parent) { it.parent }
                        .filterIsInstance<GlassHostView>().first()
                    val parent = host.parent as ViewGroup
                    val index = parent.indexOfChild(host)
                    val params = host.layoutParams
                    parent.removeView(host)
                    assertFalse(host.isDisposed)
                    parent.addView(host, index, params)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertSame(activity.drawerLayout, host.contentRoot)
                    assertFalse(host.isDisposed)
                    assertTrue(activity.drawerLayout.isAttachedToWindow)
                    assertTrue(activity.drawerLayout.width > 0)
                    assertTrue(activity.drawerLayout.height > 0)
                }
            }
        }
    }
}
