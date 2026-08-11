package com.vishnu.jarvis

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log

/**
 * Handles automatic disabling/re-enabling of JarvisAccessibilityService
 * when payment/banking apps are opened or closed.
 *
 * Requires WRITE_SECURE_SETTINGS permission (granted once via ADB):
 *   adb shell pm grant com.vishnu.jarvis android.permission.WRITE_SECURE_SETTINGS
 *
 * Also requires PACKAGE_USAGE_STATS for foreground app detection:
 *   Granted via Settings > Apps > Special access > Usage access
 */
object PaymentAppGuard {

    private const val TAG = "PaymentAppGuard"

    /** Set of package names for Indian UPI, banking, and payment apps */
    val PAYMENT_PACKAGES = setOf(
        // UPI / Payment apps
        "in.org.npci.upiapp",              // BHIM
        "com.phonepe.app",                  // PhonePe
        "com.google.android.apps.nbu.paisa.user", // Google Pay (India)
        "net.one97.paytm",                  // Paytm
        "com.dreamplug.androidapp",         // CRED
        "in.amazon.mShop.android.shopping", // Amazon (has UPI)
        "com.mobikwik_new",                 // MobiKwik
        "in.juspay.nammayatri",             // Namma Yatri
        "com.freecharge.android",           // FreeCharge
        "com.airtel.money",                 // Airtel Payments Bank
        "com.jio.myjio",                    // JioFinance/MyJio UPI
        "com.bajajfinserv.app",             // Bajaj Finserv
        "com.navi.app",                     // Navi
        "com.indmoney.app",                 // INDmoney
        "com.upi.axispay",                  // Axis Pay UPI
        "com.ibl.upi",                      // Indian Bank UPI
        "com.sbi.upi",                      // SBI UPI
        "com.hdfcbank.payzapp",             // HDFC PayZapp
        "com.lazycard.app",                 // LazyPay
        "com.simpl.android",                // Simpl
        "com.olamoney.app",                 // Ola Money
        "in.juspay.hyperpay",               // HyperPay
        "com.razorpay.payments.app",        // Razorpay

        // SBI apps (ALL variants)
        "com.sbi.lotusintouch",             // YONO SBI (main app)
        "com.sbi.SBIFreedomPlus",           // SBI Freedom Plus (legacy)
        "com.sbi.SBAnywhereCorporate",      // YONO Lite / SBI Anywhere Corporate
        "com.sbi.SBAnywhere",               // SBI Anywhere Personal
        "com.sbi.yono.sme",                 // YONO Business

        // Banking apps — Private sector
        "com.csam.icici.bank.imobile",      // ICICI iMobile Pay
        "com.icicibank.imobile",            // ICICI iMobile (alternate)
        "com.axis.mobile",                  // Axis Mobile
        "com.msf.kbank.mobile",             // Kotak Mahindra 811
        "com.snapwork.hdfc",                // HDFC Mobile Banking
        "com.hdfc.retail.banking",          // HDFC PayZapp
        "com.rbl.mobilebanking",            // RBL Bank
        "com.yes.mobilebanking",            // YES Bank
        "com.fss.federal",                  // Federal Bank
        "com.indusind.mobilebanking",       // IndusInd Bank
        "com.kbl.mobile",                   // Karnataka Bank
        "com.sc.mobilebanking.in",          // Standard Chartered India
        "com.citi.mobile.coc",              // Citibank
        "com.hsbc.hsbcindia",              // HSBC India
        "com.dlsdelhi.mobilebanking",      // Delhi State Cooperative Bank
        "com.kvb.mobilebanking",           // Karur Vysya Bank
        "com.tamilnadmercantile.mobilebanking", // Tamilnad Mercantile Bank
        "com.dcbbank.mobilebanking",        // DCB Bank
        "com.saraswat.mobilebanking",       // Saraswat Bank
        "com.southindianbank.mobilebanking",// South Indian Bank
        "com.bandhanbank.mobilebanking",    // Bandhan Bank
        "com.au.bank.mobilebanking",        // AU Small Finance Bank
        "com.ujjivan.mobilebanking",        // Ujjivan Small Finance Bank

        // Banking apps — Public sector
        "com.bob.mconnect",                 // Bank of Baroda
        "in.co.bankofbaroda.mob",           // BOB World
        "com.unionbankofindia.unionapp",    // Union Bank
        "com.pnb.mbanking",                // PNB mBanking
        "com.pnb.pnbone",                  // PNB ONE
        "com.idbi.mpassbook",              // IDBI Bank GO Mobile
        "com.canlogin.canara.mobilenew",    // Canara Bank ai1
        "com.infrasofttech.indianbank",     // Indian Bank
        "com.canaaborig.mobilebanking",     // Canara Bank (legacy)
        "com.iob.mobilebanking",           // IOB
        "com.uco.mobilebanking",           // UCO Bank
        "com.boi.mobilebanking",           // Bank of India
        "com.centralbankofindia.mobilebanking", // Central Bank of India
        "com.maharashtrabank.mobilebanking",// Bank of Maharashtra
        "com.psbmobilebanking",            // Punjab & Sind Bank
        "com.syndicatebank.mobilebanking", // Syndicate Bank (now Canara)
        "com.allahabadbank.mobilebanking", // Allahabad Bank (now Indian Bank)
        "com.andhraabank.mobilebanking",   // Andhra Bank (now Union Bank)

        // Wallets / Fintech / Trading
        "com.samsung.android.spay",         // Samsung Pay
        "com.slice.app",                    // Slice
        "money.jupiter",                    // Jupiter
        "com.fi.money",                     // Fi Money
        "com.niyo.go",                      // Niyo
        "com.groww.android",                // Groww
        "com.zerodha.kite3",                // Zerodha Kite
        "com.angel.one",                    // Angel One
        "com.upstox.pro",                   // Upstox
        "com.five.paisa",                   // 5Paisa
        "com.iifl.markets",                // IIFL Markets
        "in.paytm.wealth",                 // Paytm Money
        "com.bajajfinserv.broking",        // Bajaj Broking
        "com.motilaloswal.mobile",         // Motilal Oswal
        "com.dhan.app",                    // Dhan
        "com.swiggy.android",              // Swiggy (has payments)
        "com.application.zomato",          // Zomato (has payments)

        // Insurance / Lending / Credit
        "com.policybazaar.app",            // PolicyBazaar
        "com.coverfox.app",                // Coverfox
        "com.acko.android",                // Acko Insurance
        "com.digit.insurance",             // Digit Insurance
        "com.tataaia.app",                 // Tata AIA
        "com.liciapp",                     // LIC India
        "com.bankbazaar.android",          // BankBazaar
        "com.paisabazaar.android",         // PaisaBazaar
        "com.kreditbee.app",               // KreditBee
        "com.moneytap.app",                // MoneyTap
        "com.earlyalary.app",              // EarlySalary
        "com.paysense.android",            // PaySense
        "com.cashe.app",                   // CASHe
        "in.stashfin.app",                 // Stashfin

        // Crypto
        "com.coinswitch.kuber",            // CoinSwitch Kuber
        "com.coinbase.android",            // Coinbase
        "com.wazirx.android",              // WazirX
        "com.zebpay.app",                  // ZebPay
        "com.unocoin.app",                 // Unocoin
        "com.bitbns.app",                  // BitBNS
    )

