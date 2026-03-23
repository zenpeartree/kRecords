package com.zenpeartree.krecords

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SegmentDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE segments (
                segment_id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                activity_type TEXT NOT NULL,
                distance_m REAL NOT NULL,
                start_lat REAL NOT NULL,
                start_lng REAL NOT NULL,
                end_lat REAL NOT NULL,
                end_lng REAL NOT NULL,
                bbox_min_lat REAL NOT NULL,
                bbox_min_lng REAL NOT NULL,
                bbox_max_lat REAL NOT NULL,
                bbox_max_lng REAL NOT NULL,
                polyline TEXT NOT NULL,
                best_elapsed_time_s INTEGER,
                strava_pr_elapsed_time_s INTEGER,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE tiles (
                tile_id TEXT PRIMARY KEY,
                min_lat REAL NOT NULL,
                min_lng REAL NOT NULL,
                max_lat REAL NOT NULL,
                max_lng REAL NOT NULL,
                fetched_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE tile_segments (
                tile_id TEXT NOT NULL,
                segment_id INTEGER NOT NULL,
                PRIMARY KEY (tile_id, segment_id)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS tile_segments")
        db.execSQL("DROP TABLE IF EXISTS tiles")
        db.execSQL("DROP TABLE IF EXISTS segments")
        onCreate(db)
    }

    @Synchronized
    fun clearAll() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("tile_segments", null, null)
            writableDatabase.delete("tiles", null, null)
            writableDatabase.delete("segments", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    fun upsertSegments(segments: Collection<SegmentRecord>, planner: TilePlanner) {
        if (segments.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            segments.forEach { segment ->
                db.insertWithOnConflict("segments", null, ContentValues().apply {
                    put("segment_id", segment.id)
                    put("name", segment.name)
                    put("activity_type", segment.activityType)
                    put("distance_m", segment.distanceMeters)
                    put("start_lat", segment.start.lat)
                    put("start_lng", segment.start.lng)
                    put("end_lat", segment.end.lat)
                    put("end_lng", segment.end.lng)
                    put("bbox_min_lat", segment.bounds.minLat)
                    put("bbox_min_lng", segment.bounds.minLng)
                    put("bbox_max_lat", segment.bounds.maxLat)
                    put("bbox_max_lng", segment.bounds.maxLng)
                    put("polyline", segment.polyline)
                    put("best_elapsed_time_s", segment.bestElapsedTimeSeconds)
                    put("strava_pr_elapsed_time_s", segment.stravaPrElapsedTimeSeconds)
                    put("updated_at", segment.updatedAt)
                }, SQLiteDatabase.CONFLICT_REPLACE)

                planner.tilesForBounds(segment.bounds).forEach { tileId ->
                    db.insertWithOnConflict("tile_segments", null, ContentValues().apply {
                        put("tile_id", tileId)
                        put("segment_id", segment.id)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun updateTile(tileId: String, bounds: TileBounds, segmentIds: Collection<Long>, fetchedAt: Long, expiresAt: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("tiles", null, ContentValues().apply {
                put("tile_id", tileId)
                put("min_lat", bounds.minLat)
                put("min_lng", bounds.minLng)
                put("max_lat", bounds.maxLat)
                put("max_lng", bounds.maxLng)
                put("fetched_at", fetchedAt)
                put("expires_at", expiresAt)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("tile_segments", "tile_id = ?", arrayOf(tileId))
            segmentIds.forEach { segmentId ->
                db.insertWithOnConflict("tile_segments", null, ContentValues().apply {
                    put("tile_id", tileId)
                    put("segment_id", segmentId)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun missingOrExpiredTiles(tileIds: Set<String>, nowMs: Long): List<String> {
        if (tileIds.isEmpty()) return emptyList()
        val db = readableDatabase
        val known = mutableMapOf<String, Long>()
        db.rawQuery(
            "SELECT tile_id, expires_at FROM tiles WHERE tile_id IN (${tileIds.joinToString(",") { "?" }})",
            tileIds.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                known[cursor.getString(0)] = cursor.getLong(1)
            }
        }
        return tileIds.filter { tileId -> (known[tileId] ?: 0L) < nowMs }
    }

    @Synchronized
    fun loadSegmentsForTiles(tileIds: Set<String>, limit: Int): List<SegmentRecord> {
        if (tileIds.isEmpty()) return emptyList()
        val args = tileIds.toTypedArray()
        val sql = """
            SELECT DISTINCT
                s.segment_id,
                s.name,
                s.activity_type,
                s.distance_m,
                s.start_lat,
                s.start_lng,
                s.end_lat,
                s.end_lng,
                s.bbox_min_lat,
                s.bbox_min_lng,
                s.bbox_max_lat,
                s.bbox_max_lng,
                s.polyline,
                s.best_elapsed_time_s,
                s.strava_pr_elapsed_time_s,
                s.updated_at
            FROM segments s
            JOIN tile_segments t ON t.segment_id = s.segment_id
            WHERE t.tile_id IN (${tileIds.joinToString(",") { "?" }})
            ORDER BY s.distance_m DESC
            LIMIT $limit
        """.trimIndent()
        return readableDatabase.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val polyline = cursor.getString(12) ?: ""
                    val decoded = decodePolyline(polyline)
                    val start = GeoPoint(cursor.getDouble(4), cursor.getDouble(5))
                    val end = GeoPoint(cursor.getDouble(6), cursor.getDouble(7))
                    add(
                        SegmentRecord(
                            id = cursor.getLong(0),
                            name = cursor.getString(1),
                            activityType = cursor.getString(2),
                            distanceMeters = cursor.getDouble(3),
                            start = start,
                            end = end,
                            bounds = TileBounds(
                                minLat = cursor.getDouble(8),
                                minLng = cursor.getDouble(9),
                                maxLat = cursor.getDouble(10),
                                maxLng = cursor.getDouble(11),
                            ),
                            polyline = polyline,
                            bestElapsedTimeSeconds = cursor.getIntOrNull(13),
                            stravaPrElapsedTimeSeconds = cursor.getIntOrNull(14),
                            updatedAt = cursor.getLong(15),
                            points = decoded.ifEmpty { listOf(start, end) },
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    fun mergeBestEfforts(bestEfforts: Map<Long, Int>) {
        if (bestEfforts.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            bestEfforts.forEach { (segmentId, elapsedSeconds) ->
                db.execSQL(
                    """
                    UPDATE segments
                    SET best_elapsed_time_s = CASE
                        WHEN best_elapsed_time_s IS NULL THEN ?
                        WHEN best_elapsed_time_s > ? THEN ?
                        ELSE best_elapsed_time_s
                    END
                    WHERE segment_id = ?
                    """.trimIndent(),
                    arrayOf<Any>(elapsedSeconds, elapsedSeconds, elapsedSeconds, segmentId),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun updateLocalBest(segmentId: Long, elapsedSeconds: Int) {
        writableDatabase.execSQL(
            """
            UPDATE segments
            SET best_elapsed_time_s = CASE
                WHEN best_elapsed_time_s IS NULL THEN ?
                WHEN best_elapsed_time_s > ? THEN ?
                ELSE best_elapsed_time_s
            END
            WHERE segment_id = ?
            """.trimIndent(),
            arrayOf<Any>(elapsedSeconds, elapsedSeconds, elapsedSeconds, segmentId),
        )
    }

    fun countSegments(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM segments", emptyArray()).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    companion object {
        private const val DB_NAME = "krecords.db"
        private const val DB_VERSION = 1
    }
}

private fun android.database.Cursor.getIntOrNull(columnIndex: Int): Int? {
    return if (isNull(columnIndex)) null else getInt(columnIndex)
}
