package com.omnix.assistant.core.headset

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.omnix.assistant.core.admin.AdminModelStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Следит, подключены ли наушники OMNIX, и решает, открыт ли ассистент
 * ([OmnixHeadsetPolicy]). Подключённые аудиоустройства берутся из профилей
 * Bluetooth (гарнитура, A2DP, LE Audio): только там есть адрес устройства.
 * Адрес и имя требуют разрешения BLUETOOTH_CONNECT (Android 12+); без него
 * состояние — [LockReason.NO_PERMISSION], и экран блокировки просит его.
 */
@Singleton
class OmnixHeadsetGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: OmnixHeadsetRegistry,
    adminStore: AdminModelStore
) {
    private data class Environment(val hasPermission: Boolean, val bluetoothOn: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val proxies = ConcurrentHashMap<Int, BluetoothProfile>()

    private val _connected = MutableStateFlow<List<ConnectedHeadset>>(emptyList())

    /** Подключённые сейчас аудиоустройства — для экрана регистрации у админа. */
    val connected: StateFlow<List<ConnectedHeadset>> = _connected.asStateFlow()

    private val environment = MutableStateFlow(readEnvironment())

    val state: StateFlow<HeadsetGateState> = combine(
        adminStore.state,
        registry.headsets,
        registry.enforceForAdmin,
        _connected,
        environment
    ) { admin, headsets, enforce, connected, env ->
        OmnixHeadsetPolicy.decide(
            isAdmin = admin.isAdmin,
            enforceForAdmin = enforce,
            hasPermission = env.hasPermission,
            bluetoothOn = env.bluetoothOn,
            connected = connected,
            registered = headsets.mapTo(HashSet()) { it.address }
        )
    }.stateIn(scope, SharingStarted.Eagerly, HeadsetGateState.Checking)

    /** Можно ли сейчас отвечать на слово вызова и запросы. */
    fun allowsAssistant(): Boolean = state.value is HeadsetGateState.Unlocked

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (proxy != null) proxies[profile] = proxy
            recomputeConnected()
        }

        override fun onServiceDisconnected(profile: Int) {
            proxies.remove(profile)
            recomputeConnected()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    init {
        runCatching {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }.onFailure { Log.w(TAG, "register receiver failed", it) }
        requestProxies()
    }

    /**
     * Перечитать всё заново: после выдачи разрешения, включения Bluetooth,
     * подключения наушников или возврата в приложение.
     */
    fun refresh() {
        environment.value = readEnvironment()
        if (proxies.isEmpty()) requestProxies()
        recomputeConnected()
    }

    private fun requestProxies() {
        val bt = adapter ?: return
        val profiles = buildList {
            add(BluetoothProfile.HEADSET)
            add(BluetoothProfile.A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(BluetoothProfile.LE_AUDIO)
        }
        for (profile in profiles) {
            if (proxies.containsKey(profile)) continue
            runCatching { bt.getProfileProxy(context, profileListener, profile) }
                .onFailure { Log.w(TAG, "profile proxy $profile failed", it) }
        }
    }

    // Разрешение проверено первой строкой; SecurityException (разрешение
    // отозвали между проверкой и вызовом) ловится у каждого вызова.
    @SuppressLint("MissingPermission")
    private fun recomputeConnected() {
        if (!hasConnectPermission()) {
            _connected.value = emptyList()
            return
        }
        val found = LinkedHashMap<String, ConnectedHeadset>()
        for (proxy in proxies.values) {
            val devices = try {
                proxy.connectedDevices.orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
            for (device in devices) {
                val address = OmnixHeadsetPolicy.normalize(device.address ?: continue)
                val name = try {
                    device.name
                } catch (_: SecurityException) {
                    null
                }
                found.putIfAbsent(address, ConnectedHeadset(address, name))
            }
        }
        _connected.value = found.values.toList()
    }

    @SuppressLint("MissingPermission")
    private fun readEnvironment(): Environment {
        val on = try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }
        return Environment(hasPermission = hasConnectPermission(), bluetoothOn = on)
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "OmnixHeadsetGate"

        /** BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED (API 33). */
        const val ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED =
            "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED"
    }
}
