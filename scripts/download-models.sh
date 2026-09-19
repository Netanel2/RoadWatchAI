#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/src/main/assets
curl -L --fail --retry 3 -o app/src/main/assets/yolo26n_w8a32.tflite \
  https://github.com/ultralytics/yolo-flutter-app/releases/download/v0.6.6/yolo26n_w8a32.tflite
curl -L --fail --retry 3 -o app/src/main/assets/yolo26n_obb_w8a32.tflite \
  https://github.com/ultralytics/yolo-flutter-app/releases/download/v0.6.6/yolo26n-obb_w8a32.tflite
