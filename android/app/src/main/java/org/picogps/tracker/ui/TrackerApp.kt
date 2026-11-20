package org.picogps.tracker.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.picogps.tracker.capture.CaptureConnection
import org.picogps.tracker.capture.CaptureState
import org.picogps.tracker.capture.TrackerCapture
import org.picogps.tracker.maps.MapImport
import org.picogps.tracker.maps.MapPosition
import org.picogps.tracker.maps.OfflineMap
import org.picogps.tracker.maps.OfflineMapStore
import org.picogps.tracker.maps.TrackMap
import org.picogps.tracker.storage.RoutePoint
import org.picogps.tracker.storage.Trip
import org.picogps.tracker.storage.TripRepository
import org.picogps.tracker.storage.TripSegment
import org.picogps.tracker.storage.TripState

private val Paper = Color(0xfff6f4eb)
private val Panel = Color(0xfffffdf5)
private val Forest = Color(0xff175d43)
private val ForestTint = Color(0xffdeead8)
private val Ink = Color(0xff1c3128)
private val Muted = Color(0xff52645a)
private val Border = Color(0xffdce1d5)
private val Rust = Color(0xffa4422b)
private val RoundedPanel = RoundedCornerShape(22.dp)

@Composable
internal fun TrackerApp(
    repository: TripRepository,
    maps: OfflineMapStore,
    serviceReady: Boolean,
    bluetoothMessage: String?,
    scan: () -> Unit,
    select: (String) -> Unit,
    disconnect: () -> Unit,
    clearCaptureMessage: () -> Unit,
    startRecording: (Long?) -> Unit,
    stopRecording: () -> Unit,
) {
    val capture by TrackerCapture.state.collectAsStateWithLifecycle()
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var storageError by remember { mutableStateOf<String?>(null) }
    val reportStorageError: () -> Unit = { storageError = "Trip storage is unavailable." }
    val trips by remember(repository) { repository.watchTrips().catch { reportStorageError() } }
        .collectAsStateWithLifecycle(emptyList())
    var history by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val viewingTrip = if (history) trips.firstOrNull { it.id == selectedId } else null
    val liveTrip = trips.firstOrNull { it.id == capture.activeTripId }
    val observedId = if (history) selectedId else capture.activeTripId
    val route = key(observedId) {
        remember(observedId) {
            observedId?.let { repository.watchRoute(it).catch { reportStorageError() } } ?: flowOf(emptyList())
        }.collectAsStateWithLifecycle(emptyList()).value
    }
    val segments = key(observedId) {
        remember(observedId) {
            observedId?.let { repository.watchSegments(it).catch { reportStorageError() } } ?: flowOf(emptyList())
        }.collectAsStateWithLifecycle(emptyList()).value
    }

    var offlineMap by remember { mutableStateOf<OfflineMap?>(null) }
    var inspecting by remember { mutableStateOf(true) }
    var viewingMaps by rememberSaveable { mutableStateOf(false) }
    var mapImport by remember { mutableStateOf<MapImport?>(null) }
    var mapError by remember { mutableStateOf(false) }
    var mapMessage by remember { mutableStateOf<String?>(null) }
    var choosingReceiver by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var cameraRequest by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(maps) {
        try {
            offlineMap = maps.loadActive()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            snackbar.showSnackbar("The installed offline map could not be opened.")
        } finally {
            inspecting = false
        }
    }
    LaunchedEffect(capture.message) {
        capture.message?.let {
            snackbar.showSnackbar(it)
            clearCaptureMessage()
        }
    }
    LaunchedEffect(storageError) {
        storageError?.let { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(bluetoothMessage, capture.scanning) {
        if (bluetoothMessage != null || capture.scanning) choosingReceiver = true
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                inspecting = true
                mapMessage = null
                try {
                    mapImport = maps.inspect(uri)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    mapMessage = failure.message ?: "Could not read the map pack."
                } finally {
                    inspecting = false
                }
            }
        }
    }
    val dismissMapImport = {
        mapImport?.let(maps::discard)
        mapImport = null
    }
    val installMap = { candidate: MapImport ->
        mapImport = null
        scope.launch {
            inspecting = true
            mapMessage = null
            try {
                offlineMap = maps.install(candidate)
                mapError = false
                viewingMaps = false
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val message = failure.message ?: "Could not install the offline map."
                if (viewingMaps) mapMessage = message else snackbar.showSnackbar(message)
            } finally {
                maps.discard(candidate)
                inspecting = false
            }
        }
    }
    BackHandler(history && selectedId != null) { selectedId = null }
    BackHandler(history && selectedId == null) { history = false }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Forest, onPrimary = Color.White, primaryContainer = ForestTint, onPrimaryContainer = Ink,
        background = Paper, surface = Panel, onBackground = Ink, onSurface = Ink, surfaceVariant = ForestTint,
        onSurfaceVariant = Muted, outline = Border, error = Rust,
    )) {
        Box(Modifier.fillMaxSize().background(Paper).safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                Header(onMaps = {
                    mapMessage = null
                    viewingMaps = true
                })
                when {
                    !history -> {
                        val fresh = capture.hasFreshFix(now)
                        val position = capture.position(stale = !fresh)
                        LiveScreen(
                            capture = capture,
                            now = now,
                            position = position,
                            trip = liveTrip,
                            route = route,
                            pack = offlineMap,
                            mapError = mapError,
                            follow = follow,
                            recenter = cameraRequest,
                            mapGesture = { follow = false },
                            recenterMap = { follow = true; cameraRequest += 1 },
                            onMapError = { mapError = true },
                            chooseReceiver = {
                                choosingReceiver = true
                                if (capture.selectedReceiver == null) scan()
                            },
                            startRecording = { startRecording(null) },
                            stopRecording = stopRecording,
                            serviceReady = serviceReady,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    viewingTrip != null -> TripDetails(
                        trip = viewingTrip,
                        route = route,
                        segments = segments,
                        pack = offlineMap,
                        mapError = mapError,
                        onMapError = { mapError = true },
                        resume = {
                            selectedId = null
                            history = false
                            startRecording(viewingTrip.id)
                        },
                        finish = {
                            scope.launch {
                                try {
                                    repository.finishTrip(viewingTrip.id)
                                } catch (failure: Exception) {
                                    if (failure is CancellationException) throw failure
                                    reportStorageError()
                                }
                            }
                        },
                        canResume = capture.connection == CaptureConnection.STREAMING && capture.activeTripId == null && !capture.recordingTransition,
                        back = { selectedId = null },
                        modifier = Modifier.weight(1f),
                    )
                    else -> HistoryScreen(trips, onSelect = { selectedId = it }, modifier = Modifier.weight(1f))
                }
                ScreenTabs(history) { history = it; selectedId = null }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 66.dp))
        }
        if (choosingReceiver) {
            ReceiverDialog(
                capture, bluetoothMessage, serviceReady, scan, disconnect,
                onSelect = { select(it); choosingReceiver = false },
                dismiss = { choosingReceiver = false },
            )
        }
        val candidate = mapImport
        if (candidate != null) {
            AlertDialog(
                onDismissRequest = dismissMapImport,
                title = { Text("Install offline map?") },
                text = { MapInfo(candidate.info) },
                confirmButton = { Button(onClick = { installMap(candidate) }) { Text("Install map") } },
                dismissButton = { TextButton(onClick = dismissMapImport) { Text("Cancel") } },
            )
        } else if (viewingMaps) {
            AlertDialog(
                onDismissRequest = { viewingMaps = false },
                title = { Text("Offline map") },
                text = {
                    val current = offlineMap
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (current != null) MapInfo(current) else Text("Choose a Pico GPS map pack ZIP to see roads and places offline.")
                        if (inspecting) Text("Reading and verifying map pack…", color = Muted)
                        mapMessage?.let { Text(it, color = Rust) }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !inspecting,
                        onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    ) { Text("Choose ZIP") }
                },
                dismissButton = { TextButton(onClick = { viewingMaps = false }) { Text("Close") } },
            )
        }
    }
}

