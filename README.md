# Ascend — Android wrapper

A native Android shell around the Ascend health tracker that reads **steps, deep & total sleep, HRV and weight** from **Health Connect** (fed by RingConn, Garmin, etc.) and syncs them straight into the app — no files, no Google Drive.

## How it works
- The full Ascend web app is bundled in `app/src/main/assets/ascend.html` and runs in a WebView (your data lives in the WebView's local storage).
- `HealthReader.kt` reads the last 14 days from Health Connect.
- `MainActivity.kt` auto-syncs every time you open the app, and the in-app **Sync** button calls `AndroidAscend.requestSync()`.

## Build (automatic, via GitHub Actions)
1. Push to the `main` branch (already set up).
2. Open the **Actions** tab → latest **Build Ascend APK** run → download the **ascend-debug-apk** artifact.
3. Unzip it to get `app-debug.apk`.

## Install on your phone
1. Copy `app-debug.apk` to your phone.
2. Tap it; allow "install unknown apps" for your file manager if prompted.
3. Open **Ascend**, tap **Sync**, and grant the Health Connect permissions.

## First-time data migration
Your existing history/logs live in the web PWA. In the PWA: **Settings → Back up (.json)**, then in this app **Settings → Restore** that file once.
