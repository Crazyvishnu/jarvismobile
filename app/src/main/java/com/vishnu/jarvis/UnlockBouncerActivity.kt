package com.vishnu.jarvis

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager

/**
 * Android 16 unlock strategy:
 *
 * This activity does NOT use showWhenLocked. When started while the device is locked,
 * it cannot display — but its onCreate still runs. Calling requestDismissKeyguard()
 * forces Android SystemUI to present its native bouncer (fingerprint/PIN/pattern).
 *
 * Once the user authenticates (even just touching the fingerprint sensor),
 * onDismissSucceeded fires and we immediately execute the pending voice command.
 *
 * On Android 16, the keyguard bouncer runs in a privileged secure window layer that
 * accessibility services CANNOT read or interact with. Therefore we do NOT attempt
 * automated PIN entry — we rely on the user's fingerprint or manual PIN input.
 */
class UnlockBouncerActivity : Activity() {

    private var pendingCommand: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure the screen is on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        pendingCommand = intent.getStringExtra("command")
            ?: JarvisAccessibilityService.pendingCommandToExecute

        Log.d(TAG, "onCreate — requesting native bouncer. Command: $pendingCommand")
        Log.d(VOSK_TAG, "[Bouncer] Requesting native SystemUI bouncer for: $pendingCommand")

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        if (!km.isKeyguardLocked) {
            Log.d(VOSK_TAG, "[Bouncer] Device already unlocked!")
            executeAndFinish()
            return
        }

        // Wake the screen to ensure the bouncer is visible
        wakeScreen()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    Log.d(VOSK_TAG, "[Bouncer] onDismissSucceeded — user authenticated! Executing command...")
                    executeAndFinish()
                }

                override fun onDismissError() {
                    Log.w(VOSK_TAG, "[Bouncer] onDismissError — SystemUI could not show bouncer")
                    // Try again after a short delay
                    handler.postDelayed({
                        Log.d(VOSK_TAG, "[Bouncer] Retrying requestDismissKeyguard...")
                        val km2 = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                        if (km2.isKeyguardLocked) {
                            km2.requestDismissKeyguard(this@UnlockBouncerActivity, object : KeyguardManager.KeyguardDismissCallback() {
                                override fun onDismissSucceeded() {
                                    Log.d(VOSK_TAG, "[Bouncer] Retry succeeded! Executing command...")
                                    executeAndFinish()
                                }
                                override fun onDismissError() {
                                    Log.w(VOSK_TAG, "[Bouncer] Retry also failed. Finishing.")
                                    finish()
                                }
                                override fun onDismissCancelled() {
                                    Log.w(VOSK_TAG, "[Bouncer] Retry cancelled by user.")
                                    finish()
                                }
                            })
                        } else {
                            executeAndFinish()
                        }
                    }, 1000)
                }

                override fun onDismissCancelled() {
                    Log.w(VOSK_TAG, "[Bouncer] onDismissCancelled — user cancelled or bouncer timed out")
                    finish()
                }
            })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("command")?.let { pendingCommand = it }
    }

    override fun onResume() {
        super.onResume()
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked) {
            Log.d(VOSK_TAG, "[Bouncer] onResume — device unlocked!")
            executeAndFinish()
        }
    }

    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "Jarvis:BouncerWake"
                )
                wl.acquire(5000L)
                wl.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wake screen: ${e.message}")
        }
    }

    private fun executeAndFinish() {
        val cmd = pendingCommand ?: JarvisAccessibilityService.pendingCommandToExecute
        if (!cmd.isNullOrEmpty()) {
            Log.d(VOSK_TAG, "[Bouncer] Launching command: $cmd")
            val execIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("execute_unlocked_command", cmd)
            }
            startActivity(execIntent)
            JarvisAccessibilityService.pendingCommandToExecute = null
            JarvisAccessibilityService.shouldAutoUnlock = false
        } else {
            Log.w(VOSK_TAG, "[Bouncer] No pending command to execute")
        }
        finish()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val TAG = "UnlockBouncer"
        private const val VOSK_TAG = "VoskWakeWordSvc"
    }
}
