package com.vishnu.jarvis.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TTSManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            ready = true
        }
    }

    fun speak(text: String) {
        if (ready) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}