package com.vishnu.jarvis.voice

import android.content.Context
import com.vishnu.jarvis.VoskWakeWordService

object WakeWordDetector {

    fun isWakeWord(text: String, context: Context? = null): Boolean {
        val t = text.lowercase()
        val wakeWord = if (context != null) {
            VoskWakeWordService.getWakeWord(context)
        } else {
            "hey jarvis"
        }
        return t.contains(wakeWord) || t.contains("jarvis")
    }
}