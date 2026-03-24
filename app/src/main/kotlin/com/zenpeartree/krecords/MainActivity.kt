package com.zenpeartree.krecords

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private lateinit var authQrImage: ImageView
    private lateinit var authUrlText: TextView
    private lateinit var backendInfoText: TextView
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

        authQrImage = findViewById(R.id.authQrImage)
        authUrlText = findViewById(R.id.authUrlText)
        backendInfoText = findViewById(R.id.backendInfoText)
        statusText = findViewById(R.id.statusText)
        summaryText = findViewById(R.id.summaryText)

        settings.saveBackendUrl(SettingsStore.PRODUCTION_BACKEND_URL)
        populateInputs()
        refreshSummary()

        findViewById<Button>(R.id.startAuthButton).setOnClickListener {
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

        findViewById<Button>(R.id.checkAuthButton).setOnClickListener {
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

        findViewById<Button>(R.id.syncButton).setOnClickListener {
            statusText.text = "Syncing recent Strava history..."
            executor.execute {
                val summary = syncEngine.syncRecentActivities(DirectHttpClient())
                runOnUiThread {
                    statusText.text = summary.message
                    refreshSummary()
                }
            }
        }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
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

    private fun populateInputs() {
        val config = settings.loadBackendConfig()
        backendInfoText.text = "Backend: ${config?.backendUrl ?: SettingsStore.PRODUCTION_BACKEND_URL}"
    }

    private fun renderAuthQr() {
        val config = settings.loadBackendConfig()
        val authUrl = config?.activeAuthUrl
        if (config == null) {
            authQrImage.visibility = android.view.View.GONE
            authUrlText.text = "Add your Firebase backend URL first."
            return
        }
        if (authUrl.isNullOrBlank()) {
            authQrImage.visibility = android.view.View.GONE
            authUrlText.text = "Tap Start Strava Auth to create a phone login session."
            return
        }

        authQrImage.setImageBitmap(QrCodeGenerator.generate(authUrl))
        authQrImage.visibility = android.view.View.VISIBLE
        authUrlText.text = authUrl
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
        statusText.text = dashboard.status
        summaryText.text = buildString {
            appendLine(if (dashboard.configured) "Backend configured." else "Backend missing.")
            appendLine(if (dashboard.authenticated) "Strava authorization complete." else "Strava authorization pending.")
            settings.athleteName()?.let { appendLine("Connected athlete: $it") }
            appendLine("${dashboard.segmentCount} segments cached locally.")
            if (dashboard.lastPrName != null && dashboard.lastPrSeconds != null) {
                appendLine("Last PR: ${dashboard.lastPrName} in ${dashboard.lastPrSeconds}s.")
            }
        }.trim()
    }

    companion object {
        private const val AUTH_POLL_INTERVAL_MS = 4_000L
    }
}
