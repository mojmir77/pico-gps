package org.picogps.tracker.capture

import org.picogps.tracker.model.GapReason
import org.picogps.tracker.model.RecordedSample
import org.picogps.tracker.protocol.GpsFix
import org.picogps.tracker.protocol.ReceiverMetadata

/** Tracks the selected receiver independently of the lifetime of its GATT connection. */
internal class ReceptionTracker {
    private var previousReceiver: ReceiverMetadata? = null
    private var previousSequence = 0
    private var disconnected = false
    var missedSequences: Long = 0
        private set

    fun linkLost() {
        disconnected = true
    }

    fun accept(
        receiver: ReceiverMetadata,
        fix: GpsFix,
        receivedUtcMillis: Long,
        elapsedRealtimeMillis: Long,
    ): RecordedSample? {
        val previous = previousReceiver
        val sameSession = previous != null && previous.receiverId == receiver.receiverId &&
            previous.sessionId == receiver.sessionId
        if (sameSession && fix.sequence <= previousSequence) return null

        val skipped = if (sameSession) fix.sequence - previousSequence - 1 else 0
        val gap = when {
            previous == null -> null
            !sameSession -> GapReason.SESSION_CHANGED
            disconnected -> GapReason.DISCONNECTED
            skipped > 0 -> GapReason.SEQUENCE_GAP
            else -> null
        }
        missedSequences += skipped
        previousReceiver = receiver
        previousSequence = fix.sequence
        disconnected = false
        return RecordedSample(receiver, fix, receivedUtcMillis, elapsedRealtimeMillis, gap)
    }
}
