# Adware Scanner (Android)

A small, offline Android app that checks your phone for signs of adware.

## Download

Go to **Releases → latest → AdwareScanner.apk**, open it on your phone, and allow your browser/Files app to install it when Android asks.
(Every push to `main` rebuilds it automatically via GitHub Actions.)

## What it checks

**Every installed app** gets a risk score from these red flags:

- Active **device admin** (can block its own uninstall)
- **Accessibility** access turned on (can read the screen and tap for you)
- Allowed to **draw over other apps** (how pop-up ads appear)
- Can read all your **notifications**
- Installed **outside the Play Store** (APK file or another app)
- **No icon** in the app drawer (hiding)
- Can install other apps, watches app usage, starts on boot, refuses sleep, reads SMS
- Targets very old Android to dodge newer protections
- "Cleaner / booster / flashlight / wallpaper"-type app (common adware disguises)
- Installed in the last 14 days
- Set as default browser or home screen

**Phone-wide checks:** user-installed security certificates, device admins, accessibility services, active VPN, USB debugging, developer options, default browser and launcher.

Tap **App info** or **Uninstall** on any app. The list re-scans when you come back.

## Privacy

No `INTERNET` permission and no libraries. Nothing leaves the phone.

## Limits

Android sandboxes apps, so no app (including commercial antivirus) can read other apps' files.
This scanner flags warning signs; it doesn't prove an app is malware. Launchers, keyboards,
password managers and accessibility tools legitimately need some of these permissions.

## Build it yourself

Open the folder in Android Studio and press Run, or `./gradlew assembleDebug`.
