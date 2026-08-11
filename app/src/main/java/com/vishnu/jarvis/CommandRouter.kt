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
class CommandRouter(private val context: Context, private val onReply: (String, Boolean) -> Unit) {
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

    companion object {
        var pendingActionType: String? = null // "whatsapp_msg", "call", "whatsapp_call"
        var pendingActionData: String? = null
        var pendingContactMatches: List<Pair<String, String>>? = null
        var pendingTimestamp: Long = 0
    }

    private fun reply(message: String, needsResponse: Boolean = false) {
        onReply(message, needsResponse)
    }

    fun process(text: String) {

        if (bareWakeWord.matches(text.lowercase().trim())) {
            reply("Yes Vishnu?", false)
            return
        }
        
        if (pendingContactMatches != null && pendingActionType != null) {
            if (System.currentTimeMillis() - pendingTimestamp > 60_000) {
                pendingContactMatches = null
                pendingActionType = null
                pendingActionData = null
            } else {
                handleContactDisambiguation(text)
                return
            }
        }

        // Try Gemini planner first
        com.vishnu.jarvis.agent.GeminiPlanner.plan(text, context) { result ->
            if (result != null) {
                // Gemini successfully parsed it - switch to main thread to execute UI/intent logic
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    com.vishnu.jarvis.agent.IntentExecutor.execute(context, result, ::reply)
                }
            } else {
                // Gemini failed, timed out, or no API key set - fallback to local offline routing
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    handleSingle(text.lowercase())
                }
            }
        }
    }
    
    private fun handleContactDisambiguation(text: String) {
        val matches = pendingContactMatches!!
        val actionType = pendingActionType!!
        val actionData = pendingActionData
        
        val choice = text.lowercase()
        if ("cancel" in choice || "stop" in choice || "nevermind" in choice) {
            pendingContactMatches = null
            pendingActionType = null
            pendingActionData = null
            reply("Cancelled.", false)
            return
        }
        
        val normalizedChoice = choice.replace(Regex("[^a-z0-9]"), "")
        val chosen = matches.find { 
            val normalizedName = it.first.lowercase().replace(Regex("[^a-z0-9]"), "")
            normalizedName.contains(normalizedChoice) || normalizedChoice.contains(normalizedName)
        }

        if (chosen != null) {
            pendingContactMatches = null
            pendingActionType = null
            pendingActionData = null
            when (actionType) {
                "whatsapp_msg" -> {
                    val ok = JarvisAccessibilityService.instance?.sendWhatsAppMessage(context, chosen.second, actionData!!)
                    reply(if (ok == true) "Sending message to ${chosen.first}" else "Accessibility service not enabled.", false)
                }
                "call" -> {
                    val intent = Intent(Intent.ACTION_CALL).apply {
                        data = Uri.parse("tel:${chosen.second}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    reply("Calling ${chosen.first}", false)
                }
                "whatsapp_call" -> {
                    val ok = JarvisAccessibilityService.instance?.makeWhatsAppCall(context, chosen.second)
                    reply(if (ok == true) "Calling ${chosen.first} on WhatsApp" else "Accessibility service not enabled.", false)
                }
            }
        } else {
            val isNewCommand = choice.startsWith("open ") || choice.startsWith("play ") || 
                               choice.startsWith("turn ") || choice.startsWith("send ") || 
                               choice.startsWith("say ") || choice.startsWith("tell ") || 
                               choice.startsWith("message ") || choice.startsWith("call ") ||
                               choice == "what time is it"
            
            if (isNewCommand) {
                pendingContactMatches = null
                pendingActionType = null
                pendingActionData = null
                process(choice)
                return
            }

            reply("I didn't catch that. Please say one of: " + matches.joinToString(", ") { it.first }, true)
        }
    }

    // Runs steps one at a time (not all at once) so a spoken command like
    // "open youtube, search ayyappa songs, play first song" resolves to a
    // single YoutubeSearchAndPlay action instead of three separate ones.
    // Kept as a loop-with-callback because the search actions are async.
    private fun executeStep(plan: List<PlannedAction>, index: Int) {
        if (index >= plan.size) return
        when (val action = plan[index]) {
            is PlannedAction.YoutubeSearchAndPlay -> {
                reply("Searching YouTube for ${action.query}...", false)
                youtube.searchAndPlayFirst(action.query) { _, message ->
                    reply(message, false)
                    executeStep(plan, index + 1)
                }
            }
            is PlannedAction.SpotifySearchAndPlay -> {
                reply("Searching Spotify for ${action.query}...", false)
                spotify.searchAndPlayFirst(action.query) { _, message ->
                    reply(message, false)
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
                reply("Opening YouTube", false)
            }

            t == "open chrome" -> {
                media.openChrome()
                reply("Opening Chrome", false)
            }

            t == "open spotify" -> {
                media.openSpotify()
                reply("Opening Spotify", false)
            }

            t.startsWith("play ") && t.contains("youtube") -> {
                val song = t.removePrefix("play")
                    .substringBefore("on youtube")
                    .trim()
                youtube.searchAndPlayFirst(song) { _, message -> reply(message, false) }
            }

            "play" in t && "spotify" in t -> {
                val song = t.substringAfter("play")
                    .substringBefore("on spotify")
                    .trim()
                spotify.searchAndPlayFirst(song) { _, message -> reply(message, false) }
            }

            t.startsWith("search ") -> {
                val q = t.removePrefix("search").trim()
                media.searchGoogle(q)
                reply("Searching Google for $q", false)
            }

            t.startsWith("maps ") -> {
                val place = t.removePrefix("maps").trim()
                media.openMaps(place)
                reply("Opening Maps", false)
            }

            // --- Phone & Quick Settings ---
            "volume up" in t -> {
                phone.volumeUp()
                reply("Volume increased.", false)
            }

            "volume down" in t -> {
                phone.volumeDown()
                reply("Volume decreased.", false)
            }

            "mute" == t -> {
                phone.mute()
                reply("Muted.", false)
            }

            "open battery settings" in t -> {
                phone.openBatterySettings()
                reply("Opening battery settings.", false)
            }

            "open display settings" in t -> {
                phone.openBrightnessSettings()
                reply("Opening display settings.", false)
            }

            "open sound settings" in t -> {
                phone.openSoundSettings()
                reply("Opening sound settings.", false)
            }

            "open location settings" in t -> {
                phone.openLocationSettings()
                reply("Opening location settings.", false)
            }

            // --- Conversational & Info Commands ---
            t == "hi" || t == "hello" || t == "hey" -> {
                reply("Hello Vishnu! How can I help you?", false)
            }

            "good morning" in t -> {
                reply("Good morning Vishnu! Hope you have an amazing day.", false)
            }

            "good night" in t -> {
                reply("Good night Vishnu. Sleep well.", false)
            }

            "who are you" in t -> {
                reply("I am Jarvis. Your personal AI assistant.", false)
            }

            "thank" in t -> {
                reply("You're welcome.", false)
            }

            "time" in t -> {
                val current = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                reply("Current time is $current", false)
            }

            "date" in t -> {
                val current = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date())
                reply(current, false)
            }

            "battery" in t -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                reply("Battery is $level percent", false)
            }

            // --- App & System Actions ---
            "fill" in t && "form" in t -> {
                val ok = JarvisAccessibilityService.instance?.fillCurrentFormWithProfile(context)
                reply(if (ok == true) "Filling the form." else "Enable accessibility first.", false)
            }

            ("whatsapp" in t) && ("new" in t || "unread" in t || "msg" in t) -> {
                reply(NotificationCaptureService.whatsAppMessages(), false)
            }

            t.startsWith("say ") || t.startsWith("tell ") || t.startsWith("message ") || t.startsWith("send ") -> {
                // Parse pattern: "say [message] to [contact]"
                val match = Regex("^(?:say|tell|message|send)\\s+(.+?)\\s+to\\s+(.+)$").find(t)
                if (match != null) {
                    val msg = match.groupValues[1].trim()
                    val contact = match.groupValues[2].trim()
                    val matches = ContactHelper.findContactMatches(context, contact)
                    
                    if (matches.isEmpty()) {
                        reply("I couldn't find a contact matching $contact.", false)
                    } else if (matches.size == 1) {
                        val ok = JarvisAccessibilityService.instance?.sendWhatsAppMessage(context, matches[0].second, msg)
                        reply(if (ok == true) "Sending message to ${matches[0].first}" else "Accessibility service not enabled.", false)
                    } else {
                        // Multiple matches!
                        pendingActionType = "whatsapp_msg"
                        pendingActionData = msg
                        pendingContactMatches = matches
                        pendingTimestamp = System.currentTimeMillis()
                        val names = matches.joinToString(", ") { it.first }
                        reply("I found multiple matches. Do you mean $names?", true)
                    }
                } else {
                    reply("I didn't catch the message and contact. Try saying 'say hi to John' or 'send come tomorrow to play to mom'.", false)
                }
            }

            t.startsWith("call ") -> {
                val match = Regex("^call\\s+(.+)$").find(t)
                if (match != null) {
                    var contact = match.groupValues[1].trim()
                    val isWhatsApp = contact.endsWith(" by whatsapp") || contact.endsWith(" on whatsapp")
                    if (isWhatsApp) {
                        contact = contact.replace(" by whatsapp", "").replace(" on whatsapp", "").trim()
                    }
                    
                    val matches = ContactHelper.findContactMatches(context, contact)
                    
                    if (matches.isEmpty()) {
                        reply("I couldn't find a contact matching $contact.", false)
                    } else if (matches.size == 1) {
                        if (isWhatsApp) {
                            val ok = JarvisAccessibilityService.instance?.makeWhatsAppCall(context, matches[0].second)
                            reply(if (ok == true) "Calling ${matches[0].first} on WhatsApp" else "Accessibility service not enabled.", false)
                        } else {
                            val intent = Intent(Intent.ACTION_CALL).apply {
                                data = Uri.parse("tel:${matches[0].second}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            reply("Calling ${matches[0].first}", false)
                        }
                    } else {
                        // Multiple matches!
                        pendingActionType = if (isWhatsApp) "whatsapp_call" else "call"
                        pendingActionData = null
                        pendingContactMatches = matches
                        pendingTimestamp = System.currentTimeMillis()
                        val names = matches.joinToString(", ") { it.first }
                        reply("I found multiple matches. Do you mean $names?", true)
                    }
                }
            }
            Regex("^(?:turn )?(on|off|of|open|close)\\s+(?:the )?(wifi|wi-fi|hotspot|flash|flashlight|bluetooth|mobile data|internet|data)$").matches(t) ||
            Regex("^(wifi|wi-fi|hotspot|flash|flashlight|bluetooth|mobile data|internet|data)\\s+(on|off|of|open|close)$").matches(t) -> {
                
                val match1 = Regex("^(?:turn )?(on|off|of|open|close)\\s+(?:the )?(wifi|wi-fi|hotspot|flash|flashlight|bluetooth|mobile data|internet|data)$").find(t)
                val match2 = Regex("^(wifi|wi-fi|hotspot|flash|flashlight|bluetooth|mobile data|internet|data)\\s+(on|off|of|open|close)$").find(t)
                
                val action = match1?.groupValues?.get(1) ?: match2?.groupValues?.get(2)!!
                val rawTarget = match1?.groupValues?.get(2) ?: match2?.groupValues?.get(1)!!
                
                val turnOn = action == "on" || action == "open"
                val target = rawTarget.replace("-", "")
                
                if (target == "flash" || target == "flashlight") {
                    try {
                        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                        val cameraId = cameraManager.cameraIdList[0]
                        cameraManager.setTorchMode(cameraId, turnOn)
                        reply("Turning ${if (turnOn) "on" else "off"} flashlight.", false)
                    } catch (e: Exception) {
                        reply("Failed to toggle flashlight.", false)
                    }
                } else {
                    val tileName = when (target) {
                        "wifi" -> "Wi-Fi"
                        "mobile data", "data" -> "Mobile data"
                        "hotspot" -> "Hotspot"
                        "bluetooth" -> "Bluetooth"
                        "internet" -> "Internet"
                        else -> target.replaceFirstChar { it.uppercase() }
                    }
                    val ok = JarvisAccessibilityService.instance?.toggleQuickSetting(tileName)
                    if (ok == true) {
                        reply("Toggling $tileName.", false)
                    } else {
                        reply("Accessibility service not enabled.", false)
                    }
                }
            }

            "unlock" == t || "unlock my phone" == t || "unlock phone" == t -> {
                reply("Phone unlocked.")
            }

            "lock" == t || "lock my phone" == t || "lock phone" == t || "lock the phone" == t -> {
                val ok = JarvisAccessibilityService.instance?.lockScreenGracefully()
                if (JarvisAccessibilityService.instance != null) {
                    reply("Phone locked.", false)
                } else {
                    reply("Accessibility service not enabled. I cannot lock the screen.", false)
                }
            }

            "summarize" in t && ("notification" in t || "panel" in t) -> {
                reply(NotificationCaptureService.summarize())
            }

            "turn off the mic" in t || "turn off mic" in t || "turn off the mike" in t || "turn off mike" in t || "stop listening" in t -> {
                context.stopService(Intent(context, OpenWakeWordService::class.java))
                reply("Microphone turned off. I will no longer listen for the wake word.", false)
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
}