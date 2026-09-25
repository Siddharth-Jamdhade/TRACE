# TRACE — Implementation Walkthrough

## What Was Built

18 files changed (10 new, 8 modified) across the full prototype pipeline.

---

## Architecture — How Data Flows

```
Phone Sensors
 ├── Accelerometer (SensorManager)
 │     └── jerk (Δacceleration) ──────────────────────────────────┐
 ├── Microphone (MediaRecorder, polled 150ms)                      │
 │     └── maxAmplitude ───────────────────────────────────────────┤
 └── Camera (CameraX ImageAnalysis, grayscale Y-plane)             │
       └── frame-difference ratio ───────────────────────────────── ▼
                                                           toConfidence(raw, threshold, max)
                                                                    │
                                                                    ▼
                                                           Observation(source, confidence, timestamp)
                                                                    │
                                                           EventExtractor.extract()
                                                                    │ (threshold rules)
                                                           EventCandidate type string
                                                                    │
                                                           Cooldown gate (1 sec per type)
                                                                    │
                                                           Room DB: insert base Event (gets ID)
                                                                    │
                                                           FusionEngine.buildEnrichedEvent()
                                                           (2-sec window, per-sensor confs, CONFIRMED/UNCONFIRMED)
                                                                    │
                                                           HashChain.computeHash() (SHA-256)
                                                                    │
                                                           Room DB: update enriched+hashed Event
                                                                    │
                                                           saveEvidenceClip() → copy temp_audio.3gp
                                                                    │
                                                           Room DB: update evidenceClipPath
                                                                    │
                                                           Retroactive status update for window events
```

---

## Files Changed

### New Business Logic

| File | Purpose |
|------|---------|
| [`FusionEngine.kt`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/java/com/example/trace/FusionEngine.kt) | Counts how many independent sensor sources agreed at >60% within 2 seconds. 2+ → CONFIRMED, 1 → UNCONFIRMED, 0 → REJECTED. Also fills per-sensor confidence fields. |
| [`HashChain.kt`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/java/com/example/trace/HashChain.kt) | SHA-256 tamper-evidence. Each event's hash includes its `id|type|timestamp|source|confidence` plus the previous event's hash. `status` is excluded so human Confirm/Reject doesn't break the chain. |
| [`QueryEngine.kt`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/java/com/example/trace/QueryEngine.kt) | Keyword-based natural language query engine. Routes "before", "after", "why", "last", "how many", "confirmed", "fall", "alarm", "show" to the correct list-filter function. No LLM required. |

### New UI Layer

| File | Purpose |
|------|---------|
| [`EventAdapter.kt`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/java/com/example/trace/EventAdapter.kt) | RecyclerView `ListAdapter` with DiffUtil. Colours status strip: green=CONFIRMED, amber=UNCONFIRMED, red=REJECTED. |
| [`TimelineActivity.kt`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/java/com/example/trace/TimelineActivity.kt) | Shows all events newest-first. Query box at top queries `QueryEngine`. Tap row → EventDetailActivity. Options menu: Refresh / Clear All. |
| [`EventDetailActivity.kt`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/java/com/example/trace/EventDetailActivity.kt) | Full evidence breakdown: status banner, 3× ProgressBar sensor confidence, QueryEngine explanation text, Confirm/Reject buttons, evidence clip playback (MediaPlayer), SHA-256 integrity line. |

### New Layouts

| File | Purpose |
|------|---------|
| [`activity_timeline.xml`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/res/layout/activity_timeline.xml) | Dark-theme screen: query EditText + Ask button + answer TextView + RecyclerView |
| [`item_event.xml`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/res/layout/item_event.xml) | MaterialCardView row: coloured status strip + type/timestamp/source labels + status badge |
| [`activity_event_detail.xml`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/res/layout/activity_event_detail.xml) | ScrollView with status banner, 3 ProgressBars, explanation, Confirm/Reject buttons, evidence button, hash integrity line |

### Modified Files

