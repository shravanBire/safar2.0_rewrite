package com.example.safar20.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.safar20.data.local.entity.DeviceEntity
import com.example.safar20.data.network.BinaryPacketCodec
import com.example.safar20.data.network.LinkState
import com.example.safar20.ui.theme.Inter
import com.example.safar20.ui.theme.PhosphorGreen
import com.example.safar20.ui.theme.Rajdhani
import com.example.safar20.ui.theme.RoyalBrass
import com.example.safar20.ui.theme.SignalRed
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ChecklistStepStatus {
    PENDING,
    IN_PROGRESS,
    SUCCESS,
    WARNING,
    FAILED
}

/**
 * Pairing Wizard & QR Scanner Dialog (P2-U1, P2-U3).
 * Step 1: Scan QR v2/v1 or enter keys manually.
 * Step 2: Live 4-step verification checklist:
 *   ① QR scanned
 *   ② Broker reachable
 *   ③ Tracker online (60s timeout with plain-language hints: power, Wi-Fi/SIM, wrong key)
 *   ④ GPS fix
 */
@ExperimentalGetImage
@Composable
fun QrPairingDialog(
    existingDevices: List<DeviceEntity> = emptyList(),
    onDismiss: () -> Unit,
    onPairQrUri: (String) -> Unit,
    onPairManual: (id: String, name: String, sim: String, pubKey: String, secretHex: String) -> Unit,
    externalError: String? = null,
    linkState: LinkState = LinkState.Idle,
    connectionState: ConnectionState = ConnectionState.NO_DEVICE,
    trackerOnline: Boolean? = null,
    liveTelemetry: BinaryPacketCodec.ParsedTripPoint? = null,
    onRetryConnect: () -> Unit = {},
    onOpenAdvancedSettings: (() -> Unit)? = null
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Camera Scanner, 1 = QR Text, 2 = Manual
    var isVerifyingChecklist by remember { mutableStateOf(false) }
    var trackerWaitSeconds by remember { mutableIntStateOf(0) }

    var qrPayloadText by remember { mutableStateOf("") }
    var manualId by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }
    var manualSim by remember { mutableStateOf("") }
    var manualPubKey by remember { mutableStateOf("") }
    var manualSecretHex by remember { mutableStateOf("") }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingReplaceAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val displayedError = externalError ?: errorMessage

    // If externalError arrives while we entered checklist mode, return to input mode so user sees the error
    LaunchedEffect(externalError) {
        if (externalError != null) {
            isVerifyingChecklist = false
        }
    }

    // 60-second timer for Step 3 (Tracker Online)
    val isBrokerConnected = linkState is LinkState.Connected
    val isTrackerResponding = trackerOnline == true ||
            connectionState is ConnectionState.LIVE ||
            connectionState is ConnectionState.LIVE_NO_FIX ||
            connectionState is ConnectionState.SYNCING ||
            liveTelemetry != null

    LaunchedEffect(isVerifyingChecklist, isBrokerConnected, isTrackerResponding) {
        if (isVerifyingChecklist && isBrokerConnected && !isTrackerResponding) {
            while (trackerWaitSeconds < 60) {
                delay(1000L)
                trackerWaitSeconds++
            }
        } else if (isTrackerResponding) {
            trackerWaitSeconds = 0
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(16.dp))
        ) {
            if (isVerifyingChecklist && displayedError == null) {
                PairingChecklistView(
                    linkState = linkState,
                    connectionState = connectionState,
                    isTrackerResponding = isTrackerResponding,
                    liveTelemetry = liveTelemetry,
                    trackerWaitSeconds = trackerWaitSeconds,
                    onRetry = {
                        trackerWaitSeconds = 0
                        onRetryConnect()
                    },
                    onOpenAdvancedSettings = onOpenAdvancedSettings,
                    onDone = onDismiss
                )
            } else {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Pair Tracker",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PAIR SAFAR TRACKER",
                            fontFamily = Rajdhani,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SegmentedToggle(
                        options = listOf("CAMERA", "QR TEXT", "MANUAL"),
                        selectedIndex = selectedTab,
                        onSelect = {
                            selectedTab = it
                            errorMessage = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    when (selectedTab) {
                        0 -> {
                            CameraXScannerTab(
                                onQrScanned = { scannedUri ->
                                    errorMessage = null
                                    val extractedId = extractIdFromUri(scannedUri)
                                    val action = {
                                        isVerifyingChecklist = true
                                        trackerWaitSeconds = 0
                                        onPairQrUri(scannedUri)
                                    }
                                    if (extractedId != null && existingDevices.any { it.deviceId == extractedId }) {
                                        pendingReplaceAction = action
                                    } else {
                                        action()
                                    }
                                },
                                onError = { err -> errorMessage = err }
                            )
                        }
                        1 -> {
                            OutlinedTextField(
                                value = qrPayloadText,
                                onValueChange = {
                                    qrPayloadText = it
                                    errorMessage = null
                                },
                                label = { Text("Paste QR URI (safar://pair?...)") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                )
                            )
                        }
                        2 -> {
                            OutlinedTextField(
                                value = manualId,
                                onValueChange = {
                                    manualId = it
                                    errorMessage = null
                                },
                                label = { Text("Tracker ID (e.g. SAFAR_01)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = manualName,
                                onValueChange = {
                                    manualName = it
                                    errorMessage = null
                                },
                                label = { Text("Tracker Name (e.g. RE Classic 350)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = manualPubKey,
                                onValueChange = {
                                    manualPubKey = it
                                    errorMessage = null
                                },
                                label = { Text("Public Key (64 Hex Characters)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = manualSecretHex,
                                onValueChange = {
                                    manualSecretHex = it
                                    errorMessage = null
                                },
                                label = { Text("Shared Secret (64 Hex Characters)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = manualSim,
                                onValueChange = {
                                    manualSim = it
                                    errorMessage = null
                                },
                                label = { Text("GSM SIM Number (+919876543210, Optional)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (displayedError != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SignalRed.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp))
                                .border(1.dp, SignalRed, shape = RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = displayedError,
                                color = SignalRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("CANCEL", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        if (selectedTab != 0) {
                            Button(
                                onClick = {
                                    if (selectedTab == 1) {
                                        if (qrPayloadText.isBlank()) {
                                            errorMessage = "Please enter a valid QR payload URI"
                                        } else {
                                            val extractedId = extractIdFromUri(qrPayloadText)
                                            val action = {
                                                isVerifyingChecklist = true
                                                trackerWaitSeconds = 0
                                                onPairQrUri(qrPayloadText)
                                            }
                                            if (extractedId != null && existingDevices.any { it.deviceId == extractedId }) {
                                                pendingReplaceAction = action
                                            } else {
                                                action()
                                            }
                                        }
                                    } else if (selectedTab == 2) {
                                        if (manualId.isBlank() || manualName.isBlank()) {
                                            errorMessage = "Tracker ID and Name are required"
                                            return@Button
                                        }
                                        val cleanPub = manualPubKey.trim()
                                        val cleanSec = manualSecretHex.trim()
                                        if (cleanPub.length != 64 || !cleanPub.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                                            errorMessage = "Public Key must be a valid 64-character hex string"
                                            return@Button
                                        }
                                        if (cleanSec.length != 64 || !cleanSec.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                                            errorMessage = "Shared Secret must be a valid 64-character hex string"
                                            return@Button
                                        }

                                        val targetId = manualId.trim()
                                        val action = {
                                            isVerifyingChecklist = true
                                            trackerWaitSeconds = 0
                                            onPairManual(targetId, manualName.trim(), manualSim.trim(), cleanPub, cleanSec)
                                        }
                                        if (existingDevices.any { it.deviceId == targetId }) {
                                            pendingReplaceAction = action
                                        } else {
                                            action()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text("PAIR NOW", color = MaterialTheme.colorScheme.onPrimary, fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (pendingReplaceAction != null) {
        AlertDialog(
            onDismissRequest = { pendingReplaceAction = null },
            title = { Text("Replace Existing Tracker?", fontFamily = Rajdhani, fontWeight = FontWeight.Bold) },
            text = { Text("A tracker with this ID already exists. Pairing will overwrite its configuration and keys.") },
            confirmButton = {
                Button(
                    onClick = {
                        val action = pendingReplaceAction
                        pendingReplaceAction = null
                        action?.invoke()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("REPLACE", fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingReplaceAction = null }) {
                    Text("CANCEL", fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun PairingChecklistView(
    linkState: LinkState,
    connectionState: ConnectionState,
    isTrackerResponding: Boolean,
    liveTelemetry: BinaryPacketCodec.ParsedTripPoint?,
    trackerWaitSeconds: Int,
    onRetry: () -> Unit,
    onOpenAdvancedSettings: (() -> Unit)?,
    onDone: () -> Unit
) {
    // Step 1: QR Scanned -> always SUCCESS here
    val step1Status = ChecklistStepStatus.SUCCESS

    // Step 2: Broker Reachable
    val (step2Status, step2Hint) = when {
        connectionState is ConnectionState.BROKER_NOT_CONFIGURED ->
            ChecklistStepStatus.FAILED to "Relay address is missing. Open Advanced settings or scan a QR v2 code."
        linkState is LinkState.Error ->
            ChecklistStepStatus.FAILED to "Can't reach the relay: ${StateMessageCatalog.sanitizeReason(linkState.message)}"
        linkState is LinkState.Connected ->
            ChecklistStepStatus.SUCCESS to "Connected to encrypted relay"
        else ->
            ChecklistStepStatus.IN_PROGRESS to "Connecting to relay server…"
    }

    // Step 3: Tracker Online (60s timeout)
    val (step3Status, step3Hint) = when {
        step2Status != ChecklistStepStatus.SUCCESS ->
            ChecklistStepStatus.PENDING to "Waiting for relay connection"
        isTrackerResponding ->
            ChecklistStepStatus.SUCCESS to "Tracker is online and authenticated"
        trackerWaitSeconds >= 60 ->
            ChecklistStepStatus.FAILED to "Timed out after 60s. Possible causes: tracker power is off, no Wi-Fi/SIM signal, or wrong encryption key."
        else ->
            ChecklistStepStatus.IN_PROGRESS to "Waiting for tracker response (${60 - trackerWaitSeconds}s)…"
    }

    // Step 4: GPS Fix
    val satCount = liveTelemetry?.satCount ?: 0
    val (step4Status, step4Hint) = when {
        step3Status != ChecklistStepStatus.SUCCESS ->
            ChecklistStepStatus.PENDING to "Waiting for tracker to come online"
        satCount > 0 ->
            ChecklistStepStatus.SUCCESS to "Locked onto $satCount satellites"
        else ->
            ChecklistStepStatus.WARNING to "Searching for satellites. Move motorcycle to open sky."
    }

    Column(
        modifier = Modifier
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "PAIRING CHECKLIST",
            fontFamily = Rajdhani,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(16.dp))

        ChecklistStepRow(
            number = "1",
            title = "QR Scanned",
            hint = "Tracker credentials saved to your phone",
            status = step1Status
        )
        Spacer(modifier = Modifier.height(10.dp))

        ChecklistStepRow(
            number = "2",
            title = "Relay Reachable",
            hint = step2Hint,
            status = step2Status
        )
        Spacer(modifier = Modifier.height(10.dp))

        ChecklistStepRow(
            number = "3",
            title = "Tracker Online",
            hint = step3Hint,
            status = step3Status
        )
        Spacer(modifier = Modifier.height(10.dp))

        ChecklistStepRow(
            number = "4",
            title = "GPS Fix",
            hint = step4Hint,
            status = step4Status
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (step2Status == ChecklistStepStatus.FAILED || step3Status == ChecklistStepStatus.FAILED) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("RETRY", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                if (connectionState is ConnectionState.BROKER_NOT_CONFIGURED && onOpenAdvancedSettings != null) {
                    OutlinedButton(
                        onClick = {
                            onDone()
                            onOpenAdvancedSettings()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("OPEN ADVANCED", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f)
            ) {
                val btnText = if (step3Status == ChecklistStepStatus.SUCCESS) "OPEN COCKPIT" else "CONTINUE"
                Text(btnText, fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ChecklistStepRow(
    number: String,
    title: String,
    hint: String,
    status: ChecklistStepStatus
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            when (status) {
                ChecklistStepStatus.IN_PROGRESS -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = RoyalBrass
                    )
                }
                ChecklistStepStatus.SUCCESS -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = PhosphorGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
                ChecklistStepStatus.WARNING -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = RoyalBrass,
                        modifier = Modifier.size(20.dp)
                    )
                }
                ChecklistStepStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = SignalRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
                ChecklistStepStatus.PENDING -> {
                    Text(
                        text = number,
                        fontFamily = Rajdhani,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$number. $title",
                fontFamily = Rajdhani,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = hint,
                fontFamily = Inter,
                fontSize = 11.sp,
                color = when (status) {
                    ChecklistStepStatus.FAILED -> SignalRed
                    ChecklistStepStatus.WARNING -> RoyalBrass
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

private fun extractIdFromUri(qrUriString: String): String? {
    return try {
        val uri = Uri.parse(qrUriString)
        uri.getQueryParameter("id") ?: uri.getQueryParameter("device_id")
    } catch (_: Exception) {
        null
    }
}

@ExperimentalGetImage
@Composable
private fun CameraXScannerTab(
    onQrScanned: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasScanned by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val executor = ContextCompat.getMainExecutor(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = CameraPreview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val scanner = BarcodeScanning.getClient()
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        imageAnalysis.setAnalyzer(executor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage != null && !hasScanned) {
                                val inputImage = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                scanner.process(inputImage)
                                    .addOnSuccessListener { barcodes ->
                                        if (!hasScanned) {
                                            for (barcode in barcodes) {
                                                barcode.rawValue?.let { raw ->
                                                    if (raw.startsWith("safar://")) {
                                                        hasScanned = true
                                                        onQrScanned(raw)
                                                        break
                                                    } else {
                                                        hasScanned = true
                                                        onError("Invalid QR Code Scheme. Expected safar://pair")
                                                        scope.launch {
                                                            delay(2000)
                                                            hasScanned = false
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .addOnCompleteListener {
                                        imageProxy.close()
                                    }
                            } else {
                                imageProxy.close()
                            }
                        }

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis
                            )
                        } catch (_: Exception) {
                        }
                    }, executor)

                    previewView
                },
                modifier = Modifier.fillMaxWidth().height(200.dp)
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = RoyalBrass)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Camera permission required to scan QR code", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("GRANT PERMISSION", fontSize = 11.sp, fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("APP SETTINGS", fontSize = 11.sp, fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
