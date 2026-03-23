import * as admin from "firebase-admin";
import express, { Request, Response } from "express";
import { onRequest } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";

admin.initializeApp();

const db = admin.firestore();
const app = express();
const stravaClientId = defineSecret("STRAVA_CLIENT_ID");
const stravaClientSecret = defineSecret("STRAVA_CLIENT_SECRET");

app.use(express.json());

app.post("/auth/start", async (req, res) => {
  try {
    const { deviceId, deviceSecret } = parseDeviceBody(req.body);
    const userRef = await ensureUser(deviceId, deviceSecret);
    const sessionId = randomId();
    const state = randomId();
    const callbackUrl = buildAbsoluteUrl(req, "/api/auth/callback");
    const authUrl = buildStravaAuthUrl(callbackUrl, state);

    await db.collection("authSessions").doc(sessionId).set({
      deviceId,
      state,
      status: "pending",
      authUrl,
      callbackUrl,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });
    await userRef.set(
      {
        lastAuthSessionId: sessionId,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true },
    );

    res.json({
      sessionId,
      authUrl,
      message: "Scan the QR code on your phone and finish Strava login.",
    });
  } catch (error) {
    sendError(res, error);
  }
});

app.get("/auth/session", async (req, res) => {
  try {
    const deviceId = requireString(req.query.deviceId, "deviceId");
    const deviceSecret = requireString(req.query.deviceSecret, "deviceSecret");
    const sessionId = requireString(req.query.sessionId, "sessionId");
    await verifyDevice(deviceId, deviceSecret);

    const sessionSnap = await db.collection("authSessions").doc(sessionId).get();
    if (!sessionSnap.exists) {
      res.status(404).json({ status: "missing", message: "Session not found." });
      return;
    }

    const session = sessionSnap.data() as Record<string, unknown>;
    if (session.deviceId !== deviceId) {
      res.status(403).json({ status: "forbidden", message: "Session does not belong to this device." });
      return;
    }

    res.json({
      status: session.status ?? "pending",
      message: session.message ?? "Waiting for authorization.",
      athleteName: session.athleteName ?? null,
    });
  } catch (error) {
    sendError(res, error);
  }
});

