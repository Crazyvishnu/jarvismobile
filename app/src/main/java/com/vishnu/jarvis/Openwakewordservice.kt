package com.vishnu.jarvis

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.vishnu.jarvis.voice.SpeechRecognizerManager
import kotlin.concurrent.thread

// Real "Hey Jarvis" wake word foreground service.
// Captures raw mic audio with AudioRecord and processes it via ONNX OpenWakeWord.
// When the wake word is heard, it runs SpeechRecognizerManager in the service
// to capture the command, processes it via CommandRouter, and speaks the reply.
class OpenWakeWordService : Service() {

    private var audioRecord: AudioRecord? = null
    private var aec: android.media.audiofx.AcousticEchoCanceler? = null
    private var ns: android.media.audiofx.NoiseSuppressor? = null
    private var detector: OpenWakeWordDetector? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var listeningForWakeWord = true
    @Volatile private var micThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var speechRecognizer: SpeechRecognizerManager? = null

    companion object {
        private const val TAG = "OpenWakeWordSvc"
        private const val NOTIF_ID = 3
        private const val SAMPLE_RATE = 16000
        private const val THRESHOLD = 0.15f // Optimized for raw unboosted microphone audio

        /** Broadcast actions and extras for updating MainActivity's Chat log */
        const val ACTION_CHAT_UPDATE = "com.vishnu.jarvis.ACTION_CHAT_UPDATE"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_TEXT = "text"

        /** Broadcast action: MainActivity signals to resume wake-word detection */
        const val ACTION_WAKE_WORD_RESUME = "com.vishnu.jarvis.ACTION_WAKE_WORD_RESUME"
    }

