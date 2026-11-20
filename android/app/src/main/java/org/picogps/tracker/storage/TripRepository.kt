package org.picogps.tracker.storage

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.room.withTransaction
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.picogps.tracker.model.GapReason
import org.picogps.tracker.model.RecordedSample
import org.picogps.tracker.model.TrackerStorage

class TripRepository internal constructor(private val database: TripDatabase) : TrackerStorage {
    private val trips = database.trips()
    private val recoveryLock = Mutex()
    private var recovered = false

    private suspend fun recover() = recoveryLock.withLock {
        if (!recovered) {
            trips.recoverInterruptedTrips()
            recovered = true
        }
    }

    internal fun watchTrips(): Flow<List<Trip>> = afterRecovery { trips.watchTrips() }
    internal fun watchRoute(tripId: Long): Flow<List<RoutePoint>> = afterRecovery { trips.watchRoute(tripId) }
    internal fun watchSegments(tripId: Long): Flow<List<TripSegment>> = afterRecovery { trips.watchSegments(tripId) }

    private fun <T> afterRecovery(observe: () -> Flow<T>): Flow<T> = flow {
        recover()
        emitAll(observe())
    }

    private suspend fun <T> write(block: suspend () -> T): T {
        recover()
        return database.withTransaction(block)
    }

    override suspend fun startTrip(): Long = write {
        check(trips.recordingCount() == 0) { "A trip is already recording" }
        trips.insertTrip(Trip(
            uuid = UUID.randomUUID().toString(),
            startedUtcMillis = System.currentTimeMillis(),
            activeStartElapsedMillis = SystemClock.elapsedRealtime(),
        ))
    }

    override suspend fun resumeTrip(tripId: Long) = write {
        val trip = requireTrip(tripId)
        check(trip.state == TripState.INTERRUPTED.name) { "Only interrupted trips can be resumed" }
        check(trips.recordingCount() == 0) { "A trip is already recording" }
        trips.updateTrip(trip.copy(
            state = TripState.RECORDING.name,
            activeStartElapsedMillis = SystemClock.elapsedRealtime(),
            elapsedBeforeResumeMillis = trip.elapsedMillis,
            pendingGap = GapReason.RESUMED.name,
        ))
    }

    override suspend fun finishTrip(tripId: Long) = write {
        val trip = requireTrip(tripId)
        if (trip.state != TripState.SAVED.name) {
            trips.updateTrip(trip.copy(
                state = TripState.SAVED.name,
                finishedUtcMillis = System.currentTimeMillis(),
                elapsedMillis = trip.elapsedAt(SystemClock.elapsedRealtime()),
                activeStartElapsedMillis = null,
            ))
        }
    }

    override suspend fun interruptTrip(tripId: Long) = write {
        val trip = requireTrip(tripId)
        if (trip.state == TripState.RECORDING.name) {
            trips.updateTrip(trip.copy(
                state = TripState.INTERRUPTED.name,
                elapsedMillis = trip.elapsedAt(SystemClock.elapsedRealtime()),
                activeStartElapsedMillis = null,
            ))
        }
    }

