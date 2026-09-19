# RoadWatch AI — Native Android

Professional on-device vehicle analytics for a fixed phone camera. The app is designed to keep the camera preview smooth while independently detecting, tracking and classifying vehicle state over time.

## Live dashboard

- **חונים עכשיו** — unique tracked vehicles that remained stationary inside any configured parking zone for the parking dwell window.
- **בתנועה עכשיו** — unique tracked vehicles with sustained movement.
- **עברו היום** — unique tracked vehicles that crossed the finite traffic count segment.
- **חנו היום** — actual `MOVING → PARKED` events. Cars that were already parked when the app started do not inflate this counter.
- **יצאו מחניה** — actual `PARKED → LEAVING` events.
- Breakdown by car / truck / bus / motorcycle.
- Stable track ID and state label over every vehicle.

## Runtime architecture

`CameraX Preview` → `KEEP_ONLY_LATEST ImageAnalysis` → `MediaPipe EfficientDet-Lite2 INT8` → `two-stage identity tracker` → `vehicle state machine` → `parking occupancy + finite tripwire counter` → `live native UI`

Important design choices:

- Native Android — no browser/WASM camera loop.
- Camera preview and inference are decoupled; old analysis frames are dropped rather than queued.
- AI initialization runs off the UI thread.
- The model is downloaded **at build time** and packaged in the APK. The installed app does not need internet for AI inference.
- Detections below the main confidence band can still maintain or create tentative tracks; only multi-hit tracks become visible/countable.
- Brief detector misses do not immediately destroy IDs, but stale snapshots cannot accumulate false parking/movement evidence.
- Count-line logic uses the **finite segment**, not an imaginary infinite extension.
- Supports multiple parking polygons, useful when the visible parking area is split by trees, sidewalks or perspective.

## Calibration

Open **הגדרת אזורים** and configure:

1. **כביש** — mark four points around the actual driving area.
2. **חניה** — mark four points around a parking area. Repeat to add more parking areas.
3. **קו ספירה** — mark two endpoints across the lane where passing vehicles should be counted.
4. Save calibration.

For a permanent installation, keep the phone rigidly fixed after calibration.

## Accuracy policy

RoadWatch does not claim mathematically perfect computer vision. Production accuracy must be measured against recorded ground truth from the exact camera angle. The project includes `docs/VALIDATION.md` and a ground-truth template for this process.

The generic model is deliberately replaceable. If the remaining misses are caused by the unusual top-down camera angle, fine-tune an object detector with labeled frames from this camera and package the replacement TFLite model; the tracker/state/counter architecture stays the same.

## Build

### Android Studio

Open the project in a current Android Studio version, allow Gradle sync, and build the `app` module. During `preBuild`, Gradle downloads the official EfficientDet-Lite2 INT8 model into `app/src/main/assets/` and packages it into the APK.

### GitHub Actions

The repository contains `.github/workflows/build-apk.yml`. Push the project to GitHub and run **Build Android APK**. The workflow:

1. downloads the pinned AI model;
2. runs unit tests;
3. builds `app-debug.apk`;
4. uploads the APK as an Actions artifact.

## Verification performed in this workspace

The Android APK itself cannot be compiled in this execution environment because no Android SDK/Gradle installation is available here. The platform-independent Kotlin core **was compiled with `kotlinc` and passed a smoke test** covering tracking continuity, speed, parking dwell, multiple parking zones, and finite line crossing. Android XML resources were also parsed successfully.
