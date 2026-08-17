# Monologue Orbital Companion — Development Notes & Architectural Context

> Handoff document for future agents. This explains WHY the app is implemented the way it is,
> the communication protocol, the PebbleKit saga, and the pitfalls we hit. Read this before
> modifying the codebase.

---

## 1. What This App Is

An Android companion app for a Pebble watchface called "Monologue Orbital"
(UUID `c4b040f4-ea4c-481c-8050-355006f5804d`). It pushes upcoming **alarm**, **calendar event**,
and **timer** times (epoch seconds) to the watchface over the Pebble AppMessage protocol,
whenever the watch is connected and any sync feature is enabled.

- App name: **Monologue Orbital Companion**
- Package / applicationId: `com.overstuffed.monologue_orbital_companion`
- SDK: `compileSdk = 37`, `targetSdk = 37`, `minSdk = 26`
- UI: Jetpack Compose + Material 3 (single configuration page)
- Language: Kotlin, coroutines + StateFlow
- Build: AGP 9.x (version catalog in `gradle/libs.versions.toml`)

### AppMessage keys (defined in `PebbleMessageKeys.kt`)

| Key | Value | Direction | Payload |
|-----|-------|-----------|---------|
| `KEY_SYNC_REQUEST` | 110 | watch → phone | none (triggers a re-sync) |
| `KEY_SYNC_ALARM` | 111 | phone → watch | single uint32 epoch seconds |
| `KEY_SYNC_CALENDAR` | 112 | phone → watch | uint32[] epoch seconds, cast to uint8 byte array, **little-endian** (4 bytes per value) |
| `KEY_SYNC_TIMER` | 113 | phone → watch | single uint32 epoch seconds |

The watchface may request a sync at any time by sending key 110; the app re-pushes whatever
features are enabled.

---

## 2. Architecture / File Map

| File | Responsibility |
|------|----------------|
| `MonologueApplication.kt` | Application subclass. Calls `SyncCoordinator.initialize(this)` once for the whole process. Decouples sync from the Activity lifecycle. |
| `MainActivity.kt` | Single activity hosting the Compose UI. No sync lifecycle code (that lives in the Application + Service now). |
| `ConfigurationScreen.kt` | The only screen. Toggles (alarm/calendar/timer), per-calendar checkboxes, force-sync button, notification-access dialog, status section. |
| `SyncCoordinator.kt` | Singleton facade. Owns monitors + `PebbleCommunicationManager`, enable/disable flags (StateFlows), persistence wiring, foreground-service start/stop (`updateServiceState()`), `requestSync()` (responds to watch SyncRequest AND UI force-sync). |
| `PebbleCommunicationManager.kt` | All Pebble communication: send alarm/calendar/timer, ACK/NACK receivers, 5s timeout + retry, per-channel transmit-status StateFlows, `isWatchConnected()` (PK2 + legacy fallback). |
| `PebbleListenerService.kt` | Runtime BroadcastReceiver for `com.getpebble.action.app.RECEIVE` (watch → phone data). Detects key 110 → `SyncCoordinator.onWatchSyncRequested()`. |
| `AlarmMonitor.kt` | Reads `AlarmManager.getNextAlarmClock()`; listens for `ACTION_NEXT_ALARM_CLOCK_CHANGED`; sends alarm epoch (0 when no alarm). |
| `CalendarMonitor.kt` | Queries `CalendarContract` for the next day's events (max 16, nearest first); ContentObserver on events/calendars; per-calendar filtering; sends timestamp list. |
| `TimerMonitor.kt` | Reads timer state from `ClockTimerNotificationListener`; sends timer end epoch (0 when none). |
| `ClockTimerNotificationListener.kt` | `NotificationListenerService` that parses "Timer" notifications from common clock apps (Google/Samsung/OnePlus/AOSP). Exposes `TimerCallback` + `activeFinishEpochSeconds()`. |
| `PebbleMessageKeys.kt` | Constants: watchface UUID, message-key integers, `encodeEpochsAsUint8LittleEndian()`, LOG_TAG. |
| `SettingsRepository.kt` | DataStore Preferences: `alarmSyncEnabled`, `calendarSyncEnabled`, `timerSyncEnabled`, `selectedCalendarIds`. |
| `SyncService.kt` | Foreground service (type `connectedDevice`), START_STICKY, low-importance notification. Runs only while ≥1 sync feature is enabled. |
| `CalendarInfo.kt` | Data class (calendarId, displayName, accountName, selected). |

