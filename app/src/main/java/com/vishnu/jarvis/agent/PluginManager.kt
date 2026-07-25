package com.vishnu.jarvis.agent

object PluginManager {

    private val plugins = mutableListOf<JarvisPlugin>()

    fun register(plugin: JarvisPlugin) {
        plugins.add(plugin)
    }

    fun find(action: AgentAction): JarvisPlugin? {
        return plugins.firstOrNull {
            it.canHandle(action)
        }
    }

    fun all(): List<JarvisPlugin> {
        return plugins
    }
}