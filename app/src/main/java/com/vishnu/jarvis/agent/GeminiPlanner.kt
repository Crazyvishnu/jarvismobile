package com.vishnu.jarvis.agent

import android.content.Context
import android.util.Log
import com.vishnu.jarvis.ProfileStore
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object GeminiPlanner {

    private const val TAG = "GeminiPlanner"
    private const val SYSTEM_PROMPT = """You are a command parser for a voice assistant app called Jarvis.
Your ONLY job is to read a voice command and return a JSON plan.
Never explain. Never answer questions. Never chat. ONLY return valid JSON.

The JSON must follow this exact schema:
{
  "intent": "play_music" | "open_app" | "search" | "call" | "send_message" | "toggle_setting" | "reminder" | "volume" | "info_query" | "lock" | "unlock" | "stop" | "greet" | "unknown",
  "app": "spotify" | "youtube" | "whatsapp" | "chrome" | "maps" | "camera" | "instagram" | "settings" | "phone" | null,
  "query": string or null,
  "contact": string or null,
  "message": string or null,
  "setting": "wifi" | "bluetooth" | "flashlight" | "hotspot" | "mobile_data" | null,
  "state": "on" | "off" | null,
  "direction": "up" | "down" | null,
  "minutes": number or null
}

Examples:
"play boom boom on spotify" -> {"intent":"play_music","app":"spotify","query":"boom boom","contact":null,"message":null,"setting":null,"state":null,"direction":null,"minutes":null}
"open spotify and play boom boom song" -> {"intent":"play_music","app":"spotify","query":"boom boom","contact":null,"message":null,"setting":null,"state":null,"direction":null,"minutes":null}
"boom boom spotify" -> {"intent":"play_music","app":"spotify","query":"boom boom","contact":null,"message":null,"setting":null,"state":null,"direction":null,"minutes":null}
"call mom on whatsapp" -> {"intent":"call","app":"whatsapp","query":null,"contact":"mom","message":null,"setting":null,"state":null,"direction":null,"minutes":null}
"send hi to basith" -> {"intent":"send_message","app":"whatsapp","query":null,"contact":"basith","message":"hi","setting":null,"state":null,"direction":null,"minutes":null}
"turn on wifi" -> {"intent":"toggle_setting","app":null,"query":null,"contact":null,"message":null,"setting":"wifi","state":"on","direction":null,"minutes":null}
"remind me to drink water in 30 minutes" -> {"intent":"reminder","app":null,"query":null,"contact":null,"message":"drink water","setting":null,"state":null,"direction":null,"minutes":30}
"what time is it" -> {"intent":"info_query","app":null,"query":"time","contact":null,"message":null,"setting":null,"state":null,"direction":null,"minutes":null}
"open instagram" -> {"intent":"open_app","app":"instagram","query":null,"contact":null,"message":null,"setting":null,"state":null,"direction":null,"minutes":null}"""

    fun plan(command: String, context: Context, callback: (NLUResult?) -> Unit) {
        val apiKey = ProfileStore.getGeminiApiKey(context)?.trim()
        if (apiKey.isNullOrEmpty()) {
            Log.w(TAG, "No Gemini API key found, falling back to local processing")
            callback(null)
            return
        }

        thread {
            // First attempt with fast flash model
            var result = makeApiRequest(command, apiKey, "gemini-flash-lite-latest")
            
            // If the model was not found (404), fallback to the universally available legacy model
            if (result == null) {
                Log.w(TAG, "Primary model failed. Retrying with gemini-2.5-flash...")
                result = makeApiRequest(command, apiKey, "gemini-2.5-flash")
            }

            if (result != null) {
                callback(result)
            } else {
                Log.w(TAG, "Gemini planning failed on all models, falling back to local")
                callback(null)
            }
        }
    }

    private fun makeApiRequest(command: String, apiKey: String, model: String): NLUResult? {
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            val requestJson = JSONObject()
            val systemInstruction = JSONObject().apply {
                put("parts", org.json.JSONArray().put(JSONObject().apply { put("text", SYSTEM_PROMPT) }))
            }
            requestJson.put("system_instruction", systemInstruction)
            
            val contents = org.json.JSONArray()
            val userContent = JSONObject().apply {
                put("role", "user")
                put("parts", org.json.JSONArray().put(JSONObject().apply { put("text", command) }))
            }
            contents.put(userContent)
            requestJson.put("contents", contents)

            val generationConfig = JSONObject().apply {
                put("response_mime_type", "application/json")
            }
            requestJson.put("generationConfig", generationConfig)

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestJson.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val responseStr = reader.readText()
                reader.close()

                val responseJson = JSONObject(responseStr)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text", "")
                        val parsedResult = parseResult(text)
                        if (parsedResult != null) {
                            Log.d(TAG, "Gemini plan parsed successfully with $model: $parsedResult")
                            return parsedResult
                        }
                    }
                }
            } else {
                val errorStream = connection.errorStream
                if (errorStream != null) {
                    val errorReader = BufferedReader(InputStreamReader(errorStream))
                    val errorStr = errorReader.readText()
                    errorReader.close()
                    Log.e(TAG, "Gemini API error on $model: $responseCode - Response: $errorStr")
                    
                    if (responseCode == 404 && model == "gemini-flash-lite-latest") {
                        try {
                            val listUrl = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
                            val listConn = listUrl.openConnection() as HttpURLConnection
                            if (listConn.responseCode == HttpURLConnection.HTTP_OK) {
                                val listStr = BufferedReader(InputStreamReader(listConn.inputStream)).readText()
                                Log.e(TAG, "Available models: $listStr")
                            }
                        } catch (e: Exception) {}
                    }
                } else {
                    Log.e(TAG, "Gemini API error on $model: $responseCode")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling Gemini API for $model: ${e.message}")
        }
        return null
    }

    private fun parseResult(jsonText: String): NLUResult? {
        try {
            // Sometimes models wrap in markdown even with response_mime_type set
            var cleanText = jsonText.trim()
            if (cleanText.startsWith("```json")) {
                cleanText = cleanText.removePrefix("```json")
            } else if (cleanText.startsWith("```")) {
                cleanText = cleanText.removePrefix("```")
            }
            if (cleanText.endsWith("```")) {
                cleanText = cleanText.removeSuffix("```")
            }
            cleanText = cleanText.trim()

            val json = JSONObject(cleanText)
            
            // Extract with null handling for opt methods
            val intent = json.optString("intent", "unknown")
            if (intent == "unknown" || intent.isEmpty()) return null

            val app = if (json.isNull("app")) null else json.optString("app")
            val query = if (json.isNull("query")) null else json.optString("query")
            val contact = if (json.isNull("contact")) null else json.optString("contact")
            val message = if (json.isNull("message")) null else json.optString("message")
            val setting = if (json.isNull("setting")) null else json.optString("setting")
            val state = if (json.isNull("state")) null else json.optString("state")
            val direction = if (json.isNull("direction")) null else json.optString("direction")
            val minutes = if (json.isNull("minutes")) null else json.optInt("minutes", -1).let { if (it == -1) null else it }

            return NLUResult(
                intent = intent,
                app = app,
                query = query,
                contact = contact,
                message = message,
                setting = setting,
                state = state,
                direction = direction,
                minutes = minutes,
                confidence = 1.0f
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Gemini JSON: ${e.message}\nRaw JSON: $jsonText")
            return null
        }
    }
}