    override suspend fun appendSample(tripId: Long, sample: RecordedSample): Boolean = write {
        val trip = requireTrip(tripId)
        check(trip.state == TripState.RECORDING.name) { "Trip is not recording" }
        val source = sample.toSource()
        val insertedId = trips.insertSource(source)
        val sourceId = if (insertedId < 0) {
            trips.findSourceId(source.receiverId, source.sessionId, source.sequence)
        } else insertedId
        if (trips.hasMembership(tripId, sourceId)) return@write false

        val canonical = if (insertedId < 0) trips.getSource(sourceId) else source
        val previousMembership = trips.lastMembership(tripId)
        val previous = previousMembership?.let { trips.getSource(it.sourceId) }
        val gap = trip.pendingGap ?: sample.gapBefore?.name ?: detectGap(previous, canonical)
        if (canonical.latitudeE7 == null || canonical.longitudeE7 == null) {
            trips.insertMembership(sample.membership(tripId, sourceId, null, gap ?: GapReason.NO_FIX.name))
            trips.updateTrip(trip.copy(
                pendingGap = gap ?: GapReason.NO_FIX.name,
                elapsedMillis = trip.elapsedAt(sample.elapsedRealtimeMillis),
            ))
            return@write true
        }

        val stepMillis = previousMembership?.let { sample.elapsedRealtimeMillis - it.elapsedRealtimeMillis } ?: 0
        val distance = if (gap == null && previous != null) distanceMetres(previous, canonical) else 0.0
        val stepGap = when {
            gap != null -> gap
            previousMembership?.segmentId == null -> null
            stepMillis <= 0 || stepMillis > 5_000 -> GapReason.SEQUENCE_GAP.name
            distance > (stepMillis * 0.080 + 15) -> "POSITION_JUMP"
            else -> null
        }
        val segmentId = trip.currentSegmentId?.takeIf { stepGap == null && previousMembership?.segmentId != null }
            ?: trips.insertSegment(TripSegment(tripId = tripId, gapBefore = stepGap.takeIf { trip.pointCount > 0 }))
        trips.insertMembership(sample.membership(tripId, sourceId, segmentId, stepGap))

        val hasMotion = canonical.speedCmPerSecond?.let { it >= 50 && distance >= 0.25 } ?: (distance >= 2.0)
        val moving = stepGap == null && previousMembership?.segmentId != null && hasMotion
        trips.updateTrip(trip.copy(
            elapsedMillis = trip.elapsedAt(sample.elapsedRealtimeMillis),
            pointCount = trip.pointCount + 1,
            currentSegmentId = segmentId,
            distanceMetres = trip.distanceMetres + if (moving) distance else 0.0,
            movingMillis = trip.movingMillis + if (moving) stepMillis else 0,
            pendingGap = null,
        ))
        true
    }

    private suspend fun requireTrip(tripId: Long): Trip = requireNotNull(trips.getTrip(tripId)) { "Trip is unavailable" }

    companion object {
        @Volatile private var instance: TripRepository? = null

        fun get(context: Context): TripRepository = instance ?: synchronized(this) {
            instance ?: TripRepository(Room.databaseBuilder(
                context.applicationContext, TripDatabase::class.java, "pico-gps.db",
            ).build()).also { instance = it }
        }
    }
}

private fun RecordedSample.toSource() = SourceSample(
    protocolVersion = receiver.version,
    receiverId = receiver.receiverId,
    sessionId = receiver.sessionId,
    sequence = fix.sequence,
    gpsUtcSeconds = fix.utcSeconds,
    latitudeE7 = fix.latitudeE7,
    longitudeE7 = fix.longitudeE7,
    speedCmPerSecond = fix.speedCmPerSecond,
    altitudeMetres = fix.altitudeMetres,
    satellites = fix.satellites,
)

private fun RecordedSample.membership(tripId: Long, sourceId: Long, segmentId: Long?, gap: String?) = TripSample(
    tripId = tripId,
    sourceId = sourceId,
    segmentId = segmentId,
    receivedUtcMillis = receivedUtcMillis,
    elapsedRealtimeMillis = elapsedRealtimeMillis,
    gapBefore = gap,
)

private fun detectGap(previous: SourceSample?, current: SourceSample): String? = when {
    previous == null -> null
    previous.receiverId != current.receiverId || previous.sessionId != current.sessionId -> GapReason.SESSION_CHANGED.name
    previous.latitudeE7 == null -> GapReason.NO_FIX.name
    previous.sequence + 1 != current.sequence -> GapReason.SEQUENCE_GAP.name
    else -> null
}

private fun distanceMetres(a: SourceSample, b: SourceSample): Double {
    val fromLatitude = Math.toRadians(requireNotNull(a.latitudeE7) / 1e7)
    val toLatitude = Math.toRadians(requireNotNull(b.latitudeE7) / 1e7)
    val deltaLatitude = toLatitude - fromLatitude
    val deltaLongitude = Math.toRadians((requireNotNull(b.longitudeE7).toLong() - requireNotNull(a.longitudeE7)) / 1e7)
    val halfLat = sin(deltaLatitude / 2)
    val halfLon = sin(deltaLongitude / 2)
    val h = halfLat * halfLat + cos(fromLatitude) * cos(toLatitude) * halfLon * halfLon
    return 12_742_008.8 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
