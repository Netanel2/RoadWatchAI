# RoadWatch AI — validation protocol

A computer-vision counter should not be accepted because a few live examples looked correct. Validate the exact installation against ground truth.

## Camera lock

Before testing, permanently fix:

- camera position and tilt;
- focal/zoom setting;
- road polygon;
- every parking polygon;
- finite count line.

Do not move the phone during the validation recording.

## Recommended test set

Record at least 60 minutes from the final position, and include:

- vehicles already parked at startup;
- cars entering parking and becoming stationary;
- cars leaving parking;
- vehicles passing in both directions;
- two vehicles close together;
- partial occlusion by trees / poles / other vehicles;
- long shadows and lighting transitions;
- white, dark and reflective vehicles;
- motorcycles, trucks and buses if relevant.

## Ground truth

Manually annotate:

- every finite-line crossing;
- every parking event;
- every parking exit;
- parked occupancy at regular checkpoints (for example once per minute).

Use `ground_truth_template.csv` as a starting point.

## Error taxonomy

Review every mismatch and label it as one of:

- detector miss;
- false vehicle detection;
- ID switch;
- duplicate crossing;
- missed crossing;
- false parking transition;
- missed parking transition;
- incorrect vehicle class;
- geometry/calibration error.

Do not compensate for detector misses by blindly weakening every threshold. Fix the layer responsible for the error.

## Acceptance criteria

Choose targets before the test. Example deployment targets could be:

- crossing-count error ≤ 1% on the validation recording;
- zero duplicate crossing events;
- parked occupancy exact at ≥ 99% of checkpoints;
- no false `parked today` event for a car already parked at app startup.

These are example engineering targets, not guarantees. Actual acceptance criteria should match the intended use.

## When to train a custom model

If tracking and state transitions are correct but vehicles are consistently missed because of the high/top-down camera angle, collect and label frames from this camera and fine-tune the detector. Keep difficult negatives such as bins, shadows, signs and parked objects in the training set.
