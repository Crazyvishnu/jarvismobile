package com.vishnu.jarvis.voice

import android.content.Context

class VoiceManager(
    private val context: Context,
    private val commandCallback: (String) -> Unit
) {

    private val tts = TTSManager(context)

    private val speech = SpeechRecognizerManager(context) { text ->

        if (WakeWordDetector.isWakeWord(text)) {

            tts.speak("Yes sir")

        } else {

            commandCallback(text)
        }
    }

    fun startListening() {
        speech.startListening()
    }

    fun stopListening() {
        speech.stop()
    }

    fun speak(text: String) {
        tts.speak(text)
    }

    fun destroy() {
        speech.destroy()
        tts.shutdown()
    }
}