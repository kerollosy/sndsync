# sndsync

Stream your Android device's audio and Now Playing metadata to your Windows desktop over ADB — no root, no Wi-Fi, no third-party apps on the device.

Audio plays through your PC speakers in real time. The currently playing track (title, artist, album art, playback position) shows up in the Windows media overlay just like any local media player, and you can control playback from there too.

---

## How it works

sndsync runs a small Java server on the device via `app_process` under the `com.android.shell` identity. No installation required — the JAR is pushed at runtime and executed directly. Two servers start up:

- **AudioServer** — captures internal audio using Android's `REMOTE_SUBMIX` source and streams raw PCM over TCP.
- **MetaServer** — polls `MediaSessionManager` for the active media session and pushes JSON events (metadata, playback state, volume) over a second TCP connection.

On the PC side, a Python client pulls both streams over ADB port forwarding, plays audio through PyAudio with a configurable jitter buffer, and updates the Windows System Media Transport Controls (SMTC) via `winrt` so the OS sees it as a proper media session.

Media key presses from Windows (play, pause, next, previous) are forwarded back to the device as ADB `input keyevent` commands.

```
Android device                           Windows PC
──────────────────────────────────────   ──────────────────────────────────────
AudioServer  ──[ TCP / ADB forward ]──►  PyAudio playback
MetaServer   ──[ TCP / ADB forward ]──►  SMTC (lock screen, taskbar, widgets)
             ◄──────[ ADB shell ]──────  Media key events
```

---

## Requirements

**Android device**
- Android 11 (API 30) or higher — required for `REMOTE_SUBMIX` audio capture.
- USB debugging enabled.

**PC**
- [ADB](https://developer.android.com/tools/releases/platform-tools) installed and on your `PATH`.
- Python 3.10+
- The following Python packages:

```
pyaudio
colorama
winrt-runtime
winrt-Windows.Media
winrt-Windows.Media.Playback
winrt-Windows.Storage.Streams
```

The `winrt` packages are Windows-only. On other platforms, SMTC support is silently disabled and only audio streaming works.

**Building the server JAR**

The Java sources in this repository need to be compiled into `sndsync-server.jar`. Build with your preferred toolchain targeting Android's `app_process` environment. The entry point is `com.sndsync.Main`.

---

## Installation

```bash
pip install pyaudio colorama winrt-runtime winrt-Windows.Media winrt-Windows.Media.Playback winrt-Windows.Storage.Streams
```

Connect your Android device via USB and verify ADB sees it:

```bash
adb devices
```

---

## Usage

```bash
python sndsync_client.py --jar path/to/sndsync-server.jar
```

That's the common case. The client will push the JAR, forward the ports, start both servers, connect, and begin streaming.

### Options

| Flag | Default | Description |
|---|---|---|
| `-j`, `--jar` | *(required)* | Path to `sndsync-server.jar` |
| `-ap`, `--audio-port` | `9999` | Local TCP port for audio forwarding |
| `-mp`, `--metadata-port` | `9998` | Local TCP port for metadata forwarding |
| `-s`, `--serial` | — | ADB device serial, required if multiple devices are connected |
| `--no-audio` | — | Skip audio streaming, metadata only |
| `--no-metadata` | — | Skip metadata streaming, audio only |
| `--stereo` | — | Record in stereo instead of mono |
| `--max-latency MS` | `200` | Jitter buffer cap in milliseconds. Chunks older than this are dropped to snap back to live audio |
| `-d`, `--debug` | — | Enable verbose debug logging |

### Examples

**Stream audio only (no SMTC integration):**
```bash
python sndsync_client.py --jar sndsync-server.jar --no-metadata
```

**SMTC/Now Playing only, no audio (e.g. you're already using the phone speaker):**
```bash
python sndsync_client.py --jar sndsync-server.jar --no-audio
```

**Stereo audio with a tighter latency target:**
```bash
python sndsync_client.py --jar sndsync-server.jar --stereo --max-latency 100
```

**Specific device when multiple are connected:**
```bash
python sndsync_client.py --jar sndsync-server.jar --serial emulator-5554
```

Press `Ctrl+C` to stop. The client cleans up port forwarding and terminates the server process on exit.

---

## Audio latency

The client uses a jitter buffer to absorb network jitter over ADB. The `--max-latency` value is the ceiling: if the buffer grows beyond that many milliseconds of audio, old chunks are silently dropped to resync to live. Lower values reduce latency but increase the chance of audible glitches on a busy USB bus. The default of 200 ms is a reasonable starting point for most setups.

If you hear stuttering, raise `--max-latency`. If playback consistently lags, lower it.

---

## Limitations

- **Android 11+ only.** The `REMOTE_SUBMIX` audio source with the necessary permissions is not available on older versions.
- **One client at a time.** The AudioServer streams to a single connected client; connecting a second will wait until the first disconnects.
- **DRM-protected audio** (Widevine L1, etc.) is not captured by `REMOTE_SUBMIX` by design — this is an Android platform restriction, not a sndsync limitation.
- **SMTC integration is Windows-only.** The `winrt` dependency is not available on Linux or macOS. Audio streaming works on any platform with PyAudio.
- **No seeking.** The timeline position shown in SMTC reflects what the device reports; seeking from the Windows overlay is not implemented.

---

## Troubleshooting

**`ADB not found`** — Install [Platform Tools](https://developer.android.com/tools/releases/platform-tools) and make sure `adb` is on your `PATH`.

**`No device connected`** — Run `adb devices` and confirm your device appears as `device` (not `unauthorized`). Accept the USB debugging prompt on the device if needed.

**`AudioRecord with REMOTE_SUBMIX source requires Android 11 or higher`** — The device is running Android 10 or below. There is no workaround.

**Audio starts then immediately drops out** — Try increasing `--max-latency`. USB bandwidth spikes (e.g. file transfers in the background) can starve the audio socket.

**No album art in SMTC** — Some apps don't populate `METADATA_KEY_ALBUM_ART`. This is an app-side limitation.

**Server fails to start, logcat shows permission errors** — Some device manufacturers restrict `REMOTE_SUBMIX` access even under the shell identity. Devices running stock AOSP or stock-adjacent ROMs work reliably.