    private val resumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Resume broadcast received — re-enabling wake word detection")
            resumeWakeWordDetection()
        }
    }

    private fun enableBluetoothMic() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (audioManager.isBluetoothScoAvailableOffCall) {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        }
    }

    private fun disableBluetoothMic() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
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
            acquireWakeLock()
            registerResumeReceiver()
            enableBluetoothMic()

            // Initialize TextToSpeech & SpeechRecognizerManager directly in the service context
            TextToSpeechManager.init(this)
            speechRecognizer = SpeechRecognizerManager(this) { command ->
                Log.d(TAG, "Speech recognized: $command")
                processCommand(command)
            }.apply {
                errorCallback = { error ->
                    Log.w(TAG, "Speech recognition error: $error")
                    resumeWakeWordDetection()
                }
            }

            running = true
            startMicLoop()
            Log.d(TAG, "openWakeWord listening for 'hey jarvis' (service-level speech recognition active)")

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
        speechRecognizer?.destroy()
        releaseWakeLock()
        unregisterResumeReceiver()
        disableBluetoothMic()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed — scheduling service restart")
        val restartIntent = Intent(applicationContext, OpenWakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    @Suppress("MissingPermission")
    private fun startMicLoop() {
        // 1. Initialize and start the AudioRecord mic if not already running
        if (audioRecord == null) {
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
            
            val sessionId = audioRecord?.audioSessionId ?: -1
            if (sessionId != -1) {
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    aec = android.media.audiofx.AcousticEchoCanceler.create(sessionId)
                    aec?.enabled = true
                    Log.d(TAG, "Hardware AcousticEchoCanceler enabled")
                } else {
                    Log.w(TAG, "Hardware AcousticEchoCanceler NOT available on this device")
                }

                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    ns = android.media.audiofx.NoiseSuppressor.create(sessionId)
                    ns?.enabled = true
                    Log.d(TAG, "Hardware NoiseSuppressor enabled")
                } else {
                    Log.w(TAG, "Hardware NoiseSuppressor NOT available on this device")
                }
            }

            audioRecord?.startRecording()
        }

        // 2. Spawn the background thread exactly ONCE during service lifetime
        if (micThread == null) {
            micThread = thread(start = true, name = "OpenWakeWordMic") {
                val chunk = ShortArray(OpenWakeWordDetector.CHUNK_SAMPLES)

                // ~1.5s rolling buffer of RAW (un-boosted) mic audio, used to verify
                // that the speaker who said the wake word is the enrolled owner.
                val rollingAudio = ShortArray(24000)
                var rollingIdx = 0

                while (running) {
                    if (!listeningForWakeWord) {
                        Thread.sleep(100)
                        continue
                    }
                    val record = audioRecord
                    if (record == null || record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        Thread.sleep(50)
                        continue
                    }
                    val read = record.read(chunk, 0, chunk.size) ?: -1
                    if (read <= 0) continue

                    // Store the RAW samples (before the volume boost below) into the rolling
                    // buffer. Voice Match enrollment records un-boosted audio, so the live
                    // voice print must be un-boosted too or scores collapse.
                    for (i in 0 until read) {
                        rollingAudio[rollingIdx] = chunk[i]
                        rollingIdx = (rollingIdx + 1) % rollingAudio.size
                    }

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
    }

    private fun stopMicLoop() {
        // Just stop and release the AudioRecord instance to free the microphone.
        // We do NOT stop/nullify the micThread here, so the same thread is reused.
        try {
            aec?.release()
            aec = null
            ns?.release()
            ns = null
            
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }



    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    private fun requestAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, android.media.AudioManager.STREAM_VOICE_CALL, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun onWakeWordDetected() {
        listeningForWakeWord = false

        // Stop the wake word audio recorder so we release the mic for SpeechRecognizer
        stopMicLoop()
        detector?.reset()
        
        // PAUSE/DUCK background music (Spotify, YouTube, etc.) so Jarvis can hear clearly
        requestAudioFocus()

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        if (km.isKeyguardLocked) {
            // ── Lock screen is active ──────────────────────────────────────────
            // Only wake the screen (turn display on). Do NOT open MainActivity /
            // the Jarvis panel, so the user's lock screen is preserved.
            handler.post {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isInteractive) {
                    @Suppress("DEPRECATION")
                    val wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                        "Jarvis:LockScreenWake"
                    )
                    wl.acquire(10_000L)
                    // Do not call wl.release() immediately. Allow the 10_000L timeout to expire naturally
                    // so the screen stays on long enough for the user to say their command.
                }
            }
        } else {
            // ── Device is already unlocked ─────────────────────────────────────
            // Launch MainActivity so the Jarvis panel is visible for the user.
            handler.post {
                LockScreenWaker.wakeAndListen(this)
            }
        }

        // Start listening instantly without a TTS delay
        handler.post {
            startListeningForCommand()
        }
    }

    private fun startListeningForCommand() {
        Log.d(TAG, "Starting SpeechRecognizer to capture user command...")
        sendChatUpdateBroadcast("Jarvis", "Listening...")

        try {
            speechRecognizer?.startListening()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SpeechRecognizer", e)
            resumeWakeWordDetection()
        }
    }

    /**
     * Check whether the device keyguard (lock screen) is currently active.
     */
    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }

    /**
     * Returns true if [command] will launch another app's activity
     * (anything that requires the keyguard to be gone).
     */
    private fun needsKeyguardDismissed(command: String): Boolean {
        val t = command.lowercase().trim()
        return t.startsWith("open ") || t.startsWith("unlock") ||
                t.startsWith("say ") || t.startsWith("send ") || t.startsWith("tell ") || t.startsWith("message ") ||
                "play" in t || "spotify" in t || "youtube" in t ||
                "whatsapp" in t || "chrome" in t || "search" in t ||
                "call" in t || "mail" in t || "email" in t
    }

    private fun processCommand(command: String) {
        if (command.trim().isEmpty()) {
            resumeWakeWordDetection()
            return
        }

        sendChatUpdateBroadcast("You", command)

        // Wake the screen for ALL commands so the user can see what's happening
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) {
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "Jarvis:WakeWordCommand"
            )
            wakeLock.acquire(3000L)
        }

        if (isDeviceLocked() && needsKeyguardDismissed(command)) {
            Log.d(TAG, "Keyguard is locked — delegating to AccessibilityService: $command")
            if (JarvisAccessibilityService.instance != null) {
                JarvisAccessibilityService.shouldRelockAfterCommand = true
            }
            JarvisAccessibilityService.startAutoUnlock(command)
            
            // Resume listening after allowing time for the unlock sequence and command execution
            handler.postDelayed({
                resumeWakeWordDetection()
            }, 10000)
        } else {
            executeCommand(command)
        }
    }

    private fun executeCommand(command: String) {
        val router = CommandRouter(this) { reply, needsResponse ->
            Log.d(TAG, "Jarvis reply: $reply")
            sendChatUpdateBroadcast("Jarvis", reply)
            if (needsResponse) {
                TextToSpeechManager.speakAndListen(this, reply)
            } else {
                TextToSpeechManager.speak(reply)
            }
        }

        try {
            router.process(command)
        } catch (e: Exception) {
            Log.e(TAG, "Command routing error", e)
        }

        // Wait a short delay for reply playback, then resume wake word detection
        handler.postDelayed({
            resumeWakeWordDetection()
        }, 5000)
    }

    private fun resumeWakeWordDetection() {
        if (listeningForWakeWord) return

        Log.d(TAG, "Resuming wake word detection")
        
        // Restore music/audio to normal volume since we are done listening
        abandonAudioFocus()
        
        handler.removeCallbacksAndMessages(null)
        startMicLoop()
        detector?.reset()
        listeningForWakeWord = true
    }

    private fun sendChatUpdateBroadcast(sender: String, text: String) {
        val intent = Intent(ACTION_CHAT_UPDATE).apply {
            putExtra(EXTRA_SENDER, sender)
            putExtra(EXTRA_TEXT, text)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::WakeWordLock")
        wakeLock?.acquire(30 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun registerResumeReceiver() {
        val filter = IntentFilter(ACTION_WAKE_WORD_RESUME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resumeReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(resumeReceiver, filter)
        }
    }

    private fun unregisterResumeReceiver() {
        try {
            unregisterReceiver(resumeReceiver)
        } catch (_: Exception) {
        }
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