### Send path (all three channels share it)

1. Monitor fires (change detected, or requestSync).
2. `SyncCoordinator` calls `PebbleCommunicationManager.sendAlarmSync/sendCalendarSync/sendTimerSync`.
3. The manager arms a 5s timeout **before** broadcasting (ordering matters — see §6).
4. Broadcasts via legacy `PebbleKit.sendDataToPebbleWithTransactionId(...)` (fire-and-forget intent).
5. Status set optimistically to `Sent (waiting ACK)`.
6. ACK/NACK arrive via runtime BroadcastReceivers (RECEIVER_EXPORTED on API 33+).
7. ACK → `Success (ACK)` + records `lastSuccessfulSyncTime`; NACK → `Failed (NACK[...])` (success time untouched); timeout → retry (max 3 attempts), then `Failed (timeout)`.

---

## 3. The PebbleKit Saga (read this before touching communication code)

This is the most important section. We went through three phases.

### 3.1 Started with PebbleKitAndroid2 (`io.rebble.pebblekit2:client:1.2.0`)

Initial implementation used the modern community-maintained SDK:
- Receiving: `BasePebbleListenerService` (bound service).
- Sending: `DefaultPebbleSender.sendDataToPebble()` with `PebbleDictionaryItem` map.
- Connectivity: `DefaultPebbleInfoRetriever` (content provider).

**Why we abandoned it:** On real hardware with the Core Devices Pebble app
(`coredevices.coreapp`), every send returned **`FailedDifferentAppOpen`**, and SyncRequest
messages were never received.

Root causes identified:
- PK2's sender is a relay: Core Devices rejects sends with `FailedDifferentAppOpen` unless its
  tracked "active app" (`content://coredevices.coreapp.pebblekit/activeApp/<serial>`) matches our
  UUID — but Core's `startAppOnTheWatch` only flips that record optimistically and does NOT
  actually foreground the watchface (verified on-device; also documented by the
  bquelhas/pebble-steer project).
- `BasePebbleListenerService` silently drops incoming messages via a caller-package check against
  a DataStore-selected app; if the selection is empty/mismatched, messages vanish with only a
  warning log.

### 3.2 Switched to legacy PebbleKit (`com.getpebble:pebblekit:4.0.1@aar`)

The Steer project (`https://github.com/bquelhas/pebble-steer`) proved the **classic broadcast path**
works on Core Devices: `PebbleKit.sendDataToPebble()` broadcasts
`com.getpebble.action.app.SEND`, which Core forwards **regardless of the active-app gate**.
The classic path has NO active-app check.

Current architecture therefore uses classic PebbleKit for **all sending and receiving**:
- Send: `PebbleKit.sendDataToPebbleWithTransactionId(context, WATCHFACE_UUID, dict, txnId)`.
- Receive: a manually-registered runtime BroadcastReceiver for `com.getpebble.action.app.RECEIVE`,
  registered with **`RECEIVER_EXPORTED` on API 33+** (the library's own
  `registerReceivedDataHandler` omits the flag and silently fails on Android 13+).
- Launch: `PebbleKit.startAppOnPebble(context, WATCHFACE_UUID)`.
- The `PebbleKit` 4.0.1 AAR requires the Sonatype OSS repo in `settings.gradle.kts`.

### 3.3 Both libraries now coexist

