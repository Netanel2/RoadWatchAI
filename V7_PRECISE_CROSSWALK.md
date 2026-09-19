# RoadWatch AI V7 — precise crosswalk + small-person pass

V7 targets the exact failures seen in the fixed high-angle night camera test.

- Crosswalk detection now uses contiguous white runs instead of summing unrelated bright pixels across an entire row.
- Requires 4–9 nearby stripe bands with similar widths, overlap and reasonably regular spacing.
- Rejects giant road-wide boxes (the V6 curb/lane-marking failure mode).
- Crosswalk lock now needs 6 stable observations and can recover from a stale/wrong lock.
- Adds one overlapping magnified scene tile per analyzed frame for better small/distant person detection.
- Person floor is lower, while temporal confirmation remains required before people affect behavior logic.
- Reset also clears the crosswalk lock so the scene can relearn if the camera moves.
