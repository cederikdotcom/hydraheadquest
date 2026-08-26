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


## WireGuard (Phase 2, v0.3.0+)

Prerequisite: the head is enrolled and the admin config
`GET /api/v1/heads/{id}/wireguard-config` returns 200 (provisioned at
enrollment time by hydracluster through hydraguard).

1. Open Operator (PIN 1337), select WireGuard.
   - First run: the Android VPN consent dialog appears. Accept it.
   - Expected: status dialog shows "up hs <n>s" within a few seconds.
2. Check the heartbeat: `diagnostics.wireguard` in
   `GET /api/v1/heads/{id}` shows "up hs <n>s" and stays fresh.
   - "up no-handshake" means UDP 51820 to hydraguard.experiencenet.com
     is blocked, or Horizon OS interferes with VpnService.
   - "consent-needed" means the dialog was denied; run the operator
     action again.
3. Restart the app. Expected: the tunnel reconnects silently (no
   dialog) and the heartbeat shows "up hs" again.
4. Off-venue stream test: with the tunnel up, start an experience.
   Expected: LAN probe fails, mesh probe to the body wireguard_ip
   succeeds, pairing and stream proceed over 10.10.0.0/16.


## Diagnostics (v0.7.0+)

In-stream panel:
1. During a stream, wake the pointer and look below the screen. The
   move bar shows three buttons: info, environment, exit X.
2. Pinch or click the info button. A dark card appears left of the
   screen: Route, Body, Codec, Stream, Bitrate, RTT and variance,
   FPS incoming and rendered, Net drops, Host latency avg and
   min-max, Decode time. Values update every second.
3. Expected on a mesh body: Route mesh, Bitrate 20.0 Mbps.
4. Click info again to dismiss.

Operator 5-step run (Operator, PIN 1337, Diagnostics):
1. Tap Run diagnostics. Five steps run in order: Cluster connection,
   Experience catalog, Body available, Body reachable, WireGuard
   routing. Healthy result: five green checks and "All checks
   passed".
2. Step 4 probes hosts in the same order a stream start would (LAN
   first, then mesh). Step 5 shows the mesh probe plus the local
   tunnel state.
3. Report issue after a run includes the step results in the report.

## XR immersive path (issue #558)

Run after any change to the XR flow, and always together with the flat
smoke test: the top acceptance criterion is that flat streaming stays
untouched.

### Prerequisites

- ALVR client APK installed on the headset; head enrolled; tunnel up.
- The head node has the `hydraheadquest` role (automatic on new
  enrollments; heads enrolled by an older app version need an admin
  role change or a re-enroll, see the runbook).
- Head's `xr_client_hostname` set in hydracluster.
- An XR-capable body (role `alvr`, ALVR staged) idle in the head's
  district. First hardware: chunky-turnip-23 in a parked district.
- One library experience marked `"stream_mode": "xr"`.
- Never run this against bxl1 production bodies.

### Steps

1. Catalog: the XR experience tile shows an XR tag. Flat tiles are
   unchanged.
2. Tap the XR tile. Expected: "Preparing headset stream... (this takes
   about a minute)". Heartbeat `status` goes to `pairing` and
   `diagnostics.xr_client` shows `alvr`.
3. Within about 110 s the ALVR client launches by itself and the
   experience renders immersively. Heartbeat `status` is `streaming-xr`
   with the body id set.
4. Doff the headset for under 90 s, put it back on. Expected: the
   session resumes; the head still shows `streaming-xr`.
5. Reopen the kiosk (leave the ALVR client). Expected: "Return to
   experience" and "End session". Tap Return: the ALVR client comes
   back to the foreground.
6. Reopen the kiosk and tap End session. Expected: the grid returns
   within about 10 s and the head heartbeat goes back to
   `self-service`. No relaunch happens on the following ticks (the
   stale-block guard).
7. On the body: driver unregistered, Sunshine back up (hydrabody
   testbook has the exact checks).
8. Immediately start a FLAT stream on the same body. Expected: pairing
   and stream work first try. This proves the switch.
9. Negative test: uninstall or rename the ALVR client, tap the XR tile.
   Expected: "ALVR client not installed", no session request on the
   cluster, flat tiles still work.
10. Re-run the flat Phase 1 smoke test on cosmic-pretzel-98. Expected:
    byte-identical behavior to before the XR build.

### Pass criteria

- XR session arms and streams within 180 s.
- Doff under 90 s resumes; End session tears down and frees the body.
- The same body serves a flat stream immediately after an XR session.
- Flat streaming behavior is unchanged everywhere.
