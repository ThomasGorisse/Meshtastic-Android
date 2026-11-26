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
@file:Suppress("MagicNumber")

package org.meshtastic.feature.xr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import org.meshtastic.feature.map.terrain.spatial.LatLon
import org.meshtastic.feature.map.terrain.spatial.SpatialTerrainGeometry
import org.meshtastic.feature.map.terrain.spatial.Vec3
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** How much of the first Fresnel zone a link keeps free: the 60% rule of thumb, then any at all. */
internal enum class LinkVerdict {
    Clear,
    Marginal,
    Blocked,
}

/**
 * One step along a link: the ground under it raised by the Earth's bulge, the straight line between the antennas, and
 * the first Fresnel zone's radius there, all in meters.
 */
internal class ProfileSample(val t: Double, val ground: Double, val line: Double, val radius: Double) {
    val clearance: Double
        get() = line - ground

    /** The clearance in zone radii: 1 is the whole zone free, 0 the line grazing the ground, below 0 the line in it. */
    val fraction: Double
        get() = if (radius <= 0) 1.0 else clearance / radius

    val verdict: LinkVerdict
        get() =
            when {
                fraction >= CLEAR_FRACTION -> LinkVerdict.Clear
                fraction >= 0 -> LinkVerdict.Marginal
                else -> LinkVerdict.Blocked
            }
}

/** The ground between two antennas as a radio link sees it. */
internal class LinkProfile(val distanceMeters: Double, val frequencyMHz: Double, val samples: List<ProfileSample>) {
    /** The tightest point between the ends: the antennas themselves always stand clear. */
    val worst: ProfileSample = samples.drop(1).dropLast(1).minByOrNull { it.fraction } ?: samples.first()

    val verdict: LinkVerdict
        get() = worst.verdict
}

/**
 * The link from [a] to [b] over [geometry]'s ground: the line between antennas [mastA] and [mastB] meters above it,
 * checked against the ground plus the Earth's bulge (k = 4/3, the usual allowance for radio bending) and the first
 * Fresnel zone at [frequencyMHz].
 */
