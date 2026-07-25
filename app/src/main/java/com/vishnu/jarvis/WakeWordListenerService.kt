package com.vishnu.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

class WakeWordListenerService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private lateinit var router: CommandRouter
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var errorCount = 0

    // Two-phase listening:
    //  Phase 1 (awaitingCommand = false): listening for the wake word.
    //  Phase 2 (awaitingCommand = true): listening for the actual command,
    //    no need to say "jarvis" again - whatever is heard next IS the command.
    private var awaitingCommand = false

    companion object {
        private const val TAG = "JarvisWakeWord"
    }

    override fun onCreate() {
        super.onCreate()

        TextToSpeechManager.init(this)

        router = CommandRouter(this) { reply ->
            Handler(Looper.getMainLooper()).post {
                TextToSpeechManager.speak(reply)
                Toast.makeText(this, reply, Toast.LENGTH_SHORT).show()
            }
        }

        try {
            val notification = buildNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, notification)
            }

            running = true
            Log.d(TAG, "Service started, beginning listening cycle")
            handler.post {
                Toast.makeText(this, "Jarvis is now listening for \"Hey Jarvis\"", Toast.LENGTH_SHORT).show()
            }
            startListeningCycle()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            handler.post {
                Toast.makeText(this, "Jarvis failed to start: ${e.message}", Toast.LENGTH_LONG).show()
            }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        running = false
        recognizer?.destroy()
        handler.removeCallbacksAndMessages(null)
        TextToSpeechManager.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListeningCycle() {
        if (!running) return

        // Safety check for microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            handler.post {
                Toast.makeText(this, "Microphone permission lost. Jarvis stopped listening.", Toast.LENGTH_LONG).show()
            }
            Log.e(TAG, "RECORD_AUDIO permission missing - stopping")
            stopSelf()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "SpeechRecognizer.isRecognitionAvailable() returned false - no recognition service on this device")
            handler.post {
                Toast.makeText(this, "No speech recognition service available on this device.", Toast.LENGTH_LONG).show()
            }
            running = false
            stopSelf()
            return
        }

        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: android.os.Bundle) {
                    errorCount = 0 // Reset on success
                    val heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.lowercase().orEmpty()
                    Log.d(TAG, "Heard: \"$heard\"")

                    if (awaitingCommand) {
                        awaitingCommand = false
                        if (heard.isNotBlank()) {
                            router.process(heard)
                        }
                        restart(400)
                        return
                    }

                    if ("jarvis" in heard) {
                        val command = heard.substringAfter("jarvis").trim()
                        if (command.isNotEmpty()) {
                            LockScreenWaker.wakeAndListen(this@WakeWordListenerService)
                            router.process(command)
                            restart(400)
                        } else {
                            LockScreenWaker.wakeAndListen(this@WakeWordListenerService)
                            TextToSpeechManager.speak("Yes Vishnu?")
                            awaitingCommand = true
                            restart(900)
                        }
                    } else {
                        restart(400)
                    }
                }

                override fun onError(error: Int) {
                    errorCount++
                    Log.w(TAG, "Recognizer error #$errorCount: ${errorName(error)}")
                    if (awaitingCommand) {
                        awaitingCommand = false
                    }
                    val delay = if (errorCount > 3) 5000L else 500L
                    if (errorCount > 10) {
                        running = false
                        Log.e(TAG, "Too many consecutive errors (last: ${errorName(error)}) - giving up")
                        handler.post {
                            Toast.makeText(
                                this@WakeWordListenerService,
                                "Jarvis stopped listening after repeated errors (${errorName(error)}). Tap listen to restart.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        stopSelf()
                    } else {
                        restart(delay)
                    }
                }

                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                if (awaitingCommand) {
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
                }
            }
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening threw", e)
            restart(2000)
        }
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        else -> "UNKNOWN($error)"
    }

    private fun restart(delay: Long) {
        if (running) {
            handler.postDelayed({ startListeningCycle() }, delay)
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "jarvis_listener"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Jarvis listening", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Jarvis\" followed by a command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}