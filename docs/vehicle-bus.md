# Vehicle bus

One small, typed bus sits between every source of car data and every consumer. **Sources publish what they know; consumers ask for an element by key, never for a store; the choice between sources happens once, inside the bus.**

It replaced the per-source stores of `oldies`, where ObdData, CarBox, McuReader, CarFeed, UnitSignals, Tyres and others were each read directly by consumers, each with its own fallback chain (`docs/data-flow-oldies.html` is that baseline; `docs/vehicle-data-refactor-review.md` says why the earlier refactor, b63d1de1, was not carved as is). It was built in six commits on `feature/vehicle-bus`: core, speed, gauges, body / fuel / tyres / weather, liveness / direct publishers / estimates, and the deletion of the old stores. The code is in `com.openauto.dash.vehicle`.

## 1. Keys

A key names one element of car data: its value type, unit, kind, domain and criticality, and **never where the value comes from**. The kind decides what the bus does with it:

| Kind | Class | Bus rules |
|---|---|---|
| Fast measurement | `Key.Measurement<T>` (`ttlMs` 3-10 s, `maxHz`) | expires after `ttlMs`; emits at most `maxHz` per second (conflated, latest wins) |
| Slow measurement | `Key.Measurement<T>` (`ttlMs` 60 s to hours) | expires after `ttlMs` |
| Discrete state | `Key.State<T>` | no clock: alive while its source is up; each change of the resolved value is also a `VehicleEvent.Changed` |
| Counter | `Key.Counter` | a value lower than the same source's last is a glitch and is dropped |
| Identity | `Key.Info<T>` | no expiry (`CANBOX_VERSION`) |

`Key.BestOf<T>(id, first, fallback...)` resolves to the first member that has a value; subscribing to it is demand for all members. `Domain` and `Criticality` are labels for the UI and for strictness; they never change resolution.

**Different meaning, different key.** GPS speed is ground speed, the car's comes from the wheels (`GPS_SPEED`, `VEHICLE_SPEED`); the car's outside sensor and the forecast are `OUTSIDE_TEMP` and `FORECAST_TEMP`, read together as `AMBIENT_TEMP`.

### The catalogue (`K`)

