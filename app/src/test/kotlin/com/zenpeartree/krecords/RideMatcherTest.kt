package com.zenpeartree.krecords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideMatcherTest {
    private val matcher = RideMatcher()

    @Test
    fun startsWhenSampleLandsShortlyAfterSegmentStart() {
        val segment = straightSegment(
            id = 1L,
            distanceMeters = 1_000.0,
            pointCount = 21,
        )

        val result = matcher.process(
            sample = LocationSample(
                point = segment.points[2],
                heading = null,
                timestampMs = 1_000L,
            ),
            candidates = listOf(segment),
        )

        assertTrue(result is MatchResult.Started)
    }

    @Test
    fun finishesAfterProgressingAcrossSparseSegmentGeometry() {
        val segment = straightSegment(
            id = 2L,
            distanceMeters = 2_000.0,
            pointCount = 6,
            bestElapsed = 400,
        )

        val startResult = matcher.process(
            sample = LocationSample(
                point = offsetPoint(segment.start, 0.0, 20.0),
                heading = 90.0,
                timestampMs = 1_000L,
            ),
            candidates = listOf(segment),
        )
        assertTrue(startResult is MatchResult.Started)

        matcher.process(
            sample = LocationSample(
                point = segment.points[3],
                heading = 90.0,
                timestampMs = 121_000L,
            ),
            candidates = listOf(segment),
        )

        val finishResult = matcher.process(
            sample = LocationSample(
                point = offsetPoint(segment.end, 0.0, -15.0),
                heading = 90.0,
                timestampMs = 241_000L,
            ),
            candidates = listOf(segment),
        )

        assertTrue(finishResult is MatchResult.Finished)
        finishResult as MatchResult.Finished
        assertEquals(segment.id, finishResult.segmentId)
        assertTrue(finishResult.isNewPr)
    }

    @Test
    fun backdatesStartWhenSegmentLoadsAfterTheRiderHasAlreadyEnteredIt() {
        val segment = straightSegment(
            id = 3L,
            distanceMeters = 100.0,
            pointCount = 11,
        )

        matcher.process(
            sample = LocationSample(
                point = segment.start,
                heading = 90.0,
                timestampMs = 1_000L,
            ),
            candidates = emptyList(),
        )

        val startResult = matcher.process(
            sample = LocationSample(
                point = segment.points[2],
                heading = 90.0,
                timestampMs = 3_000L,
            ),
            candidates = listOf(segment),
        )
        assertTrue(startResult is MatchResult.Started)

        val finishResult = matcher.process(
            sample = LocationSample(
                point = segment.end,
                heading = 90.0,
                timestampMs = 11_000L,
            ),
            candidates = listOf(segment),
        )

        assertTrue(finishResult is MatchResult.Finished)
        finishResult as MatchResult.Finished
        assertEquals(10, finishResult.elapsedSeconds)
    }

    @Test
    fun prAlertDetailIncludesTimeSavedWhenThereIsAPreviousPr() {
        val detail = buildPrAlertDetail(
            MatchResult.Finished(
                segmentId = 1L,
                segmentName = "Test",
                elapsedSeconds = 145,
                previousBestSeconds = 147,
                isNewPr = true,
            )
        )

        assertEquals("2:25, saved 0:02", detail)
    }

    @Test
    fun prAlertDetailShowsOnlyRecordedTimeWhenNoPreviousPrExists() {
        val detail = buildPrAlertDetail(
            MatchResult.Finished(
                segmentId = 1L,
                segmentName = "Test",
                elapsedSeconds = 145,
                previousBestSeconds = null,
                isNewPr = true,
            )
        )

        assertEquals("2:25", detail)
    }

    private fun straightSegment(
        id: Long,
        distanceMeters: Double,
        pointCount: Int,
        bestElapsed: Int? = null,
    ): SegmentRecord {
        val start = GeoPoint(38.7154, -8.8565)
        val end = offsetPoint(start, eastMeters = distanceMeters, northMeters = 0.0)
        val points = buildList {
            for (index in 0 until pointCount) {
                val fraction = index.toDouble() / (pointCount - 1).coerceAtLeast(1)
                add(
                    offsetPoint(
                        start,
                        eastMeters = distanceMeters * fraction,
                        northMeters = 0.0,
                    )
                )
            }
        }
        return SegmentRecord(
            id = id,
            name = "Segment $id",
            activityType = "Ride",
            distanceMeters = distanceMeters,
            start = start,
            end = end,
            bounds = TileBounds(
                minLat = minOf(start.lat, end.lat),
                minLng = minOf(start.lng, end.lng),
                maxLat = maxOf(start.lat, end.lat),
                maxLng = maxOf(start.lng, end.lng),
            ),
            polyline = "",
            bestElapsedTimeSeconds = bestElapsed,
            stravaPrElapsedTimeSeconds = bestElapsed,
            updatedAt = 0L,
            points = points,
        )
    }

    private fun offsetPoint(origin: GeoPoint, eastMeters: Double, northMeters: Double): GeoPoint {
        val lat = origin.lat + northMeters / 111_320.0
        val lng = origin.lng + eastMeters / (111_320.0 * kotlin.math.cos(Math.toRadians(origin.lat)).coerceAtLeast(0.2))
        return GeoPoint(lat, lng)
    }
}