@Suppress("LongParameterList")
internal fun linkProfile(
    geometry: SpatialTerrainGeometry,
    a: LatLon,
    b: LatLon,
    mastA: Double,
    mastB: Double,
    frequencyMHz: Double,
    count: Int = PROFILE_SAMPLES,
): LinkProfile {
    val transform = geometry.transform
    val frame = transform.frame
    val pa = frame.localPosition(a, 0.0)
    val pb = frame.localPosition(b, 0.0)
    val dx = (pb.x - pa.x).toDouble()
    val dz = (pb.z - pa.z).toDouble()
    val distance = sqrt(dx * dx + dz * dz)
    fun groundAt(t: Double): Double {
        val here = LatLon(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
        return transform.altitude(geometry.groundHeight(here))
    }
    val start = groundAt(0.0) + mastA
    val end = groundAt(1.0) + mastB
    val wavelength = SPEED_OF_LIGHT / (frequencyMHz * 1e6)
    val samples =
        (0..count).map { i ->
            val t = i.toDouble() / count
            val d1 = distance * t
            val d2 = distance - d1
            val bulge = d1 * d2 / (2 * EFFECTIVE_EARTH_RADIUS)
            val radius = if (distance > 0) sqrt(wavelength * d1 * d2 / distance) else 0.0
            ProfileSample(t, groundAt(t) + bulge, start + (end - start) * t, radius)
        }
    return LinkProfile(distance, frequencyMHz, samples)
}

/**
 * The link on the table: a rod from one marker's top to the other's, each stretch in its verdict's color, so where the
 * ground gets in the way shows on the ground itself.
 */
internal fun linkMeshData(from: Vec3, to: Vec3, profile: LinkProfile): TerrainMeshData {
    val acc = MeshAccumulator()
    acc.link(from, to, profile)
    return acc.build()
}

internal fun MeshAccumulator.link(from: Vec3, to: Vec3, profile: LinkProfile) {
    val acc = this
    val samples = profile.samples
    for (i in 0 until samples.size - 1) {
        val t0 = samples[i].t.toFloat()
        val t1 = samples[i + 1].t.toFloat()
        val p0 = Vec3(from.x + (to.x - from.x) * t0, from.y + (to.y - from.y) * t0, from.z + (to.z - from.z) * t0)
        val p1 = Vec3(from.x + (to.x - from.x) * t1, from.y + (to.y - from.y) * t1, from.z + (to.z - from.z) * t1)
        val worse = if (samples[i].fraction < samples[i + 1].fraction) samples[i] else samples[i + 1]
        acc.rod(p0, p1, LINK_HALF, verdictColor(worse.verdict))
    }
}

/** A relay that is only being considered: where it would stand, and what each node would make of it. */
internal class RelayPlan(val foot: Vec3, val top: Vec3, val links: List<Pair<NodeMarker, LinkProfile>>) {
    val seen: Int
        get() = links.count { it.second.verdict != LinkVerdict.Blocked }
}

/**
 * A relay [mastMeters] up at [at], checked against every marker on the table: the question a planner asks before
 * carrying one up a hill.
 */
internal fun relayPlan(
    geometry: SpatialTerrainGeometry,
    markers: Collection<NodeMarker>,
    at: LatLon,
    mastMeters: Double,
    frequencyMHz: Double,
    mastOf: (NodeMarker) -> Double,
): RelayPlan {
    val foot = geometry.surfacePosition(at)
    val top = Vec3(foot.x, foot.y + RELAY_HEIGHT, foot.z)
    val links = markers.map { it to linkProfile(geometry, at, it.node.coordinate, mastMeters, mastOf(it), frequencyMHz) }
    return RelayPlan(foot, top, links)
}

/** The relay's mast in white, then a link to each node in its verdict's colors. */
internal fun relayMeshData(plan: RelayPlan): TerrainMeshData {
    val acc = MeshAccumulator()
    val white = Rgb(0.96f, 0.97f, 1f)
    acc.rod(plan.foot, plan.top, RELAY_HALF, white)
    acc.sphere(plan.top.x, plan.top.y, plan.top.z, RELAY_HALF * 2.4f, white)
    for ((marker, profile) in plan.links) acc.link(plan.top, marker.top, profile)
    return acc.build()
}

/** The relay's card: how many nodes it would reach, then each node with its verdict's dot. */
internal object RelayPanel {
    fun draw(plan: RelayPlan, mastMeters: Double, frequencyMHz: Double): Bitmap {
        val rows = plan.links.sortedBy { it.second.verdict.ordinal }.take(MAX_ROWS)
        val width = 760
        val height = 150 + rows.size * 52 + 24
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6141821.toInt() }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 36f, 36f, paint)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.DEFAULT_BOLD
            textSize = 44f
        }
        canvas.drawText("Relay here sees ${plan.seen} of ${plan.links.size}", 36f, 70f, title)
        val sub = Paint(title).apply {
            typeface = Typeface.DEFAULT
            textSize = 30f
            color = 0xFFC9D1DC.toInt()
        }
        canvas.drawText(String.format(Locale.US, "%.0f m mast · %.0f MHz · long-press to move", mastMeters, frequencyMHz), 36f, 114f, sub)
        val name = Paint(sub).apply {
            textSize = 34f
            color = 0xFFFFFFFF.toInt()
        }
        rows.forEachIndexed { i, (marker, profile) ->
            val y = 150f + i * 52f + 34f
            paint.color = verdictColor(profile.verdict).toArgb()
            canvas.drawCircle(56f, y - 11f, 13f, paint)
            canvas.drawText(marker.node.shortName.ifBlank { "!" + marker.node.id.toString(16) }, 88f, y, name)
            canvas.drawText(String.format(Locale.US, "%.1f km", profile.distanceMeters / 1000), width - 200f, y, sub)
        }
        return bitmap
    }

    private const val MAX_ROWS = 8
}

internal fun verdictColor(verdict: LinkVerdict): Rgb = when (verdict) {
    LinkVerdict.Clear -> Rgb(0.30f, 0.85f, 0.45f)
    LinkVerdict.Marginal -> Rgb(0.98f, 0.75f, 0.20f)
    LinkVerdict.Blocked -> Rgb(0.95f, 0.30f, 0.28f)
}

/**
 * The link's side view, as a planner draws it: the ground, the line, the first Fresnel zone around it and where the
 * ground breaks into it, under the two names, the distance and the verdict. Height is stretched to fit, as such
 * profiles always are.
 */
