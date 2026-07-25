package com.vishnu.jarvis.voice

object WakeWordDetector {

    fun isWakeWord(text: String): Boolean {

        val t = text.lowercase()

        return t.contains("hey jarvis") ||
                t.contains("jarvis")
    }
}