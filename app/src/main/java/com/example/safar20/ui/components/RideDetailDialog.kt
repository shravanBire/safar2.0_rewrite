package com.example.safar20.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.safar20.data.local.RideExportAndNav
import com.example.safar20.data.local.UnitConverter
import com.example.safar20.data.local.entity.LocationPointEntity
import com.example.safar20.data.local.entity.TripSummaryEntity
import com.example.safar20.ui.screens.home.CockpitMapView
import com.example.safar20.ui.theme.BebasNeue
import com.example.safar20.ui.theme.CafeCopper
import com.example.safar20.ui.theme.Inter
import com.example.safar20.ui.theme.Rajdhani
import com.example.safar20.ui.theme.RoyalBrass
import com.example.safar20.ui.theme.SignalRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen Ride Detail Dialog (P3-R1, P3-R2, P3-R3):
 *   - Route Map with CafeCopper polyline and start/end markers
 *   - Stats Grid: Distance, Duration, Moving Time, Avg Speed, Max Speed
 *   - Speed-over-Time Chart drawn with Compose Canvas
 *   - GPX 1.1 Export + Share, Rename Ride, and Delete Ride with confirmation
 */
@Composable
fun RideDetailDialog(
    trip: TripSummaryEntity,
    customName: String?,
    routePoints: List<LocationPointEntity>,
    speedUnit: String,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onExportFailed: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val defaultTitle = "RIDE #${trip.tripId.takeLast(2).uppercase()}"
    val displayTitle = customName?.takeIf { it.isNotBlank() } ?: defaultTitle

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember(displayTitle) { mutableStateOf(displayTitle) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val movingMinutes = remember(routePoints, trip.durationMinutes) {
        if (routePoints.size >= 2) {
            minOf(trip.durationMinutes, RideExportAndNav.movingDurationMinutes(routePoints))
        } else {
            trip.durationMinutes
        }
    }

    val dateTimeSubtitle = remember(trip.startTimestamp, trip.endTimestamp) {
        val dateFmt = SimpleDateFormat("dd MMM yyyy • hh:mm a", Locale.US)
        val endFmt = SimpleDateFormat("hh:mm a", Locale.US)
        "${dateFmt.format(Date(trip.startTimestamp * 1000L))} → ${endFmt.format(Date(trip.endTimestamp * 1000L))}"
    }

    val mapLat = routePoints.firstOrNull()?.latitude ?: 21.1458
    val mapLon = routePoints.firstOrNull()?.longitude ?: 79.0882

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 1. Top Bar: Back + Title + Rename / Delete Icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Column {
                        Text(
                            text = displayTitle.uppercase(),
                            fontFamily = BebasNeue,
                            fontSize = 26.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = dateTimeSubtitle,
                            fontFamily = Inter,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row {
                    IconButton(onClick = {
                        renameText = displayTitle
                        showRenameDialog = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Rename Ride",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Ride",
                            tint = SignalRed
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Map with Route Polyline (CafeCopper) and Start/End Markers
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                CockpitMapView(
                    latitude = mapLat,
                    longitude = mapLon,
                    routePoints = routePoints,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Stats Grid (Distance, Total Time, Moving Time, Avg Speed, Max Speed)
            SectionLabel("RIDE TELEMETRY SUMMARY")
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BrassStatBadge(
                    value = UnitConverter.formatDistance(trip.distanceKm, speedUnit, 1),
                    label = "DISTANCE",
                    modifier = Modifier.weight(1f)
                )
                BrassStatBadge(
                    value = "${trip.durationMinutes} MIN",
                    label = "TOTAL TIME",
                    modifier = Modifier.weight(1f)
                )
                BrassStatBadge(
                    value = "$movingMinutes MIN",
                    label = "MOVING TIME",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BrassStatBadge(
                    value = UnitConverter.formatSpeed(trip.avgSpeedKmh, speedUnit, 0),
                    label = "AVG SPEED",
                    modifier = Modifier.weight(1f)
                )
                BrassStatBadge(
                    value = UnitConverter.formatSpeed(trip.maxSpeedKmh, speedUnit, 0),
                    label = "MAX SPEED",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Speed-Over-Time Canvas Chart (P3-R1)
            SectionLabel("SPEED OVER TIME (${speedUnit.uppercase()})")
            Spacer(modifier = Modifier.height(8.dp))
            BrassCard(modifier = Modifier.fillMaxWidth()) {
                SpeedOverTimeCanvasChart(
                    points = routePoints,
                    fallbackMaxSpeedKmh = trip.maxSpeedKmh,
                    speedUnit = speedUnit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. GPX 1.1 Export + Share Button (P3-R2)
            Button(
                onClick = {
                    val ptsToExport = if (routePoints.isNotEmpty()) {
                        routePoints
                    } else {
                        listOf(
                            LocationPointEntity(
                                tripId = trip.tripId,
                                deviceId = trip.deviceId,
                                timestamp = trip.startTimestamp,
                                latitude = mapLat,
                                longitude = mapLon,
                                speedKmh = trip.avgSpeedKmh.toFloat(),
                                heading = 0
                            )
                        )
                    }
                    val ok = RideExportAndNav.shareRideAsGpx(
                        context = context,
                        tripId = trip.tripId,
                        tripName = displayTitle,
                        points = ptsToExport
                    )
                    if (!ok) {
                        onExportFailed("Could not export GPX file")
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "EXPORT & SHARE GPX 1.1",
                    fontFamily = Rajdhani,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("CLOSE", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Ride", fontFamily = Rajdhani, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Ride Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRename(renameText.trim())
                        showRenameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("SAVE", fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("CANCEL", fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Ride?", fontFamily = Rajdhani, fontWeight = FontWeight.Bold) },
            text = { Text("Delete '$displayTitle' and all of its recorded GPS route points? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SignalRed)
                ) {
                    Text("DELETE", fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("CANCEL", fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun SpeedOverTimeCanvasChart(
    points: List<LocationPointEntity>,
    fallbackMaxSpeedKmh: Double,
    speedUnit: String,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val convertedSpeeds = remember(points, speedUnit) {
        points.sortedBy { it.timestamp }.map {
            UnitConverter.convertSpeed(it.speedKmh.toDouble(), speedUnit).toFloat()
        }
    }
    val maxChartSpeed = remember(convertedSpeeds, fallbackMaxSpeedKmh, speedUnit) {
        val rawMax = convertedSpeeds.maxOrNull()
            ?: UnitConverter.convertSpeed(fallbackMaxSpeedKmh, speedUnit).toFloat()
        maxOf(20f, rawMax * 1.1f)
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "PEAK: ${String.format(Locale.US, "%.0f", convertedSpeeds.maxOrNull() ?: UnitConverter.convertSpeed(fallbackMaxSpeedKmh, speedUnit))} ${speedUnit.uppercase()}",
                fontFamily = Rajdhani,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = RoyalBrass
            )
            Text(
                text = "${points.size} GPS SAMPLES",
                fontFamily = Rajdhani,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Draw 3 horizontal guide lines
            for (i in 0..2) {
                val y = h * (i / 2f)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            if (convertedSpeeds.size >= 2) {
                val path = Path()
                val stepX = w / (convertedSpeeds.size - 1).coerceAtLeast(1)
                convertedSpeeds.forEachIndexed { index, spd ->
                    val x = index * stepX
                    val normalized = (spd / maxChartSpeed).coerceIn(0f, 1f)
                    val y = h - (normalized * (h - 6.dp.toPx())) - 3.dp.toPx()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = CafeCopper,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
            } else {
                val midY = h * 0.5f
                drawLine(
                    color = RoyalBrass,
                    start = Offset(0f, midY),
                    end = Offset(w, midY),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }
}
