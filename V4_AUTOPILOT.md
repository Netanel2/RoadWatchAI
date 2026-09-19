# RoadWatch AI V4 — Autopilot

V4 removes all required manual road, parking and count-line drawing.

## What changed
- Full-frame automatic vehicle tracking.
- Automatic moving / stopping / parked / leaving classification.
- Automatic "passed today" based on meaningful track displacement rather than a manual tripwire.
- Existing parked cars count as parked now but not parked today.
- Moving -> parked creates one parked-today event; parked -> moving creates one left-parking event.
- Preview uses FIT_CENTER so the default view is no longer cropped like a digital zoom.
- Real CameraX zoom slider plus +/- controls; starts at optical 1x when available.
- Compact HUD; diagnostics are hidden behind the Details button.

The system still cannot guarantee perfect vision in every lighting/occlusion case. V4 is designed to require zero scene setup while keeping multi-frame confirmation and confidence filtering from V3.
