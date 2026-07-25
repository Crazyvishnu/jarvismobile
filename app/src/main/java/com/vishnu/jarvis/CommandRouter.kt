package com.vishnu.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.*
import com.vishnu.jarvis.agent.AgentExecutor
import com.vishnu.jarvis.agent.AgentPlanner

// Takes plain text (from voice OR chat), plans it into one or more steps,
// then executes each step in order. Single commands ("open chrome") still
// go straight through handleSingle() exactly as before - only "search and
// play" style commands go through the new controllers.
class CommandRouter(private val context: Context, private val reply: (String) -> Unit) {
    private val appController = AppController(context)
    private val phone = PhoneController(context)
    private val media = MediaController(context)
    private val reminder = ReminderController(context)
    private val memory = MemoryStore
    private val youtube = YoutubeController(context)
    private val spotify = SpotifyController(context)
     private val executor = AgentExecutor()

    // Matches the wake word on its own (with or without "hey"), e.g.
    // "jarvis" or "hey jarvis" typed/spoken with no command attached.
    // Without this, that phrase fell through every branch in handleSingle()
    // and landed on "Sorry, I don't understand that command yet."
    private val bareWakeWord = Regex("^(hey )?jarvis[.,!? ]*$")

    fun process(text: String) {

        if (bareWakeWord.matches(text.lowercase().trim())) {
            reply("Yes Vishnu?")
            return
        }

        val plan = AgentPlanner.plan(text)

        if (plan.actions.isNotEmpty()) {

            executor.execute(plan) {
                reply(it)
            }

            return
        }

        // Fallback to the old command system
        handleSingle(text.lowercase())
    }

    // Runs steps one at a time (not all at once) so a spoken command like
    // "open youtube, search ayyappa songs, play first song" resolves to a
    // single YoutubeSearchAndPlay action instead of three separate ones.
    // Kept as a loop-with-callback because the search actions are async.
    private fun executeStep(plan: List<PlannedAction>, index: Int) {
        if (index >= plan.size) return
        when (val action = plan[index]) {
            is PlannedAction.YoutubeSearchAndPlay -> {
                reply("Searching YouTube for ${action.query}...")
                youtube.searchAndPlayFirst(action.query) { _, message ->
                    reply(message)
                    executeStep(plan, index + 1)
                }
            }
            is PlannedAction.SpotifySearchAndPlay -> {
                reply("Searching Spotify for ${action.query}...")
                spotify.searchAndPlayFirst(action.query) { _, message ->
                    reply(message)
                    executeStep(plan, index + 1)
                }
            }
            is PlannedAction.Raw -> {
                handleSingle(action.text)
                executeStep(plan, index + 1)
            }
        }
    }

    // Everything that used to live directly in process() - unchanged logic,
    // just now handles one already-lowercased single command at a time.
    private fun handleSingle(t: String) {
        when {
            // --- Specific Media Commands ---
            t == "open youtube" -> {
                media.openYoutube()
                reply("Opening YouTube")
            }

            t == "open chrome" -> {
                media.openChrome()
                reply("Opening Chrome")
            }

            t == "open spotify" -> {
                media.openSpotify()
                reply("Opening Spotify")
            }

            t.startsWith("play ") && t.contains("youtube") -> {
                val song = t.removePrefix("play")
                    .substringBefore("on youtube")
                    .trim()
                youtube.searchAndPlayFirst(song) { _, message -> reply(message) }
            }

            "play" in t && "spotify" in t -> {
                val song = t.substringAfter("play")
                    .substringBefore("on spotify")
                    .trim()
                spotify.searchAndPlayFirst(song) { _, message -> reply(message) }
            }

            t.startsWith("search ") -> {
                val q = t.removePrefix("search").trim()
                media.searchGoogle(q)
                reply("Searching Google for $q")
            }

            t.startsWith("maps ") -> {
                val place = t.removePrefix("maps").trim()
                media.openMaps(place)
                reply("Opening Maps")
            }

            // --- Phone & Quick Settings ---
            "flash on" in t -> {
                if (phone.flashlight(true)) reply("Flashlight turned on.")
                else reply("Unable to turn on flashlight.")
            }

            "flash off" in t -> {
                if (phone.flashlight(false)) reply("Flashlight turned off.")
                else reply("Unable to turn off flashlight.")
            }

            "volume up" in t -> {
                phone.volumeUp()
                reply("Volume increased.")
            }

            "volume down" in t -> {
                phone.volumeDown()
                reply("Volume decreased.")
            }

            "mute" == t -> {
                phone.mute()
                reply("Muted.")
            }

            "open bluetooth" in t -> {
                phone.openBluetoothSettings()
                reply("Opening Bluetooth settings.")
            }

            "open wifi" in t -> {
                phone.openWifiSettings()
                reply("Opening Wi-Fi settings.")
            }

            "open hotspot" in t -> {
                phone.openHotspotSettings()
                reply("Opening hotspot settings.")
            }

            "open battery settings" in t -> {
                phone.openBatterySettings()
                reply("Opening battery settings.")
            }

            "open display settings" in t -> {
                phone.openBrightnessSettings()
                reply("Opening display settings.")
            }

            "open sound settings" in t -> {
                phone.openSoundSettings()
                reply("Opening sound settings.")
            }

            "open location settings" in t -> {
                phone.openLocationSettings()
                reply("Opening location settings.")
            }

            // --- Conversational & Info Commands ---
            t == "hi" || t == "hello" || t == "hey" -> {
                reply("Hello Vishnu! How can I help you?")
            }

            "good morning" in t -> {
                reply("Good morning Vishnu! Hope you have an amazing day.")
            }

            "good night" in t -> {
                reply("Good night Vishnu. Sleep well.")
            }

            "who are you" in t -> {
                reply("I am Jarvis. Your personal AI assistant.")
            }

            "thank" in t -> {
                reply("You're welcome.")
            }

            "time" in t -> {
                val current = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                reply("Current time is $current")
            }

            "date" in t -> {
                val current = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date())
                reply(current)
            }

            "battery" in t -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                reply("Battery is $level percent")
            }

