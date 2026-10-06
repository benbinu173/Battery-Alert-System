# Battery Guard

Battery monitoring and safety-alert application for the **Skydroid G20** smart controller
(Android 13) with a **Skydroid GR01** telemetry link.

The app watches a UAV flight battery in real time, computes cell health and a dynamic
return-to-launch threshold, and warns the operator before the pack becomes the reason the
aircraft does not come home.

> **Status — Phase 10 of 10.** Everything in the build plan is implemented and unit-tested:
> the architecture, the telemetry boundary, the simulator, the live dashboard, the
> battery-health calculations (voltage-sag compensation, discharge rate, remaining flight
> time, cell statistics, pack-configuration detection), the **dynamic RTL requirement
> (FR 3.1)**, the **alert engine (the full alert matrix)**, the **spoken, haptic and spray
> interlock behaviour (FR 5.1–5.3)**, the **blackbox recorder (FR 5.3)**, and a **MAVLink
> v1/v2 decoder** that turns real telemetry bytes into the same domain objects the simulator
> produces — including `HOME_POSITION`, so FR 3.1's dynamic RTL now has a real home distance
> to work from.
>
> **One thing is written but not verified.** The USB-UART transport that would feed the
> decoder from an actual aircraft exists, compiles, and has never been run — verifying it
> needs the aircraft. The app still ships bound to the simulator, and
> [Reading real MAVLink](#reading-real-mavlink) says exactly what that leaves untested.

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
- **A blackbox flight recorder** (FR 5.3) — battery, current, temperature, cell health and
  GPS written to a local Room database, at a 1 Hz heartbeat that goes to full resolution
  the moment the alert level changes or the link drops. The **Flight recorder** screen
  reads it back: a summary of one flight above the raw rows, with the reason each row was
  kept.
- **A MAVLink decoder** (FR 1.1–1.2) — a resynchronising stream parser for MAVLink v1 and
  v2, checksummed with MAVLink's own CRC and decoding `HEARTBEAT`, `SYS_STATUS`,
  `GLOBAL_POSITION_INT`, `HOME_POSITION`, `VFR_HUD` and `BATTERY_STATUS` into the same
  domain objects the simulator produces. The wire itself sits behind a `TelemetryTransport`
  seam; see [Reading real MAVLink](#reading-real-mavlink).
- **Home distance and a real dynamic RTL** (FR 3.1) — `HOME_POSITION` is latched when it
  arrives and outlives the message that carried it, the distance to it is computed by a
  haversine (`GeoDistance`, unit-tested against known city pairs and the antimeridian), and
  the RTL requirement is then assessed from a real distance rather than from the simulator's
  fiction.
- **A pack-capacity configuration point** — FR 3.1's missing input, resolved. `PackCapacity`
  takes the charge left from whichever source has it: a reported measurement if the autopilot
  sends one, otherwise the configured capacity times the state of charge. The **Aircraft**
  screen sets it, validates it against a plausible range rather than clamping it, and then
  reports live whether the figure it just accepted is the one the dashboard is using.
- **A link that recovers** — the serial source watches its own wire, reopens a port that
  drops, backs off while it cannot, and reports `RECONNECTING` on the dashboard rather than
  going quiet. `LinkHealthSource` is a separate seam so the simulator, which has no wire, is
  not made to answer a question it has no answer to.
- **A USB-UART transport** — `UsbSerialTransport`, behind the same `TelemetryTransport`
  interface the simulator's fake uses: adapter enumeration, the Android USB permission
  dialog, 8N1 at MAVLink's conventional 57600 baud, and the library's reader bridged into a
  `Flow<ByteArray>`. **Written, compiling, never run.** See
  [What this does not do](#what-this-does-not-do).
- **282 unit tests** over the pure calculation layer, the presentation state, the safety and
  recording policies, the serial source's own state machine, and the MAVLink decoder.

## What is deliberately not claimed

- **No live aircraft is being monitored.** Every number on screen comes from the on-device
  simulator. The dashboard says so, on screen, permanently.
- **Nothing is plugged into an aircraft.** A MAVLink decoder exists and is unit-tested
  against synthetic frames, and the USB-UART transport that would feed it is written — but
  it has never been run against real hardware, because that needs the aircraft. The
  simulator is still the source that is bound. Everything described in
  [Reading real MAVLink](#reading-real-mavlink) has run only in tests.
- **The USB transport compiles and that is all that is known about it.** It is the one
  class in this repository whose correctness rests on nothing but review. Its failure mode
  is deliberately legible rather than plausible: a link that cannot open shows **Link
  error** and a diagnostics screen with zero frames decoded, not a dashboard full of
  numbers that happen to be wrong.
- **The MAVLink dialect constants are unverified.** The per-message CRC extras and field
  offsets were written without access to the vehicle's generated headers, and a wrong one
  looks exactly like a dead link rather than like a wrong number. They ship as seeds the
  parser corrects at runtime, not as values anyone has confirmed against the aircraft.
- **No pump control.** The spray interlock is specified as an abstraction only, because the
  physical pump protocol is not defined anywhere in the requirements. `NoOpSprayController`
  is bound, so the interlock *decision* is real and the wire format is not invented.
- **No export or cloud upload.** The blackbox is on-device and bounded; FR 5.3 asks for the
  log to exist, not for it to leave the aircraft. Getting it off the device is a reported
  gap, not a hidden one.

---

## Architecture

```
Skydroid GR01 / /dev/ttySx          MockTelemetryDataSource
            |                                  |
     TelemetryTransport                        |
     the only hardware-specific code            |
            |                                  |
  SerialTelemetryDataSource                    |
            |                                  |
            +-------------+--------------------+
                          |
                  TelemetryDataSource            <- the only telemetry boundary
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
                   BlackboxRecorder -> Room
```

The two branches matter. The dashboard consumes state; `SafetyCoordinator` and
`BlackboxRecorder` consume the same frames and produce *effects*. Keeping them separate is
what lets the dashboard be recomposed, rotated or recreated without the app repeating an
announcement — and it is what would let the safety layer move into a foreground service
later without touching the alert engine at all.

Both effect branches hang off the pipeline upstream of `stateIn`, and the dashboard's state
is collected in `GuardApp` rather than inside the dashboard. That pairing is deliberate: a
`WhileSubscribed` pipeline whose only collector was the dashboard would stop the telemetry —
and therefore the recorder and the alarms — the moment the operator opened the flight
recorder to read it.

The rule the whole design hangs on: **the UI never computes a safety number.** Every value
in `DashboardUiState` is produced by the domain layer; Compose only formats and lays it out.
There is no threshold anywhere in the `presentation` package.

### Package layout

| Package | Responsibility |
| --- | --- |
| `data/telemetry` | `TelemetryDataSource` boundary, the mock simulator, the serial source and its reconnect loop, `LinkHealthSource`, and the `TelemetryTransport` hardware seam with its USB-UART implementation |
| `data/telemetry/mavlink` | Framing, checksums, the self-correcting dialect table, and the message decoders |
| `data/aircraft` | `AircraftProfileStore` — the per-airframe settings the telemetry cannot supply, currently the pack capacity |
| `domain/model` | `BatteryTelemetry`, `GpsData`, `FlightState`, `ConnectionState`, `AlertLevel`, `BatteryAlert`, `BatteryConfiguration`, `BlackboxRecord` |
| `domain/usecase` | Pure calculations: `CellHealth`, `VoltageSag`, `FlightTime`, `GeoDistance`, `PackCapacity`, `RtlCalculator`, `VoltageTrend`, `AlertEngine`, `SprayInterlock`, `BlackboxSampler`, `BlackboxAnalysis`, `BatteryConfigurationDetector`, `BatteryConfigurationResolver` |
| `data/database` | Room entity, DAO and database for the blackbox |
| `data/repository` | `BlackboxRepository` and the `BlackboxRecorder` that decides and writes |
| `di` | Hilt bindings — the places a boundary is chosen: what the app listens to, what it acts through, where it stores, and where it runs |
| `safety` | The side effects of an alert: `SafetyCoordinator`, `AnnouncementPolicy`, and the TTS / haptic / spray boundaries |
| `presentation/dashboard` | `DashboardScreen`, `DashboardViewModel`, `DashboardUiState` |
| `presentation/aircraft` | The airframe screen: `AircraftScreen`, `AircraftViewModel`, `AircraftUiState` |
| `presentation/diagnostics` | The flight recorder screen: `DiagnosticsScreen`, `DiagnosticsViewModel`, `DiagnosticsUiState` |
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

## The flight recorder

FR 5.3 asks for battery, current and temperature to be logged against GPS. The interesting
decisions are not *what* to write but *when*, and *without slowing the aircraft down*.

**Why a plain timer would be the wrong answer.** A recorder that writes every frame is a
disk-filler — a 10 Hz link over a 40-minute flight is 24,000 rows describing a pack that did
not change. A recorder on a fixed timer is worse, because the timer is guaranteed to be
wrong exactly where it matters: the row that explains the incident is the one that lands
between two ticks. So `BlackboxSampler` is a hybrid, and the priority order is the design:

| Priority | Trigger | Why |
| --- | --- | --- |
| 1 | `SESSION_START` | A log that does not begin is not a log |
| 2 | `ALERT_CHANGE` | The row the feature exists for. Recorded whatever the cadence |
| 3 | `LINK_CHANGE` | "The log stops here" and "the link dropped here" must not look the same |
| 4 | `HIGH_RESOLUTION` | At a cell fault and above, every frame is kept |
| 5 | `CADENCE` | The 1 Hz heartbeat that makes the quiet parts legible |

The trigger is stored on each row rather than inferred, because it is the one thing the
numbers cannot supply. A dense cluster of rows in the diagnostic view says that something
happened; the `Why` column says *what*.

**The cadence is not tied to the frame rate.** The simulator publishes at 1 Hz and the
default cadence is 1 s, so rules 2–4 look redundant today. They are not, and they must not
be deleted when a real link is bound: MAVLink telemetry arrives at 10–50 Hz, and a one-second
cadence would then be dropping nine frames in ten at the worst possible moment.

**The write path never blocks the alert path.** `BlackboxRecorder.offer` decides and queues;
a single IO coroutine drains. An alert three hundred milliseconds late because SQLite was
busy is a safety regression caused by an audit feature. A single consumer rather than a
`launch` per row, because a blackbox whose rows are not in the order the aircraft lived them
cannot be read as a timeline.

**It says when it loses rows.** If the queue ever fills, the *oldest* queued row is dropped —
the rows nearest an event matter most — and the count is reported on the recorder screen. A
recorder that silently loses rows is worse than one that admits it: the first makes a gap in
the log look like a gap in the flight.

**The log is a bounded rolling buffer, not an archive.** Past 20,000 rows — a little over
five hours at the heartbeat rate — the oldest are discarded, and a device left running for a
weekend cannot exhaust its storage. The database is created with
`fallbackToDestructiveMigration`, because it is diagnostic telemetry rather than user data
and recreating it costs a few hours of log, not a migration nobody will read.

**Unknown stays unknown through SQLite.** Every nullable column round-trips as null, so "the
link did not report a temperature" survives as unknown rather than arriving back as 0 °C —
the same convention the rest of the codebase uses, applied to storage.

---

## Reading real MAVLink

Bytes from a serial link become the same `BatteryTelemetry`, `GpsData` and `FlightState`
objects the dashboard already consumes, through the same `TelemetryDataSource` boundary —
which means the alert engine, the RTL calculation, the interlock and the recorder all run on
the new source without knowing it is new.

The pipe itself is the one piece that needs the aircraft, and it is behind
`TelemetryTransport`: open, a flow of byte chunks, close. That single interface is what keeps
everything above it testable on the JVM — the parser, the checksums, the decoders and the
link's reconnect loop are all driven in unit tests by synthetic frames, with a fake transport
pumping bytes.

### The transport that has never been run

`UsbSerialTransport` is the implementation of that interface over a real adapter, and it is
the one class in this repository whose correctness rests on nothing but review. It enumerates
attached USB-UART bridges through the driver library's prober rather than matching a
particular VID/PID, raises Android's per-device USB permission dialog, opens the first port at
8N1 / 57600, and bridges the library's reader thread into a `Flow<ByteArray>`.

Three things about it are worth saying plainly rather than leaving for the reader to notice:

**It is deliberately narrow.** It knows nothing about MAVLink — no frame, no checksum, no
message id appears in it. That is what keeps it replaceable and what keeps the untested
surface down to one file: everything that could be wrong in an *interesting* way is on the
tested side of the seam.

**Its failure mode is legible.** If the adapter does not enumerate, or the permission is
refused, or the port will not open, `open()` throws and the dashboard says **Link error** with
zero frames decoded on the diagnostics screen. The dangerous alternative — a transport that
half-works and yields plausible numbers — is not available, because a port that is not
receiving produces no bytes rather than wrong ones.

**It does not transmit.** The link is receive-only. Most MAVLink links will not stream
telemetry until the ground station requests a data stream, so if this is ever run against the
aircraft and the dashboard connects but sits at zero frames, that request is the first thing
to look at — not this class. It is not added here because the requirements describe an alert
system reading a link that already carries traffic, and a ground station that begins
commanding an aircraft it cannot yet see is a much larger claim than this submission is
making.

There is no `ACTION_USB_DEVICE_ATTACHED` receiver either, and that is a decision rather than
an omission: the retry loop already reopens a port on every attempt, so plugging the radio in
while the app is running recovers on its own, and a receiver would be a second mechanism
duplicating the first — with a lifecycle to leak, in exchange for nothing.

### The constants that could not be checked

MAVLink frames are checksummed, and the checksum folds in a per-message byte called the CRC
extra that never appears on the wire. Getting one wrong does not produce a wrong number; it
produces a frame that fails its checksum, which is indistinguishable from a link that is
dead. The extras this app needs were written from memory and **were not verified against
the vehicle's generated headers.**

So they ship as *seeds*, and the parser treats them that way. When a supported message fails
its checksum, `MavlinkCrc.recoverExtra` searches all 256 possible values and finds the one —
at most one — that explains the frame. Three identical recoveries in a row for the same
message id and the table corrects itself, so a wrong constant costs a few frames rather than
the whole flight. The search is exact rather than a heuristic: the fold that mixes the extra
into the checksum is injective, which is what makes "at most one" a proof rather than a hope.

What the correction is *not* is an acceptance path. Roughly one corrupt frame in 256 will
have some byte that explains it, so a frame is only ever decoded against a constant the
dialect already holds; a recovery is evidence about the dialect and never a licence to decode
the frame that produced it. And the divergence is reported the first time it is seen, not
the third, in `LinkHealth.dialectCorrections` — the value to fold back into
`MavlinkMessageSpec` before this ships to anyone with an aircraft.

### Two definitions of one message

`BATTERY_STATUS` has been redefined at least once, and the two definitions put the cell
voltages in different places. Nothing inside a frame says which definition built it.
`BatteryStatusLayout` makes that a named configuration point — an enum, not an offset table
buried in a decoder — which is what the brief's "keep it behind an abstraction or
configuration point" instruction asks for.

A wrong guess here would be dangerous rather than merely wrong: it would produce plausible
cell voltages out of the wrong bytes, and FR 2.2's ΔV > 0.08 V rule would then be checking
numbers that mean nothing while looking perfectly healthy. So the cells are never trusted on
their own. They are summed and compared against `SYS_STATUS`'s independent pack measurement;
if the total disagrees, or if any cell falls outside 2.0–5.0 V, the per-cell data is withheld
from the alert engine entirely — and if the *other* layout would have fitted, it is named in
`suspectedLayout`. A layout error therefore surfaces as missing cell data with the fix
printed beside it, rather than as an imbalance alert that never fires.

There is a second trap in the same message: a 12S pack does not fit the ten-element
`voltages` array, and the last two cells live in a separate overflow array. A decoder reading
only the first array would report ten of twelve cells and stop checking the other two for
imbalance — silently, and exactly where a 12S pack is most likely to drift. Both arrays are
read. The test builder that found this derives every offset from each message's MAVLink
schema rather than from hardcoded numbers, so it disagrees with the decoder the moment either
is wrong; it caught a real misplacement of `time_remaining` the same way.

### Two answers for one battery

`SYS_STATUS` and `BATTERY_STATUS` both report pack voltage and current, and on a real
aircraft they disagree by a few hundred millivolts. The app uses the flight controller's own
measurement, on the grounds that it is the one the autopilot's own failsafes are armed on —
an app that disagrees with the aircraft about the pack is an app that will one day be ignored
at the wrong moment. Cells, percentage and temperature come from `BATTERY_STATUS`, which is
the only message carrying them.

### A link that is up while a message is not

Two watches answer two different questions. The link watchdog asks whether *anything* is
arriving; the assembler times each message type separately, because a link that is up while
one message type has stopped arriving is quieter and more dangerous than a link that has
dropped — the dashboard would go on showing the last good voltage forever while everything
else stayed live, and there is nothing on screen to suggest the number is old.

When the link itself goes silent the readings are dropped outright rather than left to expire
on their own timers. Those windows are deliberately longer than the silence window, so
without this there is a gap in which a live-looking voltage sits next to an ERROR badge.

### A link that comes back

A USB cable works loose. The transport's flow ends, and the question is what the app does in
the next second — because the alternatives are both bad in different ways: give up and the
operator has to restart the app mid-flight, or retry instantly and burn the battery on an
adapter that is not there.

So the source has a third state. `RECONNECTING` is on the dashboard, distinct from both
`CONNECTED` and `ERROR`, because "the link is trying" and "the link has failed" call for
different actions from the operator. The retry loop backs off rather than spinning, and the
backoff is a constructor parameter so the tests drive it in milliseconds rather than by
waiting.

Two details in there are deliberate:

**The reconnect reuses the same watchdog, with the readings cleared.** A link that is being
re-established is not a link that is delivering numbers, and a stale voltage left on screen
under a `RECONNECTING` badge is exactly the failure this whole file is about.

**The transport releases before it reopens.** `UsbSerialTransport.open()` closes whatever it
is still holding before it acquires anything, because nothing else in the path calls `close()`
between a drop and the retry — the retry loop's whole job is to reopen. A `UsbDeviceConnection`
that is reopened without being closed does not fail cleanly; it leaks a file descriptor that
survives the app being backgrounded. Putting the release inside `open()` is the same argument
as putting it in a `finally`: the class that owns the resource is the one that should not need
reminding.

### What this does not do

- **The serial source is not bound.** `TelemetryModule` still binds the simulator, because
  binding a serial source whose transport has never run would trade a working demo for an
  empty dashboard. Switching is four edits, all in that one file, and they are named in its
  KDoc. Nothing in the domain or presentation layers moves for any of them.
- **The spray interlock's pump input is unknown.** No supported message says whether a pump
  is running, so `sprayingActive` has no source and the interlock fails safe. That is a
  property of the requirements, not of this code: FR 5.1 specifies the interlock's behaviour
  and never defines a pump interface.
- **Dynamic RTL over a real link still needs one setting.** `HOME_POSITION` closed the
  distance half of FR 3.1 — but the other half is the pack's capacity in mAh, and no
  supported message reports it. `PackCapacity` resolves that where it can: a source that
  reports the charge left directly needs no configuration, and otherwise the **Aircraft**
  screen supplies the capacity and the state of charge does the rest. So on a real aircraft
  the RTL threshold is **unknown until someone enters the pack size**, and the dashboard
  says so rather than showing a zero. That is the honest shape of the requirement: FR 3.1's
  formula has an input the telemetry does not carry, and the answer is a configuration
  point, not a guessed number.

The gaps that were listed here before have closed and are worth recording as closed, because
each was pinned by a test that would now fail if it reopened: `HOME_POSITION` is decoded,
home is latched so it outlives the message that carried it, the distance to it is a tested
haversine, and the pack capacity that FR 3.1 was missing is a setting with a plausible-range
guard rather than a constant.

---

## Tests

```bash
./gradlew test
```

282 unit tests cover the pure calculation layer, the presentation state, the safety policy,
the recorder, the serial source's own state machine and the MAVLink decoder — sag
compensation and its sign convention under charge, the mAh/min conversion, the guards that
stop a parked aircraft producing an infinite flight time, ΔV across the 0.08 V fault
threshold, zero-padded cell packets, chemistry detection including the cases where it must
refuse to answer, configuration resolution from cell telemetry versus baseline voltage, the
RTL requirement including its behaviour when the aircraft is hovering or the GPS distance
is unknown, the distance to home across the antimeridian, a pack capacity that refuses to be
guessed, the fitted voltage-decline rate, every rule and boundary in the alert
matrix, the announcement and interlock rules including their escalations and their
unknown-charge case, the link's recovery from a dropped port, and the recorder's decision
table and the summary it is read back through.

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
- **`the cadence is measured from the last kept frame, not the last seen one`** feeds the
  sampler frames it declines to keep and then asserts the heartbeat still fires. If a
  skipped frame advanced the clock, the log would go quiet after its first second and
  nothing else in the suite would notice.
- **`a clock that steps backwards does not stop the log`** corrects the device's clock
  mid-flight and asserts logging continues. Without the guard, an NTP sync would silence the
  rest of the flight.
- **`a field no row reported summarises as unknown, not as zero`** is the null convention
  crossing a database boundary, which is where it is easiest to lose.

The MAVLink tests are the largest block, and the ones worth reading are the adversarial
ones — they are written to fail if a safety property is ever traded away for a passing
frame count:

- **`chains to the published CRC catalogue value`** pins the checksum to the CRC-16/MCRF4XX
  reference value for `"123456789"`. Without an external pin, a reversed polynomial would
  still agree with itself in a round-trip test, and every other test in the file would pass
  against a checksum that no aircraft emits.
- **`a corrupt frame is never handed on`** flips a payload byte and asserts nothing comes
  out. The dialect recovery is deliberately off the acceptance path, and this is the test
  that fails if it ever creeps back on.
- **`the link recovers on its own when bytes start again`** and its companion **`a single
  corrupt frame does not rewrite the dialect`** are the two halves of the self-correction
  rule. About one corrupt frame in 256 can be explained by some extra, so one sighting must
  never be enough.
- **`a twelve cell pack reports all twelve cells`** covers the overflow array. Reading only
  the ten-element array would leave the last two cells of a 12S pack unchecked, silently.
- **`a legacy payload read as extended is caught and the other layout is named`** feeds the
  assembler a frame built for the other `BATTERY_STATUS` definition and asserts that the
  cells are withheld and `LEGACY` is suggested as the fix.
- **`a silent link is reported as an error and its readings are dropped`** advances an
  injected clock past the silence window and asserts the published voltage becomes unknown.
  A frozen reading beside an ERROR badge is precisely the failure it guards, and it is the
  reason the link's clock is injected rather than read.

The phases that closed the last two requirement gaps added tests that are written to fail if
the gap quietly reopens:

- **`one degree of longitude loses half its length by sixty north`** pins the haversine
  against a missing `cos`. Without it, every distance at high latitude would be overstated by
  up to a factor of two — and the RTL threshold derived from it would be wrong in the
  direction that leaves the aircraft short.
- **`a distance across the date line goes the short way round`** is the one that catches a
  longitude subtraction that forgot to wrap. The bug is invisible anywhere except the Pacific,
  and it produces a distance of most of the planet.
- **`the home point outlives the message that carried it`** advances the clock well past the
  position-staleness window and asserts the home distance is still known. `HOME_POSITION`
  arrives once; a home that expired with it would take the RTL assessment with it a few
  seconds into every flight.
- **`a home reported as zeroes does not move the aircraft to the atlantic`** feeds the
  assembler a home point of 0,0, which is what an autopilot that has not been given one
  sends. Taken at face value it is a position, and the RTL requirement computed from it would
  be enormous and entirely invented.
- **`a configured capacity makes the rtl rate independent of the state of charge`** is the
  property that makes the setting worth having: with a capacity known, the percent-per-second
  rate is the same at 95% as at 50%. The derivation that avoided needing a capacity only
  works while the charge is reported, and this is the test that says the two routes agree.
- **`a capacity that is not a drone pack is refused at both ends`** (with
  `the plausible range has endpoints that are themselves plausible`) pins the decision to
  reject rather than clamp. Clamping a mistyped capacity would silently produce a flight time
  that looks reasonable, which is worse than refusing the input.

The tests run on the JVM, which is why the calculation layer, the state object and the whole
MAVLink path were kept free of Android dependencies. The one exception is the transport,
which is why it is an interface.

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

One dependency resolves through **JitPack** rather than Maven Central: `usb-serial-for-android`,
the driver library behind `UsbSerialTransport`. It is the only artifact that comes from there,
and `settings.gradle.kts` scopes the repository to that one group so nothing else can. It is
pinned at 3.7.0 — the last release published as plain Java, for reasons set out in
`libs.versions.toml`. If JitPack is unreachable the build fails at dependency resolution with
the coordinate named, rather than somewhere confusing later.

**Target device:** Skydroid G20 (Android 13). The dashboard also runs on a standard phone
or tablet emulator, which is how it is developed and demonstrated. `android.hardware.usb.host`
is declared as *not required*, so the demo installs and runs anywhere; a device without it
gets the simulator and a transport that reports no adapter.

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

The **Recorder** button in the top bar opens the log. Leave it running for a minute
and the summary card fills in: the worst level the flight reached, the rules that fired in
the order the flight met them, the lowest pack and weakest cell, peak current and
temperature, and how far from home it got. The table underneath shows the rows themselves,
with the `Why` column naming the reason each one was kept — `session`, `alert`, `link`,
`detail`, or `tick` for the ordinary heartbeat. Rows that exist for a reason are tinted;
heartbeat rows are not.

The `link` rows are the point of the column. Lose the telemetry and the log says so in
words, at the instant it happened, rather than leaving a silent gap that reads like a quiet
moment in the flight.

The **Aircraft** button opens the airframe settings — one screen, one setting. Enter the
pack's capacity in mAh and save; the card underneath then reports, live, whether that number
is actually the one the dashboard is using. That is deliberate: a settings screen that
accepted a value and said nothing else would let a mistyped capacity sit there looking
configured while the RTL threshold was computed from something else. The demo simulator
reports the charge left directly, so on demo mode the card says so and the capacity is
unused — which is the same code path a real autopilot takes when it reports it, and the
reason `PackCapacity` prefers a measurement to arithmetic wherever there is one.

Nothing on that screen is a threshold. It is a number the telemetry does not carry, so
someone has to supply it, and the screen's job is to make clear whether it did.

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
| 7 | Room blackbox logging and diagnostic view | **Done** |
| 8 | MAVLink parser and serial telemetry source | **Done** |
| 9 | Error handling and parser tests | **Done** — reconnect loop, link watchdog, `LinkHealthSource`, and the `HOME_POSITION` / pack-capacity gaps closed |
| 10 | Final polish, APK, on-device test | **Written, not verified** — the USB-UART transport compiles; running it needs the aircraft |

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
   percentage is a percentage **of** — and no supported MAVLink message reports the pack's
   capacity in mAh. Assuming a pack size would be silently wrong on every airframe but one.
   So the capacity is resolved rather than assumed, in that order: a source that reports the
   charge left directly is believed, because that is a measurement; otherwise the capacity
   is read from the **Aircraft** screen and multiplied by the state of charge. When neither
   is available the answer is null, not a guess — the same convention as everywhere else.
   The earlier derivation, which cancelled the capacity out of the formula entirely by
   dividing two quantities the source already reported, is still what runs in demo mode; its
   limit is stated in the code, that it divides by state of charge, so below 5% the
   assessment is withheld rather than reported with false precision.
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

FR 5.3 has the same problem in a quieter form, and the recorder makes the same kind of
bargain:

- **The logging rate is not specified.** "Blackbox logging" names the data and not the
  cadence, so one is chosen — 1 Hz, going to full resolution on an alert change or a link
  change — and it lives as `BlackboxSampler.DEFAULT_CADENCE_MILLIS` beside its reasoning
  rather than as a literal in the recording path.
- **Retention is not specified.** Nothing says how long a log should be kept, and an
  unbounded one is a monitoring app that eventually fills the device and becomes the fault.
  20,000 rows is a rolling buffer of a little over five hours, and the screen states the cap
  and the current count rather than hiding the truncation.
- **Getting the log off the device is not specified either.** Nothing is implemented for it.
  That is a reported gap rather than an implied feature: the flight recorder screen shows
  the log, and there is no export control that does not do anything.
