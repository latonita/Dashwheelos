<img src="docs/logo.png" width="96" alt="Dashwheel logo">

# Dashwheel

> **This is a fork.** Dashwheel is the work of [deviloufr-ai](https://github.com/deviloufr-ai), and all credit for it goes to them: the original project lives at **[deviloufr-ai/Dashwheel](https://github.com/deviloufr-ai/Dashwheel)**, and that is where releases, the wiki and support are.
>
> **Why the fork:** Dashwheel is a great launcher, and it deserves to run on older head units too. The original needs Android 9 and a GPU with OpenGL ES 3.0. This fork brings it to **Android 8.1 (API 27) units whose GPU only does OpenGL ES 2.0**.
>
> My own unit is from **XYAuto**, built on the **ZhangXun / Hechuang B9320B** motherboard layout: this exact PCB is the **B9320BH V0.1/C 64G**, paired with the **AutoChips AC8238PTVA** SoC (Mali-450 GPU). The build isn't tied to it, though: it should work on many other Android 8.1 units, the AC8227L ones included.
>
> What the fork changes, each as its own branch off the original:
> - `feature/api27`: runs on Android 8.1 (minSdk 27, with pre-Android 9 paths for the few calls that need them)
> - `feature/tls13`: TLS 1.3 through Conscrypt, so routing works below Android 10
> - `feature/gles2`: the map on MapLibre 10.3.7, the last release that draws with OpenGL ES 2.0
> - `feature/bar-space-stretch`: Space and Stretch in the bottom bar (also offered upstream)
>
> `radio` is the branch that brings them together for my own unit. The rest of this README is the original's.

**The free, open-source car launcher for Android head units (QF, FYT, Microntek and more).** Map, music and live car data on one calm screen: up to seven dashboards you arrange yourself, 55+ widgets, whole-design skins, an AI mechanic that explains warning lights, and your phone's calls and messages on the big screen. No ads, no trial, no account.

![Dashwheel: map, speed, weather and music on one screen](docs/screenshots/01_hero.jpg)

**[Download the latest release](https://github.com/deviloufr-ai/Dashwheel/releases/latest)** · Android 9+ · made for 1280×720 head units (ROCO K706 / FYT / QF), also works on upright screens and phones.

📖 **[Read the wiki](https://github.com/deviloufr-ai/Dashwheel/wiki)** for every feature, setup guides and what works with or without root.

**Will it work on my unit?** See [supported head units](#supported-head-units) and [tested hardware](https://github.com/deviloufr-ai/Dashwheel/wiki/Tested-Hardware). Root is not needed.

[![Support me on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/deviloufr)

Dashwheel is free, with no ads. If it makes your drives nicer, a coffee on Ko-fi keeps it going.

## Screenshots

| | |
|---|---|
| ![Live car data](docs/screenshots/02_live_car_data.jpg) | ![AI mechanic](docs/screenshots/03_ai_mechanic.jpg) |
| **Live car data** from a Bluetooth OBD adapter and the head unit's CAN box | **AI mechanic**: fault codes explained in plain words |
| ![Road trip](docs/screenshots/04_road_trip.jpg) | ![Widget designs](docs/screenshots/05_widget_designs.jpg) |
| **Road trip**: next turn, break reminder, eco score, fuel prices nearby | **60+ widget designs**, one per tile |
| ![Skins](docs/screenshots/06_skins.jpg) | ![Arranged your way](docs/screenshots/07_customise.jpg) |
| **Whole-design skins**: Panorama, Cockpit, Racing, Orbit, Canvas and more | **Your dashboards**, live widget previews, templates |
| ![Calls](docs/screenshots/13_calls.jpg) | ![Upright screens](docs/screenshots/14_upright.jpg) |
| **Calls** from the paired phone, answered from the screen | **Upright screens** get their own layout |

## Highlights

- **Dashboards your way**: free grid, drag to move and resize, templates, Canvas theme with the map behind the tiles
- **Live car data**: ELM327 Bluetooth OBD and the head unit's own car data (doors, fuel, tyres, reversing)
- **AI mechanic** (free Google Gemini key): fault codes explained and spoken, start-up briefing, car care and servicing reminders
- **Navigation**: free built-in 3D map with turn-by-turn guidance, or Google Maps / Waze next turn on any tile
- **Phone link** (Dashwheel Companion app): notifications, messages, calls, where's my car, drive log
- **Your own alerts and reverse view** instead of the head unit's pop-ups
- **Hands-free**: spoken alerts, steering-wheel buttons, volume that follows speed
- **Share and automate**: share a dashboard and its look as a QR code or a short code; Tasker and similar apps can drive Dashwheel and react to the car ([automation](docs/automation.md))
- **Second screen** (experimental): a Raspberry Pi drives a second monitor in the car
- **11 languages**, day and night themes, updates from GitHub Releases on a stable or a beta channel
- **Root is optional**: extra features appear only when the unit allows them ([what needs what](https://github.com/deviloufr-ai/Dashwheel/wiki/Root-and-PMPatch3)); without root, [Shizuku](https://shizuku.rikka.app/) brings the shell features (app windows, the car app's switches, the volume keys)

## Supported head units

Any Android 9+ head unit runs Dashwheel as a launcher. On top of that, Dashwheel works out which firmware family the unit runs and talks to that firmware (ignition, wheel keys, the CAN box's car data, the unit's Bluetooth phone):

| Family | Typical units | Status |
|---|---|---|
| **QF** | Hizpo / ROCO K706 (UIS7862, UIS8581a) | Everything; the developer's own unit, daily use |
| **FYT / SYU** | Joying, Atoto, Mekede, Junsun, Idoing, Isudar, NaviFly, Sinosmart, Winca, DUDU | Full support, waiting for testers |
| **Microntek MTCD / MTCE, HCT MTCH** | Dasaita, Xtrons PX5 / PX6, Eonon, Pumpkin, Erisin | Full support, waiting for testers |
| **Topway TS**, **Reglink**, **Allwinner**, **Nowadays** | TS10 / TS18, MediaTek AC8227L / AC8257, T3 / T8, K4811 | Basics: plain Android, ignition and key broadcasts, help against the firmware's app killer |
| Anything else | plain Android units, tablets, phones | Works as a launcher |

What each family gives, and how to help confirm yours: [Supported head units](https://github.com/deviloufr-ai/Dashwheel/wiki/Supported-Head-Units) on the wiki. Technical details: [docs/unit-platforms.md](docs/unit-platforms.md). Your unit works? Settings → General → About → **Report how it works**.

## Two editions

- **GitHub edition** (`Dashwheel-<version>.apk` from [Releases](https://github.com/deviloufr-ai/Dashwheel/releases/latest)): the full app. Root, Shizuku, internal ADB and PMPatch3 features appear when the unit allows them, and it updates itself from GitHub Releases.
- **Google Play edition**: the same app without the features that need root, the unit's internal ADB or PMPatch3. It never installs anything itself: updates come from Google Play, and the Companion comes from Play too. Store texts and the privacy policy are in `fastlane/metadata-play/` and `docs/play/`.

| | GitHub | Google Play |
|---|---|---|
| Dashboards, widgets, skins, OBD, map, AI mechanic, phone link | yes | yes |
| Self-update from GitHub, Ko-fi link | yes | no (Play updates it) |
| System-app install, boot logo, Google Maps inside a tile (PMPatch3), reverse camera takeover, firmware pop-up and volume bar replacement, CAN car data from the unit's MCU, Signal Finder | when the unit allows it | no |
| Accessibility service (split screen, typing from the phone, wheel buttons) | turned on by the app where there is a root shell | turned on by you in Android's settings, after an in-app explanation |

## Get started

1. Install `Dashwheel-<version>.apk` on the head unit and set it as the Home app.
2. Optional: `dashwheel-companion.apk` on your phone, an ELM327 Bluetooth adapter, a free Gemini key.
3. Follow the first-run setup and the tour.

Updates: Dashwheel updates itself from the stable channel. Turn on **Settings, About, Beta updates** to get each new version as soon as it is built; a beta becomes stable once it has been tried.

Details: [Installation and updates](https://github.com/deviloufr-ai/Dashwheel/wiki/Installation-and-Updates) · [Troubleshooting](https://github.com/deviloufr-ai/Dashwheel/wiki/Troubleshooting)

## iPhone owners

The Dashwheel Companion app is Android only, so an iPhone gets no notifications, messages, drive log, phone GPS or "where's my car" on the head unit. Everything else works without it:

- dashboards, widgets and skins, the built-in map and turn-by-turn guidance
- live car data from an OBD adapter and the head unit's CAN box, the AI mechanic, servicing reminders
- calls through the head unit's own Bluetooth (on ROCO K706 / QF units), and its radio and Bluetooth music
- CarPlay, where the head unit has it, next to Dashwheel

## Feedback and support

- Something broken, or a unit that isn't listed? In the app, **Settings, About, Report how it works** fills in a GitHub issue with your unit's model and firmware (a QR code for your phone). Or [open an issue](https://github.com/deviloufr-ai/Dashwheel/issues/new/choose) yourself.
- After a crash, Dashwheel offers to send the error the next time it starts, once the car is parked.
- If Dashwheel is useful to you, a ⭐ on this repo helps other drivers find it.
- [Ko-fi](https://ko-fi.com/deviloufr) pays for test hardware.

## Branded builds and commercial licences

Installers and resellers: custom or branded builds, and licences for closed distribution, are available. [Get in touch](https://deviloufr-ai.github.io/alexandreleblanc.github.io/).

## Build from source

Android Studio with a Gradle JDK of 17–21, then `./gradlew assembleDebug`. Toolchain, modules, CI and release signing: [Building from source](https://github.com/deviloufr-ai/Dashwheel/wiki/Building-from-Source). Pull requests are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Copyright (C) 2026 Alexandre Leblanc (deviloufr-ai)

Dashwheel (the launcher and the Companion app) is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY; see the [LICENSE](LICENSE) file for the full text.

The Dashwheel name and logo are not covered by the GPL: forks must use a different name and logo.
