package com.vishnu.jarvis.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vishnu.jarvis.AppController
import com.vishnu.jarvis.JarvisAccessibilityService
import com.vishnu.jarvis.MediaController
import com.vishnu.jarvis.PhoneController
import com.vishnu.jarvis.ReminderController
import com.vishnu.jarvis.SpotifyController
import com.vishnu.jarvis.YoutubeController
import com.vishnu.jarvis.ContactHelper

object IntentExecutor {

    fun execute(context: Context, plan: NLUResult, reply: (String, Boolean) -> Unit) {
        val appController = AppController(context)
        val phone = PhoneController(context)
        val media = MediaController(context)
        val reminder = ReminderController(context)
        val youtube = YoutubeController(context)
        val spotify = SpotifyController(context)
        val accessibility = JarvisAccessibilityService.instance

        when (plan.intent) {
            "play_music" -> {
                val query = plan.query ?: ""
                when (plan.app) {
                    "spotify" -> {
                        reply("Searching Spotify for $query", false)
                        spotify.searchAndPlayFirst(query) { _, msg -> reply(msg, false) }
                    }
                    "youtube" -> {
                        reply("Searching YouTube for $query", false)
                        youtube.searchAndPlayFirst(query) { _, msg -> reply(msg, false) }
                    }
                    else -> {
                        // Default to Spotify, fall back to YouTube if it fails?
                        // For now just try Spotify
                        reply("Playing $query on Spotify", false)
                        spotify.searchAndPlayFirst(query) { _, msg -> reply(msg, false) }
                    }
                }
            }

            "open_app" -> {
                val targetApp = plan.app ?: plan.query ?: ""
                if (targetApp.isNotEmpty()) {
                    if (appController.openApp(targetApp)) {
                        reply("Opening $targetApp", false)
                    } else {
                        reply("I couldn't find $targetApp on your device.", false)
                    }
                } else {
                    reply("I'm not sure which app to open.", false)
                }
            }

            "search" -> {
                val query = plan.query ?: ""
                if (query.isNotEmpty()) {
                    media.searchGoogle(query)
                    reply("Searching Google for $query", false)
                } else {
                    reply("What would you like me to search for?", false)
                }
            }

            "call" -> {
                val contactName = plan.contact ?: ""
                if (contactName.isEmpty()) {
                    reply("Who would you like me to call?", false)
                    return
                }

                val matches = ContactHelper.findContactMatches(context, contactName)
                if (matches.isEmpty()) {
                    reply("I couldn't find a contact named $contactName.", false)
                } else if (matches.size == 1) {
                    if (plan.app == "whatsapp") {
                        val ok = accessibility?.makeWhatsAppCall(context, matches[0].second)
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
                    // Complex disambiguation dropped here for simplicity - fallback handles it
                    reply("I found multiple matches for $contactName. Please be more specific.", false)
                }
            }

            "send_message" -> {
                val contactName = plan.contact ?: ""
                val msg = plan.message ?: ""
                if (contactName.isEmpty() || msg.isEmpty()) {
                    reply("I need a message and a contact.", false)
                    return
                }
                
                val matches = ContactHelper.findContactMatches(context, contactName)
                if (matches.isEmpty()) {
                    reply("I couldn't find a contact named $contactName.", false)
                } else if (matches.size == 1) {
                    val ok = accessibility?.sendWhatsAppMessage(context, matches[0].second, msg)
                    reply(if (ok == true) "Sending message to ${matches[0].first}" else "Accessibility service not enabled.", false)
                } else {
                    reply("I found multiple matches for $contactName. Please be more specific.", false)
                }
            }

            "toggle_setting" -> {
                val setting = plan.setting ?: ""
                val isOn = plan.state == "on"
                
                if (setting == "flashlight") {
                    try {
                        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                        val cameraId = cameraManager.cameraIdList[0]
                        cameraManager.setTorchMode(cameraId, isOn)
                        reply("Turning ${if (isOn) "on" else "off"} flashlight.", false)
                    } catch (e: Exception) {
                        reply("Failed to toggle flashlight.", false)
                    }
                } else {
                    val tileName = when (setting) {
                        "wifi" -> "Wi-Fi"
                        "mobile_data" -> "Mobile data"
                        "hotspot" -> "Hotspot"
                        "bluetooth" -> "Bluetooth"
                        else -> setting.replaceFirstChar { it.uppercase() }
                    }
                    val ok = accessibility?.toggleQuickSetting(tileName)
                    if (ok == true) {
                        reply("Toggling $tileName.", false)
                    } else {
                        reply("Accessibility service not enabled.", false)
                    }
                }
            }

            "reminder" -> {
                val msg = plan.message ?: "Reminder"
                val mins = plan.minutes ?: 1
                reminder.remindIn(mins, msg)
                reply("Reminder set for $mins minutes.", false)
            }

            "volume" -> {
                if (plan.direction == "up") {
                    phone.volumeUp()
                    reply("Volume increased.", false)
                } else if (plan.direction == "down") {
                    phone.volumeDown()
                    reply("Volume decreased.", false)
                } else if (plan.state == "off") {
                    phone.mute()
                    reply("Muted.", false)
                } else {
                    reply("Adjusting volume.", false)
                }
            }

            "info_query" -> {
                when (plan.query) {
                    "time" -> {
                        val current = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                        reply("Current time is $current", false)
                    }
                    "date" -> {
                        val current = java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date())
                        reply("Today is $current", false)
                    }
                    "battery" -> {
                        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        reply("Battery is $level percent", false)
                    }
                    else -> reply("I don't have that information right now.", false)
                }
            }

            "lock" -> {
                if (accessibility != null) {
                    accessibility.lockScreenGracefully()
                    reply("Phone locked.", false)
                } else {
                    reply("Accessibility service not enabled.", false)
                }
            }

            "unlock" -> {
                reply("Phone is unlocked.", false)
            }

            "stop" -> {
                reply("Stopping.", false)
            }

            "greet" -> {
                reply("Hello Vishnu! How can I help you?", false)
            }

            else -> {
                reply("I parsed the command but I'm not sure how to execute it yet.", false)
            }
        }
    }
}
