package com.vishnu.jarvis.agent

import android.content.Context
import com.vishnu.jarvis.YoutubeController

class YoutubePlugin(
    context: Context
) : JarvisPlugin {

    private val youtube = YoutubeController(context)

    override val name = "youtube"

    override fun canHandle(action: AgentAction): Boolean {
        return action.plugin.equals("youtube", true)
    }

    override fun execute(
        action: AgentAction,
        callback: (Boolean, String) -> Unit
    ) {

        when (action.command.lowercase()) {

            "search" -> {

                val query = action.arguments["query"]

                if (query.isNullOrBlank()) {

                    callback(false, "No search query.")

                    return
                }

                youtube.searchAndPlayFirst(query, callback)

            }

            else -> {

                callback(false, "Unknown YouTube command.")

            }
        }
    }
}