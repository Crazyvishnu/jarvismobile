package com.vishnu.jarvis.agent

object AgentPlanner {

    fun plan(text: String): AgentPlan {
        val t = text.lowercase().trim()
        val plan = AgentPlan()

        // 1. YouTube Intent
        if ("youtube" in t) {
            val query = t
                .replace(Regex("\\b(open|youtube|play|search|and)\\b"), "")
                .trim()

            plan.actions.add(
                AgentAction(
                    plugin = "youtube",
                    command = "search",
                    arguments = mapOf("query" to query)
                )
            )
            return plan
        }

        // 2. Open Google Intent
        if (t == "open google") {
            plan.actions.add(
                AgentAction(
                    plugin = "browser",
                    command = "opengoogle"
                )
            )
            return plan
        }

        // 3. General Search Intent
        if ("search" in t && "youtube" !in t && "spotify" !in t) {
            val query = t.replace("search", "").trim()

            plan.actions.add(
                AgentAction(
                    plugin = "browser",
                    command = "search",
                    arguments = mapOf("query" to query)
                )
            )
            return plan
        }

        return plan
    }
}