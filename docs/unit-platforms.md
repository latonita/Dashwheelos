# Head unit firmware families

Dashwheel grew up on one unit, the Hizpo / ROCO **K706** and its **QF** firmware. Since October 2026 it works out which firmware family a unit runs at start (`UnitPlatform`, `unitproto/UnitDetector.kt`) and speaks to that firmware through an adapter (`app/src/main/java/com/openauto/dash/unit/`). Everything below comes from the community's reverse engineering of each firmware, not from its maker; what is marked *unconfirmed* is implemented but waits for a tester's report (Settings → About → Report how it works now carries the family, what pointed at it, and which firmware features delivered something).

Settings → System → Head unit shows the family found, lets the driver name another one when the detector got it wrong, and lists what the firmware offers and what each feature still needs.

## The families

| Family | Units | How Dashwheel tells it | What Dashwheel does with it |
|---|---|---|---|
| **QF** | Hizpo / ROCO K706 (QF001 UIS7862, QF005 UIS8581a) | `sys.qf.is.acc.on`, `persist.sys.qf.launcher`, `com.qf.framework`, `ro.qf.platform`, the `mcu_service` and `util_service` binders | Everything: ignition, the car app's data and the MCU stream, the key service, the Bluetooth phone and players, the unit's volume, its launcher choice, its pop-ups, the reversing camera, the boot logo. |
| **FYT / SYU** | Joying, Atoto, Mekede, Junsun, Idoing, Isudar, NaviFly, Sinosmart, Winca, Teyes (locked), some Xtrons / Seicane; UIS7862(s), UIS7870, UIS8581a, SC9853i; DUDU units | `ro.fyt.*`, `ro.build.fytmanufacturer`, `ro.build.fytid`, the `com.syu.ms` app | Binds the firmware's toolkit service (`com.syu.ms/app.ToolkitService`): CAN module 7 for speed (code 1031) and revs (1032), both *unconfirmed*, plus the per-car codes the Signal Finder learns; module 2 for calls on the unit's Bluetooth (ringing = state 4, answer = command 9, hang up = 10; the other states *unconfirmed*); module 0 for the wheel keys (update 133), only after the unit is told to hand them over (`sys.carlink.type` = 2, a shell) and only when the driver turns that on. Ignition: `com.fyt.boot.ACCON` / `ACCOFF`. |
| **Microntek MTCD / MTCE, HCT MTCH** | Dasaita, Xtrons PX5 / PX6, Eonon, Pumpkin, Erisin, Klyde; PX3, PX5, PX6, PX30; QCM6125 for MTCH | `/system/priv-app/MTCManager` or `HCTManagerService`, the `android.microntek.CarManager` class with the `carservice` binder, Rockchip board names | Keys through `CarManager.attach("KeyDown")` or the `com.microntek.irkeyDown` / `irkeyUp` broadcasts; power-off from `com.microntek.bootcheck` (class = poweroff); the Vehicle app's `com.microntek.sync` frame (20 bytes, layout *unconfirmed*) and the system settings `com.microntek.controlinfo.*` as Signal Finder sources. The firmware force-stops apps it has not whitelisted at ignition off: the Head unit card says so. |
| **Topway TS** | TS10 (UIS7862), TS18 (UIS8151 / UIS8581), TS7 / TS8 (Allwinner); Hikity, Podofo, many generics | `com.tw.car`, `com.tw.carchoose`, `com.tw.carapps`, `com.tv.carinfoservice`; model `ums512_1h10_Natv`; build `TS10.x` | No public interface is known: plain Android, the vendor broadcasts below, the Signal Finder. |
| **Reglink** | the cheapest MediaTek units (AC8227L, AC8257) | `com.reglink.services`, `com.reglink.launcher4`, `/system/etc/reglink_autorun_whitelist.txt`, MediaTek board names, `persist.duraspeed.support` | Plain Android plus survival: DuraSpeed (MediaTek's background killer) turned off at each boot once Dashwheel may write secure settings; the three whitelist files on /system written with root; Quick Boot to turn off in the factory settings. Its fast boot (`com.mediatek.intent.action.QUICKBOOT_POWERON`, `BOOT_IPO`) counts as a boot. |
| **Allwinner** | T3 / T8 units with their own firmware (Junsun, Umecity, old Seicane) | `ro.board.platform` / `ro.hardware` starting with `sun` | Plain Android and the vendor broadcasts. |
| **Nowadays (NWD)** | K4811 and the like (sprd UIS8581) | `persist.nwd.launcher.default`, `ro.nwd.start.launcher.by.fw` | Plain Android; its launcher choice pointed at Dashwheel through a shell; `com.nwd.action.ACTION_KEY_VALUE` keys. |
| **XYAuto** | the "9320"-class MediaTek units (AC8227L, AC8257), Android 8.1; many no-name Aliexpress boxes | `com.kyhero.car.myhost`, `com.kyhero.car.myplugin.canbus`, `ro.board.platform` / `ro.hardware` = `ac8227l` / `ac8257` (AOSP's `com.android.launcher`, which holds the `com.xyauto.*` classes, counts only together with the board) | No binder, no service to bind: the CAN stack publishes everything as plain broadcasts. The host app and its per-brand decoder plugin send `send_canbus_data_engine` and `send_canbus_data_current`, the wheel keys come as `canbus.intent.action.key`, and the selected vehicle profile sits in `Settings.System` under `Canset_User`. The raw frame mirror `xy.auto.canbus.rxpduses` (turned on with `com.xygala.canbus.printdata`) is what a custom canbox firmware publishes through — everything the plugin does not decode has to come that way. *Unconfirmed*. |
| **Plain Android** | anything else | — | GPS speed, OBD, Android's own volume and keys; the vendor ACC and key broadcasts are still listened to. |

Most of these units claim a newer Android than they run (12, 13 or 14 shown, API 29 or 30 underneath): only the API level is trusted.

## Vendor broadcasts heard everywhere

Ignition and wake: `com.glsx.boot.ACCON`, `xy.android.acc.on`, `com.cayboy.action.ACC_ON` / `ACC_OFF`, `com.carboy.action.ACC_ON` / `ACC_OFF`, `autochips.intent.action.QB_POWERON`, `android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT`, the MediaTek quick-boot ones, `ACTION_SHUTDOWN`.

Wheel keys as broadcasts: Nowadays (`com.nwd.action.ACTION_KEY_VALUE`, byte `extra_key_value`), Winca (`com.winca.service.Setting.KEY_ACTION`), `android.intent.action.C3_HARDKEY` (key code and 0 = down), `IKeyClick.KEY_CLICK`, Eryanet's `com.eryanet.music.*` / `com.eryanet.media.*`, BZ's `com.bz.action.phone.pickup` / `hangup`, BYD's `APP_REV_DATA` frame. A button learned from one of these keeps its sender's code space on the wheel map.

## Trying another family on the emulator

`tools/fakeunit/` holds a debug app that stands in for the FYT firmware on the emulator (it is installed as `com.syu.ms` and speaks the real binder protocol, empty replies included), plays a scripted car and relays other firmwares' broadcasts, and scripts that make the emulator look like each family. Debug builds also honour `persist.dashwheel.family` (the family) and `persist.dashwheel.fytkeys` (take the FYT keys). See its README.

## Sources

- FYT: AxesOfEvil/FYTCanbusMonitor, Pacjonek/fyt-dialer, andreknieriem/open-headunit (CarFYTReceiver), cssbruno/honda-customizer's `com.syu.ms` audit, hvdwolf's XDA threads.
- Microntek: f1xpl/MtcdTools, the Hal9k and Malaysk ROM threads, the "reading vehicle sensor data" XDA thread.
- Reglink, detection table: the HeadUnit Revived auto-start guide (gist by andrecuellar).
- Topway: the TS10 / TS18 XDA threads (package names only).