@Composable
private fun Header(onMaps: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 19.dp, end = 14.dp, top = 5.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(35.dp).clip(RoundedCornerShape(12.dp)).background(Forest)) {
            val pathColor = Color(0xffe6f3d5)
            val a = Offset(size.width * .25f, size.height * .70f)
            val b = Offset(size.width * .46f, size.height * .42f)
            val c = Offset(size.width * .62f, size.height * .54f)
            val d = Offset(size.width * .76f, size.height * .29f)
            drawLine(pathColor, a, b, 2.dp.toPx())
            drawLine(pathColor, b, c, 2.dp.toPx())
            drawLine(pathColor, c, d, 2.dp.toPx())
            drawCircle(pathColor, radius = 2.5.dp.toPx(), center = a)
            drawCircle(pathColor, radius = 2.5.dp.toPx(), center = d)
        }
        Text("Pico GPS", Modifier.padding(start = 10.dp).weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = onMaps) { Text("Offline map") }
    }
}

@Composable
private fun ScreenTabs(history: Boolean, onTab: (Boolean) -> Unit) {
    HorizontalDivider(color = Border)
    Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTab("Live map", selected = !history, onClick = { onTab(false) })
        ScreenTab("Trips", selected = history, onClick = { onTab(true) })
    }
}

@Composable
private fun RowScope.ScreenTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(if (selected) ForestTint else Paper)
            .clickable(onClick = onClick, role = Role.Tab),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Forest else Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LiveScreen(
    capture: CaptureState,
    now: Long,
    position: MapPosition?,
    trip: Trip?,
    route: List<RoutePoint>,
    pack: OfflineMap?,
    mapError: Boolean,
    follow: Boolean,
    recenter: Int,
    mapGesture: () -> Unit,
    recenterMap: () -> Unit,
    onMapError: () -> Unit,
    chooseReceiver: () -> Unit,
    startRecording: () -> Unit,
    stopRecording: () -> Unit,
    serviceReady: Boolean,
    modifier: Modifier = Modifier,
) {
    val recording = capture.activeTripId != null
    val ageMs = capture.latestValidFixElapsedMs?.let { (now - it).coerceAtLeast(0) }
    val fixFresh = capture.hasFreshFix(now)
    val status = when (capture.connection) {
        CaptureConnection.BLUETOOTH_OFF -> "Bluetooth is off"
        CaptureConnection.IDLE -> if (capture.selectedReceiver == null) "No receiver selected" else "Receiver disconnected"
        CaptureConnection.PAIRING -> "Pairing with receiver"
        CaptureConnection.CONNECTING -> "Connecting to receiver"
        CaptureConnection.RECONNECTING -> "Receiver disconnected"
        CaptureConnection.STREAMING -> when {
            capture.latestFix == null -> "Waiting for receiver data"
            fixFresh -> "GPS fix acquired"
            capture.latestFix.latitudeE7 == null -> "Searching for satellites"
            else -> "GPS fix is stale"
        }
    }
    val receiverInfo = if (capture.connection == CaptureConnection.STREAMING) {
        listOfNotNull(capture.selectedReceiver?.name, capture.latestFix?.satellites?.let { "$it satellites" }, ageMs?.let(::fixAge)).joinToString("  ·  ")
    } else listOfNotNull(capture.selectedReceiver?.name, ageMs?.let(::fixAge)).joinToString("  ·  ")
    Column(modifier.padding(start = 14.dp, end = 14.dp, bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Surface(color = Panel, shape = RoundedPanel, modifier = Modifier.fillMaxWidth().border(1.dp, Border, RoundedPanel)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 9.dp).size(9.dp).background(if (fixFresh) Forest else Rust, CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(status, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (receiverInfo.isNotEmpty()) Text(receiverInfo, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = chooseReceiver, enabled = serviceReady) {
                    Text(if (capture.selectedReceiver == null) "Find Pico" else "Receiver")
                }
            }
        }
        MapPanel(
            pack, route, position, follow, false, recenter, mapError, mapGesture, onMapError,
            recenterMap.takeIf { position != null }, Modifier.fillMaxWidth().weight(1f),
        )
        if (position != null) {
            val coordinates = String.format(Locale.ROOT, "%.6f,  %.6f", position.latitude, position.longitude)
            Text(coordinates, Modifier.padding(start = 4.dp), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        StatsGrid(
            values = listOf(
                "Elapsed" to (trip?.elapsedAt(now)?.let(::duration) ?: "—"),
                "Distance" to (trip?.distanceMetres?.let(::distance) ?: "—"),
                "Speed" to (capture.latestFix?.speedCmPerSecond?.takeIf { fixFresh }?.let { speed(it / 100.0) } ?: "—"),
                "Avg. moving" to (trip?.movingSpeedMetresPerSecond?.let(::speed) ?: "—"),
            ),
        )
        if (capture.missedSequences > 0) {
            Text("Missed fixes · ${capture.missedSequences}", Modifier.padding(start = 4.dp), color = Muted, fontSize = 12.sp)
        }
        Button(
            onClick = if (recording) stopRecording else startRecording,
            modifier = Modifier.fillMaxWidth().height(49.dp),
            enabled = !capture.recordingTransition && (recording || capture.connection == CaptureConnection.STREAMING),
            colors = ButtonDefaults.buttonColors(containerColor = if (recording) Rust else Forest),
        ) {
            Text(
                when {
                    capture.recordingTransition -> "Updating recording…"
                    recording -> "Stop & save trip"
                    else -> "Start recording"
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun MapPanel(
    pack: OfflineMap?,
    route: List<RoutePoint>,
    position: MapPosition?,
    follow: Boolean,
    frameRoute: Boolean,
    recenter: Int,
    mapError: Boolean,
    onGesture: () -> Unit,
    onMapError: () -> Unit,
    recenterMap: (() -> Unit)?,
    modifier: Modifier,
) {
    val message = when {
        mapError -> "Offline map could not load"
        pack == null -> "No offline map installed"
        position != null && !pack.bounds.contains(position.latitude, position.longitude) -> "Outside map coverage"
        frameRoute && route.isNotEmpty() && route.any { !pack.bounds.contains(it.latitudeE7 / 1e7, it.longitudeE7 / 1e7) } -> "Trip extends outside map coverage"
        else -> null
    }
    Box(modifier.clip(RoundedPanel).background(ForestTint).border(1.dp, Border, RoundedPanel)) {
        TrackMap(pack, route, position, follow, frameRoute, recenter, onGesture, onMapError, Modifier.matchParentSize())
        if (message != null) {
            MapChip(message, Modifier.align(Alignment.TopStart).padding(10.dp))
        }
        if (pack != null && !mapError) {
            MapChip(pack.attribution, Modifier.align(Alignment.BottomStart).padding(8.dp), small = true)
        }
        if (recenterMap != null) {
            Surface(
                onClick = recenterMap,
                color = Panel,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Text("Recenter", Modifier.padding(horizontal = 11.dp, vertical = 8.dp), fontSize = 12.sp, color = Forest)
            }
        }
    }
}

@Composable
private fun MapChip(text: String, modifier: Modifier, small: Boolean = false) {
    Surface(modifier, shape = RoundedCornerShape(9.dp), color = Panel.copy(alpha = .94f)) {
        Text(text, Modifier.padding(horizontal = 7.dp, vertical = 4.dp), color = Muted, fontSize = if (small) 10.sp else 11.sp)
    }
}

@Composable
private fun StatsGrid(values: List<Pair<String, String>>) {
    Surface(color = Panel, shape = RoundedPanel, modifier = Modifier.fillMaxWidth().border(1.dp, Border, RoundedPanel)) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            values.forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text(value, fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(label, color = Muted, fontSize = 11.sp, lineHeight = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(trips: List<Trip>, onSelect: (Long) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 15.dp)) {
        Text("Trips", Modifier.padding(start = 4.dp, bottom = 10.dp), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        if (trips.isEmpty()) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedPanel, color = Panel) {
                Text("Recorded trips appear here.", Modifier.padding(22.dp), color = Muted)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(trips, key = { it.id }) { trip ->
                    Surface(onClick = { onSelect(trip.id) }, shape = RoundedPanel, color = Panel, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tripDate(trip.startedUtcMillis), fontWeight = FontWeight.SemiBold)
                                Text("${distance(trip.distanceMetres)}  ·  ${duration(trip.elapsedMillis)}  ·  ${trip.pointCount} fixes", color = Muted, fontSize = 12.sp)
                            }
                            Text(trip.state.lowercase().replaceFirstChar { it.titlecase() }, fontSize = 12.sp, color = if (trip.state == TripState.INTERRUPTED.name) Rust else Forest)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripDetails(
    trip: Trip,
    route: List<RoutePoint>,
    segments: List<TripSegment>,
    pack: OfflineMap?,
    mapError: Boolean,
    onMapError: () -> Unit,
    resume: () -> Unit,
    finish: () -> Unit,
    canResume: Boolean,
    back: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var cameraRequest by rememberSaveable(trip.id) { mutableStateOf(0) }
    val gapCounts = segments.mapNotNull { it.gapBefore }.groupingBy { gapName(it) }.eachCount()
    Column(modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("‹ Trips") }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(tripDate(trip.startedUtcMillis), fontWeight = FontWeight.SemiBold)
                Text(trip.state.lowercase().replaceFirstChar { it.titlecase() }, color = if (trip.state == TripState.INTERRUPTED.name) Rust else Muted, fontSize = 12.sp)
            }
        }
        MapPanel(
            pack, route, null, false, true, cameraRequest, mapError, {}, onMapError,
            if (route.isNotEmpty()) ({ cameraRequest += 1 }) else null, Modifier.fillMaxWidth().weight(1f),
        )
        StatsGrid(listOf(
            "Elapsed" to duration(trip.elapsedMillis), "Distance" to distance(trip.distanceMetres),
            "Avg. moving" to (trip.movingSpeedMetresPerSecond?.let(::speed) ?: "—"), "GPS fixes" to trip.pointCount.toString(),
        ))
        if (gapCounts.isNotEmpty()) {
            Text(gapCounts.entries.joinToString(" · ") { (name, count) -> "$name ($count)" }, Modifier.padding(start = 4.dp), color = Muted, fontSize = 12.sp)
        }
        if (trip.state == TripState.INTERRUPTED.name) {
            if (!canResume) Text("Connect the Pico on the live map to resume.", color = Muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = finish, modifier = Modifier.weight(1f)) { Text("Finish trip") }
                Button(onClick = resume, enabled = canResume, modifier = Modifier.weight(1f)) { Text("Resume") }
            }
        }
    }
}

@Composable
private fun ReceiverDialog(
    capture: CaptureState,
    bluetoothMessage: String?,
    serviceReady: Boolean,
    scan: () -> Unit,
    disconnect: () -> Unit,
    onSelect: (String) -> Unit,
    dismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Pico GPS receivers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (bluetoothMessage != null) Text(bluetoothMessage, color = Rust)
                if (capture.scanning) Text("Scanning nearby…", color = Muted)
                if (capture.receivers.isEmpty() && !capture.scanning && bluetoothMessage == null) {
                    Text("Power on the Pico and open pairing on the receiver before connecting a new phone.")
                }
                LazyColumn(Modifier.heightIn(max = 230.dp)) {
                    items(capture.receivers, key = { it.address }) { receiver ->
                        val selected = receiver.address == capture.selectedReceiver?.address
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = serviceReady && capture.activeTripId == null) { onSelect(receiver.address) }
                                .padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(receiver.name, fontWeight = FontWeight.SemiBold)
                                Text(receiver.address, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                            Text(if (selected) "Selected" else if (receiver.bonded) "Paired" else "Connect", color = Forest, fontSize = 12.sp)
                        }
                        HorizontalDivider(color = Border)
                    }
                }
                if (capture.selectedReceiver != null && capture.activeTripId == null) {
                    TextButton(onClick = { disconnect(); dismiss() }) { Text("Disconnect receiver") }
                }
            }
        },
        confirmButton = { Button(onClick = scan, enabled = serviceReady && !capture.scanning) { Text("Scan") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Close") } },
    )
}

@Composable
private fun MapInfo(map: OfflineMap) {
    val bounds = map.bounds
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(map.name, fontWeight = FontWeight.SemiBold)
        Text(String.format(Locale.ROOT, "%.1f MB installed · Zoom %d–%d", map.sizeBytes / 1_000_000.0, map.minZoom, map.maxZoom), color = Muted)
        Text(
            "${coordinate(bounds.south, "S", "N")} to ${coordinate(bounds.north, "S", "N")}\n" +
                "${coordinate(bounds.west, "W", "E")} to ${coordinate(bounds.east, "W", "E")}",
            color = Muted,
        )
        Text(map.attribution, color = Muted, fontSize = 12.sp)
    }
}

private fun CaptureState.hasFreshFix(now: Long): Boolean = connection == CaptureConnection.STREAMING && latestFix?.latitudeE7 != null &&
    latestFixElapsedMs?.let { now - it <= 5_000 } == true

private fun CaptureState.position(stale: Boolean): MapPosition? {
    val last = latestValidFix ?: return null
    return MapPosition((last.latitudeE7 ?: return null) / 1e7, (last.longitudeE7 ?: return null) / 1e7, stale)
}

private fun fixAge(milliseconds: Long) = if (milliseconds < 2_000) "Fix just now" else "Fix ${duration(milliseconds)} ago"
private fun duration(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
private fun distance(metres: Double) = if (metres < 1000) "${metres.toInt()} m" else String.format(Locale.ROOT, "%.2f km", metres / 1000)
private fun speed(metresPerSecond: Double) = String.format(Locale.ROOT, "%.1f km/h", metresPerSecond * 3.6)
private fun coordinate(value: Double, negative: String, positive: String) =
    String.format(Locale.ROOT, "%.3f° %s", kotlin.math.abs(value), if (value < 0) negative else positive)
private fun tripDate(millis: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
private fun gapName(reason: String) = when (reason) {
    "DISCONNECTED" -> "Bluetooth dropout"
    "NO_FIX" -> "Satellite outage"
    "SEQUENCE_GAP" -> "Missing fixes"
    "SESSION_CHANGED" -> "Receiver restarted"
    "RESUMED" -> "Trip resumed"
    "POSITION_JUMP" -> "Position jump"
    else -> "Route gap"
}
