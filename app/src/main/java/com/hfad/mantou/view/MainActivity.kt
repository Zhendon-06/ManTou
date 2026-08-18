package com.hfad.mantou.view

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.hfad.mantou.R
import com.hfad.mantou.service.HarnessForegroundService
import com.hfad.mantou.view.glass.LiquidGlass

class MainActivity : AppCompatActivity() {

    lateinit var drawerLayout: DrawerLayout
        private set
    private var useDarkStatusBarIcons = true
    private var useDarkNavigationBarIcons = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)

        // 主内容保持 edge-to-edge，顶部状态栏 inset 由各页面自己的浮层处理。
        val mainView = findViewById<android.view.View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, 0)
            insets
        }
        mainView.post(::dispatchHarnessNotificationIntent)

        // 处理侧边菜单的状态栏 padding
        val drawerMenu = findViewById<android.view.View>(R.id.drawerMenu)
        val drawerMenuBasePaddingTop = drawerMenu.paddingTop
        val drawerMenuBasePaddingBottom = drawerMenu.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(drawerMenu) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                systemBars.top + drawerMenuBasePaddingTop,
                v.paddingRight,
                systemBars.bottom + drawerMenuBasePaddingBottom
            )
            insets
        }

        val glassHandle = LiquidGlass.install(this, drawerLayout)
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: android.view.View, slideOffset: Float) {
                glassHandle.refresh()
            }

            override fun onDrawerOpened(drawerView: android.view.View) {
                glassHandle.refresh()
            }

            override fun onDrawerClosed(drawerView: android.view.View) {
                glassHandle.refresh()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        findViewById<android.view.View>(R.id.main).post(::dispatchHarnessNotificationIntent)
    }

    internal fun dispatchHarnessNotificationIntent() {
        val sessionId = intent.getLongExtra(
            HarnessForegroundService.EXTRA_SESSION_ID,
            Long.MIN_VALUE
        ).takeUnless { it == Long.MIN_VALUE } ?: return
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
        val mainFragment = navHost?.childFragmentManager?.primaryNavigationFragment as? MainFragment
            ?: return
        intent.removeExtra(HarnessForegroundService.EXTRA_SESSION_ID)
        intent.removeExtra(HarnessForegroundService.EXTRA_RUN_ID)
        mainFragment.openHarnessSessionFromNotification(sessionId)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBarIconAppearance()
    }

    fun updateSystemBarIconAppearance(
        darkStatusBarIcons: Boolean,
        darkNavigationBarIcons: Boolean
    ) {
        useDarkStatusBarIcons = darkStatusBarIcons
        useDarkNavigationBarIcons = darkNavigationBarIcons
        applySystemBarIconAppearance()
    }

    private fun applySystemBarIconAppearance() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = useDarkStatusBarIcons
            isAppearanceLightNavigationBars = useDarkNavigationBarIcons
        }
    }

    fun openDrawer() = drawerLayout.openDrawer(GravityCompat.START)
    fun closeDrawer() = drawerLayout.closeDrawer(GravityCompat.START)
}
