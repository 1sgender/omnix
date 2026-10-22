package com.omnix.assistant.presentation.state

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.omnix.assistant.data.preferences.OmnixExperienceStore
import com.omnix.assistant.voice.audio.BluetoothAudioRouter
import com.omnix.assistant.voice.audio.BluetoothAudioState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of Clip state for the frontend (§13, §23, §40).
 *
 * In this build the OMNIX Clip is the connected Bluetooth audio device that
 * `BluetoothAudioRouter` already owns. This repository does not open a second
 * device stack — it translates the real audio state into the product's own
 * vocabulary and adds the two facts the UI needs and the router does not
 * store: whether the adapter is on, and when the Clip was last seen.
 *
 * Capabilities the device genuinely does not report (battery, firmware,
 * "find my Clip") are expressed as [ClipCapability.Unavailable] rather than
 * being invented (§3, §33).
 */
@Singleton
class ClipRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothAudioRouter: BluetoothAudioRouter,
    private val experienceStore: OmnixExperienceStore
) {
    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** True when the system Bluetooth adapter is switched on. */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /** True when the device has no Bluetooth radio at all. */
    fun hasBluetoothHardware(): Boolean = bluetoothAdapter != null

    /**
     * Whether Bluetooth is on, live. Reading [isBluetoothEnabled] once per
     * emission left «Bluetooth is off» on screen after the user turned it on
     * and connected earbuds (owner report 2026-10-21): nothing else emitted.
     * The state broadcast needs no permission.
     */
    private val bluetoothOn: Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                trySend(state == BluetoothAdapter.STATE_ON)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        trySend(isBluetoothEnabled())
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    /**
     * Live Clip state.
     *
     * @param searching set by the pairing screen while it is actively looking,
     *        so "Searching" is a real UI intent rather than a guess.
     */
    fun clipState(searching: Flow<Boolean>): Flow<ClipState> = combine(
        combine(bluetoothAudioRouter.audioState, bluetoothAudioRouter.isHeadsetPlugged, ::Pair),
        combine(experienceStore.clipLastSeenMillis, experienceStore.clipLastDeviceName, ::Pair),
        bluetoothOn,
        searching
    ) { (audioState, plugged), (lastSeen, lastName), btOn, isSearching ->
        when {
            !hasBluetoothHardware() -> ClipState.Disconnected(lastName, lastSeen)

            audioState is BluetoothAudioState.Connected -> ClipState.Connected(
                deviceName = audioState.deviceName,
                // The Android audio stack does not expose a headset battery
                // level for arbitrary devices; we never fabricate one.
                battery = ClipCapability.Unavailable,
                isSingleEarbud = audioState.isSingleEarbud
            )

            audioState is BluetoothAudioState.Connecting ->
                ClipState.Connecting(lastName.orEmpty())

            // A wired headset still means "OMNIX can hear you", so it is a
            // connected Clip from the user's point of view.
            plugged -> ClipState.Connected(
                deviceName = lastName.orEmpty(),
                battery = ClipCapability.Unavailable
            )

            !btOn -> ClipState.BluetoothOff

            isSearching -> ClipState.Searching

            else -> ClipState.Disconnected(lastName, lastSeen)
        }
    }

    /** Records a real connection so "Last seen" is never invented (§23). */
    suspend fun onClipConnected(deviceName: String) {
        experienceStore.recordClipConnected(deviceName, System.currentTimeMillis())
    }

    /** Refreshes the "last seen" stamp while the Clip stays connected. */
    suspend fun onClipStillConnected() {
        experienceStore.recordClipSeen(System.currentTimeMillis())
    }

    /** Asks the audio layer to re-evaluate the current connection. */
    fun refresh(): Boolean = bluetoothAudioRouter.checkHeadsetConnection()

    /** Hot state for callers that do not drive a search themselves. */
    fun clipStateIn(scope: CoroutineScope, searching: Flow<Boolean>) =
        clipState(searching).stateIn(scope, SharingStarted.WhileSubscribed(5_000), ClipState.Unknown)
}
