# JarvisAgent Mobile 🤖

JarvisAgent Mobile is a highly advanced, ultra-responsive AI voice assistant for Android. It is designed to run silently in the background, listening for the "Hey Jarvis" wake word using a state-of-the-art ONNX machine learning model, and seamlessly taking control of your device to execute complex tasks.

## 🚀 Key Features

*   **Zero-Latency Wake Word Engine**: Powered by a highly optimized ONNX model (`openWakeWord`), Jarvis detects the "Hey Jarvis" wake word locally on your device with lightning speed and zero internet connection required.
*   **Hardware DSP Audio Processing**: Jarvis taps directly into your phone's hardware chips to apply **Acoustic Echo Cancellation (AEC)** and **Noise Suppression (NS)**. If you are blasting music on Spotify, Jarvis mathematically cancels out the music so he can hear your command perfectly.
*   **Seamless Audio Focus Ducking**: The moment you say "Hey Jarvis," any background music or video automatically lowers in volume (ducks) or pauses, allowing you to speak your command clearly. Once the command is finished, your media instantly resumes.
*   **True Background Execution & Lock Screen Waking**: Jarvis operates entirely in the background. If your phone is locked and the screen is off, saying the wake word will wake the screen and execute your command without forcing you to manually unlock your device.
*   **Automated Lock Screen Bypass**: For commands that require the device to be unlocked (like sending a WhatsApp message), Jarvis uses an advanced Accessibility Service to automatically swipe up and enter your PIN code, completely bypassing the lock screen on your behalf.
*   **Advanced Accessibility Control**: Jarvis can manipulate UI elements on your screen natively. For example, he automatically hunts down and clicks "Skip Ad" buttons on YouTube—even adapting to YouTube's latest UI changes and hidden developer IDs.
*   **Instantaneous Command Recognition**: The artificial TTS delay has been completely ripped out. The absolute microsecond Jarvis hears his name, the microphone opens for your command.

## 🛠️ Architecture

*   **`OpenWakeWordService`**: The primary background service that continuously monitors raw, unboosted microphone audio (using `VOICE_RECOGNITION`) for the wake word. It manages hardware DSP effects and triggers the command router.
*   **`VoskWakeWordService`**: A fallback offline speech recognition engine.
*   **`JarvisAccessibilityService`**: The core engine that allows Jarvis to read the screen, automatically unlock the device, skip YouTube ads, and execute complex UI flows (like navigating Android settings or sending messages).
*   **`LockScreenWaker` & `UnlockBouncerActivity`**: Specialized components designed to wake the device from a deep sleep state and trigger the native Android PIN bouncer for automated unlocking.

## ⚙️ Installation

1.  Clone this repository to your local machine.
2.  Open the project in **Android Studio**.
3.  Build and run the app on your Android device (ensure you grant Microphone and Accessibility permissions).
4.  **Note**: This app requires specific permissions to function as a true background assistant. Ensure `JarvisAccessibilityService` is enabled in your Android Accessibility Settings.

## 🛡️ Privacy & Security

Jarvis processes the wake word **100% locally** on your device. Microphone data is never sent to the cloud for wake word detection. The Accessibility Service is used strictly for executing user-requested commands and automating tasks like skipping ads or bypassing the lock screen at the user's explicit request.

---
*Developed with the goal of creating the ultimate Android voice assistant.*
