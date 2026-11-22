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
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
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
import me.kavishdevar.librepods.services.AirPodsService
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
    private const val PAUSED_FOR_A2DP = "system_scan_paused_for_a2dp"
    private const val COOLDOWN_MS = 30_000L
    private const val CONNECTION_TIMEOUT_MS = 8_000L
    private const val REGISTRATION_COOLDOWN_MS = 15_000L
    private const val TRACE_INTERVAL_MS = 5_000L
    private var lastSuccessfulRegistrationMs = 0L
    private var lastScanTraceMs = 0L

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
    fun register(context: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        // Starting scans repeatedly can reset match tracking and miss a brief open-case event.
        if (!force && lastSuccessfulRegistrationMs > 0L &&
            now >= lastSuccessfulRegistrationMs &&
            now - lastSuccessfulRegistrationMs < REGISTRATION_COOLDOWN_MS
        ) {
            Log.d(TAG, "System scan already registered recently; keeping existing scan")
            return
        }
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "System scan not registered: Bluetooth permissions not granted")
            return
        }
        val prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("system_managed_auto_connect", true)) return
        // Persist the pause across process deaths. A2DP's system broadcast clears
        // it when the paired AirPods disconnect. Force registration is reserved
        // for system startup / Bluetooth enabling / app replacement.
        if (!force && prefs.getBoolean(PAUSED_FOR_A2DP, false)) {
            Log.d(TAG, "System scan paused while AirPods A2DP is connected")
            return
        }
        if (prefs.getString("mac_address", null).isNullOrBlank() || readIrk(context) == null) {
            Log.i(TAG, "System scan pending: paired device address or AirPods IRK unavailable")
            return
        }

        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            if (!adapter.isEnabled) return
            val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner ?: return

            // Match only the stable AirPods advertising prefix in the controller.
            // Do not use FIRST_MATCH: the same device may remain nearby across
            // case-close/open events and FIRST_MATCH need not fire again.
            // Lid/identity/audio checks are performed in the receiver.
            val bytes = byteArrayOf(0x07, 0x19)
            val mask = byteArrayOf(0xff.toByte(), 0xff.toByte())
            val filter = ScanFilter.Builder()
                .setManufacturerData(0x004c, bytes, mask)
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(1_500L)
                .build()

            // Refresh registrations after Bluetooth restarts / APK updates; the scan
            // remains registered with Bluetooth even when our process exits.
            scanner.stopScan(scanIntent(context))
            val result = scanner.startScan(listOf(filter), settings, scanIntent(context))
            Log.i(TAG, "System-managed BLE scan registration result=$result")
            if (result == 0) {
                lastSuccessfulRegistrationMs = SystemClock.elapsedRealtime()
                if (force) prefs.edit().putBoolean(PAUSED_FOR_A2DP, false).apply()
            }
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

    /** Stop PendingIntent scan deliveries while the paired AirPods A2DP is connected. */
    @SuppressLint("MissingPermission")
    fun onAirPodsA2dpConnected(context: Context) {
        val prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PAUSED_FOR_A2DP, false)) return
        prefs.edit().putBoolean(PAUSED_FOR_A2DP, true).apply()
        unregister(context)
        lastSuccessfulRegistrationMs = 0L
        Log.i(TAG, "AirPods A2DP connected; system BLE scan stopped")
    }

    /** Resume discovery only when the paired AirPods A2DP profile disconnects. */
    fun onAirPodsA2dpDisconnected(context: Context) {
        val prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        // A previous connect request cooldown should not prevent a scan registration.
        prefs.edit().putBoolean(PAUSED_FOR_A2DP, false).apply()
        lastSuccessfulRegistrationMs = 0L
        Log.i(TAG, "AirPods A2DP disconnected; resuming system BLE scan")
        register(context, force = true)
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
        if (context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(PAUSED_FOR_A2DP, false)) {
            // A few scan PendingIntents can be queued before stopScan completes.
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
            Log.w(TAG, "System scan callback received but the saved AirPods IRK is unavailable")
            finish()
            return
        }

        // Count each rejection stage without logging BLE addresses or key material.
        var validPayloads = 0
        var identityMatches = 0
        var openMatches = 0
        var idleMatches = 0
        for (result in results) {
            try {
                val data = result.scanRecord?.getManufacturerSpecificData(0x004c)
                if (data == null || data.size <= 10) continue
                validPayloads++
                if (!BluetoothCryptography.verifyRPA(result.device.address, irk)) continue
                identityMatches++
                if ((data[8].toInt() and 0x08) != 0) continue
                openMatches++
                // 0x00 disconnected; 0x04 idle. Never auto-join active iPhone media/calls.
                if ((data[10].toInt() and 0xff) !in setOf(0x00, 0x04)) continue
                idleMatches++
                break
            } catch (e: Exception) {
                Log.w(TAG, "Unable to parse a system BLE scan result", e)
            }
        }

        val now = SystemClock.elapsedRealtime()
        if (idleMatches > 0 || lastScanTraceMs == 0L || now - lastScanTraceMs >= TRACE_INTERVAL_MS) {
            lastScanTraceMs = now
            Log.i(TAG, "System scan callback: results=${results.size}, valid=$validPayloads, " +
                "trusted=$identityMatches, open=$openMatches, idle=$idleMatches")
        }
        if (idleMatches == 0) {
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
            Log.d(TAG, "A2DP reconnect suppressed by cooldown")
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
                        Log.i(TAG, "A2DP profile proxy obtained, profile=$profile")
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
                        } else if (profile == BluetoothProfile.A2DP) {
                            Log.d(TAG, "A2DP already connected; no reconnect necessary")
                            // Catch connections which preceded scan registration or whose
                            // connection broadcast was missed during process startup.
                            onAirPodsA2dpConnected(context)
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
            Log.i(TAG, "A2DP profile proxy request accepted=$requested")
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

/** Receives protected platform profile and ACL events without a resident app process. */
class SystemAirPodsA2dpStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED &&
            action != BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED &&
            action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED) return

        val target = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("mac_address", null) ?: return
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            ?: return
        if (!device.address.equals(target, ignoreCase = true)) return

        if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            // A2DP may disconnect while a call still uses HFP. Only the physical
            // ACL disconnect is used to end the connected-device service.
            context.stopService(Intent(context, AirPodsService::class.java))
            Log.i("AirPodsSystemScan", "Paired AirPods ACL disconnected; service stopped")
            return
        }

        when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
            BluetoothProfile.STATE_CONNECTED -> {
                if (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                    SystemAirPodsAutoConnect.onAirPodsA2dpConnected(context.applicationContext)
                }
                AirPodsService.startForConnectedDevice(context.applicationContext)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                if (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                    SystemAirPodsAutoConnect.onAirPodsA2dpDisconnected(context.applicationContext)
                }
                // Do not end the service on a single profile disconnect.
            }
        }
    }
}
