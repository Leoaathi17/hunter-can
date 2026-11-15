# Hunter CAN Live v3 - smoke test report

Run date: 2026-11-15. Node v22.23.4, Google Chrome for Testing 153 (headless, via playwright), Python 3.13.
**No real hardware was used. Nothing in this report was tested against a CAN adapter, a vehicle or a motorcycle.** Everything ran against software mocks: fake serial ports, fake WebUSB / Web Bluetooth / WebSocket objects, and simulated adapters and ECUs written for these tests (`EcuSim`, `ElmSim`, `MockGsUsb` in `tests/v3.js`). The simulators encode my reading of the protocols, so a passing test means "the app does what I intended against my model of the device", not "the device behaves like this".

Run: `node tests/smoke.js` (30 v2 regression tests), `node tests/v3.js` (28 new tests), `node tests/browser.js` (41 real-browser DOM checks). All three pass on the delivered files.
Changes to existing v2 tests (the only two): test 2 (no external network) now allows exactly one `new WebSocket(url)` fed by the user's own URL setting, because the WebSocket bridge adapter is a v3 requirement; test 27 (service worker) now expects cache name `hcl-v3`. Every other v2 assertion is unchanged. `tests/harness.js` only gained optional hooks (`nav`, `globals`, `MockSerial`, `M0`/`L` in the mock port).

## What v3 adds

* Transport abstraction (`open, close, write, onFrame, bitrate, listenOnly, txEnabled, configure, sendFrame/sendRequest`) with SLCAN (Web Serial, WebUSB CDC, WebSocket, BLE-UART), gs_usb (WebUSB) and ELM327 (Web Serial, BLE) implementations; adapter picker in Settings that hides APIs the browser lacks.
* Diagnostics tab (off by default, warning screen, session-only enable): standard OBD-II over ISO 15765-4, 11-bit. Mode 01 PIDs, Mode 03/07/0A codes, Mode 09 VIN, Mode 04 clear. No write, security access, routine, flashing or raw-frame sender exists in the code.
* Mode banner (MONITOR read-only / DIAGNOSTICS active TX) with a one-tap return, TX log, `# TX` lines in the capture file, analyzer update.

## A. New v3 tests (tests/v3.js) - 28/28 passed

