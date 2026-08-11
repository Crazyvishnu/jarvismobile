package com.vishnu.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// This is what actually "controls the phone" - it reads the screen tree of
// whatever app is in front, and can tap or type into it. Must be turned on
// manually once in Settings > Accessibility > Jarvis (Android won't let an
// app grant this to itself).
//
// Also handles auto-PIN entry: when the lock screen PIN entry UI appears
// (SystemUI), the service reads the stored PIN from ProfileStore and
// programmatically clicks the digit buttons, then confirms. This lets
// voice commands execute fully from the lock screen without manual unlock.
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
        private const val TAG = "JarvisA11y"

        /** Set to true when the service should auto-enter the PIN on the next keyguard appearance */
        @Volatile var shouldAutoUnlock = false

        /** Set to true after a command finishes and we need to re-lock the device */
        @Volatile var shouldRelockAfterCommand = false
        
        @Volatile var pendingCommandToExecute: String? = null
        
        fun startAutoUnlock(command: String? = null) {
            android.util.Log.d(TAG, "startAutoUnlock called: command=$command, instance=${instance != null}")
            android.util.Log.d("VoskWakeWordSvc", "[A11y] startAutoUnlock called: command=$command, serviceAlive=${instance != null}")
            shouldAutoUnlock = true
            pendingCommandToExecute = command
            instance?.pinEntryAttempted = false
            instance?.isTypingPin = false
            instance?.swipeAttempted = false
            instance?.bouncerDumped = false
            if (instance != null) {
                instance?.startAutoUnlockPolling()
            } else {
                android.util.Log.e(TAG, "AccessibilityService instance is NULL! Cannot unlock. Is the service enabled in Settings?")
                android.util.Log.e("VoskWakeWordSvc", "[A11y] ERROR: AccessibilityService is NULL! Cannot unlock. Please re-enable Jarvis in Settings -> Accessibility!")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile var pinEntryAttempted = false
    @Volatile var isTypingPin = false
    var swipeAttempted = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected. Payment guard ready.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Gracefully turns off the screen (equivalent to pressing the power button).
     * Unlike DevicePolicyManager.lockNow(), this does not trigger a "hard lock",
     * meaning accessibility services and biometrics can still unlock the phone later.
     */
    fun lockScreenGracefully() {
        // GLOBAL_ACTION_LOCK_SCREEN requires API 28+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // ══════════════════════════════════════════════════════════════════════
        // PAYMENT APP GUARD — Primary: TYPE_WINDOWS_CHANGED (global, instant)
        //
        // TYPE_WINDOWS_CHANGED fires at the OS level for ANY app's window
        // change, regardless of the packageNames restriction in our config.
        // We read the active window list to identify the foreground app and
        // disable ourselves BEFORE the payment app's security check runs.
        // This is zero-latency — no polling, no timing race.
        // ══════════════════════════════════════════════════════════════════════
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            checkWindowsForPaymentApp()
            return
        }

        val pkg = event.packageName?.toString() ?: return

        // Fallback guard: TYPE_WINDOW_STATE_CHANGED from whitelisted packages
        // (com.vishnu.jarvis / com.android.systemui). Catches edge cases where
        // the window-changed path above missed the app.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            PaymentAppGuard.isPaymentApp(pkg) &&
            !PaymentAppGuard.isGuardActive) {

            triggerPaymentGuard(pkg)
            return
        }

        // Feature: Auto skip YouTube ads
        if (pkg == "com.google.android.youtube") {
            val root = rootInActiveWindow
            if (root != null) {
                var skipAd = findNodeByText(root, "Skip Ad", partial = true, requireClickable = false) 
                             ?: findNodeByText(root, "Skip ads", partial = true, requireClickable = false)
                             ?: findNodeByText(root, "Skip video", partial = true, requireClickable = false)
                             ?: findNodeByText(root, "Skip", partial = true, requireClickable = false)
                             ?: findNodeByDescription(root, "Skip Ad", partial = true)
                             ?: findNodeByDescription(root, "Skip ads", partial = true)
                             ?: findNodeByDescription(root, "Skip video", partial = true)
                             ?: findNodeByDescription(root, "Skip", partial = true)
                             
                if (skipAd == null) {
                    val byId = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/skip_ad_button")
                    if (!byId.isNullOrEmpty()) skipAd = byId[0]
                }
                
                if (skipAd == null) {
                    val byId2 = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/modern_skip_ad_button")
                    if (!byId2.isNullOrEmpty()) skipAd = byId2[0]
                }

                if (skipAd != null) {
                    var clickableNode: AccessibilityNodeInfo? = skipAd
                    while (clickableNode != null && !clickableNode.isClickable) {
                        clickableNode = clickableNode.parent
                    }
                    clickableNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
        }
        
        // === AUTO-UNLOCK: React to SystemUI bouncer events ===
        if (pkg != "com.android.systemui") return
        if (!shouldAutoUnlock) return
        if (pinEntryAttempted) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            handleBouncerEvent()
        }
    }

    /**
     * Called whenever SystemUI fires an event while we want to auto-unlock.
     * Inspects ALL on-screen windows to find the bouncer UI.
     *
     * Android 16 bouncer states:
     *   1. Fingerprint/biometric prompt -> click "Use PIN" / "Enter PIN"
     *   2. PIN pad visible (digits 0-9 + Emergency) -> auto-enter PIN
     *   3. Password field visible -> set text + confirm
     */
    private fun handleBouncerEvent() {
        if (!shouldAutoUnlock || pinEntryAttempted || isTypingPin) return
        val allRoots = getAllOnScreenRoots()
        
        // Log what we see on first encounter
        if (!bouncerDumped) {
            bouncerDumped = true
            logDiagnosticUiDump(0, allRoots)
        }

        for (root in allRoots) {
            val rootPkg = root.packageName?.toString() ?: ""
            
            // Only inspect SystemUI windows
            if (rootPkg != "com.android.systemui") continue

            // Check 1: Is the PIN pad already showing?
            if (isPinPadVisible(root)) {
                if (pinEntryAttempted || isTypingPin) return
                pinEntryAttempted = true
                isTypingPin = true
                Log.d(TAG, "PIN pad detected via event! Auto-typing PIN...")
                Log.d("VoskWakeWordSvc", "[A11y] PIN pad detected via SystemUI event! Typing PIN now...")
                val pin = ProfileStore.getDevicePin(this)
                if (!pin.isNullOrEmpty()) {
                    handler.postDelayed({ tryEnterPin(pin) }, 400)
                } else {
                    Log.w("VoskWakeWordSvc", "[A11y] WARNING: No PIN stored! Go to Jarvis Settings to save your PIN.")
                    isTypingPin = false
                }
                return
            }

            // Check 2: Is there a "Use PIN" / "Enter PIN" button to dismiss biometric prompt?
            if (tryDismissFingerprintPrompt(root)) {
                Log.d("VoskWakeWordSvc", "[A11y] Clicked fingerprint->PIN switch button. Waiting for PIN pad...")
                // Re-dump after clicking to see what appears next
                bouncerDumped = false
                return
            }
        }
    }

    private var bouncerDumped = false

    private fun startAutoUnlockPolling() {
        Log.d(TAG, "Starting auto-unlock polling. Pending command: $pendingCommandToExecute")
        Log.d("VoskWakeWordSvc", "[A11y] Starting auto-unlock. Command: $pendingCommandToExecute")

        bouncerDumped = false
        pinEntryAttempted = false
        isTypingPin = false

        try {
            val intent = Intent(this, UnlockBouncerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("command", pendingCommandToExecute)
            }
            startActivity(intent)
            Log.d("VoskWakeWordSvc", "[A11y] Launched UnlockBouncerActivity to trigger native bouncer")
        } catch (e: Exception) {
            Log.e("VoskWakeWordSvc", "[A11y] Failed to launch UnlockBouncerActivity: ${e.message}")
        }

        startUnlockWatchdog()
    }

    /**
     * Watchdog poller: periodically checks if keyguard has been dismissed.
     * Automatically performs swipe-up gestures to dismiss fingerprint/lock screens and reveal PIN keypad.
     * Relies on handleBouncerEvent() to detect PIN keypad and type stored PIN.
     */
    private fun startUnlockWatchdog() {
        val maxAttempts = 240 // 60 seconds
        var attempt = 0
        val poller = object : Runnable {
            override fun run() {
                if (!shouldAutoUnlock) return
                
                val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (!km.isKeyguardLocked) {
                    Log.d("VoskWakeWordSvc", "[A11y] SUCCESS -> Device unlocked!")
                    shouldAutoUnlock = false
                    isTypingPin = false
                    
                    pendingCommandToExecute?.let { cmd ->
                        Log.d("VoskWakeWordSvc", "[A11y] Executing command: $cmd")
                        val intent = Intent(this@JarvisAccessibilityService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra("execute_unlocked_command", cmd)
                        }
                        startActivity(intent)
                        pendingCommandToExecute = null
                    }
                    return
                }

                // Immediately swipe at 500ms (attempt 2), then every 1 second (every 4 attempts)
                if (!pinEntryAttempted && !isTypingPin && (attempt == 2 || (attempt > 0 && attempt % 4 == 0))) {
                    handleBouncerEvent()
                    
                    // If we haven't reached PIN entry yet, simulate swipe up to reveal PIN keypad
                    if (!pinEntryAttempted && !isTypingPin) {
                        forceSwipeUp(attempt = attempt)
                    }
                }

                // Every 3 seconds, dump diagnostic info to logs
                if (attempt > 0 && attempt % 12 == 0) {
                    val allRoots = getAllOnScreenRoots()
                    logDiagnosticUiDump(attempt, allRoots)
                }
                
                attempt++
                if (attempt < maxAttempts) {
                    handler.postDelayed(this, 250)
                } else {
                    Log.w("VoskWakeWordSvc", "[A11y] Timeout after 60s. Giving up auto-unlock.")
                    shouldAutoUnlock = false
                    pendingCommandToExecute = null
                    pinEntryAttempted = false
                    bouncerDumped = false
                }
            }
        }
        handler.post(poller)
    }

    private fun isPinPadVisible(node: AccessibilityNodeInfo): Boolean {
        if (findEmergencyButton(node) != null) return true
        val has1 = findNodeByText(node, "1", requireClickable = false) != null || findNodeByDescription(node, "1") != null
        val has2 = findNodeByText(node, "2", requireClickable = false) != null || findNodeByDescription(node, "2") != null
        val has3 = findNodeByText(node, "3", requireClickable = false) != null || findNodeByDescription(node, "3") != null
        val has0 = findNodeByText(node, "0", requireClickable = false) != null || findNodeByDescription(node, "0") != null
        return has1 && has2 && has3 && has0
    }

    private fun tryDismissFingerprintPrompt(node: AccessibilityNodeInfo): Boolean {
        // Expanded keyword list for Android 16 Motorola bouncer
        val switchKeywords = listOf(
            "use pin", "enter pin", "enter your pin",
            "use password", "enter password", "enter your password",
            "use pattern",
            "lock screen pin",
            "pin", "password",
            "cancel", "try again",
            "i can't use this method"  // Android 16 accessibility fallback text
        )
        for (kw in switchKeywords) {
            val target = findNodeByText(node, kw, partial = true, requireClickable = false)
                      ?: findNodeByDescription(node, kw, partial = true)
            if (target != null) {
                Log.d(TAG, "Found bouncer switch button: '${target.text ?: target.contentDescription}'. Clicking!")
                Log.d("VoskWakeWordSvc", "[A11y] Clicking bouncer switch: '${target.text ?: target.contentDescription}'")
                var clickable: AccessibilityNodeInfo? = target
                while (clickable != null && !clickable.isClickable) {
                    clickable = clickable.parent
                }
                if (clickable != null) {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }
        return false
    }

    private fun findEmergencyButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if ("emergency" in text || "emergency" in desc) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEmergencyButton(child)?.let { return it }
        }
        return null
    }

    private fun getAllOnScreenRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        windows?.forEach { win ->
            win.root?.let { if (!roots.contains(it)) roots.add(it) }
        }
        return roots
    }

    private fun logDiagnosticUiDump(attempt: Int, roots: List<AccessibilityNodeInfo>) {
        Log.d("VoskWakeWordSvc", "[A11y-Dump] === UI DUMP AT ATTEMPT $attempt across ${roots.size} windows ===")
        for ((idx, root) in roots.withIndex()) {
            val pkg = root.packageName?.toString() ?: "unknown"
            val nodesInfo = mutableListOf<String>()
            collectNodeTexts(root, nodesInfo)
            Log.d("VoskWakeWordSvc", "[A11y-Dump] Win #$idx ($pkg): ${if (nodesInfo.isEmpty()) "Empty/Transparent" else nodesInfo.take(20).joinToString(" | ")}")
        }
    }

    private fun collectNodeTexts(node: AccessibilityNodeInfo, list: MutableList<String>) {
        val txt = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val id = node.viewIdResourceName?.substringAfter(":id/") ?: ""
        if (!txt.isNullOrEmpty() || !desc.isNullOrEmpty() || id.isNotEmpty()) {
            if (list.size < 30) {
                list.add("[t='${txt ?: ""}', d='${desc ?: ""}', id='$id', click=${node.isClickable}]")
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodeTexts(child, list)
        }
    }

    override fun onInterrupt() {}

    /**
     * Called on every TYPE_WINDOWS_CHANGED event (fires globally, regardless of
     * packageNames restriction). Reads all visible windows to find the foreground
     * application package. If it is a payment/banking app, disable Jarvis
     * accessibility service immediately — before the app's security check runs.
     *
     * When the payment app is gone, the VoskWakeWordService proactive poller
     * handles re-enabling. This function only handles disable-on-detect.
     */
    private fun checkWindowsForPaymentApp() {
        if (PaymentAppGuard.isGuardActive) return // Already disabled, nothing to do

        val foregroundPkg = try {
            // windows is available when flagRetrieveInteractiveWindows is set.
            // The application-layer window with highest Z-order is the foreground app.
            windows?.firstOrNull { win ->
                win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
            }?.root?.packageName?.toString()
        } catch (e: Exception) {
            null
        } ?: return

        if (PaymentAppGuard.isPaymentApp(foregroundPkg)) {
            Log.d(TAG, "⚡ [TYPE_WINDOWS_CHANGED] Payment app in foreground: $foregroundPkg — disabling NOW!")
            triggerPaymentGuard(foregroundPkg)
        }
    }

    /**
     * Common payment guard activation logic — disables the accessibility service
     * and broadcasts so VoskWakeWordService knows to start monitoring for re-enable.
     */
    private fun triggerPaymentGuard(pkg: String) {
        PaymentAppGuard.guardTriggerPackage = pkg
        PaymentAppGuard.guardTriggerPackage = pkg

        val guardIntent = Intent("com.vishnu.jarvis.PAYMENT_GUARD_ACTIVATED").apply {
            setPackage(packageName)
            putExtra("payment_package", pkg)
        }
        sendBroadcast(guardIntent)

        if (PaymentAppGuard.disableAccessibilityService(this)) {
            Log.d(TAG, "✓ Accessibility service disabled. $pkg is safe to use.")
        } else {
            Log.w(TAG, "⚠ Could not disable service — WRITE_SECURE_SETTINGS may not be granted.")
        }
    }

    /**
     * Attempt to enter the PIN by finding digit buttons across all onscreen windows
     * and clicking them in sequence, followed by the Enter/OK button.
     */
    private fun tryEnterPin(pin: String) {
        val allRoots = getAllOnScreenRoots()
        if (allRoots.isEmpty()) {
            isTypingPin = false
            pinEntryAttempted = false
            return
        }

        Log.d(TAG, "Attempting auto-PIN entry (${pin.length} digits)")
        Log.d("VoskWakeWordSvc", "[A11y] Starting sequential PIN entry of ${pin.length} digits...")

        // Strategy 1: Find number buttons across all windows by content description / text
        var allFound = true
        for (digit in pin) {
            val digitStr = digit.toString()
            var button: AccessibilityNodeInfo? = null
            for (r in allRoots) {
                button = findClickableDigitButton(r, digitStr)
                if (button != null) break
            }
            if (button != null) {
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d("VoskWakeWordSvc", "[A11y] Tapped PIN digit '$digitStr'")
                Thread.sleep(200) // 200ms delay ensures clean single registration without bounce
            } else {
                Log.w("VoskWakeWordSvc", "[A11y] Could not find clickable PIN button for digit '$digitStr'")
                allFound = false
                break
            }
        }

        if (allFound) {
            // Try to find and click Enter/OK/checkmark button across all windows
            handler.postDelayed({
                val newRoots = getAllOnScreenRoots()
                var enter: AccessibilityNodeInfo? = null
                for (nr in newRoots) {
                    enter = findNodeByDescription(nr, "Enter")
                        ?: findNodeByDescription(nr, "OK")
                        ?: findNodeByDescription(nr, "Done")
                        ?: findNodeByDescription(nr, "Confirm")
                        ?: findNodeByDescription(nr, "enter")
                        ?: findEnterKey(nr)
                        ?: findEnterKeyByStructure(nr)
                    if (enter != null) break
                }
                
                enter?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                Log.d(TAG, "PIN entry completed, enter button tapped: ${enter != null}")
                Log.d("VoskWakeWordSvc", "[A11y] PIN entry completed, enter button tapped: ${enter != null}")
                
                // Allow Android 3 seconds to complete unlock animation and verify PIN
                // before permitting another attempt if unlock failed
                handler.postDelayed({
                    isTypingPin = false
                }, 3000)
            }, 300)
        } else {
            // Strategy 2: Try setting text directly on an EditText field
            Log.d(TAG, "Digit buttons not found, trying EditText strategy")
            var editField: AccessibilityNodeInfo? = null
            for (r in allRoots) {
                editField = findEditField(r)
                if (editField != null) break
            }
            if (editField != null) {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        pin
                    )
                }
                editField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

                // After setting text, press Enter/confirm
                handler.postDelayed({
                    val newRoots = getAllOnScreenRoots()
                    var enter: AccessibilityNodeInfo? = null
                    for (nr in newRoots) {
                        enter = findNodeByDescription(nr, "Enter")
                            ?: findNodeByDescription(nr, "OK")
                            ?: findEnterKey(nr)
                            ?: findEnterKeyByStructure(nr)
                        if (enter != null) break
                    }
                    
                    enter?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                    Log.d(TAG, "PIN text set, enter tapped: ${enter != null}")
                    handler.postDelayed({
                        isTypingPin = false
                    }, 3000)
                }, 300)
            } else {
                Log.w(TAG, "Could not find PIN entry UI elements")
                isTypingPin = false
            }
        }
    }

    private fun findClickableDigitButton(node: AccessibilityNodeInfo, digit: String): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.trim()
        val text = node.text?.toString()?.trim()
        if (desc == digit || text == digit) {
            if (node.isClickable) return node
            var parent = node.parent
            while (parent != null && !parent.isClickable) {
                parent = parent.parent
            }
            if (parent != null && parent.isClickable) return parent
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClickableDigitButton(child, digit)?.let { return it }
        }
        return null
    }

    // ---- Feature 1: send a WhatsApp message by contact name ----
    fun sendWhatsAppMessage(context: Context, phoneNumber: String, message: String): Boolean {
        val encoded = Uri.encode(message)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://wa.me/$phoneNumber?text=$encoded")
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        // WhatsApp needs time to open (especially from lock screen), load the
        // chat, and pre-fill the text box. Try tapping Send after 4s, and
        // retry once at 6s in case WhatsApp was slow to load.
        Handler(Looper.getMainLooper()).postDelayed({ tapWhatsAppSend() }, 4000)
        Handler(Looper.getMainLooper()).postDelayed({ tapWhatsAppSend() }, 6000)
        return true
    }

    // ---- Feature: Toggle Quick Settings (Wi-Fi, Bluetooth, Hotspot, etc) ----
    fun toggleQuickSetting(settingName: String): Boolean {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        // Wait for panel to open
        Handler(Looper.getMainLooper()).postDelayed({
            val root = rootInActiveWindow
            if (root != null) {
                // Find node by text or description
                val tile = findNodeByText(root, settingName, partial = true, requireClickable = false)
                           ?: findNodeByDescription(root, settingName, partial = true)
                
                if (tile != null) {
                    var clickableNode: AccessibilityNodeInfo? = tile
                    while (clickableNode != null && !clickableNode.isClickable) {
                        clickableNode = clickableNode.parent
                    }
                    clickableNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Toggled quick setting: $settingName")
                } else {
                    Log.d(TAG, "Quick setting not found: $settingName")
                }
                
                // Close quick settings
                Handler(Looper.getMainLooper()).postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }, 800)
            }
        }, 1200)
        return true
    }

    private fun tapWhatsAppSend() {
        val root = rootInActiveWindow ?: return
        val sendButton = findNodeByDescription(root, "Send")
        sendButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ---- Feature: make a WhatsApp voice call by contact name ----
    fun makeWhatsAppCall(context: Context, phoneNumber: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://wa.me/$phoneNumber")
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        // WhatsApp needs time to open (especially from lock screen) and load the chat.
        // Try tapping Voice Call after 4s, and retry once at 6s.
        Handler(Looper.getMainLooper()).postDelayed({ tapWhatsAppCall() }, 4000)
        Handler(Looper.getMainLooper()).postDelayed({ tapWhatsAppCall() }, 6000)
        return true
    }

    private fun tapWhatsAppCall() {
        val root = rootInActiveWindow ?: return
        val callButton = findNodeByDescription(root, "Voice call", partial = true)
            ?: findNodeByDescription(root, "Call ", partial = true)
            ?: findNodeByText(root, "Voice call", partial = true)
        callButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ---- Feature 2: fill whatever form is currently on screen ----
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

    private fun findNodeByDescription(node: AccessibilityNodeInfo, desc: String, partial: Boolean = false): AccessibilityNodeInfo? {
        val nodeDesc = node.contentDescription?.toString()
        if (nodeDesc != null) {
            val isMatch = if (partial) nodeDesc.lowercase().contains(desc.lowercase()) else nodeDesc.equals(desc, ignoreCase = true)
            if (isMatch) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByDescription(child, desc, partial)?.let { return it }
        }
        return null
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String, partial: Boolean = false, requireClickable: Boolean = true): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()?.trim()
        if (nodeText != null) {
            val isMatch = if (partial) nodeText.lowercase().contains(text.lowercase()) else nodeText == text
            if (isMatch) {
                if (!requireClickable || node.isClickable) return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByText(child, text, partial, requireClickable)?.let { return it }
        }
        return null
    }

    private fun findEditField(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditField(child)?.let { return it }
        }
        return null
    }

    /**
     * Last resort: find the enter/confirm key by looking for a clickable node
     * that is NOT a digit (0-9) in the keyguard area. On many devices, this
     * is an icon button at the bottom-right of the PIN pad.
     */
    private fun findEnterKey(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        if (node.isClickable && (
                    viewId.contains("key_enter") ||
                    desc.contains("enter") || desc.contains("ok") ||
                    desc.contains("done") || desc.contains("confirm") ||
                    desc.contains("arrow") || desc.contains("next") || desc.contains("submit") ||
                    text.contains("enter") || text.contains("ok") ||
                    text.contains("✓") || text.contains("→") || text.contains("next")
                )) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEnterKey(child)?.let { return it }
        }
        return null
    }

    /**
     * Finds the Enter/Arrow key by finding the "0" button and looking for the
     * clickable button that is geometrically located to the right of it.
     */
    private fun findEnterKeyByStructure(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val zeroNode = findNodeByText(root, "0") ?: findNodeByDescription(root, "0") ?: return null
        val zeroBounds = android.graphics.Rect()
        zeroNode.getBoundsInScreen(zeroBounds)

        val allClickable = mutableListOf<AccessibilityNodeInfo>()
        collectClickableNodes(root, allClickable)

        var bestMatch: AccessibilityNodeInfo? = null
        var minDistanceX = Int.MAX_VALUE

        for (node in allClickable) {
            if (node == zeroNode) continue
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)

            // Check if this node is to the right of the "0" button and in the same row
            if (bounds.left >= zeroBounds.centerX() &&
                bounds.bottom > zeroBounds.top &&
                bounds.top < zeroBounds.bottom) {

                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                if (text.matches(Regex(".*[0-9].*")) || desc.matches(Regex(".*[0-9].*"))) continue

                val dist = bounds.left - zeroBounds.right
                // Find the closest clickable node to the right
                if (dist < minDistanceX) {
                    minDistanceX = dist
                    bestMatch = node
                }
            }
        }
        return bestMatch
    }

    private fun collectClickableNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable) list.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectClickableNodes(it, list) }
        }
    }

    /**
     * Simulates a swipe up gesture from the bottom of the screen to the top.
     * Called directly by MainActivity to dismiss the initial lock screen layer
     * and force the PIN pad to appear.
     */
    fun forceSwipeUp(bypassCheck: Boolean = false, attempt: Int = 0) {
        try {
            val root = rootInActiveWindow
            if (root != null) {
                if (!bypassCheck) {
                    // If the PIN pad is already on screen, don't swipe (as it might interfere with typing)
                    val emergencyNode = findEmergencyButton(root)
                    if (emergencyNode != null) {
                        Log.d(TAG, "PIN pad already visible, skipping force swipe up")
                        return
                    }
                }

                // Trick 1: Try programmatic scrolling on OEM lock screens (always execute!)
                if (root.packageName == "com.android.systemui") {
                    root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    findAndScrollForward(root)
                }
            }

            // Trick 2: Physical swipe gesture
            val metrics = resources.displayMetrics
            
            // Many modern OEM ROMs (MIUI, Realme UI, OxygenOS, One UI) ignore swipes from the far left
            // edge (0.2f) as accidental touches or reserve them for Camera/Glance widgets.
            // To reliably trigger the unlock bouncer while avoiding in-display fingerprint sensors,
            // we alternate between center (0.5f), slightly left (0.35f), and slightly right (0.65f).
            val xMultiplier = when ((attempt / 2) % 3) {
                0 -> 0.5f   // Dead center (most reliable bouncer trigger)
                1 -> 0.35f  // Left of center (avoids dead-center fingerprint icon)
                else -> 0.65f // Right of center (avoids dead-center fingerprint icon)
            }
            val swipeX = metrics.widthPixels * xMultiplier
            val startY = metrics.heightPixels * 0.75f
            val endY = metrics.heightPixels * 0.15f

            val path = Path().apply {
                moveTo(swipeX, startY)
                lineTo(swipeX, endY)
            }
            // 400ms duration simulates an organic, deliberate human swipe to prevent OEM capacitive rejection
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
                .build()

            Log.d("VoskWakeWordSvc", "[A11y] SWIPE ATTEMPTED -> Sending upward touch fling (x=$xMultiplier, attempt=$attempt)...")
            Log.d(TAG, "SWIPE ATTEMPTED: Sending upward touch fling (x=$xMultiplier, attempt=$attempt)...")
            
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d("VoskWakeWordSvc", "[A11y] SWIPED! -> Android SystemUI accepted and completed the swipe gesture!")
                    Log.d(TAG, "SWIPED! -> Android SystemUI accepted and completed the swipe gesture!")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w("VoskWakeWordSvc", "[A11y] SWIPE BLOCKED! -> Android SystemUI rejected or blocked the simulated swipe gesture!")
                    Log.w(TAG, "SWIPE BLOCKED! -> Android SystemUI rejected or blocked the simulated swipe gesture!")
                }
            }

            dispatchGesture(gesture, callback, null)
        } catch (e: Exception) {
            Log.e(TAG, "Swipe up failed: ${e.message}")
            Log.e("VoskWakeWordSvc", "[A11y] Swipe dispatch threw exception: ${e.message}")
        }
    }

    private fun findAndScrollForward(node: AccessibilityNodeInfo) {
        if (node.isScrollable) {
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAndScrollForward(child)
        }
    }
}
