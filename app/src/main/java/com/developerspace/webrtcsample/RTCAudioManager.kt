package com.developerspace.webrtcsample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import com.dwarsh.webrtcsample.R
import org.webrtc.ThreadUtils
import java.util.Collections

//Removed some Deprecations, updated code as per new syntax

class RTCAudioManager private constructor(private val context: Context) {

    enum class AudioDevice {
        SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, NONE
    }

    enum class AudioManagerState {
        UNINITIALIZED, RUNNING
    }

    interface AudioManagerEvents {
        fun onAudioDeviceChanged(
            selectedAudioDevice: AudioDevice?,
            availableAudioDevices: Set<AudioDevice>?
        )
    }

    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioManagerEvents: AudioManagerEvents? = null
    private var amState = AudioManagerState.UNINITIALIZED

    private var savedAudioMode = AudioManager.MODE_INVALID
    private var savedIsSpeakerPhoneOn = false
    private var savedIsMicrophoneMute = false
    private var hasWiredHeadset = false

    private var defaultAudioDevice: AudioDevice = AudioDevice.SPEAKER_PHONE
    private var selectedAudioDevice: AudioDevice? = null
    private var userSelectedAudioDevice: AudioDevice? = null

    private val audioDevices = mutableSetOf<AudioDevice>()
    private val wiredHeadsetReceiver = WiredHeadsetReceiver()
    private var audioFocusChangeListener: OnAudioFocusChangeListener? = null

