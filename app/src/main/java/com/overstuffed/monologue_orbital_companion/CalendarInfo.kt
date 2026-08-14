package com.overstuffed.monologue_orbital_companion

/**
 * Describes a single system calendar that the user can enable or disable for syncing to the
 * Monologue Orbital watchface.
 *
 * @param calendarId the calendar's ID in [android.provider.CalendarContract.Calendars].
 * @param displayName the user-facing name of the calendar.
 * @param accountName the owning account (e.g. a Google account) if known, otherwise `null`.
 * @param selected whether this calendar currently contributes events to the watch sync.
 */
data class CalendarInfo(
    val calendarId: Long,
    val displayName: String,
    val accountName: String?,
    val selected: Boolean,
)
