# Reliable Payment Guard for Jarvis

Improve the Payment Guard to reliably disable the Jarvis Accessibility Service before payment apps open and re-enable it only when they are closed or moved to the background (recents).

## User Review Required

> [!IMPORTANT]
> The Payment Guard requires **Usage Access** permission to reliably monitor other apps in the background. Without this, re-enabling Jarvis when a payment app is closed will have significant lag (up to 5 seconds) or might fail.

> [!NOTE]
> Re-enabling will now specifically look for "safe" states like the home screen (launcher) or the system recents screen to ensure Jarvis doesn't pop back up while the user is still in a banking app.

## Proposed Changes

### 1. PaymentAppGuard Enhancements

Update `PaymentAppGuard.kt` to handle permission checks and state management more robustly.

#### [MODIFY] [PaymentAppGuard.kt](file:///D:/experiments/JarvisAgent.mobile/app/src/main/java/com/vishnu/jarvis/PaymentAppGuard.kt)
- Add `hasUsageStatsPermission(context: Context): Boolean` to check for Usage Access.
- Add `getLauncherPackage(context: Context): String` to identify the home screen.
- Improve `isPaymentApp` to handle a broader range of keywords and packages.

### 2. Service Monitoring Logic

Update both wake word services to use a smarter polling mechanism that avoids the "immediate re-enable" bug.

#### [MODIFY] [OpenWakeWordService.kt](file:///D:/experiments/JarvisAgent.mobile/app/src/main/java/com/vishnu/jarvis/Openwakewordservice.kt)
#### [MODIFY] [VoskWakeWordService.kt](file:///D:/experiments/JarvisAgent.mobile/app/src/main/java/com/vishnu/jarvis/VoskWakeWordService.kt)
- Update `getForegroundPackage()` to use `UsageStatsManager` as the primary method if permission is granted.
- In `startProactivePaymentGuard()`, ensure re-enabling happens ONLY when `foregroundPkg` is non-null and NOT a payment app (or is the launcher/recents).
- Add a "settle period" to avoid rapid toggling if the OS is reporting changing window states.

### 3. Permissions & UI

Update `MainActivity` to prompt for Usage Access permission.

#### [MODIFY] [MainActivity.kt](file:///D:/experiments/JarvisAgent.mobile/app/src/main/java/com/vishnu/jarvis/MainActivity.kt)
- Add check for Usage Access in `onCreate` or `checkPermissions`.
- Show a Toast/Dialog if Usage Access is missing, as it's critical for the Payment Guard.

## Verification Plan

### Automated Tests
- N/A (requires cross-app interaction)

### Manual Verification
1.  **Grant Permissions**: Ensure `WRITE_SECURE_SETTINGS` and `Usage Access` are granted.
2.  **Launch Payment App**: Open a banking app (e.g., PhonePe, GPay, or YONO SBI). Verify Jarvis Accessibility Service is disabled *before* the app's splash screen finishes.
3.  **Go to Recents**: While in the payment app, open the Recents screen. Verify if Jarvis is re-enabled.
4.  **Close Payment App**: Close the payment app and return to the home screen. Verify Jarvis is re-enabled.
5.  **Re-entry**: Re-open the payment app immediately. Verify Jarvis is disabled again.