| File | Changes |
|------|---------|
| [`Event.kt`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/java/com/example/trace/Event.kt) | +6 nullable fields: `cameraConfidence`, `audioConfidence`, `motionConfidence`, `evidenceClipPath`, `hash`, `previousHash` |
| [`EventDao.kt`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/java/com/example/trace/EventDao.kt) | +`@Update update()`, +`getEventById()`, +`getChainTipForSession()`, +`getEventsInWindowForSession()` |
| [`TraceDatabase.kt`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/java/com/example/trace/TraceDatabase.kt) | Version 2→3, explicit `MIGRATION_2_3` — destructive fallback removed |
| [`EventExtractor.kt`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/java/com/example/trace/EventExtractor.kt) | Added `"camera"` source: `>0.75` → `"object_falls"`, `>0.50` → `"object_moves"` |
| [`MainActivity.kt`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/java/com/example/trace/MainActivity.kt) | Full refactor: ViewBinding, ImageAnalysis frame-differencing, FusionEngine, HashChain, evidence clip saving, ConcurrentHashMap cooldown, `onPause`/`onResume` sensor lifecycle fix |
| [`activity_main.xml`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/res/layout/activity_main.xml) | Live view: green `TAG INCIDENT` button (operator-asserted evidence) + `Analyse Video` |
| [`app/build.gradle.kts`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/build.gradle.kts) | `viewBinding = true`, +`recyclerview:1.3.2` |
| [`AndroidManifest.xml`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/AndroidManifest.xml) | Registered `TimelineActivity` and `EventDetailActivity` |

---

## What You Need to Do

### Step 1 — Build the Project
1. Open Android Studio → **File → Sync Project with Gradle Files**
2. Wait for Gradle sync to complete
3. Press the green ▶ **Run** button with your phone plugged in
4. The app will request Camera + Microphone permissions — **tap Allow** on the phone

### Step 2 — Tune the Thresholds (critical before demo)
Open Logcat in Android Studio, filter tag `TRACE`. Then:

| Test | What to observe |
|------|----------------|
| Phone lying still on table | `RAW jerk` should stay < `16.25` (MOTION_THRESHOLD) |
| Hard tap or drop | `RAW jerk` should peak well above `16.25` |
| Quiet room | `RAW amplitude` should stay < `1500` (AUDIO_THRESHOLD) |
| Loud clap / buzzer | `RAW amplitude` should peak above `1500` |
| Wave hand in front of camera slowly | `RAW frame change` should be ~0.05–0.15 |
| Sudden movement past camera | `RAW frame change` should spike > 0.05 |

