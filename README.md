# TRACE — Tamper-Resistant Autonomous Capture Environment

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white"/>
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white"/>
  <img src="https://img.shields.io/badge/ML-Google%20ML%20Kit-4285F4?style=for-the-badge&logo=google&logoColor=white"/>
  <img src="https://img.shields.io/badge/Database-Room%20SQLite-003B57?style=for-the-badge&logo=sqlite&logoColor=white"/>
  <img src="https://img.shields.io/badge/License-MIT-00E676?style=for-the-badge"/>
</p>

<p align="center">
  <b>An autonomous, tamper-proof evidence gathering platform that turns any Android phone into a cryptographically sealed, multi-sensor witness.</b>
</p>

<p align="center">
  🏆 <b>iQOO Hackathon 2026 — Shortlisted (Top 24 Teams)</b>
</p>

---

## 📖 What is TRACE?

In a world where AI deepfakes have made single-source digital evidence unreliable, TRACE provides an absolute guarantee of authenticity.

TRACE simultaneously **watches** (Camera), **listens** (Microphone), and **feels** (Accelerometer) the environment. When multiple sensors agree that something happened, TRACE:

1. **Classifies** the event (`impact`, `alarm`, `object_moves`, `object_falls`)
2. **Fuses** evidence from all sensors to determine confidence
3. **Seals** the event in a SHA-256 cryptographic hash chain
4. **Saves** an audio evidence clip tied to that exact moment
5. **Logs** everything to a tamper-evident local database

> **The mobile phone is not just a viewer — it is the main intelligence hub.**

---

## ✨ Features

### Core Sensing
| Feature | Description |
|---------|-------------|
| 📷 **Camera Motion Detection** | Pixel-level frame differencing detects object movement and falls in real-time |
| 🔊 **Microphone Sound Detection** | Amplitude polling every 150ms detects alarms and abnormal sounds |
| 📳 **Accelerometer Impact Detection** | Jerk-based motion analysis catches physical impacts invisible to the camera |

### Extended Sensor Array (v2.1)
Every sensor below feeds the same Observation → EventExtractor → FusionEngine → HashChain pipeline. Each is documented in `SensorRegistry.kt` with its reconstruction role.

| Sensor | Signal it contributes | Evidence toward |
|--------|----------------------|-----------------|
| 🧲 **Magnetometer** | Deviation from a rolling ~30 s baseline of ambient magnetic field — steel doors and large metal objects distort the local field | `door_swing`, `door_slam`, `metal_moves` |
| 🌀 **Barometer** | Fast air-pressure pulses (>0.3 hPa) from doors opening/closing — HVAC drifts too slowly to trigger | `pressure_shift` (context) |
| 💡 **Ambient Light** | Large *negative* lux step against baseline — a light source being switched off | `lights_off` (context) |
| 🪂 **Linear Acceleration** | Gravity-compensated vector collapsing toward 0 m/s² — the free-fall signature of anything dropping near the phone | `object_falls`, `device_falls` |
| 🔄 **Gyroscope** | Angular velocity — a device tumbling off a shelf spins on multiple axes | `device_falls`, `device_motion` |
| 👣 **Step Detector** | One signal per footstep — implies a person near the phone | `person_present` (context) |
| 🚶 **Significant Motion** | Hardware activity trigger — device was moved in a notable way | `person_present` (context) |
| 🔇 **Vibration / sound** | *Intentionally unused:* TRACE stays completely silent during a live session — a CONFIRMED incident never triggers a sound or a haptic pulse | — |

**Context signals** (`pressure_shift`, `lights_off`, `person_present`) rarely reach the >60% confidence needed to count toward CONFIRMED on their own — they enrich the reconstruction timeline (e.g. footsteps + lights-off + alarm tells a story) rather than drive verdicts.

**Deliberately excluded** (no meaningful contribution to physical incident reconstruction):
- **Ambient temperature / humidity** — absent on modern phones; air state doesn't distinguish incident signatures.
- **Rotation vector / orientation / gravity (raw)** — redundant; dynamics are captured by gyroscope + linear acceleration.
- **Wi-Fi / Bluetooth RSSI** — seconds-scale scans, poor spatial resolution, heavy permissions, near-zero demo value.
- **Proximity** — 5 cm range, designed for screen-off during calls, not incident detection.
- **GPS** — coarse, battery-hungry, and location is not part of the physical-incident signature. |

