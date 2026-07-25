package com.vishnu.jarvis

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// This is what actually "controls the phone" - it reads the screen tree of
// whatever app is in front, and can tap or type into it. Must be turned on
// manually once in Settings > Accessibility > Jarvis (Android won't let an
// app grant this to itself).
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // Required override - we don't need to react to every event, the
    // functions below pull the screen tree on demand instead.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- Feature 1: send a WhatsApp message by contact name ----
    // Realistic approach without APIs: WhatsApp's wa.me link opens a chat
    // with the message pre-typed. This service then finds the "Send"
    // button on screen and taps it, so the whole thing is hands-free.
    fun sendWhatsAppMessage(context: Context, contactName: String, message: String): Boolean {
        val phone = ProfileStore.getContactNumber(context, contactName) ?: return false
        val encoded = Uri.encode(message)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://wa.me/$phone?text=$encoded")
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        // give WhatsApp a moment to open and pre-fill the text box, then tap Send
        Handler(Looper.getMainLooper()).postDelayed({ tapWhatsAppSend() }, 1800)
        return true
    }

    private fun tapWhatsAppSend() {
        val root = rootInActiveWindow ?: return
        val sendButton = findNodeByDescription(root, "Send")
        sendButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ---- Feature 2: fill whatever form is currently on screen ----
    // Matches each editable field's hint/label against the user's saved
    // profile. Start simple (keyword match) - only reach for an on-device
    // model later for fields this can't confidently match.
    fun fillCurrentFormWithProfile(context: Context): Boolean {
        val root = rootInActiveWindow ?: return false
        val fields = mutableListOf<AccessibilityNodeInfo>()
        collectEditableFields(root, fields)

        for (field in fields) {
            val label = (field.hintText ?: field.text ?: field.contentDescription ?: "")
                .toString().lowercase()
            val value = when {
                "name" in label -> ProfileStore.getName(context)
                "email" in label -> ProfileStore.getEmail(context)
                "phone" in label -> ProfileStore.getPhone(context)
                "address" in label -> ProfileStore.getAddress(context)
                else -> null
            } ?: continue

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return fields.isNotEmpty()
    }

    private fun collectEditableFields(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isEditable) out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectEditableFields(it, out) }
        }
    }

    private fun findNodeByDescription(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString()?.equals(desc, ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByDescription(child, desc)?.let { return it }
        }
        return null
    }
}
