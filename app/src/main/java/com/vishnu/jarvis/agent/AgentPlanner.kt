package com.vishnu.jarvis.agent

// DEPRECATED - GeminiPlanner now handles the heavy lifting of NLU parsing.
// Left here temporarily if old AgentAction architecture is still needed elsewhere.
object AgentPlanner {
    fun plan(text: String): AgentPlan {
        return AgentPlan()
    }
}
