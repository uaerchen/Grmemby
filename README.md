# Grmemby

Grmemby is an open-source Android media client for Jellyfin/Emby-compatible servers, built with Kotlin and Jetpack Compose. It focuses on a fast phone UI, smooth playback, server management, and portable data import/export.

This project is a GPLv3 fork/rework based on JellyCine, with Grmemby branding and additional playback, server-management, S-code transfer, watch-party, and UI changes.

## Features

- Jellyfin/Emby-compatible server login and switching
- Phone and TV Android modules
- Jetpack Compose + Material 3 UI
- Media3 ExoPlayer playback with MPV/libmpv integration paths
- Server management, line configuration, and keep-alive tooling
- Data import/export by local backup file or S-code text
- Optional backup/restore of local login credentials when explicitly selected
- Search, detail pages, settings, cache controls, danmaku controls, and watch-party support
- EMOS/route-aware playback fixes and server icon matching

## Project Structure

- `phone` — main Android phone app module
- `tv` — Android TV module
- `data` — API, repositories, persistence, transfer/import-export logic
- `core` — player/preferences/shared logic
- `shared` — shared UI and image utilities
- `room-server` — watch-party room server module
- `win-player` — desktop/MPV helper module
- `docs` — documentation assets from the upstream project

## Requirements

- JDK 17
- Android Studio or Android SDK/Gradle environment
- Android SDK platform matching `compileSdk` in the Gradle files

## Build

Debug phone APK:

```bash
./gradlew :phone:assembleDebug
```

Optimized phone APK:

```bash
./gradlew :phone:assembleOptimized
```

Release signing can be configured with `keystore.properties` or these environment variables:

- `GRMEMBY_STORE_FILE`
- `GRMEMBY_STORE_PASSWORD`
- `GRMEMBY_KEY_ALIAS`
- `GRMEMBY_KEY_PASSWORD`

`keystore.properties` and keystore files are intentionally ignored by git.

### Termux/aarch64 note

On Termux/aarch64, Android Gradle Plugin may need a native `aapt2` override. Keep this machine-specific setting out of source control and pass it locally, for example:

```bash
./gradlew :phone:assembleOptimized \
  -Pandroid.aapt2FromMavenOverride=<path-to-aapt2>
```

## Privacy and Security

- Local backup/S-code export only includes credentials when the user explicitly selects the account/password option.
- Backups that do not include credentials require re-authentication after import.
- Do not commit real server URLs, tokens, passwords, API keys, backup JSON, or S-code samples.

See [PRIVACY](PRIVACY) for the privacy policy.

## License

GPLv3. See [LICENSE](LICENSE).
