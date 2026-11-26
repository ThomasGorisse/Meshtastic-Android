/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
@file:Suppress("MagicNumber", "TooManyFunctions")

package org.meshtastic.feature.xr

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.NodeScope
import io.github.sceneview.ar.ARCoachingOverlay
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.PlacementReticleVisual
import io.github.sceneview.ar.ReticlePhase
import io.github.sceneview.ar.placementHit
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.ar.rememberArGuidanceState
import io.github.sceneview.ar.reticlePhaseFor
import io.github.sceneview.ar.scene.SceneUnderstanding
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.model
import io.github.sceneview.node.Node
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.close
import org.meshtastic.core.resources.xr_terrain_empty
import org.meshtastic.core.resources.xr_terrain_flat
import org.meshtastic.core.resources.xr_terrain_loading
import org.meshtastic.core.resources.xr_terrain_unavailable
import org.meshtastic.core.ui.icon.Close
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.feature.map.terrain.spatial.LatLon
import org.meshtastic.feature.map.terrain.spatial.SpatialMapArea
import org.meshtastic.feature.map.terrain.spatial.SpatialNodeSnapshot
import org.meshtastic.feature.map.terrain.spatial.SpatialTerrainGeometry
import org.meshtastic.feature.map.terrain.spatial.SpatialZoom
import org.meshtastic.feature.map.terrain.spatial.Vec3
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The terrain table of [SpatialTerrainMap], on a phone: the same ground, pins, chips and comets, built by the same
 * [buildTerrain], drawn by SceneView over ARCore on a surface the user taps. Android XR draws it beside a panel; here
 * it stands on a real desk, and the user walks round it.
 *
 * A pinch sizes it, a one-finger drag turns it, and a later tap moves it.
 */
@Composable
@Suppress("LongParameterList")
fun ArTerrainTable(
    nodes: List<SpatialNodeSnapshot>,
    imagery: List<TerrainImagery>,
    buildings: TerrainBuildingSource?,
    route: TerrainRoute?,
    contourUnits: ContourUnits?,
    buildDispatcher: CoroutineDispatcher,
    demo: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val requestCamera =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) cameraGranted = true else onDismiss()
        }
    var asked by rememberSaveable { mutableStateOf(false) }
    SideEffect {
        if (!cameraGranted && !asked) {
            asked = true
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }
    // Taken once the first position arrives, as on Android XR: a node update must not restart the build.
    val full = remember(nodes.isNotEmpty()) { meshCircleOrNull(nodes) }
    val layer = imagery.firstOrNull()
    val built by
        produceState<TableBuild>(TableBuild.Loading, full) {
            val circle = full
            if (circle == null) {
                value = TableBuild.Empty
                return@produceState
            }
            val http = HttpClient()
            value =
                try {
                    withContext(buildDispatcher) {
                        val raised = buildings?.takeIf { hasBuildings(it) }
                        val shown = SpatialZoom.circle(circle, 1.0, null)
                        val terrain =
                            buildTerrain(shown, nodes, TerrainLayers(layer, raised, contourUnits, route), http, TerrainCache())
                        TableBuild.Ready(tableParts(terrain, nodeMarkers(terrain.geometry, nodes, shown)))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // The message names tile coordinates, which place the mesh to a few kilometers.
                    Logger.w { "AR terrain table failed to build: ${e::class.simpleName}" }
                    TableBuild.Failed
                } finally {
                    http.close()
                }
        }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        val parts = (built as? TableBuild.Ready)?.parts
        if (cameraGranted && parts != null) TableScene(parts, nodes, demo)
        val notice =
            when (val b = built) {
                TableBuild.Loading -> Res.string.xr_terrain_loading
                TableBuild.Empty -> Res.string.xr_terrain_empty
                TableBuild.Failed -> Res.string.xr_terrain_unavailable
                is TableBuild.Ready -> if (b.parts.flat) Res.string.xr_terrain_flat else null
            }
        notice?.let { Notice(stringResource(it), Modifier.align(Alignment.Center)) }
        FilledTonalIconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp),
        ) {
            Icon(MeshtasticIcons.Close, contentDescription = stringResource(Res.string.close))
        }
        layer?.attribution?.takeIf { it.isNotBlank() }?.let { credit ->
            Text(
                credit,
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(8.dp),
            )
        }
    }
}

