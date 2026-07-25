package com.vishnu.jarvis.agent

class AgentExecutor {

    fun execute(

        plan: AgentPlan,

        callback: (String) -> Unit

    ) {

        run(plan.actions, 0, callback)

    }

    private fun run(

        actions: List<AgentAction>,

        index: Int,

        callback: (String) -> Unit

    ) {

        if (index >= actions.size) {

            callback("Task completed.")

            return

        }

        val action = actions[index]

        val plugin = PluginManager.find(action)

        if (plugin == null) {

            callback("No plugin found for ${action.plugin}")

            return

        }

        plugin.execute(action) { success, message ->

            callback(message)

            if (success) {

                run(actions, index + 1, callback)

            }

        }

    }

}