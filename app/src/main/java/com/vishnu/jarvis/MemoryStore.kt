package com.vishnu.jarvis

import android.content.Context

object MemoryStore {

    private const val PREF = "jarvis_memory"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun remember(context: Context, key: String, value: String) {
        prefs(context).edit()
            .putString(key.lowercase(), value)
            .apply()
    }

    fun recall(context: Context, key: String): String? {
        return prefs(context)
            .getString(key.lowercase(), null)
    }

    fun forget(context: Context, key: String) {
        prefs(context).edit()
            .remove(key.lowercase())
            .apply()
    }

    fun getAll(context: Context): Map<String, *> {
        return prefs(context).all
    }
}