    private inner class WiredHeadsetReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val state = intent.getIntExtra("state", STATE_UNPLUGGED)
            val microphone = intent.getIntExtra("microphone", HAS_NO_MIC)
            hasWiredHeadset = state == STATE_PLUGGED
            updateAudioDeviceState()
        }



    }

    fun start(audioManagerEvents: AudioManagerEvents?) {
        ThreadUtils.checkIsOnMainThread()
        if (amState == AudioManagerState.RUNNING) {
            Log.e(TAG, "AudioManager is already active")
            return
        }

        this.audioManagerEvents = audioManagerEvents
        amState = AudioManagerState.RUNNING

        savedAudioMode = audioManager.mode
        savedIsSpeakerPhoneOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn
        }
        savedIsMicrophoneMute = audioManager.isMicrophoneMute
        hasWiredHeadset = hasWiredHeadset()

        audioFocusChangeListener = OnAudioFocusChangeListener { focusChange ->
            Log.d(TAG, "onAudioFocusChange: ${focusChangeToString(focusChange)}")
        }

        val result = audioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d(TAG, "Audio focus request granted for VOICE_CALL streams")
        } else {
            Log.e(TAG, "Audio focus request failed")
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setMicrophoneMute(false)

        userSelectedAudioDevice = AudioDevice.NONE
        selectedAudioDevice = AudioDevice.NONE
        audioDevices.clear()

        updateAudioDeviceState()
        context.registerReceiver(wiredHeadsetReceiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))
    }

    fun stop() {
        ThreadUtils.checkIsOnMainThread()
        if (amState != AudioManagerState.RUNNING) {
            Log.e(TAG, "Trying to stop AudioManager in incorrect state: $amState")
            return
        }

        amState = AudioManagerState.UNINITIALIZED
        context.unregisterReceiver(wiredHeadsetReceiver)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = savedIsSpeakerPhoneOn
        }

        setMicrophoneMute(savedIsMicrophoneMute)
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.abandonAudioFocus(audioFocusChangeListener)
        audioFocusChangeListener = null
        audioManagerEvents = null
    }

    fun setDefaultAudioDevice(defaultDevice: AudioDevice) {
        ThreadUtils.checkIsOnMainThread()
        defaultAudioDevice = when (defaultDevice) {
            AudioDevice.SPEAKER_PHONE -> defaultDevice
            AudioDevice.EARPIECE -> if (hasEarpiece()) defaultDevice else AudioDevice.SPEAKER_PHONE
            else -> {
                Log.e(TAG, "Invalid default audio device selection")
                AudioDevice.SPEAKER_PHONE
            }
        }
        updateAudioDeviceState()
    }

    fun selectAudioDevice(device: AudioDevice) {
        ThreadUtils.checkIsOnMainThread()
        if (device in audioDevices) {
            userSelectedAudioDevice = device
            updateAudioDeviceState()
        } else {
            Log.e(TAG, "Can not select $device from available $audioDevices")
        }
    }

    fun getAudioDevices(): Set<AudioDevice> = Collections.unmodifiableSet(audioDevices)
    fun getSelectedAudioDevice(): AudioDevice? = selectedAudioDevice

    private fun setAudioDeviceInternal(device: AudioDevice?) {
        if (device in audioDevices) {
            when (device) {
                AudioDevice.SPEAKER_PHONE -> setCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                AudioDevice.EARPIECE -> setCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                AudioDevice.WIRED_HEADSET -> setCommunicationDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET)
                else -> Log.e(TAG, "Invalid audio device selection")
            }
            selectedAudioDevice = device
        }
    }

    @Suppress("DEPRECATION")
    private fun setCommunicationDevice(deviceType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.firstOrNull { it.type == deviceType }?.let {
                audioManager.setCommunicationDevice(it)
            }
        } else {
            audioManager.isSpeakerphoneOn = deviceType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
    }

    private fun setMicrophoneMute(on: Boolean) {
        if (audioManager.isMicrophoneMute != on) {
            audioManager.isMicrophoneMute = on
        }
    }

    private fun hasEarpiece(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    private fun hasWiredHeadset(): Boolean {
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).any { device ->
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || device.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
    }

    fun updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread()

        val newAudioDevices = mutableSetOf<AudioDevice>().apply {
            if (hasWiredHeadset) {
                add(AudioDevice.WIRED_HEADSET)
            } else {
                add(AudioDevice.SPEAKER_PHONE)
                if (hasEarpiece()) add(AudioDevice.EARPIECE)
            }
        }

        val audioDeviceSetUpdated = audioDevices != newAudioDevices
        audioDevices.clear()
        audioDevices.addAll(newAudioDevices)

        userSelectedAudioDevice = when {
            hasWiredHeadset && userSelectedAudioDevice == AudioDevice.SPEAKER_PHONE -> AudioDevice.WIRED_HEADSET
            !hasWiredHeadset && userSelectedAudioDevice == AudioDevice.WIRED_HEADSET -> AudioDevice.SPEAKER_PHONE
            else -> userSelectedAudioDevice
        }

        val newAudioDevice = when {
            hasWiredHeadset -> AudioDevice.WIRED_HEADSET
            else -> defaultAudioDevice
        }

        if (newAudioDevice != selectedAudioDevice || audioDeviceSetUpdated) {
            setAudioDeviceInternal(newAudioDevice)
            audioManagerEvents?.onAudioDeviceChanged(selectedAudioDevice, audioDevices)
        }
    }

    private fun focusChangeToString(focusChange: Int): String = when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "AUDIOFOCUS_GAIN_TRANSIENT"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"
        AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
        else -> "AUDIOFOCUS_INVALID"
    }

    companion object {

        private const val STATE_UNPLUGGED = 0
        private const val STATE_PLUGGED = 1
        private const val HAS_NO_MIC = 0
        private const val HAS_MIC = 1
        private const val TAG = "AppRTCAudioManager"
        private const val SPEAKERPHONE_AUTO = "auto"
        private const val SPEAKERPHONE_TRUE = "true"
        private const val SPEAKERPHONE_FALSE = "false"

        fun create(context: Context): RTCAudioManager {
            return RTCAudioManager(context).apply {
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                val useSpeakerphone = sharedPreferences.getString(
                    context.getString(R.string.pref_speakerphone_key),
                    context.getString(R.string.pref_speakerphone_default)
                )

                defaultAudioDevice = when (useSpeakerphone) {
                    SPEAKERPHONE_FALSE -> if (hasEarpiece()) AudioDevice.EARPIECE else AudioDevice.SPEAKER_PHONE
                    else -> AudioDevice.SPEAKER_PHONE
                }
            }
        }
    }

}