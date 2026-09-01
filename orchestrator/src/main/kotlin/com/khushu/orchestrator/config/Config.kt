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

typealias DateLine = com.khushu.engine.calendar.DateLine
typealias DualDate = com.khushu.engine.calendar.DualDate
typealias DualDates = com.khushu.engine.calendar.DualDates

// ── geo ────────────────────────────────────────────────────────────────────
typealias Location = com.khushu.engine.core.geo.Location
typealias Latitude = com.khushu.engine.core.geo.Latitude
typealias Longitude = com.khushu.engine.core.geo.Longitude
typealias AltitudeMeters = com.khushu.engine.core.geo.AltitudeMeters
