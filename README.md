# Macro Runner

Android macro app (Kotlin + Jetpack Compose). No root required — taps and swipes are
injected through an AccessibilityService (`dispatchGesture`).

## Build
1. Open this folder in Android Studio (Koala or newer). Let it sync (it creates the Gradle wrapper).
2. Run on a device/emulator with Android 8.0+ (API 26).

## First-run setup
- Settings → Accessibility → Installed apps → **Macro Runner** → On.
  (Sideloaded on Android 13+? If the toggle is greyed out: Settings → Apps → Macro Runner → ⋮ → *Allow restricted settings*.)
- Tap **Allow** on the "exact alarms" banner (Android 12+) so macros start on time.
- Exclude the app from battery optimisation for the most reliable scheduling.

## Finding X, Y coordinates
Settings → Developer options → **Pointer location**. Tap the screen and read the coordinates (px).
The step editor shows your screen's pixel size.

## Limitations
- Android won't let any non-system app tap while the device is secure-locked; the screen is woken
  for scheduled runs, but a PIN/pattern lock stays in the way. Use a swipe-only lock or run while unlocked.
- Apps that mark windows as secure may ignore injected input.
