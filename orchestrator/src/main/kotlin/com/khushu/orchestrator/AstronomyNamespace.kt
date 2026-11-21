package com.khushu.orchestrator

import com.khushu.orchestrator.config.AltitudeConventions
import com.khushu.orchestrator.config.LunarPosition
import com.khushu.orchestrator.config.Location
import com.khushu.orchestrator.config.MonthlyMoonTrack
import com.khushu.orchestrator.config.MoonState
import com.khushu.orchestrator.config.RiseSet
import com.khushu.orchestrator.config.Seasons
import com.khushu.orchestrator.config.SolarDayPhases
import com.khushu.orchestrator.config.SolarDayTrack
import com.khushu.orchestrator.config.SolarEvents
import com.khushu.orchestrator.config.SolarPosition
import com.khushu.orchestrator.config.SunTrack
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Astronomy composites — thin read-only delegation over the engine's
 * `AstronomyApi`. Pure computation over explicit arguments: the host passes
 * its stored [Location], the civil [LocalDate]/[ZoneId] and an [Instant];
 * nothing here owns a Clock or caches.
 *
 * This is the only route host code has to ephemeris facts (AGENTS #11): the
 * engine's capability tree is `internal` through [KhushuOrchestrator.engine].
 * AR/sky-path rendering stays in the host — the engine supplies physics.
 */
class AstronomyNamespace internal constructor(private val o: KhushuOrchestrator) {

    val sun = SunNamespace(o)
    val moon = MoonNamespace(o)

    /** Equinoxes and solstices for a civil [year]. */
    fun seasons(year: Int): Seasons = o.engine.astronomy.seasons(year)

    class SunNamespace internal constructor(private val o: KhushuOrchestrator) {

        /** Topocentric solar position at one [instant] (azimuth/altitude in degrees). */
        fun position(location: Location, instant: Instant): SolarPosition =
            o.engine.astronomy.sun.position(location, instant)

        /** Sunrise/sunset for a civil date; nulls mean the event never happens that day (polar). */
        fun riseSet(location: Location, date: LocalDate, zoneId: ZoneId): RiseSet =
            o.engine.astronomy.sun.riseSet(location, date, zoneId)

        /** Daylight length; null on polar days with no rise/set pair. */
        fun dayLength(location: Location, date: LocalDate, zoneId: ZoneId): Duration? =
            o.engine.astronomy.sun.dayLength(location, date, zoneId)

        /** Night length (sunset → next sunrise); null on polar days. */
        fun nightLength(location: Location, date: LocalDate, zoneId: ZoneId): Duration? =
            o.engine.astronomy.sun.nightLength(location, date, zoneId)

        /**
         * Canonical solar day schedule — twilight tiers, blue/golden hour
         * anchors, sunrise, solar noon, sunset — chronological. These are the
         * instants sky-path markers are placed at.
         */
        fun events(location: Location, date: LocalDate, zoneId: ZoneId): SolarEvents =
            o.engine.astronomy.sun.events(location, date, zoneId)

        /** [events] with the named opt-in conventions (karahah / ishtibak al-nujum). */
        fun events(
            location: Location,
            date: LocalDate,
            zoneId: ZoneId,
            conventions: AltitudeConventions,
        ): SolarEvents = o.engine.astronomy.sun.events(location, date, zoneId, conventions)

        /** The full semantic solar day: twilight bands with explicit night ownership. */
        fun phases(location: Location, date: LocalDate, zoneId: ZoneId): SolarDayPhases =
            o.engine.astronomy.sun.phases(location, date, zoneId)

        /** The pinned altitude-threshold table (blue/golden/twilight boundaries). */
        fun conventions(): AltitudeConventions = o.engine.astronomy.sun.conventions()

        /** Per-day rise/set/transit for a whole civil month (one pass). */
        fun track(location: Location, yearMonth: YearMonth, zoneId: ZoneId): List<SolarDayTrack> =
            o.engine.astronomy.sun.track(location, yearMonth, zoneId, includePath = false).days

        /** The same month plus hourly render samples when [includePath] is true. */
        fun track(
            location: Location,
            yearMonth: YearMonth,
            zoneId: ZoneId,
            includePath: Boolean,
        ): SunTrack = o.engine.astronomy.sun.track(location, yearMonth, zoneId, includePath)
    }

    class MoonNamespace internal constructor(private val o: KhushuOrchestrator) {

        /** Topocentric lunar position at one [instant] (azimuth/altitude in degrees). */
        fun position(location: Location, instant: Instant): LunarPosition =
            o.engine.astronomy.moon.position(location, instant)

        /** Phase state: angle, illumination fraction, phase name, bright-limb tilt. */
        fun state(location: Location, instant: Instant): MoonState =
            o.engine.astronomy.moon.state(location, instant)

        /** Moonrise/moonset for a civil date; nulls mean no event that day. */
        fun riseSet(location: Location, date: LocalDate, zoneId: ZoneId): RiseSet =
            o.engine.astronomy.moon.riseSet(location, date, zoneId)

        /**
         * One-pass monthly lunar track: per-day rise/set/transit/phase plus an
         * optional clipped sky path. [pathSamplesPerDay] controls render density.
         */
        fun track(
            location: Location,
            yearMonth: YearMonth,
            zoneId: ZoneId,
            includePath: Boolean = false,
            pathSamplesPerDay: Int = 48,
        ): MonthlyMoonTrack =
            o.engine.astronomy.moon.track(location, yearMonth, zoneId, includePath, pathSamplesPerDay)

        /** Human-readable phase name for a phase angle in degrees. */
        fun phaseName(phaseAngleDeg: Double): String =
            o.engine.astronomy.moon.phaseName(phaseAngleDeg)
    }
}
