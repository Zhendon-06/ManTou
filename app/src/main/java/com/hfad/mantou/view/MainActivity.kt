package com.hfad.mantou.view

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
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)

        // 处理主内容区域的系统栏和键盘 padding
        val mainView = findViewById<android.view.View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 处理侧边菜单的状态栏 padding
        val drawerHeader = findViewById<android.view.View>(R.id.drawerHeader)
        ViewCompat.setOnApplyWindowInsetsListener(drawerHeader) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top + 16, v.paddingRight, v.paddingBottom)
            insets
        }
    }

    fun openDrawer() = drawerLayout.openDrawer(GravityCompat.START)
    fun closeDrawer() = drawerLayout.closeDrawer(GravityCompat.START)
}
