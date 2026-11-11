package com.example.safar20.ui.screens.home

import android.graphics.Color
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.safar20.data.local.entity.LocationPointEntity
import com.example.safar20.ui.theme.PhosphorGreen
import com.example.safar20.ui.theme.Rajdhani
import com.example.safar20.ui.theme.RoyalBrass
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Locale
import kotlin.math.abs

/**
 * MapLibre Native MapView for Cockpit & Trips preview.
 * Draws markers and route polylines in the update block per SAFAR 2.0 specs.
 * Fixes touch/scroll conflicts with parent container, provides a Recenter / Follow Bike FAB,
 * and handles tile failure gracefully with a fallback overlay.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CockpitMapView(
    latitude: Double,
    longitude: Double,
    heading: Int = 0,
    satCount: Int? = null,
    trackerName: String = "Tracker Position",
    routePoints: List<LocationPointEntity> = emptyList(),
    liveTrailPoints: List<LocationPointEntity> = emptyList(),
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val context = LocalContext.current

    var isFollowingBike by remember { mutableStateOf(true) }
    var isMapError by remember { mutableStateOf(false) }

    val mapView = remember {
        try {
            MapLibre.getInstance(context)
            MapView(context).apply {
                onCreate(null)
            }
        } catch (_: Throwable) {
            isMapError = true
            null
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    var currentMarker by remember { mutableStateOf<Marker?>(null) }
    var currentPolyline by remember { mutableStateOf<Polyline?>(null) }
    var startMarker by remember { mutableStateOf<Marker?>(null) }
    var endMarker by remember { mutableStateOf<Marker?>(null) }
    var lastCameraLat by remember { mutableDoubleStateOf(0.0) }
    var lastCameraLon by remember { mutableDoubleStateOf(0.0) }

    fun applyMapAnnotations(
        map: MapLibreMap,
        lat: Double,
        lon: Double,
        tName: String,
        pts: List<LocationPointEntity>,
        trailPts: List<LocationPointEntity>,
        follow: Boolean
    ) {
        if (pts.isNotEmpty()) {
            // Historical Trip Route Mode (TripsScreen)
            currentMarker?.let { map.removeAnnotation(it) }
            currentMarker = null

            currentPolyline?.let { map.removeAnnotation(it) }
            startMarker?.let { map.removeAnnotation(it) }
            endMarker?.let { map.removeAnnotation(it) }

            val latLngList = pts.map { LatLng(it.latitude, it.longitude) }

            if (latLngList.size >= 2) {
                val polylineOptions = PolylineOptions()
                    .addAll(latLngList)
                    .color(Color.parseColor("#C86D51"))
                    .width(6f)

                currentPolyline = map.addPolyline(polylineOptions)

                startMarker = map.addMarker(
                    MarkerOptions()
                        .position(latLngList.first())
                        .title("Trip Start")
                )
                endMarker = map.addMarker(
                    MarkerOptions()
                        .position(latLngList.last())
                        .title("Trip End")
                )

                try {
                    val boundsBuilder = LatLngBounds.Builder()
                    latLngList.forEach { boundsBuilder.include(it) }
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 100))
                } catch (_: Exception) {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLngList.first(), 14.0))
                }
            }
        } else if (lat != 0.0 && lon != 0.0) {
            // Live / Parked Cockpit Mode (HomeScreen)
            startMarker?.let { map.removeAnnotation(it) }
            endMarker?.let { map.removeAnnotation(it) }
            startMarker = null
            endMarker = null

            val currentPos = LatLng(lat, lon)

            // Always remove & re-add marker so MapLibre GL Native reliably renders at new coordinates
            currentMarker?.let { map.removeAnnotation(it) }
            currentMarker = map.addMarker(
                MarkerOptions()
                    .position(currentPos)
                    .title(tName)
            )

            // Draw live breadcrumb trail if available
            currentPolyline?.let { map.removeAnnotation(it) }
            currentPolyline = null
            if (trailPts.size >= 2) {
                val trailLatLngs = trailPts.map { LatLng(it.latitude, it.longitude) } + currentPos
                val polylineOptions = PolylineOptions()
                    .addAll(trailLatLngs)
                    .color(Color.parseColor("#D4AF37"))
                    .width(5f)
                currentPolyline = map.addPolyline(polylineOptions)
            }

            if (follow) {
                val dLat = abs(lat - lastCameraLat)
                val dLon = abs(lon - lastCameraLon)
                val isFirstOrLargeJump = (lastCameraLat == 0.0 && lastCameraLon == 0.0) || (dLat > 0.005 || dLon > 0.005)
                val isMoved = dLat > 0.00001 || dLon > 0.00001

                if (isFirstOrLargeJump) {
                    lastCameraLat = lat
                    lastCameraLon = lon
                    // Instant camera move on initial lock / large jump so tiles load immediately
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(currentPos, 15.5))
                } else if (isMoved) {
                    lastCameraLat = lat
                    lastCameraLon = lon
                    // Short 350ms ease so 1s updates don't cancel tile loading
                    val targetZoom = if (map.cameraPosition.zoom < 12.0) 15.5 else map.cameraPosition.zoom
                    map.easeCamera(CameraUpdateFactory.newLatLngZoom(currentPos, targetZoom), 350)
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            try {
                when (event) {
                    Lifecycle.Event.ON_START -> mapView?.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                    Lifecycle.Event.ON_STOP -> mapView?.onStop()
                    Lifecycle.Event.ON_DESTROY -> mapView?.onDestroy()
                    else -> {}
                }
            } catch (_: Throwable) {
                isMapError = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                mapView?.onPause()
                mapView?.onStop()
                mapView?.onDestroy()
            } catch (_: Throwable) {
            }
        }
    }

    Box(modifier = modifier) {
        if (mapView != null && !isMapError) {
            AndroidView(
                factory = {
                    mapView.apply {
                        setOnTouchListener { view, event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                    view.parent?.requestDisallowInterceptTouchEvent(true)
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    view.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                            false
                        }
                        getMapAsync { map ->
                            map.addOnCameraMoveStartedListener { reason ->
                                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                    isFollowingBike = false
                                }
                            }
                            map.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")) {
                                map.uiSettings.isCompassEnabled = false
                                map.uiSettings.isAttributionEnabled = false
                                map.uiSettings.isLogoEnabled = false

                                applyMapAnnotations(
                                    map = map,
                                    lat = latitude,
                                    lon = longitude,
                                    tName = trackerName,
                                    pts = routePoints,
                                    trailPts = liveTrailPoints,
                                    follow = isFollowingBike
                                )
                            }
                            mapView.addOnDidFailLoadingMapListener {
                                isMapError = true
                            }
                        }
                    }
                },
                update = { view ->
                    // Read Compose state synchronously inside update so every coordinate change triggers update
                    val targetLat = latitude
                    val targetLon = longitude
                    val targetName = trackerName
                    val targetRoutes = routePoints
                    val targetTrail = liveTrailPoints
                    val followMode = isFollowingBike

                    view.getMapAsync { map ->
                        if (map.style != null && map.style!!.isFullyLoaded) {
                            applyMapAnnotations(
                                map = map,
                                lat = targetLat,
                                lon = targetLon,
                                tName = targetName,
                                pts = targetRoutes,
                                trailPts = targetTrail,
                                follow = followMode
                            )
                        } else {
                            // Even if style tiles are still streaming over mobile hotspot, move camera & marker
                            if (targetRoutes.isEmpty() && targetLat != 0.0 && targetLon != 0.0 && followMode) {
                                val pos = LatLng(targetLat, targetLon)
                                map.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 15.5))
                            }
                            map.getStyle {
                                applyMapAnnotations(
                                    map = map,
                                    lat = targetLat,
                                    lon = targetLon,
                                    tName = targetName,
                                    pts = targetRoutes,
                                    trailPts = targetTrail,
                                    follow = followMode
                                )
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInteropFilter { motionEvent ->
                        when (motionEvent.action) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                mapView.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                mapView.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        false
                    }
            )
        }

        // Center Live Tracker Beacon (Always visible when FOLLOWING is active, even while tiles load over cellular)
        if (routePoints.isEmpty() && isFollowingBike && latitude != 0.0 && longitude != 0.0) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(22.dp)
                    .background(RoyalBrass.copy(alpha = 0.28f), shape = CircleShape)
                    .border(1.5.dp, RoyalBrass, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(PhosphorGreen, shape = CircleShape)
                )
            }
        }

        // Overlay if Map fails to load tiles
        if (isMapError || mapView == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = "Map Unavailable",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "MAP UNAVAILABLE",
                        fontFamily = Rajdhani,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        // Bottom-Start Live Coordinates & Satellites Pill
        if (routePoints.isEmpty() && latitude != 0.0 && longitude != 0.0) {
            val coordText = remember(latitude, longitude, satCount) {
                val base = String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
                if (satCount != null && satCount > 0) "$base • $satCount SATS" else base
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = coordText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = Rajdhani,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }

        // Floating Recenter / Follow Bike Button
        if (routePoints.isEmpty() && !isMapError && mapView != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
                    .border(
                        1.dp,
                        if (isFollowingBike) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable {
                        isFollowingBike = true
                        if (latitude != 0.0 && longitude != 0.0) {
                            lastCameraLat = latitude
                            lastCameraLon = longitude
                            mapView.getMapAsync { map ->
                                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), 15.5))
                            }
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Recenter or follow tracker position",
                        tint = if (isFollowingBike) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isFollowingBike) "FOLLOWING" else "RECENTER",
                        color = if (isFollowingBike) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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

