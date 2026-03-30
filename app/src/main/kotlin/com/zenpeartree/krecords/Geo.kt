package com.zenpeartree.krecords

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

private const val EARTH_RADIUS_METERS = 6_371_000.0

fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1 - h))
}

fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
    val lat1 = Math.toRadians(from.lat)
    val lat2 = Math.toRadians(to.lat)
    val dLng = Math.toRadians(to.lng - from.lng)
    val y = sin(dLng) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

fun headingDeltaDegrees(a: Double, b: Double): Double {
    val delta = abs((a - b + 540.0) % 360.0 - 180.0)
    return delta
}

data class PathProjection(
    val distanceMeters: Double,
    val progressMeters: Double,
    val progressFraction: Double,
    val totalLengthMeters: Double,
)

fun polylineLengthMeters(points: List<GeoPoint>): Double {
    if (points.size < 2) return 0.0
    var total = 0.0
    for (index in 0 until points.lastIndex) {
        total += haversineMeters(points[index], points[index + 1])
    }
    return total
}

fun projectPointToPath(points: List<GeoPoint>, point: GeoPoint): PathProjection? {
    if (points.isEmpty()) return null
    if (points.size == 1) {
        val distance = haversineMeters(points.first(), point)
        return PathProjection(
            distanceMeters = distance,
            progressMeters = 0.0,
            progressFraction = 0.0,
            totalLengthMeters = 0.0,
        )
    }

    val metersPerDegreeLat = 111_320.0
    val metersPerDegreeLng = 111_320.0 * cos(Math.toRadians(point.lat)).coerceAtLeast(0.2)
    val totalLength = polylineLengthMeters(points)

    var bestDistance = Double.MAX_VALUE
    var bestProgress = 0.0
    var traversed = 0.0

    for (index in 0 until points.lastIndex) {
        val start = points[index]
        val end = points[index + 1]
        val segmentLength = haversineMeters(start, end)
        if (segmentLength <= 0.0) continue

        val ax = (start.lng - point.lng) * metersPerDegreeLng
        val ay = (start.lat - point.lat) * metersPerDegreeLat
        val bx = (end.lng - point.lng) * metersPerDegreeLng
        val by = (end.lat - point.lat) * metersPerDegreeLat
        val abx = bx - ax
        val aby = by - ay
        val lengthSquared = abx * abx + aby * aby
        val t = if (lengthSquared <= 0.0) {
            0.0
        } else {
            ((-ax * abx) + (-ay * aby)) / lengthSquared
        }.coerceIn(0.0, 1.0)

        val projectedX = ax + abx * t
        val projectedY = ay + aby * t
        val distance = sqrt(projectedX * projectedX + projectedY * projectedY)
        if (distance < bestDistance) {
            bestDistance = distance
            bestProgress = traversed + segmentLength * t
        }
        traversed += segmentLength
    }

    return PathProjection(
        distanceMeters = bestDistance,
        progressMeters = bestProgress,
        progressFraction = if (totalLength <= 0.0) 0.0 else (bestProgress / totalLength).coerceIn(0.0, 1.0),
        totalLengthMeters = totalLength,
    )
}

fun decodePolyline(encoded: String): List<GeoPoint> {
    if (encoded.isBlank()) return emptyList()

    val points = mutableListOf<GeoPoint>()
    var index = 0
    var lat = 0
    var lng = 0

    while (index < encoded.length) {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        result = 0
        shift = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        points += GeoPoint(lat / 1e5, lng / 1e5)
    }

    return downsample(points)
}

private fun downsample(points: List<GeoPoint>, targetSize: Int = 192): List<GeoPoint> {
    if (points.size <= targetSize) return points
    val step = (points.size - 1).toDouble() / (targetSize - 1)
    return buildList {
        repeat(targetSize) { index ->
            val sourceIndex = min(points.lastIndex, (index * step).roundToInt())
            add(points[sourceIndex])
        }
    }
}

class TilePlanner(private val zoom: Int = 12) {
    fun tileIdFor(point: GeoPoint): String {
        val x = lonToTileX(point.lng)
        val y = latToTileY(point.lat)
        return "$zoom:$x:$y"
    }

    fun boundsForTile(tileId: String): TileBounds {
        val parts = tileId.split(":")
        val x = parts[1].toInt()
        val y = parts[2].toInt()
        val n = 2.0.pow(zoom.toDouble())
        val minLng = x / n * 360.0 - 180.0
        val maxLng = (x + 1) / n * 360.0 - 180.0
        val minLat = tileYToLat(y + 1)
        val maxLat = tileYToLat(y)
        return TileBounds(minLat, minLng, maxLat, maxLng)
    }

    fun tilesWithinRadius(center: GeoPoint, radiusKm: Double): Set<String> {
        val latRadiusDegrees = radiusKm / 111.0
        val lngRadiusDegrees = radiusKm / (111.0 * cos(Math.toRadians(center.lat)).coerceAtLeast(0.2))
        val minLat = center.lat - latRadiusDegrees
        val maxLat = center.lat + latRadiusDegrees
        val minLng = center.lng - lngRadiusDegrees
        val maxLng = center.lng + lngRadiusDegrees
        return tilesForBounds(TileBounds(minLat, minLng, maxLat, maxLng))
    }

    fun tilesForBounds(bounds: TileBounds): Set<String> {
        val minX = lonToTileX(bounds.minLng)
        val maxX = lonToTileX(bounds.maxLng)
        val minY = latToTileY(bounds.maxLat)
        val maxY = latToTileY(bounds.minLat)
        val ids = linkedSetOf<String>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                ids += "$zoom:$x:$y"
            }
        }
        return ids
    }

    fun prioritizeTiles(center: GeoPoint, tileIds: Collection<String>): List<String> {
        return tileIds.sortedBy { tileId ->
            val bounds = boundsForTile(tileId)
            val tileCenter = GeoPoint(
                lat = (bounds.minLat + bounds.maxLat) / 2.0,
                lng = (bounds.minLng + bounds.maxLng) / 2.0,
            )
            haversineMeters(center, tileCenter)
        }
    }

    private fun lonToTileX(lng: Double): Int {
        val normalized = ((lng + 180.0) / 360.0).coerceIn(0.0, 0.999999)
        return floor(normalized * 2.0.pow(zoom.toDouble())).toInt()
    }

    private fun latToTileY(lat: Double): Int {
        val clamped = lat.coerceIn(-85.0511, 85.0511)
        val latRad = Math.toRadians(clamped)
        val n = 2.0.pow(zoom.toDouble())
        val y = (1.0 - kotlin.math.asinh(tan(latRad)) / Math.PI) / 2.0 * n
        return floor(y.coerceIn(0.0, n - 1)).toInt()
    }

    private fun tileYToLat(y: Int): Double {
        val n = Math.PI - 2.0 * Math.PI * y / 2.0.pow(zoom.toDouble())
        return Math.toDegrees(atan2(sinhCompat(n), 1.0))
    }

    private fun sinhCompat(value: Double): Double = (kotlin.math.exp(value) - kotlin.math.exp(-value)) / 2.0
}
