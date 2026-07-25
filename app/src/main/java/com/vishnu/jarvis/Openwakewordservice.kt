package com.vishnu.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

// Real "Hey Jarvis" wake word using openWakeWord - free, fully open-source,
// no account/API key. Captures raw mic audio directly with AudioRecord and
// streams it through OpenWakeWordDetector on a background thread. When the
// wake-word is heard, it triggers the LockScreenWaker to launch the main
// UI for command capture and execution.
class OpenWakeWordService : Service() {

    private var audioRecord: AudioRecord? = null
    private var detector: OpenWakeWordDetector? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var listeningForWakeWord = true
    @Volatile private var micThread: Thread? = null

    companion object {
        private const val TAG = "OpenWakeWordSvc"
        private const val NOTIF_ID = 3
        private const val SAMPLE_RATE = 16000
        private const val THRESHOLD = 0.5f
    }

    override fun onCreate() {
        super.onCreate()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification)
            }

            detector = OpenWakeWordDetector(this)
            startMicLoop()
            running = true
            Log.d(TAG, "openWakeWord listening for 'hey jarvis'")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start", e)
            Toast.makeText(this, "Wake word engine failed to start: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        stopMicLoop()
        detector?.close()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Suppress("MissingPermission")
    private fun startMicLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, OpenWakeWordDetector.CHUNK_SAMPLES * 4)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        audioRecord?.startRecording()

        micThread = thread(start = true, name = "OpenWakeWordMic") {
            val chunk = ShortArray(OpenWakeWordDetector.CHUNK_SAMPLES)
            while (running) {
                if (!listeningForWakeWord) {
                    Thread.sleep(100)
                    continue
                }
                val read = audioRecord?.read(chunk, 0, chunk.size) ?: -1
                if (read <= 0) continue

                val score = try {
                    detector?.processChunk(chunk)
                } catch (e: Exception) {
                    Log.e(TAG, "Detector error", e)
                    null
                }

                if (score != null && score > THRESHOLD) {
                    Log.d(TAG, "Wake word detected, score=$score")
                    onWakeWordDetected()
                }
            }
        }
    }

    private fun stopMicLoop() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        micThread = null
    }

    private fun onWakeWordDetected() {
        listeningForWakeWord = false
        handler.post {
            LockScreenWaker.wakeAndListen(this)
        }
        // Resume listening after 7 seconds to allow the user to speak their
        // command to the UI activity.
        handler.postDelayed({ listeningForWakeWord = true }, 7000)
    }

    private fun buildNotification(): Notification {
        val channelId = "jarvis_wake_oww"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Jarvis listening (wake word)", NotificationManager.IMPORTANCE_LOW
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
            .setContentTitle("Jarvis")
            .setContentText("Say \"Hey Jarvis\" to wake me up")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}