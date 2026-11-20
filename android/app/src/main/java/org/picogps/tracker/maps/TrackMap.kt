package org.picogps.tracker.maps

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.RenderingEngine
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.picogps.tracker.storage.RoutePoint

internal data class MapPosition(val latitude: Double, val longitude: Double, val stale: Boolean) {
    fun asLatLng() = LatLng(latitude.coerceIn(-85.05113, 85.05113), longitude)
}

@Composable
internal fun TrackMap(
    pack: OfflineMap?,
    route: List<RoutePoint>,
    position: MapPosition?,
    follow: Boolean,
    frameRoute: Boolean,
    recenter: Int,
    onGesture: () -> Unit,
    onMapError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    AndroidView(
        factory = { TrackMapView(it, lifecycle) },
        update = { it.update(pack, route, position, follow, frameRoute, recenter, onGesture, onMapError) },
        onRelease = { it.release() },
        modifier = modifier,
    )
}

private class TrackMapView(context: Context, private val lifecycle: Lifecycle) : MapView(context, options(context)) {
    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { this@TrackMapView.onStart() }
        override fun onResume(owner: LifecycleOwner) { this@TrackMapView.onResume() }
        override fun onPause(owner: LifecycleOwner) { this@TrackMapView.onPause() }
        override fun onStop(owner: LifecycleOwner) { this@TrackMapView.onStop() }
    }
    private var map: MapLibreMap? = null
    private var pack: OfflineMap? = null
    private var route: List<RoutePoint> = emptyList()
    private var position: MapPosition? = null
    private var follow = true
    private var frameRoute = false
    private var recenter = 0
    private var gesture: () -> Unit = {}
    private var mapError: () -> Unit = {}
    private var routeSource: GeoJsonSource? = null
    private var segmentSource: GeoJsonSource? = null
    private var positionSource: GeoJsonSource? = null
    private var markerLayer: CircleLayer? = null
    private var fallback = false
    private var released = false
    private var hasFramed = false
    private var hasFollowed = false

    init {
        onCreate(null)
        lifecycle.addObserver(observer)
        addOnDidFailLoadingMapListener {
            if (!fallback && pack != null) {
                mapError()
                loadStyle(useFallback = true)
            }
        }
        getMapAsync { ready ->
            if (released) return@getMapAsync
            map = ready
            ready.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) gesture()
            }
            loadStyle()
        }
    }

    fun release() {
        if (released) return
        released = true
        lifecycle.removeObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onPause()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) onStop()
        onDestroy()
        map = null
    }

    fun update(
        pack: OfflineMap?,
        route: List<RoutePoint>,
        position: MapPosition?,
        follow: Boolean,
        frameRoute: Boolean,
        recenter: Int,
        onGesture: () -> Unit,
        onMapError: () -> Unit,
    ) {
        val packChanged = this.pack?.directory != pack?.directory
        val routeChanged = this.route !== route
        val positionChanged = this.position != position
        val modeChanged = this.frameRoute != frameRoute
        val recentered = this.recenter != recenter
        val followNow = follow && !this.follow
        this.pack = pack
        this.route = route
        this.position = position
        this.follow = follow
        this.frameRoute = frameRoute
        this.recenter = recenter
        gesture = onGesture
        mapError = onMapError
        if (modeChanged || recentered) hasFramed = false
        if (modeChanged) hasFollowed = false
        if (packChanged) {
            loadStyle()
        } else {
            if (routeChanged) updateRoute()
            if (positionChanged) updateMarker()
            if (positionChanged || routeChanged || followNow || modeChanged || recentered) updateCamera()
        }
    }

    private fun loadStyle(useFallback: Boolean = false) {
        val map = map ?: return
        fallback = useFallback
        routeSource = null
        segmentSource = null
        positionSource = null
        markerLayer = null
        hasFramed = false
        hasFollowed = false
        val json = if (!useFallback) try {
            pack?.styleJson() ?: BLANK_STYLE
        } catch (error: Exception) {
            fallback = true
            mapError()
            BLANK_STYLE
        } else BLANK_STYLE
        map.setStyle(Style.Builder().fromJson(json)) { style ->
            if (released) return@setStyle
            addOverlays(style)
            updateRoute()
            updateMarker()
            val bounds = pack?.bounds
            if (bounds != null && (position == null || !follow) && route.isEmpty()) {
                frame(listOf(LatLng(bounds.south, bounds.west), LatLng(bounds.north, bounds.east)))
            }
            updateCamera()
        }
    }

    private fun addOverlays(style: Style) {
        routeSource = GeoJsonSource("pico-overlay-route").also(style::addSource)
        segmentSource = GeoJsonSource("pico-overlay-segments").also(style::addSource)
        positionSource = GeoJsonSource("pico-overlay-position").also(style::addSource)
        style.addLayer(LineLayer("pico-overlay-route-shadow", "pico-overlay-route").withProperties(
            lineColor(Color.WHITE), lineWidth(8f), lineJoin(Property.LINE_JOIN_ROUND), lineCap(Property.LINE_CAP_ROUND),
        ))
        style.addLayer(LineLayer("pico-overlay-route-line", "pico-overlay-route").withProperties(
            lineColor(ROUTE_COLOR), lineWidth(4.5f), lineJoin(Property.LINE_JOIN_ROUND), lineCap(Property.LINE_CAP_ROUND),
        ))
        style.addLayer(CircleLayer("pico-overlay-segment-start", "pico-overlay-segments").withProperties(
            circleColor(ROUTE_COLOR), circleRadius(4f), circleStrokeColor(Color.WHITE), circleStrokeWidth(2f),
        ))
        markerLayer = CircleLayer("pico-overlay-current-position", "pico-overlay-position").withProperties(
            circleColor(ROUTE_COLOR), circleRadius(8f), circleStrokeColor(Color.WHITE), circleStrokeWidth(3f),
        ).also(style::addLayer)
    }

    private fun updateRoute() {
        val source = routeSource ?: return
        val segments = route.groupBy { it.segmentId }.values
        val features = segments.filter { it.size > 1 }.map { segment ->
            Feature.fromGeometry(LineString.fromLngLats(segment.map { point ->
                Point.fromLngLat(point.longitudeE7 / 1e7, point.latitudeE7 / 1e7)
            }))
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
        segmentSource?.setGeoJson(FeatureCollection.fromFeatures(segments.map { segment ->
            val point = segment.first()
            Feature.fromGeometry(Point.fromLngLat(point.longitudeE7 / 1e7, point.latitudeE7 / 1e7))
        }))
    }

    private fun updateMarker() {
        val fix = position
        val features = if (fix != null) listOf(Feature.fromGeometry(Point.fromLngLat(fix.longitude, fix.latitude))) else emptyList()
        positionSource?.setGeoJson(FeatureCollection.fromFeatures(features))
        markerLayer?.setProperties(circleColor(if (fix?.stale == true) STALE_COLOR else ROUTE_COLOR))
    }

    private fun updateCamera() {
        val map = map ?: return
        val fix = position
        if (frameRoute) {
            if (!hasFramed && route.isNotEmpty()) {
                hasFramed = true
                frame(route.map { LatLng((it.latitudeE7 / 1e7).coerceIn(-85.05113, 85.05113), it.longitudeE7 / 1e7) })
            }
        } else if (follow && fix != null) {
            val target = fix.asLatLng()
            val update = if (hasFollowed) CameraUpdateFactory.newLatLng(target) else {
                hasFollowed = true
                CameraUpdateFactory.newLatLngZoom(target, 15.0)
            }
            map.moveCamera(update)
        }
    }

    private fun frame(points: List<LatLng>) {
        val map = map ?: return
        val first = points.first()
        if (points.all { it == first }) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(first, 15.0))
        } else {
            val bounds = LatLngBounds.Builder().includes(points).build()
            val padding = (38 * resources.displayMetrics.density).toInt().coerceAtMost(minOf(width, height) / 4)
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
        }
    }
}

private fun options(context: Context): MapLibreMapOptions {
    MapLibre.getInstance(context.applicationContext, null, WellKnownTileServer.MapLibre, RenderingEngine.Type.OPENGL)
    MapLibre.setConnected(false)
    return MapLibreMapOptions.createFromAttributes(context)
        .textureMode(true)
        .attributionEnabled(false)
        .logoEnabled(false)
}

private val ROUTE_COLOR = Color.rgb(23, 98, 71)
private val STALE_COLOR = Color.rgb(154, 96, 47)
private const val BLANK_STYLE = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#E7EDE0"}}]}"""
