# SndSync

Stream audio from Android devices to desktop over TCP.

## Prerequisites

**Desktop:**
- Python 3.7+
- ADB in PATH
- `pip install pyaudio colorama`

**Android:**
- Android 11+
- Make sure you [enabled USB debugging][enable-adb] on your device.

[enable-adb]: https://developer.android.com/studio/debug/dev-options#enable

## Setup

### Option 1: Download Pre-built JAR (Recommended)
1. Download `AudioServer.jar` from the [latest release](../../releases/latest)
2. Place it in the project directory

### Option 2: Build from Source
1. Build the server yourself:
   ```bash
   build.bat     # Windows
   ./build.sh    # Mac/Linux
   ```

### Running the Client
Once you have the JAR file, connect your device to ADB and then run:
```bash
python sndsync_client.py
```

## Usage

```bash
python sndsync_client.py [options]

Options:
    -s SERIAL    Device serial (for multiple devices)
    -p PORT      Port number (default: 9999)  
    -j JAR       Path to AudioServer.jar (default: ./AudioServer.jar)
    -d           Debug mode
```

## How It works
```
Android Device                    Desktop Computer
┌─────────────────┐             ┌──────────────────┐
│   AudioServer   │ ◄─TCP────►  │  sndsync_client  │
│                 │   Port      │                  │
│ ┌─────────────┐ │   9999      │ ┌──────────────┐ │
│ │ AudioRecord │ │             │ │   PyAudio    │ │
│ │(REMOTE_     │ │             │ │   Playback   │ │
│ │ SUBMIX)     │ │             │ │              │ │
│ └─────────────┘ │             │ └──────────────┘ │
└─────────────────┘             └──────────────────┘
```
Audio config: 48kHz, 16-bit PCM, mono

## Troubleshooting

- **No AudioServer.jar**: Download from releases or run build script
- **No device**: Make sure your device is connected and is visible in `adb devices`
- **Connection failed**: Try different port with `-p`

Run with `-d` for debug output.