# Grmemby Room Server

Minimal public watch-party backend for Grmemby.

## Endpoints

- `GET /healthz`
- `GET /rooms`
- `POST /rooms` body: `{ "name": "Movie night", "hostName": "Host" }`
- `GET /rooms/{roomId}`
- `POST /rooms/{roomId}/join` body: `{ "name": "Guest" }`
- `POST /rooms/{roomId}/leave?memberId=...`
- `DELETE /rooms/{roomId}?memberId=...` host-only disband
- `POST /rooms/{roomId}/select-media` body: `{ "memberId": "...", "itemId": "...", "title": "..." }`
- `POST /rooms/{roomId}/playback` body: `{ "memberId": "...", "event": "seek", "positionMs": 42000, "isPlaying": true }`
- `WS /ws/rooms/{roomId}?memberId=...` sends/receives playback JSON updates.

## Local build

```bash
export JAVA_HOME=$PREFIX/lib/jvm/java-17-openjdk
$JAVA_HOME/bin/java -Xmx2g -Dorg.gradle.appname=gradle \
  -classpath $PREFIX/opt/gradle/lib/gradle-launcher-9.4.1.jar \
  org.gradle.launcher.GradleMain :room-server:test :room-server:fatJar --no-daemon
```

## Local run

```bash
PORT=8080 java -jar room-server/build/libs/grmemby-room-server-all.jar
```

## Deploy

Requires SSH access to the server.

```bash
GRMEMBY_DEPLOY_HOST=<user>@<host> room-server/deploy/deploy.sh --dry-run
GRMEMBY_DEPLOY_HOST=<user>@<host> room-server/deploy/deploy.sh
```

Configuration:

- `GRMEMBY_DEPLOY_HOST`: required SSH target, for example `<user>@<host>`
- `GRMEMBY_DEPLOY_DIR`: optional target directory, defaults to `/opt/grmemby-room-server`
- service: `grmemby-room-server`