/** The camera, the reticle until the table is down, then the table on its anchor. */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun TableScene(parts: TableParts, nodes: List<SpatialNodeSnapshot>, demo: Boolean) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    val haptic = LocalHapticFeedback.current
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var yawDegrees by rememberSaveable { mutableFloatStateOf(0f) }
    var tableMeters by rememberSaveable { mutableFloatStateOf(DEFAULT_TABLE_METERS) }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var reticlePhase by remember { mutableStateOf(ReticlePhase.SEARCHING) }
    var cameraReady by remember { mutableStateOf(false) }
    var isTracking by remember { mutableStateOf(false) }
    var surfaceFound by remember { mutableStateOf(false) }
    val placed = anchor != null
    val context = LocalContext.current
    // Two chips tapped in turn make a link to check; a third starts a new one.
    var picked by remember(parts) { mutableStateOf(if (picksLinkAtLaunch(context)) defaultLink(parts) else emptyList()) }
    val chipNodes = remember(parts) { HashMap<Long, Node>() }
    val autoPlace = remember(context) { opensArTableAtLaunch(context) && Log.isLoggable("MeshArPlace", Log.DEBUG) }
    val link = remember(parts, picked) { linkFor(parts, picked) }
    val linkInstance =
        remember(modelLoader, link) { link?.let { modelLoader.createModelInstance(ByteBuffer.wrap(it.glb)) } }
    DisposableEffect(linkInstance) { onDispose { linkInstance?.let { modelLoader.destroyModel(it.model) } } }
    // A long press on the ground stands a relay there that is only being considered, and checks it against every node.
    var relayAt by remember(parts) { mutableStateOf<LatLon?>(null) }
    var ground by remember { mutableStateOf<Node?>(null) }
    val relay = remember(parts, relayAt) { relayAt?.let { relayFor(parts, it) } }
    val relayInstance =
        remember(modelLoader, relay) { relay?.let { modelLoader.createModelInstance(ByteBuffer.wrap(it.glb)) } }
    DisposableEffect(relayInstance) { onDispose { relayInstance?.let { modelLoader.destroyModel(it.model) } } }

    // Made once per build and destroyed with it: Filament owns these, not the garbage collector.
    val instances =
        remember(modelLoader, parts) { parts.models.map { modelLoader.createModelInstance(ByteBuffer.wrap(it)) } }
    val flight = remember(parts) { CometFlight(parts, demo) }
    // One instance per comet: an instance is one set of entities, so two comets cannot share it.
    val cometInstances =
        remember(modelLoader, flight) {
            flight.comets.map { modelLoader.createModelInstance(ByteBuffer.wrap(parts.cometModels.getValue(it.colorKey))) }
        }
    DisposableEffect(instances, cometInstances) {
        onDispose {
            instances.forEach { modelLoader.destroyModel(it.model) }
            cometInstances.forEach { modelLoader.destroyModel(it.model) }
        }
    }
    val heard = remember(parts) { HashMap<Long, Int>() }
    SideEffect {
        for (node in nodes) {
            val before = heard.put(node.id, node.lastHeard)
            if (before != null && before != node.lastHeard) flight.heard(node.id)
        }
    }

    Box(Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it },
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            cameraNode = cameraNode,
            // A rough depth until a plane is found, so the table goes down on the first tap even on a plain desk.
            instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP,
            sceneUnderstanding = SceneUnderstanding(occlusion = false, planeVisualization = !placed),
            onSessionUpdated = { session, frame ->
                latestFrame = frame
                cameraReady = true
                isTracking = frame.camera.trackingState == TrackingState.TRACKING
                if (!surfaceFound) {
                    surfaceFound =
                        session.getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }
                }
                if (placed) flight.step(frame.timestamp)
                // `log.tag.MeshArPlace DEBUG`: put the table down mid-screen as soon as the camera tracks, for captures.
                if (!placed && isTracking && autoPlace && viewport != IntSize.Zero) {
                    placementHit(frame, viewport.width / 2f, viewport.height * 0.6f, instantPlacement = true)?.let {
                        anchor = it.createAnchor()
                    }
                }
            },
            onGestureListener =
            rememberOnGestureListener(
                onSingleTapConfirmed = { event, _ ->
                    val frame = latestFrame ?: return@rememberOnGestureListener
                    val chip = if (placed) chipAt(frame, chipNodes, event.x, event.y, viewport) else null
                    if (chip != null) {
                        picked = if (picked.size == 1 && picked[0] != chip) picked + chip else listOf(chip)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        return@rememberOnGestureListener
                    }
                    placementHit(frame, event.x, event.y, instantPlacement = true)?.let { hit ->
                        anchor?.detach()
                        anchor = hit.createAnchor()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onLongPress = { event, _ ->
                    val frame = latestFrame
                    val table = ground
                    if (frame != null && table != null) {
                        groundAt(frame, table, parts.geometry, event.x, event.y, viewport)?.let {
                            relayAt = it
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                },
                onScroll = { _, _, _, distance -> yawDegrees -= distance.x * YAW_DEGREES_PER_PX },
                onScale = { detector, _, _ ->
                    tableMeters = (tableMeters * detector.scaleFactor).coerceIn(MIN_TABLE_METERS, MAX_TABLE_METERS)
                },
            ),
        ) {
            if (!placed && isTracking && viewport != IntSize.Zero) {
                PlacementReticle(
                    xPx = viewport.width / 2f,
                    yPx = viewport.height / 2f,
                    onHitResultChanged = { hit -> reticlePhase = reticlePhaseFor(hasHit = hit != null) },
                ) {
                    PlacementReticleVisual(materialLoader = materialLoader, phase = reticlePhase)
                }
            }
            anchor?.let { placedAnchor ->
                key(placedAnchor) {
                    AnchorNode(anchor = placedAnchor) {
                        // The model is a unit square, so its scale is the table's width in meters. It is lifted by
                        // the depth of its skirt, so the plate stands on the surface rather than in it.
                        Node(rotation = Rotation(y = yawDegrees), scale = Scale(tableMeters)) {
                            Node(position = Position(y = TABLE_LIFT), apply = { ground = this }) {
                                instances.forEach { ModelNode(modelInstance = it) }
                                parts.chips.forEach { chip ->
                                    BillboardNode(
                                        bitmap = chip.bitmap,
                                        widthMeters = parts.chipWidth * if (chip.id in picked) PICKED_SCALE else 1f,
                                        position = Position(chip.at[0], chip.at[1] + parts.chipLift, chip.at[2]),
                                        cameraPositionProvider = { cameraNode.worldPosition },
                                        apply = { chipNodes[chip.id] = this },
                                    )
                                }
                                if (relay != null && relayInstance != null) {
                                    ModelNode(modelInstance = relayInstance)
                                    BillboardNode(
                                        bitmap = relay.panel,
                                        widthMeters = RELAY_PANEL_WIDTH,
                                        position = Position(relay.above.x, relay.above.y, relay.above.z),
                                        cameraPositionProvider = { cameraNode.worldPosition },
                                    )
                                }
                                if (link != null && linkInstance != null) {
                                    ModelNode(modelInstance = linkInstance)
                                    BillboardNode(
                                        bitmap = link.panel,
                                        widthMeters = PANEL_WIDTH,
                                        position = Position(link.above.x, link.above.y, link.above.z),
                                        cameraPositionProvider = { cameraNode.worldPosition },
                                    )
                                }
                                flight.comets.forEachIndexed { i, comet ->
                                    CometNode(cometInstances[i]) { comet.node = it }
                                }
                            }
                        }
                    }
                }
            }
        }
        val guidance =
            rememberArGuidanceState(
                cameraReady = cameraReady,
                isTracking = isTracking,
                surfaceFound = surfaceFound,
                trackingFailureReason = null,
            )
        if (!placed) ARCoachingOverlay(guidance)
    }
}

/** A comet's model, hidden until it is sent; [onNode] hands over the node the frame loop moves. */
@Composable
private fun NodeScope.CometNode(instance: ModelInstance, onNode: (Node) -> Unit) {
    ModelNode(
        modelInstance = instance,
        isVisible = false,
        apply = { onNode(this) },
    )
}

@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier =
        modifier
            .padding(horizontal = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

private sealed interface TableBuild {
    data object Loading : TableBuild

    data object Empty : TableBuild

    data object Failed : TableBuild

    class Ready(val parts: TableParts) : TableBuild
}

/** A node's name over its pin, drawn as the 2D map draws it. */
internal class ArChip(val id: Long, val bitmap: Bitmap, val at: FloatArray)

/**
 * Everything on the table as glTF, so any renderer that reads it can draw it: the ground with its image, and each
 * vertex-colored layer with a white one, which glTF multiplies away.
 */
internal class TableParts(
    val models: List<ByteArray>,
    val chips: List<ArChip>,
    val chipWidth: Float,
    val chipLift: Float,
    val cometModels: Map<Rgb, ByteArray>,
    val links: Map<Long, CometPath>,
    val routes: List<CometPath>,
    val flat: Boolean,
    val geometry: SpatialTerrainGeometry,
    val markers: Map<Long, NodeMarker>,
)

internal fun tableParts(built: BuiltTerrain, markers: List<NodeMarker>): TableParts {
    val white = whitePng()
    val ground =
        built.image?.let { terrainGlb(built.terrain, it, "image/jpeg") } ?: terrainGlb(built.terrain, white, "image/png")
    val layers =
        listOfNotNull(built.skirt, built.bezel, built.markers, built.buildings, built.contours)
            .map { terrainGlb(it, white, "image/png") }
    val quad = chipMeshData()
    // Wider than on Android XR, where the table is an arm's length away: a phone looks at it from further off.
    val chipWidth = quad.positions[3] * 2 * PHONE_CHIP_SCALE
    val chips =
        built.chips.mapNotNull { chip ->
            // A chip stands straight over its marker's top: that is how it is told which node it names.
            val marker = markers.firstOrNull { it.top.x == chip.at.x && it.top.z == chip.at.z } ?: return@mapNotNull null
            val png = ChipImage.draw(chip.name, chip.color)
            ArChip(marker.node.id, BitmapFactory.decodeByteArray(png, 0, png.size), floatArrayOf(chip.at.x, chip.at.y, chip.at.z))
        }
    val paths = built.links.values.take(MAX_LINK_COMETS) + built.routes
    val cometModels = paths.map { it.color }.distinct().associateWith { terrainGlb(cometMeshData(it), white, "image/png") }
    return TableParts(
        models = listOf(ground) + layers,
        chips = chips,
        chipWidth = chipWidth,
        chipLift = quad.positions[7] * (PHONE_CHIP_SCALE - 1),
        cometModels = cometModels,
        links = built.links.entries.take(MAX_LINK_COMETS).associate { it.key to it.value },
        routes = built.routes,
        flat = built.flat,
        geometry = built.geometry,
        markers = markers.associateBy { it.node.id },
    )
}

/** One white pixel: the image a vertex-colored layer is drawn with, since [terrainGlb] always drapes one. */
private fun whitePng(): ByteArray {
    val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        out.toByteArray()
    }
}

/** One comet on the phone's table: [CometPath] says where its head is, the frame loop moves [node] there. */
private class ArComet(val path: CometPath) {
    val colorKey: Rgb = path.color
    var node: Node? = null
    private var elapsed = IDLE

    val isFlying: Boolean
        get() = elapsed >= 0

    fun send() {
        if (!isFlying) elapsed = 0.0
    }

    fun step(dt: Double) {
        val n = node ?: return
        if (!isFlying) return
        val pose = path.at(elapsed)
        elapsed += dt
        if (pose == null) {
            elapsed = IDLE
            n.isVisible = false
            return
        }
        n.position = Position(pose.position.x, pose.position.y, pose.position.z)
        n.quaternion = towards(pose.direction.x, pose.direction.y, pose.direction.z)
        n.isVisible = true
    }

    private companion object {
        const val IDLE = -1.0
    }
}

/**
 * The comets of [TerrainComets], timed by ARCore's frames: one flies in along a node's link each time the radio hears
 * it, a trace route's pair take turns, and with [demo] each link sends one in turn, as for made-up nodes.
 */
private class CometFlight(parts: TableParts, private val demo: Boolean) {
    private val enabled = ValueAnimator.areAnimatorsEnabled()
    private val links = parts.links.mapValues { ArComet(it.value) }
    private val route = parts.routes.map(::ArComet)
    val comets: List<ArComet> = if (enabled) links.values + route else emptyList()
    private var last = 0L
    private var resting = 0.0
    private var next = 0
    private var demoClock = 0.0
    private var demoNext = 0

    fun heard(node: Long) {
        if (enabled) links[node]?.send()
    }

    fun step(timestampNanos: Long) {
        if (!enabled) return
        val dt = if (last == 0L) 0.0 else ((timestampNanos - last) / NANOS_PER_SECOND).coerceIn(0.0, MAX_STEP)
        last = timestampNanos
        comets.forEach { it.step(dt) }
        if (route.isNotEmpty() && route.none { it.isFlying }) {
            resting += dt
            if (next > 0 || resting >= ROUTE_REST) {
                route[next].send()
                next = (next + 1) % route.size
                resting = 0.0
            }
        }
        if (demo && links.isNotEmpty()) {
            demoClock += dt
            if (demoClock >= DEMO_GAP) {
                demoClock = 0.0
                links.values.elementAt(demoNext++ % links.size).send()
            }
        }
    }
}

/** The rotation that turns +z toward (x, y, z), which is the way a comet's mesh points. */
private fun towards(x: Float, y: Float, z: Float): Quaternion {
    val length = sqrt(x * x + y * y + z * z)
    if (length < 1e-6f) return Quaternion()
    val bx = x / length
    val by = y / length
    val bz = z / length
    // Half-way between +z and b: axis +z × b, angle from their dot product.
    val w = 1f + bz
    if (w < 1e-6f) return Quaternion(0f, 1f, 0f, 0f)
    val q = Quaternion(-by, bx, 0f, w)
    val n = sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
    return Quaternion(q.x / n, q.y / n, q.z / n, q.w / n)
}

/** The mesh's circle, as Android XR takes it, or null with no node placed. */
private fun meshCircleOrNull(nodes: List<SpatialNodeSnapshot>) =
    if (nodes.isEmpty()) null else SpatialMapArea.meshCircle(withLocalAnchor(nodes))

/**
 * For checks on a phone: on a debuggable build, `adb shell setprop log.tag.MeshArTable DEBUG` opens the table at
 * launch, with the made-up nodes of `MeshXrPeaks` or `MeshXrCity` when one of those is set too.
 */
fun opensArTableAtLaunch(context: Context): Boolean =
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && Log.isLoggable("MeshArTable", Log.DEBUG)

/** A link being checked: its rod on the ground, its side view over the middle. */
private class TableLink(val glb: ByteArray, val panel: Bitmap, val above: Vec3)

private fun linkFor(parts: TableParts, picked: List<Long>): TableLink? {
    if (picked.size != 2) return null
    val a = parts.markers[picked[0]] ?: return null
    val b = parts.markers[picked[1]] ?: return null
    val profile =
        linkProfile(parts.geometry, a.node.coordinate, b.node.coordinate, mastOf(a), mastOf(b), DEFAULT_FREQUENCY_MHZ)
    val glb = terrainGlb(linkMeshData(a.top, b.top, profile), whitePng(), "image/png")
    val panel = LinkPanel.draw(a.node.shortName, b.node.shortName, profile)
    val above = Vec3((a.top.x + b.top.x) / 2, max(a.top.y, b.top.y) + PANEL_LIFT, (a.top.z + b.top.z) / 2)
    return TableLink(glb, panel, above)
}

/** A relay being considered: its mast and links, and its card above it. */
private class TableRelay(val glb: ByteArray, val panel: Bitmap, val above: Vec3)

private fun relayFor(parts: TableParts, at: LatLon): TableRelay {
    val plan = relayPlan(parts.geometry, parts.markers.values, at, RELAY_MAST_METERS, DEFAULT_FREQUENCY_MHZ, ::mastOf)
    val glb = terrainGlb(relayMeshData(plan), whitePng(), "image/png")
    val panel = RelayPanel.draw(plan, RELAY_MAST_METERS, DEFAULT_FREQUENCY_MHZ)
    return TableRelay(glb, panel, Vec3(plan.top.x, plan.top.y + RELAY_PANEL_LIFT, plan.top.z))
}

/**
 * Where a tap's ray meets the ground on the table, as a coordinate: the ray is brought into the table's own frame and
 * walked until it goes under the relief, inside the disk.
 */
@Suppress("ReturnCount")
private fun groundAt(frame: Frame, table: Node, geometry: SpatialTerrainGeometry, x: Float, y: Float, viewport: IntSize): LatLon? {
    if (viewport == IntSize.Zero) return null
    val view = FloatArray(16)
    val projection = FloatArray(16)
    val viewProjection = FloatArray(16)
    val inverse = FloatArray(16)
    frame.camera.getViewMatrix(view, 0)
    frame.camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
    android.opengl.Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
    if (!android.opengl.Matrix.invertM(inverse, 0, viewProjection, 0)) return null
    val ndcX = x / viewport.width * 2f - 1f
    val ndcY = 1f - y / viewport.height * 2f
    fun unproject(z: Float): Float3? {
        val out = FloatArray(4)
        android.opengl.Matrix.multiplyMV(out, 0, inverse, 0, floatArrayOf(ndcX, ndcY, z, 1f), 0)
        if (out[3] == 0f) return null
        val world = Float4(out[0] / out[3], out[1] / out[3], out[2] / out[3], 1f)
        val local = table.worldToLocal * world
        return Float3(local.x, local.y, local.z)
    }
    val near = unproject(-1f) ?: return null
    val far = unproject(1f) ?: return null
    val radius = geometry.transform.diskRadius
    var before = near
    for (i in 1..RAY_STEPS) {
        val t = i.toFloat() / RAY_STEPS
        // The far plane is 100 m off; a table is within a couple of meters, so the walk covers the first few.
        val k = t * t * RAY_REACH
        val p = Float3(near.x + (far.x - near.x) * k, near.y + (far.y - near.y) * k, near.z + (far.z - near.z) * k)
        if (p.x * p.x + p.z * p.z <= radius * radius) {
            val coordinate = geometry.transform.coordinate(p.x, p.z)
            if (p.y <= geometry.groundHeight(coordinate)) {
                val mid = Float3((p.x + before.x) / 2, 0f, (p.z + before.z) / 2)
                return geometry.transform.coordinate(mid.x, mid.z)
            }
        }
        before = p
    }
    return null
}

/** Towers and routers stand higher than a radio in a pocket; the snapshot carries no antenna height of its own. */
private fun mastOf(marker: NodeMarker): Double = if (marker.node.isInfrastructure) TOWER_MAST_METERS else MAST_METERS

/** The connected radio and the node farthest from it: the link the debug switch below starts with. */
private fun defaultLink(parts: TableParts): List<Long> {
    val own = parts.markers.values.firstOrNull { it.node.isConnectedRadio } ?: return emptyList()
    val far =
        parts.markers.values
            .filter { it !== own }
            .maxByOrNull { (it.foot.x - own.foot.x).let { d -> d * d } + (it.foot.z - own.foot.z).let { d -> d * d } }
            ?: return emptyList()
    return listOf(own.node.id, far.node.id)
}

/**
 * The chip under a tap, by where each chip is on screen this frame: a billboard is a few millimeters of quad, too thin
 * a target for a ray, so the nearest within a finger's reach wins.
 */
private fun chipAt(frame: Frame, chips: Map<Long, Node>, x: Float, y: Float, viewport: IntSize): Long? {
    if (viewport == IntSize.Zero) return null
    val view = FloatArray(16)
    val projection = FloatArray(16)
    val viewProjection = FloatArray(16)
    frame.camera.getViewMatrix(view, 0)
    frame.camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
    android.opengl.Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
    val clip = FloatArray(4)
    var best: Long? = null
    var bestDistance = TAP_REACH_PX * TAP_REACH_PX
    for ((id, node) in chips) {
        val p = node.worldPosition
        android.opengl.Matrix.multiplyMV(clip, 0, viewProjection, 0, floatArrayOf(p.x, p.y, p.z, 1f), 0)
        if (clip[3] <= 0f) continue
        val sx = (clip[0] / clip[3] + 1f) / 2f * viewport.width
        val sy = (1f - clip[1] / clip[3]) / 2f * viewport.height
        val d = (sx - x) * (sx - x) + (sy - y) * (sy - y)
        if (d < bestDistance) {
            bestDistance = d
            best = id
        }
    }
    return best
}

/** `log.tag.MeshArLink DEBUG`, on top of the table hook, opens with a link already checked. */
private fun picksLinkAtLaunch(context: Context): Boolean =
    opensArTableAtLaunch(context) && Log.isLoggable("MeshArLink", Log.DEBUG)

/** US 915 MHz, until the radio's own LoRa region is passed in. */
private const val DEFAULT_FREQUENCY_MHZ = 915.0
private const val MAST_METERS = 2.0
private const val TOWER_MAST_METERS = 10.0
private const val PANEL_WIDTH = 0.42f
private const val PANEL_LIFT = 0.16f
private const val RELAY_MAST_METERS = 10.0
private const val RELAY_PANEL_WIDTH = 0.34f
private const val RELAY_PANEL_LIFT = 0.14f
private const val RAY_STEPS = 600
private const val RAY_REACH = 0.05f
private const val PICKED_SCALE = 1.35f
private const val TAP_REACH_PX = 120f
private const val DEFAULT_TABLE_METERS = 0.5f
private const val MIN_TABLE_METERS = 0.15f
private const val MAX_TABLE_METERS = 2f
private const val YAW_DEGREES_PER_PX = 0.25f
private const val TABLE_LIFT = 0.02f
private const val PHONE_CHIP_SCALE = 1.8f
private const val MAX_LINK_COMETS = 24
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val MAX_STEP = 0.1
private const val ROUTE_REST = 1.5
private const val DEMO_GAP = 1.2
