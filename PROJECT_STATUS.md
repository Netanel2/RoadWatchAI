# RoadWatch AI — build status

## Implemented

- Native Android camera app (CameraX).
- On-device MediaPipe Object Detector.
- EfficientDet-Lite2 INT8 packaged at build time.
- No runtime Internet requirement.
- Vehicle classes: car / truck / bus / motorcycle.
- Two-stage stable-ID tracker with low-confidence rescue/tentative tracks.
- Multiple parking polygons.
- Finite road crossing segment.
- Temporal states: UNKNOWN / MOVING / STOPPING / PARKED / LEAVING.
- Current parked and moving counts.
- Daily passed / parked / parking-exit counters.
- Existing parked cars do not become false parking events on startup.
- Daily persistence only when counters change.
- Professional native overlay/dashboard.
- Setup tools for road, repeated parking zones, count line, undo parking zone, clear zones.
- Camera and analysis both target 1280×720; stale analysis frames are discarded.
- Reusable detector input bitmap to reduce garbage collection pressure.

## Validation completed here

- All Android XML resources parse successfully.
- Pure Kotlin tracking/geometry/state core compiles successfully with `kotlinc`.
- Core smoke test passes.
- Extended smoke test passes:
  - ID continuity
  - multiple parking zones
  - finite line intersection
  - startup parked occupancy without false daily event
  - genuine moving→parked event
  - stale-track evidence protection
  - line counted once

## Not executable in this workspace

This environment does not contain an Android SDK or Gradle installation, so the final Android APK could not be compiled locally here. The project contains a GitHub Actions workflow that downloads the model, runs the JUnit suite and builds `app-debug.apk` on a standard Android build runner.

## Required production acceptance step

Run the app on the final fixed phone/camera, record a representative validation clip, and compare against human ground truth using `docs/VALIDATION.md`. A camera-specific trained detector is the next step if generic COCO detection remains the source of misses.
