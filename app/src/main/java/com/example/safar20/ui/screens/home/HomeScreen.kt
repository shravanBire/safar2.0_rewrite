package com.example.safar20.ui.screens.home

import androidx.camera.core.ExperimentalGetImage
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Expand
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safar20.data.local.UnitConverter
import com.example.safar20.ui.components.BrassCard
import com.example.safar20.ui.components.BrassStatBadge
import com.example.safar20.ui.components.ConnectionState
import com.example.safar20.ui.components.QrPairingDialog
import com.example.safar20.ui.components.SectionLabel
import com.example.safar20.ui.components.StateMessageCatalog
import com.example.safar20.ui.components.StateMessageCatalog.BannerAction
import com.example.safar20.ui.components.TelemetryHeaderPill
import com.example.safar20.ui.components.TripperSpeedometer
import com.example.safar20.ui.theme.Inter
import com.example.safar20.ui.theme.PhosphorGreen
import com.example.safar20.ui.theme.Rajdhani
import com.example.safar20.ui.theme.RoyalBrass
import com.example.safar20.ui.theme.SignalRed
import com.example.safar20.viewModels.SafarViewModel

/**
 * SAFAR 2.0 HomeScreen: Live Retro-Moto Instrument-Cluster Dashboard.
 * Integrates StateMessageCatalog (P2-U4), Offline/Error Banners (P2-U7),
 * and the 4-Step Pairing Wizard (P2-U3).
 */
