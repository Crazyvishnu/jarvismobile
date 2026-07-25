package com.vishnu.jarvis

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

// Bug fix: previously, any speak() call made before onInit() finished was
// just dropped (ready == false -> silently ignored). Since init() is
// called fresh in MainActivity.onCreate() every launch, the very first
// reply typed right after opening the app would show text but never be
// spoken. Now anything spoken too early is queued and flushed once the
// engine reports SUCCESS.
object TextToSpeechManager : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<String>()

    fun init(context: Context) {
        if (tts == null)
            tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            tts?.language = Locale.US
            if (pending.isNotEmpty()) {
                pending.forEach { tts?.speak(it, TextToSpeech.QUEUE_ADD, null, "jarvis") }
                pending.clear()
            }
        }
    }

    fun speak(text: String) {
        if (ready) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        } else {
            // Engine not ready yet (e.g. right after app launch) - queue it
            // instead of dropping it so the very first reply is still heard.
            pending.add(text)
        }
    }

    fun shutdown() {
        pending.clear()
        tts?.shutdown()
        tts = null
        ready = false
    }
}