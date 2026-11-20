package org.picogps.tracker.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.picogps.tracker.model.GapReason
import org.picogps.tracker.protocol.GpsFix
import org.picogps.tracker.protocol.ReceiverMetadata

class ReceptionTrackerTest {
    private val receiver = ReceiverMetadata(1, "0000000000000001", "0000000000001000")

    @Test
    fun firstPacketAndDelayedDuplicatesDoNotIncreaseLossCount() {
        val tracker = ReceptionTracker()
        assertNull(tracker.accept(receiver, fix(20), 10_000, 1_000)?.gapBefore)
        assertNull(tracker.accept(receiver, fix(20), 10_001, 1_001))
        assertNull(tracker.accept(receiver, fix(19), 10_002, 1_002))
        val sample = tracker.accept(receiver, fix(23), 13_000, 4_000)
        assertEquals(GapReason.SEQUENCE_GAP, sample?.gapBefore)
        assertEquals(2L, tracker.missedSequences)
    }

    @Test
    fun reconnectMarksGapEvenWithConsecutiveSequenceAndIgnoresRetriedPackets() {
        val tracker = ReceptionTracker()
        tracker.accept(receiver, fix(29), 10_000, 1_000)
        tracker.linkLost()
        assertNull(tracker.accept(receiver, fix(29), 11_000, 2_000))
        assertEquals(GapReason.DISCONNECTED, tracker.accept(receiver, fix(30), 12_000, 3_000)?.gapBefore)
        assertEquals(0L, tracker.missedSequences)
    }

    @Test
    fun rebootAcceptsResetSequenceAndNoFixPacketsAdvanceSequence() {
        val tracker = ReceptionTracker()
        tracker.accept(receiver, fix(65_535), 10_000, 1_000)
        tracker.linkLost()
        val reboot = receiver.copy(sessionId = "0000000000001001")
        val noFix = fix(0).copy(latitudeE7 = null, longitudeE7 = null)
        val first = tracker.accept(reboot, noFix, 14_000, 5_000)
        assertEquals(GapReason.SESSION_CHANGED, first?.gapBefore)
        assertEquals(noFix, first?.fix)
        val next = tracker.accept(reboot, fix(1), 15_000, 6_000)
        assertNotNull(next)
        assertNull(next?.gapBefore)
        assertEquals(0L, tracker.missedSequences)
    }

    private fun fix(sequence: Int) = GpsFix(sequence, null, 498_000_000, 182_000_000, null, null, 6)
}
