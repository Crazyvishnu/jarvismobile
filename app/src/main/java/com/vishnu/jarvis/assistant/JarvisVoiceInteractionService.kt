package com.vishnu.jarvis.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

class JarvisVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.d("JARVIS", "VoiceInteractionService ready")
    }

    override fun onShutdown() {
        Log.d("JARVIS", "VoiceInteractionService shutdown")
        super.onShutdown()
    }
}