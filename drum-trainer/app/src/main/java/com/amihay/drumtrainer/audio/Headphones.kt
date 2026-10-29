package com.amihay.drumtrainer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

enum class AudioRoute { SPEAKER, WIRED, BLUETOOTH }

/**
 * Where the app's sound goes. Wired (or USB) headphones keep the app out of the
 * microphone; Bluetooth adds a long, unsteady delay that no calibration can remove.
 */
fun currentAudioRoute(context: Context): AudioRoute {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val types = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
    return when {
        types.any { it in WIRED } -> AudioRoute.WIRED
        types.any { it in BLUETOOTH } -> AudioRoute.BLUETOOTH
        else -> AudioRoute.SPEAKER
    }
}

private val WIRED = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
)

private val BLUETOOTH = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
)
