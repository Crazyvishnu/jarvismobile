package com.vishnu.jarvis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class ProfileSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val name = findViewById<EditText>(R.id.nameInput)
        val email = findViewById<EditText>(R.id.emailInput)
        val phone = findViewById<EditText>(R.id.phoneInput)
        val address = findViewById<EditText>(R.id.addressInput)
        val mailPassword = findViewById<EditText>(R.id.mailPasswordInput)
        val contactName = findViewById<EditText>(R.id.contactNameInput)
        val contactPhone = findViewById<EditText>(R.id.contactPhoneInput)
        val wakeWordInput = findViewById<EditText>(R.id.wakeWordInput)

        // Pre-fill with whatever is already saved
        val prefs = getSharedPreferences("jarvis_profile", Context.MODE_PRIVATE)
        name.setText(prefs.getString("name", ""))
        email.setText(prefs.getString("email", ""))
        phone.setText(prefs.getString("phone", ""))
        address.setText(prefs.getString("address", ""))
        mailPassword.setText(prefs.getString("mail_app_password", ""))
        wakeWordInput.setText(VoskWakeWordService.getWakeWord(this))

        findViewById<Button>(R.id.saveProfileButton).setOnClickListener {
            ProfileStore.saveProfile(
                this, name.text.toString(), email.text.toString(),
                phone.text.toString(), address.text.toString()
            )
            prefs.edit().putString("mail_app_password", mailPassword.text.toString()).apply()

            // Save wake word and notify service
            val newWakeWord = wakeWordInput.text.toString().trim().lowercase()
            if (newWakeWord.isNotEmpty()) {
                VoskWakeWordService.setWakeWord(this, newWakeWord)
                val refreshIntent = Intent(this, VoskWakeWordService::class.java)
                refreshIntent.putExtra("refresh_wake_word", true)
                try { startService(refreshIntent) } catch (_: Exception) {}
            }
            Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show()
        }



        // API Keys button -- require biometric or device PIN before opening
        findViewById<Button>(R.id.apiKeysButton).setOnClickListener {
            authenticateAndOpenApiKeys()
        }

        findViewById<Button>(R.id.saveContactButton).setOnClickListener {
            val n = contactName.text.toString().trim()
            val p = contactPhone.text.toString().trim()
            if (n.isNotEmpty() && p.isNotEmpty()) {
                ProfileStore.saveContact(this, n, p)
                Toast.makeText(this, "Saved $n", Toast.LENGTH_SHORT).show()
                contactName.setText("")
                contactPhone.setText("")
            }
        }
    }

    /**
     * Show a biometric / device credential prompt before opening ApiKeysActivity.
     * Falls back to device PIN/pattern/password if biometrics are not enrolled.
     * If no secure lock screen is set at all, opens directly with a warning toast.
     */
    private fun authenticateAndOpenApiKeys() {
        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(this)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    startActivity(Intent(this@ProfileSettingsActivity, ApiKeysActivity::class.java))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        Toast.makeText(
                            this@ProfileSettingsActivity,
                            "Authentication failed: $errString",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Biometric not recognised -- prompt stays open, Android handles retry
                }
            }

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Verify your identity")
                .setSubtitle("Use fingerprint or device PIN to access API Keys")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            BiometricPrompt(this, executor, callback).authenticate(promptInfo)
        } else {
            // No secure lock screen -- open directly with a warning
            Toast.makeText(this, "Set a screen lock to protect your API keys", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, ApiKeysActivity::class.java))
        }
    }
}
