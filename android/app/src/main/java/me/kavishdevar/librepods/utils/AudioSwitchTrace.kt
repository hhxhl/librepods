package me.kavishdevar.librepods.utils

import android.util.Log

/** Compact, cross-component timeline for comparing AACP and Android audio events. */
object AudioSwitchTrace {
    private const val TAG = "AudioSwitchTrace"

    fun event(source: String, event: String, detail: String) {
        Log.i(TAG, "$source | $event | $detail")
    }
}
