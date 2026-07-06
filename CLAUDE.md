# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean build artifacts
./gradlew clean

# Full build including tests
./gradlew build
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Tech Stack

- **Language:** Kotlin 1.9.10
- **UI:** Jetpack Compose 1.7.3 with Material Design, Navigation Compose
- **Android API:** Min 29, Target 34, Compile SDK 34 — current app version 3.1.0
- **Maps/GIS:** ArcGIS Runtime Android 100.15.5 — shapefile loading, rendering, spatial queries
- **Async:** Kotlin Coroutines (Dispatchers.Default / Dispatchers.IO)
- **Serial comms:** android-serialport-2.1.3 (JAR) for DB9 (GNSS) and CAN bus (motors)
- **State:** MVVM with LiveData + ViewModel
- **Persistence:** SharedPreferences (incl. Gson-serialized rows), File I/O (CSV), Geodatabase export via ArcGIS

## Application Purpose

Variable-rate fertilizer + variable-depth sowing controller for precision agriculture. The app:
1. Reads real-time GNSS position via a DB9 serial port (NMEA RMC sentences)
2. Queries a shapefile prescription map to determine fertilizer rate at the current field position
3. Sends motor RPM commands to up to 8 fertilizer motors over a CAN bus serial connection
4. Controls up to 8 CANopen servo motors for sowing depth regulation (shares the same CAN port)
5. Records experiment data (fertilizer runs + servo depth dynamics) as CSV for offline analysis

## Architecture

### Package: `com.nx.vfremake`

```
MainActivity.kt          — App lifecycle, serial port init, asset copying, system start/stop
VariableFertScreen.kt    — Navigation host: enum of all routes + NavHost wiring (Main/ParamSet/Settings/
                           DantiSettings/SimGnss/SowingDepth/DepthCalibration/DepthTest/ExperimentData)
ViewModelAndPublic.kt    — CustomViewModel (background tasks) + VariableFertViewModel (central UI state)

ui/
  MainScreen.kt          — Map interface, location tracking, fertilizer zone display, follow-zoom +/- control
  SettingsScreen.kt      — Serial port config, motor diagnostics, real-time motor status panel
  ParamSettingsScreen.kt — Machine model (4/6/8-row), row spacing, GNSS offsets, lag time, forward speed
  dantiFertSettingScreen.kt — Per-unit (danti) fertilizer settings, table rows persisted via Gson
  SimGnssScreen.kt       — Simulated GNSS config: start→end route + speed, haversine-derived step count
  SowingDepthScreen.kt   — 8-motor depth status, master switch, global target, per-motor dialog
  DepthCalibrationScreen.kt — Limit setting + calibration: direct (tape measure) and indirect (gauge-block) modes
  DepthTestScreen.kt     — One-key depth performance test config/progress UI
  ExperimentDataScreen.kt — Lists saved experiment CSVs (name/time/size/row count), delete with run-guard
  ComposeTemplates.kt    — Reusable Compose components

data/
  SowingDepthData.kt     — ServoCalibration + SowingDepthState (masterEnabled, lastHeardMs)
  DepthTestData.kt       — DepthTestConfig (snapshot of test params) + DepthTestStatus (live progress)

funClass/
  MyArcGisFun.kt         — Shapefile loading, map rendering, spatial queries for fertilizer zones
  MyGNSSFun.kt           — GNSS RMC sentence parsing, rolling speed averaging
  MySerialPortFun.kt     — Serial port initialization and configuration
  CanOpenFun.kt          — CANopen protocol: SDO/NMT/TPDO frames, jog frames, motor init sequence
  ConvAndCtrlFun.kt      — Motor speed → fertilizer flow rate conversion (fitting coefficients)
  MySharedPreFun.kt      — All SharedPreferences read/write helpers (incl. depth calibration persistence)
  MydantiFertSharedPre.kt — Gson-based persistence of per-unit fertilizer table rows
  DocuAndManageFun.kt    — File management, storage permissions, saved-record directory access
  ExportGeoFun.kt        — Export/import fertilized zone data as geodatabase
  SPParamData.kt         — Data class: machine model, spacing, GNSS offsets, lag time, speed, active motors
  RmcData.kt             — Data class: GNSS lat/lon/speed/heading/timestamp + rolling speed buffer
  ArrayStatsData.kt      — Data class: max/min/mean stats with 2-decimal formatting

coroutine/
  DB9reCANseCoroutine.kt — Core control loop: RMC → shapefile query → CAN motor command dispatch;
                           also simulated-GNSS mode (start1Simulated) and grid-deduplicated zone drawing
  CanReceiveCoroutine.kt — CAN bus monitoring: fertilizer motor replies + CANopen TPDO/heartbeat/SDO replies
  SowingDepthCoroutine.kt — 500ms depth control loop, Phase 1–5, offline detection, init cooldown retry
  DepthTestCoroutine.kt  — One-key depth performance test: gradient round-trip through depth stages
  DepthRecordFun.kt      — 100ms CSV recorder for servo depth dynamics (manual + auto test share it)
  MyWriteSaveCoroutine.kt — Async persistence: fertilized zone logs and run data (fertMsg_*.csv)
  TestModeCoroutine.kt   — Diagnostic test mode for serial ports and motor control
```

