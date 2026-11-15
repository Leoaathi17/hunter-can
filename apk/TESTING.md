# Testing status (honest)

Nothing in the Android layer was compiled or run on a device in the environment that produced version 2.0
(no Android SDK or android.jar, no network, no phone). Only the pure-Java core was compiled and tested.

## Verified here (javac --release 8 on JDK 21, Python 3): `core/run_tests.sh`, 233 checks, 0 failures

- Real capture `sample.txt`: 42580 frames, per-ID counts and a CRC over the frame sequence equal to the Python
  analyzer; RPM maximum 4503. Same result for 1, 2, 3, 7, 63, 64, 100, 4096-byte and random chunk splits.
- Garbage and resync: injected bad lines, 10 kB junk line, 200 kB random bytes, glued frames, BEL anywhere in a
  line, RTR frames, V/N replies, 4096-byte overflow. No crash, no invented frames, counters increase.
- 11-bit `t` and 29-bit `T` frames, IDs out of range rejected, 8-digit display and capture form for 29-bit IDs,
  no key collision between 0x100 and 0x00000100.
- Command guard: only C, S0-S8, M1, O, V; data frames, S9, lowercase and multi-line input rejected. Source audit:
  `CdcAcmLink` has exactly one OUT bulk transfer and the service builds setup commands only through `SlcanSetup`.
- Bitrate auto-detect against a mock bus (500k, 250k, 125k, 1M in listen-only mode), rejection of a rate with
  error beeps or malformed lines, nothing found keeps the previous rate.
- Passive OBD decode on 0x7E8-0x7EF only; no request is ever built.
- User signals: decode math (endianness, signed, scale, offset), validation, per-profile storage, JSON export and
  import, header lines in captures, hero dials from units rpm / km/h / mph / gear.
- Settings JSON: defaults on garbage, per-profile bitrate and filter.
- Health counters and OK / WARN / CHECK rules (malformed, resync, beeps, overflow, gaps, bus load, counter
  jumps, unknown IDs); bus-load calculation.
- Byte stats: entropy, distinct values, changed-bit mask; counter and checksum hints.
- Find-signal and control-test phase machine and `Compare.rank` (same order and scores as `hunter_analyze.py`).
- Capture format ('# APP', '# PROFILE', '# SIGNAL', '# MARK', '# DISCONNECT', '# RECONNECT', '# HEALTH', then
  `<sec> <ID> <dlc> <hex>`): written by the engine, read back by the Java reader and by the real
  `hunter_analyze.py` (Hunter wizard capture and a generic-car capture with a 29-bit ID).

## Not verified

- That the app compiles. Every Android file was read by hand for imports, method names and signatures against the
  core, manifest permissions and Android 14 foreground-service rules (type connectedDevice,
  FOREGROUND_SERVICE_CONNECTED_DEVICE, CHANGE_NETWORK_STATE, API 29 guard on the typed `startForeground`,
  RECEIVER_NOT_EXPORTED on API 33+, mutable explicit PendingIntent for the USB permission). A hand review is not a compile.
- The look of the UI: layout on the real screen, gauge animation, touch targets, the status chip, onboarding.
- The CANable enumerating as CDC with the assumed interfaces and endpoints (descriptor scan with fallback exists).
- USB permission and auto-launch on HyperOS, foreground service survival with screen off and battery management.
- Read throughput and frame loss at real bus load, reconnect after a cable pull, watchdog with ignition off.
- That the Hunter 350 and OBD decoders match a vehicle beyond the real capture, that auto-detect behaves on a real bus,
  that the SAF file picker and share sheet work, charging through OTG while logging.

## Known deviations from the PWA

- The Android app uses the Android clock (`elapsedRealtimeNanos`) for capture time stamps.
- Junk glued directly in front of a frame on the same line (no CR) loses that one frame (also true on the web page).
- Compare excludes the 0x140 counter for the Hunter profile, like the Python analyzer.

## Version 2.1 (signal-map corrections, Battery gauge)

`core/run_tests.sh` now also replays real captures from `core/testdata/real/` (Horn, Ignition_on, engine-off, gear 1, kill switch, front brake, pass/flash): decoders (front/rear wheel, engine/intake temp, battery V, neutral bit, unverified B0 bit0), the BatteryMonitor state logic (engine off ~12.4 V, running ~13.8 V, cranking minimum ~11.2 V), the HEALTH footer (`vmin/vmax/crankmin`) and the gap rule (Horn capture: gaps = 0). Not run on a phone or on the bike: the new `BatteryGaugeView` and the Live tab layout are only compiled by the Gradle build, not exercised.
