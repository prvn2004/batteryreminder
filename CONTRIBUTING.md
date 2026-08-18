# 🤝 Contributing to AIO Battery Monitor

Thanks for considering a contribution. Bug fixes, feature work, documentation improvements, and UI polish are welcome, but battery and background-service behavior must be checked on real hardware where possible.

## Prerequisites

- Android Studio with an Android SDK 36 installation
- JDK 17 for Gradle/Android Gradle Plugin execution (the module compiles Java/Kotlin source for JVM 11)
- Git
- A physical Android device for battery-current, charging, overlay, boot, and OEM background-behavior checks

## Set up and build

~~~powershell
git clone https://github.com/prvn2004/batteryreminder.git
cd batteryreminder
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
~~~

Open the project in Android Studio, let Gradle sync, and run the app configuration. The first launch opens onboarding. Grant only the permissions needed for the features under test: notifications (Android 13+), display over other apps, modify system settings, and exemption from battery optimizations.

## Test commands

~~~powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
~~~

The current repository contains only the default sample unit and instrumentation tests. Treat the following as manual test requirements until feature-specific tests are added:

- Confirm percentage thresholds and alert duration behavior.
- Exercise sound, custom ringtone, vibration, flash, and TTS settings.
- Test the overlay on a locked screen and after overlay permission is revoked.
- Test the foreground service after app swipe-away, reboot, Doze, and OEM battery restrictions.
- Run the cable diagnostic while charging at different battery levels and temperatures.
- Compare prediction output on devices that do and do not expose current/charge-counter sensors.
- Check Android API 24 through the current target API where hardware or emulator coverage is available.

Do not describe a test as passing unless it has actually been run and observed.

## Architecture and coding standards

- Keep UI in activities/fragments and XML layouts; keep persistence in data; keep reusable calculations and device integrations in utils; keep lifecycle/background work in service.
- Follow the existing Hilt, Coroutines/Flow, Room, Preferences DataStore, View Binding, and Navigation patterns.
- Keep feature logic modular and add or update callers when changing a public function or data shape.
- Preserve the existing dark Material theme and accessibility-friendly labels.
- Do not add android.permission.INTERNET or analytics/ad SDKs without an explicit product decision. The app can open GitHub in an external browser, but that is separate from in-app network access.
- Do not commit local.properties, keystores, signing credentials, generated build outputs, or device-specific IDE files.

## Pull requests

1. Create a focused branch from production.

   ~~~powershell
   git checkout -b feature/short-description
   ~~~

2. Make the smallest coherent change and update documentation when behavior, permissions, commands, or configuration changes.
3. Run the relevant Gradle tests and manual device checks; include exact commands and any failures in the pull request.
4. Review the diff for secrets, generated artifacts, stale comments, and unrelated formatting changes.
5. Commit with a clear message and open a pull request against production. Include screenshots or a short recording for UI changes.

~~~powershell
git add .
git commit -m "feat: describe the change"
git push origin feature/short-description
~~~

## Release notes for maintainers

Pushes to production trigger .github/workflows/release.yml. CI uses JDK 17, injects the signing keystore from GitHub secrets, derives BUILD_NUMBER from the workflow run number, builds the APK/AAB, and publishes a v1.0.<run-number> GitHub Release. Never commit the keystore or its credentials.

## Licensing

There is currently no LICENSE file in the repository. Do not claim that contributions are covered by MIT or another license until the project owner adds an explicit license and contribution terms.
