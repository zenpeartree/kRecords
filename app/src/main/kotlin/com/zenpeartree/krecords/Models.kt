package com.zenpeartree.krecords

data class GeoPoint(
    val lat: Double,
    val lng: Double,
)

data class TileBounds(
    val minLat: Double,
    val minLng: Double,
    val maxLat: Double,
    val maxLng: Double,
)

data class SegmentRecord(
    val id: Long,
    val name: String,
    val activityType: String,
    val distanceMeters: Double,
    val start: GeoPoint,
    val end: GeoPoint,
    val bounds: TileBounds,
    val polyline: String,
    val bestElapsedTimeSeconds: Int?,
    val stravaPrElapsedTimeSeconds: Int?,
    val updatedAt: Long,
    val points: List<GeoPoint>,
)

data class DashboardSummary(
    val segmentCount: Int,
    val configured: Boolean,
    val authenticated: Boolean,
    val status: String,
    val lastPrName: String?,
    val lastPrSeconds: Int?,
)

data class BackendConfig(
    val backendUrl: String,
    val deviceId: String,
    val deviceSecret: String,
    val activeAuthSessionId: String?,
    val activeAuthUrl: String?,
)

data class AuthSessionStart(
    val sessionId: String,
    val authUrl: String,
    val message: String,
)

data class AuthSessionStatus(
    val status: String,
    val message: String,
    val athleteName: String?,
) {
    val isComplete: Boolean
        get() = status == "complete"
}

data class LocationSample(
    val point: GeoPoint,
    val heading: Double?,
    val timestampMs: Long,
)

sealed interface MatchResult {
    data object None : MatchResult
    data class Started(val segmentName: String) : MatchResult
    data class Finished(
        val segmentId: Long,
        val segmentName: String,
        val elapsedSeconds: Int,
        val previousBestSeconds: Int?,
        val isNewPr: Boolean,
    ) : MatchResult
}

data class SyncSummary(
    val activitiesSeen: Int = 0,
    val segmentsUpdated: Int = 0,
    val tilesHydrated: Int = 0,
    val message: String,
)