| # | Test | What it proves (against mocks) |
|---|------|--------------------------------|
| 1 | gs_usb frame round trip | 20-byte host frame layout, little-endian echo_id/can_id, EFF/RTR/ERR flags, DLC clamp, host-format 0x0000beef, mode struct |
| 2 | gs_usb bit timing | 48 MHz 10k..1M and 80 MHz 10k..1M give exact bitrates, sample point 87.5% (75% above 500k); 500k = brp 6, 13+2 tq at 48 MHz, brp 10 at 80 MHz; 750k at 80 MHz is refused rather than approximated |
| 3 | gs_usb transport on a mock WebUSB device | order HOST_FORMAT, DEVICE_CONFIG, BT_CONST, MODE reset, BITTIMING, MODE start with LISTEN_ONLY; RX frames delivered, echo and error frames not; start with flags 0 only in Diagnostics; bulk OUT frame is 20 bytes; no bulk OUT in Monitor |
| 4 | gs_usb safety | firmware that does not report listen-only is refused (controller never started); 80 MHz clock read from BT_CONST |
| 5 | ELM327 line parser | frames (11-bit, 29-bit), multiline, NO DATA, SEARCHING, STOPPED, errors, banners, junk |
| 6 | ELM327 init and monitoring | ATZ ATE0 ATL0 ATS1 ATH1 ATSP6 ATCAF0 ATCSM1 ATMA; monitored lines become frames; requests, ATSH, ATCAF1 are blocked in Monitor |
| 7 | ELM327 protocol by profile | ATSP6 baseline then ATSP8 for 250k; 29-bit maps to 7/9; other bitrates refused |
| 8 | ELM327 Diagnostics | monitor stopped, ATCAF1, ATSH7DF, multiline Mode 03 and VIN reassembled, NO DATA, no app-side flow control; ELM allow-list in Diagnostics accepts only OBD payloads and ATSH 7DF/7E0-7E7 |
| 9 | ELM327 over BLE | FFF0 UART discovered, notifications parsed, writes chunked to 20 bytes |
| 10 | ISO-TP | single frame, first + consecutive frames, padding, flow-control callback, interleaved ECUs, sequence wrap, sequence error, 1 s timeout |
| 11 | DTC decode | 0x0133 P0133, 0x4123 C0123, 0x8001 B0001, 0xC123 U0123 and 10 more vectors (checked against the SAE two-bit system / two-bit digit encoding and against the Python analyzer); generic description table is P0xxx only |
| 12 | Response parsing | Mode 03/07/0A with and without count byte and padding, VIN (valid/invalid), PID bitmaps, MIL/readiness, 8 live PID formulas |
| 13 | Mode switch | SLCAN `C,S6,M1,O` -> `C,S6,M0,O` -> `C,S6,M1,O`; USBtin `L` / `O` style; warning-screen gating; banner, chip, `# MODE_*` events |
| 14 | Monitor never transmits | allow-list, transport guard (even with txEnabled forced), `diagTx`, `clearDtcs`, reads all refused; simulator saw 0 data frames; one `sendFrame` and one `sendRequest` call site, both inside `diagTx` |
| 15 | Diagnostics reads | PID chain 00/20/40, 8 live PIDs, MIL, VIN with exactly one `30 00 00` flow control to 0x7E0, Mode 03/07/0A with two ECUs, no-answer handling |
| 16 | What may be transmitted | 26 non-OBD services, foreign ids, FF/CF/odd flow-control frames, non-zero padding all refused; source audit finds no write/security/routine/flash/raw-sender code or UI |
| 17 | Mode 04 clear flow | refused without exact `CLEAR`, without a prior read, outside Diagnostics, if RPM was seen on the bus, if the ECU reports RPM or speed > 0 (nothing sent in each case); success sends 04 then re-reads 03 and reports; negative response reported |
| 18 | Diagnostics UI flow | DTC list rendering (generic text vs raw), clear box, result text, link drop while in Diagnostics reconnects in Monitor |
| 19 | RPM tracking | OBD, Hunter 0x100 and user-decoder sources, 10 s window, expiry |
| 20 | Rate limit | 50 ms between requests, 30 frames/s cap, flow control exempt from the gap only |
| 21 | Idle timeout | back to Monitor after 5 min without a request; warning-screen and Hunter-experimental text present |
| 22 | Capture file | one `# TX` line per transmitted frame; `hunter_analyze.py` parses TX/MODE lines, flags Mode 04, decodes Mode 03 and VIN responses; ELM `isotp` lines parse; listen-only captures unchanged |
| 23 | Adapter picker | every combination of Web Serial / WebUSB / Web Bluetooth / WebSocket lists exactly the supported adapters, default, fallback, persistence, locked while connected |
| 24 | Serial options | baud, VID/PID filter (any / preset / custom), reconnect by remembered VID:PID |
| 25 | WebUSB CDC | bulk endpoints discovered from descriptors, DTR, line coding |
| 26 | WebSocket / BLE bridges | only ws:// or wss:// accepted, same allow-list over the socket, reconnect, BLE bridge speaks SLCAN |
| 27 | Global audit | every command and data frame any simulator saw happened in the right mode |
| 28 | UI statics | tab hidden by default, banner texts, warning screen, SW cache v3, manifest text |

## B. v3 real-browser checks (tests/browser.js) - 41/41 passed

The 20 v2 checks (section D) plus 21 for v3 on headless Chrome with a mock serial port: monitor banner, Diag tab hidden by default, adapter picker contents, warning screen (enable button disabled until acknowledged), tab appears after enabling, Hunter-experimental text, only `C S6 M1 O` written before entering Diagnostics, banner switches to DIAGNOSTICS and a one-tap Return to Monitor button appears, `M0` sent and the first `t7DF8...` frame written only after it, NO DATA shown when the mock does not answer, `M1` sent again on return, touch targets >= 44 px on Settings, the warning screen and Diag tab, no horizontal overflow at 390, 360 (six tabs) and 844x390 landscape, no console errors. Screenshots were written to /tmp/shots (not shipped).

## C. v2 regression suite (tests/smoke.js) - 30/30 passed

