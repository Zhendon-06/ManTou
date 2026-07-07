package com.hfad.mantou.view

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.hfad.mantou.R

class MainActivity : AppCompatActivity() {

    lateinit var drawerLayout: DrawerLayout
        private set

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

        // 处理侧边菜单的状态栏 padding
        val drawerMenu = findViewById<android.view.View>(R.id.drawerMenu)
        val drawerMenuBasePaddingTop = drawerMenu.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(drawerMenu) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top + drawerMenuBasePaddingTop, v.paddingRight, v.paddingBottom)
            insets
        }
    }

    fun openDrawer() = drawerLayout.openDrawer(GravityCompat.START)
    fun closeDrawer() = drawerLayout.closeDrawer(GravityCompat.START)
}
