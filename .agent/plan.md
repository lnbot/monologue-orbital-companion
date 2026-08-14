# Project Plan

An Android companion app for a Pebble watchface (UUID c4b040f4-ea4c-481c-8050-355006f5804d) that communicates using PebbleKit. The app syncs upcoming alarm and calendar event times (in epoch seconds) to the watchface over the Pebble appmessage protocol when the watch is connected and the watchface is running. It listens for changes to the next alarm and calendar events. It must handle a SynchronizationRequest from the watch. It includes a single-page configuration UI enabling/disabling alarm sync and calendar sync (with per-calendar checkboxes), plus a button to force a sync. It requests needed permissions when the user enables features. Comprehensive logging to the Android system log.

## Project Brief

Generated the MVP project brief as markdown with Features (5 features) and High-Level Tech Stack sections, incorporating all requested feedback.

Key decisions:
- **UI Design Image section omitted** — the `generate_image` tool is not present in my toolset, so the mandatory availability rule requires skipping the section entirely.
- **Calendar sync precision**: collects the next 16 upcoming events or all events within the next 24 hours, whichever is fewer (truncating to the nearest upcoming 16). Sent as a uint32 array (epoch seconds) cast to a uint8 byte array in little-endian order via Message Key "SyncCalendar" (value 112). "SyncAlarm" (value 111) sends a single uint32; "SyncRequest" (value 110) is received from the watchface.
- **Compose Material Adaptive removed** from the tech stack per feedback (standard phone app). **Jetpack Navigation 3 (state-driven)** retained as required.
- No database/persistence layer included since the project description does not require one.

Brief content:

```markdown
# Monologue Orbital Companion — Project Brief (MVP)

An Android companion app for a Pebble watchface (UUID c4b040f4-ea4c-481c-8050-355006f5804d)
that communicates over PebbleKit. It syncs upcoming alarm and calendar event times (in epoch
seconds) to the watchface using the Pebble appmessage protocol, and provides a single-page
configuration UI.

## Features

1. Watchface Appmessage Sync — sync data whenever the watch is connected and the watchface is running.
2. Alarm Sync — send next alarm time (single uint32 epoch second) via Message Key "SyncAlarm" (111).
3. Calendar Sync — next 16 events or next-day (24h) events, whichever fewer; uint32 array cast to uint8 byte array, little-endian, via Message Key "SyncCalendar" (112).
4. SynchronizationRequest Handling — respond to Message Key "SyncRequest" (110) with a fresh sync.
5. Configuration UI — single-page Compose screen to toggle alarm/calendar sync (per-calendar checkboxes), force sync button, permission requests on enable, and comprehensive system-log logging.

## High-Level Tech Stack

| Layer | Technology |
| Java/Kotlin | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Navigation | Jetpack Navigation 3 (state-driven) |
| Async | Kotlin Coroutines |
| Watch Comms | PebbleKit |

Note: No database/persistence layer (not required); adaptive/multi-pane libraries excluded (standard phone app).
```

## Implementation Steps
**Total Duration:** 1h 23m 14s

### Task_1_PebbleKit_Setup: Add PebbleKit dependency, define message key constants (SyncAlarm=111, SyncCalendar=112, SyncRequest=110, UUID=c4b040f4-ea4c-481c-8050-355006f5804d), create PebbleAppMessageService and PebbleCommunicationManager to handle connection checks, sending/receiving AppMessages, and watchface running detection.
- **Status:** COMPLETED
- **Updates:** PebbleKit2 (io.rebble.pebblekit2:client:1.2.0) dependency added. Created PebbleMessageKeys.kt, PebbleListenerService.kt (extends BasePebbleListenerService), PebbleCommunicationManager.kt (wraps DefaultPebbleSender), SyncCoordinator.kt (placeholder). Manifest updated with PebbleListenerService. Build verified: :app:assembleDebug passes. All logging to Logcat tag MonologueCompanion.
- **Acceptance Criteria:**
  - PebbleKit dependency added and builds successfully
  - PebbleAppMessageService correctly filters by UUID and handles SyncRequest
  - PebbleCommunicationManager provides send/recv methods for uint32 and uint8 array data
  - Project compiles without errors
- **Duration:** 43m 49s