internal object LinkPanel {
    const val WIDTH = 960
    const val HEIGHT = 480

    @Suppress("LongMethod")
    fun draw(fromName: String, toName: String, profile: LinkProfile): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xE6141821.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), 36f, 36f, paint)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.DEFAULT_BOLD
            textSize = 44f
        }
        canvas.drawText("$fromName  →  $toName", 36f, 70f, text)
        val sub = Paint(text).apply {
            typeface = Typeface.DEFAULT
            textSize = 30f
            color = 0xFFC9D1DC.toInt()
        }
        val km = profile.distanceMeters / 1000
        canvas.drawText(
            String.format(Locale.US, "%.1f km · %.0f MHz · antennas 2 m up", km, profile.frequencyMHz),
            36f,
            112f,
            sub,
        )
        val verdict = profile.verdict
        val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = verdictColor(verdict).toArgb() }
        val label =
            when (verdict) {
                LinkVerdict.Clear -> "Clear"
                LinkVerdict.Marginal -> "Fresnel zone partly blocked"
                LinkVerdict.Blocked -> "No line of sight"
            }
        val badgeText = Paint(text).apply {
            textSize = 30f
            color = 0xFF111111.toInt()
        }
        val badgeWidth = badgeText.measureText(label) + 40f
        canvas.drawRoundRect(RectF(WIDTH - 36f - badgeWidth, 40f, WIDTH - 36f, 88f), 24f, 24f, badge)
        canvas.drawText(label, WIDTH - 16f - badgeWidth, 75f, badgeText)

        // The chart: meters up the side, the link along the bottom.
        val left = 36f
        val right = WIDTH - 36f
        val top = 140f
        val bottom = HEIGHT - 36f
        val samples = profile.samples
        val low = samples.minOf { min(it.ground, it.line - it.radius) }
        val high = samples.maxOf { max(it.ground, it.line + it.radius) }
        val span = (high - low).coerceAtLeast(1.0)
        fun x(t: Double) = (left + (right - left) * t).toFloat()
        fun y(meters: Double) = (bottom - (bottom - top) * (meters - low) / span).toFloat()

        val zone = Path()
        samples.forEachIndexed { i, s -> if (i == 0) zone.moveTo(x(s.t), y(s.line + s.radius)) else zone.lineTo(x(s.t), y(s.line + s.radius)) }
        samples.asReversed().forEach { s -> zone.lineTo(x(s.t), y(s.line - s.radius)) }
        zone.close()
        paint.color = 0x553FA9F5
        canvas.drawPath(zone, paint)

        val ground = Path()
        ground.moveTo(x(0.0), bottom)
        samples.forEach { ground.lineTo(x(it.t), y(it.ground)) }
        ground.lineTo(x(1.0), bottom)
        ground.close()
        paint.color = 0xFF6B5A43.toInt()
        canvas.drawPath(ground, paint)

        // Where the ground reaches into the zone, in the verdict's color, so the culprit stands out.
        val hit = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 8f }
        for (i in 0 until samples.size - 1) {
            val s = samples[i]
            if (s.verdict == LinkVerdict.Clear) continue
            hit.color = verdictColor(s.verdict).toArgb()
            canvas.drawLine(x(s.t), y(s.ground), x(samples[i + 1].t), y(samples[i + 1].ground), hit)
        }

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            strokeWidth = 5f
        }
        canvas.drawLine(x(0.0), y(samples.first().line), x(1.0), y(samples.last().line), line)
        return bitmap
    }
}

private fun Rgb.toArgb(): Int =
    (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()

/** The share of the first zone a link should keep free. */
private const val CLEAR_FRACTION = 0.6
private const val SPEED_OF_LIGHT = 299_792_458.0

/** The Earth's radius with radio bending allowed for: 4/3 of 6,371 km. */
private const val EFFECTIVE_EARTH_RADIUS = 6_371_000.0 * 4 / 3
private const val PROFILE_SAMPLES = 96
private const val LINK_HALF = 0.0022f
private const val RELAY_HALF = 0.003f

/** As tall as a marker's tower, in model units: a relay is a tower that is not there yet. */
private const val RELAY_HEIGHT = 0.05f
