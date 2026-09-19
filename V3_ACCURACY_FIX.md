# RoadWatch AI V3 — Accuracy Fix

Changes:
- Raised YOLO vehicle confidence gates (street 0.30, aerial/OBB 0.32).
- A detection must start at >=0.30 confidence to create a new track. Lower confidence can only rescue an already-established track.
- Requires 3-frame confirmation before a track is visualized/countable.
- Production gating: only detections inside the configured road/parking ROI may create tracks or affect counters. With no ROI configured, the app shows diagnostics but creates zero tracks.
- Fixed overlay/ROI geometry to match CameraX PreviewView FILL_CENTER cropping.

This is designed to stop low-confidence indoor false positives such as figurines/furniture while keeping lower-confidence rescue for a real vehicle after it has been established.
