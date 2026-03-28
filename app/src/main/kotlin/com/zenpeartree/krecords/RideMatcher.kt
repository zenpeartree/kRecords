package com.zenpeartree.krecords

class RideMatcher {
    private var activeEffort: ActiveEffort? = null
    private var lastSample: LocationSample? = null
    private var recentFinishSegmentId: Long? = null
    private var recentFinishAtMs: Long = 0L

    fun reset() {
        activeEffort = null
        lastSample = null
        recentFinishSegmentId = null
        recentFinishAtMs = 0L
    }

    fun process(sample: LocationSample, candidates: List<SegmentRecord>): MatchResult {
        val previousSample = lastSample
        val movementHeading = derivedHeading(previousSample, sample) ?: sample.heading
        val result = activeEffort?.let { updateActive(it, sample) }
            ?: acquireCandidate(sample, candidates, movementHeading, previousSample)
        lastSample = sample
        return result
    }

    private fun acquireCandidate(
        sample: LocationSample,
        candidates: List<SegmentRecord>,
        movementHeading: Double?,
        previousSample: LocationSample?,
    ): MatchResult {
        val candidate = candidates
            .asSequence()
            .filter { it.points.size >= 2 }
            .filterNot { it.id == recentFinishSegmentId && sample.timestampMs - recentFinishAtMs < RESTART_COOLDOWN_MS }
            .mapNotNull { segment ->
                val projection = projectPointToPath(segment.points, sample.point) ?: return@mapNotNull null
                val startDistance = haversineMeters(segment.start, sample.point)
                val nearStart = startDistance <= START_RADIUS_METERS || projection.distanceMeters <= START_PATH_RADIUS_METERS
                if (!nearStart) return@mapNotNull null
                val earlyEnough = projection.progressMeters <= START_PROGRESS_MAX_METERS ||
                    projection.progressFraction <= START_PROGRESS_MAX_FRACTION
                if (!earlyEnough) return@mapNotNull null
                if (projection.progressFraction >= 0.9) return@mapNotNull null

                val headingPenalty = movementHeading?.let { heading ->
                    val segmentHeading = segmentStartHeading(segment)
                    val delta = headingDeltaDegrees(segmentHeading, heading)
                    if (delta > MAX_REJECT_HEADING_DELTA && startDistance > CLOSE_START_OVERRIDE_METERS) {
                        return@mapNotNull null
                    }
                    delta / 4.0
                } ?: 0.0

                val score = minOf(startDistance, projection.distanceMeters) +
                    projection.progressMeters * 0.35 +
                    headingPenalty
                StartCandidate(segment, projection, score)
            }
            .minByOrNull { it.score }
            ?: return MatchResult.None

        val startedAtMs = estimateStartedAtMs(
            previousSample = previousSample,
            currentSample = sample,
            previousProjection = previousSample?.let { projectPointToPath(candidate.segment.points, it.point) },
            currentProjection = candidate.projection,
        )
        activeEffort = ActiveEffort(
            segment = candidate.segment,
            startedAtMs = startedAtMs,
            bestProgressMeters = candidate.projection.progressMeters,
            previousBestSeconds = candidate.segment.bestElapsedTimeSeconds ?: candidate.segment.stravaPrElapsedTimeSeconds,
            lastSeenAtMs = sample.timestampMs,
            lastProgressMeters = candidate.projection.progressMeters,
            lastSampleTimestampMs = sample.timestampMs,
        )
        return MatchResult.Started(candidate.segment.name)
    }

    private fun updateActive(active: ActiveEffort, sample: LocationSample): MatchResult {
        val projection = projectPointToPath(active.segment.points, sample.point)
        if (projection == null || projection.distanceMeters > ACTIVE_PATH_RADIUS_METERS) {
            if (sample.timestampMs - active.lastSeenAtMs > LOST_SIGNAL_CANCEL_MS) {
                activeEffort = null
            }
            return MatchResult.None
        }

        active.lastSeenAtMs = sample.timestampMs
        active.bestProgressMeters = maxOf(active.bestProgressMeters, projection.progressMeters)

        val distanceToFinish = haversineMeters(active.segment.end, sample.point)
        val nearFinish = distanceToFinish <= FINISH_RADIUS_METERS
        val progressedFarEnough = active.bestProgressMeters >= projection.totalLengthMeters - FINISH_PROGRESS_BUFFER_METERS ||
            projection.progressFraction >= FINISH_PROGRESS_FRACTION
        if (!nearFinish || !progressedFarEnough) {
            active.lastProgressMeters = projection.progressMeters
            active.lastSampleTimestampMs = sample.timestampMs
            return MatchResult.None
        }

        val finishedAtMs = estimateFinishedAtMs(active, sample, projection)
        val elapsedSeconds = ((finishedAtMs - active.startedAtMs) / 1000.0).toInt().coerceAtLeast(1)
        val previousBest = active.previousBestSeconds
        activeEffort = null
        recentFinishSegmentId = active.segment.id
        recentFinishAtMs = finishedAtMs
        return MatchResult.Finished(
            segmentId = active.segment.id,
            segmentName = active.segment.name,
            elapsedSeconds = elapsedSeconds,
            previousBestSeconds = previousBest,
            isNewPr = previousBest == null || elapsedSeconds < previousBest,
        )
    }