| # | Test | Result | What it proves |
|---|------|--------|----------------|
| 1 | Syntax: inline script, sw.js, manifest JSON, icons exist, analyzer parses | PASS | files are well-formed |
| 2 | No external network/CDN references | PASS | no http(s) src/href/@import/fetch/XHR in index.html; v3: exactly one `new WebSocket(url)` fed by the user URL setting, no literal address |
| 3 | Parser on real sample.txt | PASS | 42580 frames, 10 IDs, per-ID counts identical to the Python parse, 0 errors |
| 4 | Chunk-split robustness | PASS | random 1-37 char chunks and 30000 single-character feeds give identical results |
| 5 | Garbage / glued junk resync | PASS | junk-prefixed frame, two glued frames, truncated frame, bell inside a frame, RTR, adapter version reply, 5000-char overflow guard |
| 6 | 29-bit parsing | PASS | `T` frames parsed, IDs shown as 8 hex digits, no collision with 11-bit same number, capture line format, 11/29-bit display filter |
| 7 | Command allow-list | PASS | C, S0-S8, M1, O, V accepted; 34 dangerous strings rejected including `t...`/`T...` data frames, S9, S10, S-1, M0, lowercase, embedded CR; only one `link.write(` call site in the source |
| 8 | Bitrate S-codes | PASS | 10k..1M -> S0..S8, each applied as C,S<n>,M1,O; 300k refused |
| 9 | Auto-detect, mock frames only at 250k | PASS | 500k window has beeps (rejected), picks 250k, stops there, persists it |
| 10 | Auto-detect order and fallback | PASS | order 500,250,125,1M; picks 1M when only that works; restores previous rate when nothing works |
| 11 | Profile switching | PASS | data-prof sections hidden/shown, Hunter IDs ignored on car (no RPM/gear/speed), no Hunter "known" labels, controls list swapped, unknown-ID tracking Hunter-only, switch refused while recording |
| 12 | Profile settings persistence | PASS | custom bitrate re-applied to the live link, ID-format and profile restored after reload |
| 13 | User decoder math | PASS | BE/LE, signed 8/16/32, scale, offset, 24-bit, 32-bit without overflow, short-frame null, all against hand-computed values |
| 14 | Decoder validation, storage, JSON, header | PASS | rejects bad ID/start/length/NaN; newline injection stripped from names/units; per-profile storage; export/import round trip; hostile imports; starters valid and flagged unverified; header lines |
| 15 | Passive OBD-II | PASS | RPM 1726, speed 60, coolant 83, voltage 14.4, throttle 100 decoded from 0x7E8-0x7EA; 0x7F0, multi-frame and negative responses ignored; scan-tool request and DTC response only counted; stale after 5 s; nothing transmitted beyond setup commands |
| 16 | Health counters and OK/WARN/CHECK | PASS | idle/WARN/CHECK thresholds for beeps (1 / 5), malformed (1 / 25), no data, Hunter-only 0x140 jumps, reset |
| 17 | Bus load and gap timeline (mock clock) | PASS | ~26% load for 1000 fps x 8 bytes matches the formula; 2.5 s hole flagged WARN with the ID named; per-second buckets; 1 Hz ID not a false gap |
| 18 | Byte entropy / changed bits | PASS | entropy exactly 8 / 0 / 1 / 4 bits, toggle masks exact |
| 19 | Counter / checksum hints | PASS | +1 counter, high-nibble mod-16 counter, monotonic byte, XOR checksum, SUM+constant checksum found; constant and random bytes and a random ID give no hints; all phrased as hints |
| 20 | Find-signal compare | PASS | 0x00->0x3C found with bit mask 0x3C; rolling counter byte demoted |
| 21 | Find-signal wizard end-to-end (mock bus) | PASS | baseline then action, candidate 0x1A0 B2 00->3F, BASELINE/EVENT marks, name saved, gauge form prefilled |
| 22 | Round trip: wizard capture -> hunter_analyze.py | PASS | profile line, Hunter decoders skipped for car, control-test result parsed |
| 23 | Round trip: car recording (29-bit + OBD + decoders) -> analyzer | PASS | 300 frames, 8-digit ID, decoder min/max, passive OBD summary, HEALTH line, DISCONNECT/RECONNECT events |
| 24 | Round trip: Hunter recording of real sample -> analyzer | PASS | 42580 frames re-read, same RPM/gear/wheel/counter lines as the original capture; legacy synth.txt output unchanged except one renamed heading |
| 25 | Legacy raw serial log + 29-bit | PASS | old header-less logs still parse |
| 26 | Reconnect + autosave + recover | PASS | cable drop mid-recording, markers, setup re-sent, 28k lines recovered from IndexedDB, clear autosaves, user disconnect sends C |
| 27 | Service worker | PASS | cache `hcl-v3.1` (test changed, see below), all files exist, old `hunter-v1` cache purged, offline fallback, POST/cross-origin untouched |
| 28 | UX statics | PASS | five tabs, read-only notice, onboarding text, safe areas, reduced motion, landscape rule |
| 29 | Contrast (WCAG) | PASS | ink 13.9:1, muted 6.5:1, accent 8.6:1, ok 8.1:1, warn 9.3:1, bad 5.6:1 (worst over the four surfaces) |
| 30 | Global allow-list audit | PASS | all commands any mock adapter received (80+) were on the allow-list |

