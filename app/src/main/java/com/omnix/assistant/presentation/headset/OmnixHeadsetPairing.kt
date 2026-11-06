package com.omnix.assistant.presentation.headset

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Имя, под которым наушники видны на этом телефоне после регистрации. */
const val OMNIX_HEADSET_ALIAS = "OMNIX"

/** Итог регистрации: адрес и имя наушников, которые человек выбрал в окне. */
data class PairedHeadset(val address: String, val name: String)

/**
 * Регистрация наушников через системное окно «Связать устройство»
 * (CompanionDeviceManager). Окно даёт адрес выбранного устройства без
 * поиска вручную, а связь «приложение-спутник» разрешает дать наушникам имя
 * [OMNIX_HEADSET_ALIAS] на этом телефоне (BluetoothDevice.setAlias, Android 12+).
 *
 * Возвращает функцию запуска; результат приходит в [onPaired], ошибка —
 * в [onFailed] (текст системы или null, если человек закрыл окно).
 */
@Composable
fun rememberOmnixHeadsetPairing(
    onPaired: (PairedHeadset) -> Unit,
    onFailed: (String?) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            onFailed(null)
            return@rememberLauncherForActivityResult
        }
        val paired = readPairedHeadset(context, result.data)
        if (paired == null) onFailed(null) else onPaired(paired)
    }
    return remember(context) {
        {
            startAssociation(
                context = context,
                onChooser = { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) },
                onFailed = onFailed
            )
        }
    }
}

private fun startAssociation(
    context: Context,
    onChooser: (IntentSender) -> Unit,
    onFailed: (String?) -> Unit
) {
    val manager = context.getSystemService(CompanionDeviceManager::class.java)
    if (manager == null) {
        onFailed(null)
        return
    }
    val request = AssociationRequest.Builder()
        .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
        .setSingleDevice(false)
        .build()
    runCatching {
        @Suppress("DEPRECATION")
        manager.associate(
            request,
            object : CompanionDeviceManager.Callback() {
                // До Android 13 окно приходит сюда; на 13+ onAssociationPending
                // по умолчанию вызывает этот же метод.
                @Deprecated("Deprecated in Java")
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    onChooser(chooserLauncher)
                }

                override fun onFailure(error: CharSequence?) {
                    onFailed(error?.toString())
                }
            },
            null
        )
    }.onFailure {
        Log.w(TAG, "associate failed", it)
        onFailed(it.message)
    }
}

@SuppressLint("MissingPermission")
private fun readPairedHeadset(context: Context, data: Intent?): PairedHeadset? {
    data ?: return null
    val device: BluetoothDevice? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            data.getParcelableExtra<BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE)
        }
    }.getOrNull()
    val address = device?.address ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching {
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                ?.deviceMacAddress?.toString()
        }.getOrNull()
    } else {
        null
    }
    address ?: return null
    val name = runCatching { device?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: address
    renameToOmnix(context, address)
    return PairedHeadset(address = address, name = name)
}

/** Имя «OMNIX» в списке Bluetooth этого телефона; без прошивки — только здесь. */
@SuppressLint("MissingPermission")
private fun renameToOmnix(context: Context, address: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        adapter.getRemoteDevice(address.uppercase()).setAlias(OMNIX_HEADSET_ALIAS)
    }.onFailure { Log.w(TAG, "setAlias failed", it) }
}

private const val TAG = "OmnixHeadsetPairing"
