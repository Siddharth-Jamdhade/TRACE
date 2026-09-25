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

### Intelligence
| Feature | Description |
|---------|-------------|
| ⚡ **Sensor Fusion Engine** | Cross-references all sensors in a ±2s window. ≥2 sensors agreeing → `CONFIRMED`. 1 sensor → `UNCONFIRMED`. 0 → `REJECTED` |
| 🔐 **SHA-256 Hash Chain** | Every event is cryptographically chained to the previous one. Tampering breaks the entire chain permanently |
| 💬 **NLP Query Engine** | Ask plain-English questions like *"what happened before the alarm?"* — answered from local DB with zero internet |

### Evidence
| Feature | Description |
|---------|-------------|
| 🎵 **Audio Evidence Clips** | Real `.amr` audio snapshot saved for every event — stop → copy → restart pattern guarantees playback |
| 📊 **Incident Timeline** | Chronological log with color-coded status pills (🟢 CONFIRMED / 🟡 UNCONFIRMED / 🔴 REJECTED) |
| 🎥 **Smart Video Import** | Pick random video clips → TRACE sorts by creation timestamp → extracts keyframes with ML Kit → adds to timeline |
| 📤 **JSON Export** | Export entire evidence chain to `events.json` for review in the standalone `trace_viewer.html` laptop viewer |

### New in v2.0
| Feature | Description |
|---------|-------------|
| 📡 **Live Sensor HUD** | Real-time `📷 45%  🔊 12%  📳 78%` readouts on the main screen — watch the AI sensing live |
| 🔔 **Push Notifications** | High-priority notification fires the moment an incident is `CONFIRMED` (even when app is minimized) |
| 🔒 **Lock Evidence** | One-tap sealing — stops all new recordings, locks the hash chain as a permanent legal artifact |

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
│   ├── MainActivity.kt          ← Sensor hub: camera + mic + accelerometer
│   ├── Event.kt                 ← Room entity for one incident record
│   ├── EventDao.kt              ← All SQL queries
│   ├── TraceDatabase.kt         ← Room database singleton
│   ├── Observation.kt           ← Raw sensor spike data class
│   ├── EventExtractor.kt        ← Maps Observation → event type label
│   ├── FusionEngine.kt          ← Multi-sensor fusion + status assignment
│   ├── HashChain.kt             ← SHA-256 tamper-evident chain
│   ├── QueryEngine.kt           ← NLP keyword engine
│   ├── VideoAnalyzer.kt         ← Keyframe extraction + ML Kit labeling
│   ├── VideoImportActivity.kt   ← Multi-video picker and analyser
│   ├── TimelineActivity.kt      ← Timeline + query bar + lock evidence
│   ├── EventDetailActivity.kt   ← Evidence detail: bars + audio + hash
│   └── EventAdapter.kt          ← RecyclerView adapter with DiffUtil
└── res/
    ├── layout/
    │   ├── activity_main.xml          ← Camera HUD with live sensor readouts
    │   ├── activity_timeline.xml      ← Query bar + locked banner + list
    │   ├── activity_event_detail.xml  ← Confidence bars + hash section
    │   ├── activity_video_import.xml  ← Video picker + progress
    │   └── item_event.xml             ← Card with icon + pill badge
    ├── drawable/
    │   └── status_pill.xml            ← Rounded pill shape for status badge
    └── values/
        └── themes.xml                 ← Cyberpunk palette: #00E676 / #0D1117
```

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
| Shake phone hard | 📳 `impact` event created |
| Clap near mic | 🔊 `abnormal_sound` event |
| Wave hand at camera | 📷 `object_moves` event |
| Two sensors fire together | ✅ `CONFIRMED` (green) |
| Open Timeline | All events with timestamps |
| Tap any event | Confidence bars + audio clip + hash |
| Type question + ASK | Instant NLP answer |
| Tap 🎥 Analyse Video | Import & chronologically sort video clips |
| Tap 🔒 LOCK | Seal evidence chain permanently |

---

## 🔐 Hash Chain Verification

```
GENESIS = "0000...0000" (64 zeros)

Event 1: hash = SHA256("impact|1234567890|motion|0.83|CONFIRMED" + GENESIS)
Event 2: hash = SHA256("alarm|1234567891|audio|0.91|CONFIRMED"  + Event1.hash)
Event 3: hash = SHA256("object_moves|...|UNCONFIRMED" + Event2.hash)
```

Delete Event 2 → Event 3 shows **INVALID** → tampering is cryptographically proven.

---

## 🔍 Query Engine Examples

```
what happened before the alarm?
what happened after the impact?
how many confirmed events?
show all falls
show all motion
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