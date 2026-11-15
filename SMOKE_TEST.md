# Hunter CAN Live v2 - smoke test report

Run date: 2026-11-14. Node v22.23.4, Google Chrome for Testing 153 (headless, via playwright), Python 3.
**No real hardware was used.** Everything below ran against mocks (a fake CANable serial port, fake IndexedDB/localStorage) or captured data files.

Run: `node tests/smoke.js` (30 vm tests) and `node tests/browser.js` (real-browser render/DOM checks).
`tests/data/` holds the real capture (sample.txt, 42580 frames) and synth.txt.

## A. Node vm tests (tests/smoke.js) - 30/30 passed

| # | Test | Result | What it proves |
|---|------|--------|----------------|
| 1 | Syntax: inline script, sw.js, manifest JSON, icons exist, analyzer parses | PASS | files are well-formed |
| 2 | No external network/CDN references | PASS | no http(s) src/href/@import/fetch/XHR/WebSocket in index.html |
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
| 27 | Service worker | PASS | cache `hcl-v2.0`, all files exist, old `hunter-v1` cache purged, offline fallback, POST/cross-origin untouched |
| 28 | UX statics | PASS | five tabs, read-only notice, onboarding text, safe areas, reduced motion, landscape rule |
| 29 | Contrast (WCAG) | PASS | ink 13.9:1, muted 6.5:1, accent 8.6:1, ok 8.1:1, warn 9.3:1, bad 5.6:1 (worst over the four surfaces) |
| 30 | Global allow-list audit | PASS | all commands any mock adapter received (80+) were on the allow-list |

## B. Real-browser checks (tests/browser.js, headless Chrome, mock serial) - all pass

First-run onboarding card shows and dismisses; Connect sends exactly C, S6, M1, O; chip shows `Hunter · 500k` with health badge; Hunter card visible on Hunter and hidden on car; car shows OBD card and no Hunter signal text; user gauge renders; every visible button/select/input is at least 44 px tall on all five tabs; landscape (844x390) turns the nav into a side rail with no page overflow; no horizontal overflow at 360 px; `prefers-reduced-motion` disables the chip animation; no console or page errors. Screenshots: portrait 390x844, landscape 844x390, 360x740 (generated to /tmp/shots, not shipped).

Dataviz palette validator (cyan accent vs red used in the timeline) on the dark surface: contrast PASS, normal-vision and CVD separation PASS (dE 32.6 / 17.6). The validator's "lightness band" check fails for the cyan/green/amber/red set; these are status and accent colours, not a multi-series categorical palette, and they are always paired with text (OK/WARN/CHECK words, tags, a text gap list under the timeline canvas).

## C. What only real hardware can verify

- Web Serial / WebUSB permission prompt, device filter 16D0:117E, and real CDC/USB behaviour on the POCO X5 PRO (the mocks do not model USB).
- That the CANable firmware accepts `S`, `M1`, `O` in this order and really stays silent (listen-only) on the bus; that it sends `\a` beeps on errors exactly as the mock assumes.
- Auto-detect on a real bus: what a wrong bitrate looks like (beeps, nothing, or garbage), and whether the 1.5 s dwell is enough on slow-heartbeat networks.
- Real decoder correctness on a car: OBD responses only appear if a scan tool or the car already produces them; the user decoders and "unverified" starters are formula-checked, not vehicle-checked.
- Real throughput (phone CPU and render cost at 3000+ fps), battery and wake-lock behaviour, and Android resume after the screen is off.
- Cable-drop timing in a real vehicle (the reconnect test simulates the drop in software).
- Counter/checksum hints and bus-load percentages on real ECUs (checked only on synthetic frames; the load figure is an estimate with a 20% stuffing allowance).
- Visual quality on a physical OLED panel and in sunlight; screenshots were taken in desktop Chrome emulating a phone.
- Installing as a PWA from GitHub Pages (service worker needs https; tested with mocks only).
- The Hunter signal decoders are unchanged from v1; their field validation status is whatever v1 had.
