package com.hfad.mantou.tool

import android.content.Context
import com.hfad.mantou.tool.generated.GeneratedToolRegistry

object ToolRegistry {

    private var initialized: Boolean = false
    private val toolInstances: MutableList<BaseTool> = mutableListOf()

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        toolInstances.clear()
        toolInstances.addAll(GeneratedToolRegistry.createAll(appContext))
        initialized = true
    }

    fun instances(): List<BaseTool> = toolInstances.toList()

    fun get(toolName: String): BaseTool? = toolInstances.firstOrNull { it.toolName == toolName }
}
