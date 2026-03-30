package com.zenpeartree.krecords

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var settings: SettingsStore
    private lateinit var repo: SegmentDatabase
    private lateinit var syncEngine: BackendSyncEngine

    private lateinit var startAuthButton: Button
    private lateinit var checkAuthButton: Button
    private lateinit var syncButton: Button
    private lateinit var clearButton: Button
    private lateinit var authQrImage: ImageView
    private lateinit var authUrlText: TextView
    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView
    private var authPolling = false
    private val authPollRunnable = object : Runnable {
        override fun run() {
            pollAuthStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsStore(this)
        repo = SegmentDatabase(this)
        syncEngine = BackendSyncEngine(settings, repo, TilePlanner())

        startAuthButton = findViewById(R.id.startAuthButton)
        checkAuthButton = findViewById(R.id.checkAuthButton)
        syncButton = findViewById(R.id.syncButton)
        clearButton = findViewById(R.id.clearButton)
        authQrImage = findViewById(R.id.authQrImage)
        authUrlText = findViewById(R.id.authUrlText)
        statusText = findViewById(R.id.statusText)
        summaryText = findViewById(R.id.summaryText)
        authUrlText.setTextIsSelectable(true)

        refreshSummary()

        startAuthButton.setOnClickListener {
            statusText.text = "Creating auth session..."
            executor.execute {
                val session = syncEngine.startAuthSession(DirectHttpClient())
                runOnUiThread {
                    if (session == null) {
                        statusText.text = "Failed to create auth session."
                    } else {
                        settings.recordAuthSession(session)
                        settings.recordStatus(session.message)
                        statusText.text = session.message
                        startAuthPolling(immediate = false)
                    }
                    renderAuthQr()
                    refreshSummary()
                }
            }
        }

        checkAuthButton.setOnClickListener {
            statusText.text = "Checking auth session..."
            executor.execute {
                val session = syncEngine.getAuthSessionStatus(DirectHttpClient())
                runOnUiThread {
                    if (session == null) {
                        statusText.text = "No active auth session."
                    } else {
                        statusText.text = session.message
                        settings.recordStatus(session.message)
                        if (session.isComplete) {
                            settings.recordAuthenticated(session.athleteName)
                            settings.clearAuthSession()
                        }
                    }
                    renderAuthQr()
                    refreshSummary()
                }
            }
        }

        syncButton.setOnClickListener {
            statusText.text = "Syncing recent Strava history..."
            executor.execute {
                val summary = syncEngine.syncRecentActivities(DirectHttpClient())
                runOnUiThread {
                    statusText.text = summary.message
                    refreshSummary()
                }
            }
        }

        clearButton.setOnClickListener {
            executor.execute {
                repo.clearAll()
                settings.clearCacheMetadata()
                runOnUiThread {
                    statusText.text = "Cleared local segment cache."
                    renderAuthQr()
                    refreshSummary()
                }
            }
        }

        renderAuthQr()
        if (settings.loadBackendConfig()?.activeAuthSessionId != null && !settings.isAuthenticated()) {
            startAuthPolling(immediate = true)
        }
    }

    override fun onDestroy() {
        stopAuthPolling()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun renderAuthQr() {
        val config = settings.loadBackendConfig()
        val authUrl = config?.activeAuthUrl
        if (authUrl.isNullOrBlank()) {
            authQrImage.visibility = View.GONE
            authUrlText.text = when {
                settings.isAuthenticated() -> "Strava is connected. You only need this QR again if you want to reconnect."
                config.activeAuthSessionId != null -> "Your login session is still open. Tap Check Auth Status after you finish on the phone."
                else -> "Tap Connect with Strava and a QR code will appear here for your phone."
            }
            return
        }

        authQrImage.setImageBitmap(QrCodeGenerator.generate(authUrl))
        authQrImage.visibility = View.VISIBLE
        authUrlText.text = buildString {
            appendLine("Scan this QR with your phone, approve Strava access, then come back and tap Check Auth Status.")
            appendLine()
            append(authUrl)
        }
    }

    private fun startAuthPolling(immediate: Boolean) {
        authPolling = true
        mainHandler.removeCallbacks(authPollRunnable)
        if (immediate) {
            authPollRunnable.run()
        } else {
            mainHandler.postDelayed(authPollRunnable, AUTH_POLL_INTERVAL_MS)
        }
    }

    private fun stopAuthPolling() {
        authPolling = false
        mainHandler.removeCallbacks(authPollRunnable)
    }

    private fun pollAuthStatus() {
        if (!authPolling) return
        if (settings.loadBackendConfig()?.activeAuthSessionId == null) {
            stopAuthPolling()
            return
        }

        executor.execute {
            val session = syncEngine.getAuthSessionStatus(DirectHttpClient())
            runOnUiThread {
                if (!authPolling) {
                    return@runOnUiThread
                }

                if (session == null) {
                    statusText.text = "No active auth session."
                    stopAuthPolling()
                } else {
                    statusText.text = session.message
                    settings.recordStatus(session.message)
                    if (session.isComplete) {
                        settings.recordAuthenticated(session.athleteName)
                        settings.clearAuthSession()
                        stopAuthPolling()
                    } else if (session.status == "error") {
                        stopAuthPolling()
                    } else {
                        mainHandler.postDelayed(authPollRunnable, AUTH_POLL_INTERVAL_MS)
                    }
                }
                renderAuthQr()
                refreshSummary()
            }
        }
    }

    private fun refreshSummary() {
        val dashboard = settings.dashboard(repo.countSegments())
        val activeSession = settings.loadBackendConfig().activeAuthSessionId != null
        statusText.text = when {
            activeSession -> "Waiting for Strava authorization to finish on your phone."
            dashboard.authenticated -> "Connected and ready to watch for nearby PRs."
            else -> "Connect Strava to start building your local PR alerts."
        }

        startAuthButton.text = if (dashboard.authenticated) {
            getString(R.string.reconnect_strava)
        } else {
            getString(R.string.connect_with_strava)
        }
        checkAuthButton.isEnabled = activeSession
        syncButton.isEnabled = dashboard.authenticated
        clearButton.isEnabled = dashboard.segmentCount > 0

        summaryText.text = buildString {
            appendLine(
                if (dashboard.authenticated) {
                    "Strava account connected."
                } else {
                    "Strava account not connected yet."
                }
            )
            settings.athleteName()?.let { appendLine("Connected athlete: $it") }
            appendLine("${dashboard.segmentCount} segments cached on this Karoo.")
            appendLine(
                if (dashboard.segmentCount > 0) {
                    "Nearby segments will keep refreshing around you as you ride."
                } else {
                    "Run Sync Recent History after connecting to build your first segment baseline."
                }
            )
            if (dashboard.lastPrName != null && dashboard.lastPrSeconds != null) {
                appendLine("Last PR: ${dashboard.lastPrName} in ${dashboard.lastPrSeconds}s.")
            }
            appendLine("Latest app status: ${dashboard.status}")
        }.trim()
    }

    companion object {
        private const val AUTH_POLL_INTERVAL_MS = 4_000L
    }
}
