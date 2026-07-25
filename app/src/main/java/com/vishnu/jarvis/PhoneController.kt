package com.vishnu.jarvis

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

class PhoneController(private val context: Context) {

    private var flashEnabled = false

    fun flashlight(on: Boolean): Boolean {
        return try {
            val manager =
                context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val cameraId = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false

            manager.setTorchMode(cameraId, on)
            flashEnabled = on
            true
        } catch (e: Exception) {
            false
        }
    }

    fun flashlightToggle(): Boolean {
        return flashlight(!flashEnabled)
    }

    fun volumeUp() {
        val audio =
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_RAISE,
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun volumeDown() {
        val audio =
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun mute() {
        val audio =
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_MUTE,
                AudioManager.FLAG_SHOW_UI
            )
        }
    }

    fun openBluetoothSettings() {
        context.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun openWifiSettings() {
        context.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun openHotspotSettings() {
        context.startActivity(
            Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun openBrightnessSettings() {
        context.startActivity(
            Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun openSoundSettings() {
        context.startActivity(
            Intent(Settings.ACTION_SOUND_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun openBatterySettings() {
        context.startActivity(
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun openLocationSettings() {
        context.startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}