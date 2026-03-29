# kRecords

kRecords is a Hammerhead Karoo app I built because I was ending up with way too many favorite segments on Strava just to make Karoo Live Segments usable.

I wanted the PR alerts, but I did not want the maintenance work:
- no constantly starring and unstarring segments on Strava
- no curating a list before every ride
- no fiddling with Karoo segment setup beyond signing in once

With `kRecords`, you just connect Strava, sync once, and ride. The app keeps a local segment cache, watches your ride live, and alerts you when you beat your best time on a segment it knows about.

## Download

Download the current APK here:

[kRecords v0.1.0 APK](https://github.com/zenpeartree/kRecords/releases/download/v0.1.0/kRecords-v0.1.0.apk)

You can also browse all builds on the [Releases page](https://github.com/zenpeartree/kRecords/releases).

## Optional Support

If `kRecords` saves you some annoyance and you want to leave a little sugar, you can do it here:

[PayPal tip jar](https://www.paypal.com/qrcodes/managed/43c0db10-e8fd-4f6f-b72a-035b8e3bf6d5?utm_source=consweb_more)

This is completely optional and not expected.

## What It Solves

Karoo Live Segments works well, but for my use case it pushed too much manual work into Strava:
- favorite the segments you care about
- keep that list trimmed
- manage it again when your routes and training focus change

`kRecords` is meant to remove that friction. After auth, the alerts should just work.

## What You Need

- A Hammerhead Karoo
- A phone for the Strava login flow
- A Strava account
- Internet access for the first sync and occasional refreshes

## Install

### Option 1: Hammerhead Companion App

1. Download the APK from the [Releases page](https://github.com/zenpeartree/kRecords/releases).
2. Open it on your phone.
3. Share it to the Hammerhead Companion App.
4. Install it on your Karoo when prompted.

### Option 2: ADB

1. Download the APK from the [Releases page](https://github.com/zenpeartree/kRecords/releases).
2. Connect your Karoo to your computer.
3. Install it:

```bash
adb install kRecords-v0.1.0.apk
```

## First-Time Setup

This is the only real setup:

1. Open `kRecords` on your Karoo.
2. Tap `Start Strava Auth`.
3. Scan the QR code with your phone.
4. Sign in to Strava and approve access.
5. Return to the Karoo.
6. Wait for `kRecords` to detect the completed login, or tap `Check Auth Status`.
7. Tap `Sync Recent History`.

After that, there is nothing else to manage on Strava or on the Karoo. Just ride.

## How It Works

- Auth happens on your phone through a QR-code flow.
- Recent Strava activity history is synced to build your first segment/PR baseline.
- Nearby segments are cached locally on the Karoo as you move.
- The app matches your live ride to those nearby segments on-device.
- If you beat your best local time, `kRecords` plays an alert and shows the PR result.

## What The Alert Shows

- If the segment already had a previous PR, the alert shows the new recorded time and the time saved.
- If it is your first known result for that segment, the alert just shows the recorded time.

## Notes

- End users do not need to set up Firebase or any backend infrastructure.
- This is not Strava Live Segments. It is a separate Karoo app focused on local PR detection.
- Nearby segment discovery depends on what has already been synced and cached for your riding area.

## Troubleshooting

- If the QR code does not appear, make sure the Karoo has internet access.
- If login finishes on the phone but the Karoo does not update, tap `Check Auth Status`.
- If no segments seem to trigger, run `Sync Recent History` again before riding.

## For Developers

The Android app lives in `app/` and the Firebase backend lives in `functions/`.
