package org.picogps.tracker.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.picogps.tracker.MainActivity
import org.picogps.tracker.model.RecordedSample
import org.picogps.tracker.model.TrackerStorage
import org.picogps.tracker.protocol.GpsFix
import org.picogps.tracker.protocol.ReceiverMetadata
import org.picogps.tracker.storage.TripRepository

class TrackerService : Service() {
    inner class LocalBinder : Binder() {
        fun scan() {
            if (!TrackerCapture.state.value.recordingTransition && recording == null) link.startScan()
        }

        fun selectReceiver(address: String) {
            if (!TrackerCapture.state.value.recordingTransition && recording == null) link.select(address)
        }

        fun disconnect() {
            if (!TrackerCapture.state.value.recordingTransition && recording == null) link.disconnect()
        }

        fun clearMessage() = TrackerCapture.update { it.copy(message = null) }
    }

    private class Recording {
        var id: Long? = null
        val queue = Channel<RecordedSample>(64)
        lateinit var writer: Job
        var stopping = false
        var error: String? = null
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val storage: TrackerStorage by lazy { TripRepository.get(applicationContext) }
    private var reception = ReceptionTracker()
    private var streamStartedElapsedMs: Long? = null
    private lateinit var link: BleReceiverLink
    private var recording: Recording? = null
    private var starting = false
    private var stopAfterStart = false
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        link = BleReceiverLink(this, Handler(Looper.getMainLooper()), bleListener)
        link.open()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onUnbind(intent: Intent): Boolean {
        link.stopScan()
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START_RECORDING -> startRecording(intent)
            STOP_RECORDING -> {
                if (starting) {
                    stopAfterStart = true
                    recording?.queue?.close()
                }
                else if (recording != null) stopRecording()
                else stopSelfResult(startId)
            }
            else -> if (recording == null && !starting) stopSelfResult(startId)
        }
        // A terminated trip is reopened explicitly from committed local data.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        link.close()
        recording?.queue?.cancel()
        scope.cancel()
        TrackerCapture.update { CaptureState(message = it.message) }
        super.onDestroy()
    }