app.get("/auth/callback", async (req, res) => {
  const state = requireString(req.query.state, "state");
  const error = optionalString(req.query.error);
  const code = optionalString(req.query.code);

  const sessionQuery = await db.collection("authSessions").where("state", "==", state).limit(1).get();
  if (sessionQuery.empty) {
    res.status(404).send(renderHtml("Session not found", "This authorization session no longer exists."));
    return;
  }

  const sessionDoc = sessionQuery.docs[0];
  const session = sessionDoc.data() as Record<string, unknown>;
  const deviceId = session.deviceId as string;
  const callbackUrl = session.callbackUrl as string;

  if (error) {
    await sessionDoc.ref.set(
      {
        status: "error",
        message: `Strava returned an error: ${error}`,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    res.status(400).send(renderHtml("Authorization failed", `Strava returned: ${escapeHtml(error)}`));
    return;
  }

  if (!code) {
    await sessionDoc.ref.set(
      {
        status: "error",
        message: "Missing authorization code.",
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    res.status(400).send(renderHtml("Authorization failed", "No authorization code was returned by Strava."));
    return;
  }

  try {
    const tokens = await exchangeCode(code, callbackUrl);
    const athlete = await fetchAthlete(tokens.accessToken);
    const athleteName = [athlete.firstname, athlete.lastname].filter(Boolean).join(" ").trim() || `Athlete ${athlete.id}`;

    await db.collection("users").doc(deviceId).set(
      {
        athleteId: athlete.id,
        athleteName,
        tokens: {
          accessToken: tokens.accessToken,
          refreshToken: tokens.refreshToken,
          expiresAt: tokens.expiresAt,
        },
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    await sessionDoc.ref.set(
      {
        status: "complete",
        message: "Authorization complete. Return to kRecords and tap Check Auth Status.",
        athleteName,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true },
    );

    res.send(renderHtml("kRecords connected", `You can return to your Karoo now. Connected athlete: ${escapeHtml(athleteName)}.`));
  } catch (callbackError) {
    await sessionDoc.ref.set(
      {
        status: "error",
        message: callbackError instanceof Error ? callbackError.message : "Authorization exchange failed.",
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    res.status(500).send(renderHtml("Authorization failed", escapeHtml(callbackError instanceof Error ? callbackError.message : "Unknown error")));
  }
});

app.post("/sync/history", async (req, res) => {
  try {
    const { deviceId, deviceSecret } = parseDeviceBody(req.body);
    const user = await loadAuthorizedUser(deviceId, deviceSecret);
    const accessToken = await ensureAccessToken(deviceId, user);
    const activities = await stravaJson<Array<Record<string, unknown>>>(accessToken, "https://www.strava.com/api/v3/athlete/activities?per_page=15&page=1");

    const bestEfforts = new Map<number, number>();
    let activitiesSeen = 0;

    for (const activity of activities) {
      const activityId = Number(activity.id ?? 0);
      if (!activityId) continue;
      activitiesSeen += 1;
      const detail = await stravaJson<Record<string, unknown>>(accessToken, `https://www.strava.com/api/v3/activities/${activityId}?include_all_efforts=true`);
      const efforts = Array.isArray(detail.segment_efforts) ? detail.segment_efforts as Array<Record<string, unknown>> : [];
      for (const effort of efforts) {
        const segmentId = Number((effort.segment as Record<string, unknown> | undefined)?.id ?? 0);
        const elapsed = Number(effort.elapsed_time ?? 0);
        if (!segmentId || !elapsed) continue;
        const current = bestEfforts.get(segmentId);
        if (current == null || elapsed < current) {
          bestEfforts.set(segmentId, elapsed);
        }
      }
    }

    const detailedSegments: StoredSegment[] = [];
    for (const [segmentId, bestElapsedTimeSeconds] of Array.from(bestEfforts.entries()).slice(0, 30)) {
      const segment = await fetchSegment(accessToken, segmentId, bestElapsedTimeSeconds);
      detailedSegments.push(segment);
      await saveSegment(deviceId, segment);
    }

    await db.collection("users").doc(deviceId).set(
      {
        athleteName: user.athleteName,
        lastHistorySyncAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true },
    );

    res.json({
      message: `Synced ${activitiesSeen} activities and refreshed ${detailedSegments.length} segments.`,
      activitiesSeen,
      segmentsUpdated: detailedSegments.length,
      athleteName: user.athleteName,
      segments: detailedSegments,
    });
  } catch (error) {
    sendError(res, error);
  }
});

app.post("/sync/tiles", async (req, res) => {
  try {
    const { deviceId, deviceSecret } = parseDeviceBody(req.body);
    const user = await loadAuthorizedUser(deviceId, deviceSecret);
    const accessToken = await ensureAccessToken(deviceId, user);
    const tiles = Array.isArray(req.body.tiles) ? req.body.tiles as Array<Record<string, unknown>> : [];
    const now = Date.now();

    let tilesHydrated = 0;
    let segmentsUpdated = 0;

    for (const tile of tiles.slice(0, 2)) {
      const tileId = requireString(tile.tileId, "tileId");
      const bounds = parseBounds(tile.bounds);
      const existingTile = await db.collection("users").doc(deviceId).collection("tiles").doc(tileId).get();
      const expiresAt = Number(existingTile.data()?.expiresAt ?? 0);
      if (existingTile.exists && expiresAt > now) {
        continue;
      }

      const segments = await stravaJson<{ segments?: Array<Record<string, unknown>> }>(
        accessToken,
        `https://www.strava.com/api/v3/segments/explore?bounds=${bounds.minLat},${bounds.minLng},${bounds.maxLat},${bounds.maxLng}`,
      );
      const segmentIds: number[] = [];
      for (const summary of segments.segments ?? []) {
        const segmentId = Number(summary.id ?? 0);
        if (!segmentId) continue;
        const storedSegment = await fetchSegment(accessToken, segmentId, undefined);
        segmentIds.push(segmentId);
        segmentsUpdated += 1;
        await saveSegment(deviceId, storedSegment);
      }

      await db.collection("users").doc(deviceId).collection("tiles").doc(tileId).set({
        tileId,
        bounds,
        segmentIds,
        fetchedAt: now,
        expiresAt: now + 12 * 60 * 60 * 1000,
      });
      tilesHydrated += 1;
    }

    const requestedTileIds = tiles.map((tile) => requireString(tile.tileId, "tileId"));
    const tileSnapshots = await Promise.all(
      requestedTileIds.map((tileId) =>
        db.collection("users").doc(deviceId).collection("tiles").doc(tileId).get(),
      ),
    );
    const segmentIds = new Set<number>();
    const tilePayload = tileSnapshots
      .filter((snap) => snap.exists)
      .map((snap) => {
        const data = snap.data() as Record<string, unknown>;
        const ids = Array.isArray(data.segmentIds) ? data.segmentIds.map((value) => Number(value)) : [];
        ids.forEach((id) => segmentIds.add(id));
        return {
          tileId: data.tileId,
          bounds: data.bounds,
          segmentIds: ids,
        };
      });
    const segmentPayload = await Promise.all(
      Array.from(segmentIds).map(async (segmentId) => {
        const snap = await db.collection("users").doc(deviceId).collection("segments").doc(String(segmentId)).get();
        return snap.data();
      }),
    );

    res.json({
      message: `Hydrated ${tilesHydrated} nearby tiles and returned ${segmentPayload.filter(Boolean).length} cached segments.`,
      tilesHydrated,
      segmentsUpdated,
      athleteName: user.athleteName,
      tiles: tilePayload,
      segments: segmentPayload.filter(Boolean),
    });
  } catch (error) {
    sendError(res, error);
  }
});

export const api = onRequest(
  {
    region: "us-central1",
    cors: true,
    secrets: [stravaClientId, stravaClientSecret],
  },
  app,
);

type Bounds = {
  minLat: number;
  minLng: number;
  maxLat: number;
  maxLng: number;
};

type UserDoc = {
  deviceSecret: string;
  athleteName?: string;
  tokens?: {
    accessToken: string;
    refreshToken: string;
    expiresAt: number;
  };
};

type StoredSegment = {
  id: number;
  name: string;
  activityType: string;
  distanceMeters: number;
  start: { lat: number; lng: number };
  end: { lat: number; lng: number };
  bounds: Bounds;
  polyline: string;
  bestElapsedTimeSeconds?: number;
  stravaPrElapsedTimeSeconds?: number;
  updatedAt: number;
};

function parseDeviceBody(body: unknown): { deviceId: string; deviceSecret: string } {
  const payload = body as Record<string, unknown>;
  return {
    deviceId: requireString(payload.deviceId, "deviceId"),
    deviceSecret: requireString(payload.deviceSecret, "deviceSecret"),
  };
}

async function ensureUser(deviceId: string, deviceSecret: string) {
  const ref = db.collection("users").doc(deviceId);
  const snap = await ref.get();
  if (!snap.exists) {
    await ref.set({
      deviceSecret,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
    return ref;
  }
  const data = snap.data() as UserDoc;
  if (data.deviceSecret !== deviceSecret) {
    throw new Error("Device secret does not match existing registration.");
  }
  return ref;
}

async function verifyDevice(deviceId: string, deviceSecret: string): Promise<UserDoc> {
  const snap = await db.collection("users").doc(deviceId).get();
  if (!snap.exists) {
    throw new Error("Unknown device. Start a new auth session first.");
  }
  const data = snap.data() as UserDoc;
  if (data.deviceSecret !== deviceSecret) {
    throw new Error("Device secret mismatch.");
  }
  return data;
}

async function loadAuthorizedUser(deviceId: string, deviceSecret: string): Promise<UserDoc> {
  const user = await verifyDevice(deviceId, deviceSecret);
  if (!user.tokens?.refreshToken) {
    throw new Error("This device has not completed Strava authorization yet.");
  }
  return user;
}

function buildStravaAuthUrl(callbackUrl: string, state: string): string {
  const url = new URL("https://www.strava.com/oauth/mobile/authorize");
  url.searchParams.set("client_id", stravaClientId.value());
  url.searchParams.set("redirect_uri", callbackUrl);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("approval_prompt", "auto");
  url.searchParams.set("scope", "read,activity:read_all");
  url.searchParams.set("state", state);
  return url.toString();
}

async function exchangeCode(code: string, redirectUri: string) {
  const response = await fetch("https://www.strava.com/oauth/token", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: new URLSearchParams({
      client_id: stravaClientId.value(),
      client_secret: stravaClientSecret.value(),
      code,
      grant_type: "authorization_code",
      redirect_uri: redirectUri,
    }),
  });
  if (!response.ok) {
    throw new Error(`Token exchange failed with ${response.status}.`);
  }
  const body = await response.json() as Record<string, unknown>;
  return {
    accessToken: String(body.access_token ?? ""),
    refreshToken: String(body.refresh_token ?? ""),
    expiresAt: Number(body.expires_at ?? 0),
  };
}

async function ensureAccessToken(deviceId: string, user: UserDoc): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const tokens = user.tokens;
  if (!tokens) {
    throw new Error("Missing Strava tokens.");
  }
  if (tokens.accessToken && tokens.expiresAt > now + 300) {
    return tokens.accessToken;
  }

  const response = await fetch("https://www.strava.com/oauth/token", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: new URLSearchParams({
      client_id: stravaClientId.value(),
      client_secret: stravaClientSecret.value(),
      grant_type: "refresh_token",
      refresh_token: tokens.refreshToken,
    }),
  });
  if (!response.ok) {
    throw new Error(`Token refresh failed with ${response.status}.`);
  }
  const body = await response.json() as Record<string, unknown>;
  const refreshed = {
    accessToken: String(body.access_token ?? ""),
    refreshToken: String(body.refresh_token ?? tokens.refreshToken),
    expiresAt: Number(body.expires_at ?? 0),
  };
  await db.collection("users").doc(deviceId).set(
    {
      tokens: refreshed,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    },
    { merge: true },
  );
  return refreshed.accessToken;
}

async function fetchAthlete(accessToken: string) {
  return stravaJson<Record<string, unknown>>(accessToken, "https://www.strava.com/api/v3/athlete");
}

async function fetchSegment(accessToken: string, segmentId: number, bestElapsedTimeSeconds?: number): Promise<StoredSegment> {
  const segment = await stravaJson<Record<string, unknown>>(accessToken, `https://www.strava.com/api/v3/segments/${segmentId}`);
  const start = parseLatLng(segment.start_latlng);
  const end = parseLatLng(segment.end_latlng);
  const map = (segment.map ?? {}) as Record<string, unknown>;
  const polyline = String(map.polyline ?? map.summary_polyline ?? "");
  const points = decodePolyline(polyline);
  const effectivePoints = points.length > 0 ? points : [start, end];
  const bounds = {
    minLat: Math.min(...effectivePoints.map((point) => point.lat)),
    minLng: Math.min(...effectivePoints.map((point) => point.lng)),
    maxLat: Math.max(...effectivePoints.map((point) => point.lat)),
    maxLng: Math.max(...effectivePoints.map((point) => point.lng)),
  };
  const stravaPr = Number(((segment.athlete_segment_stats ?? {}) as Record<string, unknown>).pr_elapsed_time ?? 0) || undefined;

  return {
    id: segmentId,
    name: String(segment.name ?? `Segment ${segmentId}`),
    activityType: String(segment.activity_type ?? "Ride"),
    distanceMeters: Number(segment.distance ?? 0),
    start,
    end,
    bounds,
    polyline,
    bestElapsedTimeSeconds: [bestElapsedTimeSeconds, stravaPr].filter((value): value is number => typeof value === "number" && value > 0).sort((a, b) => a - b)[0],
    stravaPrElapsedTimeSeconds: stravaPr,
    updatedAt: Date.now(),
  };
}

async function saveSegment(deviceId: string, segment: StoredSegment) {
  await db.collection("users").doc(deviceId).collection("segments").doc(String(segment.id)).set(segment);
}

async function stravaJson<T>(accessToken: string, url: string): Promise<T> {
  const response = await fetch(url, {
    headers: {
      Authorization: `Bearer ${accessToken}`,
    },
  });
  if (!response.ok) {
    throw new Error(`Strava request failed with ${response.status}.`);
  }
  return response.json() as Promise<T>;
}

function parseBounds(value: unknown): Bounds {
  const bounds = value as Record<string, unknown>;
  return {
    minLat: Number(bounds.minLat ?? 0),
    minLng: Number(bounds.minLng ?? 0),
    maxLat: Number(bounds.maxLat ?? 0),
    maxLng: Number(bounds.maxLng ?? 0),
  };
}

function parseLatLng(value: unknown): { lat: number; lng: number } {
  const latLng = Array.isArray(value) ? value : [];
  return {
    lat: Number(latLng[0] ?? 0),
    lng: Number(latLng[1] ?? 0),
  };
}

function buildAbsoluteUrl(req: Request, path: string): string {
  return `${req.protocol}://${req.get("host")}${path}`;
}

function requireString(value: unknown, fieldName: string): string {
  const stringValue = String(value ?? "").trim();
  if (!stringValue) {
    throw new Error(`Missing ${fieldName}.`);
  }
  return stringValue;
}

function optionalString(value: unknown): string | undefined {
  const stringValue = String(value ?? "").trim();
  return stringValue || undefined;
}

function randomId(): string {
  return `${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}`;
}

function sendError(res: Response, error: unknown) {
  const message = error instanceof Error ? error.message : "Unknown error";
  res.status(400).json({ error: message, message });
}

function renderHtml(title: string, message: string) {
  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>${escapeHtml(title)}</title>
    <style>
      body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 3rem auto; max-width: 36rem; padding: 0 1rem; line-height: 1.5; }
      h1 { margin-bottom: 0.5rem; }
      p { color: #333; }
    </style>
  </head>
  <body>
    <h1>${escapeHtml(title)}</h1>
    <p>${message}</p>
  </body>
</html>`;
}

function escapeHtml(value: string) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function decodePolyline(encoded: string): Array<{ lat: number; lng: number }> {
  if (!encoded) return [];
  const points: Array<{ lat: number; lng: number }> = [];
  let index = 0;
  let lat = 0;
  let lng = 0;

  while (index < encoded.length) {
    let result = 0;
    let shift = 0;
    let byte;
    do {
      byte = encoded.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20);
    lat += result & 1 ? ~(result >> 1) : result >> 1;

    result = 0;
    shift = 0;
    do {
      byte = encoded.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20);
    lng += result & 1 ? ~(result >> 1) : result >> 1;

    points.push({ lat: lat / 1e5, lng: lng / 1e5 });
  }

  return points;
}
