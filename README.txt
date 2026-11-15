Hunter CAN Live v3 - CAN viewer for motorcycles and cars, with an optional OBD-II diagnostics tool
Install on an Android phone (Chrome), e.g. CANable via USB-OTG:
1. Create a free GitHub account, new PUBLIC repo named hunter-can.
2. Upload index.html, sw.js, manifest.webmanifest, icon.svg and the three icon-*.png files (tests/ and tools/ are optional), commit.
3. Settings > Pages > Source: main branch / root > Save. Wait 1 min.
4. On the phone open Chrome: https://<yourname>.github.io/hunter-can/
5. Chrome menu (3 dots) > Add to Home screen / Install app.
6. Settings > Adapter: pick your adapter type (SLCAN serial/USB, candleLight gs_usb, ELM327, WebSocket or BLE bridge). Options the browser lacks are hidden.
7. Plug in the adapter, switch ignition on, open the app, tap Connect.

MONITOR mode (default, banner "MONITOR - read-only"): the app only listens. It sends only listen-only setup commands and never transmits a CAN data frame.
DIAGNOSTICS (off by default): Settings > Diagnostics tool > Enable, read the warning screen. The Diag tab can then read standard OBD-II live data, VIN and fault codes, and clear codes (Mode 04, needs typing CLEAR, refused if the engine is running). It is the only mode that transmits, and only standard OBD-II requests. Hunter 350 and other motorcycles often use proprietary protocols, so it may not respond; Diagnostics on the Hunter profile is experimental and unverified. The banner's "Return to Monitor" button restores listen-only in one tap.

v3 has only been tested against software mocks. gs_usb/candleLight and ELM327 support in particular are untested on hardware; see SMOKE_TEST.md.
Tests: node tests/smoke.js ; node tests/v3.js ; node tests/browser.js (needs playwright + Chrome)
Analyse captures on a PC: python3 tools/hunter_analyze.py capture.txt   (shows "# TX" frames, mode changes and decoded DTC/VIN responses)
