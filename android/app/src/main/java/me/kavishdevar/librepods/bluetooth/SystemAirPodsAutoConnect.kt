/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or later.
 */
package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import me.kavishdevar.librepods.utils.BluetoothCryptography

/**
 * Background auto-connect with a system-owned, filtered BLE PendingIntent scan.
 * No AirPodsService / AACP process is started by a scan result.
 *
 * Deliberately conservative: do not connect when the advertisements show
 * media/call usage; never send an AACP takeover or media play command.
 */
object SystemAirPodsAutoConnect {
    private const val TAG = "AirPodsSystemScan"
    private const val ACTION_SCAN = "me.kavishdevar.librepods.SYSTEM_AIRPODS_SCAN"
    private const val SETTINGS = "settings"
    private const val COOLDOWN_MS = 30_000L
    private const val CONNECTION_TIMEOUT_MS = 8_000L

    private fun scanIntent(context: Context): PendingIntent {
        val intent = Intent(context, SystemAirPodsScanReceiver::class.java).apply {
            action = ACTION_SCAN
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    @SuppressLint("MissingPermission")
    fun register(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "System scan not registered: Bluetooth permissions not granted")
            return
        }
        val prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("system_managed_auto_connect", true)) return
        if (prefs.getString("mac_address", null).isNullOrBlank() || readIrk(context) == null) {
            Log.i(TAG, "System scan pending: paired device address or AirPods IRK unavailable")
            return
        }

        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            if (!adapter.isEnabled) return
            val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner ?: return

            // Apple manufacturer data: 07 19 ... ; payload byte 8, bit 3 == 0 when lid open.
            // Restricting at the controller level avoids waking the app for closed cases.
            val bytes = ByteArray(27)
            val mask = ByteArray(27)
            bytes[0] = 7
            bytes[1] = 25
            mask[0] = 0xff.toByte()
            mask[1] = 0xff.toByte()
            mask[8] = 0x08
            val filter = ScanFilter.Builder()
                .setManufacturerData(0x004c, bytes, mask)
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                .build()

            // Refresh registrations after Bluetooth restarts / APK updates; the scan
            // remains registered with Bluetooth even when our process exits.
            scanner.stopScan(scanIntent(context))
            val result = scanner.startScan(listOf(filter), settings, scanIntent(context))
            Log.i(TAG, "System-managed BLE scan registration result=$result")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register system BLE scan", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun unregister(context: Context) {
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            adapter.bluetoothLeScanner?.stopScan(scanIntent(context))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister system BLE scan", e)
        }
    }

    private fun readIrk(context: Context): ByteArray? {
        val encoded = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .getString(AACPManager.Companion.ProximityKeyType.IRK.name, null) ?: return null
        return try {
            Base64.decode(encoded, Base64.DEFAULT).takeIf { it.size == 16 }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun handleScan(context: Context, intent: Intent, finish: () -> Unit) {
        if (intent.action != ACTION_SCAN) {
            finish()
            return
        }
        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (errorCode != 0) {
            Log.w(TAG, "System BLE scan error=$errorCode")
            finish()
            return
        }
        val results = intent.getParcelableArrayListExtra(
            BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java
        ).orEmpty()
        val irk = readIrk(context)
        if (irk == null) {
            finish()
            return
        }
        val allowed = results.any { result ->
            try {
                val data = result.scanRecord?.getManufacturerSpecificData(0x004c)
                if (data == null || data.size <= 10) return@any false
                val knownAirPods = BluetoothCryptography.verifyRPA(result.device.address, irk)
                val lidOpen = (data[8].toInt() and 0x08) == 0
                // 0x00 disconnected; 0x04 idle. Avoid disturbing iPhone media/calls.
                val noActiveAudio = (data[10].toInt() and 0xff) in setOf(0x00, 0x04)
                knownAirPods && lidOpen && noActiveAudio
            } catch (_: Exception) {
                false
            }
        }
        if (!allowed) {
            finish()
            return
        }
        connectKnownDevice(context, finish)
    }

    @SuppressLint("MissingPermission")
    private fun connectKnownDevice(context: Context, finish: () -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        val prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("system_managed_auto_connect", true)) {
            finish()
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            finish()
            return
        }
        val address = prefs.getString("mac_address", "").orEmpty()
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (device == null || device.bondState != BluetoothDevice.BOND_BONDED) {
            Log.w(TAG, "No bonded Classic Bluetooth AirPods device; skipping reconnect")
            finish()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val last = prefs.getLong("system_scan_last_connect_elapsed", 0)
        if (last > 0 && now >= last && now - last < COOLDOWN_MS) {
            finish()
            return
        }
        prefs.edit().putLong("system_scan_last_connect_elapsed", now).apply()

        val handler = Handler(Looper.getMainLooper())
        var finished = false
        fun complete() {
            if (!finished) {
                finished = true
                finish()
            }
        }
        handler.postDelayed({ complete() }, CONNECTION_TIMEOUT_MS)
        try {
            val requested = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    try {
                        // The receiver has a bounded lifetime. Never initiate an
                        // audio connection after the async broadcast already finished.
                        if (finished) return
                        if (profile == BluetoothProfile.A2DP &&
                            proxy.connectedDevices.none { it.address.equals(device.address, ignoreCase = true) }
                        ) {
                            // This is only a Bluetooth profile connection; no AACP ownership
                            // transfer, media playback, or HFP call profile modification.
                            if (context.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
                                val method = proxy.javaClass.getMethod(
                                    "setConnectionPolicy", BluetoothDevice::class.java, Integer.TYPE
                                )
                                method.invoke(proxy, device, 100)
                            }
                            val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                            val success = method.invoke(proxy, device)
                            Log.i(TAG, "Requested A2DP reconnect; result=$success")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "A2DP reconnect request failed", e)
                    } finally {
                        adapter.closeProfileProxy(profile, proxy)
                        complete()
                    }
                }
                override fun onServiceDisconnected(profile: Int) { complete() }
            }, BluetoothProfile.A2DP)
            if (!requested) complete()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to obtain A2DP profile", e)
            complete()
        }
    }
}

/** Receives system scan matches without having a long-running process or service. */
class SystemAirPodsScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                SystemAirPodsAutoConnect.handleScan(appContext, intent) { result.finish() }
            } catch (e: Exception) {
                Log.e("AirPodsSystemScan", "Scan receiver failed", e)
                result.finish()
            }
        }
    }
}
