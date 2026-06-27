# CameraLink

CameraLink turns any Android 12+ phone into an HTTP-based IP camera for use on your **local network**. The app keeps streaming even with the screen off and exposes a browser-friendly MJPEG feed. An **optional, off-by-default** Tailscale keep-alive service can ping your own peers if you choose to enable it. CameraLink contains no analytics or telemetry and never contacts any server outside your network unless you explicitly add Tailscale peers.

## Feature Highlights
- Live MJPEG streaming over HTTP, viewable from any modern browser or VLC
- Foreground camera service with wake lock for reliable screen-off streaming
- Built-in HTTP endpoints for `/`, `/stream`, `/snapshot`, `/config`, and `/test`
- Configurable lens, resolution, and JPEG quality via the in-app UI or URL query overrides
- In-app live preview to frame the shot and compare lenses before/while streaming
- Optional auto-flash that turns on the torch in low light (disabled by default), with an adjustable darkness threshold and (where the device supports it) flash brightness
- Optional access key to gate the HTTP server on shared networks (disabled by default)
- **Optional** Tailscale keep-alive (disabled by default, no preconfigured peers)
- Persistent notifications for both streaming and pinging with quick controls
- Snapshot capture, multi-viewer support, and MagicDNS hostname resolution
- No telemetry, analytics, or third-party network calls

## Architecture Overview
- **CameraStreamingService**: Foreground service (type `camera`) that owns CameraX capture, wake locks, and lifecycle.
- **StreamingServer**: Embedded NanoHTTPD server that serves frames as MJPEG or single JPEG snapshots.
- **TailscalePingService + TailscalePinger**: Optional foreground service (type `specialUse`, off by default) that resolves user-added peers and issues ICMP pings, updating status in notifications/UI.
- **MainActivity**: Compose UI for starting/stopping services, showing stream URLs, peer status, and editing peer lists.

The app targets Android API level 31+ (compiles against the latest SDK) and is written entirely in Kotlin with Jetpack Compose and CameraX.

## Getting Started

### Prerequisites
- Android Studio Ladybug or newer
- Android SDK 31 or higher installed locally
- A physical Android device running Android 12+ with USB debugging enabled
- Java 17+ if building from the command line

### Clone and Build
```bash
git clone https://github.com/yourusername/camera-link.git
cd camera-link
./gradlew assembleDebug
```

### Install
- **Android Studio**: Open the project, sync Gradle, connect a device, and press Run to deploy the debug build.
- **Command line**:
  ```bash
  ./gradlew installDebug
  # or manually install the generated APK
  adb install app/build/outputs/apk/debug/app-debug.apk
  ```

## Usage

### Start Streaming
1. Launch CameraLink on the device.
2. Grant camera, notification, and foreground service permissions when prompted.
3. Tap **Start Streaming**. The UI and notification display the local stream URL (default `http://<device-ip>:8080`).
4. Open the URL from any device on the same network to view the live feed or trigger `/snapshot`.
5. Stop streaming from the in-app button or the notification action.

Screen-off and background streaming remain active as long as the foreground service runs. Disable battery optimizations for best reliability.

### Camera Settings & URL Overrides
CameraLink lets you pick the lens, stream resolution, and JPEG quality both from the in-app **Camera Settings** card and via query parameters on any stream request.

In-app (persisted across launches):
- **Lens**: ultra-wide / wide / telephoto / front (only lenses the device actually exposes are shown). Auxiliary mono/infrared/depth sensors are filtered out automatically.
- **Resolution**: 480p, 720p (default), or 1080p. The camera snaps to the closest supported sensor size, so a 4:3 sensor may report e.g. 1280×960 for "720p".
- **JPEG quality**: 1–100 (default 80).
- **Auto flash in low light** (default **off**): when enabled, the streaming service turns the camera torch on automatically while the scene is too dark and off again once it brightens. Uses hysteresis and throttling to avoid flickering, and only acts on a back lens that has a flash unit (it never engages on the front camera or a lens without a torch).
  - **Low-light threshold** (default 40, range 5–150): how dark the scene must get before the torch engages. Raise it if the torch stays off in dim light, lower it if it comes on too eagerly. Tune for your camera's position/framing.
  - **Flash brightness** (1–100%, default 100): shown only on devices/lenses whose camera reports adjustable torch strength (requires CameraX 1.5+ and HAL support). Many phones — including the OnePlus 8T — only support on/off torch, in which case the slider is hidden.
