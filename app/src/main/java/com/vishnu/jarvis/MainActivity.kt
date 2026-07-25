package com.vishnu.jarvis

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vishnu.jarvis.agent.PluginManager
import com.vishnu.jarvis.agent.YoutubePlugin
import com.vishnu.jarvis.agent.BrowserPlugin

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ChatAdapter
    private lateinit var router: CommandRouter
    private var isListenerRunning = false
    private val handler = Handler(Looper.getMainLooper())

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()

        if (spoken != null) {
            handleUserInput(spoken)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!audioGranted) {
            Toast.makeText(this, "Microphone permission is required for voice commands", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_main)
        PluginManager.register(YoutubePlugin(this))
        PluginManager.register(BrowserPlugin(this))
        checkPermissions()

        LockScreenWaker.ensureFullScreenIntentPermission(this)

        // Initialize Text To Speech
        TextToSpeechManager.init(this)

        val list = findViewById<RecyclerView>(R.id.messageList)
        adapter = ChatAdapter(mutableListOf("Jarvis: Hello Vishnu! What can I do for you?"))
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        router = CommandRouter(this) { reply ->
            runOnUiThread {
                adapter.addMessage("Jarvis: $reply")
                TextToSpeechManager.speak(reply)
            }
        }

        val input = findViewById<EditText>(R.id.inputBox)

        findViewById<ImageButton>(R.id.sendButton).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                handleUserInput(text)
                input.setText("")
            }
        }

        findViewById<ImageButton>(R.id.micButton).setOnClickListener {
            startVoiceCommand()
        }

        findViewById<ImageButton>(R.id.profileButton).setOnClickListener {
            startActivity(Intent(this, ProfileSettingsActivity::class.java))
        }

        val listenButton = findViewById<ImageButton>(R.id.listenToggleButton)
        listenButton.setOnClickListener {
            toggleWakeWordListener()
        }

        listenButton.setOnLongClickListener {
            openDndSettings()
            true
        }

        if (intent?.getBooleanExtra("from_wake_word", false) == true) {
            handleWakeWordTrigger()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("from_wake_word", false)) {
            handleWakeWordTrigger()
        }
    }

    private fun handleWakeWordTrigger() {
        dismissKeyguard()
        // Wait a bit for the screen to settle before speaking/listening
        handler.postDelayed({
            TextToSpeechManager.speak("Yes Vishnu?")
            handler.postDelayed({ startVoiceCommand() }, 1200)
        }, 500)
    }

    private fun dismissKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        }
    }

    private fun startVoiceCommand() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Jarvis")
            }
            try {
                speechLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Voice recognition not available", Toast.LENGTH_SHORT).show()
            }
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun toggleWakeWordListener() {
        val listenButton = findViewById<ImageButton>(R.id.listenToggleButton)
        if (!isListenerRunning) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                try {
                    startForegroundService(Intent(this, OpenWakeWordService::class.java))
                    listenButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    isListenerRunning = true
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to start listening: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        } else {
            stopService(Intent(this, OpenWakeWordService::class.java))
            listenButton.setImageResource(android.R.drawable.ic_btn_speak_now)
            isListenerRunning = false
        }
    }

    private fun openDndSettings() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Please grant Do Not Disturb access for silencing automation", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Do Not Disturb access already granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun handleUserInput(text: String) {
        adapter.addMessage("You: $text")
        router.process(text)
    }

    override fun onDestroy() {
        TextToSpeechManager.shutdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}