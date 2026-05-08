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
- **UI:** Jetpack Compose 1.7.3 with Material Design
- **Android API:** Min 29, Target 34, Compile SDK 34
- **Maps/GIS:** ArcGIS Runtime Android 100.15.5 — shapefile loading, rendering, spatial queries
- **Async:** Kotlin Coroutines (Dispatchers.Default / Dispatchers.IO)
- **Serial comms:** android-serialport-2.1.3 (JAR) for DB9 (GNSS) and CAN bus (motors)
- **State:** MVVM with LiveData + ViewModel
- **Persistence:** SharedPreferences, File I/O, Geodatabase export via ArcGIS

## Application Purpose

Variable-rate fertilizer controller for precision agriculture. The app:
1. Reads real-time GNSS position via a DB9 serial port (NMEA RMC sentences)
2. Queries a shapefile prescription map to determine fertilizer rate at the current field position
3. Sends motor RPM commands to up to 8 motors over a CAN bus serial connection

## Architecture

### Package: `com.nx.vfremake`

```
MainActivity.kt          — App lifecycle, serial port init, asset copying, system start/stop
ViewModelAndPublic.kt    — CustomViewModel (background tasks) + VariableFertViewModel (central UI state)

ui/
  MainScreen.kt          — Map interface, location tracking, fertilizer zone display
  SettingsScreen.kt      — Serial port config, motor diagnostics, real-time motor status panel
  ParamSettingsScreen.kt — Row count, spacing, GNSS offsets, lag time, forward speed
  dantiFertSettingScreen.kt — Anti-fertilizer settings
  ComposeTemplates.kt    — Reusable Compose components

funClass/
  MyArcGisFun.kt         — Shapefile loading, map rendering, spatial queries for fertilizer zones
  MyGNSSFun.kt           — GNSS RMC sentence parsing, rolling speed averaging
  MySerialPortFun.kt     — Serial port initialization and configuration
  ConvAndCtrlFun.kt      — Motor speed → fertilizer flow rate conversion (fitting coefficients)
  MySharedPreFun.kt      — All SharedPreferences read/write helpers
  DocuAndManageFun.kt    — File management, storage permissions
  ExportGeoFun.kt        — Export/import fertilized zone data as geodatabase
  SPParamData.kt         — Data class: row count, spacing, GNSS offsets, lag time, speed, active motors
  RmcData.kt             — Data class: GNSS lat/lon/speed/heading/timestamp + rolling speed buffer

coroutine/
  DB9reCANseCoroutine.kt — Core control loop: RMC → shapefile query → CAN motor command dispatch
  CanReceiveCoroutine.kt — CAN bus monitoring, parses motor response messages
  MyWriteSaveCoroutine.kt — Async persistence: fertilized zone logs and run data
  TestModeCoroutine.kt   — Diagnostic test mode for serial ports and motor control
```

### Core Control Loop (`DB9reCANseCoroutine.kt`)

This is the heart of the application:
1. Reads RMC sentence from DB9 serial port
2. Applies GNSS offset and lag time to project the vehicle's future position
3. Calls `MyArcGisFun` to query the prescription shapefile at that projected position
4. Calls `ConvAndCtrlFun` to convert prescribed fertilizer rate + forward speed → motor RPM
5. Sends CAN bus command bytes to each active motor

### Key Data Flow

```
GNSS (DB9 serial) → MyGNSSFun → RmcData
                                    ↓
                           DB9reCANseCoroutine
                                    ↓
                  MyArcGisFun (shapefile spatial query)
                                    ↓
                  ConvAndCtrlFun (rate → RPM conversion)
                                    ↓
                         CAN bus serial output → Motors
```

## ArcGIS Setup

The app uses ArcGIS Runtime SDK 100.15.5. Dependencies come from Esri's Artifactory repository configured in `settings.gradle.kts`. An ArcGIS API key is required — check `MainActivity.kt` for where it is set. Shapefiles are loaded from device storage; the `assets/` folder contains sample shapefiles that are copied to external storage on first launch.

