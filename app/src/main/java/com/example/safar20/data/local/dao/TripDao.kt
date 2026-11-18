package com.example.safar20.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.safar20.data.local.TripMath
import com.example.safar20.data.local.entity.LocationPointEntity
import com.example.safar20.data.local.entity.TripSummaryEntity
import com.example.safar20.data.network.BinaryPacketCodec.ParsedTripPoint
import kotlinx.coroutines.flow.Flow

data class TripPointAggregates(
    val pointCount: Int,
    val avgMovingSpeedKmh: Double?,
    val maxSpeedKmh: Double?,
    val minTimestamp: Long?,
    val maxTimestamp: Long?
)

@Dao
interface TripDao {

    @Query("SELECT * FROM trip_summaries WHERE device_id = :deviceId ORDER BY start_timestamp DESC")
    fun getTripsForDevice(deviceId: String): Flow<List<TripSummaryEntity>>

    @Query("SELECT COALESCE(SUM(distance_km), 0.0) FROM trip_summaries WHERE device_id = :deviceId AND trip_id NOT LIKE 'DEMO_%'")
    fun getSavedOdoDistanceKm(deviceId: String): Flow<Double>

    @Query("SELECT * FROM trip_summaries WHERE trip_id = :tripId")
    suspend fun getTripById(tripId: String): TripSummaryEntity?

