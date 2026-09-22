# V10 validation

## Executed checks

- `gradle testDebugUnitTest assembleDebug`: **BUILD SUCCESSFUL**.
- Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin plugin 2.2.21, Android SDK 36, Java 17.
- **24 JUnit tests passed, 0 failed, 0 errors, 0 skipped** (12 existing cases, 12 added cases).
- Pure core compilation and JUnit execution also performed independently with Kotlin 2.1.20 during development.
- The existing fast-pass test now expects a high-confidence two-observation vehicle to be visible, consistent with V10's earlier display behavior.

Coverage includes tracking, parking/pass counters, pedestrian footpoint classification, persistent crosswalk locking and expiry, finite swept crossings between frames, duplicate suppression, stationary cars, absent/late pedestrians, excessive frame gaps, homography geometry, degenerate corner rejection, calibrated known-speed tracks, stale/out-of-area speed samples, and synthetic crosswalk stripes at 0/30/60/90 degrees plus negative blank/single-stripe images.

## Limits of validation

- Android source, resources, dependency integration, unit tests and debug packaging were checked. No physical phone, camera stream or road footage was available.
- The local packaging check did not include the model binaries and is not distributed as an installable deliverable. The existing GitHub workflow downloads and checks the original two model binaries before building the installable APK.
- No real-scene precision/recall, speed error in km/h, FPS or thermal benchmark is claimed. Synthetic stripe tests do not establish real-world crosswalk accuracy.
- Event results are suspected non-yield passages; lane rules, signals, pedestrian intent and legal determination are not implemented. Camera movement detection is best effort.

## Device acceptance check

Use a fixed camera. Check the outlined crosswalk and correct it manually if necessary. Set a measured road rectangle before relying on speed estimates. Compare a short manually reviewed recording against the on-screen counters: stationary cars, empty crossing, pedestrian waiting, pedestrian crossing, two close vehicles and partial occlusion. Check the displayed analysis FPS and processing time. Recalibrate after moving or zooming the camera.
