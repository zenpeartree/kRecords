package com.zenpeartree.krecords

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var settings: SettingsStore
    private lateinit var repo: SegmentDatabase
    private lateinit var syncEngine: BackendSyncEngine

    private lateinit var backendUrlInput: EditText
    private lateinit var deviceIdText: TextView
    private lateinit var authQrImage: ImageView
    private lateinit var authUrlText: TextView
    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsStore(this)
        repo = SegmentDatabase(this)
        syncEngine = BackendSyncEngine(settings, repo, TilePlanner())

        backendUrlInput = findViewById(R.id.backendUrlInput)
        deviceIdText = findViewById(R.id.deviceIdText)
        authQrImage = findViewById(R.id.authQrImage)
        authUrlText = findViewById(R.id.authUrlText)
        statusText = findViewById(R.id.statusText)
        summaryText = findViewById(R.id.summaryText)

        populateInputs()
        refreshSummary()

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            persistBackendUrl()
            settings.recordStatus("Backend URL saved.")
            renderAuthQr()
            refreshSummary()
        }

        findViewById<Button>(R.id.startAuthButton).setOnClickListener {
            persistBackendUrl()
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
                    }
                    renderAuthQr()
                    refreshSummary()
                }
            }
        }

        findViewById<Button>(R.id.checkAuthButton).setOnClickListener {
            persistBackendUrl()
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
            persistBackendUrl()
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
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun populateInputs() {
        val config = settings.loadBackendConfig()
        backendUrlInput.setText(config?.backendUrl.orEmpty())
        deviceIdText.text = "Device ID: ${settings.deviceId()}"
    }

    private fun persistBackendUrl() {
        settings.saveBackendUrl(backendUrlInput.text.toString())
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

    private fun refreshSummary() {
        val dashboard = settings.dashboard(repo.countSegments())
        statusText.text = dashboard.status
        summaryText.text = buildString {
            appendLine(if (dashboard.configured) "Backend URL configured." else "Backend URL missing.")
            appendLine(if (dashboard.authenticated) "Strava authorization complete." else "Strava authorization pending.")
            settings.athleteName()?.let { appendLine("Connected athlete: $it") }
            appendLine("${dashboard.segmentCount} segments cached locally.")
            if (dashboard.lastPrName != null && dashboard.lastPrSeconds != null) {
                appendLine("Last PR: ${dashboard.lastPrName} in ${dashboard.lastPrSeconds}s.")
            }
        }.trim()
    }
}