## D. v2 real-browser checks (first part of tests/browser.js) - all pass

First-run onboarding card shows and dismisses; Connect sends exactly C, S6, M1, O; chip shows `Hunter · 500k` with health badge; Hunter card visible on Hunter and hidden on car; car shows OBD card and no Hunter signal text; user gauge renders; every visible button/select/input is at least 44 px tall on all five tabs; landscape (844x390) turns the nav into a side rail with no page overflow; no horizontal overflow at 360 px; `prefers-reduced-motion` disables the chip animation; no console or page errors. Screenshots: portrait 390x844, landscape 844x390, 360x740 (generated to /tmp/shots, not shipped).

Dataviz palette validator (cyan accent vs red used in the timeline) on the dark surface: contrast PASS, normal-vision and CVD separation PASS (dE 32.6 / 17.6). The validator's "lightness band" check fails for the cyan/green/amber/red set; these are status and accent colours, not a multi-series categorical palette, and they are always paired with text (OK/WARN/CHECK words, tags, a text gap list under the timeline canvas).

## E. What only real hardware can verify

Everything below is UNVERIFIED. Treat v3 as untested on hardware.

**Adapters**
- **gs_usb / candleLight over WebUSB is entirely unverified.** The control-request numbers, struct layouts, flag bits (LISTEN_ONLY = bit 0), 20-byte frames and echo handling are from my knowledge of the Linux `gs_usb` driver and candleLight firmware, not from a device. Whether Chrome can claim the interface (Android may bind a kernel driver; on desktop Linux/Windows another driver may own it), whether the endpoint numbers are discovered correctly, and whether a given firmware really stays silent with the listen-only flag are all open.
- Bit timing is only correct if the clock the firmware reports in BT_CONST is the real CAN clock; only 48 MHz and 80 MHz (and whatever divides exactly) were exercised, always against a mock.
- **ELM327 behaviour is modelled, not observed.** Real clones differ a lot: banner text, whether `ATCSM1` is accepted, whether a single space really stops `ATMA`, whether `ATH1` shows PCI bytes on multi-frame replies, 29-bit header formatting, prompt timing, and dropped frames under ATMA load. ELM327 monitoring is expected to be slow and lossy; the UI says so. Most cheap Bluetooth ELM327 dongles are classic Bluetooth, which no browser can open; only BLE variants (FFE0/FFF0 UART) can work, and the characteristic choice is a guess from the properties.
- SLCAN firmware differences: `M1`/`M0` (CANable) vs `L`/`O` (USBtin) are chosen by a setting; I did not verify which firmware versions honour `M1` as silent mode. If a firmware ignores `M1`, "Monitor" would not be listen-only on that device. Check the first time with a scope or a second logger.
- CANable V1/V2 slcan, USBtin and CANtact VID/PID presets are from memory and may be wrong for your unit (use "Any device").
- WebUSB CDC endpoint discovery, DTR and line-coding behaviour on a real device; Web Serial baud settings on real USB serial chips.
- WebSocket bridge and BLE-UART bridge: no gateway exists yet. A page served from https cannot open plain `ws://` to another host (mixed content); use `wss://` or localhost.