    private fun estimateStartedAtMs(
        previousSample: LocationSample?,
        currentSample: LocationSample,
        previousProjection: PathProjection?,
        currentProjection: PathProjection,
    ): Long {
        val speedMps = estimatePathSpeedMps(previousSample, currentSample, previousProjection, currentProjection)
            ?: return currentSample.timestampMs
        if (currentProjection.progressMeters < MIN_START_ADJUST_METERS) return currentSample.timestampMs
        val adjustmentMs = ((currentProjection.progressMeters / speedMps) * 1000.0)
            .toLong()
            .coerceAtMost(MAX_START_ADJUST_MS)
        return currentSample.timestampMs - adjustmentMs
    }

    private fun estimateFinishedAtMs(
        active: ActiveEffort,
        currentSample: LocationSample,
        currentProjection: PathProjection,
    ): Long {
        val speedMps = estimateProgressSpeedMps(
            previousProgressMeters = active.lastProgressMeters,
            previousTimestampMs = active.lastSampleTimestampMs,
            currentProgressMeters = currentProjection.progressMeters,
            currentTimestampMs = currentSample.timestampMs,
        ) ?: return currentSample.timestampMs
        val finishRemainderMeters = (currentProjection.totalLengthMeters - currentProjection.progressMeters)
            .coerceAtLeast(0.0)
        if (finishRemainderMeters <= MIN_FINISH_ADJUST_METERS) {
            return currentSample.timestampMs
        }
        val adjustmentMs = ((finishRemainderMeters / speedMps) * 1000.0)
            .toLong()
            .coerceAtMost(MAX_FINISH_ADJUST_MS)
        return currentSample.timestampMs + adjustmentMs
    }

    private fun estimatePathSpeedMps(
        previousSample: LocationSample?,
        currentSample: LocationSample,
        previousProjection: PathProjection?,
        currentProjection: PathProjection,
    ): Double? {
        val pathSpeed = if (
            previousSample != null &&
            previousProjection != null &&
            previousProjection.distanceMeters <= ACTIVE_PATH_RADIUS_METERS * 1.5
        ) {
            estimateProgressSpeedMps(
                previousProgressMeters = previousProjection.progressMeters,
                previousTimestampMs = previousSample.timestampMs,
                currentProgressMeters = currentProjection.progressMeters,
                currentTimestampMs = currentSample.timestampMs,
            )
        } else {
            null
        }
        if (pathSpeed != null) return pathSpeed
        if (previousSample == null) return null
        val deltaMs = currentSample.timestampMs - previousSample.timestampMs
        if (deltaMs <= 0L) return null
        val speedMps = haversineMeters(previousSample.point, currentSample.point) / (deltaMs / 1000.0)
        return speedMps.takeIf { it in MIN_ESTIMATED_SPEED_MPS..MAX_ESTIMATED_SPEED_MPS }
    }

    private fun estimateProgressSpeedMps(
        previousProgressMeters: Double,
        previousTimestampMs: Long,
        currentProgressMeters: Double,
        currentTimestampMs: Long,
    ): Double? {
        val deltaMs = currentTimestampMs - previousTimestampMs
        if (deltaMs <= 0L) return null
        val deltaMeters = currentProgressMeters - previousProgressMeters
        if (deltaMeters <= 0.0) return null
        val speedMps = deltaMeters / (deltaMs / 1000.0)
        return speedMps.takeIf { it in MIN_ESTIMATED_SPEED_MPS..MAX_ESTIMATED_SPEED_MPS }
    }

    private fun segmentStartHeading(segment: SegmentRecord): Double {
        val points = segment.points
        val headingEndIndex = minOf(5, points.lastIndex)
        return bearingDegrees(points.first(), points[headingEndIndex])
    }

    private fun derivedHeading(previous: LocationSample?, current: LocationSample): Double? {
        if (previous == null) return null
        if (current.timestampMs <= previous.timestampMs) return null
        if (haversineMeters(previous.point, current.point) < MIN_HEADING_DISTANCE_METERS) return null
        return bearingDegrees(previous.point, current.point)
    }

    private data class ActiveEffort(
        val segment: SegmentRecord,
        val startedAtMs: Long,
        var bestProgressMeters: Double,
        val previousBestSeconds: Int?,
        var lastSeenAtMs: Long,
        var lastProgressMeters: Double,
        var lastSampleTimestampMs: Long,
    )

    private data class StartCandidate(
        val segment: SegmentRecord,
        val projection: PathProjection,
        val score: Double,
    )

    companion object {
        private const val START_RADIUS_METERS = 70.0
        private const val START_PATH_RADIUS_METERS = 50.0
        private const val CLOSE_START_OVERRIDE_METERS = 25.0
        private const val START_PROGRESS_MAX_METERS = 250.0
        private const val START_PROGRESS_MAX_FRACTION = 0.12
        private const val FINISH_RADIUS_METERS = 55.0
        private const val FINISH_PROGRESS_BUFFER_METERS = 120.0
        private const val FINISH_PROGRESS_FRACTION = 0.97
        private const val ACTIVE_PATH_RADIUS_METERS = 120.0
        private const val MAX_REJECT_HEADING_DELTA = 145.0
        private const val MIN_HEADING_DISTANCE_METERS = 12.0
        private const val MIN_START_ADJUST_METERS = 8.0
        private const val MIN_FINISH_ADJUST_METERS = 3.0
        private const val MAX_START_ADJUST_MS = 8_000L
        private const val MAX_FINISH_ADJUST_MS = 3_000L
        private const val MIN_ESTIMATED_SPEED_MPS = 1.5
        private const val MAX_ESTIMATED_SPEED_MPS = 30.0
        private const val LOST_SIGNAL_CANCEL_MS = 90_000L
        private const val RESTART_COOLDOWN_MS = 60_000L
    }
}
