package com.example.safar20.ui.screens.settings

import androidx.camera.core.ExperimentalGetImage
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safar20.data.local.AppLog
import com.example.safar20.data.local.entity.DeviceEntity
import com.example.safar20.data.network.LinkState
import com.example.safar20.ui.components.BrassCard
import com.example.safar20.ui.components.QrPairingDialog
import com.example.safar20.ui.components.SectionLabel
import com.example.safar20.ui.components.SegmentedToggle
import com.example.safar20.ui.theme.BebasNeue
import com.example.safar20.ui.theme.Inter
import com.example.safar20.ui.theme.PhosphorGreen
import com.example.safar20.ui.theme.Rajdhani
import com.example.safar20.ui.theme.SignalRed
import com.example.safar20.viewModels.SafarViewModel
import kotlinx.coroutines.launch

/**
 * Simplified Settings Screen (P2-U1, P2-U6):
 *   1. Appearance
 *   2. Units
 *   3. My Trackers (Select, Rename, Delete, Pair)
 *   4. About (Export Diagnostics + 7-tap Developer Options unlock)
 *   5. Advanced (Collapsible section containing MQTT Relay Broker & TEST CONNECTION)
 *   6. Developer Options (Hidden until unlocked)
 */
@ExperimentalGetImage
@Composable
fun SettingScreen(
    viewModel: SafarViewModel,
    initialExpandAdvanced: Boolean = false,
    onAdvancedExpandedHandled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val allDevices by viewModel.allDevices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val speedUnit by viewModel.speedUnit.collectAsState()
    val isSimulatorRunning by viewModel.isSimulatorRunning.collectAsState()
    val linkState by viewModel.linkState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val trackerOnline by viewModel.trackerOnline.collectAsState()
    val liveTelemetry by viewModel.liveTelemetry.collectAsState()
    val isDevModeEnabled by viewModel.isDevModeEnabled.collectAsState()

    val mqttBrokerUri by viewModel.mqttBrokerUri.collectAsState()
    val mqttUsername by viewModel.mqttUsername.collectAsState()
    val mqttPassword by viewModel.mqttPassword.collectAsState()

    var hostInput by remember(mqttBrokerUri) { mutableStateOf(mqttBrokerUri) }
    var usernameInput by remember(mqttUsername) { mutableStateOf(mqttUsername) }
    var passwordInput by remember(mqttPassword) { mutableStateOf(mqttPassword) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    var isAdvancedExpanded by remember { mutableStateOf(initialExpandAdvanced) }

    LaunchedEffect(initialExpandAdvanced) {
        if (initialExpandAdvanced) {
            isAdvancedExpanded = true
            onAdvancedExpandedHandled()
        }
    }

    var testConnectionState by remember { mutableStateOf<String?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }

    var showQrDialog by remember { mutableStateOf(false) }
    var qrErrorMessage by remember { mutableStateOf<String?>(null) }

    var deviceToDelete by remember { mutableStateOf<DeviceEntity?>(null) }
    var deviceToRename by remember { mutableStateOf<DeviceEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var showClearFlashConfirm by remember { mutableStateOf(false) }

    var versionTapCount by remember { mutableIntStateOf(0) }

    val appVersion = remember(context) {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "2.0.0"
        } catch (_: Exception) {
            "2.0.0"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "COCKPIT SETTINGS",
            fontFamily = Rajdhani,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 1. APPEARANCE
        SectionLabel(text = "APPEARANCE")
        Spacer(modifier = Modifier.height(8.dp))
        BrassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    text = "COCKPIT THEME",
                    fontFamily = Rajdhani,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isDarkTheme) "Midnight Moto Cockpit (Dark)" else "Classic Day Dial (Light)",
                    fontFamily = Inter,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                SegmentedToggle(
                    options = listOf("DARK", "LIGHT"),
                    selectedIndex = if (isDarkTheme) 0 else 1,
                    onSelect = { index ->
                        viewModel.toggleDarkTheme(index == 0)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 2. UNITS
        SectionLabel(text = "UNITS")
        Spacer(modifier = Modifier.height(8.dp))
        BrassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    text = "SPEED & DISTANCE",
                    fontFamily = Rajdhani,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (speedUnit == "mph") "Imperial (Miles, MPH)" else "Metric (Kilometers, KM/H)",
                    fontFamily = Inter,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                SegmentedToggle(
                    options = listOf("KM/H", "MPH"),
                    selectedIndex = if (speedUnit == "mph") 1 else 0,
                    onSelect = { index ->
                        val targetUnit = if (index == 0) "km/h" else "mph"
                        if (speedUnit != targetUnit) {
                            viewModel.toggleSpeedUnit()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 3. MY TRACKERS (Select, Rename, Delete, Pair)
        SectionLabel(text = "MY TRACKERS")
        Spacer(modifier = Modifier.height(8.dp))
        BrassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                if (allDevices.isEmpty()) {
                    Text(
                        text = "NO TRACKERS PAIRED",
                        fontFamily = Rajdhani,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    allDevices.forEachIndexed { index, device ->
                        val isSelected = (device.deviceId == selectedDevice?.deviceId)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectDevice(device.deviceId) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.DirectionsBike,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = device.deviceName,
                                            fontFamily = Rajdhani,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(PhosphorGreen, shape = CircleShape)
                                            )
                                        }
                                    }
                                    val subInfo = if (isDevModeEnabled) {
                                        val topicId = com.example.safar20.data.network.MqttTelemetrySource.deriveDeviceTopicId(device.nostrPubKey)
                                        "ID: ${device.deviceId} · Topic: $topicId · SIM: ${device.simNumber.ifBlank { "N/A" }}"
                                    } else {
                                        "ID: ${device.deviceId}" + (if (device.simNumber.isNotBlank()) " · SIM: ${device.simNumber}" else "")
                                    }
                                    Text(
                                        text = subInfo,
                                        fontFamily = Inter,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        renameInput = device.deviceName
                                        deviceToRename = device
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Rename Tracker",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = { deviceToDelete = device }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Tracker",
                                        tint = SignalRed
                                    )
                                }
                            }
                        }
                        if (index < allDevices.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        qrErrorMessage = null
                        showQrDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "+ PAIR NEW TRACKER",
                        fontFamily = Rajdhani,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 4. ABOUT
        SectionLabel(text = "ABOUT")
        Spacer(modifier = Modifier.height(8.dp))
        BrassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            versionTapCount++
                            if (versionTapCount >= 7) {
                                versionTapCount = 0
                                val next = !isDevModeEnabled
                                viewModel.toggleDevMode(next)
                                viewModel.showTransientMessage(
                                    if (next) "Developer Options Unlocked!" else "Developer Options Hidden"
                                )
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SAFAR 2.0",
                        fontFamily = Rajdhani,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "v$appVersion",
                        fontFamily = BebasNeue,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Edge storage + relay-only MQTT server, end-to-end encrypted",
                    fontFamily = Inter,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val shared = AppLog.shareDiagnostics(context)
                        if (!shared) {
                            viewModel.showTransientMessage("Could not open share sheet for diagnostics")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "EXPORT DIAGNOSTICS",
                        fontFamily = Rajdhani,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 5. ADVANCED (Collapsible MQTT Relay Broker Config — P2-U1, P2-U6)
        SectionLabel(text = "ADVANCED")
        Spacer(modifier = Modifier.height(8.dp))
        BrassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isAdvancedExpanded = !isAdvancedExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Router,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "RELAY SERVER & CONNECTION",
                                fontFamily = Rajdhani,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                letterSpacing = 1.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (mqttBrokerUri.isNotBlank()) "Configured automatically or manually" else "Scan a QR v2 code or configure manually",
                                fontFamily = Inter,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = if (isAdvancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle Advanced",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isAdvancedExpanded) {
                    Spacer(modifier = Modifier.height(14.dp))

                    val isUriInvalid = hostInput.isNotBlank() && !hostInput.trim().startsWith("ssl://")

                    OutlinedTextField(
                        value = hostInput,
                        onValueChange = {
                            hostInput = it
                            testConnectionState = null
                        },
                        label = { Text("Broker URI (ssl://...)") },
                        isError = isUriInvalid,
                        supportingText = {
                            if (isUriInvalid) {
                                Text("URI must start with ssl:// (e.g. ssl://broker.emqx.io:8883)", color = SignalRed)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = {
                            usernameInput = it
                            testConnectionState = null
                        },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            testConnectionState = null
                        },
                        label = { Text("Password") },
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "Toggle Password"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (isUriInvalid || hostInput.isBlank()) {
                                    testConnectionState = "Please enter a valid ssl:// broker URI"
                                    return@OutlinedButton
                                }
                                isTestingConnection = true
                                testConnectionState = null
                                scope.launch {
                                    val err = viewModel.testBrokerConnection(hostInput, usernameInput, passwordInput)
                                    isTestingConnection = false
                                    testConnectionState = err ?: "SUCCESS"
                                }
                            },
                            enabled = !isTestingConnection,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("TEST CONNECTION", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }

                        Button(
                            onClick = {
                                if (isUriInvalid) {
                                    viewModel.showTransientMessage("Broker URI must start with ssl://")
                                    return@Button
                                }
                                viewModel.saveMqttBrokerConfig(hostInput, usernameInput, passwordInput)
                                viewModel.showTransientMessage("Relay config saved & reconnecting…")
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("SAVE & RECONNECT", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }

                    if (testConnectionState != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (testConnectionState == "SUCCESS") "✓ Connected successfully!" else "✗ $testConnectionState",
                            color = if (testConnectionState == "SUCCESS") PhosphorGreen else SignalRed,
                            fontFamily = Inter,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val statusText = when (linkState) {
                        is LinkState.Connected -> "Link Status: Connected to Relay"
                        is LinkState.Connecting -> "Link Status: Connecting…"
                        is LinkState.Error -> "Link Status: ${(linkState as LinkState.Error).message}"
                        is LinkState.Idle -> "Link Status: Idle"
                    }
                    Text(
                        text = statusText,
                        fontFamily = Inter,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 6. DEVELOPER OPTIONS (Only visible when unlocked via 7 taps on version)
        if (isDevModeEnabled) {
            SectionLabel(text = "DEVELOPER OPTIONS")
            Spacer(modifier = Modifier.height(8.dp))
            BrassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "MOCK SIMULATOR",
                                fontFamily = Rajdhani,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                letterSpacing = 1.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Simulate live GPS stream along mock route",
                                fontFamily = Inter,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isSimulatorRunning,
                            onCheckedChange = { viewModel.toggleSimulator(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                                uncheckedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.seedDemoRides()
                                viewModel.showTransientMessage("Demo rides loaded")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("LOAD DEMO RIDES", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                viewModel.removeDemoRides()
                                viewModel.showTransientMessage("Demo rides removed")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("REMOVE DEMO RIDES", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { showClearFlashConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CLEAR TRACKER FLASH MEMORY",
                            fontFamily = Rajdhani,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        Spacer(modifier = Modifier.height(80.dp))
    }

    // Rename Tracker Dialog (P2-U6)
    if (deviceToRename != null) {
        val dev = deviceToRename!!
        AlertDialog(
            onDismissRequest = { deviceToRename = null },
            title = { Text("Rename Tracker", fontFamily = Rajdhani, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Motorcycle / Tracker Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameInput.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.renameDevice(dev.deviceId, trimmed)
                            deviceToRename = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("SAVE", fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRename = null }) {
                    Text("CANCEL", fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Delete Tracker Confirmation Dialog
    if (deviceToDelete != null) {
        val dev = deviceToDelete!!
        AlertDialog(
            onDismissRequest = { deviceToDelete = null },
            title = { Text("Delete Tracker?", fontFamily = Rajdhani, fontWeight = FontWeight.Bold) },
            text = { Text("Delete tracker '${dev.deviceName}' and its local ride history? This will permanently remove device and all stored trip data.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteDevice(dev.deviceId)
                        deviceToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SignalRed)
                ) {
                    Text("DELETE", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToDelete = null }) {
                    Text("CANCEL", fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Clear Flash Confirmation Dialog
    if (showClearFlashConfirm) {
        AlertDialog(
            onDismissRequest = { showClearFlashConfirm = false },
            title = { Text("Clear Tracker Flash?", fontFamily = Rajdhani, fontWeight = FontWeight.Bold) },
            text = { Text("Send CLEAR_FLASH command (0x06) to tracker? This will erase flash memory on the device up to current timestamp.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearFlashConfirm = false
                        val sent = viewModel.sendClearTrackerFlash(mode = 0)
                        viewModel.showTransientMessage(
                            if (sent) "Clear Flash command sent to tracker" else "Tracker not connected"
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("CONFIRM CLEAR", fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearFlashConfirm = false }) {
                    Text("CANCEL", fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showQrDialog) {
        QrPairingDialog(
            existingDevices = allDevices,
            externalError = qrErrorMessage,
            linkState = linkState,
            connectionState = connectionState,
            trackerOnline = trackerOnline,
            liveTelemetry = liveTelemetry,
            onRetryConnect = { viewModel.retryConnection() },
            onOpenAdvancedSettings = { isAdvancedExpanded = true },
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
