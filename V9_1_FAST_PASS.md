# RoadWatchAI V9.1 – Fast Pass Counter

Fixes a small-field-of-view edge case: a fast vehicle may only appear for 2 detector observations and previously could leave before the normal 3-hit / long-displacement path counted it.

V9.1 keeps the conservative 3-hit rule for on-screen state labels, but adds an independent fast-pass counter path:

- remembers the first observation from hit 1;
- can count from hit 2 when confidence is high and motion is clearly above box jitter;
- uses an adaptive distance threshold based on the vehicle box size;
- never counts from a single frame;
- keeps duplicate suppression and the normal slower counting path.

This change is intentionally limited to `passedToday`; parking/moving state classification remains conservative.
