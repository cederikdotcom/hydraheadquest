# hydraheadquest Testbook

## Phase 1 smoke test

Run this after every sideload or release build. It proves the full head loop:
enroll, heartbeat, catalog, body selection, pairing, stream, stop, screenshot.

### Prerequisites

- A Quest in developer mode with the build installed (see the runbook).
- Fleet enrollment QR reachable at `hydracluster.experiencenet.com/enroll`
  (admin login).
- Admin token exported as `$ADMIN` for the curl checks below.
- Test streamer body `cosmic-pretzel-98` online and idle in HydraCluster
  (district bxl1, ip 11.0.11.24, wireguard_ip 10.10.100.12). This is the
  default bxl1 test streamer. Never test against `boom-pickle-38`.
- Check the streaming monitor first: the body must not be streaming to
  another head.

### Steps

**1. Enrollment**

- Launch the app on the headset. The QR scanner appears on first launch.
- Scan the fleet QR from `hydracluster.experiencenet.com/enroll`.
- Expected on device: the scanner goes away and the experience grid (or an
  empty-catalog message) appears. No error dialog.
- Expected in the cluster:

  ```sh
  curl -s -H "Authorization: Bearer $ADMIN" \
    https://hydracluster.experiencenet.com/api/v1/heads | jq '.[].name'
  ```

  A new head named `quest-head-<6 chars>` is listed. The suffix comes from the
  device id, so it must differ from every other head. If the head shows as
  pending, approve it in the admin UI before the next step.

**2. Heartbeat visible**

- Wait up to 30 s after enrollment.
- Expected:

  ```sh
  curl -s -H "Authorization: Bearer $ADMIN" \
    "https://hydracluster.experiencenet.com/api/v1/heads/<head_id>" | jq .
  ```

  - `status` is `self-service` (or `idle`).
  - `diagnostics.version` matches the installed build tag, for example
    `v0.1.0`.
  - `diagnostics.app` is `kiosk`.
  - `diagnostics.wifi_ssid` and `diagnostics.local_ip` show the venue network.
  - `last_seen` is fresh and stays fresh; the heartbeat repeats every 30 s
    while idle.

**3. Catalog fetch**

- Expected on device: the experience grid shows one tile per catalog entry.
- Cross-check against the API:

  ```sh
  curl -s -H "Authorization: Bearer $ADMIN" \
    "https://hydracluster.experiencenet.com/api/v1/heads/<head_id>/experiences" | jq .
  ```

  Tile labels match the `label` fields. An empty array must show a clear
  "no experiences" message, not a spinner forever and not a crash.

**4. Body selection and Sunshine pairing (cosmic-pretzel-98)**

- Tap an experience tile.
- Expected on device: a "finding a body" state, then a pairing state that
  names `cosmic-pretzel-98` (the body name, not an IP).
- Expected selection behavior: the app calls `/api/v1/bodies/eligible` and
  picks the first body with `stream_count == 0`. It probes candidate hosts on
  TCP 47990 with a 1 s timeout. The body's LAN ip 11.0.11.24 is not RFC1918,
  so the WireGuard ip 10.10.100.12 is probed first:
  - On a venue LAN with mesh routing (mobile-kit style), expect host
    `10.10.100.12`.
  - On the Visit Flanders LAN without mesh routing, expect fallback to
    `11.0.11.24`.
- Expected pairing behavior: GameStream pairing on port 47989, PIN posted to
  `https://<host>:47990/api/pin` with Basic auth. On `alreadyPaired` the app
  unpairs once and re-pairs. Pairing completes without any PIN entry on the
  headset.
- If discovery fails, the error must name the reason (no body, or the address
  that did not answer). It must not retry on its own.

**5. Stream start**

- Expected on device: the stream renders on the virtual screen within 30 s of
  the tile tap. Video and audio play. No artefacts.
- Expected resolution and bitrate: 1920x1080 at 60 fps for landscape,
  1080x1920 for a portrait experience. 150000 kbps on a LAN host, 20000 kbps
  when the host starts with `10.10.`.
- Expected in the cluster: `GET /api/v1/heads/<head_id>` shows
  `status: streaming` and `body_id` set to the body's id. The heartbeat
  interval drops to 5 s, so `last_seen` updates faster than in step 2.
- Expected in the streaming monitor: the head row shows `streaming` and the
  topology view draws an active line from cosmic-pretzel-98 to the head.

**6. Stream stop**

- Stop the stream from the in-app control.
- Expected on device: the experience grid returns. No error screen.
- Expected in the cluster: the app sends
  `DELETE /api/v1/heads/<head_id>/stream` with the body id, and it awaits the
  response before showing the grid. `GET /api/v1/heads/<head_id>` shows
  `status: self-service` with `body_id` cleared. The heartbeat returns to
  30 s.
- Immediately tap another tile. Discovery must succeed. A "no body available"
  error here means the DELETE was not awaited and the body was still marked
  busy.

**7. Remote screenshot command**

- With the app in the foreground on the headset:

  ```sh
  curl -sf -H "Authorization: Bearer $ADMIN" \
    "https://hydracluster.experiencenet.com/api/v1/heads/<head_id>/screenshot" \
    -o /tmp/quest.jpg
  ```

- Expected: a JPEG returns within about 5 s. The app polls
  `/api/v1/heads/<head_id>/commands` every 3 s, sees `{"screenshot": true}`,
  captures, and uploads raw JPEG bytes.
- The image must show the app UI (grid, overlays, error screens). The video
  surface may capture black, as on the iPad; that is acceptable for kiosk
  diagnosis. Note the result: if the whole image is black even on the grid,
  file an issue (MediaProjection fallback, open question in #544).
- A 504 means the headset did not answer the command poll. Check `last_seen`
  and the headset's network before blaming the build.

**8. Head record check**

- Final verification of the whole loop:

  ```sh
  curl -s -H "Authorization: Bearer $ADMIN" \
    "https://hydracluster.experiencenet.com/api/v1/heads/<head_id>" | jq .
  ```

- Expected after the test: `status: self-service`, fresh `last_seen`, correct
  `diagnostics.version`, `diagnostics.routing` matching how step 4 reached the
  body (`wireguard` for a `10.10.` host, `lan` otherwise), and
  `diagnostics.latency_ms` a number, not `?`.

### Pass criteria

- Enrollment creates a uniquely named `quest-head-*` entry; no name collision.
- Heartbeat visible within 30 s and steady; 5 s cadence while streaming.
- Catalog tiles match the experiences API.
- Body selection picks cosmic-pretzel-98 and pairing completes without manual
  PIN entry.
- Stream starts within 30 s, plays cleanly, and stops back to the grid; a
  second stream can start immediately after a stop.
- Remote screenshot returns a JPEG within about 5 s.
- The head record shows correct status, version, routing, and latency after
  the run.

### Cleanup

- Stop any stream you started and confirm cosmic-pretzel-98 shows idle again.
- If you streamed via PinchTab or the monitor, delete leftover streaming
  sessions.
