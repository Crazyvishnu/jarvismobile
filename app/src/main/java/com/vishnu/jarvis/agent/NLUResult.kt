package com.vishnu.jarvis.agent

data class NLUResult(
    val intent: String,
    val app: String?,
    val query: String?,
    val contact: String?,
    val message: String?,
    val setting: String?,
    val state: String?,
    val direction: String?,
    val minutes: Int?,
    val confidence: Float
)