            // --- App & System Actions ---
            "fill" in t && "form" in t -> {
                val ok = JarvisAccessibilityService.instance?.fillCurrentFormWithProfile(context)
                reply(if (ok == true) "Filling the form." else "Enable accessibility first.")
            }

            ("whatsapp" in t) && ("new" in t || "unread" in t || "msg" in t) -> {
                reply(NotificationCaptureService.whatsAppMessages())
            }

            "say hi to" in t || "message" in t || "tell" in t -> {
                val contact = extractContact(t)
                val msg = extractMessage(t)
                val ok = JarvisAccessibilityService.instance?.sendWhatsAppMessage(context, contact, msg)
                reply(if (ok == true) "Sending message to $contact" else "Accessibility service not enabled.")
            }

            "summarize" in t && ("notification" in t || "panel" in t) -> {
                reply(NotificationCaptureService.summarize())
            }

            "mail" in t || "email" in t -> {
                checkMail()
            }

            t.startsWith("open ") -> {
                val app = t.removePrefix("open ").trim()
                if (appController.openApp(app)) {
                    reply("Opening $app")
                } else {
                    reply("I couldn't find $app")
                }
            }

            "help" in t -> {
                reply(
                    """
                    You can ask me:
                    Open Chrome
                    Open WhatsApp
                    Open YouTube
                    Open Spotify
                    Search AI News
                    Play Believer on YouTube
                    Open youtube, search believer, play first song
                    Battery
                    Time
                    Date
                    Summarize notifications
                    Check email
                    Fill form
                    """.trimIndent()
                )
            }

            t.startsWith("remind me") -> {
                val regex = Regex("remind me (.+) in (\\d+) minute")
                val match = regex.find(t)
                if (match != null) {
                    val message = match.groupValues[1]
                    val minutes = match.groupValues[2].toInt()
                    reminder.remindIn(minutes, message)
                    reply("Reminder set for $minutes minutes.")
                } else {
                    reply("Try: remind me drink water in 30 minutes")
                }
            }

            t.startsWith("remember ") -> {
                val data = t.removePrefix("remember").trim()
                if (" is " in data) {
                    val key = data.substringBefore(" is ").trim()
                    val value = data.substringAfter(" is ").trim()
                    memory.remember(context, key, value)
                    reply("I'll remember that.")
                } else {
                    reply("Example:\nRemember wifi password is abc123")
                }
            }

            t.startsWith("what is ") -> {
                val key = t.removePrefix("what is ").trim()
                val value = memory.recall(context, key)
                if (value != null) reply("$key is $value")
                else reply("I don't know that yet.")
            }

            t.startsWith("forget ") -> {
                val key = t.removePrefix("forget ").trim()
                memory.forget(context, key)
                reply("Forgot $key.")
            }

            t == "show memory" -> {
                val all = memory.getAll(context)
                if (all.isEmpty()) reply("Memory is empty.")
                else reply(all.entries.joinToString("\n") { "${it.key} = ${it.value}" })
            }

            else -> {
                reply("Sorry, I don't understand that command yet.")
            }
        }
    }

    private fun checkMail() {
        val prefs = context.getSharedPreferences("jarvis_profile", Context.MODE_PRIVATE)
        val email = prefs.getString("email", null)
        val appPassword = prefs.getString("mail_app_password", null)
        if (email == null || appPassword == null) {
            reply("Save your email and app password in Profile settings first.")
            return
        }
        Thread {
            val subjects = try {
                MailChecker.fetchUnreadSubjects("imap.gmail.com", email, appPassword)
            } catch (e: Exception) {
                listOf("Couldn't connect: ${e.message}")
            }
            reply(if (subjects.isEmpty()) "Inbox is clear." else summarizeBySender(subjects))
        }.start()
    }

    private fun summarizeBySender(entries: List<String>): String {
        val bySender = entries.groupBy { it.substringBefore(" - ") }
        return bySender.entries.joinToString("\n") { (sender, mails) ->
            val latestSubject = mails.last().substringAfter(" - ")
            "$sender (${mails.size}): $latestSubject"
        }
    }

    private fun extractContact(t: String): String {
        val after = when {
            "say hi to" in t -> t.substringAfter("say hi to")
            "message" in t -> t.substringAfter("message")
            "tell" in t -> t.substringAfter("tell")
            else -> ""
        }
        return after.trim().split(" ").firstOrNull().orEmpty()
    }

    private fun extractMessage(t: String): String {
        return when {
            "saying" in t -> t.substringAfter("saying").trim()
            "say hi to" in t -> "hi"
            else -> "hi"
        }
    }
}