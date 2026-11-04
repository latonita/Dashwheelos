# Jeep module diagnostics for the Car errors tile — requirement and architecture

Status: **spec only, not built.** Branch `clean/jeep-diag` (off `clean/base` = `6b1ecf15`).
Recorded 2026-11-04.

## 1. Requirement (from the user)

The "Car errors" tile (`FaultCodesTile.kt`, `BuiltinKind.OBD_DTC`) is built around PSA cars. The user drives
a **2005 Jeep Grand Cherokee WK (3.0 CRD, VIN `1J8HCE8M45Y588369`)** and wants the tile to support it:

- R1. **Query every diagnosable module of the Jeep**, not only the engine. Show each module's fault codes, with
  the code (P/C/B/U), a description and whether it is active or stored.
- R2. The existing generic OBD-II behaviour (mode 03/07 to 7DF/7E0, mode 04 clear) must keep working for
  other cars. PSA extras must keep working for PSA cars.
- R3. The design should allow more makes later; Jeep must not become a new hard-coded special case.

The reference data (modules, requests, DTC descriptions, PIDs, and a live scan of this car) is in
`D:\Repos\car\extracted\`.

## 2. How the tile works today (`clean/radio`)

Paths are under `app/src/main/java/com/openauto/dash/`. All of this came from upstream (deviloufr-ai). Our
own commits only touched the vehicle-bus wiring.

| Part | Where | What it does |
|---|---|---|
| UI | `FaultCodesTile.kt` (`ObdDtcCard` :100, `FaultDetailSheet` :719, clear :626) | Scan and Clear buttons. Clear needs a confirm and only works when parked. Merges OBD codes with the car's own messages. |
| State | `AiMechanic.kt:463` `State(codes: List<String>, …)` | A flat code list with no module. `autoScan()` runs 3 s after the link comes up and rescans after engine start. Gemini explains new codes. |
| Transport | `ObdBluetoothManager.kt` (BT SPP), `UsbObd.kt` (FTDI/CH340/PL2303/CP210x), `PhoneObd.kt` (companion relay) | One ELM327 byte stream. Every command passes one `commandMutex`. |
| Init | `initializeAdapter` :782 | `ATZ ATE0 ATL0 ATSP0`. CAN auto-format stays on and headers stay off. |
| DTC scan | `readTroubleCodes` :989 | `0101`, `03`, `07` to 7DF, then `ATSH7E0` and `03`, `07` again. No mode 0A. |
| Parse | `ObdParser.parseDtcReply` :111, `decodeDtc` :197 | Takes only `43`/`47` replies with 2-byte DTCs. |
| Clear | :1111 | Mode `04` broadcast. "OK" means the reply contains `44`. |
| Descriptions | `ObdCodes.kt` `TABLE` | About 30 P-codes with C4 Picasso hints. Other codes get a generic fallback text. |
| Addressed query | `query`/`queryAll` :1052–1105 | `ATSH`, `ATCRA`, `ATFCSH`, `ATFCSD300000`, `ATFCSM1`, plus a session keep-alive every 2 s. Used by `PidExplorer` and `MyReadings`. **This is the building block to reuse.** |
| PSA parts | `PsaTripComputer.kt`, `PsaWarnings`, `CarProfile.PRESET`, `ObdCodes` fallback text, `PidExplorer` prompt | Car messages from the RZC CAN box are already gated by `isPsa(make)`. The rest is only text. |

**The gaps for the Jeep:**
- There is no module list.
- KWP2000 `18`/`58` and UDS `19`/`59` are not supported.
- Codes have no module.
- There is no per-module clear.
- The description table is too small.
- The make is free text in `CarProfile.make`. Nothing reads the VIN.

## 3. What the Jeep needs (from `extracted/`)

### 3.1 Bus and adapter

- Every module is on ISO 15765, 11-bit, 500 kbit/s (`ATSP6`). One port reaches all of them, because the
  FCM/TIPM bridges the interior CAN.
- There is no secure gateway.
- The reference capture used an OBDLink EX (STN2230). A plain ELM327 should work: the STN-only commands are
  optional.

### 3.2 Modules

All 12 modules below use KWP2000 on CAN. They answered on this car and have definitions:

| Module | Name | Req → Resp |
|---|---|---|
| PCM | Engine (3.0 CRD) | 7E0 → 7E8 |
| TCM | Transmission | 7E1 → 7E9 |
| ABS | Anti-lock brakes | 784 → 785 |
| ESM | Electronic shifter | 788 → 789 |
| FDCM | Final drive / AWD | 7B6 → 7B7 |
| FCMCGW | Front control module / gateway | 620 → 504 |
| WCM | Wireless control / immobilizer | 600 → 500 |
| CCN | Instrument cluster | 6A0 → 514 |
| SCM | Steering column | 6A8 → 515 |
| RADIO | Radio | 6B0 → 516 |
| ORC | Airbags | 6E0 → 51C |
| EOM | Overhead console | 6F0 → 51E |

**15 more modules** never answered: AHBM, AMP, DDM, HFM, HSM, HVAC, ITM, LRSM, MSMD, OCM, PDM, PTS, SDAR,
SUNR and VES. Their IDs are in `ecus.csv`. They are probably options this car doesn't have.

### 3.3 Reading fault codes (KWP)

- **Request:** `18 00 FF 00`, read DTC by status. No session is needed.
- **Reply:** `58 <n> [Bhi Blo St] × n`.
  - `58 00` means no faults.
  - Use `n`, not the reply length.
  - The reply can span several frames, e.g. ABS `10 0B 58 03 …`.
- **Letter:** `"PCBU"[Bhi >> 6]`, then `(Bhi >> 4) & 3`, then the remaining 3 nibbles.
- **Status:** `(St >> 5) & 3` gives 3 = active, 2 = pending, 0/1 = stored.
- **Waiting and refusals:**
  - `7F 18 78` means "response pending": keep waiting.
  - Any other `7F` means the module refused, which counts as a result.
  - No answer means the module is not fitted.

### 3.4 Clearing fault codes (KWP)

- Send `10 92` (extended session), then `14 FF 00`. The positive reply is `54 FF 00`.
- The module may answer `7F 14 78` first; keep waiting.
- No security access is needed.

### 3.5 Descriptions

- `dtc_codes.csv` has 1,152 rows. The key is **(module, 2-byte raw code)**, because the same code means
  different things on different modules.
- The text is English only.
- It is about 58 KB raw, or roughly 15–20 KB gzipped.

### 3.6 Later

- Identification `1A 87`: part number, variant, version.
- Freeze frame `17 hi lo FF`: 23 parameters. The reply layout has not been checked on the car.
- Live data `21xx`: 1,085 PIDs with scaling and enum tables.

### 3.7 Expected result on this car

`extracted/live_dtc_scan.csv` is the acceptance oracle. The PCM has P0676 and P213A **active**, plus 5 stored
codes. ABS, CCN, ESM, FCMCGW, FDCM, WCM, ORC and TCM have stored codes. EOM, RADIO and SCM are clean.

## 4. Architecture

### 4.1 Diagnostic profiles (data, not branches in code)

```kotlin
enum class DiagProtocol { OBD2, KWP_CAN, UDS }