| Key | Type | Kind / ttl | Domain | Crit. |
|---|---|---|---|---|
| `VEHICLE_SPEED` | Double km/h, corrected | Measurement 3 s | MOTION | SAFETY |
| `GPS_SPEED` | Double km/h, raw | Measurement 5 s | MOTION | DISPLAY |
| `SPEED` | BestOf(VEHICLE_SPEED, GPS_SPEED) | | MOTION | SAFETY |
| `STEERING_ANGLE` | Double degrees, right positive | Measurement 3 s | MOTION | DISPLAY |
| `CAR_HEADING` | Double degrees, the car's own (magnetic) compass; not the GPS heading | Measurement 10 s | MOTION | DISPLAY |
| `RPM`, `THROTTLE`, `ENGINE_LOAD` | Double | Measurement 3 s | POWERTRAIN | DISPLAY |
| `COOLANT_TEMP` | Double C | Measurement 60 s | POWERTRAIN | WARNING |
| `INTAKE_TEMP` | Double C | Measurement 60 s | POWERTRAIN | DISPLAY |
| `ENGINE_RUNNING` | Boolean | State | POWERTRAIN | DISPLAY |
| `GEAR` | `Gear` (Park, Reverse, Neutral, Drive, Forward(n)) | State | POWERTRAIN | DISPLAY |
| `IGNITION` | Boolean | State | POWERTRAIN | WARNING |
| `FUEL_FLOW` | Double L/h | Measurement 5 s | ENERGY | DISPLAY |
| `BATTERY_VOLTS` | Double V | Measurement 60 s | ENERGY | WARNING |
| `FUEL_LEVEL` | Double % | Measurement 120 s | ENERGY | WARNING |
| `FUEL_LITRES` | Double L, a box that measures litres (never converted to a %) | Measurement 120 s | ENERGY | DISPLAY |
| `RANGE` | Double km | Measurement 120 s | ENERGY | DISPLAY |
| `FUEL_CONSUMPTION` | Double L/100 km, the car's trip computer | Measurement 10 s | ENERGY | DISPLAY |
| `OUTSIDE_TEMP` | Double C, the car's sensor | Measurement 2 h | ENVIRONMENT | DISPLAY |
| `FORECAST_TEMP` | Double C | Measurement 3 h | ENVIRONMENT | DISPLAY |
| `AMBIENT_TEMP` | BestOf(OUTSIDE_TEMP, FORECAST_TEMP) | | ENVIRONMENT | DISPLAY |
| `DOORS` | `McuReader.DoorState` | State | BODY | WARNING |
| `REVERSE` | Boolean | State | BODY | SAFETY |
| `HEADLIGHTS` | Boolean (from the side lights up) | State | BODY | DISPLAY |
| `LIGHTS` | `List<CarLight>` (hazard, beam, fogs) | State | BODY | DISPLAY |
| `HANDBRAKE` | Boolean | State | BODY | DISPLAY |
| `INSTRUMENT_DIMMER` | Double %, the instrument panel's dimmer | Measurement 60 s | BODY | DISPLAY |
| `DRIVER_BELT` | Boolean, true = unfastened | State | BODY | WARNING |
| `CLIMATE` | `Climate` | State | CLIMATE | DISPLAY |
| `RADAR` | `Radar` | State | DRIVER_AIDS | SAFETY |
| `TYRES` | `Map<TyrePos, Tyre>` | State | TYRES | WARNING |
| `ODOMETER` | Double km | Counter | TRIP | DISPLAY |
| `TRIP_DISTANCE` | Double km, the car's trip counter (the driver resets it, so not a Counter) | Measurement 6 h | TRIP | DISPLAY |
| `CANBOX_VERSION` | String, what the canbox names itself with | Info | IDENTITY | DISPLAY |

## 2. Sources

`Source(id, kind, label)` is a provider's identity. `SourceKind` carries a rank; lower wins: `DEMO` -2, `LEARNED` -1 (the driver's taught signals beat the built-in decoding, as on oldies), `CANBOX` / `MCU` / `VEHICLE_APP` 0 (the car, through the head unit), `OBD` 1, `COMPUTED` 2, `GPS` 3, `NETWORK` 4. `Quality` is GOOD, ESTIMATED or DEGRADED; DEGRADED loses to the others from any rank. **An estimate is a source, not a kind of data**: `K.GEAR` is the same key on every car, and a computed provider publishes it ESTIMATED when nothing better is live.

