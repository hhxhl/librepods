package me.kavishdevar.librepods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Resolve the *phone's* Bluetooth identity; never substitute the remote AirPods address. */
object LocalBluetoothAddressResolver {
    private const val TAG = "LocalBtAddress"
    private val MAC = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
    private const val REDACTED = "02:00:00:00:00:00"

    data class Result(val address: String, val source: String)

    fun canonical(value: String?): String? {
        val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (!MAC.matches(normalized) || normalized == REDACTED ||
            normalized == "00:00:00:00:00:00" || normalized == "FF:FF:FF:FF:FF:FF") return null
        return normalized
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    fun resolve(context: Context, saved: String?): Result? {
        canonical(saved)?.let { return Result(it, "saved") }
        val adapterAddress = try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.address
        } catch (e: SecurityException) {
            Log.d(TAG, "Adapter address restricted by Android")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Adapter address unavailable: ${e.javaClass.simpleName}")
            null
        }
        canonical(adapterAddress)?.let { return Result(it, "bluetooth_adapter") }
        return fromRoot()
    }

    private fun fromRoot(): Result? {
        // No read of bt_config.conf: most addresses in it are *remote* devices.
        // Use only local-address-specific properties / labeled system fields.
        val script = """
            for p in persist.vendor.service.bdroid.bdaddr ro.vendor.bt.boot.macaddr persist.vendor.bluetooth.bdaddr persist.bluetooth.bdaddr; do
                v=$(getprop "${'$'}p")
                [ -n "${'$'}v" ] && echo "${'$'}p=${'$'}v"
            done
            echo "secure.bluetooth_address=${'$'}(settings get secure bluetooth_address)"
            dumpsys bluetooth_manager 2>/dev/null | grep -m 1 -E '^[[:space:]]*(mAddress|Local address|Bluetooth address)[[:space:]]*[:=]' || true
        """.trimIndent()
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Log.w(TAG, "Timed out resolving local Bluetooth address via root")
                null
            } else if (process.exitValue() != 0) {
                Log.d(TAG, "Root address probe unavailable (exit=${process.exitValue()})")
                null
            } else {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.mapNotNull { line ->
                        val trimmed = line.trim()
                        val match = Regex("^([^=:]+)\\s*[=:]\\s*(.+)$").matchEntire(trimmed)
                        if (match == null) null else {
                            canonical(match.groupValues[2])?.let { Result(it, match.groupValues[1].trim()) }
                        }
                    }.firstOrNull()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Root address probe failed: ${e.javaClass.simpleName}")
            null
        } finally {
            process?.destroy()
        }
    }
}
