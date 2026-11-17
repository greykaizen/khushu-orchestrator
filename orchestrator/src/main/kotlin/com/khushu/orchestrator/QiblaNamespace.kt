package com.khushu.orchestrator

import com.khushu.orchestrator.config.BearingAndDistance
import com.khushu.orchestrator.config.Location
import com.khushu.orchestrator.config.QiblaBearing
import com.khushu.orchestrator.config.SunQiblaRelation
import java.time.Instant

/**
 * Qibla geometry composites — thin read-only delegation over the engine's
 * `QiblaApi`. Pure computation over explicit [Location]/[Instant] parameters;
 * no Clock, no caching, no Android. Location is passed by the host (there is no
 * geo/location namespace in this artifact), and every result type is re-exported
 * from [com.khushu.orchestrator.config].
 */
class QiblaNamespace internal constructor(private val o: KhushuOrchestrator) {

    /** Great-circle bearing from north toward the Kaaba, plus the distance to it. */
    fun bearing(location: Location): QiblaBearing = o.engine.qibla.bearing(location)

    /** Generic great-circle initial bearing + distance between two locations. */
    fun bearingBetween(from: Location, to: Location): BearingAndDistance =
        o.engine.qibla.bearingBetween(from, to)

    /**
     * Signed angle between the sun's azimuth and the local qibla bearing at
     * [instant] ([−180, 180]); plus whether they align within [toleranceDeg].
     */
    fun relativeSunAngle(
        location: Location,
        instant: Instant,
        toleranceDeg: Double = 2.0,
    ): SunQiblaRelation = o.engine.qibla.relativeSunAngle(location, instant, toleranceDeg)
}
