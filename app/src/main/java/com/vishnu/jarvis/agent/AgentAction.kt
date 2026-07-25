package com.vishnu.jarvis.agent

data class AgentAction(

    val plugin: String,

    val command: String,

    val arguments: Map<String, String> = emptyMap()

)