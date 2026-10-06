# Battery Guard

Battery monitoring and safety-alert application for the **Skydroid G20** smart controller
(Android 13) with a **Skydroid GR01** telemetry link.

The app watches a UAV flight battery in real time, computes cell health and a dynamic
return-to-launch threshold, and warns the operator before the pack becomes the reason the
aircraft does not come home.

> **Status — Milestone 1 of 10.** The architecture, domain models, telemetry boundary,
> simulator and live dashboard are in place and running. Battery calculations, dynamic
> RTL, the alert engine, TTS/vibration, the spray interlock, blackbox logging and the
> MAVLink/serial source are **not implemented yet** — see [Roadmap](#roadmap). Until they
> land, the dashboard deliberately shows no alert colours and no RTL figure, because
> making a safety claim it cannot back up would be worse than showing nothing.

---

## What works today

- Single-page Compose operator dashboard that updates live at 1 Hz.
- A physically-plausible **telemetry simulator**: a 12S 16 Ah pack that is coulomb-counted,
  with per-cell open-circuit voltage derived from a state-of-charge curve and `I x Ri` sag
  subtracted under load.
- Five selectable demo scenarios (Normal Flight, Low Battery, Cell Imbalance, RTL Required,
  Emergency) plus 1x–60x playback speed.
- Cell statistics (min / max / average / ΔV) computed by a pure, testable use case.
- The complete package structure and DI boundary that the rest of the build slots into.

## What is deliberately not claimed

- **No live aircraft is being monitored.** Every number on screen comes from the on-device
  simulator. The dashboard says so, on screen, permanently.
- **No serial or MAVLink connection exists yet.** The `TelemetryDataSource` boundary is in
  place, but only the mock implementation is bound.
- **No pump control.** The spray interlock is specified as an abstraction only, because the
  physical pump protocol is not defined anywhere in the requirements.

---

## Architecture

```
Skydroid GR01 / /dev/ttySx          MockTelemetryDataSource
            |                                  |
            +-------------+--------------------+
                          |
                  TelemetryDataSource            <- the only hardware boundary
                          |
              Telemetry Repository / ViewModel
                          |
   +----------+-----------+-----------+----------+
   |          |           |           |          |
Battery     RTL        Alert      Flight      Cell
Health   Calculator   Engine      Time       Health
   |          |           |           |          |
   +----------+-----------+-----------+----------+
                          |
                   DashboardUiState              <- one immutable state object
                          |
                    Compose Dashboard
                          |
        TTS / Vibration / Spray Interlock
                          |
                  Blackbox Logger -> Room
```

The rule the whole design hangs on: **the UI never computes a safety number.** Every value
in `DashboardUiState` is produced by the domain layer; Compose only formats and lays it out.
There is no threshold anywhere in the `presentation` package.

### Package layout

| Package | Responsibility |
| --- | --- |
| `data/telemetry` | `TelemetryDataSource` boundary, the mock simulator, and (later) the serial/MAVLink source |
| `domain/model` | `BatteryTelemetry`, `GpsData`, `FlightState`, `ConnectionState`, `AlertLevel` |
| `domain/usecase` | Pure calculations: cell health, and later sag, RTL, flight time and alerts |
| `di` | Hilt bindings — the single place the telemetry source is chosen |
| `presentation/dashboard` | `DashboardScreen`, `DashboardViewModel`, `DashboardUiState` |
| `presentation/components` | Reusable dashboard primitives |
| `presentation/theme` | Dark operator palette; semantic colours reserved for the alert engine |

---

## Building and running

**Requirements:** Android Studio (Koala or newer), JDK 17, Android SDK 34.

```bash
# First time only, if the Gradle wrapper jar is not present:
gradle wrapper

./gradlew assembleDebug          # build
./gradlew test                   # unit tests
./gradlew installDebug           # install on a connected device or emulator
```

Or simply open the project root in Android Studio and press Run. On first sync Android
Studio will prompt to accept the SDK licences and install platform 34 if it is missing.

**Target device:** Skydroid G20 (Android 13). The dashboard also runs on a standard phone
or tablet emulator, which is how it is developed and demonstrated.

---

## Using demo mode

Open the app — it connects to the simulator automatically. The **Demo Mode** card at the
bottom switches scenarios:

| Scenario | What it sets up | What it is for |
| --- | --- | --- |
| Normal Flight | 98%, ~4.1 V/cell, tight spread | Baseline healthy pack |
| Low Battery | 23%, ~3.51 V/cell | Both Warning triggers together |
| Cell Imbalance | 75%, ~0.12 V cell spread | Imbalance with healthy cell voltages |
| RTL Required | 26% at 2.4 km from home | Distance driving the RTL threshold |
| Emergency | 9%, ~3.30 V/cell | The lowest safety band |

Each scenario starts from a designed state rather than waiting for a slow drain, so a full
demonstration takes seconds. **Sim speed** (1x–60x) accelerates the discharge further; the
simulator sub-steps so that high speeds cannot skip past an alert band in a single tick.

Selecting a scenario restarts the simulation from that scenario's starting condition.

---

## Roadmap

| Phase | Scope | State |
| --- | --- | --- |
| 1 | Project, Compose, MVVM, dashboard | **Done** |
| 2 | Mock telemetry, StateFlow | **Done** |
| 3 | Battery calculations — voltage sag, discharge rate, flight time | Pending |
| 4 | Dynamic RTL engine with 15% safety margin | Pending |
| 5 | Alert engine — Notice / Warning / Critical / Emergency / Cell Fault | Pending |
| 6 | TTS, vibration, spray interlock abstraction | Pending |
| 7 | Room blackbox logging and diagnostic view | Pending |
| 8 | MAVLink parser and serial telemetry source | Pending |
| 9 | Unit tests and error handling | Pending |
| 10 | UI polish, APK, final test | Pending |

---

## Requirements this is built against

- **Telemetry:** `BATTERY_STATUS`, `SYS_STATUS`, `GLOBAL_POSITION_INT` over USB-UART or an
  internal `/dev/ttySx` interface; battery chemistry and cell count auto-detected from
  baseline voltage.
- **Battery health:** voltage-sag compensation (`V_rest = V_measured + I x Ri`), cell delta
  alert at ΔV > 0.08 V, discharge rate in mAh/min and estimated remaining flight time.
- **Dynamic RTL:** `Required% = (time to home x discharge rate) + 15%`, with an
  un-dismissable critical alert when remaining drops below required.
- **Alerts:** Notice (30%), Warning (20% or ≤ 3.65 V/cell), Critical (dynamic RTL or
  ≤ 3.50 V/cell), Emergency (≤ 3.40 V/cell or rapid sag), Cell Fault (ΔV > 0.08 V).
- **Safety:** spray interlock at 20%, periodic TTS announcements, blackbox logging of
  battery/current/temperature against GPS.

### Where the requirements stop short

Two things the specification requires but does not define, so they are configurable
abstraction points rather than invented values:

1. **Cell-chemistry detection thresholds.** The requirement says to auto-detect 6S/12S/14S
   from baseline voltage but does not give the threshold table. The detector will take
   configurable thresholds rather than hardcoded numbers that merely look plausible.
2. **Spray pump control protocol.** The interlock *behaviour* is specified; the physical
   control interface is not. It is modelled behind an interface, and the default
   implementation does not drive hardware.