### Core Control Loop (`DB9reCANseCoroutine.kt`)

This is the heart of the application:
1. Reads RMC sentence from DB9 serial port (or generates simulated RMC via `start1Simulated`)
2. Applies GNSS offset and lag time to project the vehicle's future position
3. Calls `MyArcGisFun` to query the prescription shapefile at that projected position
4. Calls `ConvAndCtrlFun` to convert prescribed fertilizer rate + forward speed → motor RPM
5. Sends CAN bus command bytes to each active motor

Stability-critical behaviors (learned from field failures — do not regress):
- **Stop gating**: near-zero ground speed (`gnssRawSpeed`) sends 0 RPM to all fertilizer motors and skips
  the query/dispatch for that frame (prevents motors idling/dumping fertilizer while parked)
- **Zero-rate / outside-plot = stop**: prescription field 0 or position outside all plots stops the motor
  instead of holding the last RPM
- **Grid-deduplicated drawing**: fertilized-zone polygons are drawn at most once per ~2m quantized grid
  cell per unit (`drawnCells`), so polygon count is bounded by area covered — independent of RMC rate,
  speed, duration, or overlapping passes. The fertilized layer uses `RenderingMode.STATIC`. This fixed a
  ~10-minute ANR/freeze during prescription-map operation.

### Key Data Flow

```
GNSS (DB9 serial / simulated) → MyGNSSFun → RmcData
                                    ↓
                           DB9reCANseCoroutine
                                    ↓
                  MyArcGisFun (shapefile spatial query)
                                    ↓
                  ConvAndCtrlFun (rate → RPM conversion)
                                    ↓
                         CAN bus serial output → Motors

CAN bus input → CanReceiveCoroutine ─→ fertilizer motor status (SettingsScreen panel)
                                    └→ CANopen TPDO/heartbeat/SDO → SowingDepthState
```

## ArcGIS Setup

The app uses ArcGIS Runtime SDK 100.15.5. Dependencies come from Esri's Artifactory repository configured in `settings.gradle.kts`. An ArcGIS API key is required — check `MainActivity.kt` for where it is set. Shapefiles are loaded from device storage; the `assets/` folder contains sample shapefiles that are copied to external storage on first launch. Prescription field parsing is null-safe and supports floating-point values.

## Serial Port Notes

- DB9 port: GNSS receiver input (NMEA RMC sentences)
- CAN port: Motor controller output (custom byte protocol for fertilizer motors, CANopen for depth servos)
- Serial port device paths and baud rates are configured in `SettingsScreen` and persisted via `MySharedPreFun`
- CAN serial close/reopen self-heals: depth control no longer locks into offline state and the receive
  coroutine survives port cycling
