package org.picogps.tracker

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.picogps.tracker.capture.TrackerCapture
import org.picogps.tracker.capture.TrackerService
import org.picogps.tracker.maps.OfflineMapStore
import org.picogps.tracker.storage.TripRepository
import org.picogps.tracker.ui.TrackerApp

class MainActivity : ComponentActivity() {
    private var binder by mutableStateOf<TrackerService.LocalBinder?>(null)
    private var bluetoothMessage by mutableStateOf<String?>(null)
    private var bound = false
    private var scanPending = false
    private var pendingRecording = false
    private var pendingTripId: Long? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service as TrackerService.LocalBinder
            if (scanPending) scanWhenReady()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
        }
    }

    private val bluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasBluetoothPermissions()) requestBluetoothScan()
        else bluetoothMessage = if (Build.VERSION.SDK_INT >= 31) {
            "Allow Nearby devices access to find your Pico over Bluetooth."
        } else {
            "Allow location access to scan for Bluetooth receivers on this Android version."
        }
    }

    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) scanWhenReady()
        else bluetoothMessage = "Turn on Bluetooth to find your receiver."
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingRecording) {
            pendingRecording = false
            TrackerCapture.startRecording(this, pendingTripId)
            pendingTripId = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRecording = savedInstanceState?.getBoolean("pendingRecording") ?: false
        pendingTripId = savedInstanceState?.getLong("pendingTripId", -1)?.takeIf { it >= 0 }
        scanPending = savedInstanceState?.getBoolean("scanPending") ?: false
        val background = android.graphics.Color.rgb(246, 244, 235)
        enableEdgeToEdge(SystemBarStyle.light(background, background), SystemBarStyle.light(background, background))
        val repository = TripRepository.get(this)
        val maps = OfflineMapStore.get(this)
        setContent {
            TrackerApp(
                repository = repository,
                maps = maps,
                serviceReady = binder != null,
                bluetoothMessage = bluetoothMessage,
                scan = ::requestBluetoothScan,
                select = { binder?.selectReceiver(it) },
                disconnect = { binder?.disconnect() },
                clearCaptureMessage = { binder?.clearMessage() },
                startRecording = ::startRecording,
                stopRecording = { TrackerCapture.stopRecording(this) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, TrackerService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) unbindService(connection)
        bound = false
        binder = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingRecording", pendingRecording)
        outState.putLong("pendingTripId", pendingTripId ?: -1)
        outState.putBoolean("scanPending", scanPending)
        super.onSaveInstanceState(outState)
    }

    private fun hasBluetoothPermissions() = TrackerCapture.requiredBluetoothPermissions.all { permission ->
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBluetoothScan() {
        bluetoothMessage = null
        if (!hasBluetoothPermissions()) {
            bluetoothPermissions.launch(TrackerCapture.requiredBluetoothPermissions)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        when {
            adapter == null -> bluetoothMessage = "Bluetooth is unavailable on this phone."
            !adapter.isEnabled -> enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> scanWhenReady()
        }
    }

    private fun scanWhenReady() {
        val service = binder
        scanPending = service == null
        service?.scan()
    }

    private fun startRecording(resumeTripId: Long?) {
        val settings = getPreferences(Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !settings.getBoolean("askedNotifications", false)) {
            settings.edit().putBoolean("askedNotifications", true).apply()
            pendingRecording = true
            pendingTripId = resumeTripId
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TrackerCapture.startRecording(this, resumeTripId)
        }
    }
}
