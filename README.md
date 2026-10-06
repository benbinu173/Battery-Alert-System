# Battery Guard

Battery monitoring and safety-alert application for the **Skydroid G20** smart controller
(Android 13) with a **Skydroid GR01** telemetry link.

The app watches a UAV flight battery in real time, computes cell health and a dynamic
return-to-launch threshold, and warns the operator before the pack becomes the reason the
aircraft does not come home.

> **Status — Phase 6 of 10.** The architecture, telemetry boundary, simulator and live
> dashboard are running, the battery-health calculations (voltage-sag compensation,
> discharge rate, remaining flight time, cell statistics, pack-configuration detection)
> are implemented and unit-tested, the **dynamic RTL requirement (FR 3.1)** is computed
> and displayed, the **alert engine (the full alert matrix)** drives the dashboard's
> colour, banner and action prompts, and the alerts now **speak, vibrate and hold the
> spray interlock** (FR 5.1–5.3). Blackbox logging and the MAVLink/serial source are
> **not implemented yet** — see [Roadmap](#roadmap).

---

## What works today

- Two-column Compose operator dashboard, laid out for the G20's landscape screen and
  updating live at 1 Hz: an animated arc gauge, a cell-balance chart, rolling voltage and
  current trends, and the pack/position/flight readouts.
- A physically-plausible **telemetry simulator**: a 12S 16 Ah pack that is coulomb-counted,
  with per-cell open-circuit voltage derived from a state-of-charge curve and `I x Ri` sag
  subtracted under load.
- Five selectable demo scenarios (Normal Flight, Low Battery, Cell Imbalance, RTL Required,
  Emergency) plus 1x–60x playback speed.
- **Voltage-sag compensation** (FR 2.1) — the loaded reading, the reconstructed resting
  voltage, and the per-cell resistance the reconstruction used, all shown together.
- **Discharge rate** in mAh/min and an **estimated remaining flight time** (FR 2.3), with
  the estimate refused rather than faked when the aircraft is parked or current is unknown.
- **Cell statistics** (min / max / average / ΔV) and a balance chart that plots each cell's
  offset above the weakest cell, so the on-screen gap between the tallest and shortest bar
  is exactly the ΔV the requirements threshold.
- **Pack configuration resolution** (FR 1.3) — cell count counted from cell telemetry where
  available, inferred from baseline voltage otherwise, with the basis and confidence
  surfaced rather than hidden.
- **Dynamic RTL requirement** (FR 3.1) — `trip + 15% reserve`, with the trip and the reserve
  reported separately, the return speed and consumption rate shown, and a threshold bar
  that puts the requirement and the remaining charge on the same axis.
- **The alert engine** (FR 3.2, the alert matrix) — every rule evaluated independently,
  results ranked by severity, and the worst one driving a banner that states both the
  condition and the action prompt. No dismiss control exists, which is how FR 3.2's
  "un-dismissable" requirement is satisfied rather than worked around. Each card is
  coloured only by the alerts that are actually about it.
- **Spoken and haptic alerts** (FR 5.2) — the announcer speaks the active condition, the
  haptic channel plays a distinct pattern per level, and a policy decides when repeating is
  news rather than noise: escalation is immediate, repetition waits 60 seconds, and a
  flickering reading cannot talk over itself.
- **The spray interlock** (FR 5.1) — spraying is inhibited at or below 20%, the dashboard
  reports the interlock state rather than offering a control, and the pump itself sits
  behind a `SprayController` interface because the requirements never define one.
- 132 unit tests over the pure calculation layer, the state object and the safety policy.

## What is deliberately not claimed

- **No live aircraft is being monitored.** Every number on screen comes from the on-device
  simulator. The dashboard says so, on screen, permanently.
- **No serial or MAVLink connection exists yet.** The `TelemetryDataSource` boundary is in
  place, but only the mock implementation is bound.
- **No pump control.** The spray interlock is specified as an abstraction only, because the
  physical pump protocol is not defined anywhere in the requirements. `NoOpSprayController`
  is bound, so the interlock *decision* is real and the wire format is not invented.
- **No blackbox log yet.** The alerts are announced and the interlock is held, but the
  GPS-mapped battery history the requirements ask for is written to nothing until Phase 7.

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
              +-----------+-----------+
              |                       |
       Compose Dashboard      SafetyCoordinator    <- side effects, off the same frames
                                      |
                    TTS / Vibration / Spray Interlock
                          |
                  Blackbox Logger -> Room
```

The two branches matter. The dashboard consumes state; `SafetyCoordinator` consumes the same
frames and produces *effects*. Keeping them separate is what lets the dashboard be
recomposed, rotated or recreated without the app repeating an announcement — and it is what
would let the safety layer move into a foreground service later without touching the alert
engine at all.

The rule the whole design hangs on: **the UI never computes a safety number.** Every value
in `DashboardUiState` is produced by the domain layer; Compose only formats and lays it out.
There is no threshold anywhere in the `presentation` package.

### Package layout

| Package | Responsibility |
| --- | --- |
| `data/telemetry` | `TelemetryDataSource` boundary, the mock simulator, and (later) the serial/MAVLink source |
| `domain/model` | `BatteryTelemetry`, `GpsData`, `FlightState`, `ConnectionState`, `AlertLevel`, `BatteryAlert`, `BatteryConfiguration` |
| `domain/usecase` | Pure calculations: `CellHealth`, `VoltageSag`, `FlightTime`, `RtlCalculator`, `VoltageTrend`, `AlertEngine`, `SprayInterlock`, `BatteryConfigurationDetector`, `BatteryConfigurationResolver` |
| `di` | Hilt bindings — the two places hardware is chosen: what the app listens to, and what it acts through |
| `safety` | The side effects of an alert: `SafetyCoordinator`, `AnnouncementPolicy`, and the TTS / haptic / spray boundaries |
| `presentation/dashboard` | `DashboardScreen`, `DashboardViewModel`, `DashboardUiState` |
| `presentation/components` | Dashboard primitives, and the Canvas-drawn gauge, balance chart and sparklines |
| `presentation/theme` | Dark operator palette; semantic colours reserved for the alert engine |

### Why the use cases are pure objects

Every calculation in `domain/usecase` is a stateless `object` with no Android, coroutine or
clock dependency. That is what makes them testable against fixed inputs, and it is why the
ViewModel can call them directly without a fake or a mock.

They also share one failure convention: **null means "unknown", never zero.** A pack whose
current is not reported has an unknown discharge rate, not a rate of zero; an aircraft
parked with the motors stopped has an unknown remaining flight time, not an infinite one;
an aircraft with no GPS fix has an unknown RTL requirement, not a requirement of 0%.
`VoltageSag`, `FlightTime`, `RtlCalculator`, `CellHealth`, `VoltageTrend` and
`BatteryConfigurationResolver` all return null in those cases so a caller cannot
accidentally render a confident number.

`AlertEngine` extends the same idea to the alert itself: it has no path that produces an
alert level without a rule behind it. There is no `else` branch that defaults to a colour,
so a colour on screen always names the number that caused it.

---

## How the alert engine decides

Each rule in the matrix is evaluated independently, and the results are ranked. Two
decisions in there are judgement calls the requirements do not make:

**It returns every active condition, not just the worst.** At 18% charge with a 0.12 V cell
spread, the honest answer is "you are low *and* a cell is failing" — and the cell fault is
the one that will not be fixed by landing sooner. The banner leads with the worst
condition and the others appear as chips beneath it.

**Voltage thresholds are read under load, from the weakest cell.** 3.40 V/cell is roughly
where an ESC browns out, and it browns out because of what the pack delivers *under
current*. A rule using the sag-compensated resting voltage would stay silent while the
pack sagged straight through cutoff. The weakest cell is the one that reaches that point
first, and under load it is also the one that sags hardest, so it is the right single
number to threshold.

The thresholds live in one `AlertThresholds` object rather than being spread through the
logic, because they are the only numbers the safety claim depends on and it should be
possible to see all of them at once.

**Each card is coloured by its own alerts, not by the headline.** The obvious
implementation — every card taking the worst active level — means a Critical raised because
the aircraft is 2.4 km from home also turns the cell-balance chart red. The operator reads
that as a failing cell, and lands for the wrong reason. So the cell chart answers only to
the cell rules, the voltage sparklines to the rules about what the pack is delivering, and
the RTL threshold bar to the RTL rule alone. `DashboardUiState.levelFor` does the scoping,
and `DashboardUiStateTest` asserts — among other things — that an RTL alert leaves the cell
rules at NORMAL.

---

## How the app speaks, buzzes and stops spraying

The alert engine answers *what is wrong*. A separate layer answers *what to do about it*,
because those are side effects and the ViewModel's job is to produce state.

**The side effects hang off the same frames the dashboard renders.** `SafetyCoordinator` is
called from the telemetry pipeline upstream of `stateIn`, so what is spoken and what is on
screen can never describe two different situations — and the app is silent when nothing is
watching the dashboard, which is the right behaviour for a foreground safety display.

**FR 5.2's "every 60 seconds" is treated as a floor, not a schedule.** Taken literally, a
pack that goes from a Notice to an Emergency two seconds after the last announcement waits
fifty-eight seconds to mention it. So the policy is: escalation is announced immediately,
everything else waits out the interval, and recovery is announced so the operator is not
left wondering whether the app died or the problem went away. The property that keeps this
from chattering is that state is recorded *only when something is actually announced* — a
reading flickering across the threshold is compared against what was last said, not against
the momentary quiet in between.

**The haptic patterns encode severity twice.** Rhythm and amplitude both change per level,
because a pattern a hand cannot distinguish from the one below it carries no information.
Each is also a different length, so the shape alone tells the operator which band they are
in before the voice starts.

**The interlock is shown, not offered.** There is no spray button on the dashboard. The
interlock is not something the operator may switch off, and a disabled-looking control would
imply it might be. The card reports what the system has already done.

**Unknown charge inhibits spraying.** Every other calculation in `domain/usecase` treats null
as "unknown" and refuses to answer. An interlock cannot: refusing to answer means the pump
keeps running, and the cost of that is an aircraft that does not come home. So this is the
single place in the app where unknown resolves to the restrictive answer — deliberately, and
it is covered by a test that says so.

---

## Tests

```bash
./gradlew test
```

132 unit tests cover the pure calculation layer, the presentation state and the safety
policy — sag
compensation and its sign convention under charge, the mAh/min conversion, the guards that
stop a parked aircraft producing an infinite flight time, ΔV across the 0.08 V fault
threshold, zero-padded cell packets, chemistry detection including the cases where it must
refuse to answer, configuration resolution from cell telemetry versus baseline voltage, the
RTL requirement including its behaviour when the aircraft is hovering or the GPS distance
is unknown, the fitted voltage-decline rate, every rule and boundary in the alert
matrix, and the announcement and interlock rules including their escalations and their
unknown-charge case.

The RTL and alert tests use the same numbers as the demo scenarios, so retuning the
simulator to a state its scenario is not named for fails a test rather than quietly
producing a misleading demo.

A few are worth calling out because they guard presentation rather than arithmetic:

- **`numbers use a decimal point whatever the device locale is`** sets the JVM's locale to
  German and asserts the alert still reads `3.48 V`, not `3,48 V`. The decimal point is
  part of the safety claim, not a display preference.
- **`one noisy sample cannot set the rate`** drops a single sample to 44 V and asserts the
  fitted decline stays below the rapid-sag threshold. Measuring endpoint-to-endpoint would
  read that dropout as 0.037 V/cell/s and announce that the pack was collapsing.
- **`an rtl alert does not colour the cell rules`** is the scoping regression above. It is
  the kind of bug that is invisible in review and obvious in the field.
- **`a flickering level does not chatter`** wobbles the alert level across the threshold a
  dozen times inside one interval and asserts the app stays silent. It is the test that
  would fail if the policy ever recorded state when it decided *not* to announce.
- **`an unknown charge inhibits`** pins the one deliberate inversion of the null convention,
  so it cannot be "tidied up" into consistency later by someone reading only the other use
  cases.

The tests run on the JVM, which is why the calculation layer and the state object were kept
free of Android dependencies.

---

## Building and running

**Requirements:** Android Studio, JDK 17 or newer, Android SDK 34.

The build uses **Gradle 9.0**, which is what lets it run on the JDK bundled with recent
Android Studio releases (JDK 25). Older Gradle versions cap out earlier — 8.9 supports up
to Java 22 — so if you downgrade the wrapper, point `JAVA_HOME` at a matching JDK.

```bash
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

| Scenario | What it sets up | Alert it raises |
| --- | --- | --- |
| Normal Flight | 98%, ~4.1 V/cell, tight spread | None — the all-clear |
| Low Battery | 23%, ~3.51 V/cell | **Warning**, from the cell voltage rule |
| Cell Imbalance | 75%, ~0.12 V cell spread | **Cell fault**, with the pack otherwise healthy |
| RTL Required | 26% at 2.4 km from home | **Critical**, from the dynamic RTL rule |
| Emergency | 9%, ~3.30 V/cell | **Emergency**, from the cell voltage rule |

Each scenario starts from a designed state rather than waiting for a slow drain, so a full
demonstration takes seconds. **Sim speed** (1x–60x) accelerates the discharge further; the
simulator sub-steps so that high speeds cannot skip past an alert band in a single tick.

Selecting a scenario restarts the simulation from that scenario's starting condition.

Turning the speed up is also the way to watch the matrix *escalate* rather than simply
appear: the Low Battery scenario walks the banner from Warning through Critical to
Emergency as the pack drains, and the chip row fills in around it.

The same walk is the way to hear the announcement policy work. Select a scenario and the
first alert is spoken at once; it is then repeated every 60 seconds, but each escalation
speaks immediately regardless of the clock, with its own vibration pattern. Drop below 20%
and the spray row in the Flight Safety card changes to **Inhibited** — that is FR 5.1's
interlock engaging, and it is reported, not offered as a control.

Volume up. There is nothing to configure.

---

## Roadmap

| Phase | Scope | State |
| --- | --- | --- |
| 1 | Project, Compose, MVVM, dashboard | **Done** |
| 2 | Mock telemetry, StateFlow | **Done** |
| 3 | Battery calculations — voltage sag, discharge rate, flight time, pack configuration | **Done** |
| 4 | Dynamic RTL engine with 15% safety margin | **Done** |
| 5 | Alert engine — Notice / Warning / Critical / Emergency / Cell Fault | **Done** |
| 6 | TTS, vibration, spray interlock abstraction | **Done** |
| 7 | Room blackbox logging and diagnostic view | Pending |
| 8 | MAVLink parser and serial telemetry source | Pending |
| 9 | Error handling and parser tests | Pending |
| 10 | Final polish, APK, on-device test | Pending |

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

Seven things the specification requires but does not define. Each is handled as a
configurable or explicitly-unknown point rather than an invented value:

1. **The RTL formula has a missing input.** FR 3.1 gives `Required(%) = (Discharge Rate
   %/sec x time-to-home) + 15%`. A rate in *percent* per second needs to know what
   percentage is a percentage **of** — and neither the telemetry nor the chemistry gives
   the pack's capacity in mAh. Assuming a pack size would be silently wrong on every
   airframe but one. Instead the capacity is derived from two quantities the source already
   reports (`remainingCapacityMah` and `batteryPercentage`), which cancels the capacity out
   of the formula entirely — see `RtlCalculator`. Its limit is stated in the code: the
   derivation divides by state of charge, so below 5% the assessment is withheld rather
   than reported with false precision.
2. **Return speed is unspecified.** "Time to home" needs a speed the aircraft has not been
   given. The current cruise speed is used when it is usable, and a conservative default
   otherwise — including while hovering, where dividing by ground speed would give an
   infinite trip and a permanent 100% requirement.
3. **Cell-chemistry detection thresholds.** The requirement says to auto-detect 6S/12S/14S
   from baseline voltage but gives no threshold table, and the arithmetic is genuinely
   ambiguous on its own — 25.2 V is exactly a full 6S pack, a 7S pack at 3.60 V/cell, or a
   14S pack at 1.80 V/cell. `BatteryConfigurationDetector` therefore takes its reference
   voltage and match tolerance as parameters rather than burying them, and returns null
   when no candidate is within tolerance instead of guessing.
4. **Chemistry cannot always be determined from voltage.** Li-ion and LiPo share the same
   nominal (3.70 V) and full (4.20 V) per-cell voltages, so no voltage reading can separate
   them. `BatteryConfigurationResolver` reports a chemistry only when the voltage *proves*
   it (above 4.25 V/cell, which is LiHV), and otherwise reports the cell count alone. The
   dashboard states this on screen rather than showing a confident wrong answer.
5. **Cell internal resistance.** FR 2.1 specifies `V_rest = V_measured + I x Ri` without
   defining Ri. `VoltageSag` ships a documented default and the dashboard prints the value
   it used next to every figure derived from it, so a number that depends on an assumed
   resistance is never presented as though it were measured.
6. **The spray pump protocol.** FR 5.1 specifies the interlock *behaviour* — stop spraying
   at 20% — and never defines a control interface for the pump: no protocol, no register
   map, no vendor document. Inventing a wire format and calling it pump control is the
   easiest possible way to make this submission dishonest, so the decision is implemented
   and tested (`SprayInterlock`), the boundary is given a defined shape
   (`SprayController`), and the implementation bound today drives nothing. Replacing it is a
   one-line change in `SafetyModule`; no domain or UI code moves.
7. **"Rapid sag" is named as an Emergency trigger but never quantified.** Emergency can be
   reached by a low voltage *or* by a fast decline, and the matrix gives no rate. 0.05 V
   per cell per second is used — the pack would fall from 3.65 to 3.40 V/cell in five
   seconds, which is a collapse rather than a discharge — and it lives in
   `AlertThresholds` beside the other boundaries.

   It is worth being precise about what the demo can show here. The rate is fitted from
   *wall-clock* timestamps, because that is what a real serial link samples at, so demo
   acceleration does not inflate it. A pack draining honestly, even at 60x, does not trip
   this rule — only the Emergency scenario run at 60x, as it approaches empty, produces a
   genuine decline fast enough to reach it. The rule is otherwise covered by the unit
   tests rather than by a scripted scenario, and pretending otherwise would be worse than
   saying so.

Two further gaps sit in FR 5.2 and are handled the same way, by making the missing value a
named, configurable parameter rather than a magic number buried in the logic:

- **The 60-second announcement cadence is a floor, not a schedule.** The requirement gives
  one interval for every severity, which is dangerous at the top of the range. Escalation is
  therefore announced immediately regardless of the clock; the interval itself is a
  constructor parameter (`AnnouncementPolicy.repeatIntervalMillis`) so the behaviour is
  testable in milliseconds rather than by waiting a minute.
- **The haptic patterns are not specified at all.** One pattern per level is defined in
  `AndroidHapticChannel`, with severity encoded in both rhythm and amplitude, because the
  requirements ask for vibration without saying what a Notice should feel like or how it
  should differ from an Emergency.