| Source id | Kind | Puts | Goes quiet |
|---|---|---|---|
| `demo` | DEMO | every key the demo shows: speed, gauges, doors, lights, handbrake, belt, fuel, range, consumption, mileage, trip, tyres, outside and forecast temperature | up while the demo runs; `clear()` + `up(false)` when it ends |
| `learned` | LEARNED | DOORS (taught doors merged over the car's own), REVERSE, HEADLIGHTS, DRIVER_BELT | down while no signal is taught |
| `learned.can` | LEARNED | FUEL_LEVEL, RANGE (the driver's fuel byte and range word, held 6 h) | none (the hold) |
| `qf.car_app` | VEHICLE_APP | VEHICLE_SPEED, RPM (6 s), FUEL_LEVEL, RANGE (6 h), ODOMETER, FUEL_CONSUMPTION, TRIP_DISTANCE, HEADLIGHTS, LIGHTS, DRIVER_BELT, HANDBRAKE (on a change), CLIMATE, RADAR, DOORS, REVERSE, STEERING_ANGLE (1 h) | not taken down as a whole; a body silent for 30 s withdraws the states the body puts (lights, belt, handbrake) |
| `qf.mcu` | MCU | DOORS, HEADLIGHTS, HANDBRAKE, BATTERY_VOLTS (10 min) | 10 min, at once when the MCU service dies |
| `qf.broadcast` | VEHICLE_APP | OUTSIDE_TEMP (2 h, a restored one with its real age), HEADLIGHTS, BATTERY_VOLTS (the firmware's property) | none (speaks on a change; lives with the app) |
| `unit.bus` | CANBOX | VEHICLE_SPEED, RPM, DOORS, REVERSE, HEADLIGHTS, BATTERY_VOLTS, OUTSIDE_TEMP, STEERING_ANGLE, as the family's adapter decodes them; XYAuto (the Hiworld JPF2 / JPF20L canbox, `XyautoBusPublisher`) also ENGINE_LOAD, THROTTLE, COOLANT_TEMP, INTAKE_TEMP, ENGINE_RUNNING, GEAR, HANDBRAKE, FUEL_LEVEL, FUEL_LITRES, RANGE, ODOMETER, TYRES, INSTRUMENT_DIMMER, CAR_HEADING, CANBOX_VERSION | per family: FYT 30 s, MTC 15 s, XYAuto 5 s; at once when the family's `listening` goes false |
| `tpms` | VEHICLE_APP | TYRES | 60 s (`TYRE_STALE_MS`) |
| `power` | VEHICLE_APP | IGNITION | none |
| `obd` | OBD | VEHICLE_SPEED, RPM, THROTTLE, ENGINE_LOAD, COOLANT_TEMP, INTAKE_TEMP, FUEL_FLOW, BATTERY_VOLTS (the adapter's own ATRV is DEGRADED), FUEL_LEVEL: only what the adapter answered in the poll just done | on disconnect |
| `computed.gear` | COMPUTED | GEAR from RPM, VEHICLE_SPEED, REVERSE | runs only while wanted and no real source has it |
| `computed.range` | COMPUTED | RANGE from FUEL_LEVEL and FUEL_LEVEL from RANGE (ESTIMATED) | same |
| `computed.engine` | COMPUTED | ENGINE_RUNNING from RPM | same |
| `gps` | GPS | GPS_SPEED | down while there is no fresh fix |
| `weather` | NETWORK | FORECAST_TEMP, aged from its fetch | none (the key's 3 h) |

## 3. The API

```kotlin
class VehicleBus(clock: () -> Long = SystemClock::elapsedRealtime, scope: CoroutineScope) {
    fun register(source: Source): Publisher
    fun <T : Any> sample(key: Key<T>): StateFlow<Sample<T>?>              // resolved: value, source, quality
    fun <T : Any> value(key: Key<T>, from: SourceKind? = null): Flow<T?>  // what consumers use; from = pinned read
    fun <T : Any> now(key: Key<T>): T?                                    // snapshot for non-Compose code
    fun <T : Any> candidates(key: Key<T>): StateFlow<List<Sample<T>>>     // every source, for diagnostics
    fun has(key: Key<*>): StateFlow<Boolean>                               // some source that is up has published it
    fun choose(key: Key<*>, sourceId: String?)                             // the driver's override (null = automatic)
    fun hold(key: Key<*>): Hold                                            // standing demand, for code that reads with now()
    val events: SharedFlow<VehicleEvent>                                   // state changes
    val wanted: StateFlow<Set<Key<*>>>                                     // keys with live subscribers or holds
}
interface Publisher {
    val source: Source
    fun <T : Any> put(key: Key<T>, value: T?, quality: Quality = Quality.GOOD, atMs: Long = clock(), ttlMs: Long? = null) // null withdraws
    fun up(isUp: Boolean)   // down: every value of this source drops out at once
    fun clear()             // withdraws everything this source put, so nothing stale comes back with up(true)
    val wanted: StateFlow<Set<Key<*>>>
}
object Vehicle { val bus: VehicleBus }
@Composable fun <T : Any> rememberVehicle(key: Key<T>): T?
```

**Resolution** runs per key on `put`, `up`, `choose` and expiry, and is the only place a source is chosen: (1) drop candidates whose source is down, whose value expired, or that were withdrawn; (2) the driver's chosen source wins if it has a live candidate; (3) otherwise the best quality, then the lowest rank, then the newest. The resolved flow emits only when value, source or quality changes. It is O(candidates of that key), under one lock; one expiry job runs about every 250 ms off the injected clock. Pinned reads bypass 2 and 3 and keep expiry.

**Demand.** `wanted` comes from the flows' subscription counts. Providers that pay per value (OBD) poll only wanted keys; non-Compose code that reads with `now()` takes a `hold`.

Helpers over the keys, in `vehicle/`: `displaySpeedKmh` / `rememberDisplaySpeedKmh` (Speed.kt), `Gauges` / `rememberGauges` / `rememberGauge` / `gaugesNow` (Gauges.kt, null for unknown), `reversingNow`, `headlightsNow`, `rememberTyres` and the like (Body.kt, which map null to the old default at the edge), `fuelInfo` (Fuel.kt), the computed providers (Computed.kt), `SourceLiveness` and `SilenceWatch` (Liveness.kt).

## 4. Decisions

- **Speed correction.** `VEHICLE_SPEED` is the car's speed after the driver's correction (Settings, Car): every car-speed provider applies `SpeedCorrection.corrected` before it puts. `GPS_SPEED` is raw. Display goes through `displaySpeedKmh(sample)`, which applies `correctedGps` only when the resolved source is GPS.
- **Slow senders carry their own lifetime.** A provider that knows its cadence puts with `ttlMs`, which replaces the key's for that value (Measurement keys only): the car app's speed and revs 6 s, the unit bus's 10 s, the car app's and the learned fuel and range 6 h (a parked car says nothing, and the tile always showed the last word), steering 1 h, the unit bus's volts 10 min. A restored value is put with its real age (`atMs`).
- **OBD publishes what it answered.** `ObdData` keeps 0 as its default for the readers that rely on it; the bus never sees that 0. Nothing before the first answer, nothing after the link goes. The 0 of speed, revs, throttle and load after `engineStopped()` is published (the car is stopped). The adapter's 0 % fuel is dropped.
- **Demand-driven OBD poll.** `obdPollPlan(wanted, tick)`: speed every poll, then revs, throttle and load, fuel flow, and one of coolant / intake / fuel level / volts per poll, each only while wanted. The floor `VehicleMonitor.POLL_FLOOR` is held while the monitor polls, for what runs off it (the mechanic's live watch, car care's rules, the snapshots).
- **Volts are plain resolution.** The unit's volts (`qf.mcu`) beat the OBD figure; the engine computer's `0142` is GOOD, the adapter's ATRV is DEGRADED and loses to everything. "Trusted volts" is `quality != DEGRADED`.
- **Gauge readers.** A leaf composable reads the keys it shows; the subscription is the demand. Gauges the car did not answer are null, and an estimate is not a gauge.
- **Doors are merged by the learned provider.** A learned door overrides the car's own for that door only, so the `learned` source publishes `mergeDoors(builtIn, learned)`, `builtIn` being the best non-learned, non-demo `DOORS` candidate. Nothing while no door is taught.
- **Weather is the forecast.** `WeatherRepo` publishes `FORECAST_TEMP`; the bar and the skins read `AMBIENT_TEMP`. Weather tiles, alerts and the briefing keep `Weather.tempC` (the forecast), and whoever shows the ambient temperature keeps the forecast fetched (`KeepWeatherFresh`).
- **Learned fuel and range are a LEARNED source** (`learned.can`), so they outrank the car app, which outranks the adapter. "A 0 % level with a range is dropped" lives in the providers that have it, so the next source or the level worked back from the range takes over.
- **States are put on a change.** The car app's body says the lights, belt and handbrake in every message; it puts them only when they changed, so equal-rank sources keep "the newest change stands". The ignition has one provider, `power`; the car app's own flag was never read and is not published.
- **Tyres.** The judgement (`Tyres.problems`, hysteresis and the stale-sensor timer) is made from the resolved `TYRES`, so tiles, the alert and the second screen read one judgement. The belt alert reads `DRIVER_BELT`; its "car app gone quiet" release is the body's silence.
- **A source that goes quiet is down.** A state key has no clock, so a provider whose feed can stop says so (`SourceLiveness`): after its silence, or at once on a lost feed, it `clear()`s and goes `up(false)`; the next word brings it up. Silence times are in the table above.
- **Reverse never outlives its evidence.** `CarBox` follows the resolved `REVERSE`: while true the ROM's flag is read every 2 s and the speed looked at (`ReverseReads`); reverse is over after three "off" reads of a flag seen on before, or three looks at 30 km/h or more.
- **Adapters get a publisher.** `VehicleBusSource.start(context, publisher, raw)`: the publisher is the `unit.bus` handle (everything put is word from the bus), `raw` the Signal Finder's raw values. `UnitBus` registers the source and runs the liveness; the adapters decode and put keys themselves.
- **Estimates are COMPUTED providers.** One runs only while its key is wanted and no non-COMPUTED source has it, and reads only inputs that are not estimates (`VehicleBus.measured`). `fuelInfo` only formats; `fuelInfoNow()` works the missing half out itself for snapshots.
- **The body has keys, not a record (step 6).** What the Car status card read from the car app's body is now `FUEL_CONSUMPTION`, `TRIP_DISTANCE` and `LIGHTS`, since the PSA trip computer or any other source can say the same things; nothing reads the body record any more. The belt, handbrake and lights are withdrawn with the body's silence; the consumption expires with its 6 s, the trip is believed 6 h like the mileage.
- **Capabilities (step 6).** `Cap` carries an optional `key`. The kinds of car data (DOORS, REVERSE, STEERING, RADAR, CLIMATE, BODY_TRIP, SPEED_RPM, VOLTS, HEADLIGHTS, OUTSIDE_TEMP, TYRES) name the key that stands for them (BODY_TRIP: the mileage; SPEED_RPM: the car's speed), and are live when `Vehicle.bus.has(key)` holds, whoever says it; nobody marks them (`UnitPlatform.used` / `usedNow()` combine the marks and the keys). The platform caps (IGNITION, SLEEP_SOON, VEHICLE_BUS, UNIT_KEYS, KEY_TARGETS, PHONE_BRIDGE, MEDIA_BRIDGE, VOLUME_UNIT, LAUNCHER_TAKEOVER, POPUP_HIDE, SLEEP_SURVIVAL, CAMERA_CONTROL, FREEFORM_BAR, BOOT_LOGO) still mark themselves. What a unit claims (`UnitCaps`) is unchanged; "offered" questions (which tiles to add, the first layout) ask `UnitPlatform.delivers(cap)`: claimed by the firmware, or data on the bus now. Asking `has` alone would drop a parked car's tiles from the add sheet.
- **The demo is one source (step 6).** `DemoMode` puts every value the bus carries through the `demo` source only; it is `up(true)` while it runs and `clear()` + `up(false)` when it ends, so nothing is put back. It still writes to feeds for what is not on the bus (below).
- **`ObdData` stays as an adapter (step 6).** `CareRules`, `LiveWatch`, `CarCare.watch`, `AiMechanic.watch` and the tiles drawn against the old record are written against `ObdData`'s 0 for unknown across hundreds of lines and three test files; giving them a nullable input type would touch every rule. `Gauges.toObdData()` and `rememberReadings` stay, at the edge, and the bus never sees the 0.

- **XYAuto's canbox (`XyautoBusPublisher`).** The only class that knows both the decoders' `CanboxValue` and the keys; the decoders (`unitproto/.../canbox/`) know neither the app nor the bus, and a null inside a value is a withdrawal (`put(key, null)`, the heartbeat's "sensor lost"). Box silence is the `unit.bus` liveness, `silenceMs` 5 s: the JPF20L sends its heartbeat once a second and repeats every other frame within about 10 s, all of it only while the ignition is on. The plugin's pre-decoded speed and revs take the same path as 0xB3's (`unitBusSpeed` with the driver's correction, `unitBusRpm`). Speed and revs live the unit bus's 10 s hold; the other measurements keep their key's ttl, which is longer than the box's repeat. `FUEL_LEVEL` is only the box's percentage (0xB4) and the litres (0xB8) are `FUEL_LITRES`, never converted (the tank size is the app's guess): one writer per key, no flip-flop. `GEAR` is the lever: P, R, N, D; S (AutoStick, a held gear 1-4 the box does not say) reads as Drive, since `Gear.Forward(n)` needs a number a guess would get wrong. `TYRES` is the existing map; a wheel with no pressure is absent and no wheel at all withdraws the key (the type has no "unknown" temperature, so the WK's missing one reads 0). `INSTRUMENT_DIMMER` is a slow Measurement, not a State: the driver turns it through many steps, and a State would emit a `Changed` event for every one. `CAR_HEADING` is the car's compass, a different thing from the GPS heading of the location feed. The compass's 8-way sector is not a key (it is the heading, quantised).

## 5. What stays outside the bus

- **Commands.** Read and clear DTCs, the PID explorer, Deep OBD queries, MCU and canbox queries: direct calls to the provider that can do them (`ObdBluetoothManager.readDtcs()`).
- **Raw values.** The Signal Finder's `SignalSources` and `McuReader.entries` / `changes`, and the CAN monitor; the learn and recalibrate wizards' `McuReader` mapping calls (`saveFuelMapping`, `saveRangeMapping`, `fuelConfigured`...), with the CANbox fuel and range the Range card shows (`McuReader.fuelPercent`, `rangeKm`, which the car app and the PSA trip computer also fill).
- **OBD-specific readers of `ObdBluetoothManager.data`.** The mechanic's scan and its explanation, My readings (through `readings()`, which reads the demo's drive off the bus while the demo runs) and Deep OBD's merge. The connection UI reads `connectionState`, `lamp` and `pending`.
- **Navigation-side data.** `LocationFeed.location`, `trip`, `heading`, the weather's picture, g-force, the PSA trip computer (its pages and messages) and the AI mechanic's state. Only the speed is on the bus (`GPS_SPEED`).
- **The car app's second steering path.** `CarBox.steeringFromLog` is the same provider's, not a source: the car app's own log gives a finer angle than its line picture and which one stands is the provider's business.
- **Platform state.** Phone, projection, media, the sleep and power logic (`CarPower`), and the plain question "is the car app installed" (`CarBox.available`, only for the unit report).

What the demo writes outside the bus: the position and trip, heading and g-force (`LocationFeed`, `GForceFeed`), the OBD connection state, lamp and pending codes (`ObdBluetoothManager`), the mechanic's state and the car-care stats, the PSA trip computer, the weather's picture, the PID explorer's readings, navigation, notifications, music, the phone lists, fuel prices and the parking spot.

## Rules for working on it

- Everything builds and passes `.\gradlew.bat :unitproto:test :app:testGithubDebugUnitTest :app:assembleGithubDebug`.
- Unknown is `null`, never 0, in anything new; an old consumer that needs an Int maps null to its default at its edge.
- A new element is a key in the catalogue with its kind, domain and criticality; a new source is a `register` with a `SourceKind`, a liveness rule and its own `ttlMs` where it speaks on a change.
- Match the surrounding code, and say user-visible changes in the commit under "Behaviour changes".
