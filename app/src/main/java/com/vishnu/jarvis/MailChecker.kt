package com.vishnu.jarvis

import java.util.Properties
import javax.mail.Folder
import javax.mail.Session
import javax.mail.Store

// Reads mail over plain IMAP using an app password (Google/Outlook let you
// generate one in account settings). This talks directly to the mail
// server - it is a protocol, not an "AI API" or a paid service.
// Requires adding to app/build.gradle:
//   implementation 'com.sun.mail:android-mail:1.6.7'
//   implementation 'com.sun.mail:android-activation:1.6.7'
// Must be called from a background thread, never the main thread.
object MailChecker {

    fun fetchUnreadSubjects(
        host: String,       // e.g. "imap.gmail.com"
        email: String,
        appPassword: String,
        maxResults: Int = 5
    ): List<String> {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
        }
        val session = Session.getInstance(props)
        val store: Store = session.getStore("imaps")
        store.connect(host, email, appPassword)

        val inbox: Folder = store.getFolder("INBOX")
        inbox.open(Folder.READ_ONLY)

        val messages = inbox.messages
            .filter { !it.flags.contains(javax.mail.Flags.Flag.SEEN) }
            .takeLast(maxResults)
            .map { "${it.from.firstOrNull()} - ${it.subject}" }

        inbox.close(false)
        store.close()
        return messages
    }
}