data class DiagModule(
    val id: String,            // "ABS"
    val name: String,          // "Anti-lock brakes"
    val tx: Int, val rx: Int,  // 0x784, 0x785 (11-bit)
    val protocol: DiagProtocol,
    val readRequest: String,   // "1800FF00"
    val clearSteps: List<String>, // ["1092", "14FF00"]; empty = clear not offered
    val clearRisk: ClearRisk,  // NORMAL, SENSITIVE (ORC, WCM, PCM adaptives)
    val probeOnly: Boolean,    // the 15 silent modules
)

data class DiagProfile(val id: String, val label: String, val modules: List<DiagModule>,
                       val descriptions: DtcDescriptions)
```

- **`GenericObd2`** is today's behaviour: one pseudo-module "Engine (OBD-II)" read by mode 03/07 and cleared
  by mode 04. It stays the default.
- **`JeepWk`** (FCA 2005–2010 KWP-on-CAN family): the 12 modules above, plus the 15 `probeOnly` ones that are
  off by default.
- The profiles live in a small JSON file in assets (`diag/profiles/jeep-wk.json`), loaded by one parser.
  Adding a make means adding data, not code.
- PSA can become a profile later. It is out of scope here.

### 4.2 Codecs per protocol (pure functions, unit-tested)

```kotlin
interface DtcCodec {
    fun parse(module: DiagModule, payload: ByteArray): ModuleScan
}
```

- `Obd2Codec` wraps the current `ObdParser.parseDtcReply`.
- `KwpCodec` handles `58 n [hi lo st]` as in 3.3.
- `UdsCodec` handles `59 02 mask [3B + st]`. It is in the source code of the app but not used on this car.
  Writing it now is cheap and covers newer FCA cars.

### 4.3 Transport

- Reuse `ObdBluetoothManager.query` for addressing: `ATSH`, `ATCRA`, `ATFCSH`, `ATFCSD`, `ATFCSM1`. It
  already restores the 7DF state afterwards.
- Add a raw-bytes variant that returns the reassembled payload per request. The ELM does ISO-TP with CAF on,
  as it does today.
- `7F xx 78` is handled once, in the transport: keep reading until the final reply or a timeout of about 5 s.
- One scan holds `commandMutex` per module, not for the whole sweep, so the gauge polling can still run in
  between.

### 4.4 Allow-list (hard safety rule)

The diagnostic path may only send these services: `18`, `17`, `1A`, `21`, `3E`, the `10 92` + `14` pair (for
clear only), and OBD-II `01`/`03`/`04`/`07`/`0A`.

- Everything else is refused in code before it reaches the adapter, and a unit test enforces this.
- Refused services include `27` (security access), `2E`/`3B` (writes), `30`/`2F` (actuators), `31`–`33`
  (routines), `11` (ECU reset), `23` (memory reads) and programming sessions.
- The actuator and config tables in `extracted/` are **never** shipped.

### 4.5 State

- Replace `AiMechanic.State.codes: List<String>` with:
  - `scans: List<ModuleScan>`, where `ModuleScan(module, outcome = OK|NO_RESPONSE|REFUSED|ERROR, codes: List<Dtc>)`
  - `Dtc(code = "P0676", raw = 0x0676, status = ACTIVE|PENDING|STORED, description)`
- Add a derived `codes` property so `NowCard`, the widgets, `CarProblemMap`, the automation `FAULT_CODES`
  event and the Gemini prompt still compile.
- Pass the module name to Gemini as well.
- Make `known_codes` in prefs module-scoped: `ABS:U0126`.

### 4.6 Choosing the profile

- Add a setting "Diagnostics: Auto / Generic OBD-II / Jeep WK".
- In Auto mode, choose by:
  1. the VIN's WMI (`1J4`, `1J8`, `1C4` → Jeep), where `CarProfile.vin` is filled in or read with mode 09
     PID 02;
  2. otherwise `CarProfile.make` starting with "jeep".
- In Auto mode, the PSA-only text (the C4 Picasso fallback, `CarProfile.PRESET` hints) only applies to PSA
  makes.

### 4.7 Descriptions

- The descriptions ship in the app as an asset next to the profile: `diag/profiles/jeep-wk-dtc.csv`
  (module, raw, sae, text), about 58 KB.
- A small tool script makes it from `extracted/dtc_codes.csv`.
- A code that isn't in the table shows the module name plus the generic SAE text from `ObdCodes`.

### 4.8 UI

- **Tile:** a total, an "N active" count, and a mark per module with faults. The scan runs one module at a
  time and shows its progress ("ABS…").
- **Detail sheet:** grouped by module.
  - Modules with faults first, then clean modules, then modules that didn't answer (folded).
  - Each code shows code, status and description.
- **Clear:**
  - Per module only. No "clear all" for KWP profiles.
  - Parked only, with a confirm.
  - SENSITIVE modules (ORC airbag, WCM immobilizer, PCM) get a second warning: "may reset adaptations or the
    warning lamp stays until the next check".
  - After a clear, that module is read again.

### 4.9 When to scan (Q4)

Today: `AiMechanic.autoScan()` runs 3 s after the OBD link connects (`VehicleMonitor.kt:287`) and once
more after the engine starts (`RescanAfterStart`). Otherwise the scan only runs when the user taps Scan.

Proposed:
- **Manual:** the Scan button does a full sweep, with the module progress shown on the tile.
- **Periodic:** a setting "Background check: Off / 5 / 10 / 15 / 30 min", **Off by default**.
  - **First read: 15 s after ACC on** (user, 2026-11-04).
    - **The ACC-on source is the canbox** (user, 2026-11-04): Hiworld JPF2 frame `0x72` STATE, byte 0,
      bit 0 = ACC, so ACC on is that bit going 0 → 1. The stock `0x72` flags carry both ACC and IGN.
    - Only the stock frame is used. The decoder for our rebuilt firmware's custom commands
      (`Jpf20lDecoder`) is not changed.
    - Today the decoder only documents this bit (`HiworldJpf2Decoder.kt:12`) and doesn't publish it.
    - `XyautoAdapter` takes `K.IGNITION` from the vendor `xy.android.acc.on` broadcast
      (`AccBroadcastIgnition`).
    - So this feature has to decode the bit and publish it, either into `K.IGNITION` with the canbox
      outranking the broadcast, or as its own key.
    - Units without a canbox ACC bit fall back to `K.IGNITION` from the adapter.
    - If the OBD link is not up at 15 s, the first read runs as soon as it connects.
    - After that, the read repeats every X minutes, counted from the first read.
    - When periodic is on, this first read replaces today's scan 3 s after connect.
  - **No double reads** (user, 2026-11-04). One scheduler, `DiagScanScheduler`, owns every trigger:
    - the scan 3 s after connect (`VehicleMonitor.kt:286`),
    - the read 15 s after ACC on,
    - the rescan after engine start (`RescanAfterStart`, `AiMechanic.kt:372`, engine running 20 s after a
      scan made with it stopped),
    - the periodic tick,
    - the Scan button.
  - Nothing else calls `readTroubleCodes` or the module sweep. Its rules:
    1. **One scan at a time.** A trigger that arrives while a scan runs joins that scan; it does not start
       another. This includes a tap.
    2. **One start-up scan per ACC cycle.** With periodic on, the 3 s connect scan is not scheduled, and the
       ACC + 15 s read (or the connect, if later) is the only start-up scan. With periodic off, only the
       connect scan runs.
    3. **Minimum gap of 60 s** between automatic scans. An automatic trigger within 60 s of the last
       finished scan is dropped, not queued. A tap is exempt from the gap.
    4. **Engine-start rescan.** It only fires if the last scan was made with the engine stopped (as today).
       It counts as a periodic run, so the periodic timer restarts from it.
    5. **Periodic timer.** It counts from the last finished scan of any kind, so a tap or an engine-start
       rescan pushes the next tick back.
    6. **ACC off** cancels the pending triggers. The next ACC on starts a new cycle.
  - Unit tests drive the scheduler with a fake clock and check each case:
    - ACC on at 0 s with the link up at 2 s → 1 scan;
    - link up at 30 s → 1 scan;
    - engine start at 10 s, running at 30 s → no extra scan before the 60 s gap;
    - a tap during a scan → no second sweep;
    - periodic and engine rescan in the same minute → 1 scan.
  - It runs only while the link is up.
  - It never runs while a clear is in progress.
  - It yields to the gauge poll between modules.
- **Quick check:** the periodic sweep is the quick check, because the read request already tells clean from
  faulty.
  - A clean module answers `58 00` in one frame, and the count byte gives the number of faults.
  - The scan stores a fingerprint of each module's reply. The tile and the automation `FAULT_CODES` event
    only change when it differs: a new code, a cleared code, or stored → active.
  - Descriptions and the Gemini note only run for new codes.
- **Cheap engine check:** `01 01` gives the engine lamp and the count of engine (emission) codes. It can be
  added to the normal gauge poll, about once a minute. A change there starts a PCM read at once.
- **Cost estimate (unverified):** switching the adapter to a module takes about 5 AT commands plus 1 request.
  - OBDLink: about 0.1–0.2 s per module.
  - Bluetooth clone: about 0.3–0.5 s per module.
  - So a sweep takes 1.5–6 s. To cut the switching, keep a per-module "last header set" and skip the AT
    commands that are already in effect.

### 4.10 Adapter differences (Q2)

- **Probe once at connect.** Send `STI`, `ATCRA7E8`, `ATFCSH7E0` and `ATFCSM1`. Then restore the state and
  remember which commands the adapter refused.
- **OBDLink EX.** It supports all of the above.
  - The STN extras (`STCFCPA`, `STCAF0`) are optional.
  - Check whether `UsbObd` recognises its USB chip; it lists FTDI, CH340, PL2303 and CP210x.
- **Generic ELM327 clones.** Many v1.5 clones reject `ATCRA` and the `ATFCS*` commands.
  - **Fallback for filtering:** set headers on (`ATH1`) and drop frames from other senders in software.
  - **Fallback for multi-frame replies:** these need flow control sent to the module's request ID. Without
    `ATFCS*`, only the default flow control (7Ex) can be expected to work.
  - With the fallback, modules outside 7E0–7E7 may show only their first frame. They still give the fault
    count (`58 n`), so "has N faults" still works, but the full list may not.
  - The tile says so instead of guessing.
- **Timeouts.** Use `ATST` of about 100–200 ms for each module, plus the `7F 78` wait. A clone on Bluetooth
  gets a longer budget.

## 5. Phases

1. **P1: read-only scan.**
   - Profiles, `KwpCodec`, the allow-list, the raw query with 78 handling, per-module state, the sheet
     grouped by module, the bundled description table.
   - Tests use the captured replies (the `raw_dtc_response_hex` column in `ecus.csv`, and the ABS multi-frame
     capture).
   - **Done when:** a scan on the car reproduces `live_dtc_scan.csv`.
2. **P2: per-module clear**, with the confirmations in 4.8.
3. **P3: identification** (`1A 87` part numbers in the sheet) and **freeze frame** (`17`), after checking the
   reply on the car.
4. **P4: live module data**: `21xx` PIDs as ready-made entries in My readings, or as bus keys (for example
   the TCM's gear and temperatures).
5. **P5:** an option to probe the 15 silent modules, and mode 0A for the generic profile.

## 6. Open questions for the user

- Q1. (Settled 2026-11-04: the descriptions are public information and are bundled in the app.)
- Q2. (Settled 2026-11-04) **Both adapters must work**: the OBDLink EX (USB, STN2230) and a generic Bluetooth
  ELM327. See 4.10.
- Q3. (Settled 2026-11-04) Clear is allowed for ORC (airbag) and WCM (immobilizer), behind the extra
  SENSITIVE warning in 4.8.
- Q4. (Partly settled 2026-11-04) The user wants a manual scan (tap) **plus an optional periodic scan**
  every X minutes. With periodic on, the first read is 15 s after ACC on. With periodic off, today's
  automatic scans stay as they are (3 s after connect, once after engine start). See 4.9.
