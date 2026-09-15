# CataNav v2

Native Android app for **GPS-free navigation on any calibrated map image**. Import an
image of a physical space (cave map, floor plan, mine map, hiking map, scanned
survey…), calibrate it (scale + north), and navigate on it with pedestrian dead
reckoning (step detection + heading) corrected by **manual re-anchoring**. Zero GPS,
zero network at runtime. The Paris Catacombs plate ships as the first preconfigured
example — nothing in the code assumes it.

> **Privacy:** all trip data (positions, timestamps, tracks, anchors, notes, history)
> stays on device. The manifest contains **no** location permission and **no** network
> permission — enforced by an executable test (`ManifestAuditTest`) on every
> `gradlew test`.

## Build

- Kotlin, single module, minSdk 26, targetSdk 36, Views + one custom map view.
- `./gradlew assembleDebug` — build; `./gradlew test` — unit tests.

## Coordinate & north conventions (locked)

Three coordinate spaces, one explicit transformer between them
([domain/Coordinates.kt](app/src/main/java/com/catanav/domain/Coordinates.kt)):

| Space | Units | Axes |
|---|---|---|
| Image | pixels | origin top-left, X right, Y down |
| Map-local (the engine's space) | meters | X = east, Y = north; origin shared with image origin |
| Geographic lat/lon | degrees | future layer only — the engine never depends on it |

- `CoordinateTransformer` (built from `metersPerPixel` + `northOffsetDegrees`) is the
  ONLY place pixels and meters meet.
- **`northOffsetDegrees` = angle, in degrees clockwise (as displayed), from image-up to
  true north.** North-up map ⇒ 0. North pointing image-right ⇒ 90.
- PDR update, heading 0° = north, clockwise:
  `newX = oldX + d·sin(h)` (east), `newY = oldY + d·cos(h)` (north).
- Two-finger view rotation is a pure display transform; it never touches calibration or
  engine state.
- PDR does not depend on the renderer; the renderer contains no PDR logic.

## Architecture (layers: UI → managers → domain → infrastructure)

```
com.catanav
├── ui/            MapsActivity (home: import/rename/recalibrate/delete, gating),
│                  MainActivity (navigation on any calibrated map version),
│                  CalibrateMapActivity (two-point + north arrow),
│                  StepCalibrationActivity + CalibrationTestActivity (device profile),
│                  HistoryActivity, TripDetailActivity, SettingsActivity
├── trip/          TripSession — the live trip: bound map version, PDR engine, trail,
│                  ordered autosave queue, named anchors, uncertainty growth
│                  (faster while heading confidence is LOW/UNRELIABLE)
├── service/       TrackingService: foreground service + PARTIAL_WAKE_LOCK,
│                  screen-off tracking, battery estimate
├── domain/        PURE logic: CoordinateTransformer, CalibrationMath (two-point scale,
│                  north-arrow convention, trip gating, test-error math), PositionEstimate
├── pdr/           PURE PDR: StepDetector, HeadingEstimator (+ HeadingConfidence),
│                  RotationVectorHeading / ComplementaryFilterHeading, PdrEngine (meters),
│                  SensorHub (Android glue, startup capability check)
├── anchor/        DriftCalibrator: radius = distance_since_anchor × drift_rate,
│                  anchor-to-anchor learning persisted in the device profile
├── data/          Room v2: MapDefinition → MapVersion → MapCalibration, named Anchors,
│                  Trip(mapVersionId), TrackPoint(meters + uncertainty),
│                  calibration-test history; MapImporter (SAF); CatacombsSeeder
├── map/           Rendering infra: MapView (BitmapRegionDecoder viewport decoding,
│                  pan/zoom/view-only rotation, meter-based overlay API via the
│                  transformer), MapViewport (pure px↔screen math), FileMapSource,
│                  PlaceholderMapGenerator + PngStreamWriter (streaming, O(row) memory)
├── settings/      DataStore device profile: step length, orientation offset,
│                  drift calibration, heading source, red mode
└── export/        CsvExporter (canonical, metric authoritative), GeoRef (geographic
                   seam for a future lat/lon layer — no GPX in v2)
```

### Key invariants

- **No full-bitmap decode, at any image size.** Only the visible viewport (+25 %
  margin) is decoded at a zoom-matched sample size; imports record dimensions with a
  bounds-only decode; even the generated placeholder streams through a row-at-a-time
  PNG encoder.
- **Autosave per point; crash-resume.** Every TrackPoint is queued to Room as
  generated; a trip with `endTime NULL` is offered for resume on launch, restored on
  its own map version with an honestly-sized uncertainty circle.
- **Trip start IS an anchor**; re-anchor resets position + uncertainty and never
  auto-corrects heading (optional facing dial). Every anchor is a
  `TrackPoint(source=ANCHOR)`; optionally named ("Junction 14") and then reusable
  across trips on that map version.
- **Trips reference map versions.** Recalibrating creates a new `MapVersion` +
  `MapCalibration` and re-points the map's active version; old trips render forever
  with the calibration they were recorded under (tested).
- **Device profile ≠ map calibration.** Step length, phone orientation offset, drift
  learning and calibration-test history describe the user/device; scale and north
  describe the map. They never mix.
- **No GPS code paths exist.**

## Importing and calibrating a map

1. **Maps → Import map**, pick any image (SAF, `image/*`). The image is copied into
   app-private storage; no permission is needed or requested.
2. The **calibration flow** opens automatically (a map without calibration cannot start
   a trip — the Maps list shows "Needs calibration"):
   - tap **point A** on a feature whose real distance to another feature you know
     (zoom in — precision here bounds all downstream accuracy);
   - tap **point B**;
   - enter the **real A–B distance in meters** → `metersPerPixel = meters / pixels`;
   - rotate the **north arrow** until it points to true north as drawn on the map
     (works even with the view rotated), confirm → `northOffsetDegrees`.
3. Recalibrate any time from the map's long-press menu — a new version is created, old
   trips are untouched.
4. Device-side: run **Step calibration** (walk a measured 20–50 m) once per
   user/device, optionally set the **orientation offset** (Settings) if you hold the
   phone at an angle, and validate with the **Calibration test** screen (walk a known
   distance, see the % error, history stored).

### The Catacombs seed

Seeded on first launch as data (map + version + calibration: 7000×7000 px, 231 px =
100 m ⇒ 0.433 m/px, north-up). Drop the real plate at
`app/src/main/assets/catacombs_map.jpg` before building to use it; otherwise a
full-size placeholder grid is generated. Caveat preserved in code: the plate is a
stitched historical composite — average scale, not survey-grade; re-anchoring exists to
bound that error.

## CSV export (canonical)

```
trip_id, map_id, map_version_id, timestamp, x_meters, y_meters,
x_pixels, y_pixels, heading_degrees, uncertainty_meters, source
```

Metric columns are authoritative; pixel columns are derived through the transformer at
export time. `source=ANCHOR` rows show where and by how much drift was corrected.
Export goes through Android's file picker (SAF) — nothing leaves the device on its own.
GPX was removed in v2 (the old per-trip lat/lon reference has no place in the new
model); the geographic seam ([export/GeoRef.kt](app/src/main/java/com/catanav/export/GeoRef.kt))
remains tested for a future optional layer.

## What only a human can validate — field-test checklist

The automated tests cover the math against synthetic streams, not real sensors, real
walking, or real tunnels. Per device AND per map:

1. **Step calibration + calibration test** (above ground): calibrate, then walk a
   different known distance and check the % error on the test screen. Repeat until
   stable; results accumulate in the device profile.
2. **Closed loop above ground** (300–500 m): start a trip, walk the loop, compare the
   marker with your true return point — that distance is your drift. Try both heading
   sources (Settings) before blaming the walk.
3. **Heading error near metal**: watch the confidence line ("Magnetic interference
   detected") near rebar/vehicles; verify the figure-8 prompt appears and which heading
   source recovers faster.
4. **Map calibration sanity**: after calibrating an imported map, measure a known
   corridor on the map (re-anchor at both ends): the distance-since-anchor readout
   should match reality within a few %.
5. **Underground drift over a short safe section**: anchor, walk to a landmark, compare
   prediction vs reality BEFORE re-anchoring, re-anchor, repeat 5–10×. The uncertainty
   circle should bound the true error and the drift rate converge. Check ANCHOR rows in
   a CSV export afterwards.
6. **Battery**: 2 h+ trip, screen mostly off; verify the 20 % warning, the remaining
   estimate, and that tracking survives screen-off and backgrounding.
7. **Crash recovery**: force-stop mid-trip; relaunch must offer resume with trail and
   uncertainty intact — including on a map that was recalibrated since the trip started.
8. **Manual mode**: deny ACTIVITY_RECOGNITION; the status line must say manual mode and
   the arrow buttons + heading dial must move the marker correctly on a rotated
   (non-north-up) map.

## Verified vs not verified — honestly

`./gradlew assembleDebug` and `./gradlew test` are green. Unit/Robolectric tests cover:
coordinate transformer (0/90/180/37.5° + round-trips), PDR meters math + square walks
(including on a 90°-rotated map), step detection (shake/jitter rejection), both heading
estimators + confidence grading, drift learning + uncertainty reset, two-point
calibration math + north-offset convention + trip gating, Room integrity
(Trip→MapVersion, recalibration isolation, cascades, crash-resume), CSV golden rows,
battery estimation, and the manifest permission audit.

NOT verified automatically: real sensor behavior, real-world accuracy, battery drain,
background-tracking behavior on specific OEM builds, and the full touch UX — that is
the field-test checklist above.

## Out of scope in v2 (by design)

Multi-point/affine calibration and georeferencing (the `CalibrationMethod` enum and the
GeoRef seam reserve the space), multi-floor maps, map matching, waypoint UI, heading
inference from motion, cloud/network anything.