### Session Lifecycle
| Feature | Description |
|---------|-------------|
| ▶️ **Arming Sheet** | A session begins only when the operator starts one. Describe the work ("door inspection") and the sensors that carry evidence for it are pre-selected as toggles to review. *Skip* captures everything |
| 🧠 **Sensor Suggestion** | Keyword mapping, not a model: "door" → magnetometer + barometer + audio + motion + camera, because hinges and frames are steel and a swinging door pulses room pressure. **Anything unrecognised captures every sensor** — missing evidence cannot be recovered, extra evidence can be ignored |
| 🎛️ **Per-Session Sensor Set** | The chosen set is part of the hashed session header, so it is fixed for the session and cannot be quietly widened later. Only those pipelines are started |
| 🎥 **Camera-Free Sessions** | With the camera toggled off, the viewfinder is replaced by the list of sensors that *are* capturing — and the camera is never bound at all. A black preview would look like a fault at the moment the app is working as configured |
| ⏹️ **Explicit End** | *End Session* asks first (one tap must not seal a recording), stops capture and closes the session as `COMPLETED` with its duration, event count and confirmed count |
| 🎛️ **Foreground Capture Service** | While a session is armed, all pipelines run in a foreground service (`camera|microphone` type) — capture **survives the screen turning off and the app leaving the foreground**. The persistent notification shows the session, event count and an *End session* action; a system kill leaves the session `INTERRUPTED`, never a fake recording |

### Intelligence
| Feature | Description |
|---------|-------------|
| ⚡ **Sensor Fusion Engine** | Cross-references all sensors in a ±2s window *within the same session*. ≥2 sensors agreeing → `CONFIRMED`. 1 sensor → `UNCONFIRMED`. 0 → `REJECTED`. Manual tags are excluded from the source count, so one operator tap can never masquerade as two sensors agreeing |
| 🔐 **SHA-256 Hash Chain** | Every event is cryptographically chained to the previous event **of its session**. Tampering breaks that session's chain permanently — and cannot invalidate any other session |
| 💬 **NLP Query Engine** | Ask plain-English questions like *"what happened before the alarm?"* — answered from local DB with zero internet |
| ☁️ **Cloud AI Chat** | Chat about a session with **Groq, Google AI Studio or OpenRouter** (all OpenAI-compatible). Bring your own API key and model name — stored encrypted on-device; the session's event log (text only: labels, statuses, times, confidences — never audio or photos) is the grounding context. Answers are review tooling and are never written into the evidence chain |

### Evidence
| Feature | Description |
|---------|-------------|
| 🎵 **Audio Evidence Clips** | Real `.amr` audio snapshot saved for every event — stop → copy → restart pattern guarantees playback |
| 📸 **Camera Snapshots** | One JPEG frame frozen at the moment of each event (and each operator tag), saved alongside the audio clip. Best-effort and independent: a failed snapshot never fails the event, and camera-free sessions simply log that there is none |
| 🧬 **Evidence File Integrity** | Every clip and snapshot is SHA-256-hashed at write time and stored on the event row; the detail screen re-verifies the file on view. Replaced or tampered evidence is flagged loudly instead of silently accepted |
| 🔐 **Private-by-default Storage** | Evidence (database, clips, snapshots) lives in **internal app-private storage** — no other app, file manager or MTP session can read it; cloud backup and device-transfer are explicitly excluded; older installs are migrated off the previously readable `Android/data` location on first launch |
| 📊 **Incident Timeline** | Chronological log with color-coded status pills (🟢 CONFIRMED / 🟡 UNCONFIRMED / 🔴 REJECTED / 🟣 MANUAL) |
| 🗂️ **Previous Sessions** | Session-grouped review: state, duration and counts per session; chain + header verification; per-session JSON export; session-scoped AI chat; and a delete that removes the audio clips and snapshots along with the rows (blocked while Evidence Lock is on; the live session cannot delete itself) |
| ✋ **Manual Incident Tag** | The operator asserts what happened — `TAG INCIDENT` on the live view offers the sensor vocabulary (object fell, impact, door, alarm, person, lights, other). Recorded as source `manual` with status `MANUAL`: full chain evidence, but explicitly *not* a sensor verdict |
| 🎥 **Smart Video Import** | Pick random video clips → TRACE sorts by creation timestamp → extracts keyframes with ML Kit → adds to timeline |
| 📤 **JSON Export** | Export entire evidence chain to `events.json` for review in the standalone `trace_viewer.html` laptop viewer |

