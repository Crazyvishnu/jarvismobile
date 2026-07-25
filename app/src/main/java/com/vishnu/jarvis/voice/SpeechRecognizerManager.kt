package com.vishnu.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechRecognizerManager(
    private val context: Context,
    private val callback: (String) -> Unit
) {

    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

    init {

        recognizer.setRecognitionListener(object : RecognitionListener {

            override fun onResults(results: Bundle?) {

                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()

                if (text != null) {
                    callback(text)
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {}

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    fun startListening() {

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)

        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )

        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE,
            "en-US"
        )

        recognizer.startListening(intent)
    }

    fun stop() {
        recognizer.stopListening()
    }

    fun destroy() {
        recognizer.destroy()
    }
}