- The NDK is used (NDK 27.0.11718014) — the android-serialport JAR contains native `.so` libraries; ABIs supported: armeabi-v7a, arm64-v8a, x86, x86_64

## Sowing Depth Control

### Overview

Variable-depth sowing control using YZ-AIM CANopen servo motors + lead screw actuators. Up to 8 independent depth-control motors. Communication protocol: CANopen (CiA301 + DS402), distinct from existing fertilizer motor protocol.

### Protocol Difference

- **Fertilizer motors**: Custom CAN byte protocol (simple RPM commands)
- **Sowing depth servos**: CANopen protocol (SDO for config, PDO for real-time, NMT for state management)
- Both share the same CAN serial port; distinguish by CAN-ID range

### CAN Address Allocation

- Fertilizer motors: Node-ID 1~8 (existing)
- Sowing depth servos: Node-ID 11~18 (new)

### Key Reference

- Full CANopen protocol details and implementation plan: `docs/SOWING_DEPTH_IMPLEMENTATION.md`
- CAN-UART bridge protocol and frame format: `docs/CSM100T_CAN_BRIDGE.md`
- Servo motor manual (PDF): `docs/servo_motor_CANopen.pdf`
- Outstanding defect backlog (P0–P4): `docs/CODE_REVIEW_DEFECTS.md`

### Implementation Status (as of 2026-07-04, branch `feature/depthTest`)

All core depth-control features are implemented, including both calibration modes and the one-key
performance test with CSV recording. Key files are listed in the Architecture section above.

Completed since the initial implementation:
- **Indirect (gauge-block) calibration** in `DepthCalibrationScreen` — jog the depth wheel down onto
  standard gauge blocks, record encoder positions, fit the curve without tape measurement
  (spec: `docs/SOWING_DEPTH_IMPLEMENTATION.md` Section 七)
- **One-key depth performance test** (`DepthTestScreen` + `DepthTestCoroutine`): single motor runs a
  gradient round-trip (e.g. 20→40→60→80→60→40→20 mm), dwell at each stage after tolerance-based
  arrival detection, per-stage timeout recorded as anomaly
- **100ms CSV recording** (`DepthRecordFun`): millisecond timestamps + elapsedRealtime relative-ms
  column (immune to system clock jumps); file prefixes `depthRec_` (manual) / `depthTest_` (auto test)
- **In-app experiment data viewer** (`ExperimentDataScreen`) with save-result toast notifications

### Critical Implementation Notes

1. **Offline detection**: `CanReceiveCoroutine` sets `isOnline=true` + updates `lastHeardMs` on every TPDO/heartbeat. `SowingDepthCoroutine` Phase 3 marks offline only when `System.currentTimeMillis() - lastHeardMs > 5000ms`. The old position-change proxy is removed — it falsely flagged stationary motors.

2. **First position command after init**: `motorInitCooldown[i]=6` is set after Phase 2 init. Phase 4 clears `lastSentTargetDepth` during cooldown to force retry for 3s (handles DS402 settle time after Enable Operation).

3. **Bit4 toggle**: `buildAbsoluteMoveFrames` sends `0x6040=0x000F` then `0x6040=0x002F` to guarantee Bit4 0→1 transition. Required for motor to accept new setpoint.