**Adjust these 5 constants in [`MainActivity.kt`](file:///c:/Users/SIDDHARTH/AndroidStudioProjects/TRACE/app/src/main/java/com/example/trace/MainActivity.kt) lines 72–79** to match your actual device readings.

### Step 3 — Run the Demo Sequence
1. **Main screen**: preview shows, chip reads `IDLE`, status line says *nothing is being recorded*. That is correct — TRACE does not capture until you arm it.
2. **Tap START SESSION** → type a nature of work (try `door inspection`) → watch the sensor chips re-select themselves → **Start session**. Chip turns into `● REC 00:04`
3. **Shake the phone hard** → "EVENT SAVED: type=impact" in Logcat
4. **Clap near the phone** → "EVENT SAVED: type=alarm" in Logcat
5. **Wave hand in front of camera** → "EVENT SAVED: type=object_moves" in Logcat
6. **Tap TAG INCIDENT** → choose "Object fell" → a violet `MANUAL` entry is added with its own audio clip.
   This is the one detection TRACE cannot make for itself: the sensors prove *that* something moved, only you can say *what* it was.
7. **Tap END SESSION** → confirm → capture stops and the status line shows the session totals
8. **Tap the sessions icon (top-right)** → see events listed in reverse time order
9. **Type in query box**: "what happened before the alarm?" → get text answer ("list manual tags" also works)
10. **Tap an event** → see confidence bars, Confirm / Reject buttons
11. **Tap Confirm** → status badge turns green immediately
12. **Tap "Play Evidence Clip"** → should play the audio captured at that moment

**Arming variants worth demoing**
- Type `shelf restock` → movement/camera/audio/linear, magnetometer off.
- Type something meaningless (`banana`) → *every* sensor stays on, and the sheet says why: an unrecognised description must never guess narrow.
- Turn the camera chip off, then start → the viewfinder is replaced by the list of sensors that are capturing, and the camera is never bound.
- Tap START SESSION twice quickly in a row → one session, not two.
- Watch the **SESSION LOG** block in the bottom quarter: `session started · N sensor(s) · "…"`, one line per recorded event with its icon and fusion status, your tags in violet, capture errors in red — newest at the bottom. A sensor the device lacks is named up front (`not on this device: …`) instead of being a silent gap.
- Leave the app and come back mid-session → the tail is rebuilt from the chain's last events plus a `session resumed` line, so the screen never claims nothing happened while it was away.

### Step 4 — Verify Fusion Behaviour
- **Single action** (shake only, no clap) → status should be `UNCONFIRMED`
- **Combined action** (hard shake + loud clap within 2 seconds) → status should upgrade to `CONFIRMED`
- Check the "Integrity: VALID" line in Event Detail — should show green

---

## Hardware You Handle (Not in Code)

| Item | Purpose |
|------|---------|
| **Physical drop rig** | Cardboard/wood table from which you nudge an object |
| **Test object** | Small box or ball that makes a clear impact sound when it falls |
| **Buzzer / alarm** | Phone alarm, physical piezo buzzer, or a second phone playing a loud sound |
| **DC motor / fan** | Creates "abnormal mechanical sound" — battery-powered or Arduino-driven |
| **Threshold tuning session** | See Step 2 above — do this before the hackathon day, in the actual demo room |
| **Demo rehearsal** | Run the full sequence 15–20 times until it's reliable and fast |

---

## Known Limitations (hackathon-acceptable)

1. **Evidence clips are audio-only** (.amr) — the "clip" is a copy of the MediaRecorder buffer at the moment of detection. It captures ambient sound but not a video frame. Full video clips would require a separate `VideoCapture` use-case.
2. **Fusion is retroactive but not real-time** — when a second sensor fires, previous events in the window are updated to CONFIRMED, but the Timeline screen needs a manual Refresh to show the new status.
3. **Schema migrations are now real** — `fallbackToDestructiveMigration()` has been removed and v2 → v3 migrates in place. A missing migration fails loudly instead of silently deleting evidence.
4. **Hash chain race is fixed** — every write goes through `ChainWriter`, which serialises read-tip → insert → hash → update behind a mutex, so simultaneous sensor events can no longer fork the chain.
5. **Recorder concurrency is fixed** — the `MediaRecorder` is owned by a single dedicated thread, so the amplitude loop cannot poll `maxAmplitude()` while a clip save stops/recreates the recorder. A failed clip freeze now reopens the mic instead of killing audio detection for the rest of the session.
6. **Manual tags are claims, not measurements** — a `manual` event joins its session's hash chain exactly like a sensor event, but `FusionEngine` keeps human assertions out of its source count. One operator tap therefore cannot look like two independent sensors agreeing, and the retroactive status pass leaves the tag's `MANUAL` status alone.
7. **Capture still stops when the screen sleeps** — backgrounding the app unregisters the sensors (`onPause`) and CameraX is lifecycle-bound, so a session shows `● REC` on return but was not capturing while backgrounded. Real background capture needs the foreground service, which is still not built.
8. **The live log tail is a runtime surface, not an audit trail** — it holds the last 60 lines (`SessionLog`), is rebuilt from the database's most recent events on re-attach, and dies with the process. The evidence of record stays the hash-chained DB in Previous Sessions. It also logs only what the *app* observes: pipeline events, tags, lifecycle and capture errors — not a raw sensor dump.
