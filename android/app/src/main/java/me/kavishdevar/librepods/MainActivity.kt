/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods

// import me.kavishdevar.librepods.screens.Onboarding
// import me.kavishdevar.librepods.utils.RadareOffsetFinder
//import dagger.hilt.android.AndroidEntryPoint
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.android.play.core.review.ReviewManagerFactory
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.ControlCommandRepository
import me.kavishdevar.librepods.presentation.navigation.NavigationRoot
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.bluetooth.SystemAirPodsAutoConnect
import me.kavishdevar.librepods.utils.XposedState
import kotlin.io.encoding.ExperimentalEncodingApi

lateinit var connectionStatusReceiver: BroadcastReceiver
lateinit var testReviewReceiver: BroadcastReceiver

//@AndroidEntryPoint
@ExperimentalMaterial3Api
class MainActivity : ComponentActivity() {
    companion object {
        init {
            if (XposedState.isAvailable && XposedState.bluetoothScopeEnabled) {
                System.loadLibrary("l2c_fcr_hook")
            }
        }
    }

    @ExperimentalHazeMaterialsApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Background discovery is registered with the Bluetooth stack, not this Activity.
        SystemAirPodsAutoConnect.register(applicationContext)

        setContent {
            val sharedPreferences = LocalContext.current.getSharedPreferences("settings", MODE_PRIVATE)
            val m3eEnabled = remember { mutableStateOf(sharedPreferences.getBoolean("m3e_enabled", true)) }

            val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                when (key) {
                    "m3e_enabled" -> m3eEnabled.value = sharedPreferences.getBoolean(key, true)
                }
            }

            DisposableEffect(Unit) {
                sharedPreferences.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
                onDispose {
                    sharedPreferences.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
                }
            }
            LibrePodsTheme(
                m3eEnabled = m3eEnabled.value
            ) {
//                For demo screenshots
//                val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
//                windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
//                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())

                Main()
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(connectionStatusReceiver)
            Log.d("MainActivity", "Unregistered receiver")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error while unregistering receiver: $e")
        }
        sendBroadcast(Intent(AirPodsNotifications.DISCONNECT_RECEIVERS))
        super.onDestroy()
    }

    override fun onStop() {
        try {
            unregisterReceiver(connectionStatusReceiver)
            Log.d("MainActivity", "Unregistered receiver")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error while unregistering receiver: $e")
        }
        super.onStop()
    }
}

@ExperimentalHazeMaterialsApi
@SuppressLint("MissingPermission", "InlinedApi", "UnspecifiedRegisterReceiverFlag")
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Main() {
    val context = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("settings", MODE_PRIVATE)

    val airPodsService = remember { mutableStateOf<AirPodsService?>(null) }

    val airPodsViewModel: AirPodsViewModel = viewModel()

    LaunchedEffect(Unit) {
        if (BuildConfig.PLAY_BUILD) {
            val now = System.currentTimeMillis()
            val firstConn =
                sharedPreferences.getLong("first_connection_successful_time", 0L)

            val alreadyPrompted =
                sharedPreferences.getBoolean("review_prompted", false)

            val oneDay = 24 * 60 * 60 * 1000L

            if (
                firstConn != 0L &&
                !alreadyPrompted &&
                (now - firstConn) > oneDay
            ) {
                triggerReviewFlow(context as? Activity ?: return@LaunchedEffect)

                sharedPreferences.edit {
                    putBoolean("review_prompted", true)
                }
            }
        }
    }

    val onboardingComplete = remember {
        mutableStateOf(sharedPreferences.getBoolean("onboarding_complete", false))
    }
    val boundConnection = remember { mutableStateOf<ServiceConnection?>(null) }

    val releaseNotesShownPrefKey = "release_notes_shown_${BuildConfig.VERSION_NAME.removeSuffix("-debug").removeSuffix("-play")}"
    val releaseNotesShown = sharedPreferences.getBoolean(releaseNotesShownPrefKey, false)

    fun unbindUiService() {
        val previous = boundConnection.value
        boundConnection.value = null
        if (previous != null) {
            try {
                context.unbindService(previous)
            } catch (e: Exception) {
                Log.w("MainActivity", "Unable to unbind AirPods service", e)
            }
        }
        // A bound service otherwise survives the Activity being hidden.
        context.stopService(Intent(context, AirPodsService::class.java))
        airPodsService.value = null
    }

    fun bindService() {
        if (boundConnection.value != null) return
        val serviceIntent = Intent(context, AirPodsService::class.java)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = (binder as? AirPodsService.LocalBinder)?.getService() ?: return
                airPodsService.value = service
                airPodsViewModel.init(
                    service = service,
                    controlRepo = ControlCommandRepository(service.aacpManager),
                    sharedPreferences = context.getSharedPreferences("settings", MODE_PRIVATE),
                    appContext = context.applicationContext
                )
                if (!sharedPreferences.contains("first_connection_successful_time")) {
                    sharedPreferences.edit {
                        putLong("first_connection_successful_time", System.currentTimeMillis())
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                airPodsService.value = null
            }
        }
        try {
            context.startForegroundService(serviceIntent)
            if (context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)) {
                boundConnection.value = connection
            } else {
                Log.w("MainActivity", "Unable to bind AirPods service")
                context.stopService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Unable to start AirPods service", e)
            context.stopService(serviceIntent)
        }
    }

    // The full AirPods service is only kept while the UI is visible.
    // The independently registered BLE PendingIntent scan survives this lifecycle.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, onboardingComplete.value) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (onboardingComplete.value) bindService()
                Lifecycle.Event.ON_STOP -> unbindUiService()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // Compose may install the observer AFTER ON_START has already fired.
        if (onboardingComplete.value &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) bindService()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unbindUiService()
        }
    }

    NavigationRoot(
//        showReleaseNotes = !releaseNotesShown,
//        updatesShown = { sharedPreferences.edit { putBoolean(releaseNotesShownPrefKey, true) } },
        showOnboarding = !onboardingComplete.value,
        onboardingComplete = {
            sharedPreferences.edit { putBoolean("onboarding_complete", true) }
            onboardingComplete.value = true
        },
        airPodsViewModel = airPodsViewModel
    )
}

private fun triggerReviewFlow(activity: Activity) {
    val manager = ReviewManagerFactory.create(activity)
    val request = manager.requestReviewFlow()
    request.addOnCompleteListener { task ->
        if (task.isSuccessful) {
            val reviewInfo = task.result
            manager.launchReviewFlow(activity, reviewInfo)
        }
    }
}
