package com.vishnu.jarvis.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

class JarvisSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.d("JARVIS", "New Voice Session Created")
        return JarvisVoiceSession(this)
    }
}