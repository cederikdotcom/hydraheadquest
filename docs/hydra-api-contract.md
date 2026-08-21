# Hydra head API contract

This is the exact hydracluster head API contract that hydraheadquest must implement.
It is transcribed from the working iPad implementation:

- `hydraheadipad/Sources/HydraHeadiPad/Services/HydraClusterClient.swift`
- `hydraheadipad/Sources/HydraHeadiPad/Models/HeadConfig.swift`
- `hydraheadipad/Sources/HydraHeadiPad/AppState.swift`
- `hydraheadipad/Sources/HydraHeadiPad/Services/MicrophoneRelay.swift`
- `hydraheadipad/Vendors/hydra-moonlight-ios/Limelight/HydraPairSession.m`

All JSON field names below are verbatim from the wire format. Do not rename them.

## 1. Transport and auth

- Base URL: `server_url` from enrollment. Strip trailing slashes before joining paths.
- Every request after enrollment sends `Authorization: Bearer <token>` with the
  per-device head token.
- Enrollment itself sends `Authorization: Bearer <enrollment_token>` (the fleet token
  from the QR code).
- Request timeout: 15 s. Resource timeout: 30 s.
- Success is any 2xx. On non-2xx, surface `HTTP <code>: <body>` to logs.

## 2. Enrollment

### QR code payload

One fleet QR serves all devices. The QR encodes this JSON:

```json
{
  "server_url": "https://cluster.example.com",
  "enrollment_token": "..."
}
```

### POST /api/v1/heads

Called once on first launch after the QR scan.

- Auth: `Authorization: Bearer <enrollment_token>`
- `Content-Type: application/json`
- Request body:

```json
{ "name": "quest-head-<6 chars of device id>" }
```

