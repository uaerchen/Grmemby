# Grmemby Win64

Grmemby Windows client for Jellyfin/Emby-compatible servers.

## Features

- Server login with stable per-device Win64 identifier. Passwords, tokens, and signed stream URLs are not stored.
- Home sections: latest media, continue watching, next-up episodes.
- Media library and search for Movie / Series / Episode.
- Detail screen with overview, progress, favorite/played toggles, and series episode list.
- mpv playback using Jellyfin/Emby `PlaybackInfo`, direct-stream/transcode/fallback URL resolution, required media headers, playback start/progress/stop reporting.
- Watch Party: create/join/leave/disband room, stable memberId dedupe, select media, chat, heartbeat, polling sync, play/pause/seek broadcast and remote state application.

## Requirements

1. Windows 10/11 x64.
2. Java 17 or newer in PATH (`java -version`).
3. mpv for Windows in PATH, or set the full `mpv.exe` path in the app.

The installer creates Start Menu/Desktop shortcuts to `Grmemby.bat`.