@ExperimentalGetImage
@Composable
fun HomeScreen(
    viewModel: SafarViewModel,
    launchPairingAutomatically: Boolean = false,
    onPairingLaunched: () -> Unit = {},
    onOpenAdvancedSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val allDevices by viewModel.allDevices.collectAsState()
    val hasLiveData by viewModel.hasLiveData.collectAsState()
    val liveTelemetry by viewModel.liveTelemetry.collectAsState()
    val liveTrailPoints by viewModel.liveTrailPoints.collectAsState()
    val liveOdoKm by viewModel.liveOdoDistanceKm.collectAsState()
    val liveTripDist by viewModel.liveTripDistanceKm.collectAsState()
    val liveAvgSpeed by viewModel.liveAvgSpeedKmh.collectAsState()
    val liveMaxSpeed by viewModel.liveMaxSpeedKmh.collectAsState()
    val isSimulatorRunning by viewModel.isSimulatorRunning.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val linkState by viewModel.linkState.collectAsState()
    val trackerOnline by viewModel.trackerOnline.collectAsState()
    val isNetworkAvailable by viewModel.isNetworkAvailable.collectAsState()
    val speedUnit by viewModel.speedUnit.collectAsState()

    var showDeviceMenu by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var qrErrorMessage by remember { mutableStateOf<String?>(null) }
    var isMapExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(launchPairingAutomatically) {
        if (launchPairingAutomatically) {
            qrErrorMessage = null
            showQrDialog = true
            onPairingLaunched()
        }
    }

    val hasValidLiveFix = liveTelemetry != null && (liveTelemetry?.satCount ?: 0) > 0
    val realOrSavedLat = if (hasValidLiveFix) liveTelemetry?.latitude else selectedDevice?.lastLat
    val realOrSavedLon = if (hasValidLiveFix) liveTelemetry?.longitude else selectedDevice?.lastLon
    val isLiveSession = connectionState is ConnectionState.LIVE || connectionState is ConnectionState.LIVE_NO_FIX
    val currentSpeed = if (hasLiveData && isLiveSession) liveTelemetry?.speedKmh ?: 0.0f else 0.0f
    val currentHeading = liveTelemetry?.heading ?: 210

    var mapCenterLat by remember { mutableDoubleStateOf(realOrSavedLat ?: 21.1458) }
    var mapCenterLon by remember { mutableDoubleStateOf(realOrSavedLon ?: 79.0882) }

    LaunchedEffect(realOrSavedLat, realOrSavedLon) {
        if (realOrSavedLat != null && realOrSavedLon != null) {
            mapCenterLat = realOrSavedLat
            mapCenterLon = realOrSavedLon
        }
    }

    val mapHeight by animateDpAsState(
        targetValue = if (isMapExpanded) 380.dp else 200.dp,
        label = "MapHeightAnimation"
    )

    val catalogEntry = StateMessageCatalog.resolve(
        connectionState = connectionState,
        isNetworkAvailable = isNetworkAvailable
    )

    val handleBannerAction: (BannerAction?) -> Unit = { action ->
        when (action) {
            BannerAction.PAIR_TRACKER -> {
                qrErrorMessage = null
                showQrDialog = true
            }
            BannerAction.OPEN_ADVANCED_SETTINGS -> {
                onOpenAdvancedSettings()
            }
            BannerAction.RETRY_CONNECTION -> {
                viewModel.retryConnection()
            }
            BannerAction.NAVIGATE_TO_BIKE, null -> {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Top Telemetry Header Pill
            Box(modifier = Modifier.fillMaxWidth()) {
                TelemetryHeaderPill(
                    activeDeviceName = selectedDevice?.deviceName ?: "No Tracker",
                    connectionState = connectionState,
                    onDeviceClick = { showDeviceMenu = true }
                )

                DropdownMenu(
                    expanded = showDeviceMenu,
                    onDismissRequest = { showDeviceMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    allDevices.forEach { device ->
                        DropdownMenuItem(
                            text = { Text(device.deviceName, color = MaterialTheme.colorScheme.onSurface) },
                            onClick = {
                                viewModel.selectDevice(device.deviceId)
                                showDeviceMenu = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Pair New Tracker", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        },
                        onClick = {
                            showDeviceMenu = false
                            qrErrorMessage = null
                            showQrDialog = true
                        }
                    )
                }
            }

            // 1b. Persistent State / Offline / Guidance Banner (P2-U4, P2-U5, P2-U7)
            if (catalogEntry.showBannerOnHome) {
                BrassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (!isNetworkAvailable) Icons.Default.WifiOff else Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                tint = if (catalogEntry.isError) SignalRed else RoyalBrass,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = catalogEntry.message,
                                fontFamily = Inter,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = if (catalogEntry.isError) SignalRed else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (catalogEntry.primaryActionLabel != null || catalogEntry.secondaryActionLabel != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (catalogEntry.primaryActionLabel != null) {
                                    Button(
                                        onClick = { handleBannerAction(catalogEntry.primaryAction) },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = catalogEntry.primaryActionLabel.uppercase(),
                                            fontFamily = Rajdhani,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                                if (catalogEntry.secondaryActionLabel != null) {
                                    OutlinedButton(
                                        onClick = { handleBannerAction(catalogEntry.secondaryAction) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = catalogEntry.secondaryActionLabel.uppercase(),
                                            fontFamily = Rajdhani,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Dial zone: TripperSpeedometer with soft radial glow
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(300.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                )

                TripperSpeedometer(
                    speedKmh = currentSpeed,
                    unit = speedUnit,
                    odoDistanceKm = liveOdoKm,
                    gaugeSize = 300.dp,
                    connectionState = connectionState
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 3. Stat row: TRIP, AVG, MAX (Shows "—" until live data exists)
            val tripText = if (hasLiveData) UnitConverter.formatDistance(liveTripDist, speedUnit, 1) else "—"
            val avgText = if (hasLiveData) UnitConverter.formatSpeed(liveAvgSpeed, speedUnit, 0) else "—"
            val maxText = if (hasLiveData) UnitConverter.formatSpeed(liveMaxSpeed, speedUnit, 0) else "—"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BrassStatBadge(
                    value = tripText,
                    label = "TRIP",
                    modifier = Modifier.weight(1f)
                )
                BrassStatBadge(
                    value = avgText,
                    label = "AVG",
                    modifier = Modifier.weight(1f)
                )
                BrassStatBadge(
                    value = maxText,
                    label = "MAX",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section Label (+ Stop Simulator Pill ONLY if Dev Simulator was started from Developer Options)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("NAVIGATION CLUSTER")

                if (isSimulatorRunning) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                            .clickable { viewModel.toggleSimulator(false) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "STOP DEV SIM",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontFamily = Rajdhani,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 4. Map inset with expandable height and LIVE / PARKED indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(mapHeight)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                CockpitMapView(
                    latitude = mapCenterLat,
                    longitude = mapCenterLon,
                    heading = currentHeading,
                    satCount = liveTelemetry?.satCount,
                    trackerName = selectedDevice?.deviceName ?: "Tracker Position",
                    liveTrailPoints = liveTrailPoints,
                    modifier = Modifier.fillMaxSize()
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .clickable { isMapExpanded = !isMapExpanded }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isMapExpanded) Icons.Default.Compress else Icons.Default.Expand,
                            contentDescription = "Toggle Map Size",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isMapExpanded) "COLLAPSE" else "EXPAND",
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = Rajdhani,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }

                if (connectionState is ConnectionState.LIVE ||
                    connectionState is ConnectionState.LIVE_NO_FIX ||
                    connectionState is ConnectionState.PARKED
                ) {
                    val (badgeColor, badgeLabel) = when (connectionState) {
                        is ConnectionState.LIVE -> PhosphorGreen to "LIVE"
                        is ConnectionState.LIVE_NO_FIX -> RoyalBrass to "NO GPS FIX"
                        else -> RoyalBrass to catalogEntry.message.uppercase()
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f), shape = RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(badgeColor, shape = CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = badgeLabel,
                            color = badgeColor,
                            fontFamily = Rajdhani,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }

        // QR Code Pairing Wizard Dialog (P2-U3)
        if (showQrDialog) {
            QrPairingDialog(
                externalError = qrErrorMessage,
                existingDevices = allDevices,
                linkState = linkState,
                connectionState = connectionState,
                trackerOnline = trackerOnline,
                liveTelemetry = liveTelemetry,
                onRetryConnect = { viewModel.retryConnection() },
                onOpenAdvancedSettings = onOpenAdvancedSettings,
                onDismiss = {
                    qrErrorMessage = null
                    showQrDialog = false
                },
                onPairQrUri = { uri ->
                    viewModel.pairDeviceFromQr(
                        qrUriString = uri,
                        onSuccess = {
                            qrErrorMessage = null
                        },
                        onError = { err ->
                            qrErrorMessage = err
                        }
                    )
                },
                onPairManual = { id, name, sim, pub, sec ->
                    viewModel.addManualDevice(
                        id, name, sim, pub, sec,
                        onSuccess = {
                            qrErrorMessage = null
                        },
                        onError = { err ->
                            qrErrorMessage = err
                        }
                    )
                }
            )
        }
    }
}
