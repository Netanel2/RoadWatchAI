# RoadWatch AI V4 — Auto Count

Changes:
- No manual road, parking, or count-line setup required.
- Full-frame automatic tracking and counting.
- Automatic `passed today` event when a confirmed moving vehicle travels a meaningful distance.
- Automatic parked / parked today / left parking states anywhere in frame.
- More tolerant parked-car motion hysteresis to ignore 1–2 px detector jitter.
- Compact UI so the camera is no longer covered by the large dashboard.
- Camera preview uses FIT_CENTER so it does not crop the image to fill a tall screen.
- Native CameraX zoom slider, defaulting to true 1.0x and exposing below-1x when the phone reports it.
- Smaller on-vehicle labels.