    /** Dynamically learned payment packages (discovered at runtime) */
    private val dynamicPaymentPackages = mutableSetOf<String>()

    /** SharedPreferences key for persisting dynamic packages across restarts */
    private const val PREFS_NAME = "jarvis_payment_guard"
    private const val PREFS_KEY_DYNAMIC = "dynamic_payment_packages"
    private var prefsLoaded = false

    /** Whether the guard is currently active (service disabled for a payment app) */
    @Volatile var isGuardActive = false

    /** The payment app package that triggered the guard */
    @Volatile var guardTriggerPackage: String? = null

    /**
     * Load persisted dynamic packages from SharedPreferences.
     * Called lazily on first isPaymentApp() check so no Context is needed at init time.
     */
    private fun ensurePrefsLoaded(context: Context) {
        if (prefsLoaded) return
        prefsLoaded = true
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getStringSet(PREFS_KEY_DYNAMIC, emptySet()) ?: emptySet()
            dynamicPaymentPackages.addAll(saved)
            if (saved.isNotEmpty()) {
                Log.d(TAG, "Loaded ${saved.size} persisted dynamic payment packages")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load persisted payment packages: ${e.message}")
        }
    }

    /**
     * Permanently learn a new payment package — persists across app restarts.
     * Call this when a new financial app is discovered at runtime.
     */
    fun learnPackage(context: Context, packageName: String) {
        if (packageName in PAYMENT_PACKAGES || packageName in dynamicPaymentPackages) return
        dynamicPaymentPackages.add(packageName)
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(PREFS_KEY_DYNAMIC, dynamicPaymentPackages.toSet()).apply()
            Log.d(TAG, "Permanently learned new payment package: $packageName")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist payment package: ${e.message}")
        }
    }

    /**
     * Check if the WRITE_SECURE_SETTINGS permission is granted.
     */
    fun hasWriteSecureSettings(context: Context): Boolean {
        return try {
            // Try reading the setting — if we can write, we can read
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            // Try a harmless test write to verify write permission
            val testKey = "jarvis_write_test"
            Settings.Secure.putString(context.contentResolver, testKey, "1")
            Settings.Secure.putString(context.contentResolver, testKey, null)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if a package is a known payment/banking app.
     * Uses static list + persisted + dynamically learned packages.
     * Heuristics are intentionally broad to catch future fintech/crypto apps.
     */
    fun isPaymentApp(context: Context? = null, packageName: String): Boolean {
        if (context != null) ensurePrefsLoaded(context)
        if (packageName in PAYMENT_PACKAGES) return true
        if (packageName in dynamicPaymentPackages) return true
        // Comprehensive heuristic — covers current and future financial apps
        val lower = packageName.lowercase()
        val isSuspected =
            lower.contains("bank")       ||
            lower.contains("upi")        ||
            lower.contains("paisa")      ||
            lower.contains("wallet")     ||
            lower.contains("finserv")    ||
            lower.contains("finance")    ||
            lower.contains("payment")    ||
            lower.contains("pay.")       ||
            lower.contains(".pay")       ||
            lower.contains("crypto")     ||
            lower.contains("bitcoin")    ||
            lower.contains("invest")     ||
            lower.contains("trading")    ||
            lower.contains("brokerage")  ||
            lower.contains("insurance")  ||
            lower.contains("insure")     ||
            lower.contains("loan")       ||
            lower.contains("lending")    ||
            lower.contains("credit")     ||
            lower.contains("mutual")     ||
            lower.contains("stock")      ||
            lower.contains("demat")      ||
            lower.contains("neft")       ||
            lower.contains("imps")       ||
            lower.contains("rtgs")
        if (isSuspected) {
            if (context != null) {
                learnPackage(context, packageName)
            } else {
                dynamicPaymentPackages.add(packageName)
            }
            Log.d(TAG, "Dynamically detected payment app by keyword: $packageName")
        }
        return isSuspected
    }

    // Overload for call sites that don't have a Context (e.g. VoskWakeWordService poller)
    fun isPaymentApp(packageName: String): Boolean = isPaymentApp(null, packageName)

    /**
     * Disables JarvisAccessibilityService by removing it from the
     * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES string.
     *
     * Returns true if successfully disabled.
     */
    fun disableAccessibilityService(context: Context): Boolean {
        return try {
            val componentName = ComponentName(
                context.packageName,
                JarvisAccessibilityService::class.java.name
            ).flattenToString()

            val currentServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            if (!currentServices.contains(componentName)) {
                Log.d(TAG, "Service already disabled in Settings")
                isGuardActive = true
                return true
            }

            // Remove our service from the colon-separated list
            val newServices = currentServices
                .split(":")
                .filter { it != componentName && it.isNotBlank() }
                .joinToString(":")

            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newServices
            )

            isGuardActive = true
            Log.d(TAG, "✓ Disabled accessibility service for payment app safety")
            Log.d("VoskWakeWordSvc", "[PaymentGuard] Accessibility service DISABLED for payment app: $guardTriggerPackage")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: WRITE_SECURE_SETTINGS not granted. Run: adb shell pm grant com.vishnu.jarvis android.permission.WRITE_SECURE_SETTINGS")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable accessibility service: ${e.message}")
            false
        }
    }

    /**
     * Re-enables JarvisAccessibilityService by adding it back to
     * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.
     *
     * Returns true if successfully re-enabled.
     */
    fun enableAccessibilityService(context: Context): Boolean {
        return try {
            val componentName = ComponentName(
                context.packageName,
                JarvisAccessibilityService::class.java.name
            ).flattenToString()

            val currentServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            if (currentServices.contains(componentName)) {
                Log.d(TAG, "Service already enabled in Settings")
                isGuardActive = false
                guardTriggerPackage = null
                return true
            }

            // Add our service back to the list
            val newServices = if (currentServices.isBlank()) {
                componentName
            } else {
                "$currentServices:$componentName"
            }

            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newServices
            )

            isGuardActive = false
            guardTriggerPackage = null
            Log.d(TAG, "✓ Re-enabled accessibility service — payment app closed")
            Log.d("VoskWakeWordSvc", "[PaymentGuard] Accessibility service RE-ENABLED! Welcome back, Jarvis.")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: WRITE_SECURE_SETTINGS not granted")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to re-enable accessibility service: ${e.message}")
            false
        }
    }

    /**
     * Check if the accessibility service is currently enabled in Settings.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val componentName = ComponentName(
                context.packageName,
                JarvisAccessibilityService::class.java.name
            ).flattenToString()

            val currentServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            currentServices.contains(componentName)
        } catch (e: Exception) {
            false
        }
    }
}
