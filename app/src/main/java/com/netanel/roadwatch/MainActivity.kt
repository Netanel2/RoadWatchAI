package com.netanel.roadwatch

import android.Manifest
import android.widget.EditText
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import com.netanel.roadwatch.core.*
import java.time.LocalDate
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

    private val speedEstimator = SpeedEstimator()
    private val eventCounter = YieldEventCounter()
    private var calibration: RoadCalibration? = null
    private var manualCrosswalk: Polygon2? = null
    private var eventsToday = 0
    private var maxSpeedToday = 0f
    private var statsDate = ""
    private var lastSpeedCandidates = emptyMap<Int, Float>()
    private var latestZoom: Float? = null
    private var latestFrameSize = ""
    private var needsSceneReset = false
    @Volatile private var sceneRevision = 0L
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
        loadSafetyStats()
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
            cameraExecutor.execute {
                stateEngine.resetDay(); dailyStore.clear()
                resetScene()
                eventsToday = 0; maxSpeedToday = 0f; saveSafetyStats()
                lastPersistedDaily = stateEngine.dailyCounts()
                runOnUiThread { setStatus("המונים אופסו · סמן מעבר וכייל מהירות מחדש") }
            }
        }
        findViewById<MaterialButton>(R.id.btnSetup).setOnClickListener { showSetup() }

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
        setStatus("מאתחל V10 Scene AI...")
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
                    setStatus(if (detectorReady) "V10 · סורק סצנה" else "מצלמה פעילה · ממתין ל-AI")
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
            if (latestZoom != null && kotlin.math.abs(ratio - latestZoom!!) > .005f) {
                overlayView.cancelSelection()
                cameraExecutor.execute { needsSceneReset = true }
            }
            latestZoom = ratio
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
        runOnUiThread { setStatus("V10 מוכן ✓ CROSSWALK + FAST PASS") }
    }

    override fun onResult(result: VehicleDetector.Result) {
        rolloverSafetyStats()
        val frameSize = "${result.rotatedWidth}x${result.rotatedHeight}"
        val changed = result.sceneChanged || needsSceneReset || (latestFrameSize.isNotEmpty() && latestFrameSize != frameSize)
        if (changed) { resetScene(); needsSceneReset = false }
        latestFrameSize = frameSize
        val vehicleTracks = vehicleTracker.update(result.detections, result.timestampMs)
        val (vehicleVisuals, baseMetrics) = stateEngine.update(vehicleTracks, result.timestampMs)
        val autoState = crosswalkLock.update(result.crosswalk, result.timestampMs)
        val manual = manualCrosswalk
        val manualEstimate = manual?.let { p -> CrosswalkEstimate(Box(p.points.minOf{it.x},p.points.minOf{it.y},p.points.maxOf{it.x},p.points.maxOf{it.y}),1f) }
        val crosswalkState = if (manualEstimate != null) CrosswalkLock.State(manualEstimate,true,4,result.timestampMs) else autoState
        detector.setCrosswalkSearchEnabled(!crosswalkState.locked)
        detector.pedestrianRegion = crosswalkState.estimate?.box?.expand(.1f,.22f)
        val personTracks = personTracker.update(result.personDetections, result.timestampMs)
        val behavior = pedestrianYieldEngine.update(personTracks, vehicleVisuals,
            crosswalkState.estimate.takeIf { crosswalkState.locked }, result.timestampMs, manual)
        val zone = manual ?: crosswalkState.estimate?.takeIf { crosswalkState.locked }?.polygon()
        val newEvents = eventCounter.update(behavior.people,vehicleTracks,zone,result.timestampMs)
        val speeds = speedEstimator.update(vehicleTracks,calibration,result.timestampMs)
        // Require two consecutive stable speed estimates before changing the maximum.
        val verifiedMax = speeds.mapNotNull { (id, speed) ->
            lastSpeedCandidates[id]?.takeIf { kotlin.math.abs(it-speed) <= maxOf(5f,speed*.15f) }?.let { minOf(it,speed) }
        }.maxOrNull() ?: 0f
        lastSpeedCandidates = speeds
        val changedStats = newEvents > 0 || verifiedMax > maxSpeedToday
        eventsToday += newEvents; maxSpeedToday = maxOf(maxSpeedToday,verifiedMax)
        if (changedStats) saveSafetyStats()
        val displayEvents = eventsToday
        val displayMax = maxSpeedToday
        val displayCalibration = calibration

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
            if (changed) overlayView.cancelSelection()
            overlayView.manualPolygon = manual
            overlayView.calibrationPolygon = displayCalibration?.polygon
            overlayView.speedKmh = speeds
            overlayView.setScene(
                trackVisuals = vehicleVisuals,
                personVisuals = behavior.people,
                crosswalkEstimate = crosswalkState.estimate,
                crosswalkLocked = crosswalkState.locked,
                rotatedImageWidth = result.rotatedWidth,
                rotatedImageHeight = result.rotatedHeight
            )
            updateDashboard(metrics, result, crosswalkState)
            findViewById<TextView>(R.id.txtSafetyStats).text = "חשדות היום: $displayEvents · שיא משוער: " +
                (if (displayMax > 0) "≈${displayMax.toInt()} קמ״ש" else "—") +
                (if (displayCalibration == null) " · נדרש כיול" else "")
            txtStatus.text = when {
                changed -> "התמונה השתנתה · יש לסמן מעבר ולכייל מחדש"
                metrics.yieldRiskNow > 0 -> "⚠ חשד: רכב בתנועה ליד הולך רגל במעבר"
                metrics.peopleInCrosswalkNow > 0 -> "מעבר פעיל · ${metrics.peopleInCrosswalkNow} חוצים"
                crosswalkState.locked -> "V10 · מעבר LOCKED · אנשים ${metrics.peopleNow}"
                else -> "V10 · מאמת מעבר חציה · ${crosswalkState.stableHits}/4"
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
            (crosswalkState?.stableHits ?: 0) > 0 -> "מאמת מעבר ${crosswalkState?.stableHits}/4"
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
        txtDiagnostics.text = "${result.engineLabel} · ${String.format(Locale.US, "%.1f", result.analysisFps)} FPS · עיבוד ${result.inferenceMs}ms · רכבים RAW ${result.detections.size} · אנשים RAW ${result.personDetections.size}$crosswalkText"
        txtBreakdown.text = "Street ${result.generalVehicles} · Aerial ${result.aerialVehicles} · מכוניות ${metrics.carsNow} · משאיות ${metrics.trucksNow} · אוטובוסים ${metrics.busesNow} · אופנועים ${metrics.motorcyclesNow}"
    }

    private fun loadSafetyStats() {
        val prefs = getSharedPreferences("road_safety_v10", MODE_PRIVATE)
        statsDate = prefs.getString("date", "") ?: ""
        eventsToday = prefs.getInt("events",0)
        maxSpeedToday = prefs.getFloat("maxSpeed",0f)
        rolloverSafetyStats()
    }
    private fun rolloverSafetyStats() {
        val date = LocalDate.now().toString()
        if (date != statsDate) {
            statsDate=date; eventsToday=0; maxSpeedToday=0f; eventCounter.reset(); saveSafetyStats()
        }
    }
    private fun saveSafetyStats() {
        getSharedPreferences("road_safety_v10", MODE_PRIVATE).edit().putString("date",statsDate)
            .putInt("events",eventsToday).putFloat("maxSpeed",maxSpeedToday).apply()
    }
    // All mutable analysis state is owned by the camera executor.
    private fun resetScene() {
        sceneRevision++
        vehicleTracker.reset(); personTracker.reset(); stateEngine.resetTrackingState()
        crosswalkLock.reset(); eventCounter.reset(); speedEstimator.reset()
        calibration=null; manualCrosswalk=null; lastSpeedCandidates=emptyMap()
        detector.pedestrianRegion=null; detector.setCrosswalkSearchEnabled(true)
    }
    private fun showSetup() {
        AlertDialog.Builder(this).setTitle("הגדרת כביש · מצלמה קבועה")
            .setItems(arrayOf("סימון מעבר חציה ב־4 לחיצות", "כיול מהירות לפי מלבן מדוד", "חזרה לזיהוי אוטומטי", "ביטול סימון")) { _, choice ->
                when(choice) {
                    0 -> {
                        val revision = sceneRevision
                        setStatus("לחץ על 4 פינות מעבר החציה לפי הסדר סביבו")
                        overlayView.selectFourCorners { points ->
                            if (!RoadCalibration.validQuad(points)) { setStatus("סימון לא תקין · נסה שוב לפי סדר הפינות") }
                            else cameraExecutor.execute {
                                if (revision != sceneRevision) { runOnUiThread { setStatus("התמונה השתנתה · סמן שוב") }; return@execute }
                                manualCrosswalk=Polygon2(points); eventCounter.reset()
                                runOnUiThread { setStatus("מעבר חציה סומן · ספירת חשדות פעילה") }
                            }
                        }
                    }
                    1 -> {
                        AlertDialog.Builder(this).setTitle("כיול מהירות משוערת")
                            .setMessage("המצלמה חייבת להיות קבועה. סמן מלבן אמיתי ומדוד על הכביש: פינה קרובה שמאל, קרובה ימין, רחוקה ימין, רחוקה שמאל. המדידה תקפה רק בתוך המלבן. שינוי זום או מיקום מחייב כיול מחדש.")
                            .setPositiveButton("סמן 4 פינות") { _, _ ->
                                setStatus("כיול: קרוב שמאל ← קרוב ימין ← רחוק ימין ← רחוק שמאל")
                                val revision = sceneRevision
                                overlayView.selectFourCorners { points -> requestDimensions(points, revision) }
                            }.setNegativeButton("ביטול",null).show()
                    }
                    2 -> {
                        overlayView.cancelSelection()
                        cameraExecutor.execute { manualCrosswalk=null; crosswalkLock.reset(); eventCounter.reset(); detector.setCrosswalkSearchEnabled(true) }
                    }
                    else -> overlayView.cancelSelection()
                }
            }.show()
    }
    private fun requestDimensions(points: List<Vec2>, revision: Long) {
        if (!RoadCalibration.validQuad(points)) { setStatus("פינות לא תקינות · סמן מחדש"); return }
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),0,dp(20),0) }
        val width=EditText(this).apply { hint="רוחב המלבן במטרים (בין פינות 1–2)"; inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val length=EditText(this).apply { hint="אורך המלבן במטרים (בין פינות 2–3)"; inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        panel.addView(width); panel.addView(length)
        val dialog=AlertDialog.Builder(this).setTitle("מידות אמיתיות — אין לנחש")
            .setView(panel).setPositiveButton("שמור",null).setNegativeButton("ביטול",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val w=width.text.toString().replace(',','.').toFloatOrNull()
                val l=length.text.toString().replace(',','.').toFloatOrNull()
                val c=runCatching { RoadCalibration(points,w ?: 0f,l ?: 0f) }.getOrNull()
                if(c==null) { width.error="רוחב 1–100 מ׳ ואורך 1–200 מ׳" }
                else {
                    cameraExecutor.execute {
                        if (revision == sceneRevision) {
                            calibration=c; speedEstimator.reset(); lastSpeedCandidates=emptyMap()
                            runOnUiThread { setStatus("כיול נשמר · מהירות משוערת בתוך המלבן הכחול") }
                        } else runOnUiThread { setStatus("התמונה השתנתה · יש לכייל מחדש") }
                    }
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
    override fun onStop() {
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) cameraExecutor.execute { needsSceneReset=true; detector.resetFrameClock() }
        super.onStop()
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
