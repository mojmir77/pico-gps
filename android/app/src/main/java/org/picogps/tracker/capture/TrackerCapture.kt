package org.picogps.tracker.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.picogps.tracker.protocol.GpsFix
import org.picogps.tracker.protocol.ReceiverMetadata

data class NearbyReceiver(
    val address: String,
    val name: String,
    val rssi: Int,
    val bonded: Boolean,
)

enum class CaptureConnection {
    IDLE, BLUETOOTH_OFF, PAIRING, CONNECTING, RECONNECTING, STREAMING,
}

data class CaptureState(
    val receivers: List<NearbyReceiver> = emptyList(),
    val scanning: Boolean = false,
    val selectedReceiver: NearbyReceiver? = null,
    val connection: CaptureConnection = CaptureConnection.IDLE,
    val metadata: ReceiverMetadata? = null,
    val latestFix: GpsFix? = null,
    val latestValidFix: GpsFix? = null,
    val latestFixElapsedMs: Long? = null,
    val latestValidFixElapsedMs: Long? = null,
    val missedSequences: Long = 0,
    val activeTripId: Long? = null,
    val recordingTransition: Boolean = false,
    val message: String? = null,
)

object TrackerCapture {
    private val mutableState = MutableStateFlow(CaptureState())
    val state = mutableState.asStateFlow()

    val requiredBluetoothPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    internal fun update(transform: (CaptureState) -> CaptureState) {
        mutableState.update(transform)
    }

    internal fun canConnect(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun startRecording(context: Context, resumeTripId: Long? = null) {
        val snapshot = state.value
        if (snapshot.activeTripId != null || snapshot.recordingTransition) return
        if (snapshot.connection != CaptureConnection.STREAMING) {
            update { it.copy(message = "Connect to a Pico GPS receiver to record a trip.") }
            return
        }
        val intent = Intent(context, TrackerService::class.java).setAction(TrackerService.START_RECORDING)
        resumeTripId?.let { intent.putExtra(TrackerService.RESUME_TRIP_ID, it) }
        update { it.copy(recordingTransition = true, message = null) }
        try {
            context.startForegroundService(intent)
        } catch (failure: RuntimeException) {
            update { it.copy(recordingTransition = false, message = "Could not start GPS recording.") }
        }
    }

    fun stopRecording(context: Context) {
        if (state.value.activeTripId == null) return
        try {
            context.startService(Intent(context, TrackerService::class.java).setAction(TrackerService.STOP_RECORDING))
        } catch (failure: RuntimeException) {
            update { it.copy(message = "Could not stop GPS recording. Try again from the app.") }
        }
    }
}