### New in v2.0
| Feature | Description |
|---------|-------------|
| 📡 **Live Sensor HUD** | Real-time `📷 45%  🔊 12%  📳 78%` readouts on the main screen — watch the AI sensing live |
| 🔔 **Silent Alerts** | A visual-only notification fires when an incident is `CONFIRMED`. TRACE never plays a sound or vibrates during a session |
| 🟩 **CONFIRMED Banner** | The live view shows a brief green banner when fusion agrees — the in-app cue the silent notification channel was always paired with. Visual only, auto-hides |
| 📟 **Session Status Chip** | The app bar always states the capture state: `● REC 04:12` with a live timer, `🔒 LOCKED` when evidence is sealed, or `IDLE` — no ambiguity about whether TRACE is recording |
| 🔒 **Lock Evidence** | One-tap sealing — stops all new recordings, locks the hash chain as a permanent legal artifact. The dashboard status chip shows `🔒 LOCKED` while it is armed |
| ▶️ **Start / End Session** | An explicit lifecycle replaces "recording starts when the app opens". The chip reads `IDLE` until a session is armed, so `● REC` always means evidence is actually being captured |
| 📜 **Live Session Log** | The dashboard's bottom quarter is a scrolling transcript of the session: recorded events (with icon, label and fusion status), operator tags, lifecycle and capture errors — newest at the bottom, colour-coded with the same fixed palette as the timeline. A runtime surface, not evidence: on re-attach it is rebuilt from the session's last stored events, so entries recorded while the screen was away are never missing |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────┐
│              SENSORS (concurrent)           │
│  Camera (30fps) ──► analyseFrame()         │
│  Mic (150ms)    ──► pollAmplitude()        │
│  Accel (60Hz)   ──► sensorListener         │
└──────────────────┬──────────────────────────┘
                   │  Observation(source, confidence, timestamp)
                   ▼
            EventExtractor
         (label or discard)
                   │
                   ▼
          Insert to Room DB
                   │
                   ▼
         FusionEngine (±2s window)
     ┌─────────────┼─────────────┐
  camConf      audioConf     motionConf
     └─────────────┼─────────────┘
         CONFIRMED / UNCONFIRMED / REJECTED
                   │
                   ▼
         HashChain.computeHash()
         (SHA-256 + prevHash)
                   │
                   ▼
        DB updated (final sealed event)
                   │
         ┌─────────┼──────────┐
    Timeline   QueryEngine  EventDetail
    (list)    (NLP search)  (evidence view)
