# kRecords

kRecords is a Hammerhead Karoo app that helps you chase personal records on Strava segments.

It syncs your recent segment history, watches your ride live, and shows an in-ride alert when you beat your current best time on a cached segment.

## Download

Download the current beta APK here:

[kRecords v0.1.0-beta3 APK](https://github.com/zenpeartree/kRecords/releases/download/v0.1.0-beta3/kRecords-v0.1.0-beta3.apk)

You can also browse all builds on the [Releases page](https://github.com/zenpeartree/kRecords/releases).

## What You Need

- A Hammerhead Karoo
- A phone to complete Strava login
- A Strava account
- Internet access for the first sync and occasional refreshes

## Install

### Option 1: Hammerhead Companion App

1. Download the latest `app-release.apk` or `app-debug.apk` from the [Releases page](https://github.com/zenpeartree/kRecords/releases).
2. Open the APK on your phone.
3. Share it to the Hammerhead Companion App.
4. Install it on your Karoo when prompted.

### Option 2: ADB

1. Download the APK from the [Releases page](https://github.com/zenpeartree/kRecords/releases).
2. Connect your computer to the Karoo with ADB.
3. Install it:

```bash
adb install app-release.apk
```

## First-Time Setup

1. Open `kRecords` on your Karoo.
2. Tap `Start Strava Auth`.
3. Scan the QR code with your phone.
4. Sign in to Strava and approve access.
5. Return to the Karoo.
6. Wait for kRecords to detect the completed login, or tap `Check Auth Status`.
7. Tap `Sync Recent History`.

Once that finishes, kRecords will have your initial segment library and PR baselines.

## How To Use It

1. Start a ride on your Karoo.
2. Keep riding normally.
3. When you complete a cached segment faster than your current best local time, kRecords will show an in-ride PR alert.

## What It Does

- Starts Strava login on your phone with a QR code
- Syncs your recent Strava segment history
- Keeps a local cache of nearby segments on the device
- Hydrates nearby segment tiles as you move
- Detects segment starts and finishes on-device
- Alerts you when you set a new local PR

## Notes

- kRecords uses a backend for Strava authentication and sync, but end users do not need to set up Firebase.
- PR detection is local to kRecords and is not the same thing as official Strava Live Segments.
- Nearby segment discovery depends on what has already been synced and cached for your riding area.

## Troubleshooting

- If the QR code does not appear, make sure the device has internet access.
- If login finishes on the phone but the Karoo does not update, tap `Check Auth Status`.
- If no segments appear during a ride, run `Sync Recent History` again before riding.

## For Developers

The app and Firebase backend live in this repo. If you want to build or deploy it yourself, see the Android app in `app/` and the Firebase backend in `functions/`.
