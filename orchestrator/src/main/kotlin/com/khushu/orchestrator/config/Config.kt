package com.khushu.orchestrator.config
/**
 * Host-facing re-exports of the engine's parameter/configuration types —
 * zero-cost typealiases so host settings code never imports
 * `com.khushu.engine.*` directly (the consumption contract: orchestrator is
 * the only surface). These are inert data carriers — constructing them never
 * computes anything.
 */

// ── prayer configuration ───────────────────────────────────────────────────
typealias PrayerConfiguration = com.khushu.engine.prayer.PrayerConfiguration
typealias PrayerOffsets = com.khushu.engine.prayer.PrayerOffsets
typealias Madhab = com.khushu.engine.prayer.Madhab
typealias Convention = com.khushu.engine.prayer.Convention
typealias HighLatitudeRule = com.khushu.engine.prayer.HighLatitudeRule
typealias RoundingPolicy = com.khushu.engine.prayer.RoundingPolicy
typealias Shafaq = com.khushu.engine.prayer.Shafaq

// ── calendar configuration ─────────────────────────────────────────────────
typealias CalendarConfiguration = com.khushu.engine.calendar.CalendarConfiguration
typealias CalendarParams = com.khushu.engine.calendar.CalendarParams
typealias CivilCalendarType = com.khushu.engine.calendar.CivilCalendarType

// NOTE: Kotlin cannot dereference nested classes through a typealias
// (`Alias.Nested` is unsupported by design — verified 2.3.21/2.4.10), so the
// sealed DateLine variants and CalendarConfiguration.Side are re-exported as
// their own top-level aliases.
typealias DateLine = com.khushu.engine.calendar.DateLine
typealias DateLineHijri = com.khushu.engine.calendar.DateLine.Hijri
typealias DateLineGregorian = com.khushu.engine.calendar.DateLine.Gregorian
typealias DateLineRegional = com.khushu.engine.calendar.DateLine.Regional
typealias CalendarSide = com.khushu.engine.calendar.CalendarConfiguration.Side
typealias HijriDate = com.khushu.engine.calendar.HijriDate

// ── prayer list ─────────────────────────────────────────────────────────────
// Nested engine classes each need their OWN top-level alias — Kotlin cannot
// dereference a nested class through a typealias (see the note above).
typealias PrayerTimesResult = com.khushu.engine.prayer.PrayerTimesResult
typealias PrayerStatus = com.khushu.engine.prayer.PrayerStatus
typealias PrayerKind = com.khushu.engine.prayer.PrayerStatus.Prayer
typealias PrayerEntry = com.khushu.engine.prayer.Prayer.Entry
typealias PrayerOccurrence = com.khushu.engine.prayer.Prayer.Occurrence
typealias PrayerOccasionCategory = com.khushu.engine.prayer.Prayer.OccasionCategory
typealias PrayerNightDivisions = com.khushu.engine.prayer.Prayer.NightDivisions
typealias TahajjudWindow = com.khushu.engine.prayer.TahajjudWindow
typealias PrayerJumuah = com.khushu.engine.prayer.Prayer.Jumuah
typealias PrayerForbiddenWindows = com.khushu.engine.prayer.Prayer.ForbiddenWindows
typealias PrayerFastingFacts = com.khushu.engine.prayer.Prayer.FastingFacts
typealias PrayerTravelFacts = com.khushu.engine.prayer.Prayer.TravelFacts
typealias PrayerStreakStats = com.khushu.engine.prayer.Prayer.StreakStats
typealias PrayerLogRecord = com.khushu.engine.prayer.Prayer.PrayerLogRecord
typealias DualDate = com.khushu.engine.calendar.DualDate
typealias DualDates = com.khushu.engine.calendar.DualDates

// ── geo ────────────────────────────────────────────────────────────────────
typealias Location = com.khushu.engine.core.geo.Location
typealias Latitude = com.khushu.engine.core.geo.Latitude
typealias Longitude = com.khushu.engine.core.geo.Longitude
typealias AltitudeMeters = com.khushu.engine.core.geo.AltitudeMeters

// ── dimensional units ──────────────────────────────────────────────────────
typealias Degrees = com.khushu.engine.core.units.Degrees
typealias Kilometers = com.khushu.engine.core.units.Kilometers

// ── qibla results ──────────────────────────────────────────────────────────
typealias QiblaBearing = com.khushu.engine.qibla.QiblaBearing
typealias BearingAndDistance = com.khushu.engine.qibla.BearingAndDistance
typealias SunQiblaRelation = com.khushu.engine.qibla.SunQiblaRelation

// ── qada (excused-range) configuration + results ───────────────────────────
// Qada's types are nested inside `object Qada`; Kotlin cannot dereference a
// nested class through a typealias, so each gets its own top-level alias.
typealias QadaSchool = com.khushu.engine.prayer.Qada.QadaSchool
typealias BoundaryPairing = com.khushu.engine.prayer.Qada.BoundaryPairing
typealias QadaConfig = com.khushu.engine.prayer.Qada.QadaConfig
typealias FiqhPeriod = com.khushu.engine.prayer.Qada.FiqhPeriod
typealias FiqhPeriodType = com.khushu.engine.prayer.Qada.FiqhPeriodType
typealias PrayerInstance = com.khushu.engine.prayer.Qada.PrayerInstance
typealias BoundaryRuling = com.khushu.engine.prayer.Qada.BoundaryRuling
typealias QadaReport = com.khushu.engine.prayer.Qada.QadaReport
typealias DaySchedule = com.khushu.engine.prayer.Qada.DaySchedule
