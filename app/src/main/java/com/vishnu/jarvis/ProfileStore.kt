package com.vishnu.jarvis

import android.content.Context
import org.json.JSONObject

// Plain local storage. No AI, no network - just key/value pairs on the
// phone, used to answer "what's my name/email/phone" when filling forms
// or messaging a contact by name.
object ProfileStore {

    private const val PREFS = "jarvis_profile"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getName(context: Context): String? = prefs(context).getString("name", null)
    fun getEmail(context: Context): String? = prefs(context).getString("email", null)
    fun getPhone(context: Context): String? = prefs(context).getString("phone", null)
    fun getAddress(context: Context): String? = prefs(context).getString("address", null)

    fun saveProfile(context: Context, name: String, email: String, phone: String, address: String) {
        prefs(context).edit()
            .putString("name", name)
            .putString("email", email)
            .putString("phone", phone)
            .putString("address", address)
            .apply()
    }

    // Contacts map: first name (lowercase) -> phone number, e.g. {"basith": "9198xxxxxxx"}
    // Used only for the "message <name>" command since WhatsApp needs a number, not a name.
    fun saveContact(context: Context, name: String, phone: String) {
        val json = JSONObject(prefs(context).getString("contacts", "{}")!!)
        json.put(name.lowercase(), phone)
        prefs(context).edit().putString("contacts", json.toString()).apply()
    }

    fun getContactNumber(context: Context, name: String): String? {
        val json = JSONObject(prefs(context).getString("contacts", "{}")!!)
        return if (json.has(name.lowercase())) json.getString(name.lowercase()) else null
    }

    // API keys for the "search + play" features. Stored the same way as
    // everything else here - plain SharedPreferences, no network involved
    // in storing them.
    fun getYoutubeApiKey(context: Context): String? = prefs(context).getString("youtube_api_key", null)
    fun setYoutubeApiKey(context: Context, key: String) {
        prefs(context).edit().putString("youtube_api_key", key).apply()
    }

    fun getSpotifyClientId(context: Context): String? = prefs(context).getString("spotify_client_id", null)
    fun getSpotifyClientSecret(context: Context): String? = prefs(context).getString("spotify_client_secret", null)
    fun setSpotifyCredentials(context: Context, clientId: String, clientSecret: String) {
        prefs(context).edit()
            .putString("spotify_client_id", clientId)
            .putString("spotify_client_secret", clientSecret)
            .apply()
    }

    fun getGeminiApiKey(context: Context): String? = prefs(context).getString("gemini_api_key", null)
    fun setGeminiApiKey(context: Context, key: String) {
        prefs(context).edit().putString("gemini_api_key", key).apply()
    }

    fun getOpenAiKey(context: Context): String? = prefs(context).getString("openai_key", null)
    fun setOpenAiKey(context: Context, key: String) {
        prefs(context).edit().putString("openai_key", key).apply()
    }

    fun getGroqKey(context: Context): String? = prefs(context).getString("groq_key", null)
    fun setGroqKey(context: Context, key: String) {
        prefs(context).edit().putString("groq_key", key).apply()
    }

    fun getWeatherApiKey(context: Context): String? = prefs(context).getString("weather_api_key", null)
    fun setWeatherApiKey(context: Context, key: String) {
        prefs(context).edit().putString("weather_api_key", key).apply()
    }

    fun getMapsApiKey(context: Context): String? = prefs(context).getString("maps_api_key", null)
    fun setMapsApiKey(context: Context, key: String) {
        prefs(context).edit().putString("maps_api_key", key).apply()
    }

    fun getElevenLabsKey(context: Context): String? = prefs(context).getString("eleven_labs_key", null)
    fun setElevenLabsKey(context: Context, key: String) {
        prefs(context).edit().putString("eleven_labs_key", key).apply()
    }

    // Device PIN for auto-unlock (stored locally, used by accessibility service
    // to auto-enter the PIN when executing commands from lock screen)
    fun getDevicePin(context: Context): String? = prefs(context).getString("device_pin", null)
    fun setDevicePin(context: Context, pin: String) {
        prefs(context).edit().putString("device_pin", pin).apply()
    }
}