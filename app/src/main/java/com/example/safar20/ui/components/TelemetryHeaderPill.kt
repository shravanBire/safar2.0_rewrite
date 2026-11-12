package com.example.safar20.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safar20.ui.theme.PhosphorGreen
import com.example.safar20.ui.theme.RoyalBrass
import com.example.safar20.ui.theme.SignalRed

sealed interface ConnectionState {
    object NO_DEVICE : ConnectionState
    object BROKER_NOT_CONFIGURED : ConnectionState
    object CONNECTING : ConnectionState
    data class BROKER_ERROR(val message: String) : ConnectionState
    object WAITING_FOR_TRACKER : ConnectionState
    object LIVE : ConnectionState
    object LIVE_NO_FIX : ConnectionState
    data class SYNCING(val progressPercent: Int) : ConnectionState
    data class PARKED(val lastSeenTimestamp: Long?) : ConnectionState
}

/**
 * Top Cockpit Bar with Bike Selector Pill (Left) and Live Telemetry Status Badge (Right).
 */
@Composable
fun TelemetryHeaderPill(
    activeDeviceName: String = "No Tracker",
    connectionState: ConnectionState = ConnectionState.NO_DEVICE,
    onDeviceClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Pill — Active Motorcycle Dropdown Selector
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(20.dp))
                .clickable { onDeviceClick() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.TwoWheeler,
                contentDescription = "Motorcycle",
                tint = RoyalBrass,
                modifier = Modifier.size(16.dp)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = activeDeviceName,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Select Device",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        // Right Pill — Live Telemetry Status Badge (via StateMessageCatalog P2-U4)
        val entry = StateMessageCatalog.resolve(connectionState)
        val dotColor = when (connectionState) {
            is ConnectionState.LIVE -> PhosphorGreen
            is ConnectionState.LIVE_NO_FIX,
            is ConnectionState.SYNCING,
            is ConnectionState.CONNECTING,
            is ConnectionState.WAITING_FOR_TRACKER -> RoyalBrass
            is ConnectionState.BROKER_ERROR -> SignalRed
            is ConnectionState.PARKED,
            is ConnectionState.BROKER_NOT_CONFIGURED,
            is ConnectionState.NO_DEVICE -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(20.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, shape = CircleShape)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = entry.pillLabel.uppercase(),
                color = dotColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}
