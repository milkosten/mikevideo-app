# mikevideo-app — CLAUDE.md

## What this repo is
**MikeVideo** — the Android **auto-sync client** for MikeOS's self-hosted YouTube. It automatically
uploads the phone's camera videos to the server (memory-safe, resumable, Wi-Fi-only) and lets Mike
browse + watch them.

- **Package:** `com.mikeos.video`. **Repo:** `milkosten/mikevideo-app`.
- **Backends (live):** control plane `https://video.osmike.com` (X-API-KEY = the app's hive agent
  key) · data plane `https://up.osmike.com` (Bearer the ticket). Both self-hosted on the Hetzner box.
- **Canonical architecture:** `mikeos-architecture/docs/services/video.md` (§5 = this app).
- **This is a standard MikeAgent app** — read `/home/mikeos/projects/android_mikeos/CLAUDE.md` for
  the app-agent contract (§0 self-register, heartbeat, Agent Inspector, vendored `com.mikeos.core`,
  DoH, build/OTA). Everything there applies; below is what's MikeVideo-specific.

## The auto-sync flow (deterministic on the heartbeat — NOT LLM-gated)
Each beat, a **`SyncManager`** (throttled, ~3 videos/tick, Wi-Fi-only + battery-aware, bootstrap-safe)
runs — do not make this a skill the brain picks. Per not-yet-synced camera video it mirrors the
proven reference `mikevideo-cloud/scripts/e2e_test.py`:
1. `POST video.osmike.com/api/videos` (`X-API-KEY`, body `{filename, content_type, total_size(int),
   file_hash:"sha256:…", chunk_size, count, taken_at(ISO-8601)}`) → `{video_id, upload_id, ingest_url,
   ticket, chunk_size}`. Never-trust-200: null unless all fields present.
2. Build the **manifest** in a **streamed pre-pass** — seek to each chunk, read ONE chunk into a
   **reused buffer**, `MessageDigest` **MD5** per chunk + running **SHA-256** whole. `chunk_hash_algo:"md5"`.
3. `POST {ingest_url}/ingest/init` (Bearer ticket) → `{missing}`; then `GET …/status` for the
   authoritative missing set (resume), then `PUT {ingest_url}/ingest/{upload_id}/chunk/{i}` (Bearer,
   raw body) for each missing chunk — auto-finalizes on the last.
4. Mark `synced` in the Room ledger **only after** `/status` reports `complete` (never-trust-200).

Source: `sync/{CameraVideos,VideoUploader,SyncManager,SyncLedger}.kt`,
`net/{VideoCloudClient,IngestClient}.kt`, `agent/VideoMikeAgent.kt`, `VideoViewModel.kt`.

## THE memory rule (non-negotiable — this is the whole point)
**Never `readBytes()` / load a whole video into RAM.** Seek + read exactly one chunk (≤ `chunk_size`)
into a single reused buffer, on the manifest pre-pass AND the upload. Verified: app PSS stays
~190–215 MB even with a **1.55 GB** clip queued (the exact file that once caused the reboot loop) —
no OOM, no reboot. If you touch the uploader, preserve this.

## Ledger & gating
- Room `SyncLedger` keyed on **`(mediastore_id + size + date_modified)`** → an edited file re-syncs,
  an unchanged one never re-uploads; interrupted uploads resume via `/status`.
- Default **Wi-Fi-only + battery-aware** (`ConnectivityManager` unmetered; skip on low battery).
- Permissions: `READ_MEDIA_VIDEO` (Android 13+) / `READ_EXTERNAL_STORAGE`.

## UI & playback
- **Library** grid (`GET /api/videos`) — thumbnails via **Coil + DoH**, status chip
  (uploading/encoding/ready), newest first.
- **Watch** — **Media3/ExoPlayer HLS** (`media3-exoplayer` + `-exoplayer-hls` + `-ui`), master url
  from `GET /api/videos/{id}`. **Media needs NO auth header** (server gates on the unguessable
  `video_id`), so the player/Coil load urls directly — don't add X-API-KEY to media requests.
- Sync settings toggles + the **mandatory Agent Inspector** icon.
- Agent skills: `list_videos`, `sync_status`, `upload_now`. Persona = "keep Mike's memories safe & browsable".

## Build / ship (standard MikeOS app flow — always OTA)
```bash
cd /home/mikeos/projects/mikevideo-app
# (after any shared-core change: re-vendor canonical core first)
# rm -rf app/src/main/java/com/mikeos/core && cp -r ../mikeos-android-core/core/src/main/java/com/mikeos/core app/src/main/java/com/mikeos/
./gradlew assembleDebug --no-daemon --max-workers=2
set -a; source ~/.mikeos/provider-keys.env; set +a
/home/mikeos/projects/mikeos-appstore/publish.sh com.mikeos.video <versionCode> <versionName> \
  app/build/outputs/apk/debug/app-debug.apk "<notes>" stable      # OTA (daemon Updater installs it)
adb -s R58N4101P2V install -r app/build/outputs/apk/debug/app-debug.apk   # only if phone on adb
```
**Bump `versionCode` every rebuild.** Prefer OTA — the phone drops off USB constantly. Cloud base
`https://video.osmike.com` lives in `net/VideoCloudClient.kt` / BuildConfig; ingest URL comes from the
ticket (`ingest_url`), don't hardcode it.

## House rules
Memory rule above · ISO-8601 timestamps to the cloud (never epoch-ms) · never-trust-200 · DoH on every
non-loopback OkHttp client · leave `com/mikeos/core/` untouched (vendored shared runtime) · no paid
services.
