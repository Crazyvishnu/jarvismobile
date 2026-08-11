package com.vishnu.jarvis

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
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
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.concurrent.thread

/**
 * Vosk-based wake word detection service.
 * Uses Vosk's grammar-restricted recognizer for accurate keyword spotting.
 * The wake word is customizable via SharedPreferences.
 */
class VoskWakeWordService : Service() {

    private var audioRecord: AudioRecord? = null
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var listeningForWakeWord = true
    @Volatile private var micThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var speechRecognizer: SpeechRecognizerManager? = null

    companion object {
        private const val TAG = "VoskWakeWordSvc"
        private const val NOTIF_ID = 3
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE = 4096 // bytes (2048 samples @ 16-bit)

        const val ACTION_CHAT_UPDATE = "com.vishnu.jarvis.ACTION_CHAT_UPDATE"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_TEXT = "text"
        const val ACTION_WAKE_WORD_RESUME = "com.vishnu.jarvis.ACTION_WAKE_WORD_RESUME"

        /** SharedPreferences key for the custom wake word */
        const val PREF_WAKE_WORD = "wake_word"
        const val DEFAULT_WAKE_WORD = "hey jarvis"

        fun getWakeWord(context: Context): String {
            val prefs = context.getSharedPreferences("jarvis_profile", Context.MODE_PRIVATE)
            return prefs.getString(PREF_WAKE_WORD, DEFAULT_WAKE_WORD) ?: DEFAULT_WAKE_WORD
        }

        fun setWakeWord(context: Context, wakeWord: String) {
            context.getSharedPreferences("jarvis_profile", Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_WAKE_WORD, wakeWord.lowercase().trim())
                .apply()
        }
    }

