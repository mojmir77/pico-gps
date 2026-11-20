package org.picogps.tracker.storage

import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.picogps.tracker.model.GapReason
import org.picogps.tracker.model.RecordedSample
import org.picogps.tracker.protocol.GpsFix
import org.picogps.tracker.protocol.ReceiverMetadata

@RunWith(AndroidJUnit4::class)
class TripRepositoryTest {
    private lateinit var database: TripDatabase
    private lateinit var repository: TripRepository
    private var elapsedStart = 0L

    @Before fun openDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, TripDatabase::class.java).build()
        repository = TripRepository(database)
        elapsedStart = SystemClock.elapsedRealtime()
    }

    @After fun closeDatabase() = database.close()

    @Test fun replayDoesNotAddDistanceAndOutagesBreakTheRoute() = runBlocking {
        val id = repository.startTrip()
        val first = sample(0, 498_201_000)
        val second = sample(1, 498_202_000)
        assertTrue(repository.appendSample(id, first))
        assertTrue(repository.appendSample(id, second))
        assertFalse(repository.appendSample(id, second))
        assertTrue(repository.appendSample(id, sample(2, null)))
        assertTrue(repository.appendSample(id, sample(3, 498_402_000)))
        assertTrue(repository.appendSample(id, sample(4, 498_403_000)))
        assertTrue(repository.appendSample(id, sample(5, 498_403_010, speed = 0)))
        assertTrue(repository.appendSample(id, sample(8, 498_603_000)))
        repository.finishTrip(id)

        val trip = repository.watchTrips().first().single()
        assertEquals(TripState.SAVED.name, trip.state)
        assertEquals(6, trip.pointCount)
        assertEquals(22.24, trip.distanceMetres, 0.02)
        assertEquals(2_000, trip.movingMillis)
        assertEquals(11.12, requireNotNull(trip.movingSpeedMetresPerSecond), 0.01)
        assertEquals(listOf(2, 3, 1), repository.watchRoute(id).first().groupBy { it.segmentId }.values.map { it.size })
        assertEquals(listOf(null, GapReason.NO_FIX.name, GapReason.SEQUENCE_GAP.name), repository.watchSegments(id).first().map { it.gapBefore })

        val other = repository.startTrip()
        assertTrue(repository.appendSample(other, first))
        assertFalse(repository.appendSample(other, first))
        assertEquals(1, repository.watchRoute(other).first().size)
    }

    @Test fun processRecoveryPreservesCommittedPointsAndResumeAddsSegment() = runBlocking {
        val id = repository.startTrip()
        assertTrue(repository.appendSample(id, sample(0, 498_201_000)))

        val restarted = TripRepository(database)
        assertEquals(TripState.INTERRUPTED.name, restarted.watchTrips().first().single().state)
        assertEquals(1, restarted.watchRoute(id).first().size)
        restarted.resumeTrip(id)
        assertTrue(restarted.appendSample(id, sample(1, 498_202_000)))
        restarted.finishTrip(id)

        val saved = restarted.watchTrips().first().single()
        assertEquals(2, saved.pointCount)
        assertEquals(0.0, saved.distanceMetres, 0.0)
        assertEquals(listOf(null, GapReason.RESUMED.name), restarted.watchSegments(id).first().map { it.gapBefore })
    }

    private fun sample(sequence: Int, latitude: Int?, speed: Int = 1_100) = RecordedSample(
        receiver = ReceiverMetadata(1, "0123456789abcdef", "fedcba9876543210"),
        fix = GpsFix(
            sequence, 1_775_001_600L + sequence, latitude, latitude?.let { 182_630_000 },
            latitude?.let { speed }, latitude?.let { 238 }, if (latitude == null) 0 else 9,
        ),
        receivedUtcMillis = 1_775_001_600_000L + sequence * 1_000L,
        elapsedRealtimeMillis = elapsedStart + sequence * 1_000L,
    )
}