```

---

## 🛠️ Tech Stack

| Technology | Usage | Why not the alternative? |
|-----------|-------|--------------------------|
| **Kotlin** | Primary language | Coroutines built-in for concurrent sensors; 3× less code than Java |
| **CameraX** | Camera preview + frame analysis | 10× simpler than Camera2 API; handles all device differences |
| **SensorManager** | Accelerometer data | Native Android API; lowest possible latency |
| **MediaRecorder (AMR_NB)** | Continuous audio recording | AMR allows mid-stream copy; MP4/3GP requires complete header on stop |
| **Android Room** | Local SQLite database | Type-safe queries; offline-first; zero cloud dependency |
| **ML Kit Image Labeling** | Video keyframe classification | On-device, no internet, pre-optimized; full LLM needs 4GB RAM |
| **SHA-256 (Java stdlib)** | Hash chain | Zero dependencies; same algorithm as Bitcoin |
| **Kotlin Coroutines** | Concurrent sensor pipelines | Non-blocking; sensors never freeze the UI thread |

---

## 📁 Project Structure

```
app/src/main/
├── java/com/example/trace/
│   ├── TraceApplication.kt      ← Material You dynamic colour wiring
│   ├── TraceAppearance.kt       ← Wallpaper vs TRACE palette preference
│   ├── MainActivity.kt          ← Sensor hub: dashboard + app bar + status chip
│   ├── Session.kt               ← Room entity: one capture period, one chain
│   ├── SessionDao.kt            ← Session queries
│   ├── SessionManager.kt        ← Create / close / recover sessions
│   ├── ChainWriter.kt           ← The only writer allowed to append to a chain
│   ├── EvidenceLock.kt          ← Lock state, checked by every writer
│   ├── Event.kt                 ← Room entity for one incident record
│   ├── EventDao.kt              ← All SQL queries (session-scoped)
│   ├── TraceDatabase.kt         ← Room database + explicit migrations
│   ├── Observation.kt           ← Raw sensor spike data class
│   ├── SensorRegistry.kt        ← Canonical sensor ids, icons, roles
│   ├── RollingBaseline.kt       ← EWMA baselines for magnetometer/barometer/light
│   ├── EventExtractor.kt        ← Maps Observation → event type label
│   ├── FusionEngine.kt          ← Multi-sensor fusion + status assignment
│   ├── HashChain.kt             ← SHA-256 tamper-evident chains (per session)
│   ├── QueryEngine.kt           ← Keyword query engine
│   ├── SettingsActivity.kt      ← Settings (appearance today, more later)
│   ├── VideoAnalyzer.kt         ← Keyframe extraction + ML Kit labeling
│   ├── VideoImportActivity.kt   ← Multi-video picker and analyser
│   ├── TimelineActivity.kt      ← Timeline + query bar + lock evidence
│   ├── EventDetailActivity.kt   ← Evidence detail: bars + audio + hash
│   └── EventAdapter.kt          ← RecyclerView adapter with DiffUtil
└── res/
    ├── layout/
    │   ├── activity_main.xml          ← Camera + overlaid app bar + status chip + HUD
    │   ├── activity_timeline.xml      ← Toolbar + query bar + locked banner + list
    │   ├── activity_event_detail.xml  ← Toolbar + confidence bars + hash section
    │   ├── activity_video_import.xml  ← Video picker + progress
    │   ├── activity_settings.xml      ← Settings (appearance today)
    │   └── item_event.xml             ← Card with icon + pill badge
    ├── menu/
    │   └── menu_dashboard.xml         ← Previous sessions + Settings actions
    ├── drawable/
    │   ├── status_pill.xml            ← Rounded pill shape for status badge
    │   ├── ic_sessions.xml            ← App bar: previous sessions
    │   └── ic_settings.xml            ← App bar: settings
    └── values/
        ├── themes.xml                 ← Material 3 theme (see Theming below)
        └── colors.xml                 ← TRACE palette + fixed semantic colours
```

### Theming (Material 3)

`Theme.TRACE` extends `Theme.Material3.Dark.NoActionBar`, and is dark-only on purpose: every screen paints dark surfaces and the camera viewfinder is black, so a light variant would need a full pass over five layouts before it could honestly be offered.

| Layer | Behaviour |
|-------|-----------|
| **Android 12+** | Material You — `DynamicColors` re-tints the app from the user's wallpaper (`TraceApplication`) |
| **Android 7–11** | No dynamic palette exists, so the TRACE palette in `colors.xml` is used. Those values are the exact hex the UI used before the theme existed, so the fallback renders identically |
| **Palette switch** | Timeline overflow → *Appearance* pins the TRACE palette instead of the wallpaper (moves into Settings later) |

**Semantic colours are deliberately not theme attributes.** Green = `CONFIRMED`, amber = `UNCONFIRMED`, red = `REJECTED`, and the blue/red/green camera/audio/motion legend are fixed values in `colors.xml`. If they followed the wallpaper palette, a blue wallpaper would render "confirmed" blue and the sensor legend would mean something different on every phone. Everything palette-driven instead goes through `?attr/colorPrimary`, `?attr/colorSurface`, `?attr/colorOnSurface`, `?attr/colorOnSurfaceVariant`.

---

## 🚀 Setup & Installation

### Prerequisites
- Android Studio (Hedgehog or later)
- Android phone with API 24+ (Android 7.0+)
- Data USB cable

### Clone & Run
```bash
git clone https://github.com/YOUR_USERNAME/TRACE.git
```

1. Open in **Android Studio**: `File → Open → TRACE folder`
2. Enable **USB Debugging** on your phone (tap Build Number 7 times in Settings)
3. Connect phone via USB → tap **Allow**
4. Click **▶ Run** in Android Studio

### First Launch
Grant all permissions when prompted:
- ✅ Camera, ✅ Microphone, ✅ Notifications, ✅ Storage/Media

---

## 📱 Usage

| Action | Result |
|--------|--------|
| Tap **START SESSION** | Arming sheet: describe the work, review the suggested sensors, arm. Nothing is recorded before this |
| Tap **END SESSION** | Confirms, stops capture, closes the session as `COMPLETED` with its aggregates |
| Shake phone hard | 📳 `impact` event created |
| Clap near mic | 🔊 `abnormal_sound` event |
| Wave hand at camera | 📷 `object_moves` event |
| Two sensors fire together | ✅ `CONFIRMED` (green) |
| Open Timeline | All events with timestamps |
| Tap any event | Confidence bars + audio clip + hash |
| Type question + ASK | Instant NLP answer |
| Tap ✋ TAG INCIDENT | Record an incident **you** saw: pick a label, and it joins the session chain as a human assertion |
| Tap 🎥 Analyse Video | Import & chronologically sort video clips |
| Tap 🔒 LOCK | Seal evidence chain permanently |
| Tap the app bar icons | Previous Sessions (timeline) / Settings (palette switch) |

---

## 🔐 Hash Chain Verification

Every session anchors its own chain. The session header is hashed first, and
the session's first event links to *that* — not to a shared genesis block:

```
GENESIS      = "0000...0000" (64 zeros) — used only by the pre-session timeline
sessionHash  = SHA256("trace-session-v1|7|site inspection|1730000000000|motion|audio")

