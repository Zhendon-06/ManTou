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
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)

        // 处理主内容区域的系统栏和键盘 padding
        val mainView = findViewById<android.view.View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 过滤 IME 弹起/收起瞬间系统短暂回报的 0 值，避免聊天框上下抖动
            if (systemBars.top > 0) {
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            }
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
