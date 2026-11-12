package com.example.safar20.data.repository

import android.content.Context
import com.example.safar20.data.local.AppLog
import com.example.safar20.data.local.PreferencesDataStore
import com.example.safar20.data.local.SafarDatabase
import com.example.safar20.data.local.TripMath
import com.example.safar20.data.local.entity.DeviceEntity
import com.example.safar20.data.local.entity.LocationPointEntity
import com.example.safar20.data.local.entity.TripSummaryEntity
import com.example.safar20.data.network.BinaryPacketCodec
import com.example.safar20.data.network.LinkState
import com.example.safar20.data.network.MockTelemetrySource
import com.example.safar20.data.network.MqttTelemetrySource
import com.example.safar20.data.network.TelemetrySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Unified Repository for SAFAR 2.0.
 * Coordinates RoomDB DAOs, PreferencesDataStore, QR Pairing parser,
 * and dynamic TelemetrySource abstraction (Mock & MQTT over TLS).
 */
class SafarRepository(
    private val database: SafarDatabase,
    private val preferencesDataStore: PreferencesDataStore,
    initialTelemetrySource: TelemetrySource = MockTelemetrySource()
) {

    private val repositoryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val deviceDao = database.deviceDao()
    private val tripDao = database.tripDao()

    private var activeTelemetrySource: TelemetrySource = initialTelemetrySource
    private val _liveTelemetryStream = MutableSharedFlow<BinaryPacketCodec.ParsedTripPoint>(extraBufferCapacity = 64)
    val liveTelemetryStream: SharedFlow<BinaryPacketCodec.ParsedTripPoint> = _liveTelemetryStream.asSharedFlow()

    private val _statusStream = MutableSharedFlow<BinaryPacketCodec.ParsedTripPoint>(extraBufferCapacity = 64)
    val statusStream: SharedFlow<BinaryPacketCodec.ParsedTripPoint> = _statusStream.asSharedFlow()

    private val _linkState = MutableStateFlow<LinkState>(LinkState.Idle)
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val _trackerOnline = MutableStateFlow<Boolean?>(null)
    val trackerOnline: StateFlow<Boolean?> = _trackerOnline.asStateFlow()

    private val _historySyncProgress = MutableStateFlow<Int?>(null)
    val historySyncProgress: StateFlow<Int?> = _historySyncProgress.asStateFlow()
    private var historyProgressJob: Job? = null

    private var sourceCollectorJob: Job? = null
    private var statusCollectorJob: Job? = null
    private var linkStateCollectorJob: Job? = null
    private var presenceCollectorJob: Job? = null

    init {
        attachTelemetrySource(activeTelemetrySource)
    }

    // --- Dynamic Telemetry Source Switcher ---
    fun setTelemetrySource(source: TelemetrySource) {
        activeTelemetrySource.stop()
        if (source is MqttTelemetrySource) {
            source.onHistoryChunkReceived = { chunkPacket ->
                saveHistoryChunkTransactional(source.deviceId, chunkPacket)
            }
        }
        activeTelemetrySource = source
        attachTelemetrySource(source)
    }

    fun getTelemetrySource(): TelemetrySource = activeTelemetrySource

    private fun attachTelemetrySource(source: TelemetrySource) {
        sourceCollectorJob?.cancel()
        statusCollectorJob?.cancel()
        linkStateCollectorJob?.cancel()
        presenceCollectorJob?.cancel()

        sourceCollectorJob = repositoryScope.launch {
            source.liveTelemetryStream.collect { point ->
                _liveTelemetryStream.emit(point)
            }
        }
        statusCollectorJob = repositoryScope.launch {
            source.statusStream.collect { point ->
                _statusStream.emit(point)
            }
        }
        linkStateCollectorJob = repositoryScope.launch {
            source.linkState.collect { state ->
                _linkState.value = state
            }
        }
        if (source is MqttTelemetrySource) {
            presenceCollectorJob = repositoryScope.launch {
                source.trackerOnline.collect { online ->
                    _trackerOnline.value = online
                }
            }
        } else {
            _trackerOnline.value = null
        }
    }

    // --- Device Management Flows ---
    val allDevices: Flow<List<DeviceEntity>> = deviceDao.getAllDevices()
    val selectedDevice: Flow<DeviceEntity?> = deviceDao.getSelectedDevice()

    // --- Preferences Flows ---
    val isDarkTheme: Flow<Boolean> = preferencesDataStore.isDarkTheme
    val speedUnit: Flow<String> = preferencesDataStore.speedUnit
    val mqttBrokerUri: Flow<String> = preferencesDataStore.mqttBrokerUri
    val mqttUsername: Flow<String> = preferencesDataStore.mqttUsername
    val mqttPassword: Flow<String> = preferencesDataStore.mqttPassword
    val isDevModeEnabled: Flow<Boolean> = preferencesDataStore.isDevModeEnabled
    val hasCompletedOnboarding: Flow<Boolean> = preferencesDataStore.hasCompletedOnboarding

    suspend fun setMqttBrokerConfig(uri: String, username: String, password: String) {
        preferencesDataStore.setMqttBrokerUri(uri)
        preferencesDataStore.setMqttUsername(username)
        preferencesDataStore.setMqttPassword(password)
    }

    suspend fun setDevModeEnabled(enabled: Boolean) {
        preferencesDataStore.setDevModeEnabled(enabled)
    }

    suspend fun setHasCompletedOnboarding(completed: Boolean) {
        preferencesDataStore.setHasCompletedOnboarding(completed)
    }

    // --- Trip History Flows ---
    fun getTripsForDevice(deviceId: String): Flow<List<TripSummaryEntity>> {
        return tripDao.getTripsForDevice(deviceId)
    }

    fun getSavedOdoDistanceKm(deviceId: String): Flow<Double> {
        return tripDao.getSavedOdoDistanceKm(deviceId)
    }

    fun getPointsForTrip(tripId: String): Flow<List<LocationPointEntity>> {
        return tripDao.getPointsForTrip(tripId)
    }

    suspend fun getPointsListForTrip(tripId: String): List<LocationPointEntity> {
        return tripDao.getPointsListForTrip(tripId)
    }

    suspend fun getLastSyncedTimestamp(deviceId: String): Long {
        return tripDao.getLastSyncedTimestamp(deviceId) ?: 0L
    }

    // --- Actions ---
    suspend fun setSelectedDevice(deviceId: String) {
        deviceDao.setSelectedDevice(deviceId)
    }

    suspend fun renameDevice(deviceId: String, newName: String) {
        val clean = newName.trim()
        if (clean.isNotEmpty()) {
            deviceDao.renameDevice(deviceId, clean)
        }
    }

    suspend fun setDarkTheme(isDark: Boolean) {
        preferencesDataStore.setDarkTheme(isDark)
    }

    suspend fun setSpeedUnit(unit: String) {
        preferencesDataStore.setSpeedUnit(unit)
    }

    suspend fun toggleSpeedUnit() {
        preferencesDataStore.toggleSpeedUnit()
    }

    // --- Telemetry Source Control ---
    fun startSimulator() {
        activeTelemetrySource.start()
    }

    fun stopSimulator() {
        activeTelemetrySource.stop()
    }

    fun isSimulatorRunning(): Boolean = activeTelemetrySource.isRunning()

    fun sendClearFlashCommand(mode: Int = 0): Boolean {
        val source = activeTelemetrySource
        return if (source is MqttTelemetrySource) {
            source.sendClearFlashCommand(mode)
        } else false
    }

    fun requestHistorySync(): Boolean {
        val source = activeTelemetrySource
        return if (source is MqttTelemetrySource) {
            source.requestHistorySync()
        } else false
    }

    suspend fun seedDemoTrips(deviceId: String) {
        val now = System.currentTimeMillis() / 1000
        val oneDaySec = 86400L

        val demoTrip1 = TripSummaryEntity(
            tripId = "DEMO_${deviceId}_1",
            deviceId = deviceId,
            startTimestamp = now - 7200,
            endTimestamp = now - 3600,
            distanceKm = 42.5,
            durationMinutes = 60,
            avgSpeedKmh = 42.5,
            maxSpeedKmh = 88.0
        )
        val demoTrip2 = TripSummaryEntity(
            tripId = "DEMO_${deviceId}_2",
            deviceId = deviceId,
            startTimestamp = now - oneDaySec - 7200,
            endTimestamp = now - oneDaySec - 3600,
            distanceKm = 18.4,
            durationMinutes = 35,
            avgSpeedKmh = 31.5,
            maxSpeedKmh = 78.0
        )

        tripDao.insertOrUpdateTrip(demoTrip1)
        tripDao.insertOrUpdateTrip(demoTrip2)
    }

    suspend fun deleteDemoTrips(deviceId: String) {
        tripDao.deleteDemoTrips(deviceId)
    }

    suspend fun deleteDeviceAndHistory(deviceId: String) {
        val wasSelected = deviceDao.getDeviceById(deviceId)?.isSelected == true
        tripDao.deleteAllTripsForDevice(deviceId)
        deviceDao.deleteDeviceById(deviceId)

        if (wasSelected) {
            val nextDevice = deviceDao.getFirstDeviceOnce()
            if (nextDevice != null) {
                deviceDao.setSelectedDevice(nextDevice.deviceId)
            }
        }
    }

    // --- RoomDB Transactional Chunk Ingestion ---
    suspend fun saveHistoryChunkTransactional(
        deviceId: String,
        chunkPacket: BinaryPacketCodec.HistoryChunkPacket
    ): Boolean {
        if (chunkPacket.tripPoints.isEmpty()) return true

        if (chunkPacket.totalChunks > 0) {
            val progress = ((chunkPacket.chunkId + 1) * 100) / chunkPacket.totalChunks
            _historySyncProgress.value = progress
            historyProgressJob?.cancel()
            historyProgressJob = repositoryScope.launch {
                delay(10_000)
                _historySyncProgress.value = null
            }
            if (chunkPacket.chunkId + 1 >= chunkPacket.totalChunks) {
                repositoryScope.launch {
                    delay(2000)
                    _historySyncProgress.value = null
                }
            }
        }

        return try {
            val points = chunkPacket.tripPoints.sortedBy { it.timestamp }
            val minTs = points.first().timestamp
            val maxTs = points.last().timestamp

            val existingTsSet = tripDao.getExistingTimestampsInRange(deviceId, minTs, maxTs).toSet()
            val newPoints = points.filter { it.timestamp !in existingTsSet }

            if (newPoints.isEmpty()) {
                return true
            }

            val latestPoint = tripDao.getLatestPointForDevice(deviceId)
            val latestTrip = tripDao.getLatestTripSummaryForDevice(deviceId)

            val firstPt = newPoints.first()
            val canAppendToLatest = !firstPt.isTripStart &&
                    latestPoint != null &&
                    latestTrip != null &&
                    latestTrip.tripId == latestPoint.tripId &&
                    (firstPt.timestamp - latestPoint.timestamp) in 0..300

            var currentTripId = if (canAppendToLatest && latestTrip != null) {
                latestTrip.tripId
            } else {
                "TRIP_${deviceId}_${firstPt.timestamp}"
            }

            val locationEntities = mutableListOf<LocationPointEntity>()
            val tripPointsMap = mutableMapOf<String, MutableList<BinaryPacketCodec.ParsedTripPoint>>()

            for (i in newPoints.indices) {
                val pt = newPoints[i]
                if (i > 0) {
                    val prevPt = newPoints[i - 1]
                    if (pt.isTripStart || (pt.timestamp - prevPt.timestamp) > 300) {
                        currentTripId = "TRIP_${deviceId}_${pt.timestamp}"
                    }
                }
                tripPointsMap.getOrPut(currentTripId) { mutableListOf() }.add(pt)
                locationEntities.add(
                    LocationPointEntity(
                        tripId = currentTripId,
                        deviceId = deviceId,
                        timestamp = pt.timestamp,
                        latitude = pt.latitude,
                        longitude = pt.longitude,
                        speedKmh = pt.speedKmh,
                        heading = pt.heading
                    )
                )
            }

            val tripSummariesToUpdate = mutableListOf<TripSummaryEntity>()
            for ((tId, ptsAdded) in tripPointsMap) {
                val existingPoints = tripDao.getPointsListForTrip(tId)
                val allPointsForTrip = (existingPoints.map {
                    BinaryPacketCodec.ParsedTripPoint(
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
                } + ptsAdded).sortedBy { it.timestamp }

                tripSummariesToUpdate.add(buildTripSummary(tId, deviceId, allPointsForTrip))
            }

            val committedMaxTs = newPoints.maxOf { it.timestamp }

            tripDao.saveHistoryChunkTransactional(
                deviceId = deviceId,
                tripSummaries = tripSummariesToUpdate,
                locationPoints = locationEntities,
                newLastSyncedTimestamp = committedMaxTs,
                deviceDao = deviceDao
            )
            AppLog.i("Repo", "Saved history chunk ${chunkPacket.chunkId + 1}/${chunkPacket.totalChunks} (${newPoints.size} pts)")
            true
        } catch (e: Exception) {
            AppLog.e("Repo", "Failed saving history chunk: ${e.message}")
            false
        }
    }

    private fun buildTripSummary(
        tripId: String,
        deviceId: String,
        pts: List<BinaryPacketCodec.ParsedTripPoint>
    ): TripSummaryEntity {
        val startTs = pts.first().timestamp
        val endTs = pts.last().timestamp
        val durationMins = maxOf(1, ((endTs - startTs) / 60).toInt())

        val distKm = TripMath.distanceKm(pts)
        val avgSpeed = TripMath.movingAvgSpeedKmh(pts)
        val maxSpeed = pts.maxOfOrNull { it.speedKmh.toDouble() } ?: 0.0

        return TripSummaryEntity(
            tripId = tripId,
            deviceId = deviceId,
            startTimestamp = startTs,
            endTimestamp = endTs,
            distanceKm = distKm,
            durationMinutes = durationMins,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = maxSpeed
        )
    }

    // --- QR Pairing & Device Add (Supports QR v1 and QR v2 with embedded broker) ---
    data class ParsedQrData(
        val version: Int,
        val deviceId: String,
        val deviceName: String,
        val pubKey: String,
        val secretHex: String,
        val simNumber: String,
        val brokerUri: String?,
        val brokerUsername: String?,
        val brokerPassword: String?
    )

    suspend fun parseAndPairQrUri(qrUriString: String): Result<DeviceEntity> {
        val parsedResult = parseQrUriData(qrUriString)
        return parsedResult.mapCatching { parsed ->
            // P2-U1: If QR v2 carries a valid ssl:// broker, apply broker config automatically.
            // QR v1 (no broker param) preserves existing Settings broker values.
            if (!parsed.brokerUri.isNullOrBlank()) {
                setMqttBrokerConfig(
                    uri = parsed.brokerUri,
                    username = parsed.brokerUsername ?: "",
                    password = parsed.brokerPassword ?: ""
                )
                AppLog.i("Pairing", "Applied broker config from QR v${parsed.version} for ${parsed.deviceId}")
            }

            val device = DeviceEntity(
                deviceId = parsed.deviceId,
                deviceName = parsed.deviceName,
                nostrPubKey = parsed.pubKey,
                sharedSecretHex = parsed.secretHex,
                simNumber = parsed.simNumber,
                lastSyncedTimestamp = 0L,
                isSelected = true
            )

            deviceDao.insertAndSelectDevice(device)
            preferencesDataStore.setHasCompletedOnboarding(true)
            AppLog.i("Pairing", "Paired tracker ${parsed.deviceId} (${parsed.deviceName})")
            device
        }
    }

    suspend fun addManualDevice(
        deviceId: String,
        deviceName: String,
        simNumber: String,
        pubKey: String = "",
        secretHex: String = ""
    ): DeviceEntity {
        val device = DeviceEntity(
            deviceId = deviceId,
            deviceName = deviceName,
            nostrPubKey = pubKey,
            sharedSecretHex = secretHex,
            simNumber = simNumber,
            lastSyncedTimestamp = 0L,
            isSelected = true
        )
        deviceDao.insertAndSelectDevice(device)
        preferencesDataStore.setHasCompletedOnboarding(true)
        AppLog.i("Pairing", "Manually paired tracker $deviceId ($deviceName)")
        return device
    }

    suspend fun updateLastKnownLocation(
        deviceId: String,
        lat: Double,
        lon: Double,
        speed: Float,
        timestamp: Long
    ) {
        deviceDao.updateLastKnownLocation(deviceId, lat, lon, speed, timestamp)
    }

    companion object {
        @Volatile
        private var INSTANCE: SafarRepository? = null

        fun parseQrUriData(qrUriString: String): Result<ParsedQrData> {
            return try {
                val trimmed = qrUriString.trim()
                val uri = java.net.URI(trimmed)
                if (uri.scheme != "safar" || uri.host != "pair") {
                    return Result.failure(IllegalArgumentException("Invalid QR Code Scheme. Expected safar://pair"))
                }

                val params = mutableMapOf<String, String>()
                val rawQuery = uri.rawQuery ?: ""
                for (pair in rawQuery.split("&")) {
                    if (pair.isBlank()) continue
                    val idx = pair.indexOf('=')
                    if (idx > 0) {
                        val k = java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                        val rawVal = pair.substring(idx + 1)
                        val v = java.net.URLDecoder.decode(rawVal.replace("+", "%2B"), "UTF-8")
                        params[k] = v
                    }
                }

                val version = params["v"]?.toIntOrNull() ?: 1
                val deviceId = (params["id"] ?: params["device_id"])?.trim()
                if (deviceId.isNullOrEmpty()) {
                    return Result.failure(IllegalArgumentException("Device ID missing from QR Code"))
                }

                val deviceName = (params["name"] ?: params["device_name"])?.trim()?.ifEmpty { "Safar Tracker" }
                    ?: "Safar Tracker"
                val pubKey = (params["pub"] ?: params["nostr_pub_key"] ?: "").trim()
                val secretHex = (params["sec"] ?: params["shared_secret"] ?: "").trim()

                val hex64Regex = Regex("^[0-9a-fA-F]{64}$")
                if (!pubKey.matches(hex64Regex)) {
                    return Result.failure(IllegalArgumentException("Invalid QR: Public Key must be a 64-character hex string"))
                }
                if (!secretHex.matches(hex64Regex)) {
                    return Result.failure(IllegalArgumentException("Invalid QR: Shared Secret must be a 64-character hex string"))
                }

                val rawSim = (params["sim"] ?: params["sim_number"] ?: "").trim()
                val simNumber = if (!rawSim.startsWith("+") && rawSim.startsWith("91") && rawSim.length == 12) {
                    "+$rawSim"
                } else {
                    rawSim
                }

                val brokerUri = params["broker"]?.trim()?.takeIf { it.isNotEmpty() }
                if (brokerUri != null && !brokerUri.startsWith("ssl://")) {
                    return Result.failure(IllegalArgumentException("Invalid QR: Broker URI must start with ssl://"))
                }
                val brokerUser = params["bu"]?.trim()
                val brokerPass = params["bp"]

                Result.success(
                    ParsedQrData(
                        version = version,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        pubKey = pubKey,
                        secretHex = secretHex,
                        simNumber = simNumber,
                        brokerUri = brokerUri,
                        brokerUsername = brokerUser,
                        brokerPassword = brokerPass
                    )
                )
            } catch (e: Exception) {
                Result.failure(IllegalArgumentException(e.message ?: "Invalid QR URI"))
            }
        }

        fun getInstance(
            context: Context,
            telemetrySource: TelemetrySource = MockTelemetrySource()
        ): SafarRepository {
            return INSTANCE ?: synchronized(this) {
                val db = SafarDatabase.getDatabase(context)
                val prefs = PreferencesDataStore(context)
                val instance = SafarRepository(db, prefs, telemetrySource)
                INSTANCE = instance
                instance
            }
        }
    }
}
