# Camera-specific learning / model upgrade

The app does not self-modify its neural network while running. For a counting system, uncontrolled online self-training can reinforce its own mistakes. The safer production loop is supervised improvement.

## Dataset loop

1. Record footage from the final fixed camera.
2. Sample frames across morning / noon / evening and difficult shadows.
3. Label every visible `car`, `truck`, `bus`, and `motorcycle` that should be detected.
4. Include hard negatives such as bins, signs, tree shadows and objects that previously caused false positives.
5. Split by time segment so near-identical adjacent frames do not leak from training into validation.
6. Fine-tune a detector and export a TFLite model with MediaPipe-compatible metadata.
7. Compare generic vs custom model on the same held-out validation clip.
8. Replace the packaged model only if the custom model improves the predefined metrics.

## Deployment hook

The current packaged asset is:

`app/src/main/assets/efficientdet_lite2_int8.tflite`

The Gradle task downloads the default model during the build. For a custom production model, place the compatible file in assets and adjust `VehicleDetector.MODEL_ASSET` plus the Gradle download task as needed.

The rest of RoadWatch — stable IDs, motion state, parking dwell, daily counters, and finite tripwire — does not depend on the detector architecture.
