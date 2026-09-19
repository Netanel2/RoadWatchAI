# RoadWatch AI V6 — Smart Yield Detection (beta)

V6 focuses on the exact fixed high-angle street scene:

- lower-threshold pedestrian detection + temporal PersonTracker
- pedestrian IDs and states: approaching / waiting / crossing
- crosswalk estimator that searches repeated bright stripes in both axes
- CrosswalkLock: a stable crosswalk is learned over several frames and then retained
- live yield-risk assistance when a moving vehicle is close to an active crossing pedestrian
- stricter vehicle track creation to reduce weak static false positives
- bottom controls positioned above Android system navigation via WindowInsets

Important: yield-risk is an assistive signal, not a legal determination. Validation against recorded ground truth from the exact camera is still required.