`io.rebble.pebblekit2:client:1.2.0` was re-added **only** for `isWatchConnected()`, because its
`DefaultPebbleInfoRetriever.getConnectedWatches()` reliably queries Core Devices'
`content://<package>.pebblekit/connectedWatches` provider.

Conflicts: none — package names differ (`com.getpebble.android.kit.*` vs `io.rebble.pebblekit2.*`),
both AARs have minimal manifests, transitive deps already align. **Do NOT enable Jetifier**
(it breaks PK2); it is NOT enabled in `gradle.properties`.

---

## 4. isWatchConnected() — why it's layered

`PebbleKit.isWatchConnected()` in the 4.0.1 AAR is **broken on Android 14+ (API 34+)**:
it historically called `context.registerReceiver(null, intentFilter)` as a probe, which throws
`IllegalArgumentException` on API 34+ (null receiver is disallowed). We did NOT use it.

Note: the upstream pebble-android-sdk master branch replaced that with a ContentProvider query
(`content://com.getpebble.android.provider.basalt/state` then `.../state`) — but the 4.0.1 AAR
predates that, so we ported the *concept* as a fallback.

Current `isWatchConnected(context)` implementation (in `PebbleCommunicationManager.kt`):
1. **Primary:** `DefaultPebbleInfoRetriever(context).getConnectedWatches().first()` (PK2).
   Non-empty list → connected. Works via Core's `...pebblekit/connectedWatches` provider.
2. **Fallback:** legacy basalt → primary state-provider query (`content://com.getpebble.android.provider.basalt/state`,
   `content://com.getpebble.android.provider/state`, column index 0 == 1 → connected).
3. Everything wrapped in try/catch; logs which path succeeded; never crashes.

> **Field-proven fact:** The legacy fallback (basalt → primary state-provider query to
> `content://com.getpebble.android.provider.basalt/state` and
> `content://com.getpebble.android.provider/state`) **always returned false on a real Android 16
> phone** — it never worked in practice, even though the query pattern looks correct per the
> upstream source. The effectively-working path is the **PK2 path**
> (`DefaultPebbleInfoRetriever.getConnectedWatches()` via
> `content://coredevices.coreapp.pebblekit/connectedWatches`). Treat PK2 as the primary/only
> reliable connectivity source; the legacy fallback is best-effort defense in depth at most.
> If PK2 is ever removed, `isWatchConnected()` will need a different reliable mechanism (the
> legacy provider query is not a viable fallback on modern Android/Core Devices).

The UI now checks `isWatchConnected()` on the **Force Sync** button: connected → sync; not
connected → "Cannot Sync" dialog with OK dismiss. (Previously the check was removed entirely
because it always returned false; it was restored once the check worked.)

---

## 5. Alarm / Calendar / Timer specifics

