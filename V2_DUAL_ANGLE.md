# RoadWatch AI V2 — Dual Angle

## Why V2

V1 used one general EfficientDet model and displayed the number of detections *after* zone filtering. That made two different failures look identical: either the AI missed a vehicle, or the AI found it but the zone/coordinate filter removed it.

V2 separates these layers and uses two complementary vehicle detectors:

1. **YOLO26n detect (COCO)** — normal road / street / oblique camera views.
2. **YOLO26n-OBB (DOTA)** — steep, aerial and rotated vehicle views; DOTA contains small-vehicle and large-vehicle categories.

The models are official Ultralytics Android LiteRT `w8a32` assets. The runtime tries LiteRT GPU first and falls back to CPU.

## Detection pipeline

CameraX RGBA -> rotation -> 640x640 letterbox -> YOLO street -> adaptive YOLO aerial -> NMS/fusion -> tracker -> road/parking state engine -> counters/UI.

The aerial model runs every analyzed frame when the street model finds zero vehicles, and every second analyzed frame otherwise. `STRATEGY_KEEP_ONLY_LATEST` prevents an inference queue from accumulating.

## Important V1 correctness fix

All AI detections are now sent to the tracker **before** road/parking filtering. Zones only affect analytics/counters. This preserves IDs while vehicles enter/leave a zone and lets the UI truthfully show raw detections.

Diagnostics now expose:

- `RAW` — fused vehicle detections before zones.
- `ZONE` — detections whose bottom-center is inside road/parking.
- `Street` — general YOLO candidates.
- `Aerial` — DOTA OBB vehicle candidates.
- accelerator and inference time.

If `RAW > 0` and `ZONE = 0`, the detector is working and the zone geometry needs adjustment. If `RAW = 0`, the issue is genuinely the model/view.

## Camera/UI

V2 changes PreviewView and OverlayView from FIT_CENTER to FILL_CENTER. This removes the large black letterbox area while keeping overlay mapping consistent with the cropped preview.

## Accuracy expectation

No computer-vision model can guarantee 100% detection at literally every possible angle, occlusion, lighting condition or distance. V2 is designed to cover two very different viewpoint families. The route to camera-specific production accuracy is to collect missed/false-positive frames from the actual installation and fine-tune a vehicle model on that data.

## Build

GitHub Actions downloads and verifies these official assets before building:

- `yolo26n_w8a32.tflite`
- `yolo26n-obb_w8a32.tflite`

The workflow then runs unit tests, builds `app-debug.apk` and uploads `RoadWatchAI-v2-debug-apk`.
