
package com.vishnu.jarvis.agent

interface JarvisPlugin {

    val name: String

    fun canHandle(action: AgentAction): Boolean

    fun execute(

        action: AgentAction,

        callback: (Boolean, String) -> Unit

    )
}