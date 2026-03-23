# kRecords

kRecords is a Hammerhead Karoo extension that keeps a local cache of nearby Strava segments, watches your ride in real time, and raises an in-ride alert when you beat your current best elapsed time on a cached segment.

This version uses a Firebase backend for Strava OAuth and segment syncing:

- the Karoo app keeps only a backend URL plus a generated device key
- the Firebase backend stores the Strava client secret and refresh tokens
- the phone completes Strava login through a QR code
- the Karoo app polls Firebase for auth/session state and hydrated segment data

## Repo Layout

- `app/`
  Android Karoo extension
- `functions/`
  Firebase Cloud Functions backend
- `public/`
  Firebase Hosting public files
- `firebase.json`
  Hosting rewrite and Functions configuration
- `firestore.rules`
  Firestore lock-down rules

## What It Does

- Adds a graphical ride field that opens kRecords settings
- Shows a QR code that starts Strava login on the phone
- Polls Firebase to learn when auth finished
- Syncs recent ride history through the backend
- Hydrates nearby 20 km tiles through the backend as you move
- Detects segment starts and finishes on-device
- Raises `InRideAlert` and Control Center notifications for new local PRs

## Architecture

### Android app

- Stores:
  - backend base URL
  - generated `deviceId`
  - generated `deviceSecret`
  - local segment/tile cache
- Requests:
  - `POST /api/auth/start`
  - `GET /api/auth/session`
  - `POST /api/sync/history`
  - `POST /api/sync/tiles`

### Firebase backend

- Creates auth sessions
- Builds the Strava auth URL
- Handles the Strava callback
- Exchanges auth code for refresh/access tokens
- Refreshes tokens when needed
- Fetches recent activities and nearby segments from Strava
- Stores user, segment, and tile data in Firestore

## Firebase Setup

1. Create a Firebase project.
2. Enable:
   - Cloud Functions
   - Firestore
   - Firebase Hosting
3. Install the Firebase CLI and log in.
4. From this repo, set the Firebase project:

```bash
firebase use --add
```

5. Inside `functions/`, install dependencies:

```bash
cd functions
npm install
```

6. Store the Strava app credentials as Firebase Functions secrets:

```bash
firebase functions:secrets:set STRAVA_CLIENT_ID
firebase functions:secrets:set STRAVA_CLIENT_SECRET
```

The backend reads those secrets directly in `functions/src/index.ts`.

7. Deploy Hosting, Functions, and Firestore rules:

```bash
firebase deploy --only functions,hosting,firestore:rules
```

8. After deploy, note your Hosting origin, for example:

```text
https://YOUR_PROJECT_ID.web.app
```

This is the value you enter into kRecords as the backend URL.

## Strava App Setup

Create a Strava API application and configure its callback domain to match the Firebase Hosting domain you deployed.

Example:

- Hosting origin:
  `https://YOUR_PROJECT_ID.web.app`
- Strava callback domain:
  `YOUR_PROJECT_ID.web.app`

The backend generates callback URLs under:

```text
https://YOUR_PROJECT_ID.web.app/api/auth/callback
```

So the Strava app’s callback domain must match that host.

You will need:

- `client_id`
- `client_secret`

Those are used only by the Firebase backend now, not by the Karoo app.

## Backend Local Checks

The Firebase backend in this repo was validated to the point of:

- `npm install`
- `npm run build`

inside [functions](/Users/joaopereira/Code/kRecords/functions).

I did not deploy Firebase from this environment, so you still need to run the actual `firebase deploy` command with your project selected and secrets configured.

## Karoo App Setup

1. Install the APK on your Karoo.
2. Open kRecords from the app drawer.
3. Paste the Firebase Hosting origin into the backend URL field.
4. Tap `Start Strava Auth`.
5. Scan the QR code on your phone and finish Strava login.
6. Return to Karoo and tap `Check Auth Status`.
7. Tap `Sync Recent History`.
8. Start a ride.

Once synced, kRecords keeps a local segment cache on-device and only uses the backend to refresh history and hydrate nearby tiles.

## Android Build

```bash
GRADLE_USER_HOME=/tmp/krecords-gradle ./gradlew assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Backend Notes

- The current backend identifies a Karoo install by a generated `deviceId` + `deviceSecret`.
- That keeps the app simple and backend-free on the phone side, but it is still a lightweight trust model rather than a full user-auth system.
- A future hardening step would be to add Firebase Authentication and replace raw device credentials with authenticated user sessions.

## Current Constraints

- The Android app was rebuilt and verified locally.
- The Firebase Functions code was installed and compiled locally, but not deployed from this environment.
- PR detection is still local to kRecords; it is not official Strava Live Segments confirmation.