The iPad app sends `"ipad-head"` for all devices. That caused a HydraGuard name
collision (hydracluster #449). Quest must send a unique name from the first build:
`quest-head-` plus the first 6 characters of a stable device id.

- Response body:

```json
{
  "server_url": "https://cluster.example.com",
  "head_id": "head-abc123",
  "token": "per-device-token"
}
```

Persist all three fields (`server_url`, `head_id`, `token`). They are the identity
of this head for every later call. On Android, use SharedPreferences or DataStore.

## 3. Head config

### GET /api/v1/heads/{head_id}

Fetched on every tick (see timing). Response:

```json
{
  "name": "quest-head-a1b2c3",
  "type": "kiosk",
  "district": "bxl1",
  "venue": "cloud-seven",
  "stream": {
    "stream_url": "sunshine://10.0.0.5",
    "stream_url_lan": "10.0.0.5",
    "stream_app_id": "experience-name",
    "stream_mode": "streaming"
  },
  "sunshine_username": "sunshine",
  "sunshine_password": "..."
}
```

All fields are optional. `stream` is null or absent when the admin has not assigned
a stream to this head.

`stream` semantics:

- The assignment is active when both `stream_url` and `stream_app_id` are non-empty.
- Host resolution for an assignment: use `stream_url_lan` when non-empty, else strip
  the scheme prefix (everything through `://`) from `stream_url`.
- An active assignment tells the head to start that experience. See the state
  machine, section 10.
- Default Sunshine credentials when the config omits them: username `sunshine`,
  password `sunshine`.

## 4. Heartbeat

### PUT /api/v1/heads/{head_id}

- Timing: every 30 s when idle. Every 5 s while streaming. Also once immediately
  after enrollment or app start.
- `Content-Type: application/json`
- Request body:

```json
{
  "status": "self-service",
  "body_id": "node-xyz",
  "diagnostics": {
    "version": "v0.1.0",
    "wireguard": "connected",
    "app": "kiosk",
    "routing": "lan",
    "latency_ms": "12",
    "wifi_ssid": "VenueNet",
    "local_ip": "192.168.1.50",
    "moonlight_client_id": "0123456789ABCDEF"
  }
}
```

Field rules:

- `status`: one of `idle`, `self-service`, `starting`, `streaming`, `error`.
  The state machine maps to these values (section 10).
- `body_id`: the id of the body currently streamed from. Null when not streaming.
- `version`: app version prefixed with `v`.
- `wireguard`: tunnel state string from the WireGuard manager
  (for example `connected`, `disconnected`, `not-installed`).
- `app`: the literal string `kiosk`.
- `routing`: `wireguard` when the resolved stream host starts with `10.10.`,
  `lan` otherwise, `unknown` when there is no host.
- `latency_ms`: TCP connect RTT in milliseconds, as a string, to the resolved
  stream host on port 47990. `"?"` when unknown or the probe timed out (5 s cap).
- `wifi_ssid`: current Wi-Fi SSID.
- `local_ip`: the device's local IP address.
- `moonlight_client_id`: the Moonlight unique client id. May be null.

No response body is consumed. Only the status code matters.

## 5. Experience catalog

### GET /api/v1/heads/{head_id}/experiences

Fetched on every tick, in the background. Response is a JSON array:

```json
[
  {
    "name": "rupelmonde-victory",
    "label": "Victory at Rupelmonde",
    "orientation": "landscape",
    "enable_microphone": true
  }
]
```

- `name`: unique key. Also the Sunshine app name to launch.
- `label`: display title for the grid.
- `orientation`: `portrait` or `landscape`. Optional. Drives stream resolution:
  portrait streams 1080x1920, everything else streams 1920x1080.
- `enable_microphone`: optional boolean. When true, start the mic relay after
  the stream starts (section 9).

## 6. Body discovery and selection

### GET /api/v1/bodies/eligible

Query parameters, all required:

- `district`: from head config
- `venue`: from head config
- `head_id`: this head's id
- `experience`: the experience `name`

Response is a JSON array of bodies:

```json
[
  {
    "id": "node-abc",
    "name": "cosmic-pretzel-98",
    "ip": "192.168.1.20",
    "wireguard_ip": "10.10.0.7",
    "same_venue": true,
    "stream_count": 0
  }
]
```

All fields are optional in the decoder. Treat a missing `stream_count` as 0.

### Selection rule

Sunshine is single-stream. Pick the FIRST body in the array whose
`stream_count == 0`. When no body qualifies, fail with "No body available for
this experience" and enter the error state.

### Host selection rule

Build the candidate list in this order:

1. `ip`, only when it is RFC1918 (`10.*`, `192.168.*`, `172.16.*` through `172.31.*`)
2. `wireguard_ip`, when non-empty
3. `ip` again, when not already in the list (covers non-RFC1918 LAN addresses)

Then probe each candidate in order with a TCP connect to port 47990 and a 1 s
timeout per host. Use the first host that answers. Reason: an RFC1918 body address
is only reachable inside its venue LAN. An off-site head must fall through to the
WireGuard address. The address shape alone cannot prove reachability. Only a
connection can.

When no candidate answers, fall back to the first candidate anyway, so the caller
has an address to report and fail on. When the candidate list is empty, error with
"Body has no reachable IP".

## 7. Sunshine pairing

Always pair fresh before every stream. Never cache the server certificate across
sessions. Sunshine can rotate its certificate on restart, and a stale cached
certificate causes silent stream failures that need re-enrollment to fix.

Sequence, as implemented in `HydraPairSession.m`:

1. Ensure a client key pair and certificate exist (generate once, reuse).
2. Run the standard Moonlight GameStream pairing against `http://<host>:47989`
   (HTTP). Let the pairing code discover the HTTPS port (47984) from the
   `/serverinfo` response. Do NOT pass 47990 as the HTTPS port. That is Sunshine's
   web UI, and pairing never completes against it.
3. When the pairing library asks for the PIN, return from the callback
   immediately, then after a 0.3 s delay POST the PIN to Sunshine's web API:
   - `POST https://<host>:47990/api/pin`
   - `Authorization: Basic base64(<sunshine_username>:<sunshine_password>)`
   - `Content-Type: application/json`
   - Body: `{"pin":"<PIN>"}`
   - Accept Sunshine's self-signed TLS certificate for this request only.
   - Request timeout 20 s.
   - Timing matters: Sunshine only accepts the PIN while a
     `/pair?...phrase=getservercert` request is pending. Submitting the PIN
     before that request is in flight makes Sunshine ignore it and the pairing
     hangs. Hence: return first, POST the PIN slightly later.
4. On success, keep the returned server certificate in memory and pass it to the
   stream session.
5. On `alreadyPaired`: the response carries no server certificate. Unpair, then
   pair again:
   - `GET http://<host>:47989/unpair?uniqueid=<moonlight unique id>`
   - The unique id must be the SAME id the Moonlight HTTP layer uses for pairing
     (the iOS library hardcodes `0123456789ABCDEF`). A different id makes Sunshine
     ignore the unpair request.
   - Re-pair regardless of the unpair result. Guard with a flag so unpair is
     attempted at most once. On a second `alreadyPaired`, return an empty
     certificate and let the caller proceed.

Credentials come from head config `sunshine_username` / `sunshine_password`, with
`sunshine` / `sunshine` as fallback for both.

## 8. Stream lifecycle

### Start parameters

Moonlight GameStream session against the selected body host:

- App name: the experience `name` (equals `stream_app_id`).
- Resolution: 1920x1080 landscape, 1080x1920 when the experience `orientation`
  is `portrait`. Default 1920x1080 when the experience is unknown.
- Frame rate: 60.
- Bitrate: 150000 kbps when the host is a venue LAN address, 20000 kbps when the
  host starts with `10.10.` (WireGuard mesh). The hub relays every tunnelled byte,
  so mesh streams must not ask for the LAN rate.
- Server certificate: the one from the pairing step, when present.

Note: issue #544 mentions 25000 kbps for WireGuard. The shipped iPad code uses
20000. Follow the code value until the cluster says otherwise.

### While streaming

- Heartbeat interval drops to 5 s.
- On each tick, compare the current head config `stream` assignment with the
  live stream. When the resolved host or `stream_app_id` changed, stop the
  stream and start the new assignment. Never re-pair during a stable stream.
- A self-service stream (started from the grid, no server assignment) is left
  alone by the tick. It ends only on user exit or on error.

### Stop

#### DELETE /api/v1/heads/{head_id}/stream

- `Content-Type: application/json`
- Request body:

```json
{ "body_id": "node-abc" }
```

Call this on every stream end: user exit, unexpected disconnect, and error paths.
Await the response on user-initiated stop before showing the grid again. This
prevents a race where an immediate next discovery still sees the body as busy.
A failure here is non-fatal. The body heartbeat self-corrects.

After stop: mic relay off, heartbeat interval back to 30 s.

## 9. Commands and screenshot

### GET /api/v1/heads/{head_id}/commands

- Timing: poll every 3 s, always, independent of the tick.
- Response is a JSON object of booleans:

```json
{ "screenshot": true }
```

When `screenshot` is true, capture the current screen, encode as JPEG
(quality 0.7 on iPad), and upload.

### POST /api/v1/heads/{head_id}/screenshot

- `Content-Type: image/jpeg`
- Body: raw JPEG bytes. No JSON wrapper, no multipart.

Quest note: rendering surfaces may capture black, like the Metal layer on iPad.
MediaProjection may be needed. The wire contract stays the same.

## 10. Head state machine

States, ported from `AppState.swift` `HeadState`:

| State | Payload | Heartbeat `status` |
|---|---|---|
| `unconfigured` | none | `idle` |
| `idle` | none | `idle` |
| `selfService` | experience list | `self-service` |
| `discovering` | experience name | `starting` |
| `pairing` | body name, host, experience name | `starting` |
| `streaming` | host, experience name, body id, server cert | `streaming` |
| `error` | message | `error` |

Transitions:

- `unconfigured` -> `selfService([])`: enrollment succeeds (`configure`). Starts
  the 30 s tick timer and the 3 s commands timer.
- `unconfigured` -> `selfService([])`: explicit skip for demo mode
  (`skipEnrollment`, no cluster).
- any -> `unconfigured`: enrollment reset. Clears stored config, stops timers.
- `selfService` / `idle` -> `discovering(experience)`: user taps an experience,
  or the tick finds an active server assignment.
- `discovering` -> `error(msg)`: no eligible body, or no reachable host.
- `discovering` -> `pairing(bodyName, host, experience)`: body selected and host
  probe done.
- `pairing` -> `error(msg)`: pairing failed.
- `pairing` -> `streaming(host, experience, bodyID, serverCert)`: pairing done.
  Restart heartbeat at 5 s. Start mic relay when the experience has
  `enable_microphone == true`.
- `streaming` -> `discovering(...)`: tick sees a changed server assignment
  (different host or app id). Stop first, then start the new assignment.
- `streaming` -> `selfService(experiences)`: user stops. Send the stream DELETE
  and await it. Heartbeat back to 30 s.
- `streaming` -> `error("Session interrupted...")`: Moonlight terminated the
  session without user action. Send the stream DELETE (fire and forget).
  Heartbeat back to 30 s.
- any -> `error(msg)`: stream error callback. Mic relay off.
- `error` -> `selfService(experiences)`: user dismisses the error.
- Tick in `discovering` or `pairing`: do nothing, let the in-progress flow finish.
- Tick in `error` or `unconfigured`: do nothing.
- Tick in `idle` / `selfService` with no active assignment: stay in
  `selfService(experiences)` with the latest catalog.

Tick behavior common to all states: fetch head config (fall back to the cached
copy on failure), refresh the catalog in the background, then send the heartbeat.

## 11. Microphone relay

Per-experience. Runs only while streaming an experience with
`enable_microphone == true`. Stop it on every stream end and error.

Protocol, matching the flatscreen and iPad heads:

- Transport: UDP to `<body_host>:47995`.
- Payload: RTP packets carrying Opus.
- RTP header: version 2, no padding, no extension, no CSRC, marker 0,
  payload type 111 (Opus dynamic), 16-bit sequence number starting at 0,
  32-bit timestamp advancing by 960 per packet, random 32-bit SSRC per session.
- Audio: capture mono, resample to 48000 Hz when the hardware differs, encode
  Opus VOIP mode, one frame per packet, 960 samples per frame (20 ms).
- Noise gate: RMS threshold from a user sensitivity setting in `[0, 1]`
  (default 0.75). Threshold formula: `0.05 - 0.049 * sensitivity`. Frames below
  the gate are not sent.
- Fire and forget. No acknowledgements, no retransmission.

## 12. WireGuard config (self-service tunnel)

### GET /api/v1/heads/{head_id}/wireguard-config

- Auth: this head's own bearer token.
- Response: `text/plain`, a wg-quick config. It contains this head's PRIVATE KEY.
  Never log the body. Store it once and reuse it. Refetch only on explicit
  operator action.
- Error mapping:
  - 401 or 403: the cluster has not shipped self-scoped auth yet
    (hydracluster #449/#456).
  - 404: no tunnel provisioned for this head.
  - Empty body: treat as not provisioned.

Phase 1 of hydraheadquest may ship without WireGuard (venue LAN plus hydraneck
routing). The endpoint contract is listed here for the VpnService phase.

## 13. Timing summary

| What | Interval |
|---|---|
| Heartbeat + config tick, idle | 30 s |
| Heartbeat + config tick, streaming | 5 s |
| Command poll | 3 s |
| HTTP request timeout | 15 s |
| Latency probe cap | 5 s |
| Reachability probe per host | 1 s |
| PIN POST delay after pairing callback | 0.3 s |
| PIN POST timeout | 20 s |