    private fun startRecording(intent: Intent) {
        if (starting || recording != null) return
        if (TrackerCapture.state.value.selectedReceiver == null || !TrackerCapture.canConnect(this)) {
            endForeground("Connect to a Pico GPS receiver to record a trip.")
            return
        }
        val resumeTripId = intent.getLongExtra(RESUME_TRIP_ID, 0L).takeIf { it > 0L }
        TrackerCapture.update { it.copy(recordingTransition = true, message = null) }
        link.stopScan()
        val session = Recording()
        recording = session
        starting = true
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GPS recording", NotificationManager.IMPORTANCE_LOW),
            )
            val notification = recordingNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foreground = true
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Could not start foreground recording", failure)
            endForeground("Android could not start GPS recording.")
            return
        }
        scope.launch {
            val tripId = try {
                if (resumeTripId == null) storage.startTrip()
                else {
                    storage.resumeTrip(resumeTripId)
                    resumeTripId
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Log.e(TAG, "Could not open the trip database", failure)
                endForeground("Could not open the trip for recording. Check device storage.")
                return@launch
            }

            session.id = tripId
            session.writer = scope.launch(start = CoroutineStart.LAZY) { writePoints(tripId, session) }
            starting = false
            TrackerCapture.update { it.copy(activeTripId = tripId, recordingTransition = false) }
            session.writer.start()
            if (stopAfterStart || session.error != null) stopRecording() else refreshNotification()
        }
    }

    private suspend fun writePoints(tripId: Long, session: Recording) {
        try {
            for (sample in session.queue) {
                if (storage.appendSample(tripId, sample)) publishFix(sample)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Log.e(TAG, "Could not save the GPS sample", failure)
            session.error = "Recording interrupted because a GPS sample could not be saved."
            session.queue.cancel()
            stopRecording()
        }
    }

    private fun stopRecording() {
        val session = recording ?: return
        val tripId = session.id ?: return
        if (session.stopping) return
        session.stopping = true
        TrackerCapture.update { it.copy(recordingTransition = true) }
        refreshNotification()
        session.queue.close()
        scope.launch {
            session.writer.join()
            try {
                if (session.error == null) storage.finishTrip(tripId)
                else storage.interruptTrip(tripId)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Log.e(TAG, "Could not close the recording", failure)
                session.error = "Could not close the trip. Committed samples stay on the device."
                try {
                    storage.interruptTrip(tripId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (interruptFailure: Exception) {
                    Log.e(TAG, "Could not mark the trip as interrupted", interruptFailure)
                }
            }
            endForeground(session.error)
        }
    }

    private fun endForeground(message: String?) {
        recording?.queue?.cancel()
        recording = null
        starting = false
        stopAfterStart = false
        if (foreground) {
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        TrackerCapture.update { it.copy(activeTripId = null, recordingTransition = false, message = message) }
        stopSelf()
    }

    private val bleListener = object : BleReceiverLink.Listener {
        override fun onScan(scanning: Boolean, receivers: List<NearbyReceiver>) {
            TrackerCapture.update { it.copy(scanning = scanning, receivers = receivers) }
        }

        override fun onSelection(receiver: NearbyReceiver?) {
            val changingReceiver = TrackerCapture.state.value.selectedReceiver?.address != receiver?.address
            if (changingReceiver) reception = ReceptionTracker()
            TrackerCapture.update {
                if (!changingReceiver) it.copy(selectedReceiver = receiver)
                else {
                    it.copy(
                        selectedReceiver = receiver,
                        metadata = null,
                        latestFix = null,
                        latestValidFix = null,
                        latestFixElapsedMs = null,
                        latestValidFixElapsedMs = null,
                        missedSequences = 0,
                    )
                }
            }
        }

        override fun onConnection(connection: CaptureConnection, message: String?) {
            streamStartedElapsedMs = if (connection == CaptureConnection.STREAMING) SystemClock.elapsedRealtime() else null
            TrackerCapture.update { it.copy(connection = connection, message = message) }
            refreshNotification()
        }

        override fun onLinkLost() {
            reception.linkLost()
            TrackerCapture.update { it.copy(latestFix = null, latestFixElapsedMs = null) }
        }

        override fun onMetadata(receiver: ReceiverMetadata) {
            TrackerCapture.update { it.copy(metadata = receiver) }
        }

        override fun onFix(receiver: ReceiverMetadata, fix: GpsFix, utcMillis: Long, elapsedMillis: Long) {
            val sample = reception.accept(receiver, fix, utcMillis, elapsedMillis) ?: return
            TrackerCapture.update { it.copy(missedSequences = reception.missedSequences) }
            val session = recording
            if (session == null) {
                if (!TrackerCapture.state.value.recordingTransition) publishFix(sample)
            } else if (!session.stopping && !stopAfterStart && session.error == null && session.queue.trySend(sample).isFailure) {
                session.error = "Recording interrupted because device storage could not keep up."
                stopRecording()
            }
        }

        override fun onMessage(message: String?) {
            TrackerCapture.update { it.copy(message = message) }
        }
    }

    private fun publishFix(sample: RecordedSample) {
        val currentLink = streamStartedElapsedMs?.let { sample.elapsedRealtimeMillis >= it } == true
        val hasPosition = sample.fix.latitudeE7 != null && sample.fix.longitudeE7 != null
        TrackerCapture.update {
            val live = currentLink && it.metadata == sample.receiver
            it.copy(
                latestFix = if (live) sample.fix else it.latestFix,
                latestValidFix = if (hasPosition) sample.fix else it.latestValidFix,
                latestFixElapsedMs = if (live) sample.elapsedRealtimeMillis else it.latestFixElapsedMs,
                latestValidFixElapsedMs = if (hasPosition) sample.elapsedRealtimeMillis else it.latestValidFixElapsedMs,
            )
        }
    }

    private fun refreshNotification() {
        if (!foreground) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, recordingNotification())
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Could not update the recording notification", failure)
        }
    }

    private fun recordingNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags,
        )
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP_RECORDING), flags)
        val text = when {
            recording?.stopping == true -> "Saving trip"
            starting -> "Starting trip"
            TrackerCapture.state.value.connection == CaptureConnection.STREAMING -> "Receiving fixes from the Pico"
            else -> "Waiting for the Pico GPS receiver"
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pico GPS recording")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    companion object {
        internal const val START_RECORDING = "org.picogps.tracker.START_RECORDING"
        internal const val STOP_RECORDING = "org.picogps.tracker.STOP_RECORDING"
        internal const val RESUME_TRIP_ID = "resumeTripId"
        private const val CHANNEL_ID = "pico_gps_recording"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "TrackerService"
    }
}
