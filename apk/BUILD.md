# Building Hunter CAN logger (Android, Java)

Version 2.0. The APK was NOT built in the environment that produced this project (no Android SDK, no network).
The pure-Java core compiles and its 233 checks pass there; the Android layer (UI, service, USB driver) was only
reviewed by hand. Expect that the first build may show small compile errors; see TESTING.md.
The new UI is built in code (no Material library); the only dependency is androidx.core for FileProvider.

Requirements: JDK 17, Android SDK platform 34 (Android Studio or GitHub Actions installs it).
Gradle 8.7 + Android Gradle Plugin 8.5.2, minSdk 26, targetSdk 34, compileSdk 34.

## Path A: GitHub Actions (interim test builds, no local tooling)

1. Create a GitHub repo (private is fine) and upload the CONTENTS of this folder, including the hidden
   `.github` folder (drag the whole folder into "Add file > Upload files", or `git push`).
2. Every push runs `.github/workflows/build.yml`: it runs the core tests, installs the Android SDK,
   runs `gradle assembleDebug` and uploads the APK.
3. Open the repo > Actions tab > latest run > Artifacts > `app-debug.apk` (downloads as a zip, unzip it).
4. Copy `app-debug.apk` to the POCO X5 Pro, open it from Files, allow "Install unknown apps" for that
   file manager when asked, install. (Debug-signed; Play Protect may ask to scan it, choose "Install anyway".)
5. If the run fails, open the failed step; the compiler error names the file and line. Send it back and it can be fixed.

## Path B: Android Studio (final version)

1. Install Android Studio (Koala or newer). It brings JDK 17 and the SDK manager.
2. File > Open > select this folder (`hunter_apk`). Let Gradle sync finish; accept the prompt to install
   Android SDK Platform 34 if shown. (If Studio asks to upgrade AGP, decline or accept, both work.)
3. On the phone: Settings > About phone > tap MIUI/HyperOS version 7 times > Developer options > enable
   USB debugging. Connect the phone by cable and approve the RSA prompt.
   (The phone has one USB port, so to debug while the CANable is plugged in use wireless debugging:
   Developer options > Wireless debugging > Pair device with pairing code, then Studio > Device Manager > Pair.)
4. Pick the phone in the device drop-down and press Run. Or Build > Build APK(s) and sideload as in Path A.

## Path C: command line

    export ANDROID_HOME=$HOME/Android/Sdk        # SDK with platforms;android-34 and build-tools;34.0.0
    ./gradlew assembleDebug                      # or: gradle assembleDebug   (Gradle 8.7)
    # result: app/build/outputs/apk/debug/app-debug.apk
    adb install -r app/build/outputs/apk/debug/app-debug.apk

`gradlew` and `gradle/wrapper/gradle-wrapper.jar` are included (generated offline from Gradle 8.7).
If `gradlew` is missing on your copy, use a locally installed Gradle 8.7+ and run `gradle assembleDebug`.
Create `local.properties` with `sdk.dir=/path/to/Sdk` if Gradle cannot find the SDK.

## Core tests (any machine with JDK 8+ and Python 3, no Android needed)

    core/run_tests.sh

Compiles `core/src/main/java` with `javac --release 8` and runs `CoreTest` (233 checks) against the real
captures in `core/testdata` (sample.txt, synth.txt), the bundled `hunter_analyze.py` (run with `python3 -I`) and
the Android sources (a source audit of the command path). `tools/make_expected.py` regenerates the expected values
from the analyzer. The GitHub Actions workflow runs the same script before the Gradle build, so a core
regression fails the build.

## First run on the phone

1. Install, open the app once, allow notifications (Android 13+ asks). The onboarding card on Live explains the steps.
   Pick the vehicle profile and bitrate under Settings (Hunter 350 defaults to 500 kbps) or use Auto-detect with the bike running.
2. Plug the CANable via OTG. Android offers to open "Hunter CAN" for the device: tick "Use by default" and OK.
   Then the USB permission dialog appears: tick "Always allow" if offered.
3. In Settings > Apps > Hunter CAN > Battery: set "No restrictions" (the "Battery settings" button opens the list),
   and on HyperOS also enable Autostart. Otherwise the system may kill the service with the screen off.
4. Start recording, ride, Stop, then "Share / export" (or "All captures" to pick an older file).
   Files live in `Android/data/com.hunter.canlogger/files/captures/` and run through `python3 hunter_analyze.py file.txt`.
