package com.hfad.mantou.view

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.hfad.mantou.R
import com.hfad.mantou.utils.ApiTestHelper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    lateinit var drawerLayout: DrawerLayout
        private set
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_main)
        
        drawerLayout = findViewById(R.id.drawerLayout)
        
        val mainView = findViewById<android.view.View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            
            // 键盘弹出时，底部使用键盘高度；否则使用系统栏高度
            val bottomPadding = if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                ime.bottom
            } else {
                systemBars.bottom
            }
            
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomPadding)
            insets
        }
        
        // 启动时测试 API 连接（可选）
        testApiConnection()
    }
    
    /**
     * 测试 API 连接
     */
    private fun testApiConnection() {
        lifecycleScope.launch {
            val result = ApiTestHelper.testApiConnection()
            
            when (result) {
                is ApiTestHelper.TestResult.Success -> {
                    Toast.makeText(
                        this@MainActivity,
                        "✅ API 连接成功！",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is ApiTestHelper.TestResult.Error -> {
                    Toast.makeText(
                        this@MainActivity,
                        "❌ API 测试失败: ${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * 打开侧边菜单
     */
    fun openDrawer() {
        drawerLayout.openDrawer(GravityCompat.START)
    }

    /**
     * 关闭侧边菜单
     */
    fun closeDrawer() {
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    /**
     * 切换侧边菜单状态
     */
    fun toggleDrawer() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            closeDrawer()
        } else {
            openDrawer()
        }
    }
}
