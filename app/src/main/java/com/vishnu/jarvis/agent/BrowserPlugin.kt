package com.vishnu.jarvis.agent

import android.content.Context
import com.vishnu.jarvis.MediaController

class BrowserPlugin(
    context: Context
) : JarvisPlugin {

    private val media = MediaController(context)

    override val name = "browser"

    override fun canHandle(action: AgentAction): Boolean {
        return action.plugin.equals("browser", true)
    }

    override fun execute(
        action: AgentAction,
        callback: (Boolean, String) -> Unit
    ) {

        when (action.command.lowercase()) {

            "search" -> {

                val query = action.arguments["query"] ?: ""

                if (query.isBlank()) {
                    callback(false, "Nothing to search.")
                    return
                }

                media.searchGoogle(query)

                callback(true, "Searching Google for $query")

            }

            "opengoogle" -> {

                media.searchGoogle("")

                callback(true, "Opening Google")

            }

            else -> {

                callback(false, "Unknown browser command.")

            }

        }

    }

}