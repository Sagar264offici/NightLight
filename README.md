<div align="center">

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:05070B,35:0B1320,70:111827,100:020305&height=220&section=header&text=NIGHTLIGHT&fontSize=58&fontColor=FFFFFF&fontAlignY=36&desc=FEEL%20IT&descSize=24&descAlignY=58&animation=fadeIn" width="100%"/>

<br/>

<img src="https://readme-typing-svg.demolab.com?font=JetBrains+Mono&weight=700&size=20&duration=2600&pause=700&color=7DD3FC&center=true&vCenter=true&width=850&lines=Not+another+music+player.;Relevance+%C3%97+Variety+%C3%97+Context.;Built+for+the+way+people+actually+listen.;Java+%7C+Media3+%7C+Hono+%7C+MongoDB" />

<br/><br/>

<a href="https://nightlight-music.vercel.app/">
<img src="https://img.shields.io/badge/🌐%20LANDING%20PAGE-ENTER%20NIGHTLIGHT-0B1220?style=for-the-badge&labelColor=05070B&color=1D4ED8"/>
</a>

<a href="https://github.com/Sagar264offici/NightLight">
<img src="https://img.shields.io/badge/💻%20SOURCE-CODE-0B1220?style=for-the-badge&labelColor=05070B&color=334155"/>
</a>

<br/><br/>

> **Music should feel connected — not repetitive.**

</div>

---

# 🌙 What is NightLight?

NightLight is a production-grade **Android music player** built around a simple idea:

```text
GOOD RECOMMENDATIONS
          ≠
   SAME ARTIST FOREVER

---

## 🏗 Architecture

```
ANDROID JAVA APP (Media3 + Room)
        │
        ├── HTTPS ──► Render / Hono Backend ──► MongoDB
        │                 │
        │                 └── music search, lyrics, playlist import,
        │                     users/playlists/likes/history, auth exchange
        └── Firebase Authentication (email + Google + guest)
            (Listen Together uses backend session APIs)
```

- **Android** — UI, Media3 playback, Room persistence, Firebase Auth (REST Identity Toolkit)
- **Backend** — Hono on Render: search, lyrics, playlist import, sessions, durable user data
- **MongoDB** — users, playlists, liked songs, history, preferences, sessions, analytics
- **Firebase** — authentication only; all durable data lives in MongoDB

## 🚀 Build

```bash
# Android debug (API via -P, defaults to emulator 10.0.2.2:8787)
cd android
./gradlew assembleDebug -PnightlightApiUrl=https://your-backend.example/api/

# Android release (uses android/nightlight-release.keystore, gitignored)
#   override passwords with -PNIGHTLIGHT_STORE_PASSWORD / -PNIGHTLIGHT_KEY_PASSWORD
./gradlew assembleRelease \
  -PnightlightApiUrl=https://your-backend.example/api/ \
  -PnightlightFirebaseKey=<FIREBASE_WEB_API_KEY> \
  -PnightlightGoogleClientId=<GOOGLE_OAUTH_WEB_CLIENT_ID>

# Backend
cd jiosaavn-api-0.1.0
npm install && npm run build && npm test
```

## 🔑 Environment variables (backend — `jiosaavn-api-0.1.0/.env`)

| Variable | Purpose |
|---|---|
| `MONGODB_URI` | MongoDB Atlas connection string (secret) |
| `MONGODB_DB_NAME` | Database name (default `nightlight`) |
| `RAPIDAPI_KEY` | RapidAPI key for the lyrics provider (secret) |
| `PORT` | HTTP port |

Copy `.env.example` to `.env` and fill in real values. **Never commit `.env`.**

## 🤝 Listen Together

Host starts a session → backend returns a 6-letter code → share
`nightlight://listen/CODE` or the web bridge `https://<backend-host>/l/CODE`.
Guests join, and playback state (track, position, playing) syncs every few
seconds while chat messages poll incrementally. Sessions expire after a few
hours of inactivity.

## 📱 Deep links

- `nightlight://listen/CODE` — always works
- `https://<backend-host>/l/CODE` — web bridge page redirects into the app
- App Links verification requires `https://<backend-host>/.well-known/assetlinks.json`
  to return the signing certificate fingerprint (served by this backend).

## ⚠ Known limitations

- Search results depend on the upstream music catalog; some Western originals
  may be unavailable in certain regions and only cover versions exist.
- HTTPS App Links verification requires the backend domain to serve
  `assetlinks.json` (the `nightlight.app` domain is currently parked).
- Firebase Realtime Database is not used for session sync; Listen Together
  polls the backend session APIs instead.

## 📄 License

See [LICENSE](LICENSE).
