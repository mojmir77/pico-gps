package org.picogps.tracker.storage

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

internal enum class TripState { RECORDING, INTERRUPTED, SAVED }

@Entity(tableName = "trip", indices = [Index(value = ["uuid"], unique = true)])
internal data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val startedUtcMillis: Long,
    val finishedUtcMillis: Long? = null,
    val state: String = TripState.RECORDING.name,
    val activeStartElapsedMillis: Long?,
    val elapsedBeforeResumeMillis: Long = 0,
    val elapsedMillis: Long = 0,
    val pointCount: Int = 0,
    val distanceMetres: Double = 0.0,
    val movingMillis: Long = 0,
    val pendingGap: String? = null,
    val currentSegmentId: Long? = null,
) {
    fun elapsedAt(monotonicMillis: Long): Long = activeStartElapsedMillis?.let {
        maxOf(elapsedMillis, elapsedBeforeResumeMillis + (monotonicMillis - it).coerceAtLeast(0))
    } ?: elapsedMillis

    val movingSpeedMetresPerSecond: Double?
        get() = if (movingMillis > 0) distanceMetres * 1000.0 / movingMillis else null
}

@Entity(
    tableName = "source_sample",
    indices = [Index(value = ["receiverId", "sessionId", "sequence"], unique = true)],
)
internal data class SourceSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val protocolVersion: Int,
    val receiverId: String,
    val sessionId: String,
    val sequence: Int,
    val gpsUtcSeconds: Long?,
    val latitudeE7: Int?,
    val longitudeE7: Int?,
    val speedCmPerSecond: Int?,
    val altitudeMetres: Int?,
    val satellites: Int?,
)

@Entity(
    tableName = "trip_segment",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
internal data class TripSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val gapBefore: String?,
)

@Entity(
    tableName = "trip_sample",
    foreignKeys = [
        ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SourceSample::class, parentColumns = ["id"], childColumns = ["sourceId"]),
        ForeignKey(entity = TripSegment::class, parentColumns = ["id"], childColumns = ["segmentId"]),
    ],
    indices = [Index(value = ["tripId", "sourceId"], unique = true), Index("sourceId"), Index("segmentId")],
)
internal data class TripSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val sourceId: Long,
    val segmentId: Long?,
    val receivedUtcMillis: Long,
    val elapsedRealtimeMillis: Long,
    val gapBefore: String?,
)

internal data class RoutePoint(
    val segmentId: Long,
    val latitudeE7: Int,
    val longitudeE7: Int,
)

@Dao
internal interface TripDao {
    @Insert suspend fun insertTrip(trip: Trip): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSource(sample: SourceSample): Long
    @Insert suspend fun insertSegment(segment: TripSegment): Long
    @Insert suspend fun insertMembership(sample: TripSample): Long
    @Update suspend fun updateTrip(trip: Trip)

    @Query("SELECT * FROM trip WHERE id = :tripId")
    suspend fun getTrip(tripId: Long): Trip?

    @Query("SELECT COUNT(*) FROM trip WHERE state = 'RECORDING'")
    suspend fun recordingCount(): Int

    @Query("UPDATE trip SET state = 'INTERRUPTED', activeStartElapsedMillis = NULL WHERE state = 'RECORDING'")
    suspend fun recoverInterruptedTrips()

    @Query("SELECT * FROM trip ORDER BY startedUtcMillis DESC, id DESC")
    fun watchTrips(): Flow<List<Trip>>

    @Query("SELECT id FROM source_sample WHERE receiverId = :receiverId AND sessionId = :sessionId AND sequence = :sequence")
    suspend fun findSourceId(receiverId: String, sessionId: String, sequence: Int): Long

    @Query("SELECT EXISTS(SELECT 1 FROM trip_sample WHERE tripId = :tripId AND sourceId = :sourceId)")
    suspend fun hasMembership(tripId: Long, sourceId: Long): Boolean

    @Query("SELECT * FROM trip_sample WHERE tripId = :tripId ORDER BY id DESC LIMIT 1")
    suspend fun lastMembership(tripId: Long): TripSample?

    @Query("SELECT * FROM source_sample WHERE id = :id")
    suspend fun getSource(id: Long): SourceSample

    @Query("""
        SELECT trip_sample.segmentId, source_sample.latitudeE7, source_sample.longitudeE7
        FROM trip_sample JOIN source_sample ON source_sample.id = trip_sample.sourceId
        WHERE trip_sample.tripId = :tripId AND trip_sample.segmentId IS NOT NULL
        ORDER BY trip_sample.id
    """)
    fun watchRoute(tripId: Long): Flow<List<RoutePoint>>

    @Query("SELECT * FROM trip_segment WHERE tripId = :tripId ORDER BY id")
    fun watchSegments(tripId: Long): Flow<List<TripSegment>>
}

@Database(entities = [Trip::class, SourceSample::class, TripSegment::class, TripSample::class], version = 1)
internal abstract class TripDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
}