Event 1: hash = SHA256("impact|1234567890|motion|0.83" + sessionHash)
Event 2: hash = SHA256("alarm|1234567891|audio|0.91" + Event1.hash)
Event 3: hash = SHA256("object_moves|1234567892|camera|0.61" + Event2.hash)
```

Because the session id, description, start time and sensor set are inside
`sessionHash`, editing the session description afterwards invalidates the whole
chain unless every event is rewritten. Deleting Event 2 makes Event 3
**INVALID**. Both checks are per session, so a damaged session can never make a
healthy one look broken.

### Sessions

| Concept | Behaviour |
|---------|-----------|
| **One chain per session** | Events link only to other events of the same session; deleting or exporting one session cannot affect another |
| **Session header hash** | Binds id, nature-of-work, start time and sensor set to the evidence. Closing a session or refreshing its counters does **not** change the hash |
| **Pre-session (legacy) evidence** | Events recorded before sessions existed are migrated into a synthetic `Pre-session timeline (legacy)` session whose chain stays anchored on `GENESIS`, so their hashes are never recomputed |
| **Imported footage** | Video import creates its own `Imported footage` session, keeping provenance obvious and its chain independent of any live session |
| **Interrupted sessions** | A crash or force-stop leaves the session `ACTIVE`; the next launch closes it as `INTERRUPTED` with its duration and counters filled in |
| **Fusion isolation** | The ±2s fusion window only ever reads the current session, so overlapping timestamps in imported footage cannot confirm a live event |
| **Armed, never automatic** | No session exists until the operator starts one, and the dashboard says `IDLE` until then. Closing the app mid-session and reopening it resumes the same recording instead of silently starting a second one |
| **Fixed sensor set** | The armed set is part of the header hash, so widening it mid-session would invalidate the chain that already anchors on it |
| **Human assertions** | A `manual` tag is verified by the same chain as any sensor event — moving it between sessions breaks verification — but it is skipped by the fusion verdict, so an operator's claim is never laundered into a sensor conclusion |

---

## 🔍 Query Engine Examples

```
what happened before the alarm?
what happened after the impact?
how many confirmed events?
show all falls
show all motion
list manual tags
why was it confirmed?
last event
```

---

## 📊 Sensor Thresholds (tuning guide)

Edit these constants in `MainActivity.kt`:

```kotlin
// Motion — idle jerk < 1.0, firm tap ~5, hard shake ~15-25
private val MOTION_THRESHOLD = 3.0f
private val MOTION_MAX       = 20.0f

// Audio — silence < 1500, loud clap ~10000+
private val AUDIO_THRESHOLD  = 1500
private val AUDIO_MAX        = 16147

// Camera — 5% pixels changed = moves, 30% = falls
private val CAMERA_CHANGE_THRESHOLD = 0.05f
private val CAMERA_MAX_CHANGE       = 0.30f
```

---

## 🤝 Team

Built for the **iQOO Hackathon 2026**

Previous achievements: Hackpreneur Hackathon 🥇 · Machine Learning Hackathon 🥇

---

## 📄 License

MIT License — free to use, modify, and distribute with attribution.

---

<p align="center"><i>"The phone is not a viewer. The phone is the witness, the judge, and the vault — all in one."</i></p>