package com.vishnu.jarvis

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

object ContactHelper {

    /**
     * Finds contacts matching the spoken name.
     * Returns a list of Pair(ContactName, PhoneNumber).
     */
    fun findContactMatches(context: Context, spokenName: String): List<Pair<String, String>> {
        val matches = mutableListOf<Pair<String, String>>()

        // First check the manually saved ProfileStore contact
        val savedNumber = ProfileStore.getContactNumber(context, spokenName)
        if (savedNumber != null) {
            matches.add(Pair(spokenName, savedNumber.replace(Regex("[^0-9+]"), "")))
            return matches
        }

        val resolver = context.contentResolver
        val spokenWords = spokenName.lowercase().split(" ").filter { it.isNotEmpty() }

        try {
            val cursor = resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null,
                null,
                null
            )
            
            val scoredContacts = mutableListOf<Pair<Pair<String, String>, Int>>()

            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                
                while (it.moveToNext()) {
                    val name = it.getString(nameIndex) ?: continue
                    var num = it.getString(numIndex) ?: continue
                    num = num.replace(Regex("[^0-9+]"), "")
                    
                    val nameLower = name.lowercase()
                    
                    // Score the contact based on how many spoken words match
                    var score = 0
                    for (word in spokenWords) {
                        // exact word match or substring match
                        if (nameLower.contains(word) || word.contains(nameLower)) {
                            score += 1
                        }
                    }
                    
                    if (score > 0) {
                        scoredContacts.add(Pair(Pair(name, num), score))
                    }
                }
            }
            
            if (scoredContacts.isNotEmpty()) {
                val maxScore = scoredContacts.maxOf { it.second }
                // Only return the best matches (e.g. if one scored 2 and another scored 1, just return the 2)
                matches.addAll(scoredContacts.filter { it.second == maxScore }.map { it.first }.distinctBy { it.second })
            }
            
        } catch (e: Exception) {
            Log.e("ContactHelper", "Failed to read contacts: ${e.message}")
        }
        
        return matches
    }
}
