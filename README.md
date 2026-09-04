# NightLight — FEEL IT

A production-grade Android music player with a real backend and MongoDB. Dark,
cinematic, performance-first — built in **Java** on **Media3/ExoPlayer** with a
**Hono (TypeScript)** API proxying a music provider and storing user data in
**MongoDB**.

> The recommendation engine optimizes **RELEVANCE × VARIETY × CONTEXT** — not
> RELEVANCE × SAME ARTIST. The listener should feel "these songs belong
> together" while also discovering different artists.

---

## Features

### Smart Shuffle (default)
Context-aware queue curation that replaces plain random shuffle:

- Mood matching with **mood blending** (LOVE warms to Romantic/Emotional/
  Acoustic/R&B; CHILL to Lo-fi/Ambient/Soft Pop; ENERGY to Dance/Pop/Workout…)
- **Weighted random selection** — the best-scoring track does *not* always win
- **Artist diversity** — same artist never dominates; artist/album cooldowns
  built into queue generation
- **Track repetition control** — recently played songs fade out
- Weak session signals: skipping an artist lowers their ranking, liking a
  track adds a small familiarity bonus
- Discovery preference: More familiar / Balanced / More discovery
- Modes: **Smart (default)** · Normal (plain random) · Off

### Contextual Home
- "What are you feeling?" mood selector (Love, Sad, Chill, Happy, Energy,
  Workout, Party, Focus)
- **For you right now** — dynamic section from local time, weather (server-side
  Open-Meteo proxy, no keys in the APK) and your mood
- **Trending now** — real chart data from the music provider (top chart songs +
  trending albums); never fabricated
- Time-of-day greeting and soft discovery signals
- Graceful fallback: no weather/trending → Home still works with time, mood,
  recents and likes

### Playback
- Media3/ExoPlayer with a proper playback service — background playback,
  MediaSession, notification controls, audio focus
- Queue, normal shuffle, repeat (off/all/one), seek, next/previous
- Auto-continue: when the queue ends, related songs of the same vibe keep
  playing (off in Low power mode)
- Lyrics (optional, synchronized where the provider supplies timestamps —
  karaoke-style highlight follows actual playback position)
- Listen together — share a session code; friends join via
  `nightlight://listen/CODE` deep link and stay in sync

### Library
- Likes, recently played, search history and playlists backed by Room locally
  and MongoDB server-side (bidirectional sync)
- Playlist import from **Spotify, YouTube, Apple Music and JioSaavn** URLs
- Power / experience modes: Low (ambient) · Balanced (default) · High

---

## Architecture

```
Android (Java + Media3)
        │  Retrofit / OkHttp
        ▼
Backend API (Hono · TypeScript)   ──►  Music provider (JioSaavn)
        │
        └── MongoDB (likes, playlists, history, listen-together sessions)
```

- The Android app **never talks to MongoDB directly**; all credentials stay
  server-side.
- Weather is proxied through the backend (Open-Meteo) so no weather keys ship
  in the APK.
- Client flow: `API JSON → DTO → Mapper → Domain model → Repository →
  ViewModel → UI`.

### Backend modules
`search` (songs/radio/trending) · `songs` · `albums` · `artists` · `playlists`
· `lyrics` (LRCLIB proxy) · `importer` (Spotify/YouTube/Apple Music/JioSaavn)
· `auth` · `userdata` (likes/history/recents) · `sessions` (listen together)
· `context` (weather)

---

## Tech Stack

| Layer    | Tech |
|----------|------|
| Android  | Java 17, Media3 (ExoPlayer), Room, Retrofit/OkHttp, Glide, AndroidX, Material Components |
| Backend  | Hono, TypeScript, Zod (OpenAPI), MongoDB driver |
| Database | MongoDB (Atlas) |
| Testing  | JUnit 4 (unit), Android test tooling, ADB device verification |

---

## Setup

### Backend

```bash
cd jiosaavn-api-0.1.0
cp .env.example .env   # set MONGODB_URI, MONGODB_DB_NAME, PORT
npm install
npm run build
node dist/local.js     # listens on :8787 by default
```

### Android

```bash
cd android
./gradlew assembleDebug
```

By default the debug build points at `http://10.0.2.2:8787/api/` (emulator).
For a physical device over ADB:

```bash
adb reverse tcp:8787 tcp:8787
./gradlew assembleDebug -PnightlightApiUrl=http://127.0.0.1:8787/api/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Development & Verification

```bash
./gradlew clean assembleDebug test lint
```

The project is verified on physical Android devices over ADB:

- Install → launch → smoke test (search, play, pause, seek, next/previous,
  shuffle modes, mood selection, lyrics, background playback)
- Logcat scan for `FATAL EXCEPTION` / `ANR` before each release
- Smart Shuffle is verified over 10–20 consecutive tracks: different artists,
  consistent vibe, no repetition, no queue stalls

---

## Security

- MongoDB credentials live only in the backend `.env` (gitignored) — never in
  the APK or repository
- No weather/provider API keys ship in the APK
- Backend verifies ownership of user data; clients authenticate with
  device-scoped bearer tokens
- Public GitHub repo prepared locally; publishing is done only on explicit
  instruction

---

## Known Limitations

- **Apple Music / YouTube playlist import is best-effort**: those providers
  serve consent shells or stripped data on some networks; Spotify and
  JioSaavn imports are verified working
- Weather uses IP-based geolocation when the app doesn't ask for location
  permission — the city shown may be approximate
- Listen-together sync runs while the app process is alive; sessions expire
  after 3 hours of inactivity
- Some devices filter third-party `INFO` logs, so diagnostics may appear at
  warning/error level

---

## Roadmap

- ML-assisted recommendations behind the existing scoring layer (the current
  engine is deliberately metadata-only: fast, cheap, deterministic,
  privacy-friendly)
- More regional charts and language filters on Home
- Offline download queue

---

## Developer

**Sagar Pathak**

- LinkedIn: <https://www.linkedin.com/in/sagarakanoone/>
- GitHub: <https://github.com/Sagar264offici/>
- Portfolio: <https://sagar-horizon.vercel.app/>
- Email: <pathaksagar264@gmail.com>

---

## License

MIT — see [LICENSE](LICENSE).