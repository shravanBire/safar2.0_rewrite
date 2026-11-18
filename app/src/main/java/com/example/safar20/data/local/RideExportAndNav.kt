package com.example.safar20.data.local

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.example.safar20.data.local.entity.LocationPointEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure GPX 1.1 builder and Android Intent helpers for Phase 3:
 *   - P3-R1: Moving duration calculation
 *   - P3-R2: GPX 1.1 export & share via FileProvider
 *   - P3-R4: Navigate to bike (Google Maps or fallback geo: intent) & share location link
 */
object RideExportAndNav {

    /**
     * Computes moving duration in minutes (segments where speed > 1.5 km/h).
     */
    fun movingDurationMinutes(points: List<LocationPointEntity>): Int {
        if (points.size < 2) return 0
        val sorted = points.sortedBy { it.timestamp }
        var movingSeconds = 0L
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]
            val dt = (curr.timestamp - prev.timestamp).coerceIn(0L, 60L)
            if (curr.speedKmh > TripMath.MIN_MOVING_SPEED_KMH || prev.speedKmh > TripMath.MIN_MOVING_SPEED_KMH) {
                movingSeconds += dt
            }
        }
        return maxOf(1, (movingSeconds / 60L).toInt())
    }

    /**
     * Generates a standard GPX 1.1 XML string with time, lat, lon, and speed (m/s) extensions (P3-R2).
     */
    fun buildGpx11Xml(tripName: String, points: List<LocationPointEntity>): String {
        val safeName = tripName
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"SAFAR 2.0\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(safeName).append("</name>\n")
        sb.append("    <trkseg>\n")
        for (pt in points.sortedBy { it.timestamp }) {
            val timeStr = isoFmt.format(Date(pt.timestamp * 1000L))
            val speedMs = String.format(Locale.US, "%.2f", pt.speedKmh / 3.6f)
            val latStr = String.format(Locale.US, "%.7f", pt.latitude)
            val lonStr = String.format(Locale.US, "%.7f", pt.longitude)
            sb.append("      <trkpt lat=\"").append(latStr).append("\" lon=\"").append(lonStr).append("\">\n")
            sb.append("        <time>").append(timeStr).append("</time>\n")
            sb.append("        <extensions><speed>").append(speedMs).append("</speed></extensions>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /**
     * Writes GPX 1.1 file to filesDir and launches Android Share sheet via FileProvider (P3-R2).
     */
    fun shareRideAsGpx(
        context: Context,
        tripId: String,
        tripName: String,
        points: List<LocationPointEntity>
    ): Boolean {
        if (points.isEmpty()) return false
        return try {
            val cleanFileName = tripId.replace(Regex("[^a-zA-Z0-9_-]"), "_") + ".gpx"
            val outFile = File(context.filesDir, cleanFileName)
            outFile.writeText(buildGpx11Xml(tripName, points), Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$tripName (GPX 1.1)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(shareIntent, "Export Ride GPX").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens turn-by-turn navigation to the bike's last real GPS position (P3-R4).
     * Tries Google Maps navigation first, then falls back to standard geo: URI.
     */
    fun navigateToBike(context: Context, lat: Double, lon: Double, label: String): Boolean {
        return try {
            val gmapsNavUri = "google.navigation:q=$lat,$lon".toUri()
            val gmapsIntent = Intent(Intent.ACTION_VIEW, gmapsNavUri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (gmapsIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(gmapsIntent)
                return true
            }
            val encodedLabel = android.net.Uri.encode(label)
            val geoUri = "geo:$lat,$lon?q=$lat,$lon($encodedLabel)".toUri()
            val fallbackIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
            true
        } catch (_: Exception) {
            try {
                val webUri = "https://maps.google.com/?q=$lat,$lon".toUri()
                context.startActivity(Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Shares a Google Maps location link for the bike (P3-R4).
     */
    fun shareBikeLocation(context: Context, lat: Double, lon: Double, bikeName: String): Boolean {
        return try {
            val mapsUrl = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lon)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "$bikeName Location")
                putExtra(Intent.EXTRA_TEXT, "$bikeName location: $mapsUrl")
            }
            context.startActivity(
                Intent.createChooser(shareIntent, "Share Bike Location").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}
