# RoadWatch AI V8 — Fast Track + Auto Scene

V8 is a performance/temporal-logic refactor inspired by smooth YOLO speed-tracking pipelines.

## Main changes

- Camera preview stays native-speed; AI is throttled to about 11 analysis FPS.
- Aerial/OBB inference runs less often and is used as a fallback/support pass.
- Small-person tile inference runs only when the full-frame pass has no person and only on alternating AI frames.
- Crosswalk scanning runs during scene learning, then stops after crosswalk lock.
- Automatic scene-change detector re-enables scene learning after a large camera move/new view.
- Vehicle tracker keeps a ~1.6s centre history and estimates motion over a longer baseline.
- Motion is normalized by vehicle box size to reduce false movement on tiny/distant parked cars.
- Bounding boxes are interpolated between AI updates for a smoother visual overlay.
- New unit test protects against tiny detector jitter turning a parked vehicle into MOVING.

## Goal

Keep the expensive AI loop relatively slow and stable while the preview and overlay feel smooth. Classification of PARKED/MOVING is based on temporal track history, not single-frame box wobble.

## Verification in this workspace

- Core Kotlin sources compile with `kotlinc`.
- A custom smoke harness passed track continuity, stationary/parking state and automatic passing-count checks.
- Android XML resources parse successfully.
- Final APK compilation still must be verified by the repository GitHub Actions workflow because this workspace does not contain a full Android SDK toolchain.
