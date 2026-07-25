package com.vishnu.jarvis.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.widget.Toast
import com.vishnu.jarvis.CommandRouter
import com.vishnu.jarvis.TextToSpeechManager
import com.vishnu.jarvis.voice.VoiceManager

class JarvisVoiceSession(
    private val service: JarvisSessionService
) : VoiceInteractionSession(service) {

    private lateinit var voiceManager: VoiceManager
    private lateinit var commandRouter: CommandRouter

    override fun onCreate() {
        super.onCreate()

        TextToSpeechManager.init(service)

        commandRouter = CommandRouter(service) { reply ->

            TextToSpeechManager.speak(reply)

            Toast.makeText(
                service,
                reply,
                Toast.LENGTH_SHORT
            ).show()
        }

        voiceManager = VoiceManager(service) { command ->

            commandRouter.process(command)

        }

        voiceManager.startListening()
    }

    override fun onDestroy() {

        voiceManager.destroy()

        TextToSpeechManager.shutdown()

        super.onDestroy()
    }
}