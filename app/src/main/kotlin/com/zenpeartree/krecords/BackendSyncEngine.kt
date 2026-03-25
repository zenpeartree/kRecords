package com.zenpeartree.krecords

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class BackendSyncEngine(
    private val settings: SettingsStore,
    private val repo: SegmentDatabase,
    private val planner: TilePlanner,
) {
    fun startAuthSession(httpClient: HttpClient): AuthSessionStart? {
        val config = settings.loadBackendConfig() ?: return null
        val response = postJson(
            httpClient,
            "${config.backendUrl}/api/auth/start",
            JSONObject()
                .put("deviceId", config.deviceId)
                .put("deviceSecret", config.deviceSecret),
        )
        if (response.statusCode !in 200..299) {
            settings.recordStatus("Failed to start auth session: ${response.error ?: response.bodyString().ifBlank { response.statusCode.toString() }}")
            return null
        }

        val body = JSONObject(response.bodyString())
        return AuthSessionStart(
            sessionId = body.optString("sessionId"),
            authUrl = body.optString("authUrl"),
            message = body.optString("message", "Scan the QR code on your phone."),
        ).takeIf { it.sessionId.isNotBlank() && it.authUrl.isNotBlank() }
    }

    fun getAuthSessionStatus(httpClient: HttpClient): AuthSessionStatus? {
        val config = settings.loadBackendConfig() ?: return null
        val sessionId = config.activeAuthSessionId ?: return null
        val response = httpClient.requestBlocking(
            HttpRequest(
                method = "GET",
                url = buildUrl(
                    "${config.backendUrl}/api/auth/session",
                    mapOf(
                        "deviceId" to config.deviceId,
                        "deviceSecret" to config.deviceSecret,
                        "sessionId" to sessionId,
                    )
                ),
            )
        )
        if (response.statusCode !in 200..299) {
            return AuthSessionStatus("error", response.error ?: "Failed to poll auth session.", null)
        }

        val body = JSONObject(response.bodyString())
        return AuthSessionStatus(
            status = body.optString("status", "pending"),
            message = body.optString("message", "Waiting for authorization."),
            athleteName = body.optString("athleteName").takeIf { it.isNotBlank() },
        )
    }

    fun syncRecentActivities(httpClient: HttpClient): SyncSummary {
        val config = settings.loadBackendConfig() ?: return SyncSummary(message = "Save the Firebase backend URL first.")
        val response = postJson(
            httpClient,
            "${config.backendUrl}/api/sync/history",
            JSONObject()
                .put("deviceId", config.deviceId)
                .put("deviceSecret", config.deviceSecret),
        )
        if (response.statusCode !in 200..299) {
            return SyncSummary(message = "History sync failed: ${response.error ?: response.bodyString().ifBlank { response.statusCode.toString() }}")
        }

        val body = JSONObject(response.bodyString())
        val segments = parseSegments(body.optJSONArray("segments"))
        repo.upsertSegments(segments, planner)
        body.optString("athleteName").takeIf { it.isNotBlank() }?.let(settings::recordAuthenticated)
        val message = body.optString("message", "Synced recent history.")
        settings.recordHistorySync(message)
        return SyncSummary(
            activitiesSeen = body.optInt("activitiesSeen"),
            segmentsUpdated = body.optInt("segmentsUpdated", segments.size),
            message = message,
        )
    }

    fun hydrateTiles(httpClient: HttpClient, tileIds: List<String>): SyncSummary {
        val config = settings.loadBackendConfig() ?: return SyncSummary(message = "Save the Firebase backend URL first.")
        if (tileIds.isEmpty()) return SyncSummary(message = "Nearby tiles already fresh.")

        val requestedTiles = JSONArray()
        tileIds.forEach { tileId ->
            val bounds = planner.boundsForTile(tileId)
            requestedTiles.put(
                JSONObject()
                    .put("tileId", tileId)
                    .put("bounds", JSONObject()
                        .put("minLat", bounds.minLat)
                        .put("minLng", bounds.minLng)
                        .put("maxLat", bounds.maxLat)
                        .put("maxLng", bounds.maxLng))
            )
        }

        val response = postJson(
            httpClient,
            "${config.backendUrl}/api/sync/tiles",
            JSONObject()
                .put("deviceId", config.deviceId)
                .put("deviceSecret", config.deviceSecret)
                .put("tiles", requestedTiles),
        )
        if (response.statusCode !in 200..299) {
            return SyncSummary(message = "Tile hydration failed: ${response.error ?: response.bodyString().ifBlank { response.statusCode.toString() }}")
        }

        val body = JSONObject(response.bodyString())
        val segments = parseSegments(body.optJSONArray("segments"))
        repo.upsertSegments(segments, planner)

        val tilesArray = body.optJSONArray("tiles") ?: JSONArray()
        for (index in 0 until tilesArray.length()) {
            val tile = tilesArray.optJSONObject(index) ?: continue
            val tileId = tile.optString("tileId")
            if (tileId.isBlank()) continue
            val boundsJson = tile.optJSONObject("bounds")
            val bounds = TileBounds(
                minLat = boundsJson?.optDouble("minLat") ?: planner.boundsForTile(tileId).minLat,
                minLng = boundsJson?.optDouble("minLng") ?: planner.boundsForTile(tileId).minLng,
                maxLat = boundsJson?.optDouble("maxLat") ?: planner.boundsForTile(tileId).maxLat,
                maxLng = boundsJson?.optDouble("maxLng") ?: planner.boundsForTile(tileId).maxLng,
            )
            val segmentIds = mutableListOf<Long>()
            val idsArray = tile.optJSONArray("segmentIds") ?: JSONArray()
            for (idIndex in 0 until idsArray.length()) {
                segmentIds += idsArray.optLong(idIndex)
            }
            repo.updateTile(
                tileId = tileId,
                bounds = bounds,
                segmentIds = segmentIds,
                fetchedAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + TILE_TTL_MS,
            )
        }

        val message = body.optString("message", "Hydrated nearby tiles.")
        settings.recordStatus(message)
        return SyncSummary(
            segmentsUpdated = body.optInt("segmentsUpdated", segments.size),
            tilesHydrated = body.optInt("tilesHydrated", tilesArray.length()),
            message = message,
        )
    }

    private fun parseSegments(array: JSONArray?): List<SegmentRecord> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val segment = array.optJSONObject(index) ?: continue
                val start = segment.optGeoPoint("start") ?: continue
                val end = segment.optGeoPoint("end") ?: continue
                val boundsJson = segment.optJSONObject("bounds") ?: continue
                val polyline = segment.optString("polyline")
                val points = decodePolyline(polyline).ifEmpty { listOf(start, end) }
                add(
                    SegmentRecord(
                        id = segment.optLong("id"),
                        name = segment.optString("name"),
                        activityType = segment.optString("activityType", "Ride"),
                        distanceMeters = segment.optDouble("distanceMeters"),
                        start = start,
                        end = end,
                        bounds = TileBounds(
                            minLat = boundsJson.optDouble("minLat"),
                            minLng = boundsJson.optDouble("minLng"),
                            maxLat = boundsJson.optDouble("maxLat"),
                            maxLng = boundsJson.optDouble("maxLng"),
                        ),
                        polyline = polyline,
                        bestElapsedTimeSeconds = segment.optIntOrNull("bestElapsedTimeSeconds"),
                        stravaPrElapsedTimeSeconds = segment.optIntOrNull("stravaPrElapsedTimeSeconds"),
                        updatedAt = segment.optLong("updatedAt"),
                        points = points,
                    )
                )
            }
        }
    }

    private fun postJson(httpClient: HttpClient, url: String, json: JSONObject): HttpResponse {
        return httpClient.requestBlocking(
            HttpRequest(
                method = "POST",
                url = url,
                headers = mapOf("Content-Type" to "application/json"),
                body = json.toString().toByteArray(Charsets.UTF_8),
            )
        )
    }

    private fun buildUrl(baseUrl: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        return "$baseUrl?$query"
    }

    companion object {
        private const val TILE_TTL_MS = 12 * 60 * 60 * 1000L
    }
}

private fun JSONObject.optGeoPoint(name: String): GeoPoint? {
    val child = optJSONObject(name) ?: return null
    return GeoPoint(
        lat = child.optDouble("lat"),
        lng = child.optDouble("lng"),
    )
}

private fun JSONObject.optIntOrNull(name: String): Int? {
    return if (!has(name) || isNull(name)) null else optInt(name)
}
