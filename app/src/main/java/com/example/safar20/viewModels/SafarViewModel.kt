package com.example.safar20.viewModels

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.safar20.data.local.AppLog
import com.example.safar20.data.local.EmergencyContact
import com.example.safar20.data.local.PreferencesDataStore
import com.example.safar20.data.local.TripMath
import com.example.safar20.data.local.entity.DeviceEntity
import com.example.safar20.data.local.entity.LocationPointEntity
import com.example.safar20.data.local.entity.TripSummaryEntity
import com.example.safar20.data.network.BinaryPacketCodec
import com.example.safar20.data.network.LinkState
import com.example.safar20.data.network.MockTelemetrySource
import com.example.safar20.data.network.MqttTelemetrySource
import com.example.safar20.data.repository.SafarRepository
import com.example.safar20.ui.components.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

/**
 * Shared ViewModel for SAFAR 2.0.
 * Single source of truth for Cockpit Dashboard, Logbook, and Settings.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SafarViewModel(
    private val repository: SafarRepository
) : ViewModel() {

    // --- Core StateFlows from Repository ---
    val allDevices: StateFlow<List<DeviceEntity>> = repository.allDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedDevice: StateFlow<DeviceEntity?> = repository.selectedDevice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isDarkTheme: StateFlow<Boolean> = repository.isDarkTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val speedUnit: StateFlow<String> = repository.speedUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "km/h")

    val mqttBrokerUri: StateFlow<String> = repository.mqttBrokerUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val mqttUsername: StateFlow<String> = repository.mqttUsername
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val mqttPassword: StateFlow<String> = repository.mqttPassword
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val linkState: StateFlow<LinkState> = repository.linkState
    val trackerOnline: StateFlow<Boolean?> = repository.trackerOnline
    val historySyncProgress: StateFlow<Int?> = repository.historySyncProgress
    val completedRideSummary: StateFlow<TripSummaryEntity?> = repository.completedRideSummary

    val isDevModeEnabled: StateFlow<Boolean> = repository.isDevModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hasCompletedOnboarding: StateFlow<Boolean> = repository.hasCompletedOnboarding
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val emergencyContacts: StateFlow<List<EmergencyContact>> = repository.emergencyContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(EmergencyContact(), EmergencyContact()))

    val customTripNames: StateFlow<Map<String, String>> = repository.customTripNames
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _activeSosEvent = MutableStateFlow<BinaryPacketCodec.SosEventPacket?>(null)
    val activeSosEvent: StateFlow<BinaryPacketCodec.SosEventPacket?> = _activeSosEvent.asStateFlow()

    private val _transientMessage = MutableStateFlow<String?>(null)
    val transientMessage: StateFlow<String?> = _transientMessage.asStateFlow()

    fun showTransientMessage(message: String) {
        _transientMessage.value = message
    }

    fun clearTransientMessage() {
        _transientMessage.value = null
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            repository.setHasCompletedOnboarding(true)
        }
    }

    // --- Foreground and Network State ---
    private val _isForeground = MutableStateFlow(true)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val _isNetworkAvailable = MutableStateFlow(true)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    private data class ConnectConfig(
        val device: DeviceEntity?,
        val brokerUri: String,
        val username: String,
        val password: String,
        val isSim: Boolean,
        val isFg: Boolean,
        val isNet: Boolean
    )

    // --- Live Telemetry State ---
    private val _hasLiveData = MutableStateFlow(false)
    val hasLiveData: StateFlow<Boolean> = _hasLiveData.asStateFlow()

    private val _liveTelemetry = MutableStateFlow<BinaryPacketCodec.ParsedTripPoint?>(null)
    val liveTelemetry: StateFlow<BinaryPacketCodec.ParsedTripPoint?> = _liveTelemetry.asStateFlow()

    private val _liveTrailPoints = MutableStateFlow<List<LocationPointEntity>>(emptyList())
    val liveTrailPoints: StateFlow<List<LocationPointEntity>> = _liveTrailPoints.asStateFlow()

    private val _liveTripDistanceKm = MutableStateFlow(0.0)
    val liveTripDistanceKm: StateFlow<Double> = _liveTripDistanceKm.asStateFlow()

    private val savedOdoDistanceKm = selectedDevice.flatMapLatest { dev ->
        if (dev != null) repository.getSavedOdoDistanceKm(dev.deviceId) else flowOf(0.0)
    }

    val liveOdoDistanceKm: StateFlow<Double> = combine(
        savedOdoDistanceKm,
        _liveTripDistanceKm
    ) { savedKm, liveTripKm ->
        savedKm + liveTripKm
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    private val _liveAvgSpeedKmh = MutableStateFlow(0.0)
    val liveAvgSpeedKmh: StateFlow<Double> = _liveAvgSpeedKmh.asStateFlow()

    private val _liveMaxSpeedKmh = MutableStateFlow(0.0)
    val liveMaxSpeedKmh: StateFlow<Double> = _liveMaxSpeedKmh.asStateFlow()

    private var liveSpeedSum: Double = 0.0
    private var liveSpeedPointCount: Int = 0

    private var previousPoint: BinaryPacketCodec.ParsedTripPoint? = null
    private var lastLocationDbWriteTime: Long = 0L
    private var activeConnectedDeviceId: String? = null

    @Volatile
    private var lastLivePacketElapsedRealtime: Long = 0L

    private val _isSimulatorRunning = MutableStateFlow(false)
    val isSimulatorRunning: StateFlow<Boolean> = _isSimulatorRunning.asStateFlow()

    private fun resetLiveTripStats() {
        _liveTripDistanceKm.value = 0.0
        _liveAvgSpeedKmh.value = 0.0
        _liveMaxSpeedKmh.value = 0.0
        liveSpeedSum = 0.0
        liveSpeedPointCount = 0
        previousPoint = null
        _liveTrailPoints.value = emptyList()
    }

    private fun tickerFlow(periodMs: Long) = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(periodMs)
        }
    }

    val connectionState: StateFlow<ConnectionState> = combine(
        combine(selectedDevice, mqttBrokerUri, _isSimulatorRunning) { dev, uri, isSim ->
            Triple(dev, uri, isSim)
        },
        combine(linkState, trackerOnline, historySyncProgress, tickerFlow(1000L)) { link, online, progress, _ ->
            Triple(link, online, progress)
        }
    ) { (device, brokerUri, isSim), (link, online, progress) ->
        when {
            device == null -> ConnectionState.NO_DEVICE
            brokerUri.isBlank() && !isSim -> ConnectionState.BROKER_NOT_CONFIGURED
            isSim -> {
                if (_liveTelemetry.value?.satCount == 0) ConnectionState.LIVE_NO_FIX else ConnectionState.LIVE
            }
            progress != null -> ConnectionState.SYNCING(progress)
            link is LinkState.Error -> ConnectionState.BROKER_ERROR(link.message)
            link is LinkState.Connecting -> ConnectionState.CONNECTING
            link is LinkState.Connected -> {
                val elapsedSinceLive = SystemClock.elapsedRealtime() - lastLivePacketElapsedRealtime
                val isActivelyStreaming = lastLivePacketElapsedRealtime > 0L &&
                        elapsedSinceLive <= 5000L &&
                        online != false
                val hasKnownPosition = _hasLiveData.value || (device.lastLat != null && device.lastLon != null)
                val lastSeenTs = maxOf(
                    device.lastSyncedTimestamp,
                    _liveTelemetry.value?.timestamp ?: 0L
                ).takeIf { it > 0L }

                when {
                    isActivelyStreaming -> {
                        if (_liveTelemetry.value?.satCount == 0) {
                            ConnectionState.LIVE_NO_FIX
                        } else {
                            ConnectionState.LIVE
                        }
                    }
                    hasKnownPosition -> ConnectionState.PARKED(lastSeenTs)
                    else -> ConnectionState.WAITING_FOR_TRACKER
                }
            }
            else -> {
                val lastSeenTs = maxOf(
                    device.lastSyncedTimestamp,
                    _liveTelemetry.value?.timestamp ?: 0L
                ).takeIf { it > 0L }
                ConnectionState.PARKED(lastSeenTs)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionState.NO_DEVICE)

    // --- Trip History State ---
    val tripHistory: StateFlow<List<TripSummaryEntity>> = selectedDevice
        .flatMapLatest { device ->
            if (device != null) {
                repository.getTripsForDevice(device.deviceId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedTripId = MutableStateFlow<String?>(null)
    val selectedTripId: StateFlow<String?> = _selectedTripId.asStateFlow()

    val selectedTripPoints: StateFlow<List<LocationPointEntity>> = _selectedTripId
        .flatMapLatest { tripId ->
            if (tripId != null) {
                repository.getPointsForTrip(tripId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Collect live telemetry from Repository
        viewModelScope.launch {
            repository.liveTelemetryStream.collect { telemetryPoint ->
                _hasLiveData.value = true
                lastLivePacketElapsedRealtime = SystemClock.elapsedRealtime()

                if (telemetryPoint.isTripStart) {
                    AppLog.i("Ride", "Ride started (ts=${telemetryPoint.timestamp})")
                    resetLiveTripStats()
                }

                if (telemetryPoint.satCount > 0) {
                    val prev = previousPoint
                    if (prev != null && prev.satCount > 0) {
                        val deltaM = TripMath.segmentMeters(prev, telemetryPoint)
                        if (deltaM > 0.0) {
                            _liveTripDistanceKm.value += deltaM / 1000.0
                        }
                    }
                    previousPoint = telemetryPoint

                    val newTrailPt = LocationPointEntity(
                        tripId = "LIVE",
                        deviceId = selectedDevice.value?.deviceId ?: "SAFAR",
                        timestamp = telemetryPoint.timestamp,
                        latitude = telemetryPoint.latitude,
                        longitude = telemetryPoint.longitude,
                        speedKmh = telemetryPoint.speedKmh,
                        heading = telemetryPoint.heading
                    )
                    val updatedTrail = _liveTrailPoints.value
                    if (updatedTrail.isEmpty() ||
                        TripMath.haversineMeters(
                            updatedTrail.last().latitude,
                            updatedTrail.last().longitude,
                            newTrailPt.latitude,
                            newTrailPt.longitude
                        ) >= 3.0
                    ) {
                        _liveTrailPoints.value = (updatedTrail + newTrailPt).takeLast(500)
                    }
                }

                if (telemetryPoint.speedKmh > TripMath.MIN_MOVING_SPEED_KMH) {
                    liveSpeedPointCount++
                    liveSpeedSum += telemetryPoint.speedKmh
                    _liveAvgSpeedKmh.value = liveSpeedSum / liveSpeedPointCount
                }
                if (telemetryPoint.speedKmh > _liveMaxSpeedKmh.value) {
                    _liveMaxSpeedKmh.value = telemetryPoint.speedKmh.toDouble()
                }

                _liveTelemetry.value = telemetryPoint

                // Persist real tracker coordinates only when GPS fix is valid (satCount > 0) and not simulator
                if (!_isSimulatorRunning.value) {
                    val nowSec = System.currentTimeMillis() / 1000
                    val effectiveTs = if (telemetryPoint.timestamp > 0L) telemetryPoint.timestamp else nowSec
                    if (telemetryPoint.satCount > 0 &&
                        (lastLocationDbWriteTime == 0L || (nowSec - lastLocationDbWriteTime) >= 10 || telemetryPoint.isTripEnd)
                    ) {
                        lastLocationDbWriteTime = nowSec
                        selectedDevice.value?.let { device ->
                            repository.updateLastKnownLocation(
                                deviceId = device.deviceId,
                                lat = telemetryPoint.latitude,
                                lon = telemetryPoint.longitude,
                                speed = telemetryPoint.speedKmh,
                                timestamp = effectiveTs
                            )
                        }
                    }
                    if (telemetryPoint.isTripEnd) {
                        AppLog.i("Ride", "Ride ended (ts=${telemetryPoint.timestamp})")
                        viewModelScope.launch {
                            delay(1500)
                            repository.requestHistorySync()
                        }
                    }
                }
            }
        }

        // Collect retained status telemetry from Repository
        viewModelScope.launch {
            repository.statusStream.collect { statusPoint ->
                if (_isSimulatorRunning.value) return@collect

                val currentTs = _liveTelemetry.value?.timestamp ?: 0L
                val deviceLastTs = selectedDevice.value?.lastSyncedTimestamp ?: 0L
                val isNewerOrEqual = statusPoint.timestamp >= maxOf(currentTs, deviceLastTs) ||
                        (_liveTelemetry.value == null && selectedDevice.value?.lastLat == null)

                if (isNewerOrEqual) {
                    _hasLiveData.value = true
                    val elapsedSinceLive = SystemClock.elapsedRealtime() - lastLivePacketElapsedRealtime
                    if (lastLivePacketElapsedRealtime == 0L || elapsedSinceLive > 5000L) {
                        _liveTelemetry.value = statusPoint
                    }
                    if (statusPoint.satCount > 0) {
                        selectedDevice.value?.let { device ->
                            repository.updateLastKnownLocation(
                                deviceId = device.deviceId,
                                lat = statusPoint.latitude,
                                lon = statusPoint.longitude,
                                speed = statusPoint.speedKmh,
                                timestamp = statusPoint.timestamp
                            )
                        }
                    }
                }
            }
        }

        // Collect SOS events from Repository (P3-R6)
        viewModelScope.launch {
            repository.sosEventStream.collect { sosEvent ->
                _activeSosEvent.value = sosEvent
            }
        }

        // Collect skipped invalid history point counts (Phase A2)
        viewModelScope.launch {
            repository.skippedInvalidPointsStream.collect { skippedCount ->
                if (skippedCount > 0) {
                    showTransientMessage("$skippedCount invalid points skipped")
                }
            }
        }

        // Centralized debounced connection evaluator
        viewModelScope.launch {
            combine(
                combine(selectedDevice, mqttBrokerUri, mqttUsername, mqttPassword) { dev, uri, user, pass ->
                    listOf(dev, uri, user, pass)
                },
                combine(_isSimulatorRunning, _isForeground, _isNetworkAvailable) { isSim, isFg, isNet ->
                    Triple(isSim, isFg, isNet)
                }
            ) { devConfig, stateConfig ->
                val dev = devConfig[0] as DeviceEntity?
                val uri = devConfig[1] as String
                val user = devConfig[2] as String
                val pass = devConfig[3] as String
                val (isSim, isFg, isNet) = stateConfig
                ConnectConfig(dev, uri, user, pass, isSim, isFg, isNet)
            }
                .debounce(300L)
                .collect { config ->
                    evaluateAndConnect(config)
                }
        }
    }

    fun setAppForeground(isForeground: Boolean) {
        _isForeground.value = isForeground
    }

    fun setNetworkAvailable(isAvailable: Boolean) {
        _isNetworkAvailable.value = isAvailable
    }

    private fun evaluateAndConnect(config: ConnectConfig) {
        if (!config.isFg || !config.isNet || config.isSim || config.device == null || config.brokerUri.isBlank()) {
            disconnect()
            return
        }
        connectIfReady(config.device, config.brokerUri, config.username, config.password)
    }

    fun connectIfReady(
        device: DeviceEntity? = selectedDevice.value,
        brokerUri: String = mqttBrokerUri.value,
        username: String = mqttUsername.value,
        password: String = mqttPassword.value
    ) {
        if (device == null || brokerUri.isBlank() || _isSimulatorRunning.value || !_isForeground.value || !_isNetworkAvailable.value) {
            disconnect()
            return
        }

        if (activeConnectedDeviceId != device.deviceId) {
            activeConnectedDeviceId = device.deviceId
            resetLiveTripStats()
            lastLocationDbWriteTime = 0L
        }

        val currentSource = repository.getTelemetrySource()
        if (currentSource is MqttTelemetrySource &&
            currentSource.deviceId == device.deviceId &&
            currentSource.brokerUri == brokerUri &&
            currentSource.mqttUsername == username &&
            currentSource.mqttPassword == password &&
            currentSource.sharedSecretHex == device.sharedSecretHex
        ) {
            if (currentSource.isRunning() || currentSource.linkState.value is LinkState.Connecting) {
                return
            }
        }

        resetLiveTripStats()
        val mqttSource = MqttTelemetrySource(
            deviceId = device.deviceId,
            nostrPubKey = device.nostrPubKey,
            sharedSecretHex = device.sharedSecretHex,
            brokerUri = brokerUri,
            mqttUsername = username,
            mqttPassword = password,
            lastSyncedProvider = { repository.getLastSyncedTimestamp(device.deviceId) }
        )
        repository.setTelemetrySource(mqttSource)
        mqttSource.start()
    }

    fun disconnect() {
        val currentSource = repository.getTelemetrySource()
        if (currentSource is MqttTelemetrySource) {
            currentSource.stop()
        }
    }

    suspend fun testBrokerConnection(uri: String, username: String, password: String): String? {
        return withContext(Dispatchers.IO) {
            val cleanUri = uri.trim()
            if (!cleanUri.startsWith("ssl://")) {
                return@withContext "Invalid URI: Broker URI must start with ssl://"
            }

            try {
                val clientId = "SafarTest_" + UUID.randomUUID().toString().take(8)
                val testClient = MqttClient(
                    cleanUri,
                    clientId,
                    MemoryPersistence()
                )
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 5
                    keepAliveInterval = 10
                    if (username.isNotBlank()) userName = username
                    if (password.isNotBlank()) this.password = password.toCharArray()
                }
                testClient.connect(options)
                if (testClient.isConnected) {
                    testClient.disconnect()
                    testClient.close()
                    null
                } else {
                    "Connection failed"
                }
            } catch (e: Exception) {
                MqttTelemetrySource.mapExceptionToHumanMessage(e)
            }
        }
    }

    fun saveMqttBrokerConfig(uri: String, username: String, password: String) {
        viewModelScope.launch {
            repository.setMqttBrokerConfig(uri, username, password)
        }
    }

    fun sendClearTrackerFlash(mode: Int = 0): Boolean {
        return repository.sendClearFlashCommand(mode)
    }

    fun requestHistorySync(): Boolean {
        return repository.requestHistorySync()
    }

    // --- UI Actions ---
    fun selectDevice(deviceId: String) {
        viewModelScope.launch {
            if (selectedDevice.value?.deviceId != deviceId) {
                _liveTelemetry.value = null
                _hasLiveData.value = false
                lastLivePacketElapsedRealtime = 0L
                lastLocationDbWriteTime = 0L
                resetLiveTripStats()
            }
            repository.setSelectedDevice(deviceId)
            _selectedTripId.value = null
        }
    }

    fun toggleDarkTheme(isDark: Boolean) {
        viewModelScope.launch {
            repository.setDarkTheme(isDark)
        }
    }

    fun setSpeedUnit(unit: String) {
        viewModelScope.launch {
            repository.setSpeedUnit(unit)
        }
    }

    fun toggleSpeedUnit() {
        viewModelScope.launch {
            repository.toggleSpeedUnit()
        }
    }

    fun toggleDevMode(enabled: Boolean) {
        viewModelScope.launch {
            repository.setDevModeEnabled(enabled)
            if (!enabled && _isSimulatorRunning.value) {
                toggleSimulator(false)
            }
        }
    }

    fun seedDemoRides() {
        selectedDevice.value?.let { device ->
            viewModelScope.launch {
                repository.seedDemoTrips(device.deviceId)
            }
        }
    }

    fun removeDemoRides() {
        selectedDevice.value?.let { device ->
            viewModelScope.launch {
                repository.deleteDemoTrips(device.deviceId)
            }
        }
    }

    fun toggleSimulator(enable: Boolean) {
        _isSimulatorRunning.value = enable
        if (enable) {
            _hasLiveData.value = true
            resetLiveTripStats()
            repository.setTelemetrySource(MockTelemetrySource())
            repository.startSimulator()
        } else {
            repository.stopSimulator()
            _liveTelemetry.value = null
            lastLivePacketElapsedRealtime = 0L
            resetLiveTripStats()
            _hasLiveData.value = (selectedDevice.value?.lastLat != null && selectedDevice.value?.lastLon != null)
        }
    }

    fun selectTripForMapPreview(tripId: String?) {
        _selectedTripId.value = tripId
    }

    fun pairDeviceFromQr(
        qrUriString: String,
        onSuccess: (DeviceEntity) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val result = repository.parseAndPairQrUri(qrUriString)
            result.onSuccess { device ->
                onSuccess(device)
            }.onFailure { exception ->
                onError(exception.message ?: "Failed to parse QR URI")
            }
        }
    }

    fun addManualDevice(
        id: String,
        name: String,
        simNumber: String,
        pubKey: String,
        secretHex: String,
        onSuccess: (DeviceEntity) -> Unit,
        onError: (String) -> Unit
    ) {
        if (id.isBlank() || name.isBlank()) {
            onError("Device ID and Name are required")
            return
        }
        val cleanPub = pubKey.trim()
        val cleanSec = secretHex.trim()

        if (cleanPub.length != 64 || !cleanPub.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            onError("Public Key must be a valid 64-character hex string")
            return
        }
        if (cleanSec.length != 64 || !cleanSec.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            onError("Shared Secret must be a valid 64-character hex string")
            return
        }

        viewModelScope.launch {
            val device = repository.addManualDevice(id, name, simNumber, cleanPub, cleanSec)
            onSuccess(device)
        }
    }

    fun retryConnection() {
        disconnect()
        connectIfReady()
    }

    fun renameDevice(deviceId: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            repository.renameDevice(deviceId, clean)
            showTransientMessage("Tracker renamed to $clean")
        }
    }

    fun deleteDevice(deviceId: String) {
        viewModelScope.launch {
            repository.deleteDeviceAndHistory(deviceId)
            showTransientMessage("Tracker deleted")
        }
    }

    fun saveEmergencyContacts(contact1: EmergencyContact, contact2: EmergencyContact) {
        if (!PreferencesDataStore.isValidEmergencyNumber(contact1.number) ||
            !PreferencesDataStore.isValidEmergencyNumber(contact2.number)
        ) {
            showTransientMessage("Invalid phone number. Use E.164 format (e.g. +919876543210)")
            return
        }
        viewModelScope.launch {
            val synced = repository.saveAndSyncEmergencyContacts(contact1, contact2)
            showTransientMessage(
                if (synced) "Emergency contacts saved & synced to tracker"
                else "Emergency contacts saved on phone (will sync when tracker connects)"
            )
        }
    }

    fun renameTrip(tripId: String, newName: String) {
        viewModelScope.launch {
            repository.renameTrip(tripId, newName)
            showTransientMessage("Ride renamed")
        }
    }

    fun deleteTrip(tripId: String) {
        viewModelScope.launch {
            repository.deleteTrip(tripId)
            if (_selectedTripId.value == tripId) {
                _selectedTripId.value = null
            }
            showTransientMessage("Ride deleted")
        }
    }

    fun dismissCompletedRideSummary() {
        repository.dismissCompletedRideSummary()
    }

    fun dismissSosAlert() {
        _activeSosEvent.value = null
    }

    fun triggerSimulatedSosAlert(state: Int = BinaryPacketCodec.SOS_STATE_COUNTDOWN) {
        val lat = _liveTelemetry.value?.latitude ?: selectedDevice.value?.lastLat ?: 21.1458
        val lon = _liveTelemetry.value?.longitude ?: selectedDevice.value?.lastLon ?: 79.0882
        repository.triggerSimulatedSosEvent(
            lat = lat,
            lon = lon,
            reason = BinaryPacketCodec.SOS_REASON_CRASH,
            state = state
        )
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repo = SafarRepository.getInstance(context)
            return SafarViewModel(repo) as T
        }
    }
}
