package com.vishnu.jarvis

// Turns one spoken/typed sentence into a small ordered plan.
// Most commands are still a single action - this only kicks in extra
// logic when the sentence clearly names a "search + play" workflow
// (e.g. "open youtube, search ayyappa songs, play first song").
sealed class PlannedAction {
    data class YoutubeSearchAndPlay(val query: String) : PlannedAction()
    data class SpotifySearchAndPlay(val query: String) : PlannedAction()
    data class Raw(val text: String) : PlannedAction()
}

object CommandPlanner {

    private val segmentSplitter = Regex("\\s*(?:,|\\bthen\\b|\\band then\\b|\\bafter that\\b)\\s*")

    fun plan(text: String): List<PlannedAction> {
        val t = text.lowercase().trim()

        if ("youtube" in t) {
            extractQuery(t, "youtube")?.let { return listOf(PlannedAction.YoutubeSearchAndPlay(it)) }
        }
        if ("spotify" in t) {
            extractQuery(t, "spotify")?.let { return listOf(PlannedAction.SpotifySearchAndPlay(it)) }
        }

        // No recognizable "search + play" workflow - fall back to treating
        // each comma/"then"-separated piece as its own command, handled
        // one at a time by CommandRouter's existing single-command logic.
        return t.split(segmentSplitter)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { PlannedAction.Raw(it) }
    }

    // Strips filler words/app name/connectors, leaving just the search query.
    // e.g. "open youtube, search ayyappa songs, play first song" -> "ayyappa songs"
    private fun extractQuery(t: String, app: String): String? {
        var s = t
        s = s.replace(Regex("\\bopen $app\\b"), " ")
        s = s.replace(Regex("\\bon $app\\b"), " ")
        s = s.replace(Regex("\\b$app\\b"), " ")
        s = s.replace(Regex("\\bplay (the )?first( song| video| result)?\\b"), " ")
        s = s.replace(Regex("\\bsearch\\b"), " ")
        s = s.replace(Regex("\\bplay\\b"), " ")
        s = s.replace(segmentSplitter, " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s.ifBlank { null }
    }
}