4. **Jog start/stop (reworked after 2026-07 hardware debugging — do not regress)**:
   - Start sequence is 5 frames beginning with `0x6040=0x0006` (Shutdown): `0x0006 → 0x0007 →
     0x6060=3 → 0x60FF=±v → 0x000F`. Without the leading 0x0006 a cold drive (Switch On Disabled)
     ignores 0x0007 per DS402 and the jog button does nothing. The enabling 0x000F is always the
     LAST frame (prefix-safe: a cancelled sequence can never start the motor).
   - Stop: `0x60FF=0` sent **3 times** (50ms apart, idempotent, loss-tolerant), preemptable decel
     wait `jogSpeed×1000/accel + 200ms` (max 4s), stop-verify via position samples (resend v=0 if
     still moving), then `0x6040=0x0007 → 0x6060=1` (Disable Operation + pre-switch to position
     mode, deliberately **without** 0x000F — runaway backstop even if all v=0 frames are lost).
     `buildAbsoluteMoveFrames` re-enables from Switched On via its 0x000F → 0x002F.
   - UI uses a single-consumer `Channel<JogCommand>` (DepthCalibrationScreen); press/release only
     enqueue commands, so start/stop sequences can never interleave on the bus. The Step-2 limit
     monitor enqueues `Stop` too (idempotent with manual release).
   - `JogSession` (top-level object in `CanOpenFun.kt`, AtomicInteger CAS): while a node is jogging,
     `SowingDepthCoroutine` skips Phase 2 init and Phase 4 dispatch for it (position-mode frames
     kill an active velocity-mode jog). Phase 1 reads and Phase 5 limit quickstop stay active.

5. **Master switch**: `masterEnabled` (not persisted, defaults false). Controls Phase 2 init gate and Phase 4 dispatch gate. ON→OFF transition sends `0x6040=0x0006` (Shutdown) to all initialized motors and resets `motorInitialized`/`motorInitCooldown`/`lastSentTargetDepth`.

6. **SDO timing (globally serialized)**: all CANopen senders must go through
   `CanOpenFun.sendFrameSequenced` / `sendSequence` — a shared Mutex enforces a global ≥20ms
   inter-frame gap and multi-frame sequences are sent atomically (no interleaving from other
   senders). Never call `CanOpenFun.sendFrame` directly from coroutines; per-caller `delay(20)`
   was the root cause of cross-coroutine SDO collisions (dropped frames → dead jog buttons /
   runaway). Fertilizer frames skip the pacing but share the byte-level
   `MySerialPortFun.CAN_TX_LOCK` (defect L10 fix). SDO reply (0x580+ID) comes back via
   `CanReceiveCoroutine`.

7. **Encoder**: 32768 pulses/rev. With 5mm lead screw: 32768 pulses = 5mm travel. `fitA`/`fitB` coefficients: `depth_mm = fitA × encoderPos + fitB`.

8. **Depth test drives through Phase 4**: `DepthTestCoroutine` only writes the target motor's
   `targetDepth`; actual CAN frames are dispatched exclusively by `SowingDepthCoroutine` Phase 4
   (reuses its gating, limit clamping, and SDO timing — avoids two writers competing on the bus).
   Abort = write `targetDepth`←current depth for a smooth stop. **Never use quickStop to abort**: it
   drops the drive out of Operation Enabled while `motorInitialized` stays true, leaving the motor
   unresponsive to new targets.

## Current Development Focus

Priorities for the current phase (in order):

1. **Stability hardening** — work through the outstanding defect backlog in
   `docs/CODE_REVIEW_DEFECTS.md`: P1 (build reproducibility), P2 (state convergence & lifecycle),
   P3 (performance & long-run stability), P4 (robustness & cleanup). P0 crash/runaway items and both
   field-observed failures are already fixed (`f1b538c`, `da93f17`). Update the status legend in that
   file when fixing an item.
2. **UI polish** — make screens more attractive and intuitive: consistent Material styling, clearer
   status visualization (motor/servo state at a glance), better operator ergonomics for in-field use
   (large touch targets, glanceable colors, minimal text entry). Reuse/extend `ComposeTemplates.kt`
   rather than duplicating ad-hoc styles.
3. **Sowing depth feature completion** — refine depth control behaviors on top of the implemented
   base: field-test-driven fixes, richer depth telemetry/visualization, and workflow improvements in
   calibration and testing screens.

Conventions observed in this repo:
- Commit messages in Chinese, conventional-commit prefixes (`feat:`/`fix:`/`docs:`/`chore:`)
- Feature work on `feature/*` branches, merged to `main` via PR
- File headers use the NIANXI banner comment block; in-code comments are Chinese and explain
  field-failure rationale — preserve these when refactoring
