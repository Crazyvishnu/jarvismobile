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
        speakInternal(text, null)
    }

    fun speakWithCallback(text: String, onDone: () -> Unit) {
        speakInternal(text, onDone)
    }

    fun speakAndListen(context: Context, text: String) {
        speakInternal(text) {
            context.sendBroadcast(android.content.Intent("JARVIS_NEEDS_RESPONSE"))
        }
    }

    private fun speakInternal(text: String, onDone: (() -> Unit)?) {
        if (ready) {
            val utteranceId = if (onDone != null) "jarvis_q_${System.currentTimeMillis()}" else "jarvis"
            
            if (onDone != null) {
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        if (id == utteranceId) onDone()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {}
                })
            }
            
            val params = android.os.Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } else {
            pending.add(text)
            // Callbacks for pending not supported yet
        }
    }

    fun shutdown() {
        pending.clear()
        tts?.shutdown()
        tts = null
        ready = false
    }
}