- **Access key** (default empty = disabled): when set, every HTTP request from another device on the network must supply the key via `?key=<key>` or an `X-Access-Key` header; unauthorized requests get `401`. The in-app preview and the phone itself (loopback `127.0.0.1`/`localhost`) are always allowed. This is a lightweight LAN gate sent in cleartext over HTTP — it is **not** transport encryption.

URL overrides (applied globally to the shared stream; take effect on the next frame, with the camera rebinding for lens/resolution changes):

| Param | Values | Example |
|-------|--------|---------|
| `camera` | `ultrawide`, `wide`, `telephoto`, `front` | `?camera=ultrawide` |
| `res` | `480`, `720`, `1080` | `?res=1080` |
| `q` | `1`–`100` | `?q=70` |
| `autoflash` | `1`/`0`, `on`/`off`, `true`/`false` | `?autoflash=1` |
| `flashthreshold` | `5`–`150` | `?flashthreshold=70` |
| `flashlevel` | `1`–`100` (ignored if the device can't adjust torch strength) | `?flashlevel=50` |

> The access key is **not** a URL override — it can only be set in the app. Authorized requests still pass it as `?key=<key>` (or the `X-Access-Key` header).

Combine them on `/stream` or `/snapshot`, e.g. `http://<device-ip>:8080/snapshot?camera=telephoto&res=1080&q=70`. Query the current settings and available lenses/resolutions via `GET /config` (returns JSON, including `autoFlash`, `autoFlashThreshold`, `flashStrength`, `flashStrengthAdjustable`, `maxTorchLevel`, and `accessKeyRequired`).

### In-App Live Preview
The **Camera Settings** card has a **Show Preview** toggle that displays the live camera feed directly in the app. It renders the same local MJPEG stream (`http://127.0.0.1:8080/stream`) in an embedded WebView, so it stays in sync with the lens/resolution/quality you select and never opens a second camera session. The preview traffic is loopback-only (cleartext is permitted solely for `127.0.0.1`/`localhost` via `network_security_config.xml`); nothing leaves the device.

### Tailscale Keep-Alive (optional)
CameraLink does **not** require Tailscale and ships with the feature disabled and no preconfigured peers.

1. In the **Tailscale Connections** card, toggle **Enable keep-alive** on.
2. Use the **Manage Tailscale Peers** section to add your own peers (MagicDNS names or 100.64.0.0/10 addresses). Peers are stored locally and persist across launches.
3. While enabled, a foreground service pings your configured peers every 15 seconds and shows successful/failed counts in the app and notification.
4. Toggle it off at any time to stop the service. With no peers configured, nothing is pinged.

## Configuration
- **Ping interval**: `app/src/main/java/.../TailscalePingService.kt`, `PING_INTERVAL_MS` constant.
- **Default peers**: `TailscalePinger.kt`, `configuredTailscaleIps` set.
- **HTTP port**: `CameraStreamingService.kt` and `MainActivity.kt` `port` value (default 8080).
- **Camera lens / resolution / quality**: Choose in the in-app **Camera Settings** card or via `?camera=`, `?res=`, `?q=` URL overrides (see Usage). Defaults live in `CameraSettings.kt`; lens classification logic is in `CameraLensResolver.kt`.

## Testing
- **Local browser/VLC test**: Start streaming, visit `/stream` or `/snapshot` from another device, or add the URL to VLC via “Open Network Stream.”
- **Service longevity**: Leave the stream running 30+ minutes with the screen off to confirm wake lock behavior.
- **Tailscale ping verification**: Run `adb logcat | grep TailscalePing` to confirm resolution and ping outcomes.

## Development Notes
- Project structure follows the standard Android Gradle layout under `app/src/main`.
- Dependencies include CameraX, Jetpack Compose, NanoHTTPD, Lifecycle runtime/service, Accompanist Permissions, WorkManager, and Kotlin coroutines.
- Common commands:
  ```bash
  ./gradlew lint
  ./gradlew test
  ./gradlew assembleRelease
  ```
- When modifying streaming code, test on physical hardware; emulators lack the necessary camera and network characteristics.

## Contributing
Issues and pull requests are welcome. Please include reproducible steps, device details, and logs for bug reports. For larger changes, open an issue first so we can discuss design and testing expectations.

## License
CameraLink is distributed under the [MIT License](./LICENSE). By contributing, you agree that your contributions will be licensed under the same terms.
