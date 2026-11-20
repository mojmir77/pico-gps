package org.picogps.tracker.model

import org.picogps.tracker.protocol.GpsFix
import org.picogps.tracker.protocol.ReceiverMetadata

enum class GapReason { DISCONNECTED, NO_FIX, SEQUENCE_GAP, SESSION_CHANGED, RESUMED }

data class RecordedSample(
    val receiver: ReceiverMetadata,
    val fix: GpsFix,
    val receivedUtcMillis: Long,
    val elapsedRealtimeMillis: Long,
    val gapBefore: GapReason? = null,
)

interface TrackerStorage {
    suspend fun startTrip(): Long
    suspend fun resumeTrip(tripId: Long)
    suspend fun finishTrip(tripId: Long)
    suspend fun interruptTrip(tripId: Long)

    /** Returns false if this source sample is already attached to this trip. */
    suspend fun appendSample(tripId: Long, sample: RecordedSample): Boolean
}
