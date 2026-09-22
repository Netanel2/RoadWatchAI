# RoadWatch AI V9 — Crosswalk Validator

V9 keeps the V8 fast tracking pipeline and focuses on the remaining failure mode: false crosswalk locks.

- Crosswalk geometry is now only a candidate.
- A candidate must contain 4+ separate elongated neutral-white stripe components.
- Stripe centres must form a regular sequence, which rejects tiled sidewalks, paving seams, curbs and bright plazas.
- The validator is perspective/orientation agnostic; it learns from the stripe group rather than a fixed screen position.
- Lock requires 8 stable high-confidence observations.
- A locked crosswalk is still re-verified periodically at a low rate, so scene performance remains fast while a stale/wrong lock can be challenged.
- New scenes still reset and learn automatically.
