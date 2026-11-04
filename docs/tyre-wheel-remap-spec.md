# Tyre wheel remap: requirement and design

Status: **spec, being built.** Branch `clean/tyre-remap` (made from `clean/base` = `6b1ecf15`).
Recorded 2026-11-04.

## 1. Requirement (from the user)

Some cars (and TPMS sources) report tyre data under the wrong wheel. The user wants to:

- R1. **Long-tap a wheel on the Tyres tile** (for example FL) and say which wheel its data really comes
  from: FR, RL or RR. The two wheels swap.
- R2. **Keep the setting across reboots and ACC-off.** It is stored on the head unit.
- R3. **Use the corrected order everywhere**: the tile, the tyre alert overlay, the widgets and skins, the
  low-pressure judgement (front and rear limits differ), the second-screen alerts, the voice, and the
  car facts.

## 2. Today

Paths are under `app/src/main/java/com/openauto/dash/`.

### 2.1 Data and display

- **Bus key.** `K.TYRES` (`vehicle/Catalogue.kt:78`) is `Map<TyrePos, Tyre>`. `TyrePos` has FL, FR, RL,
  RR and SPARE (`Tyres.kt:36`).
- **Publishers.** Each publisher has its own position convention:
  - the TPMS app `com.syt.tmps` (`Tyres.kt:160–203`);
  - the USB TPMS receiver (`UsbTpms.kt:142`, source `tpms.usb`);
  - demo mode (`DemoMode.kt:487`);
  - on `clean/xyauto-bus`, the XYAuto canbox (`XyautoBusPublisher.kt:112`, whose rear order is unproven
    according to `CanboxValue.kt:90`).
- **Readers.** Almost every reader goes through `vehicle/Body.kt:56–60`: `rememberTyres()`, `tyresNow()`
  and `tyresFlow()`. That includes `Tyres.judge` (`Tyres.kt:114`), so the per-axle limits follow whatever
  those helpers return.
  - Two raw readers ignore positions: `Tyres.kt:100` (the battery mark) and `TpmsReset.kt:105` (timing).
- **Tile.** `TyresCard` → `TyreGrid` → `TyreCell` (`TyreAlertOverlay.kt:194–286`) draws a fixed 2×2 grid
  with no gestures.
- **Long-press today.** A long-press anywhere on the tile enters dashboard edit mode. That is
  `detectPageHold` on `PointerEventPass.Initial` (`DashboardGrid.kt:244–253` and `:368–401`). TYRES is not
  in `takesEveryTouch()`.
- **No existing remap.** Nothing in the code swaps, rotates or remaps wheels.

## 3. Design

### 3.1 Model (pure, unit-tested)

```kotlin
/** shown position → position the data really came from; SPARE is never remapped. */
data class WheelMap(val from: Map<TyrePos, TyrePos>) {
    fun apply(raw: Map<TyrePos, Tyre>): Map<TyrePos, Tyre>
    fun swap(a: TyrePos, b: TyrePos): WheelMap    // exchanges the two entries
    val isIdentity: Boolean
    companion object { val IDENTITY: WheelMap }
}
```

- The map is always a **permutation** of FL, FR, RL and RR. Swaps keep it one, and a stored value that
  isn't one loads as IDENTITY.
- `apply` moves each `Tyre` to its shown position. A wheel with no data stays empty. SPARE passes through
  unchanged.
- **Example:** long-tapping FL and choosing RR means "FL's data is really RR's". That is `swap(FL, RR)`, so
  the shown FL is now taken from raw RR, and the shown RR from raw FL.

### 3.2 Where it applies: one read-side point

- The remap is applied **in the `Body.kt` helpers** (`rememberTyres`, `tyresNow`, `tyresFlow`), so every
  reader gets corrected positions, including `Tyres.judge` with its per-axle limits.
- The bus itself keeps the raw data as the source sent it.
- `docs/vehicle-bus.md` gets one line saying that raw `K.TYRES` readers bypass the remap.
- **Per source.** Different sources can have different errors: a car swapped from canbox TPMS to a USB
  receiver must not inherit the canbox's fix. So the map is stored **per bus source id** (for example
  `tpms.usb`, the canbox source or the syt app), using the source that currently provides `K.TYRES`.
  - If the bus can't say which source provides the current value, fall back to a single global map, and
    record that in the code and this spec.
- `TyreAlertOverlay` reads the corrected data via `tyresFlow` like everything else.

### 3.3 Storage

- New prefs file `tyre_wheel_map` in the `MapTileOptions` pattern (a `StateFlow` plus `load(context)` and
  `set`).
- One key per source id. The value is 4 letters in FL FR RL RR order, each naming the raw source wheel:
  identity is `FL,FR,RL,RR`, and a FL↔RR swap is `RR,FR,RL,FL`.
- It is added to the setup backup if `map_tile` is in it (check how the backup lists prefs).

### 3.4 UI

- **Long-tap on a wheel cell** in the Tyres tile, outside edit mode, opens a small menu at that wheel:
  - "FL data is really from:"
  - FR, RL, RR (the other three wheels)
  - "Reset wheel order" (only when the map isn't the identity)
- Choosing a wheel swaps the two. The menu is the confirmation; there is no second dialog.
- **Gestures:**
  - A wheel cell must claim its long-press before `detectPageHold`, which sees the Initial pass. A
    long-press on the tile header or the gaps still enters edit mode.
  - A short tap on a wheel does nothing new.
  - In edit mode the scrim covers the tile, so wheels can't be tapped. That is fine.
- **Indicator.** While the map isn't the identity, the tile header shows a small "swapped" mark (an icon
  or text), so the user knows the order is corrected.
- **Tile panel** (`TileOptions.kt`, edit mode): a "Reset wheel order" row for the Tyres tile, shown only
  when the map isn't the identity.
- The alert overlay's grid gets no long-press. It only displays.
- **Strings:** English, in the existing tile strings resource. Other locales fall back.

## 4. Tests

- **`WheelMap`:**
  - identity;
  - every swap pair;
  - a swap twice gives the identity;
  - SPARE untouched;
  - missing wheels;
  - parse and format;
  - a bad stored value loads as the identity.
- **Prefs:** a per-source round trip, and the global fallback.
- **Read side:** `tyresNow()`/`Tyres.judge` with a FL↔RL swap. A low front pressure is judged against
  the rear limit once it is shown as a rear wheel.
- The existing `TyresTest`, `UsbTpmsTest`, `TpmsResetTest` and `MapTileOptionsTest` must still pass.

## 5. Out of scope

- Automatic detection (for example a deflation test wizard).
- Remapping the spare.
- Changing the canbox's rear-order constant: that stays the job of a real deflation test.
