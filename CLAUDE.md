# hydraheadquest

Hydra head app for Meta Quest. It enrolls a headset in hydracluster, shows the
experience catalog, and receives Moonlight streams from Sunshine bodies.

## Fork lineage

- Upstream: github.com/Gilleece/moonlight-android-xr (itself a fork of moonlight-android).
- This repo: github.com/cederikdotcom/hydraheadquest.
- Keep `upstream` as a git remote pointing at Gilleece/moonlight-android-xr.
  Sync by merging upstream master when we want their fixes. Keep the Hydra
  layer isolated so merges stay clean.
- Commit directly to master. No PR ceremony (our moonlight fork convention).

## The Hydra layer

- Lives in `app/src/main/java/com/limelight/hydra/` (Kotlin). Everything
  Hydra-specific goes there. Touch upstream Moonlight code as little as possible.
- The API contract is in `docs/hydra-api-contract.md`. It is authoritative.
  It was extracted from the shipped iPad app (`/home/claude-user/hydraheadipad`),
  which is the sibling reference implementation.
- Covers: QR enrollment, heartbeat, catalog, eligible-body selection, Sunshine
  pairing (PIN via `POST https://host:47990/api/pin`), stream start/stop,
  command poll, screenshot upload, mic relay, state machine.
- Head naming: `quest-head-<first 6 chars of ANDROID_ID>`. Names must be unique
  per device. Never use a shared name (the `ipad-head` collision, hydracluster #449).

## Identity

- `applicationId` is `com.experiencenet.hydraheadquest` (nonRoot flavor).
- The Java/Kotlin `namespace` stays `com.limelight`. Do not move upstream packages.

## Issues and plan

- Issue tracker: issues.experiencenet.com, project `hydraheadquest`.
  NEVER GitHub Issues.
- The full plan is issue #544. Phases:
  1. Flat streaming in VR from existing Windows Sunshine bodies. No body changes.
  2. Fleet deploy via Meta Horizon Managed Services (HMS, Shared Mode). CI
     publishes the APK to releases.experiencenet.com; HMS installs from that URL.
  3. Immersive 6DoF via ALVR (Windows) or WiVRn (Linux). Separate APK, deployed
     via HMS. hydraheadquest launches it by intent when an experience has
     `stream_mode: xr`. Do not merge an XR client into this APK.

## Build

- Java project (upstream Moonlight) plus a Kotlin Hydra layer. Uses the NDK
  (ndkBuild, `app/src/main/jni/Android.mk`) and git submodules:
  `git submodule update --init --recursive` before the first build.
- Flavor dimension `root` with flavors `root` and `nonRoot`. We ship `nonRoot`.
- Tasks: `./gradlew assembleNonRootDebug` and `./gradlew assembleNonRootRelease`.
  APKs land in `app/build/outputs/apk/nonRoot/<buildType>/`.
- Release APKs are unsigned. Sign with zipalign + apksigner (see README).
- There is no JDK or Android SDK on this workstation. Builds run in CI. Be
  rigorous about syntax; you cannot compile locally.

## Docs convention

- Operational procedures go in `docs/runbooks/`. Verification procedures go in
  `docs/testbooks/`. Memory entries point at these files; they never copy them.

## Style

- No em dashes in prose or code comments.
- Short clear sentences (ASD-STE100 spirit).
- Never log tokens, passwords, or WireGuard private keys.
