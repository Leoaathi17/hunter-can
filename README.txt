Hunter CAN Live v2 - read-only CAN viewer for motorcycles and cars
Install on an Android phone (Chrome), CANable via USB-OTG:
1. Create a free GitHub account, new PUBLIC repo named hunter-can.
2. Upload index.html, sw.js, manifest.webmanifest, icon.svg and the three icon-*.png files (tests/ and tools/ are optional), commit.
3. Settings > Pages > Source: main branch / root > Save. Wait 1 min.
4. On the phone open Chrome: https://<yourname>.github.io/hunter-can/
5. Chrome menu (3 dots) > Add to Home screen / Install app.
6. Plug in the CANable with the OTG cable, switch ignition on, open the app, tap Connect.
The app never transmits CAN data and never clears or writes fault codes.
Analyse captures on a PC: python3 tools/hunter_analyze.py capture.txt
