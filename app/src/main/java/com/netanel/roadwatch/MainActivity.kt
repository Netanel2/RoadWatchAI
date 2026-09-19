package com.netanel.roadwatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.netanel.roadwatch.core.DashboardMetrics
import com.netanel.roadwatch.core.DailyCounts
import com.netanel.roadwatch.core.TrackVisual
import com.netanel.roadwatch.core.VehicleStateEngine
import com.netanel.roadwatch.core.VehicleTracker
import com.netanel.roadwatch.core.ZoneConfig
import com.netanel.roadwatch.detector.VehicleDetector
import com.netanel.roadwatch.storage.DailyStatsStore
import com.netanel.roadwatch.storage.ZoneStore
import com.netanel.roadwatch.ui.OverlayView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), VehicleDetector.Listener {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var txtStatus: TextView
    private lateinit var txtLive: TextView
    private lateinit var txtParkedNow: TextView
    private lateinit var txtMovingNow: TextView
    private lateinit var txtPassedToday: TextView
    private lateinit var txtParkedToday: TextView
    private lateinit var txtDiagnostics: TextView
    private lateinit var txtBreakdown: TextView
    private lateinit var txtLeftToday: TextView
    private lateinit var txtActiveTracks: TextView
    private lateinit var setupPanel: LinearLayout
    private lateinit var txtSetupHint: TextView

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detector: VehicleDetector
    private lateinit var zoneStore: ZoneStore
    private lateinit var dailyStore: DailyStatsStore
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val tracker = VehicleTracker()
    private val stateEngine = VehicleStateEngine()
    private var zones = ZoneConfig()
    private var detectorReady = false
    private var cameraStarted = false
    private var delegateLabel = "AI ממתין"
    private var lastMetrics = DashboardMetrics()
    private var lastPersistedDaily: DailyCounts? = null

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else setStatus("נדרשת הרשאת מצלמה כדי להפעיל את המערכת")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bindViews()
        cameraExecutor = Executors.newSingleThreadExecutor()
        zoneStore = ZoneStore(this)
        dailyStore = DailyStatsStore(this)
        zones = zoneStore.load()
        val storedDaily = dailyStore.load()
        stateEngine.restoreDaily(storedDaily)
        lastPersistedDaily = stateEngine.dailyCounts()

        detector = VehicleDetector(this, this)
        overlayView.setZones(zones)
        wireUi()

        ensureCameraPermission()
        prepareModel()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        txtStatus = findViewById(R.id.txtStatus)
        txtLive = findViewById(R.id.txtLive)
        txtParkedNow = findViewById(R.id.txtParkedNow)
        txtMovingNow = findViewById(R.id.txtMovingNow)
        txtPassedToday = findViewById(R.id.txtPassedToday)
        txtParkedToday = findViewById(R.id.txtParkedToday)
        txtDiagnostics = findViewById(R.id.txtDiagnostics)
        txtBreakdown = findViewById(R.id.txtBreakdown)
        txtLeftToday = findViewById(R.id.txtLeftToday)
        txtActiveTracks = findViewById(R.id.txtActiveTracks)
        setupPanel = findViewById(R.id.setupPanel)
        txtSetupHint = findViewById(R.id.txtSetupHint)

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    private fun wireUi() {
        val btnSetup = findViewById<MaterialButton>(R.id.btnSetup)
        val btnReset = findViewById<MaterialButton>(R.id.btnReset)
        val btnRoad = findViewById<MaterialButton>(R.id.btnRoad)
        val btnParking = findViewById<MaterialButton>(R.id.btnParking)
        val btnLine = findViewById<MaterialButton>(R.id.btnLine)
        val btnUndoParking = findViewById<MaterialButton>(R.id.btnUndoParking)
        val btnClearZones = findViewById<MaterialButton>(R.id.btnClearZones)
        val btnSave = findViewById<MaterialButton>(R.id.btnSaveSetup)

        overlayView.onZonesChanged = { updated ->
            zones = updated
            overlayView.setZones(updated)
        }
        overlayView.onEditProgress = { message ->
            txtSetupHint.text = message
        }

        btnSetup.setOnClickListener {
            setupPanel.visibility = if (setupPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (setupPanel.visibility == View.VISIBLE) {
                txtSetupHint.text = setupSummary()
            }
        }
        btnRoad.setOnClickListener {
            overlayView.beginEdit(OverlayView.EditMode.ROAD)
            txtSetupHint.text = "סמן 4 פינות סביב הכביש שבו רכבים נוסעים"
        }
        btnParking.setOnClickListener {
            overlayView.beginEdit(OverlayView.EditMode.PARKING)
            txtSetupHint.text = "סמן 4 פינות סביב אזור חניה · אפשר להוסיף כמה אזורים"
        }
        btnLine.setOnClickListener {
            overlayView.beginEdit(OverlayView.EditMode.COUNT_LINE)
            txtSetupHint.text = "סמן 2 נקודות לקו שרכב נוסע צריך לחצות"
        }
        btnUndoParking.setOnClickListener {
            overlayView.removeLastParkingZone()
            zones = overlayView.getZones()
            txtSetupHint.text = setupSummary()
        }
        btnClearZones.setOnClickListener {
            overlayView.clearAllZones()
            zones = overlayView.getZones()
            tracker.reset()
            stateEngine.resetTrackingState()
            txtSetupHint.text = "האזורים נוקו · סמן מחדש כביש, חניה וקו ספירה"
        }
        btnSave.setOnClickListener {
            if (!zones.isComplete()) {
                txtSetupHint.text = "חסר סימון: צריך כביש, לפחות אזור חניה אחד וקו ספירה"
                return@setOnClickListener
            }
            zoneStore.save(zones)
            tracker.reset()
            stateEngine.resetTrackingState()
            overlayView.cancelEdit()
            setupPanel.visibility = View.GONE
            setStatus("הכיול נשמר ✓ ${zones.parkingZones.size} אזורי חניה פעילים")
        }
        btnReset.setOnClickListener {
            stateEngine.resetDay()
            dailyStore.clear()
            lastPersistedDaily = stateEngine.dailyCounts()
            lastMetrics = DashboardMetrics()
            updateDashboard(lastMetrics, VehicleDetector.Result(emptyList(), 0L, 0L, 0L, 0, 0, 1, 1, android.os.SystemClock.uptimeMillis(), delegateLabel), 0)
            setStatus("מוני היום אופסו")
        }
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun prepareModel() {
        setStatus("מאתחל מנוע AI מקומי...")
        cameraExecutor.execute { detector.initialize() }
    }

    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true
        previewView.post {
            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    val preview = Preview.Builder()
                        .setTargetResolution(android.util.Size(1280, 720))
                        .build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                    analysis.setAnalyzer(cameraExecutor) { imageProxy -> detector.analyze(imageProxy) }
                    imageAnalysis = analysis

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                    txtLive.text = "LIVE"
                    setStatus(if (detectorReady) "סורק רכבים בלייב" else "מצלמה פעילה · ממתין ל-AI")
                } catch (t: Throwable) {
                    cameraStarted = false
                    setStatus("פתיחת מצלמה נכשלה: ${t.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    override fun onReady(delegateName: String) {
        detectorReady = true
        delegateLabel = delegateName
        runOnUiThread {
            setStatus(if (zones.isComplete()) "AI מוכן ✓ סורק רכבים" else "AI מוכן ✓ הגדר כביש, חניה וקו ספירה")
        }
    }

    override fun onResult(result: VehicleDetector.Result) {
        // V3 accuracy gate: the detector may inspect the full frame for diagnostics,
        // but only detections whose bottom-center is inside a user-defined road or
        // parking ROI are allowed to create tracks, states or counters.
        val hasAnalysisZone = zones.road.isValid() || zones.parkingZones.any { it.isValid() }

        if (!hasAnalysisZone) {
            tracker.reset()
            stateEngine.resetTrackingState()
            lastMetrics = DashboardMetrics()
            runOnUiThread {
                overlayView.setScene(
                    trackVisuals = emptyList(),
                    zoneConfig = zones,
                    rotatedImageWidth = result.rotatedWidth,
                    rotatedImageHeight = result.rotatedHeight
                )
                updateDashboard(lastMetrics, result, 0)
                txtStatus.text = "AI מוכן · סמן כביש או חניה כדי להתחיל"
            }
            return
        }

        val zoneDetections = result.detections.filter { detection ->
            val point = detection.box.bottomCenter
            zones.road.contains(point) || zones.isInParking(point)
        }

        val tracks = tracker.update(zoneDetections, result.timestampMs)
        val (visuals, metrics) = stateEngine.update(tracks, zones, result.timestampMs)
        val daily = stateEngine.dailyCounts()
        if (daily != lastPersistedDaily) {
            dailyStore.save(daily)
            lastPersistedDaily = daily
        }
        lastMetrics = metrics

        runOnUiThread {
            overlayView.setScene(
                trackVisuals = visuals,
                zoneConfig = zones,
                rotatedImageWidth = result.rotatedWidth,
                rotatedImageHeight = result.rotatedHeight
            )
            updateDashboard(metrics, result, zoneDetections.size)
            txtStatus.text = "סורק · ${metrics.activeTracks} רכבים מאומתים · RAW ${result.detections.size}"
        }
    }

    override fun onError(message: String) {
        runOnUiThread { setStatus(message) }
    }

    private fun updateDashboard(metrics: DashboardMetrics, result: VehicleDetector.Result, inZoneDetections: Int) {
        txtParkedNow.text = metrics.parkedNow.toString()
        txtMovingNow.text = metrics.movingNow.toString()
        txtPassedToday.text = metrics.passedToday.toString()
        txtParkedToday.text = metrics.parkedToday.toString()
        txtLeftToday.text = "יצאו מחניה ${metrics.leftParkingToday}"
        txtActiveTracks.text = "במעקב ${metrics.activeTracks}"
        txtDiagnostics.text = "${result.engineLabel} · ${result.inferenceMs}ms · RAW ${result.detections.size} · ZONE $inZoneDetections"
        txtBreakdown.text = "Street ${result.generalVehicles} · Aerial ${result.aerialVehicles} · מכוניות ${metrics.carsNow} · משאיות ${metrics.trucksNow} · אוטובוסים ${metrics.busesNow} · אופנועים ${metrics.motorcyclesNow}"
    }

    private fun setupSummary(): String = buildString {
        append(if (zones.road.isValid()) "כביש ✓" else "כביש חסר")
        append(" · חניות ${zones.parkingZones.size}")
        append(if (zones.countLine != null) " · קו ✓" else " · קו חסר")
    }

    private fun setStatus(message: String) {
        txtStatus.text = message
    }

    override fun onDestroy() {
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.execute { detector.close() }
            cameraExecutor.shutdown()
        } else if (::detector.isInitialized) {
            detector.close()
        }
        super.onDestroy()
    }
}
