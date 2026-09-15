package com.catanav.trip

import com.catanav.anchor.DriftCalibrator
import com.catanav.data.ActiveMap
import com.catanav.data.AnchorEntity
import com.catanav.data.MapRepository
import com.catanav.data.PointSource
import com.catanav.data.TrackPointEntity
import com.catanav.data.TripEntity
import com.catanav.data.TripRepository
import com.catanav.domain.CoordinateTransformer
import com.catanav.domain.MapPoint
import com.catanav.domain.PositionEstimate
import com.catanav.pdr.HeadingConfidence
import com.catanav.pdr.HeadingEstimator
import com.catanav.pdr.PdrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * The live trip: owns the PDR engine (map-local METERS), drift calibration, the
 * in-memory trail, and the autosave queue. Singleton (via ServiceLocator) so the
 * tracking Service and the UI observe the SAME state; the Service only adds sensors,
 * wake lock and the foreground notification on top.
 *
 * A session is BOUND to one calibrated map version before a trip can start or resume
 * ([bind]); the bound version is what trips reference and what supplies the
 * CoordinateTransformer for rendering/export. PDR itself never touches the map.
 *
 * Autosave: every generated TrackPoint goes through [persistQueue] into Room in
 * arrival order, as it is generated. A crash or process death mid-trip loses at most
 * the last point, and the trip (endTime still null) is resumed on restart
 * (hard-constraint #4).
 */
class TripSession(
    private val repository: TripRepository,
    private val mapRepository: MapRepository,
    private val settings: com.catanav.settings.SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine = PdrEngine()

    var driftCalibrator = DriftCalibrator()
        private set

    // ---- map binding -----------------------------------------------------------

    private val _boundMap = MutableStateFlow<ActiveMap?>(null)
    val boundMap: StateFlow<ActiveMap?> = _boundMap

    val transformer: CoordinateTransformer?
        get() = _boundMap.value?.transformer()

    /** Bind the session to a calibrated map version. Refused while a trip is active. */
    fun bind(map: ActiveMap) {
        check(!isActive || _boundMap.value?.version?.id == map.version.id) {
            "cannot rebind while a trip is active"
        }
        _boundMap.value = map
    }

    // ---- observable state ---------------------------------------------------------

    private val _tripId = MutableStateFlow<Long?>(null)
    val tripId: StateFlow<Long?> = _tripId

    private val _trail = MutableStateFlow<List<TrackPointEntity>>(emptyList())
    val trail: StateFlow<List<TrackPointEntity>> = _trail

    private val _stepCount = MutableStateFlow(0)
    val stepCount: StateFlow<Int> = _stepCount

    /** Name of the most recent anchor when the user gave one ("Junction 14"). */
    private val _lastAnchorName = MutableStateFlow<String?>(null)
    val lastAnchorName: StateFlow<String?> = _lastAnchorName

    val isActive: Boolean get() = _tripId.value != null

    /** Live compass heading (deg, orientation offset applied); NaN before first fix. */
    @Volatile
    var currentHeadingDeg: Double = Double.NaN
        private set

    @Volatile
    var currentHeadingConfidence: HeadingConfidence = HeadingConfidence.HIGH

    /** Manual-mode heading set on the dial. */
    @Volatile
    var manualHeadingDeg: Double = 0.0

    @Volatile
    var manualMode: Boolean = false

    /**
     * Fixed device-orientation offset (deg): how far the phone's Y axis points away
     * from the walking direction (device profile, set in Settings). Applied to every
     * sensor heading before it reaches the engine.
     */
    @Volatile
    var orientationOffsetDeg: Double = 0.0

    /**
     * Extra distance-weight accumulated while the heading was LOW/UNRELIABLE: those
     * meters count double in the uncertainty radius (PDR keeps running, the circle
     * just grows faster — never imply confidence that is not there).
     */
    @Volatile
    private var unreliableDistanceM: Double = 0.0

    private val persistQueue = Channel<TrackPointEntity>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (point in persistQueue) {
                repository.appendPoint(point) // ordered, one insert per point
            }
        }
        scope.launch {
            driftCalibrator = settings.loadDriftCalibrator()
            engine.stepLengthMeters = settings.stepLengthMeters.first()
            orientationOffsetDeg = settings.orientationOffsetDegrees.first()
        }
    }

    // ---- uncertainty ---------------------------------------------------------------

    fun uncertaintyRadiusMeters(): Double {
        val p = engine.position.value ?: return 0.0
        return driftCalibrator.uncertaintyRadiusMeters(p.distanceSinceAnchorM + unreliableDistanceM)
    }

    fun positionEstimate(): PositionEstimate? {
        val p = engine.position.value ?: return null
        return PositionEstimate(
            xMeters = p.xMeters,
            yMeters = p.yMeters,
            positionUncertaintyMeters = uncertaintyRadiusMeters(),
            headingDegrees = p.headingDeg,
            headingUncertaintyDegrees = currentHeadingConfidence.headingUncertaintyDegrees,
            confidence = currentHeadingConfidence,
        )
    }

    // ---- sensor plumbing (called by TrackingService's SensorHub) ----------------------

    fun onSensorHeading(headingDeg: Double) {
        currentHeadingDeg = HeadingEstimator.normalize(headingDeg + orientationOffsetDeg)
        if (!manualMode) engine.onHeadingChanged(currentHeadingDeg)
    }

    fun onSensorConfidence(confidence: HeadingConfidence) {
        currentHeadingConfidence = confidence
    }

    fun onSensorStep(timestampMs: Long) {
        if (manualMode) return
        val heading = currentHeadingDeg
        if (heading.isNaN()) return // never integrate a frozen/unknown heading
        step(heading, 1)
    }

    // ---- manual mode --------------------------------------------------------------------

    fun manualStep(direction: Int) {
        if (!isActive) return
        engine.onHeadingChanged(manualHeadingDeg)
        step(manualHeadingDeg, direction)
    }

    fun setManualHeading(deg: Double) {
        manualHeadingDeg = HeadingEstimator.normalize(deg)
        if (manualMode) engine.onHeadingChanged(manualHeadingDeg)
    }

    private fun step(headingDeg: Double, direction: Int) {
        val id = _tripId.value ?: return
        engine.onStep(headingDeg, direction)
        val p = engine.position.value ?: return
        if (!manualMode &&
            (currentHeadingConfidence == HeadingConfidence.LOW ||
                currentHeadingConfidence == HeadingConfidence.UNRELIABLE)
        ) {
            unreliableDistanceM += engine.stepLengthMeters
        }
        val point = TrackPointEntity(
            tripId = id,
            xMeters = p.xMeters,
            yMeters = p.yMeters,
            timestampMs = System.currentTimeMillis(),
            source = PointSource.PDR,
            headingDeg = p.headingDeg,
            uncertaintyMeters = uncertaintyRadiusMeters(),
        )
        _trail.value = _trail.value + point
        _stepCount.value = p.totalSteps
        persistQueue.trySend(point)
    }

    // ---- trip lifecycle --------------------------------------------------------------------

    /**
     * Trip start IS a forced first anchor: [anchor] is the user-confirmed starting
     * point in map meters (no default or guessed origin exists anywhere). Requires a
     * bound, calibrated map version.
     */
    suspend fun startTrip(name: String, anchor: MapPoint, headingDeg: Double?, anchorName: String? = null) {
        check(!isActive) { "trip already active" }
        val bound = _boundMap.value ?: error("no map bound — calibrate and select a map first")
        engine.stepLengthMeters = settings.stepLengthMeters.first()
        orientationOffsetDeg = settings.orientationOffsetDegrees.first()
        driftCalibrator = settings.loadDriftCalibrator()
        unreliableDistanceM = 0.0
        val now = System.currentTimeMillis()
        val id = repository.startTrip(bound.version.id, name, now)
        _tripId.value = id
        engine.reset()
        engine.anchor(
            anchor.xMeters, anchor.yMeters,
            headingDeg ?: currentHeadingDeg.takeUnless { it.isNaN() },
        )
        val point = TrackPointEntity(
            tripId = id,
            xMeters = anchor.xMeters,
            yMeters = anchor.yMeters,
            timestampMs = now,
            source = PointSource.ANCHOR,
            headingDeg = engine.position.value?.headingDeg?.takeUnless { it.isNaN() },
            uncertaintyMeters = 0.0,
        )
        _trail.value = listOf(point)
        _stepCount.value = 0
        _lastAnchorName.value = anchorName
        repository.appendPoint(point) // anchor is persisted before any step can follow
        if (anchorName != null) {
            mapRepository.addAnchor(
                AnchorEntity(
                    mapVersionId = bound.version.id,
                    xMeters = anchor.xMeters,
                    yMeters = anchor.yMeters,
                    name = anchorName,
                    createdAt = now,
                ),
            )
        }
    }

    /**
     * Mid-trip manual re-anchor (map meters). Logs the anchor as a
     * TrackPoint(source=ANCHOR) so exports show where and by how much drift was
     * corrected, feeds the predicted-vs-tapped delta into the drift-rate running
     * average (persisted in the device profile), and — when the user names it —
     * stores a reusable named Anchor for this map version.
     * Heading is NOT auto-corrected; [headingDeg] only if the user confirmed a facing.
     */
    suspend fun reanchor(
        tapped: MapPoint,
        headingDeg: Double? = null,
        anchorName: String? = null,
        anchorNotes: String? = null,
    ) {
        val id = _tripId.value ?: return
        val bound = _boundMap.value ?: return
        val predicted = engine.position.value
        if (predicted != null && predicted.distanceSinceAnchorM > 0) {
            val accepted = driftCalibrator.onReanchor(
                predictedXM = predicted.xMeters,
                predictedYM = predicted.yMeters,
                actualXM = tapped.xMeters,
                actualYM = tapped.yMeters,
                distanceWalkedM = predicted.distanceSinceAnchorM,
            )
            if (accepted) settings.saveDriftCalibrator(driftCalibrator)
        }
        engine.anchor(tapped.xMeters, tapped.yMeters, headingDeg)
        unreliableDistanceM = 0.0
        val now = System.currentTimeMillis()
        val point = TrackPointEntity(
            tripId = id,
            xMeters = tapped.xMeters,
            yMeters = tapped.yMeters,
            timestampMs = now,
            source = PointSource.ANCHOR,
            headingDeg = headingDeg,
            uncertaintyMeters = 0.0,
        )
        _trail.value = _trail.value + point
        _lastAnchorName.value = anchorName
        repository.appendPoint(point)
        if (anchorName != null || anchorNotes != null) {
            mapRepository.addAnchor(
                AnchorEntity(
                    mapVersionId = bound.version.id,
                    xMeters = tapped.xMeters,
                    yMeters = tapped.yMeters,
                    name = anchorName,
                    notes = anchorNotes,
                    createdAt = now,
                ),
            )
        }
    }

    /**
     * Reload an interrupted trip (endTime null) and continue from its last point.
     * The session must already be bound to the trip's OWN map version (the caller
     * resolves it via MapRepository.boundMapForVersion — never the active version).
     */
    suspend fun resumeTrip(trip: TripEntity) {
        check(!isActive) { "trip already active" }
        val bound = _boundMap.value
        check(bound != null && bound.version.id == trip.mapVersionId) {
            "session must be bound to the trip's map version before resuming"
        }
        engine.stepLengthMeters = settings.stepLengthMeters.first()
        orientationOffsetDeg = settings.orientationOffsetDegrees.first()
        driftCalibrator = settings.loadDriftCalibrator()
        unreliableDistanceM = 0.0
        val points = repository.points(trip.id)
        _tripId.value = trip.id
        _trail.value = points
        if (points.isEmpty()) {
            engine.reset() // resumed before the start anchor was ever placed
            return
        }
        val last = points.last()
        // Distance walked since the most recent ANCHOR — drives the uncertainty circle.
        val lastAnchorIdx = points.indexOfLast { it.source == PointSource.ANCHOR }
        var distM = 0.0
        for (i in maxOf(lastAnchorIdx, 0) until points.size - 1) {
            distM += hypot(
                points[i + 1].xMeters - points[i].xMeters,
                points[i + 1].yMeters - points[i].yMeters,
            )
        }
        val steps = points.count { it.source == PointSource.PDR }
        engine.restore(
            xMeters = last.xMeters,
            yMeters = last.yMeters,
            headingDeg = last.headingDeg ?: Double.NaN,
            distanceSinceAnchorM = distM,
            totalSteps = steps,
        )
        _stepCount.value = steps
    }

    suspend fun stopTrip() {
        val id = _tripId.value ?: return
        repository.finishTrip(id, System.currentTimeMillis())
        settings.saveDriftCalibrator(driftCalibrator)
        _tripId.value = null
        _trail.value = emptyList()
        _stepCount.value = 0
        _lastAnchorName.value = null
        manualMode = false
        unreliableDistanceM = 0.0
        engine.reset()
    }

    /**
     * Retrace mode (safety feature): the stored breadcrumb trail, reversed, from the
     * current position back to the last anchor — or all the way to trip start.
     */
    fun retracePath(toLastAnchorOnly: Boolean): List<TrackPointEntity> {
        val points = _trail.value
        if (points.size < 2) return emptyList()
        val from = if (toLastAnchorOnly) {
            // The most recent anchor STRICTLY BEFORE the end (else nothing to retrace).
            val idx = points.subList(0, points.size - 1).indexOfLast { it.source == PointSource.ANCHOR }
            maxOf(idx, 0)
        } else {
            0
        }
        return points.subList(from, points.size).reversed()
    }
}