**Diagnostics**
- Whether any real ECU answers the 0x7DF request. **The Hunter 350 very probably does not speak standard OBD-II over CAN**; expect NO DATA. The Hunter profile is marked experimental/unverified in the UI for that reason.
- Real ISO-TP timing: flow control must arrive within the ECU's timeout, ST-min/block-size handling is fixed at `30 00 00`, ECUs that need padding bytes other than 0x00 or that require different addressing are not handled.
- That Mode 04 really clears what the ECU reports, and what it does to freeze frames and readiness on a given vehicle. The safety checks (confirmation, prior read, RPM/speed) are software guards; the engine-running check only knows RPM it has seen (OBD PID 0C, Hunter 0x100, user decoders) and fresh answers from the ECU. If the vehicle does not report RPM at all, the check cannot detect a running engine.
- DTC descriptions are a small generic P0xxx table; manufacturer meanings can differ and most codes are shown raw.
- Whether an adapter in normal mode on a real bus ACKs/disturbs it, error-passive behaviour with a wrong bitrate in Diagnostics mode, and ISO 15765-4 bus wiring/termination (a bench or OBD port is assumed).

**Inherited from v2 (still unverified)**
- Web Serial / WebUSB permission prompts and real CDC behaviour on the POCO X5 PRO; the CANable accepting `S`, `M1`, `O` in that order and staying silent; error beeps; auto-detect on a real bus; decoder correctness on a vehicle; throughput, battery and wake-lock on Android; cable-drop timing; counter/checksum hints on real ECUs; visual quality on a physical OLED panel; installing as a PWA from GitHub Pages (service worker needs https).
- The Hunter signal decoders are unchanged from v1; their field validation status is whatever v1 had.


## v3.1: Hunter 350 signal-map corrections and Battery gauge (from the user's real captures, 2026-11-15)

Tests: `node tests/signals.js` (13 tests, uses `tests/data/real/*.txt`), plus the existing smoke.js, v3.js, browser.js. Not tested on hardware: everything here was checked by replaying the recorded captures, not on a bike or phone.

Signal map now used (PWA, APK and `tools/hunter_analyze.py`):

| Signal | Decoding | Tag |
|---|---|---|
| 0x211 B2 | battery voltage, raw x 0.1 V (13.8 V running, ~12.4 V engine off, ~11.2 V cranking) | strong evidence |
| 0x211 B0 | engine temperature, raw - 40 C | likely |
| 0x211 B1 | intake air temperature, raw - 40 C | likely |
| 0x12B B0:B1 / 10 km/h | FRONT wheel (wheel A) | likely |
| 0x12B B2:B3 / 10 km/h | REAR wheel (wheel B). Evidence: gear test, wheel B 4-5 km/h while A was 0, bike presumably on a stand | likely |
| 0x080 B7 bit6 (0x40) | neutral: set in neutral, clears in 1st gear. UNRESOLVED: it also clears when the kill switch is turned off | likely |
| 0x080 B0 bit0 | UNVERIFIED: on whenever the engine is off. It was labelled front brake, but never turned on in the running-engine front-brake captures | unverified |

Not found on CAN: horn, high beam, low beam, pass/flash, rear brake. Only the electrical load of pass/flash shows as a dip in the 0x211 B2 voltage.

Health "gaps" counter: the old rule counted a gap for any ID when the silence exceeded max(1 s, 5 periods) even for slow IDs, and did not forget the last-frame time across a disconnect, reconnect or bitrate-detect trial. The recorded captures contain no real silence > 1 s on any ID (the analyzer's "Real gaps" line is 0 for all of them) yet the app reported gaps=25..50 per capture. The exact trigger in the live app was not reproduced (only the recorded files were available); the likely source is the link set-up/detection phase. The fix: only an ID whose typical period is <= 220 ms (>= 5 Hz) can have a gap, and `markDiscontinuity()` / `discontinuity()` is called on connect, disconnect and around bitrate detection. Regression tests replay the real Horn capture (and six more) and expect gaps = 0, plus synthetic reconnect/detect cases that failed with the old rule.

Battery gauge: a voltage-state indicator (engine off / running / cranking minimum), NOT a battery state-of-health measurement. Thresholds are typical 12 V lead-acid values, unverified for this bike; they live in one block (`BAT_T` in index.html, constants at the top of `BatteryMonitor.java`). The capture HEALTH footer now ends with `vmin= vmax= crankmin=` when voltage was seen. Known limitation: right after a start the voltage is still recovering (12-13 V) and the gauge says "Low charge" until it climbs above 13.0 V.
