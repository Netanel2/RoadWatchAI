package com.netanel.roadwatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
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
import com.google.android.material.button.MaterialButton
import com.netanel.roadwatch.core.DashboardMetrics
import com.netanel.roadwatch.core.DailyCounts
import com.netanel.roadwatch.core.VehicleStateEngine
import com.netanel.roadwatch.core.VehicleTracker
import com.netanel.roadwatch.core.ZoneConfig
import com.netanel.roadwatch.detector.VehicleDetector
import com.netanel.roadwatch.storage.DailyStatsStore
import com.netanel.roadwatch.ui.OverlayView
import java.util.Locale
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
    private lateinit var txtLeftToday: TextView
    private lateinit var txtActiveTracks: TextView
    private lateinit var txtZoom: TextView
    private lateinit var zoomSeek: SeekBar

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detector: VehicleDetector
    private lateinit var dailyStore: DailyStatsStore
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null

    private val tracker = VehicleTracker(maxMissingMs = 3_200L)
    private val stateEngine = VehicleStateEngine()
    private val noManualZones = ZoneConfig()

    private var detectorReady = false
    private var cameraStarted = false
    private var lastPersistedDaily: DailyCounts? = null
    private var minZoom = 1f
    private var maxZoom = 1f
    private var zoomUiReady = false

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
        dailyStore = DailyStatsStore(this)
        val storedDaily = dailyStore.load()
        stateEngine.restoreDaily(storedDaily)
        lastPersistedDaily = stateEngine.dailyCounts()

        detector = VehicleDetector(this, this)
        overlayView.setZones(noManualZones)
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
        txtLeftToday = findViewById(R.id.txtLeftToday)
        txtActiveTracks = findViewById(R.id.txtActiveTracks)
        txtZoom = findViewById(R.id.txtZoom)
        zoomSeek = findViewById(R.id.zoomSeek)

        // FIT_CENTER deliberately shows the complete camera image instead of the
        // crop/"fake zoom" caused by FILL_CENTER on tall phone screens.
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
    }

    private fun wireUi() {
        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener {
            stateEngine.resetDay()
            dailyStore.clear()
            lastPersistedDaily = stateEngine.dailyCounts()
            updateDashboard(DashboardMetrics())
            setStatus("מוני היום אופסו · הספירה ממשיכה אוטומטית")
        }

        zoomSeek.max = 1000
        zoomSeek.progress = 0
        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!zoomUiReady) return
                val ratio = progressToZoom(progress)
                txtZoom.text = String.format(Locale.US, "%.1f×", ratio)
                if (fromUser) camera?.cameraControl?.setZoomRatio(ratio)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun prepareModel() {
        setStatus("מאתחל AI אוטומטי...")
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
                    val boundCamera = provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                    camera = boundCamera
                    configureZoom(boundCamera)

                    txtLive.text = "LIVE"
                    setStatus(if (detectorReady) "סופר הכל אוטומטית" else "מצלמה פעילה · ממתין ל-AI")
                } catch (t: Throwable) {
                    cameraStarted = false
                    setStatus("פתיחת מצלמה נכשלה: ${t.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun configureZoom(boundCamera: Camera) {
        boundCamera.cameraInfo.zoomState.observe(this) { state ->
            minZoom = state.minZoomRatio.coerceAtLeast(0.1f)
            maxZoom = state.maxZoomRatio.coerceAtMost(8f).coerceAtLeast(minZoom)

            if (!zoomUiReady) {
                zoomUiReady = true
                // True optical/digital 1.0x by default. On devices exposing an
                // ultra-wide logical lens, the slider can also go below 1.0x.
                val defaultZoom = 1f.coerceIn(minZoom, maxZoom)
                boundCamera.cameraControl.setZoomRatio(defaultZoom)
                val p = zoomToProgress(defaultZoom)
                zoomSeek.progress = p
                txtZoom.text = String.format(Locale.US, "%.1f×", defaultZoom)
            } else if (!zoomSeek.isPressed) {
                val ratio = state.zoomRatio.coerceIn(minZoom, maxZoom)
                zoomSeek.progress = zoomToProgress(ratio)
                txtZoom.text = String.format(Locale.US, "%.1f×", ratio)
            }
        }
    }

    private fun progressToZoom(progress: Int): Float {
        if (maxZoom <= minZoom) return minZoom
        val t = progress.coerceIn(0, 1000) / 1000f
        return minZoom + (maxZoom - minZoom) * t
    }

    private fun zoomToProgress(ratio: Float): Int {
        if (maxZoom <= minZoom) return 0
        return (((ratio - minZoom) / (maxZoom - minZoom)) * 1000f)
            .toInt().coerceIn(0, 1000)
    }

    override fun onReady(delegateName: String) {
        detectorReady = true
        runOnUiThread { setStatus("AI מוכן ✓ ספירה אוטומטית · ללא סימון") }
    }

    override fun onResult(result: VehicleDetector.Result) {
        // V4: no manual zones at all. The strict detector + 3-hit tracker filter are
        // the gate. Every confirmed vehicle in the frame participates in automatic
        // moving / parked / passed / parked-today / left-parking logic.
        val tracks = tracker.update(result.detections, result.timestampMs)
        val (visuals, metrics) = stateEngine.update(
            tracks = tracks,
            zones = noManualZones,
            nowMs = result.timestampMs,
            automaticMode = true
        )

        val daily = stateEngine.dailyCounts()
        if (daily != lastPersistedDaily) {
            dailyStore.save(daily)
            lastPersistedDaily = daily
        }

        runOnUiThread {
            overlayView.setScene(
                trackVisuals = visuals,
                zoneConfig = noManualZones,
                rotatedImageWidth = result.rotatedWidth,
                rotatedImageHeight = result.rotatedHeight
            )
            updateDashboard(metrics)
            txtStatus.text = "אוטומטי · ${metrics.activeTracks} במעקב · ${result.inferenceMs}ms"
        }
    }

    override fun onError(message: String) {
        runOnUiThread { setStatus(message) }
    }

    private fun updateDashboard(metrics: DashboardMetrics) {
        txtParkedNow.text = metrics.parkedNow.toString()
        txtMovingNow.text = metrics.movingNow.toString()
        txtPassedToday.text = metrics.passedToday.toString()
        txtParkedToday.text = metrics.parkedToday.toString()
        txtLeftToday.text = "יצאו ${metrics.leftParkingToday}"
        txtActiveTracks.text = "מעקב ${metrics.activeTracks}"
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
