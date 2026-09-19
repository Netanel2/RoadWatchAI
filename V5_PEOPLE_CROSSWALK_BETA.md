# RoadWatch AI V5 beta — people + crosswalk assist

What was added in this beta:

1. **People detection**
   - The street YOLO model now also decodes `person` detections.
   - People are drawn in yellow and counted live.

2. **Crosswalk assist (automatic)**
   - Added a heuristic crosswalk estimator based on white-stripe structure in the live frame.
   - The estimated crosswalk is drawn as a cyan dashed box.
   - This is a best-effort assist layer, not a guaranteed legal-grade detector.

3. **Yield-risk assist**
   - When pedestrians are present in / near the estimated crosswalk, moving vehicles near the crosswalk are counted as live `yield risk`.
   - This is intentionally phrased as a risk / warning, not a legal verdict.

4. **Reduced false positives**
   - Added stricter vehicle shape / size plausibility filters.
   - Small / implausible boxes are filtered earlier.

5. **UI / usability**
   - Bottom panel now shows people / crosswalk / risk status.
   - Added safe-area handling so the bottom controls sit above Android system buttons.
   - Zoom control remains visible and usable.

## Important notes

- This V5 package is a **beta upgrade**. The crosswalk detector is heuristic and should be tested on the exact camera angle.
- If the exact camera scene is known, the next version should promote the crosswalk from heuristic detection to a stabilized scene-memory object.
- If legal evidence / reports are desired, the next step should store short event clips and snapshots for each yield-risk event.
