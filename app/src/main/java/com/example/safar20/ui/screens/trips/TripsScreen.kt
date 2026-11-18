package com.example.safar20.ui.screens.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safar20.data.local.UnitConverter
import com.example.safar20.ui.components.BrassCard
import com.example.safar20.ui.components.RideDetailDialog
import com.example.safar20.ui.components.RideTicketCard
import com.example.safar20.ui.components.SectionLabel
import com.example.safar20.ui.theme.BebasNeue
import com.example.safar20.ui.theme.Inter
import com.example.safar20.ui.theme.PhosphorGreen
import com.example.safar20.ui.theme.Rajdhani
import com.example.safar20.ui.theme.RoyalBrass
import com.example.safar20.viewModels.SafarViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Logbook Screen displaying vintage highway ride tickets, 14-day horizontal date selector,
 * pull-to-refresh ("Sync now" via requestHistorySync) with live progress percentage,
 * and friendly first-ride guidance empty states (P2-U5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    viewModel: SafarViewModel,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val tripHistory by viewModel.tripHistory.collectAsState()
    val selectedTripId by viewModel.selectedTripId.collectAsState()
    val selectedTripPoints by viewModel.selectedTripPoints.collectAsState()
    val speedUnit by viewModel.speedUnit.collectAsState()
    val historySyncProgress by viewModel.historySyncProgress.collectAsState()
    val customTripNames by viewModel.customTripNames.collectAsState()
    val completedRideSummary by viewModel.completedRideSummary.collectAsState()

    var isPullRefreshing by remember { mutableStateOf(false) }
    var detailDialogTripId by remember { mutableStateOf<String?>(null) }

    val dateList = remember {
        (0..13).map { daysAgo ->
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -daysAgo)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }
    }

    var selectedDateIndex by remember { mutableIntStateOf(0) }

    val selectedDateText = remember(selectedDateIndex) {
        if (selectedDateIndex == 0) {
            "ALL HISTORICAL RIDES"
        } else {
            val cal = dateList[selectedDateIndex - 1]
            val fmt = SimpleDateFormat("dd MMM yyyy", Locale.US)
            fmt.format(cal.time).uppercase()
        }
    }

    val isDemoData = remember(tripHistory) {
        tripHistory.any { it.tripId.startsWith("DEMO_") }
    }

    LaunchedEffect(selectedDevice?.deviceId) {
        if (selectedDevice != null) {
            viewModel.requestHistorySync()
        }
    }

    LaunchedEffect(tripHistory) {
        if (selectedTripId == null && tripHistory.isNotEmpty()) {
            viewModel.selectTripForMapPreview(tripHistory.first().tripId)
        }
    }

    val filteredTrips = remember(tripHistory, selectedDateIndex) {
        if (selectedDateIndex == 0) {
            tripHistory
        } else {
            val cal = dateList[selectedDateIndex - 1]
            val startSec = cal.timeInMillis / 1000
            val endSec = startSec + 86400L
            tripHistory.filter { it.startTimestamp in startSec..endSec }
        }
    }

    val datesWithRidesSet = remember(tripHistory) {
        tripHistory.map { trip ->
            val cal = Calendar.getInstance().apply {
                timeInMillis = trip.startTimestamp * 1000L
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        }.toSet()
    }

    val triggerManualSync = {
        val requested = viewModel.requestHistorySync()
        if (requested) {
            viewModel.showTransientMessage("Syncing rides from tracker…")
        } else {
            viewModel.showTransientMessage("Tracker is offline — will sync automatically when connected")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 1. Screen Header: SectionLabel + SYNC NOW button + DEMO DATA Badge + Bebas Date Title
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("LOGBOOK")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isDemoData) {
                        Box(
                            modifier = Modifier
                                .background(RoyalBrass.copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp))
                                .border(1.dp, RoyalBrass, shape = RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "DEMO DATA",
                                color = RoyalBrass,
                                fontFamily = Rajdhani,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                    if (selectedDevice != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                .clickable { triggerManualSync() }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = "Sync Now",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (historySyncProgress != null) "SYNCING ${historySyncProgress}%" else "SYNC NOW",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = Rajdhani,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = selectedDateText,
                fontFamily = BebasNeue,
                fontSize = 32.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (historySyncProgress != null) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (historySyncProgress ?: 0) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = RoyalBrass,
                    trackColor = MaterialTheme.colorScheme.surface
                )
            }

            // Ride-Complete Summary Card (P3-R7)
            if (completedRideSummary != null) {
                val summary = completedRideSummary!!
                val distStr = UnitConverter.formatDistance(summary.distanceKm, speedUnit, 1)
                val maxStr = UnitConverter.formatSpeed(summary.maxSpeedKmh, speedUnit, 0)
                Spacer(modifier = Modifier.height(8.dp))
                BrassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.selectTripForMapPreview(summary.tripId)
                            detailDialogTripId = summary.tripId
                        }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ride complete · $distStr · max $maxStr",
                            fontFamily = Rajdhani,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = PhosphorGreen,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.dismissCompletedRideSummary() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Horizontal Scrolling Date Chip Strip with Indicator Dots
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(1 + dateList.size) { index ->
                val isSelected = (index == selectedDateIndex)
                val hasRidesOnDate = if (index == 0) {
                    tripHistory.isNotEmpty()
                } else {
                    datesWithRidesSet.contains(dateList[index - 1].timeInMillis)
                }

                val dayOfWeek = if (index == 0) "RIDES" else SimpleDateFormat("EEE", Locale.US).format(dateList[index - 1].time).uppercase()
                val dayNum = if (index == 0) "ALL" else SimpleDateFormat("dd", Locale.US).format(dateList[index - 1].time)

                Box(
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                        .clickable { selectedDateIndex = index }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = dayOfWeek,
                                fontFamily = Rajdhani,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (hasRidesOnDate) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .background(if (isSelected) MaterialTheme.colorScheme.onPrimary else PhosphorGreen, shape = CircleShape)
                                )
                            }
                        }
                        Text(
                            text = dayNum,
                            fontFamily = BebasNeue,
                            fontSize = 20.sp,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Pull-to-refresh Logbook Ride Tickets or Empty State Guidance (P2-U5)
        PullToRefreshBox(
            isRefreshing = isPullRefreshing || historySyncProgress != null,
            onRefresh = {
                scope.launch {
                    isPullRefreshing = true
                    triggerManualSync()
                    delay(800L)
                    isPullRefreshing = false
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                selectedDevice == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "PAIR YOUR TRACKER TO START",
                                fontFamily = Rajdhani,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Once paired, every ride recorded on your motorcycle's flash memory will sync here automatically.",
                                fontFamily = Inter,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                historySyncProgress != null && filteredTrips.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = RoyalBrass,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "SYNCING RIDES… ${historySyncProgress}%",
                                fontFamily = Rajdhani,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            CircularProgressIndicator(
                                color = RoyalBrass,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
                filteredTrips.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (selectedDateIndex == 0) "YOUR FIRST RIDE AWAITS" else "NO RIDES ON THIS DATE",
                                fontFamily = Rajdhani,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                letterSpacing = 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (selectedDateIndex == 0)
                                    "Start your motorcycle and ride. Even if you ride through zero-network zones, SAFAR records your route on the tracker and syncs it here when you finish."
                                else
                                    "Select 'ALL' to see all recorded rides or pull down to sync from your tracker.",
                                fontFamily = Inter,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { triggerManualSync() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("SYNC NOW", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(
                            items = filteredTrips,
                            key = { it.tripId }
                        ) { trip ->
                            val isSelected = (trip.tripId == selectedTripId)
                            RideTicketCard(
                                trip = trip,
                                speedUnit = speedUnit,
                                isSelected = isSelected,
                                routePoints = if (isSelected) selectedTripPoints else emptyList(),
                                customName = customTripNames[trip.tripId],
                                onCardClick = {
                                    viewModel.selectTripForMapPreview(trip.tripId)
                                    detailDialogTripId = trip.tripId
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Ride Detail Screen Dialog (P3-R1, P3-R2, P3-R3)
    val detailTrip = remember(detailDialogTripId, tripHistory) {
        tripHistory.find { it.tripId == detailDialogTripId }
    }
    if (detailTrip != null) {
        RideDetailDialog(
            trip = detailTrip,
            routePoints = if (detailTrip.tripId == selectedTripId) selectedTripPoints else emptyList(),
            customName = customTripNames[detailTrip.tripId],
            speedUnit = speedUnit,
            onDismiss = { detailDialogTripId = null },
            onRename = { newName ->
                viewModel.renameTrip(detailTrip.tripId, newName)
            },
            onDelete = {
                viewModel.deleteTrip(detailTrip.tripId)
                detailDialogTripId = null
            },
            onExportFailed = { msg -> viewModel.showTransientMessage(msg) }
        )
    }
}
