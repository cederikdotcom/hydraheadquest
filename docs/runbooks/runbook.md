# hydraheadquest Runbook

## Status: Phase 1 scaffold (issue #544 on issues.experiencenet.com)

## What this repo is

hydraheadquest is the Hydra head app for Meta Quest. It is a fork of
[Gilleece/moonlight-android-xr](https://github.com/Gilleece/moonlight-android-xr),
which is itself a moonlight-android fork for Quest and Pico. The upstream gives
us the Moonlight client, an immersive resizable virtual screen, passthrough,
and controller and hand-tracking input. We add the Hydra head layer on top:
QR enrollment, heartbeat, experience catalog, eligible-body selection, Sunshine
pairing, command poll, and remote screenshot. The API contract is in
`docs/hydra-api-contract.md`.

The app is a head, not a node. It is an outbound client only, like
hydraheadipad. It does not run hydranode and has no local listener. All remote
operations go through the head API on hydracluster.

Key facts:

- Language: Java (upstream) plus the Hydra layer. Namespace `com.limelight`.
- Sibling app: `hydraheadipad` (same role, iPadOS). Its state machine is the
  reference: `Sources/HydraHeadiPad/AppState.swift`.
- Git submodule: `app/src/main/jni/moonlight-core/moonlight-common-c`. Clone
  with `--recursive`, or run `git submodule update --init --recursive`.

## Upstream remote and sync

Our fork convention for moonlight forks applies: commit directly to `master`.
No PR ceremony, no feature branches for routine work.

One-time setup after a fresh clone:

```sh
git -C /home/claude-user/hydraheadquest remote add upstream https://github.com/Gilleece/moonlight-android-xr.git
```

To pull upstream changes:

```sh
git -C /home/claude-user/hydraheadquest fetch upstream
git -C /home/claude-user/hydraheadquest merge upstream/master
git -C /home/claude-user/hydraheadquest submodule update --init --recursive
git -C /home/claude-user/hydraheadquest push origin master
```

Resolve conflicts in favor of our Hydra layer. Keep the Hydra code isolated in
its own package so merges stay small. After any upstream merge, run a full
build before tagging.

## Local build prerequisites

Versions come from `app/build.gradle`. Check there first when a build fails on
a toolchain mismatch.

| Tool | Version |
|---|---|
| JDK | 17 (Gradle and AGP need it; source and target compatibility are Java 11) |
| Android SDK | compileSdk 34, minSdk 21, targetSdk 34 |
| Android NDK | 27.0.12077973 (`ndkVersion` in `app/build.gradle`) |

Build:

```sh
cd /home/claude-user/hydraheadquest
git submodule update --init --recursive
./gradlew assembleNonRootDebug      # sideload testing
./gradlew assembleNonRootRelease    # release lane
```

The upstream has two product flavors, `root` and `nonRoot`. We use `nonRoot`
only (application id `com.experiencenet.hydraheadquest`; the Java namespace
stays `com.limelight`). Ignore the `root` flavor.

APK output: `app/build/outputs/apk/nonRoot/<buildType>/`. The debug APK is
signed with the debug key and installs directly. The release APK is UNSIGNED
(`app-nonRoot-release-unsigned.apk`); `adb install` rejects it until it is
signed with zipalign plus apksigner (see the README signing section). CI signs
release APKs; the keystore secrets are configured (see Signing key below).

## Signing key

Set up 2026-08-21. The release signing key is a PKCS12 keystore, RSA 4096,
self-signed certificate valid to 2056, alias `hydraheadquest`.

- GitHub secrets on this repo: `ANDROID_KEYSTORE_BASE64`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
  (PKCS12: key password equals store password).
- Master copy: `~/.hydraheadquest/release.p12` plus
  `release-keystore-password.txt` on the workstation, mode 600.
- Backup: Hetzner Storage Box u645590 (SSH port 23, Hetzner internal),
  `hydramdm-backups/hydraheadquest-signing/`.
- Never lose or rotate this key casually. Android updates install only when
  the new APK is signed with the same key. A lost key means uninstall and
  reinstall on every headset.

## Sideload deploy for the pilot

This is the Phase 1 deploy lane. One headset, one cable or one venue LAN.

1. Enable developer mode on the Quest. The Meta account must be in a Meta
   developer organization. In the Meta Horizon phone app: select the headset,
   then Headset settings, then Developer Mode, then toggle it on. Reboot the
   headset.
2. Connect over USB. Put the headset on once to accept the "Allow USB
   debugging" prompt.
3. Install a signed APK. Pick one source:

   - Release lane (preferred): download `hydraheadquest-<version>.apk` from the
     GitHub release or the `hydraheadquest-apk` CI artifact. CI signs release
     APKs since v0.2.0 (secrets configured 2026-08-21).
   - Local debug build (quick iteration; application id gets a `.debug`
     suffix):

   ```sh
   adb install -r app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
   ```

   A locally built release APK is unsigned. Sign it first (README signing
   section) or `adb install` fails with `INSTALL_PARSE_FAILED_NO_CERTIFICATES`.

4. The app appears in the library under "Unknown Sources".

adb over the venue LAN (no cable after the first setup):

```sh
adb tcpip 5555            # once, while on USB
adb connect <quest-ip>:5555
adb install -r <apk>
```

The Quest's IP is in headset settings under Wi-Fi details, or in the venue
router's DHCP leases. The TCP adb mode survives until reboot. WDAC-style
restrictions do not apply here; this is our own APK on our own device.

## HMS deploy lane (NOT YET SET UP)

The fleet lane is Meta Horizon Managed Services (HMS). It is free and it is
the required path for managed Quests. Planned shape, not yet configured:

- Enroll headsets in HMS Shared Mode. No per-device Meta accounts.
- Use the HMS built-in Device Manager to deploy the APK as a private app from
  a URL. That URL is our own release server: `releases.experiencenet.com`.
- CI publishes the APK there on every release tag. HMS points at the URL and
  pushes the update to the fleet.
- HMS also blocks the public store and restricts visible apps.

Nothing MDM-side gets built by us. When this lane is live, the procedure moves
to a Quest runbook in `hydramdm`, next to the SimpleMDM iPad lane. Until then,
use the sideload lane above.

## Release flow

A `v*` tag triggers `.github/workflows/release.yml`. The workflow builds the
release APK and publishes it to `releases.experiencenet.com`. The HMS lane
will consume that URL later; the pilot sideloads the same APK.

Version tagging convention:

- Tags are `v<major>.<minor>.<patch>`, for example `v0.1.0`. The app starts in
  the `v0.1.x` series.
- The version in the tag is what the app reports in heartbeat
  `diagnostics.version` (as `v<x.y.z>`). Tag and app version must never drift.
- Always tag with an explicit repo path, because the shell cwd does not
  persist:

```sh
git -C /home/claude-user/hydraheadquest tag v0.1.0
git -C /home/claude-user/hydraheadquest push origin v0.1.0
```

Push the `moonlight-common-c` submodule commit before tagging the parent. CI
fetches the pinned SHA and fails if it is not on the remote.

## Enrollment against hydracluster

1. Open `https://hydracluster.experiencenet.com/enroll` (admin login). Show
   the fleet QR. One QR serves the whole fleet. The QR payload is JSON:
   `{"server_url": ..., "enrollment_token": ...}`.
2. Launch hydraheadquest on the headset. The QR scanner appears on first
   launch. Scan the fleet QR with the headset cameras.
3. The app POSTs to `/api/v1/heads` with the fleet enrollment token and
   persists the returned `{server_url, head_id, token}`. All later calls use
   the per-device token.
4. The head appears in HydraCluster. If it shows as pending, approve it in
   the admin UI. After approval the head starts normal operation: catalog,
   heartbeat every 30 s, command poll every 3 s.

### Head naming convention

The app enrolls as `quest-head-<first 6 chars of the device id>`. The name is
unique per headset from the first build. Never use a shared literal name such
as `ipad-head`; that collision broke HydraGuard peer allocation once
(hydracluster #449) and we do not repeat it.
