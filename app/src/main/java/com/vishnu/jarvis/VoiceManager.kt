package com.vishnu.jarvis

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

class VoiceManager(
    private val activity: Activity,
    private val onResult: (String) -> Unit
) {

    private var recognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    fun initialize() {

        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Toast.makeText(
                activity,
                "Speech recognition is not available on this device.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        tts = TextToSpeech(activity) { status ->

            if (status == TextToSpeech.SUCCESS) {

                tts.language = Locale.getDefault()

                ttsReady = true
            }
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(activity)

        recognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {}

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {

                val data = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )

                if (!data.isNullOrEmpty()) {

                    val text = data[0].trim()

                    onResult(text)
                }

                // Continue listening
                startListening()
            }

            override fun onError(error: Int) {

                when (error) {

                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_CLIENT -> {
                        startListening()
                    }

                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        stopListening()
                        startListening()
                    }

                    else -> {
                        startListening()
                    }
                }
            }
        })
    }

    fun startListening() {

        try {

            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            )

            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault()
            )

            intent.putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                false
            )

            intent.putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                1
            )

            recognizer?.startListening(intent)

        } catch (e: ActivityNotFoundException) {

            Toast.makeText(
                activity,
                "Speech recognition unavailable",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun speak(text: String) {

        if (ttsReady) {

            tts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "jarvis"
            )
        }
    }

    fun destroy() {

        recognizer?.stopListening()
        recognizer?.cancel()
        recognizer?.destroy()

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}