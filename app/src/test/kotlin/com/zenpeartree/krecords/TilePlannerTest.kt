package com.zenpeartree.krecords

import org.junit.Assert.assertEquals
import org.junit.Test

class TilePlannerTest {
    private val planner = TilePlanner()

    @Test
    fun prioritizesCurrentTileBeforeFartherTiles() {
        val center = GeoPoint(38.7154, -8.8565)
        val currentTile = planner.tileIdFor(center)
        val nearbyTiles = planner.tilesWithinRadius(center, 5.0)

        val prioritized = planner.prioritizeTiles(center, nearbyTiles)

        assertEquals(currentTile, prioritized.first())
    }
}