### Task_2_Alarm_Sync: Implement alarm sync: read next alarm via AlarmManager.getNextAlarmClock(), register ACTION_NEXT_ALARM_CLOCK_CHANGED broadcast receiver, convert alarm time to uint32 epoch seconds, send via PebbleKit with MessageKey SyncAlarm(111). Handle SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM permission, make sync toggle-able.
- **Status:** COMPLETED
- **Updates:** Created AlarmMonitor.kt: reads AlarmManager.getNextAlarmClock() (epoch seconds), registers ACTION_NEXT_ALARM_CLOCK_CHANGED broadcast receiver, sends 0 when no alarm set. Updated SyncCoordinator.kt: initialize(), setAlarmSyncEnabled(), requestSync() calls syncCurrentAlarm(). All Logcat tag MonologueCompanion. Build verified: :app:assembleDebug passes.
- **Acceptance Criteria:**
  - AlarmManager queries next alarm clock successfully
  - Broadcast receiver captures alarm changes
  - Alarm time sent as uint32 epoch seconds via PebbleKit
  - SCHEDULE_EXACT_ALARM permission handled gracefully
  - Alarm sync can be enabled/disabled
- **Duration:** 7m 45s

### Task_3_Calendar_Sync: Implement calendar sync: query CalendarContract for upcoming events, collect next 16 events or events within 24h (whichever fewer), format as uint8 byte array (little-endian), send via PebbleKit with MessageKey SyncCalendar(112). Handle READ_CALENDAR permission, support per-calendar filtering, make sync toggle-able.
- **Status:** COMPLETED
- **Updates:** Created CalendarInfo.kt and CalendarMonitor.kt (CalendarContract queries within 24h, up to 16 nearest events, per-calendar filtering, ContentObserver auto-sync, READ_CALENDAR permission check). Updated SyncCoordinator.kt (setCalendarSyncEnabled, getAvailableCalendars, setCalendarSelected, requestSync pushes calendar). Added READ_CALENDAR to manifest. Build verified: :app:assembleDebug passes.
- **Acceptance Criteria:**
  - CalendarContract queried successfully for upcoming events
  - Correctly selects min(16 events, events in 24h) with nearest upcoming
  - Events formatted as uint8 byte array in little-endian
  - READ_CALENDAR permission requested and handled
  - Per-calendar toggle support implemented
  - Calendar sync can be enabled/disabled
- **Duration:** 10m 56s

### Task_4_Configuration_UI: Create single-page Jetpack Compose + Material 3 configuration screen: alarm sync toggle, calendar sync toggle (with per-calendar checkboxes when enabled), force sync button. Show dialog when force sync pressed while watch disconnected. Wire all features to the sync engine. Comprehensive Logcat logging throughout.
- **Status:** COMPLETED
- **Updates:** MainActivity.kt updated (initialize/shutdown SyncCoordinator, Navigation 3 host). Created ConfigurationScreen.kt: Material 3 single scrollable screen with Sync Alarm switch, Sync Calendar switch (READ_CALENDAR permission request via ActivityResultContracts), per-calendar checkboxes (shown when enabled), Force Sync button (checks connection+watchface, shows Cannot Sync dialog if not), status section. Logging via MonologueConfigUI tag. Build verified: :app:assembleDebug passes.
- **Acceptance Criteria:**
  - All UI controls render correctly on single screen
  - Alarm toggle enables/disables alarm sync
  - Calendar toggle shows/hides per-calendar checkboxes
  - Force sync button triggers sync request and shows dialog when watch disconnected
  - SyncRequest handler triggers alarm/calendar push depending on enabled toggles
  - Logcat logs all sync events with appropriate detail
  - Build passes, app does not crash
- **Duration:** 6m 53s

### Task_5_Run_Verify: Final build verification: ensure the project builds successfully, all existing tests pass, app runs without crashes. Verify alignment with user requirements and report any critical UI issues.
- **Status:** COMPLETED
- **Updates:** Full verification on small phone emulator: ✅ Launch (no crash), ✅ UI rendering (correct Material 3 single-page layout, no layout issues), ✅ Alarm and Calendar toggle behavior, ✅ READ_CALENDAR permission request flow, ✅ Force Sync dialog "Cannot Sync" appears when watch not connected (key requirement confirmed), ✅ Logcat logging operational (MonologueCompanion + MonologueConfigUI tags), ✅ No crashes, ANRs, or exceptions across all UI flows.
- **Acceptance Criteria:**
  - Project builds successfully
  - All existing tests pass
  - App does not crash on launch
  - All features (alarm sync, calendar sync, UI toggles, force sync) are functional
- **Duration:** 13m 51s