    // A3: Ignore DEMO_% rows in trip stitching
    @Query("SELECT * FROM trip_summaries WHERE device_id = :deviceId AND trip_id NOT LIKE 'DEMO_%' ORDER BY start_timestamp DESC LIMIT 1")
    suspend fun getLatestTripSummaryForDevice(deviceId: String): TripSummaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTrip(trip: TripSummaryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationPoint(point: LocationPointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTripPoints(points: List<LocationPointEntity>)

    @Query("SELECT * FROM location_points WHERE trip_id = :tripId ORDER BY timestamp ASC")
    fun getPointsForTrip(tripId: String): Flow<List<LocationPointEntity>>

    @Query("SELECT * FROM location_points WHERE trip_id = :tripId ORDER BY timestamp ASC")
    suspend fun getPointsListForTrip(tripId: String): List<LocationPointEntity>

    // A4: DAO query for the last stored point of a trip (for incremental distance)
    @Query("SELECT * FROM location_points WHERE trip_id = :tripId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastStoredPointForTrip(tripId: String): LocationPointEntity?

    // A4: Single SQL aggregate query per trip for appended trips (no full point list loading)
    @Query(
        """
        SELECT
            COUNT(*) AS pointCount,
            AVG(CASE WHEN speed_kmh > 1.5 THEN speed_kmh END) AS avgMovingSpeedKmh,
            MAX(speed_kmh) AS maxSpeedKmh,
            MIN(timestamp) AS minTimestamp,
            MAX(timestamp) AS maxTimestamp
        FROM location_points
        WHERE trip_id = :tripId
        """
    )
    suspend fun getTripPointAggregates(tripId: String): TripPointAggregates?

    // A3: Ignore DEMO_% rows in trip stitching
    @Query("SELECT * FROM location_points WHERE device_id = :deviceId AND trip_id NOT LIKE 'DEMO_%' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestPointForDevice(deviceId: String): LocationPointEntity?

    @Query("SELECT timestamp FROM location_points WHERE device_id = :deviceId AND trip_id NOT LIKE 'DEMO_%' AND timestamp >= :minTs AND timestamp <= :maxTs")
    suspend fun getExistingTimestampsInRange(deviceId: String, minTs: Long, maxTs: Long): List<Long>

    // A1: Sync cursor ignores DEMO_% and enforces [minValid, maxValid]
    @Query(
        """
        SELECT MAX(timestamp)
        FROM location_points
        WHERE device_id = :deviceId
          AND trip_id NOT LIKE 'DEMO_%'
          AND timestamp BETWEEN :minValid AND :maxValid
        """
    )
    suspend fun getLastSyncedTimestamp(deviceId: String, minValid: Long, maxValid: Long): Long?

    @Query("DELETE FROM trip_summaries WHERE trip_id = :tripId")
    suspend fun deleteTripSummary(tripId: String)

    @Query("DELETE FROM location_points WHERE trip_id = :tripId")
    suspend fun deletePointsForTrip(tripId: String)

    @Transaction
    suspend fun deleteTripAndPoints(tripId: String) {
        deletePointsForTrip(tripId)
        deleteTripSummary(tripId)
    }

    @Query("DELETE FROM trip_summaries WHERE device_id = :deviceId AND trip_id LIKE 'DEMO_%'")
    suspend fun deleteDemoTripSummaries(deviceId: String)

    @Query("DELETE FROM location_points WHERE device_id = :deviceId AND trip_id LIKE 'DEMO_%'")
    suspend fun deleteDemoLocationPoints(deviceId: String)

    @Transaction
    suspend fun deleteDemoTrips(deviceId: String) {
        deleteDemoLocationPoints(deviceId)
        deleteDemoTripSummaries(deviceId)
    }

    @Query("DELETE FROM trip_summaries WHERE device_id = :deviceId")
    suspend fun deleteAllTripSummariesForDevice(deviceId: String)

    @Query("DELETE FROM location_points WHERE device_id = :deviceId")
    suspend fun deleteAllLocationPointsForDevice(deviceId: String)

    @Transaction
    suspend fun deleteAllTripsForDevice(deviceId: String) {
        deleteAllLocationPointsForDevice(deviceId)
        deleteAllTripSummariesForDevice(deviceId)
    }

    /**
     * A4: Incremental transactional history chunk ingestion.
     * - Captures existing trip summary & last stored point for any appended trip
     * - Inserts the new location points first (same @Transaction)
     * - For appended trips: runs ONE SQL aggregate query + incremental distance calculation
     * - For brand-new trips: uses buildNewTripSummary
     * - Updates DeviceEntity.lastSyncedTimestamp only from valid, non-demo points
     */
    @Transaction
    suspend fun saveHistoryChunkIncrementalTransactional(
        deviceId: String,
        tripPointsMap: Map<String, List<ParsedTripPoint>>,
        locationPoints: List<LocationPointEntity>,
        newLastSyncedTimestamp: Long,
        deviceDao: DeviceDao,
        buildNewTripSummary: (String, String, List<ParsedTripPoint>) -> TripSummaryEntity
    ): List<TripSummaryEntity> {
        val preInsertState = HashMap<String, Pair<TripSummaryEntity, ParsedTripPoint?>>(tripPointsMap.size)
        for (tripId in tripPointsMap.keys) {
            val existingSummary = getTripById(tripId)
            if (existingSummary != null) {
                val lastPtEntity = getLastStoredPointForTrip(tripId)
                val lastParsedPt = lastPtEntity?.let {
                    ParsedTripPoint(
                        timestamp = it.timestamp,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        speedKmh = it.speedKmh,
                        heading = it.heading,
                        isIgnitionOn = true,
                        isTripStart = false,
                        isTripEnd = false,
                        satCount = 8
                    )
                }
                preInsertState[tripId] = existingSummary to lastParsedPt
            }
        }

        // Insert new points first (same @Transaction)
        insertTripPoints(locationPoints)

        val updatedSummaries = ArrayList<TripSummaryEntity>(tripPointsMap.size)
        for ((tripId, newPts) in tripPointsMap) {
            val prev = preInsertState[tripId]
            val summary = if (prev != null) {
                val (prevSummary, lastStoredPt) = prev
                val agg = getTripPointAggregates(tripId)
                val startTs = agg?.minTimestamp ?: prevSummary.startTimestamp
                val endTs = agg?.maxTimestamp ?: newPts.last().timestamp
                val durationMins = maxOf(1, ((endTs - startTs) / 60).toInt())
                val updatedDistKm = TripMath.incrementalDistanceKm(
                    previousDistanceKm = prevSummary.distanceKm,
                    lastStoredPoint = lastStoredPt,
                    newPoints = newPts
                )
                TripSummaryEntity(
                    tripId = tripId,
                    deviceId = deviceId,
                    startTimestamp = startTs,
                    endTimestamp = endTs,
                    distanceKm = updatedDistKm,
                    durationMinutes = durationMins,
                    avgSpeedKmh = agg?.avgMovingSpeedKmh ?: 0.0,
                    maxSpeedKmh = agg?.maxSpeedKmh ?: 0.0
                )
            } else {
                buildNewTripSummary(tripId, deviceId, newPts)
            }
            insertOrUpdateTrip(summary)
            updatedSummaries.add(summary)
        }

        if (newLastSyncedTimestamp > 0 && !tripPointsMap.keys.all { it.startsWith("DEMO_") }) {
            deviceDao.updateLastSyncedTimestamp(deviceId, newLastSyncedTimestamp)
        }
        return updatedSummaries
    }
}
