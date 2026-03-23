package com.zenpeartree.krecords

class RideMatcher {
    private var activeEffort: ActiveEffort? = null
    private var recentFinishSegmentId: Long? = null
    private var recentFinishAtMs: Long = 0L

    fun reset() {
        activeEffort = null
        recentFinishSegmentId = null
        recentFinishAtMs = 0L
    }

    fun process(sample: LocationSample, candidates: List<SegmentRecord>): MatchResult {
        val active = activeEffort
        if (active != null) {
            return updateActive(active, sample)
        }

        val candidate = candidates
            .asSequence()
            .filter { it.points.size >= 2 }
            .filterNot { it.id == recentFinishSegmentId && sample.timestampMs - recentFinishAtMs < RESTART_COOLDOWN_MS }
            .mapNotNull { segment ->
                val startDistance = haversineMeters(segment.start, sample.point)
                if (startDistance > START_RADIUS_METERS) return@mapNotNull null
                val heading = sample.heading
                if (heading != null) {
                    val segmentHeading = bearingDegrees(segment.points.first(), segment.points[minOf(3, segment.points.lastIndex)])
                    if (headingDeltaDegrees(segmentHeading, heading) > MAX_HEADING_DELTA) return@mapNotNull null
                }
                startDistance to segment
            }
            .minByOrNull { it.first }
            ?.second
            ?: return MatchResult.None

        activeEffort = ActiveEffort(
            segment = candidate,
            startedAtMs = sample.timestampMs,
            furthestIndex = 0,
            previousBestSeconds = candidate.bestElapsedTimeSeconds ?: candidate.stravaPrElapsedTimeSeconds,
            lastSeenAtMs = sample.timestampMs,
        )
        return MatchResult.Started(candidate.name)
    }

    private fun updateActive(active: ActiveEffort, sample: LocationSample): MatchResult {
        val nearestIndex = nearestPointIndex(active.segment.points, sample.point)
        if (nearestIndex == -1) {
            if (sample.timestampMs - active.lastSeenAtMs > LOST_SIGNAL_CANCEL_MS) {
                activeEffort = null
            }
            return MatchResult.None
        }

        active.lastSeenAtMs = sample.timestampMs
        if (nearestIndex > active.furthestIndex) {
            active.furthestIndex = nearestIndex
        }

        val distanceToFinish = haversineMeters(active.segment.end, sample.point)
        val nearFinish = distanceToFinish <= FINISH_RADIUS_METERS
        val progressedFarEnough = active.furthestIndex >= active.segment.points.lastIndex - 2
        if (!nearFinish || !progressedFarEnough) {
            return MatchResult.None
        }

        val elapsedSeconds = ((sample.timestampMs - active.startedAtMs) / 1000L).toInt().coerceAtLeast(1)
        val previousBest = active.previousBestSeconds
        activeEffort = null
        recentFinishSegmentId = active.segment.id
        recentFinishAtMs = sample.timestampMs
        return MatchResult.Finished(
            segmentId = active.segment.id,
            segmentName = active.segment.name,
            elapsedSeconds = elapsedSeconds,
            previousBestSeconds = previousBest,
            isNewPr = previousBest == null || elapsedSeconds < previousBest,
        )
    }

    private fun nearestPointIndex(points: List<GeoPoint>, point: GeoPoint): Int {
        var bestIndex = -1
        var bestDistance = Double.MAX_VALUE
        points.forEachIndexed { index, candidate ->
            val distance = haversineMeters(candidate, point)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return if (bestDistance <= PATH_LOCK_RADIUS_METERS) bestIndex else -1
    }

    private data class ActiveEffort(
        val segment: SegmentRecord,
        val startedAtMs: Long,
        var furthestIndex: Int,
        val previousBestSeconds: Int?,
        var lastSeenAtMs: Long,
    )

    companion object {
        private const val START_RADIUS_METERS = 60.0
        private const val FINISH_RADIUS_METERS = 45.0
        private const val PATH_LOCK_RADIUS_METERS = 80.0
        private const val MAX_HEADING_DELTA = 80.0
        private const val LOST_SIGNAL_CANCEL_MS = 90_000L
        private const val RESTART_COOLDOWN_MS = 60_000L
    }
}
