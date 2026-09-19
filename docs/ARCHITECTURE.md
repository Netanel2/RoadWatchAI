# RoadWatch AI — Architecture

## Pipeline

CameraX Preview (device display refresh)
→ CameraX ImageAnalysis (1280×720, `STRATEGY_KEEP_ONLY_LATEST`)
→ MediaPipe Object Detector in `LIVE_STREAM`
→ vehicle-only normalized detections
→ two-stage identity tracker
→ per-track temporal state engine
→ finite line-crossing + parking occupancy/events
→ native OverlayView + dashboard

The display never waits for the detector. If inference cannot keep up with the camera, old analysis frames are discarded instead of accumulating latency.

## Detector

Default: **EfficientDet-Lite2 INT8**, chosen for accuracy over Lite0. Supported classes used by RoadWatch are `car`, `truck`, `bus`, and `motorcycle`.

The model is fetched during the Gradle build and packaged as `efficientdet_lite2_int8.tflite`. Runtime inference is local; the app manifest does not require Internet permission.

A score threshold of 0.10 allows difficult/high-angle detections to reach the tracker. A detection must still survive tracking/state logic before it can affect metrics.

## Tracking

The tracker is self-contained and deterministic. It uses two association stages inspired by the core idea behind modern multi-object trackers:

1. associate stronger detections using IoU + predicted center + class consistency;
2. use lower-confidence detections to rescue unmatched tracks.

Unmatched moderate-confidence detections may create tentative tracks, but UI/state counting only uses tracks with repeated hits. Tracks survive brief detector dropouts for ID continuity.

Speed is computed from smoothed normalized-frame velocity, so the state engine does not depend on raw box jitter from one frame.

## State machine

Each stable ID owns temporal memory.

- `MOVING` — sustained high-speed evidence.
- `STOPPING` — low-speed dwell inside a parking polygon.
- `PARKED` — low speed persists inside any parking polygon for the parking dwell window.
- `LEAVING` — a previously parked track shows sustained movement.
- `UNKNOWN` — not enough stable temporal evidence.

Stale retained tracks are allowed to preserve identity through a detector miss, but stale snapshots do **not** accumulate movement or parking evidence.

An already-parked vehicle visible at app startup eventually becomes `PARKED` and contributes to **חונים עכשיו**, but it does not increment **חנו היום** unless sustained motion was actually observed before the parking transition.

## Road crossing

A crossing is counted only when:

- the track is moving;
- its bottom-center reference point is in the road ROI;
- motion between two observations intersects the actual finite count-line segment;
- that track has not already counted the line.

A short position+direction reacquisition guard protects against duplicate counts caused by an ID switch, while keeping the window short enough not to suppress a legitimate following vehicle.

## Parking geometry

`ZoneConfig` supports one road polygon, **multiple parking polygons**, and one count line. Bottom-center of each vehicle box is the geometry reference point, which is more appropriate for ground-plane reasoning than the visual center of the object box.

## Persistence

- Zone calibration is stored locally in `SharedPreferences`.
- Daily event counters are persisted only when values change, not every inference frame.
- Counters automatically start a new day when the local calendar date changes.

## Production upgrade path

The generic detector is the replaceable component. For one permanent camera:

1. record representative footage;
2. label difficult vehicles and hard negatives;
3. train/fine-tune a TFLite detector with compatible metadata;
4. validate it on a held-out clip;
5. replace the packaged model while leaving tracking/state/counter code unchanged.