## Serial Port Notes

- DB9 port: GNSS receiver input (NMEA RMC sentences)

- CAN port: Motor controller output (custom byte protocol)

- Serial port device paths and baud rates are configured in `SettingsScreen` and persisted via `MySharedPreFun`

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
- Servo motor manual (Chinese): `docs/YZ-AIM_canopen用户手册_v1_66_出线版_.pdf`
- CAN-UART bridge protocol and frame format: `docs/CSM100T_CAN_BRIDGE.md`

### Implementation Status (as of 2026-05-08, branch `feature/depthcontrol`)

All core files are implemented. Key files:

```
funClass/CanOpenFun.kt            — CANopen protocol: SDO/NMT/TPDO frames, jog frames, motor init sequence
data/SowingDepthData.kt           — ServoCalibration + SowingDepthState (masterEnabled, lastHeardMs)
coroutine/SowingDepthCoroutine.kt — 500ms control loop, Phase 1–5, timestamp offline detection, init cooldown retry
ui/SowingDepthScreen.kt           — 8-motor status, master switch (red/green card), global target, per-motor dialog
ui/DepthCalibrationScreen.kt      — Limit setting + 5-point direct calibration, two-phase jog deceleration
CanReceiveCoroutine.kt            — TPDO/heartbeat → isOnline + currentPosition + lastHeardMs
ViewModelAndPublic.kt             — sowingDepthState LiveData, updateMasterEnabled, updateServoCalibration
MySharedPreFun.kt                 — Depth calibration persistence (limits, fit coefficients, node IDs)
```

### Critical Implementation Notes

1. **Offline detection**: `CanReceiveCoroutine` sets `isOnline=true` + updates `lastHeardMs` on every TPDO/heartbeat. `SowingDepthCoroutine` Phase 3 marks offline only when `System.currentTimeMillis() - lastHeardMs > 5000ms`. The old position-change proxy is removed — it falsely flagged stationary motors.

2. **First position command after init**: `motorInitCooldown[i]=6` is set after Phase 2 init. Phase 4 clears `lastSentTargetDepth` during cooldown to force retry for 3s (handles DS402 settle time after Enable Operation).

3. **Bit4 toggle**: `buildAbsoluteMoveFrames` sends `0x6040=0x000F` then `0x6040=0x002F` to guarantee Bit4 0→1 transition. Required for motor to accept new setpoint.

4. **Jog stop (two-phase)**: On release — send `0x60FF=0` (velocity zero, motor decelerates per `0x6084`), wait `jogSpeed×1000/accel + 200ms` (max 4s), then restore position mode (`0x0007 → 0x6060=1 → 0x000F`).

5. **Master switch**: `masterEnabled` (not persisted, defaults false). Controls Phase 2 init gate and Phase 4 dispatch gate. ON→OFF transition sends `0x6040=0x0006` (Shutdown) to all initialized motors and resets `motorInitialized`/`motorInitCooldown`/`lastSentTargetDepth`.

6. **SDO timing**: 20ms min between SDO commands to same node. SDO reply (0x580+ID) comes back via `CanReceiveCoroutine`.

7. **Encoder**: 32768 pulses/rev. With 5mm lead screw: 32768 pulses = 5mm travel. `fitA`/`fitB` coefficients: `depth_mm = fitA × encoderPos + fitB`.

### Next Development Target

**Indirect measurement calibration mode** in `DepthCalibrationScreen` — Step 2 currently only has direct measurement (jog to 5 equal positions, measure with tape). The new mode uses standard gauge blocks (2/4/6/8/10cm): user jogs the depth wheel down onto each block, records encoder position, system fits the curve without manual measurement.

Full specification: `docs/SOWING_DEPTH_IMPLEMENTATION.md` Section 七.
