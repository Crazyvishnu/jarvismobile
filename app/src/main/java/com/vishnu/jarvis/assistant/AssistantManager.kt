package com.vishnu.jarvis.assistant

object AssistantManager {

    var state = AssistantState.IDLE

    fun listening() {

        state = AssistantState.LISTENING

    }

    fun thinking() {

        state = AssistantState.THINKING

    }

    fun executing() {

        state = AssistantState.EXECUTING

    }

    fun speaking() {

        state = AssistantState.SPEAKING

    }

    fun idle() {

        state = AssistantState.IDLE

    }

}