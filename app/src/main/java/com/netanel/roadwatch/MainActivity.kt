package com.netanel.roadwatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.netanel.roadwatch.core.CrosswalkLock
import com.netanel.roadwatch.core.DashboardMetrics
import com.netanel.roadwatch.core.DailyCounts
import com.netanel.roadwatch.core.PedestrianYieldEngine
import com.netanel.roadwatch.core.PersonTracker
import com.netanel.roadwatch.core.VehicleStateEngine
import com.netanel.roadwatch.core.VehicleTracker
import com.netanel.roadwatch.detector.VehicleDetector
import com.netanel.roadwatch.storage.DailyStatsStore
import com.netanel.roadwatch.ui.OverlayView
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

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
    private lateinit var txtZoom: TextView
    private lateinit var txtPeopleNow: TextView
    private lateinit var txtCrosswalkNow: TextView
    private lateinit var txtYieldRisk: TextView
    private lateinit var txtCrosswalkLock: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var detailsPanel: LinearLayout
    private lateinit var bottomPanel: LinearLayout

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detector: VehicleDetector
    private lateinit var dailyStore: DailyStatsStore
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var boundCamera: Camera? = null

    private val vehicleTracker = VehicleTracker()
    private val personTracker = PersonTracker()
    private val stateEngine = VehicleStateEngine()
    private val crosswalkLock = CrosswalkLock()
    private val pedestrianYieldEngine = PedestrianYieldEngine()

    private var detectorReady = false
    private var cameraStarted = false
    private var delegateLabel = "AI ממתין"
    private var lastMetrics = DashboardMetrics()
    private var lastPersistedDaily: DailyCounts? = null
    private var zoomMin = 1f
    private var zoomMax = 1f
    private var syncingZoomUi = false

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
        applySafeInsets()
        cameraExecutor = Executors.newSingleThreadExecutor()
        dailyStore = DailyStatsStore(this)
        val storedDaily = dailyStore.load()
        stateEngine.restoreDaily(storedDaily)
        lastPersistedDaily = stateEngine.dailyCounts()

        detector = VehicleDetector(this, this)
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
        txtZoom = findViewById(R.id.txtZoom)
        txtPeopleNow = findViewById(R.id.txtPeopleNow)
        txtCrosswalkNow = findViewById(R.id.txtCrosswalkNow)
        txtYieldRisk = findViewById(R.id.txtYieldRisk)
        txtCrosswalkLock = findViewById(R.id.txtCrosswalkLock)
        zoomSeek = findViewById(R.id.zoomSeek)
        detailsPanel = findViewById(R.id.detailsPanel)
        bottomPanel = findViewById(R.id.bottomPanel)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
    }

    private fun applySafeInsets() {
        val baseMargin = dp(10)
        ViewCompat.setOnApplyWindowInsetsListener(bottomPanel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val lp = view.layoutParams as FrameLayout.LayoutParams
            lp.bottomMargin = bars.bottom + baseMargin
            view.layoutParams = lp
            insets
        }
    }

    private fun wireUi() {
        val btnReset = findViewById<MaterialButton>(R.id.btnReset)
        val btnDetails = findViewById<MaterialButton>(R.id.btnDetails)
        val btnZoomOut = findViewById<MaterialButton>(R.id.btnZoomOut)
        val btnZoomIn = findViewById<MaterialButton>(R.id.btnZoomIn)

        btnReset.setOnClickListener {
            stateEngine.resetDay()
            dailyStore.clear()
            vehicleTracker.reset()
            personTracker.reset()
            lastPersistedDaily = stateEngine.dailyCounts()
            lastMetrics = DashboardMetrics()
            updateDashboard(lastMetrics, null, null)
            setStatus("מוני היום אופסו · AUTO ממשיך לסרוק")
        }

        btnDetails.setOnClickListener {
            detailsPanel.visibility = if (detailsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            btnDetails.text = if (detailsPanel.visibility == View.VISIBLE) "סגור" else "פרטים"
        }

        zoomSeek.max = 1000
        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || syncingZoomUi) return
                val ratio = progressToZoom(progress)
                boundCamera?.cameraControl?.setZoomRatio(ratio)
                updateZoomLabel(ratio)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        btnZoomOut.setOnClickListener { nudgeZoom(-0.25f) }
        btnZoomIn.setOnClickListener { nudgeZoom(0.25f) }
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun prepareModel() {
        setStatus("מאתחל V6 Scene AI...")
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
                        .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                    analysis.setAnalyzer(cameraExecutor) { imageProxy -> detector.analyze(imageProxy) }
                    imageAnalysis = analysis

                    provider.unbindAll()
                    boundCamera = provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                    configureZoom(boundCamera!!)
                    txtLive.text = "LIVE"
                    setStatus(if (detectorReady) "V6 · סורק סצנה" else "מצלמה פעילה · ממתין ל-AI")
                } catch (t: Throwable) {
                    cameraStarted = false
                    setStatus("פתיחת מצלמה נכשלה: ${t.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun configureZoom(camera: Camera) {
        camera.cameraInfo.zoomState.observe(this) { state ->
            zoomMin = state.minZoomRatio
            zoomMax = state.maxZoomRatio.coerceAtLeast(zoomMin)
            val ratio = state.zoomRatio.coerceIn(zoomMin, zoomMax)
            syncingZoomUi = true
            zoomSeek.progress = zoomToProgress(ratio)
            syncingZoomUi = false
            updateZoomLabel(ratio)
        }

        camera.cameraInfo.zoomState.value?.let { state ->
            zoomMin = state.minZoomRatio
            zoomMax = state.maxZoomRatio.coerceAtLeast(zoomMin)
            camera.cameraControl.setZoomRatio(1f.coerceIn(zoomMin, zoomMax))
        }
    }

    private fun nudgeZoom(delta: Float) {
        val state = boundCamera?.cameraInfo?.zoomState?.value ?: return
        val target = (state.zoomRatio + delta).coerceIn(state.minZoomRatio, state.maxZoomRatio)
        boundCamera?.cameraControl?.setZoomRatio(target)
        updateZoomLabel(target)
    }

    private fun progressToZoom(progress: Int): Float {
        if (zoomMax <= zoomMin) return zoomMin
        return zoomMin + (zoomMax - zoomMin) * (progress.coerceIn(0, 1000) / 1000f)
    }

    private fun zoomToProgress(ratio: Float): Int {
        if (zoomMax <= zoomMin) return 0
        return (((ratio - zoomMin) / (zoomMax - zoomMin)) * 1000f).toInt().coerceIn(0, 1000)
    }

    private fun updateZoomLabel(ratio: Float) {
        txtZoom.text = String.format(Locale.US, "%.1fx", ratio)
    }

    override fun onReady(delegateName: String) {
        detectorReady = true
        delegateLabel = delegateName
        runOnUiThread { setStatus("V6 מוכן ✓ לומד את הסצנה אוטומטית") }
    }

    override fun onResult(result: VehicleDetector.Result) {
        val vehicleTracks = vehicleTracker.update(result.detections, result.timestampMs)
        val (vehicleVisuals, baseMetrics) = stateEngine.update(vehicleTracks, result.timestampMs)

        val crosswalkState = crosswalkLock.update(result.crosswalk, result.timestampMs)
        val personTracks = personTracker.update(result.personDetections, result.timestampMs)
        val behavior = pedestrianYieldEngine.update(
            personTracks = personTracks,
            vehicleVisuals = vehicleVisuals,
            crosswalk = crosswalkState.estimate,
            nowMs = result.timestampMs
        )

        val metrics = baseMetrics.copy(
            peopleNow = behavior.peopleNow,
            peopleInCrosswalkNow = behavior.peopleInCrosswalkNow,
            yieldRiskNow = behavior.yieldRiskNow
        )

        val daily = stateEngine.dailyCounts()
        if (daily != lastPersistedDaily) {
            dailyStore.save(daily)
            lastPersistedDaily = daily
        }
        lastMetrics = metrics

        runOnUiThread {
            overlayView.setScene(
                trackVisuals = vehicleVisuals,
                personVisuals = behavior.people,
                crosswalkEstimate = crosswalkState.estimate,
                crosswalkLocked = crosswalkState.locked,
                rotatedImageWidth = result.rotatedWidth,
                rotatedImageHeight = result.rotatedHeight
            )
            updateDashboard(metrics, result, crosswalkState)
            txtStatus.text = when {
                metrics.yieldRiskNow > 0 -> "⚠ חשד: רכב בתנועה ליד הולך רגל במעבר"
                metrics.peopleInCrosswalkNow > 0 -> "מעבר פעיל · ${metrics.peopleInCrosswalkNow} חוצים"
                crosswalkState.locked -> "V6 · מעבר חציה LOCKED · אנשים ${metrics.peopleNow}"
                else -> "V6 · לומד מעבר חציה · ${crosswalkState.stableHits}/4"
            }
        }
    }

    override fun onError(message: String) {
        runOnUiThread { setStatus(message) }
    }

    private fun updateDashboard(
        metrics: DashboardMetrics,
        result: VehicleDetector.Result?,
        crosswalkState: CrosswalkLock.State?
    ) {
        txtParkedNow.text = metrics.parkedNow.toString()
        txtMovingNow.text = metrics.movingNow.toString()
        txtPassedToday.text = metrics.passedToday.toString()
        txtParkedToday.text = metrics.parkedToday.toString()
        txtPeopleNow.text = "אנשים ${metrics.peopleNow}"
        txtCrosswalkNow.text = "במעבר ${metrics.peopleInCrosswalkNow}"
        txtYieldRisk.text = "חשד ${metrics.yieldRiskNow}"
        txtLeftToday.text = "יצאו ${metrics.leftParkingToday}"
        txtActiveTracks.text = "רכבים ${metrics.activeTracks}"
        txtCrosswalkLock.text = when {
            crosswalkState?.locked == true -> "CROSSWALK LOCK ✓"
            (crosswalkState?.stableHits ?: 0) > 0 -> "לומד מעבר ${crosswalkState?.stableHits}/4"
            else -> "מחפש מעבר חציה"
        }

        if (result == null) {
            txtDiagnostics.text = delegateLabel
            txtBreakdown.text = "מכוניות ${metrics.carsNow} · משאיות ${metrics.trucksNow} · אוטובוסים ${metrics.busesNow} · אופנועים ${metrics.motorcyclesNow}"
            return
        }
        val crosswalkText = crosswalkState?.estimate?.let {
            " · מעבר ${(it.confidence * 100f).roundToInt()}%"
        } ?: " · מעבר --"
        txtDiagnostics.text = "${result.engineLabel} · ${result.inferenceMs}ms · רכבים RAW ${result.detections.size} · אנשים RAW ${result.personDetections.size}$crosswalkText"
        txtBreakdown.text = "Street ${result.generalVehicles} · Aerial ${result.aerialVehicles} · מכוניות ${metrics.carsNow} · משאיות ${metrics.trucksNow} · אוטובוסים ${metrics.busesNow} · אופנועים ${metrics.motorcyclesNow}"
    }

    private fun setStatus(message: String) {
        txtStatus.text = message
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

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