### Alarm
- Read-only via `AlarmManager.getNextAlarmClock()` → `AlarmClockInfo.triggerTime / 1000` epoch sec.
- **No permission needed to READ the next alarm** (we never schedule).
- Listen: `ACTION_NEXT_ALARM_CLOCK_CHANGED` (runtime receiver, `RECEIVER_NOT_EXPORTED` on 33+ —
  it's a system broadcast).
- No alarm set → send `0` (watch clears stale display).

### Calendar
- Query `CalendarContract.Events` for `dtstart >= now AND dtstart <= now+24h
  AND calendar_id IN (selected) **AND allDay != 1**` (all-day events are excluded — user request).
- Sort `dtstart ASC`; cap at **16 events** (nearest first) — per spec: "next 16 events or events
  for the next day, whichever is fewer".
- Encode: each epoch second (lower 32 bits) → 4 bytes **little-endian**; concatenated byte array
  → key 112. Helper: `encodeEpochsAsUint8LittleEndian()` in `PebbleMessageKeys.kt`.
- Watch for changes via `ContentObserver` on events + calendars ContentUris.
- Permission: `READ_CALENDAR` (dangerous), requested when the user enables calendar sync.
- Default selection: all visible calendars selected when nothing persisted.

### Timer
- `ClockTimerNotificationListener` (NotificationListenerService) parses clock-app "Timer"
  notifications; computes `finishEpochSeconds = now + remaining`.
- Requires **notification access** (user must enable via
  `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`).
- Multiple timer apps: earliest finish wins; app must never crash in that case (single-timer
  assumption is safe).
- No timer → send `0`.
- Key 113, single uint32, shares ACK/NACK + timeout machinery.

---

## 6. ACK / NACK — the feedback loop

Classic sends are fire-and-forget broadcasts, so we built our own feedback:

- Broadcast actions (from pebble-android-sdk `Constants.java`):
  - `INTENT_APP_SEND` = `com.getpebble.action.app.SEND`
  - `INTENT_APP_RECEIVE` = `com.getpebble.action.app.RECEIVE` (watch→phone data)
  - `INTENT_APP_RECEIVE_ACK` = `com.getpebble.action.app.RECEIVE_ACK`
  - `INTENT_APP_RECEIVE_NACK` = `com.getpebble.action.app.RECEIVE_NACK`
  - **Important:** ACK/NACK from a SENT message arrive on `RECEIVE_ACK`/`RECEIVE_NACK`, NOT on
    `ACK`/`NACK` (those are the reverse channel, acknowledging messages received FROM the watch).
- Extras: `"uuid"` (Serializable `java.util.UUID`) and `"transaction_id"` (int). **No error code
  extra exists in the legacy protocol** — NACKs carry only the transaction id. Code that probes
  for error extras is defensive only; a NACK essentially means "sent but rejected/never received".
- Per-channel transaction IDs: **1 = ALARM, 2 = CALENDAR, 3 = TIMER** — used to route
  ACK/NACK/timeout to the right channel's status StateFlow.
- Receivers must be registered with `RECEIVER_EXPORTED` on API 33+.

### Timeout + retry
- 5s timeout per attempt (`ACK_TIMEOUT_MS = 5_000`), `MAX_SEND_ATTEMPTS = 3` (initial + 2 retries).
- Retry happens **on timeout only** — NOT on NACK (user decision).
- The timer is armed **before** broadcasting (a reply can never beat the timer — eliminates the
  false-timeout-after-ack race).
- Single-flight per channel: a new send cancels the old pending timer; identity check prevents
  stale timers from firing.
- Lock ordering: `sendMutex` → `timeoutMutex` (retry is launched outside `timeoutMutex` to avoid
  inversion).

### Transmit-status StateFlows (shown on the config page)
- Per channel: `ChannelSyncStatus(lastTransmitStatus: String?, lastSuccessfulSyncTime: Long?)`.
- `alarmSyncStatus`, `calendarSyncStatus`, `timerSyncStatus` — the UI's Status section displays
  "Last transmit" + "Last successful sync" for each, plus "Last sync request from watch"
  (from `SyncCoordinator.lastSyncRequestTime`, set only when the WATCH requests a sync).

---

## 7. Foreground Service (background sync)

- `SyncService` — type **`connectedDevice`** (NOT `dataSync`: Android 15+ caps `dataSync` at
  6h/day; `connectedDevice` has no such limit and is the correct type for a wearable companion).
- Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
  `POST_NOTIFICATIONS` (API 33+ runtime), `CHANGE_NETWORK_STATE` (normal — satisfies the
  qualifying-permission rule for `connectedDevice` without a BT runtime prompt).
- Runs **only while any of alarm/calendar/timer sync is enabled**
  (`SyncCoordinator.updateServiceState()`).
- `START_STICKY`; notification (low importance) taps back into the app.
- `SyncCoordinator` is initialized in `MonologueApplication` (process-scoped), NOT in the
  Activity — monitoring survives Activity death. The service does NOT own shutdown; monitors are
  gated by their enabled flags.

## 8. Persistence

- DataStore Preferences (`settings.preferences_pb`): alarm/calendar/timer enable flags +
  `selectedCalendarIds` (StringSet).
- Loaded asynchronously on startup in `initialize()`; every toggle persists.
- Default: all calendars selected if nothing stored; empty stored set means none.

## 9. UI / UX details

- Single scrollable Material 3 screen ("Monologue Orbital Companion").
- Sections: Sync Preferences (alarm / calendar / timer switches), Calendars (per-calendar
  checkboxes, visible only when calendar sync on), Sync Now button (+ Refresh Calendars),
  Status section.
- Notification permissions:
  - `POST_NOTIFICATIONS` (posting) requested on first sync enable (API 33+); grant re-posts the
    FGS notification; denial shows a Snackbar (permanent denial → "Open Settings" action).
  - Notification **access** (reading) is a Settings-level toggle, not a runtime permission —
    dialog with "Open Settings" when enabling timer sync without access.
- Force Sync: checks `isWatchConnected()`; if not connected shows "Cannot Sync" dialog; if
  connected calls `SyncCoordinator.requestSync()`.

## 10. Gotchas & Lessons Learned (cheat sheet)

1. `registerReceiver(null, ...)` throws on API 34+ → don't rely on probe-based connectivity.
2. PebbleKit2's `FailedDifferentAppOpen` with Core Devices is a real, unfixable-on-our-side
   issue → use the classic broadcast path for sends.
3. On API 33+, Pebble broadcasts NEED explicit `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED`
   flags; the legacy lib's helpers omit them and silently fail.
4. The watch→phone confirmation actions are `RECEIVE_ACK`/`RECEIVE_NACK` (not `ACK`/`NACK`).
5. The legacy NACK carries **no error code**.
6. Use `connectedDevice` FGS, not `dataSync` (Android 15+ 6h/day cap).
7. Don't enable Jetifier (breaks PebbleKit2); leave `gradle.properties` alone.
8. `PebbleDictionary` keys are `Int` in legacy PebbleKit (not `UInt` like PK2).
9. Send `0` as the "clear/no value" sentinel (alarm & timer) so the watch clears stale UI.
10. The classic send is fire-and-forget → implement feedback via transaction-id-keyed
    ACK/NACK receivers + timeout/retry.
11. All-day events excluded from calendar sync (`allDay != 1`).
12. Arm timeouts before broadcasting; use identity checks + mutexes to avoid stale-timer races.
13. The legacy PebbleKit state-provider query (`content://com.getpebble.android.provider[.basalt]/state`)
    always returns false on a real Android 16 phone with Core Devices — do NOT rely on it for
    connectivity; use PebbleKit2's `getConnectedWatches()` (via `coredevices.coreapp.pebblekit` provider).

## 11. Current status

- Builds and runs; verified on a Pixel 5 phone emulator (UI, toggles, dialogs, no crashes) and
  on real hardware with a Pebble (alarm + calendar sync + ACK feedback confirmed working).
- Timer sync added; needs real-hardware validation of notification parsing + key 113 delivery.
- Open items if revisited:
  - Auto-activate timer sync after notification access is granted (currently the dialog asks the
    user to re-toggle).
  - `isWatchfaceRunning()` was removed (classic path has no gate); could be re-added as a
    content-provider query if the UI ever needs it for a "watchface not running" hint.
  - `SyncCoordinator.shutdown()` exists for cleanup but is not called on Activity destroy
    (intentional — app-scoped coordinator).

## 12. Authoritative references

- Legacy PebbleKit source (use this as the source of truth for classic API behavior):
  `https://github.com/pebble/pebble-android-sdk/` (PebbleKit/PebbleKit/src/main/java/com/getpebble/android/kit/)
- PebbleKit2: `https://github.com/pebble-dev/PebbleKitAndroid2`
- Steer (working classic-PebbleKit companion, same Core Devices pitfalls):
  `https://github.com/bquelhas/pebble-steer`