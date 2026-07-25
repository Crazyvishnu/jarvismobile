package com.vishnu.jarvis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

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
val youtubeApi = findViewById<EditText>(R.id.youtubeApiKeyInput)

val spotifyId = findViewById<EditText>(R.id.spotifyClientIdInput)

val spotifySecret = findViewById<EditText>(R.id.spotifyClientSecretInput)

        // Pre-fill with whatever is already saved
        val prefs = getSharedPreferences("jarvis_profile", Context.MODE_PRIVATE)
        name.setText(prefs.getString("name", ""))
        email.setText(prefs.getString("email", ""))
        phone.setText(prefs.getString("phone", ""))
        address.setText(prefs.getString("address", ""))
        mailPassword.setText(prefs.getString("mail_app_password", ""))
        youtubeApi.setText(
            ProfileStore.getYoutubeApiKey(this)
        )

        spotifyId.setText(
            ProfileStore.getSpotifyClientId(this)
        )

        spotifySecret.setText(
            ProfileStore.getSpotifyClientSecret(this)
        )

        findViewById<Button>(R.id.saveProfileButton).setOnClickListener {
            ProfileStore.saveProfile(
                this, name.text.toString(), email.text.toString(),
                phone.text.toString(), address.text.toString()
            )
            prefs.edit().putString("mail_app_password", mailPassword.text.toString()).apply()
            ProfileStore.setYoutubeApiKey(
                this,
                youtubeApi.text.toString().trim()
            )

            ProfileStore.setSpotifyCredentials(
                this,
                spotifyId.text.toString().trim(),
                spotifySecret.text.toString().trim()
            )
            Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.apiKeysButton).setOnClickListener {
            startActivity(Intent(this, ApiKeysActivity::class.java))
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
}
