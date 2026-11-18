package com.example.safar20.data.network

import com.example.safar20.data.local.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.NoRouteToHostException
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/**
 * MQTT TelemetrySource implementation for SAFAR 2.0.
 * Connects to EMQX / MQTT broker over TLS, subscribes to pub-key-hash topics only,
 * processes incoming frames sequentially via a single Channel consumer (P1-A3),
 * and guards against duplicate connections (P1-A2).
 */
class MqttTelemetrySource(
    val deviceId: String = "SAFAR_01",
    val nostrPubKey: String = "",
    val sharedSecretHex: String = "",
    val brokerUri: String = "",
    val mqttUsername: String = "",
    val mqttPassword: String = "",
    val lastSyncedProvider: (suspend () -> Long)? = null,
    var onHistoryChunkReceived: (suspend (BinaryPacketCodec.HistoryChunkPacket) -> Boolean)? = null
) : TelemetrySource {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val liveReplayValidator = CryptoEngine.ReplayWindowValidator(maxStaleWindowSeconds = 300L)
    private val statusReplayValidator = CryptoEngine.ReplayWindowValidator(maxStaleWindowSeconds = 300L)

    private val _liveTelemetryStream = MutableSharedFlow<BinaryPacketCodec.ParsedTripPoint>(extraBufferCapacity = 64)
    override val liveTelemetryStream: SharedFlow<BinaryPacketCodec.ParsedTripPoint> = _liveTelemetryStream.asSharedFlow()

    private val _statusStream = MutableSharedFlow<BinaryPacketCodec.ParsedTripPoint>(extraBufferCapacity = 64)
    override val statusStream: SharedFlow<BinaryPacketCodec.ParsedTripPoint> = _statusStream.asSharedFlow()

    private val _sosEventStream = MutableSharedFlow<BinaryPacketCodec.SosEventPacket>(extraBufferCapacity = 16)
    val sosEventStream: SharedFlow<BinaryPacketCodec.SosEventPacket> = _sosEventStream.asSharedFlow()

    private val _linkState = MutableStateFlow<LinkState>(LinkState.Idle)
    override val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val _trackerOnline = MutableStateFlow<Boolean?>(null)
    val trackerOnline: StateFlow<Boolean?> = _trackerOnline.asStateFlow()

    private var mqttClient: MqttClient? = null
    @Volatile
    private var isConnected = false
    private val isConnecting = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(false)
    private var heartbeatJob: Job? = null
    private var sequenceNumber = 1
    private var lastPacketAcceptedTime = 0L

    @Volatile
    var undecryptableFrameCount = 0
        private set

    val topicDevId: String = deriveDeviceTopicId(deviceId, nostrPubKey)
    val statusTopic = "safar/v1/$topicDevId/status"
    val liveTopic = "safar/v1/$topicDevId/live"
    val histTopic = "safar/v1/$topicDevId/hist"
    val cmdTopic = "safar/v1/$topicDevId/cmd"
    val presenceTopic = "safar/v1/$topicDevId/presence"
    val eventTopic = "safar/v1/$topicDevId/event"

    private val subTopics = arrayOf(statusTopic, liveTopic, histTopic, presenceTopic, eventTopic)
    private val subQos = intArrayOf(1, 1, 1, 1, 1)

    // Single sequential consumer channel for incoming MQTT frames (P1-A3)
    private val incomingChannel = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for ((topic, payload) in incomingChannel) {
                processIncomingPayload(topic, payload)
            }
        }
    }

    override fun isRunning(): Boolean = isConnected || isConnecting.get()

    @Synchronized
    private fun closeOldClient() {
        val old = mqttClient
        mqttClient = null
        isConnected = false
        if (old != null) {
            try {
                old.setCallback(null)
                if (old.isConnected) {
                    old.disconnectForcibly(1000, 1000)
                }
            } catch (_: Exception) {
            }
            try {
                old.close(true)
            } catch (_: Exception) {
            }
        }
    }

    override fun start() {
        if (isStopped.get() || isConnected || !isConnecting.compareAndSet(false, true)) return
        if (deviceId.isBlank() || brokerUri.isBlank()) {
            isConnecting.set(false)
            return
        }

        if (!isValidSecretHex(sharedSecretHex)) {
            isConnecting.set(false)
            val err = "Tracker has no valid encryption key. Re-pair using QR"
            _linkState.value = LinkState.Error(err)
            AppLog.e("MQTT", err)
            return
        }

        _linkState.value = LinkState.Connecting
        AppLog.i("MQTT", "Connecting to broker for topicId=$topicDevId")

        scope.launch {
            var attempt = 0
            while (isActive && !isStopped.get() && !isConnected) {
                try {
                    closeOldClient()

                    val clientId = "SafarApp_" + UUID.randomUUID().toString().take(8)
                    val client = MqttClient(brokerUri.trim(), clientId, MemoryPersistence())
                    mqttClient = client

                    client.setCallback(object : MqttCallbackExtended {
                        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                            isConnected = true
                            isConnecting.set(false)
                            _linkState.value = LinkState.Connected
                            liveReplayValidator.reset()
                            statusReplayValidator.reset()
                            lastPacketAcceptedTime = System.currentTimeMillis()
                            AppLog.i("MQTT", "Connected (reconnect=$reconnect) topicId=$topicDevId")

                            scope.launch {
                                try {
                                    client.subscribe(subTopics, subQos)

                                    val freshLastSynced = lastSyncedProvider?.invoke() ?: 0L
                                    val openSessionPacket = BinaryPacketCodec.encodeOpenSession(
                                        lastSyncedTimestamp = freshLastSynced,
                                        sequenceNumber = sequenceNumber++
                                    )
                                    publishCommand(openSessionPacket)
                                    startHeartbeatLoop()
                                } catch (e: Exception) {
                                    AppLog.e("MQTT", "Post-connect subscribe/openSession failed", e)
                                }
                            }
                        }

                        override fun connectionLost(cause: Throwable?) {
                            isConnected = false
                            isConnecting.set(true)
                            stopHeartbeat()
                            _linkState.value = LinkState.Connecting
                            AppLog.e("MQTT", "Connection lost; auto-reconnecting", cause)
                        }

                        override fun messageArrived(topic: String, message: MqttMessage) {
                            val bytes = message.payload ?: return
                            if (bytes.isNotEmpty()) {
                                incomingChannel.trySend(topic to bytes)
                            }
                        }

                        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                    })

                    val options = MqttConnectOptions().apply {
                        isCleanSession = true
                        isAutomaticReconnect = true
                        connectionTimeout = 12
                        keepAliveInterval = 30
                        if (mqttUsername.isNotBlank()) userName = mqttUsername
                        if (mqttPassword.isNotBlank()) password = mqttPassword.toCharArray()
                    }

                    client.connect(options)
                    break
                } catch (e: Exception) {
                    isConnected = false
                    val errorMsg = mapExceptionToHumanMessage(e)
                    _linkState.value = LinkState.Error(errorMsg)
                    AppLog.e("MQTT", "Connect attempt ${attempt + 1} failed: $errorMsg", e)

                    // Do not loop forever on fatal auth / invalid URI errors
                    if (e is IllegalArgumentException || e is URISyntaxException ||
                        (e is MqttException && (e.reasonCode == 4 || e.reasonCode == 5))
                    ) {
                        isConnecting.set(false)
                        break
                    }

                    val backoffMs = computeBackoffDelayMs(attempt)
                    attempt++
                    delay(backoffMs)
                    if (!isStopped.get() && !isConnected) {
                        _linkState.value = LinkState.Connecting
                    }
                }
            }
            if (isStopped.get() || !isConnected) {
                isConnecting.set(false)
            }
        }
    }

    override fun stop() {
        if (!isStopped.compareAndSet(false, true)) return
        stopHeartbeat()
        isConnecting.set(false)
        _linkState.value = LinkState.Idle
        _trackerOnline.value = null
        incomingChannel.close()
        closeOldClient()
        AppLog.i("MQTT", "Disconnected and stopped source for topicId=$topicDevId")
        scope.cancel()
    }

    fun sendClearFlashCommand(mode: Int = 0, upToTimestamp: Long = System.currentTimeMillis() / 1000): Boolean {
        val packet = BinaryPacketCodec.encodeClearFlash(
            upToTimestamp = upToTimestamp,
            mode = mode,
            sequenceNumber = sequenceNumber++
        )
        return publishCommand(packet)
    }

    fun sendSetEmergencyCommand(slot: Int, number: String): Boolean {
        val packet = BinaryPacketCodec.encodeSetEmergency(
            slot = slot,
            number = number,
            sequenceNumber = sequenceNumber++
        )
        return publishCommand(packet)
    }

    fun requestHistorySync(): Boolean {
        if (!isConnected) return false
        scope.launch {
            try {
                val freshLastSynced = lastSyncedProvider?.invoke() ?: 0L
                val openSessionPacket = BinaryPacketCodec.encodeOpenSession(
                    lastSyncedTimestamp = freshLastSynced,
                    sequenceNumber = sequenceNumber++
                )
                publishCommand(openSessionPacket)
            } catch (_: Exception) {
            }
        }
        return true
    }

    fun publishCommand(commandPacket: ByteArray): Boolean {
        val client = mqttClient ?: return false
        if (!client.isConnected) return false
        if (!isValidSecretHex(sharedSecretHex)) return false

        return try {
            val payloadToSend = CryptoEngine.encryptFrame(commandPacket, sharedSecretHex) ?: return false
            val message = MqttMessage(payloadToSend).apply { qos = 1 }
            client.publish(cmdTopic, message)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun startHeartbeatLoop() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive && isConnected) {
                delay(20_000)
                val heartbeatPacket = BinaryPacketCodec.encodeHeartbeat(
                    timestamp = System.currentTimeMillis() / 1000,
                    sequenceNumber = sequenceNumber++
                )
                publishCommand(heartbeatPacket)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun enqueueMessageForTest(topic: String, rawBytes: ByteArray) {
        incomingChannel.trySend(topic to rawBytes)
    }

    suspend fun processIncomingPayload(topic: String, rawBytes: ByteArray) {
        if (rawBytes.isEmpty()) return

        if (topic == presenceTopic) {
            val presenceStr = String(rawBytes, Charsets.UTF_8).trim()
            _trackerOnline.value = (presenceStr == "1")
            return
        }

        if (!isValidSecretHex(sharedSecretHex)) {
            undecryptableFrameCount++
            return
        }

        val plainData = CryptoEngine.decryptFrame(rawBytes, sharedSecretHex)
        if (plainData == null || plainData.isEmpty()) {
            undecryptableFrameCount++
            return
        }

        val now = System.currentTimeMillis()
        if (lastPacketAcceptedTime > 0 && (now - lastPacketAcceptedTime) > 60_000) {
            liveReplayValidator.reset()
            statusReplayValidator.reset()
        }

        when (topic) {
            statusTopic -> {
                val livePacket = BinaryPacketCodec.decodeLiveTelemetry(plainData)
                if (livePacket != null && livePacket.isValidCrc) {
                    if (statusReplayValidator.validateAndRecord(livePacket.tripPoint.timestamp, livePacket.sequenceNumber, checkStale = false)) {
                        lastPacketAcceptedTime = now
                        _statusStream.emit(livePacket.tripPoint)
                    }
                }
            }
            liveTopic -> {
                val livePacket = BinaryPacketCodec.decodeLiveTelemetry(plainData)
                if (livePacket != null && livePacket.isValidCrc) {
                    if (liveReplayValidator.validateAndRecord(livePacket.tripPoint.timestamp, livePacket.sequenceNumber, checkStale = true)) {
                        lastPacketAcceptedTime = now
                        _trackerOnline.value = true
                        _liveTelemetryStream.emit(livePacket.tripPoint)
                    }
                }
            }
            histTopic -> {
                val chunkPacket = BinaryPacketCodec.decodeHistoryChunk(plainData)
                if (chunkPacket != null && chunkPacket.isValidCrc) {
                    lastPacketAcceptedTime = now
                    _trackerOnline.value = true
                    val isIngested = onHistoryChunkReceived?.invoke(chunkPacket) ?: true
                    val ackStatus = if (isIngested) 0 else 1
                    val ackPacket = BinaryPacketCodec.encodeHistoryAck(
                        chunkId = chunkPacket.chunkId,
                        status = ackStatus
                    )
                    publishCommand(ackPacket)
                }
            }
            eventTopic -> {
                val sosPacket = BinaryPacketCodec.decodeSosEvent(plainData)
                if (sosPacket != null && sosPacket.isValidCrc) {
                    lastPacketAcceptedTime = now
                    _trackerOnline.value = true
                    _sosEventStream.emit(sosPacket)
                }
            }
        }
    }

    companion object {
        fun isValidSecretHex(hex: String): Boolean {
            val clean = hex.trim()
            return clean.length == 64 && clean.matches(Regex("^[0-9a-fA-F]{64}$"))
        }

        fun deriveDeviceTopicId(nostrPubKey: String): String = deriveDeviceTopicId("", nostrPubKey)

        fun deriveDeviceTopicId(deviceId: String, nostrPubKey: String): String {
            val cleanPub = nostrPubKey.trim()
            if (cleanPub.length == 64) {
                return try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val hash = digest.digest(cleanPub.toByteArray(Charsets.UTF_8))
                    val hex = hash.joinToString("") { "%02x".format(it) }
                    hex.take(16).lowercase()
                } catch (_: Exception) {
                    "0000000000000000"
                }
            }
            return "0000000000000000"
        }

        fun computeBackoffDelayMs(attempt: Int): Long {
            val shift = attempt.coerceIn(0, 4)
            return minOf(30_000L, 2_000L * (1L shl shift))
        }

        fun mapExceptionToHumanMessage(e: Exception): String {
            return when {
                e is MqttException && (e.reasonCode == 4 || e.reasonCode == 5) -> "Bad credentials or unauthorized"
                e is UnknownHostException || e is NoRouteToHostException -> "Unknown host or no internet connection"
                e is MqttException && e.reasonCode == MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt() -> "Connection timed out"
                e is SSLException || e.cause is SSLException -> "SSL/TLS certificate error"
                e is IllegalArgumentException || e is URISyntaxException -> "Invalid MQTT broker URI"
                else -> e.message ?: "Connection error"
            }
        }
    }
}