    private val resumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Resume broadcast received — re-enabling wake word detection")
            resumeWakeWordDetection()
        }
    }

    // === PAYMENT GUARD: Receiver + Foreground Monitor ===
    private val paymentGuardReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val paymentPkg = intent?.getStringExtra("payment_package") ?: return
            Log.d(TAG, "[PaymentGuard] Received guard activation for: $paymentPkg")
            startPaymentAppMonitoring(paymentPkg)
        }
    }
    @Volatile private var paymentGuardPolling = false

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

            enableBluetoothMic()

            // Initialize Vosk model
            val modelPath = copyModelToStorage()
            voskModel = Model(modelPath)
            initRecognizer()

            acquireWakeLock()
            registerResumeReceiver()
            registerPaymentGuardReceiver()
            startProactivePaymentGuard()

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

            val wakeWord = getWakeWord(this)
            Log.d(TAG, "Vosk listening for '$wakeWord' (keyword spotting active)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Vosk wake word service", e)
            Toast.makeText(this, "Wake word engine failed: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    /**
     * Initialize (or re-initialize) the Vosk recognizer with the current wake word grammar.
     */
    private fun initRecognizer() {
        voskRecognizer?.close()
        val wakeWord = getWakeWord(this)
        // Build grammar JSON: the wake word + individual words from it + [unk] to ignore noise
        val words = mutableSetOf<String>()
        words.add(wakeWord)
        // Also add individual words so Vosk can match partial speech
        wakeWord.split(" ").forEach { words.add(it) }
        words.add("[unk]")
        val grammar = words.joinToString(", ") { "\"$it\"" }
        val grammarJson = "[$grammar]"
        Log.d(TAG, "Vosk grammar: $grammarJson")
        voskRecognizer = Recognizer(voskModel, SAMPLE_RATE.toFloat(), grammarJson)
    }

    /**
     * Copy the Vosk model from assets to internal storage (Vosk needs a filesystem path).
     */
    private fun copyModelToStorage(): String {
        val modelDir = File(filesDir, "vosk-model")
        if (modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true) {
            Log.d(TAG, "Vosk model already extracted at ${modelDir.absolutePath}")
            return modelDir.absolutePath
        }

        Log.d(TAG, "Extracting Vosk model from assets...")
        modelDir.mkdirs()

        // Copy all files/dirs from assets/model-en-us to internal storage
        copyAssetDir("model-en-us", modelDir)
        Log.d(TAG, "Vosk model extracted to ${modelDir.absolutePath}")
        return modelDir.absolutePath
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        val assetManager = assets
        val entries = assetManager.list(assetPath) ?: return

        if (entries.isEmpty()) {
            // It's a file, copy it
            assetManager.open(assetPath).use { input ->
                File(destDir, "").outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            // It's a directory
            destDir.mkdirs()
            for (entry in entries) {
                val childAsset = "$assetPath/$entry"
                val childDest = File(destDir, entry)
                val grandChildren = assetManager.list(childAsset)
                if (grandChildren != null && grandChildren.isNotEmpty()) {
                    copyAssetDir(childAsset, childDest)
                } else {
                    assetManager.open(childAsset).use { input ->
                        childDest.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Check if wake word was updated
        if (intent?.getBooleanExtra("refresh_wake_word", false) == true) {
            initRecognizer()
            val wakeWord = getWakeWord(this)
            Log.d(TAG, "Wake word updated to '$wakeWord'")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        stopMicLoop()
        paymentGuardPolling = false
        voskRecognizer?.close()
        voskModel?.close()
        speechRecognizer?.destroy()
        releaseWakeLock()
        unregisterResumeReceiver()
        disableBluetoothMic()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed — scheduling service restart")
        val restartIntent = Intent(applicationContext, VoskWakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    @Suppress("MissingPermission")
    private fun startMicLoop() {
        if (audioRecord == null) {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, CHUNK_SIZE * 2)

            // Attempt to open HOTWORD (1999) audio source first — on supported OS setups, this suppresses the green mic dot!
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val record = AudioRecord(
                        1999, // MediaRecorder.AudioSource.HOTWORD (hidden in standard SDK)
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize
                    )
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        audioRecord = record
                        Log.i(TAG, "Opened HOTWORD audio source for stealth background wake word listening.")
                    } else {
                        record.release()
                    }
                }
            } catch (_: Exception) {}

            // Fallback to standard VOICE_RECOGNITION if HOTWORD requires system privilege on this device
            if (audioRecord == null) {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
            }
            audioRecord?.startRecording()
        }

        if (micThread == null) {
            micThread = thread(start = true, name = "VoskWakeWordMic") {
                val buffer = ByteArray(CHUNK_SIZE)
                val wakeWord = getWakeWord(this)
                
                // 48KB circular audio buffer holding last ~1.5s of speech for Voice Match verification
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

                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) continue

                    // Copy bytes into rolling 16-bit PCM buffer for speaker biometrics
                    val numSamples = read / 2
                    for (i in 0 until numSamples) {
                        val sample = ((buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)).toShort()
                        rollingAudio[rollingIdx] = sample
                        rollingIdx = (rollingIdx + 1) % rollingAudio.size
                    }

                    val recognizer = voskRecognizer ?: continue

                    if (recognizer.acceptWaveForm(buffer, read)) {
                        // Final result
                        val resultJson = recognizer.result
                        val text = parseVoskResult(resultJson)
                        if (text.isNotEmpty()) {
                            Log.d(TAG, "Vosk final result: '$text'")
                            if (text.contains(wakeWord) || matchesWakeWord(text, wakeWord)) {
                                Log.d(TAG, "Wake word detected in text! ('$text' matched '$wakeWord').")
                                onWakeWordDetected()
                            }
                        }
                    } else {
                        // Partial result - check for wake word in real-time
                        val partialJson = recognizer.partialResult
                        val partial = parseVoskPartial(partialJson)
                        if (partial.isNotEmpty() && (partial.contains(wakeWord) || matchesWakeWord(partial, wakeWord))) {
                            Log.d(TAG, "Wake word detected in partial! ('$partial' matched '$wakeWord').")
                            onWakeWordDetected()
                        }
                    }
                }
            }
        }
    }


    /**
     * Flexible wake word matching that handles slight speech recognition variations.
     */
    private fun matchesWakeWord(text: String, wakeWord: String): Boolean {
        val t = text.lowercase().trim()
        val ww = wakeWord.lowercase().trim()
        
        // Exact match
        if (t == ww || t.contains(ww)) return true
        
        // Check if all words of the wake word appear in order in the text
        val wwWords = ww.split(" ")
        var searchStart = 0
        for (word in wwWords) {
            val idx = t.indexOf(word, searchStart)
            if (idx < 0) return false
            searchStart = idx + word.length
        }
        return true
    }

    private fun parseVoskResult(json: String): String {
        return try {
            JSONObject(json).optString("text", "").lowercase().trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseVoskPartial(json: String): String {
        return try {
            JSONObject(json).optString("partial", "").lowercase().trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun stopMicLoop() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) { }
        audioRecord = null
    }

    private fun onWakeWordDetected() {
        listeningForWakeWord = false
        stopMicLoop()

        handler.post {
            LockScreenWaker.wakeAndListen(this)
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

    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }

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

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) {
            @Suppress("DEPRECATION")
            val wl = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "Jarvis:WakeWordCommand"
            )
            wl.acquire(3000L)
        }

        if (isDeviceLocked() && needsKeyguardDismissed(command)) {
            Log.d(TAG, "Keyguard is locked — delegating to AccessibilityService: $command")
            if (JarvisAccessibilityService.instance != null) {
                JarvisAccessibilityService.shouldRelockAfterCommand = true
            }
            JarvisAccessibilityService.startAutoUnlock(command)
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

        handler.postDelayed({
            resumeWakeWordDetection()
        }, 5000)
    }

    private fun resumeWakeWordDetection() {
        if (listeningForWakeWord) return

        Log.d(TAG, "Resuming wake word detection")
        handler.removeCallbacksAndMessages(null)
        voskRecognizer?.reset()
        startMicLoop()
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
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::VoskWakeWordLock")
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

    private fun registerPaymentGuardReceiver() {
        val filter = IntentFilter("com.vishnu.jarvis.PAYMENT_GUARD_ACTIVATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(paymentGuardReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(paymentGuardReceiver, filter)
        }
    }

    /**
     * PROACTIVE PAYMENT GUARD
     * 
     * Continuously polls the foreground app every 1 second.
     * When a payment/banking app comes to foreground:
     *   → Immediately disables accessibility service via Settings.Secure
     *   → Payment app's security check finds NO accessibility services → no alert!
     * When the user leaves the payment app:
     *   → Re-enables accessibility service automatically
     *   
     * This proactive approach catches apps like YONO SBI that check during their
     * splash screen, BEFORE the accessibility service even receives a window event.
     */
    private fun startProactivePaymentGuard() {
        Log.d(TAG, "[PaymentGuard] Starting proactive background monitor (every 1s)")
        
        val guardPoller = object : Runnable {
            override fun run() {
                try {
                    val foregroundPkg = getForegroundPackage()
                    
                    if (foregroundPkg != null && PaymentAppGuard.isPaymentApp(foregroundPkg)) {
                        // Payment app is in foreground — make sure accessibility is OFF
                        if (!PaymentAppGuard.isGuardActive) {
                            Log.d(TAG, "[PaymentGuard] ⚡ Payment app detected: $foregroundPkg — disabling NOW!")
                            PaymentAppGuard.guardTriggerPackage = foregroundPkg
                            PaymentAppGuard.disableAccessibilityService(this@VoskWakeWordService)
                        }
                    } else {
                        // No payment app in foreground — make sure accessibility is ON
                        if (PaymentAppGuard.isGuardActive) {
                            Log.d(TAG, "[PaymentGuard] Payment app gone (foreground: $foregroundPkg) — re-enabling service!")
                            PaymentAppGuard.enableAccessibilityService(this@VoskWakeWordService)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[PaymentGuard] Poll error: ${e.message}")
                }
                
                // Poll every 100ms — fast enough to disable before any banking app's
                // security check fires (which typically runs within 300-500ms of launch)
                handler.postDelayed(this, 100)
            }
        }
        // Start immediately (0ms delay) — BHIM and other banking apps run their
        // security check within 200ms of launch, so the guard must already be
        // running before any payment app opens.
        handler.post(guardPoller)
    }

    /**
     * Reactive handler: when the accessibility service detects a payment app and
     * broadcasts, this provides an immediate backup disable.
     */
    private fun startPaymentAppMonitoring(paymentPackage: String) {
        Log.d(TAG, "[PaymentGuard] Reactive broadcast received for: $paymentPackage")
        // The proactive poller already handles everything.
        // But as a safety net, ensure the service is disabled right now:
        if (!PaymentAppGuard.isGuardActive) {
            PaymentAppGuard.guardTriggerPackage = paymentPackage
            PaymentAppGuard.disableAccessibilityService(this)
        }
    }

    /**
     * Uses UsageStatsManager to determine which app is currently in the foreground.
     * Requires PACKAGE_USAGE_STATS permission (Settings > Apps > Special access > Usage access).
     */
    private fun getForegroundPackage(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 10_000, // last 10 seconds
                now
            )
            if (stats.isNullOrEmpty()) return null

            // Return the app with the most recent lastTimeUsed
            stats.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (e: Exception) {
            null // Silently fail — don't spam logs every second
        }
    }

    private fun unregisterResumeReceiver() {
        try {
            unregisterReceiver(resumeReceiver)
        } catch (_: Exception) { }
        try {
            unregisterReceiver(paymentGuardReceiver)
        } catch (_: Exception) { }
    }

    private fun buildNotification(): Notification {
        val channelId = "jarvis_wake_vosk"
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
        val wakeWord = getWakeWord(this)
        return builder
            .setContentTitle("Jarvis")
            .setContentText("Say \"$wakeWord\" to wake me up")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}
