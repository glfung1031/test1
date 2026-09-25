# Stock Tracker (Android)
Build the APK (no Android Studio needed):
1. Create a new GitHub repo, upload everything in this folder (keep `.github/`).
2. Actions tab -> "Build APK" -> Run workflow. Download `stock-tracker-apk` artifact -> unzip -> app-debug.apk.
3. Sideload to your phone (allow "install unknown apps").
Or: open in Android Studio and press Run.

Gmail email: enable 2-Step Verification, then create an App Password at myaccount.google.com/apppasswords.
Tip: set the app to "Unrestricted" in Settings > Battery. For 1:00-2:00 polling a foreground service runs; reopen the app after a reboot to restart it.


Target/Amazon: the app uses Android WebView for these retailers so JavaScript-rendered product pages can be inspected. Amazon `a.co` short links are supported and resolved before parsing.

## Test URLs used for version 2
Amazon:
- https://a.co/d/0dcOLRLf
- https://a.co/d/0eq3mUgZ
- https://a.co/d/06736Oz6

Target:
- https://www.target.com/p/pok-233-mon-trading-card-game-30th-celebration-knock-out-collection/-/A-1010892070
- https://www.target.com/p/pok-233-mon-trading-card-game-30th-celebration-elite-trainer-box/-/A-1010892076
