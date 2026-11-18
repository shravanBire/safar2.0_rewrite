package com.example.safar20.ui.components

import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.example.safar20.data.local.EmergencyContact
import com.example.safar20.data.local.RideExportAndNav
import com.example.safar20.data.network.BinaryPacketCodec
import com.example.safar20.ui.screens.home.CockpitMapView
import com.example.safar20.ui.theme.BebasNeue
import com.example.safar20.ui.theme.Inter
import com.example.safar20.ui.theme.PhosphorGreen
import com.example.safar20.ui.theme.Rajdhani
import com.example.safar20.ui.theme.RoyalBrass
import com.example.safar20.ui.theme.SignalRed

/**
 * Full-screen SOS Alert Overlay (P3-R6).
 * Displays live crash/manual SOS state (countdown, cancelled, fired),
 * map location, "Call Contact", and "Open in Maps".
 */
@Composable
fun SosAlertOverlay(
    sosEvent: BinaryPacketCodec.SosEventPacket,
    trackerName: String,
    emergencyContacts: List<EmergencyContact>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val primaryContact = emergencyContacts.firstOrNull { it.number.isNotBlank() }

    val (stateTitle, stateColor) = when (sosEvent.state) {
        BinaryPacketCodec.SOS_STATE_CANCELLED -> "SOS CANCELLED BY RIDER" to PhosphorGreen
        BinaryPacketCodec.SOS_STATE_FIRED -> "EMERGENCY SOS FIRED" to SignalRed
        else -> "CRASH SOS COUNTDOWN ACTIVE" to SignalRed
    }

    val reasonText = if (sosEvent.reason == BinaryPacketCodec.SOS_REASON_MANUAL) {
        "Manual SOS triggered on $trackerName"
    } else {
        "High-impact crash or fall detected on $trackerName"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(stateColor.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                        .border(1.dp, stateColor, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = stateColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stateTitle,
                        fontFamily = Rajdhani,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = stateColor,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = reasonText.uppercase(),
                    fontFamily = BebasNeue,
                    fontSize = 28.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Dev Build Note: Tracker GSM call/SMS action is stubbed until SIM module is attached.",
                    fontFamily = Inter,
                    fontSize = 11.sp,
                    color = RoyalBrass,
                    textAlign = TextAlign.Center
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 16.dp)
                    .border(1.5.dp, stateColor, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
            ) {
                CockpitMapView(
                    latitude = sosEvent.tripPoint.latitude,
                    longitude = sosEvent.tripPoint.longitude,
                    heading = sosEvent.tripPoint.heading,
                    satCount = sosEvent.tripPoint.satCount,
                    trackerName = "$trackerName SOS",
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (primaryContact != null) {
                    Button(
                        onClick = {
                            try {
                                val dialIntent = Intent(Intent.ACTION_DIAL, "tel:${primaryContact.number}".toUri()).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(dialIntent)
                            } catch (_: Exception) {
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SignalRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        val label = if (primaryContact.name.isNotBlank()) {
                            "CALL ${primaryContact.name.uppercase()} (${primaryContact.number})"
                        } else {
                            "CALL CONTACT (${primaryContact.number})"
                        }
                        Text(label, fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }

                Button(
                    onClick = {
                        RideExportAndNav.navigateToBike(
                            context = context,
                            lat = sosEvent.tripPoint.latitude,
                            lon = sosEvent.tripPoint.longitude,
                            label = "$trackerName SOS Location"
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("OPEN IN MAPS", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("DISMISS ALERT", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}
