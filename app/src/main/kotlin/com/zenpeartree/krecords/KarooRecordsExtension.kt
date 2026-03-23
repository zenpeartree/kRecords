package com.zenpeartree.krecords

import android.content.Intent
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SystemNotification
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class KarooRecordsExtension : KarooExtension(EXTENSION_ID, "1") {
    override val types: List<DataTypeImpl> by lazy { listOf(RideStatusField()) }

    private lateinit var karooSystem: KarooSystemService
    private lateinit var settings: SettingsStore
    private lateinit var repo: SegmentDatabase
    private lateinit var planner: TilePlanner
    private lateinit var syncEngine: BackendSyncEngine

    private val executor = Executors.newSingleThreadExecutor()
    private val matcher = RideMatcher()
    private val syncInFlight = AtomicBoolean(false)

    private var rideStateConsumerId: String? = null
    private var locationConsumerId: String? = null
    private var activeSegments: List<SegmentRecord> = emptyList()
    private var lastTileId: String? = null
    private var lastRefreshPoint: GeoPoint? = null
    private var lastHydrationRequestMs: Long = 0L
    private var setupNoticeShown = false

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(this)
        settings = SettingsStore(this)
        repo = SegmentDatabase(this)
        planner = TilePlanner()
        syncEngine = BackendSyncEngine(settings, repo, planner)

        karooSystem.connect { connected ->
            if (connected) {
                subscribeToRideState()
            }
        }
    }

    override fun onDestroy() {
        locationConsumerId?.let(karooSystem::removeConsumer)
        rideStateConsumerId?.let(karooSystem::removeConsumer)
        karooSystem.disconnect()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBonusAction(actionId: String) {
        if (actionId == BONUS_ACTION_OPEN_SETTINGS) {
            openSettings()
        }
    }

    private fun subscribeToRideState() {
        if (rideStateConsumerId != null) return
        rideStateConsumerId = karooSystem.addConsumer<RideState> { state ->
            val riding = state !is RideState.Idle
            if (riding) {
                ensureLocationConsumer()
                scheduleHistorySyncIfNeeded()
                if (!settings.isConfigured() && !setupNoticeShown) {
                    setupNoticeShown = notifyNeedsSetup()
                }
            } else {
                locationConsumerId?.let(karooSystem::removeConsumer)
                locationConsumerId = null
                matcher.reset()
                activeSegments = emptyList()
                lastTileId = null
                lastRefreshPoint = null
                setupNoticeShown = false
            }
        }
    }

    private fun ensureLocationConsumer() {
        if (locationConsumerId != null) return
        locationConsumerId = karooSystem.addConsumer<OnLocationChanged> { event ->
            handleLocation(
                LocationSample(
                    point = GeoPoint(event.lat, event.lng),
                    heading = event.orientation,
                    timestampMs = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun handleLocation(sample: LocationSample) {
        val tileId = planner.tileIdFor(sample.point)
        val movedEnough = lastRefreshPoint?.let { haversineMeters(it, sample.point) >= REFRESH_DISTANCE_METERS } ?: true
        if (tileId != lastTileId || movedEnough) {
            val wantedTiles = planner.tilesWithinRadius(sample.point, FETCH_RADIUS_KM)
            activeSegments = repo.loadSegmentsForTiles(wantedTiles, MAX_ACTIVE_SEGMENTS)
            scheduleNearbyHydration(wantedTiles)
            lastTileId = tileId
            lastRefreshPoint = sample.point
        }

        when (val result = matcher.process(sample, activeSegments)) {
            MatchResult.None -> Unit
            is MatchResult.Started -> Unit
            is MatchResult.Finished -> {
                if (result.isNewPr) {
                    repo.updateLocalBest(result.segmentId, result.elapsedSeconds)
                    settings.recordPr(result.segmentName, result.elapsedSeconds)
                    dispatchPrAlert(result)
                }
            }
        }
    }

    private fun scheduleHistorySyncIfNeeded() {
        if (!settings.isConfigured() || !settings.isAuthenticated()) return
        val now = System.currentTimeMillis()
        if (now - settings.lastSyncAt() < HISTORY_SYNC_INTERVAL_MS) return
        if (!syncInFlight.compareAndSet(false, true)) return

        executor.execute {
            try {
                syncEngine.syncRecentActivities(KarooHttpClient(karooSystem))
            } finally {
                syncInFlight.set(false)
            }
        }
    }

    private fun scheduleNearbyHydration(wantedTiles: Set<String>) {
        if (!settings.isConfigured() || !settings.isAuthenticated()) return
        val now = System.currentTimeMillis()
        if (now - lastHydrationRequestMs < HYDRATION_INTERVAL_MS) return
        if (!syncInFlight.compareAndSet(false, true)) return

        val staleTiles = repo.missingOrExpiredTiles(wantedTiles, now).take(MAX_TILE_HYDRATION)
        if (staleTiles.isEmpty()) {
            syncInFlight.set(false)
            return
        }

        lastHydrationRequestMs = now
        executor.execute {
            try {
                syncEngine.hydrateTiles(KarooHttpClient(karooSystem), staleTiles)
            } finally {
                syncInFlight.set(false)
            }
        }
    }

    private fun dispatchPrAlert(result: MatchResult.Finished) {
        karooSystem.dispatch(
            InRideAlert(
                id = "pr-${result.segmentId}",
                icon = R.drawable.ic_launcher,
                title = "New PR",
                detail = "${result.segmentName} in ${result.elapsedSeconds}s",
                autoDismissMs = 8_000L,
                backgroundColor = android.R.color.holo_green_dark,
                textColor = android.R.color.white,
            )
        )
        karooSystem.dispatch(
            SystemNotification(
                id = "pr-summary-${result.segmentId}",
                header = "kRecords",
                message = "New local PR on ${result.segmentName}",
                subText = "Elapsed ${result.elapsedSeconds}s",
                action = "Open",
                actionIntent = SETTINGS_INTENT_ACTION,
                style = SystemNotification.Style.EVENT,
            )
        )
    }

    private fun notifyNeedsSetup(): Boolean {
        return karooSystem.dispatch(
            SystemNotification(
                id = "krecords-setup",
                header = "kRecords",
                message = "Add the Firebase backend URL and connect Strava.",
                action = "Open",
                actionIntent = SETTINGS_INTENT_ACTION,
                style = SystemNotification.Style.SETUP,
            )
        )
    }

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    companion object {
        const val EXTENSION_ID = "krecords"
        const val BONUS_ACTION_OPEN_SETTINGS = "open-settings"
        const val SETTINGS_INTENT_ACTION = "com.zenpeartree.krecords.OPEN_SETTINGS"
        private const val FETCH_RADIUS_KM = 20.0
        private const val REFRESH_DISTANCE_METERS = 250.0
        private const val MAX_ACTIVE_SEGMENTS = 250
        private const val MAX_TILE_HYDRATION = 2
        private const val HISTORY_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val HYDRATION_INTERVAL_MS = 30_000L
    }
}
