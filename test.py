"""
metasync - Android Media Metadata → Windows SMTC
Streams live media metadata from an Android device and exposes it
via Windows System Media Transport Controls (taskbar media flyout).

Requirements:
    pip install winrt-runtime winrt-Windows.Media winrt-Windows.Media.Playback colorama
"""

import argparse
import base64
import json
import logging
import signal
import socket
import subprocess
import sys
import time
import threading
from datetime import timedelta
from typing import Optional

from colorama import init, Fore, Style
from winrt.windows.media import (
    MediaPlaybackStatus,
    MediaPlaybackType,
    SystemMediaTransportControlsTimelineProperties,
)
from winrt.windows.media.playback import BackgroundMediaPlayer
from winrt.windows.storage.streams import (
    InMemoryRandomAccessStream,
    DataWriter,
    RandomAccessStreamReference,
)

init(autoreset=True)

# ADB keyevent codes
KEYEVENT = {
    "play":      "126",
    "pause":     "127",
    "next":      "87",
    "prev":      "88",
    "stop":      "86",
    "playpause": "85",
}

# Android PlaybackState → WinRT MediaPlaybackStatus
ANDROID_STATE_MAP = {
    2: MediaPlaybackStatus.PAUSED,    # Poweramp paused
    3: MediaPlaybackStatus.PLAYING,   # Poweramp playing
    4: MediaPlaybackStatus.PLAYING,
    6: MediaPlaybackStatus.CHANGING,
}

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------

class ColoredFormatter(logging.Formatter):
    COLORS = {
        "DEBUG":   Fore.BLUE,
        "INFO":    Fore.GREEN,
        "WARNING": Fore.YELLOW,
        "ERROR":   Fore.RED,
    }
    def format(self, record):
        color = self.COLORS.get(record.levelname, "")
        record.levelname = f"{color}{record.levelname}{Style.RESET_ALL}"
        return super().format(record)


def make_logger(debug: bool) -> logging.Logger:
    logger = logging.getLogger("metasync")
    logger.setLevel(logging.DEBUG if debug else logging.INFO)
    h = logging.StreamHandler()
    h.setFormatter(ColoredFormatter(
        fmt="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    ))
    logger.addHandler(h)
    logger.propagate = False
    return logger


# ---------------------------------------------------------------------------
# ADB helpers
# ---------------------------------------------------------------------------

def adb(cmd: list[str], *args, **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd + list(args), capture_output=True, text=True, **kwargs)


def check_adb(logger: logging.Logger):
    try:
        r = subprocess.run(["adb", "version"], capture_output=True, text=True)
        if r.returncode != 0:
            raise FileNotFoundError
        logger.debug(r.stdout.splitlines()[0])
    except FileNotFoundError:
        logger.error("ADB not found. Install it and make sure it's in PATH.")
        sys.exit(1)


def check_device(adb_cmd: list[str], serial: Optional[str], logger: logging.Logger):
    r = adb(adb_cmd, "get-state")
    if "device" not in r.stdout:
        logger.error("No device connected." + (f" ({r.stderr.strip()})" if r.stderr else ""))
        sys.exit(1)
    logger.debug("Device ready" + (f": {serial}" if serial else ""))


def push_and_start_server(
    adb_cmd: list[str],
    jar_path: str,
    meta_port: int,
    logger: logging.Logger,
) -> subprocess.Popen:
    logger.info("Pushing MetaServer.jar to device...")
    r = adb(adb_cmd, "push", jar_path, "/data/local/tmp/MetaServer.jar")
    if r.returncode != 0:
        logger.error(f"Push failed: {r.stderr.strip()}")
        sys.exit(1)

    logger.info(f"Setting up port forwarding ({meta_port})...")
    r = adb(adb_cmd, "forward", f"tcp:{meta_port}", f"tcp:{meta_port}")
    if r.returncode != 0:
        logger.error(f"Port forwarding failed: {r.stderr.strip()}")
        sys.exit(1)

    logger.info("Starting MetaServer on device...")
    proc = subprocess.Popen(
        adb_cmd + [
            "shell",
            f"CLASSPATH=/data/local/tmp/MetaServer.jar "
            f"app_process /data/local/tmp/ com.audioserver.MetaServer {meta_port}",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )

    time.sleep(2)

    if proc.poll() is not None:
        out, err = proc.communicate()
        logger.error("MetaServer failed to start.")
        if out: logger.debug(f"stdout:\n{out}")
        if err: logger.error(f"stderr:\n{err}")
        sys.exit(1)

    logger.debug("MetaServer running.")
    return proc


def send_keyevent(adb_cmd: list[str], key: str, logger: logging.Logger):
    code = KEYEVENT.get(key)
    if not code:
        logger.debug(f"Unknown button: {key!r}")
        return
    logger.debug(f"keyevent {key} ({code})")
    adb(adb_cmd, "shell", "input", "keyevent", code)


# ---------------------------------------------------------------------------
# SMTC
# ---------------------------------------------------------------------------

def init_smtc(logger: logging.Logger):
    logger.info("Initialising SMTC...")
    player  = BackgroundMediaPlayer.current
    smtc    = player.system_media_transport_controls
    updater = smtc.display_updater

    smtc.is_enabled          = True
    smtc.is_play_enabled     = True
    smtc.is_pause_enabled    = True
    smtc.is_next_enabled     = True
    smtc.is_previous_enabled = True
    smtc.is_stop_enabled     = True
    smtc.playback_status     = MediaPlaybackStatus.CLOSED

    updater.type = MediaPlaybackType.MUSIC
    updater.update()

    logger.info("SMTC ready.")
    return smtc, updater


def smtc_update(smtc, updater, state: dict, logger: logging.Logger):
    """Push full state to SMTC. Skips music_properties if title is absent to avoid WinError."""
    title    = state.get("title")   or ""
    artist   = state.get("artist")  or ""
    album    = state.get("album")   or ""
    duration = state.get("duration", 0) or 0
    ps       = state.get("playback_state", 0)
    art_b64  = state.get("art")

    # music_properties throws if type hasn't been set to MUSIC first
    updater.type = MediaPlaybackType.MUSIC

    # Only write properties if we have at least a title — avoids the
    # "media type not initialised" WinError on null-metadata events
    if title:
        updater.music_properties.title       = title
        updater.music_properties.artist      = artist
        updater.music_properties.album_title = album

    if duration > 0:
        tl = SystemMediaTransportControlsTimelineProperties()
        tl.start_time    = timedelta()
        tl.min_seek_time = timedelta()
        tl.position      = timedelta()
        tl.max_seek_time = timedelta(milliseconds=duration)
        tl.end_time      = timedelta(milliseconds=duration)
        smtc.update_timeline_properties(tl)

    updater.thumbnail = None # always clear first

    if art_b64:
        try:
            img_bytes = base64.b64decode(art_b64)
            stream = InMemoryRandomAccessStream()
            writer = DataWriter(stream)
            writer.write_bytes(img_bytes)
            # store_async must be awaited — use the synchronous _interop path winrt exposes
            writer.store_async().get()
            writer.detach_stream()
            stream.seek(0)
            updater.thumbnail = RandomAccessStreamReference.create_from_stream(stream)
            logger.debug(f"Album art set ({len(img_bytes)} bytes)")
        except Exception as e:
            logger.debug(f"Art load failed: {e}")

    smtc.playback_status = ANDROID_STATE_MAP.get(ps, MediaPlaybackStatus.CLOSED)
    updater.update()


def smtc_clear(smtc, updater):
    smtc.playback_status = MediaPlaybackStatus.CLOSED
    updater.clear_all()
    updater.update()


# ---------------------------------------------------------------------------
# Terminal display
# ---------------------------------------------------------------------------

PLAYBACK_LABELS = {
    1: "None",
    2: f"{Fore.RED}Stopped{Style.RESET_ALL}",
    3: f"{Fore.YELLOW}Paused{Style.RESET_ALL}",
    4: f"{Fore.GREEN}▶ Playing{Style.RESET_ALL}",
    6: f"{Fore.CYAN}Buffering{Style.RESET_ALL}",
}

def fmt_ms(ms: int) -> str:
    s = (ms or 0) // 1000
    return f"{s // 60}:{s % 60:02d}"

def display_metadata(state: dict, logger: logging.Logger):
    has_art = bool(state.get("art"))
    logger.info(f"{Fore.CYAN}{'─' * 52}{Style.RESET_ALL}")
    logger.info(f"  {Style.BRIGHT}{Fore.WHITE}{state.get('title') or 'Unknown'}{Style.RESET_ALL}")
    logger.info(f"  {Fore.YELLOW}{state.get('artist') or 'Unknown'}{Style.RESET_ALL}"
                f"  ·  {Fore.WHITE}{state.get('album') or 'Unknown'}{Style.RESET_ALL}")
    dur = state.get("duration", 0) or 0
    if dur:
        logger.info(f"  Duration : {fmt_ms(dur)}")
    logger.info(f"  Package  : {Fore.BLUE}{state.get('package') or 'Unknown'}{Style.RESET_ALL}")
    logger.info(f"  Art      : {'yes' if has_art else 'none'}")
    logger.info(f"{Fore.CYAN}{'─' * 52}{Style.RESET_ALL}")

def display_playback(state: dict, logger: logging.Logger):
    ps    = state.get("playback_state", 0)
    label = PLAYBACK_LABELS.get(ps, f"State {ps}")
    pos   = state.get("position", 0) or 0
    speed = state.get("speed", 1.0)  or 1.0
    speed_str = f"  ×{speed}" if speed != 1.0 else ""
    logger.info(f"  {label}  {fmt_ms(pos)}{speed_str}")

def display_volume(state: dict, logger: logging.Logger):
    cur = state.get("volume_current")
    mx  = state.get("volume_max")
    if cur is None or not mx:
        return
    filled = round((cur / mx) * 20)
    bar = (f"{Fore.GREEN}{'█' * filled}"
           f"{Fore.WHITE}{'░' * (20 - filled)}{Style.RESET_ALL}")
    logger.info(f"  🔊 {bar}  {cur}/{mx}")


# ---------------------------------------------------------------------------
# Main listener loop
# ---------------------------------------------------------------------------

def run(
    adb_cmd: list[str],
    meta_port: int,
    smtc,
    updater,
    running: threading.Event,
    logger: logging.Logger,
):
    state = {
        "package":        None,
        "title":          None,
        "artist":         None,
        "album":          None,
        "duration":       0,
        "playback_state": 0,
        "position":       0,
        "speed":          1.0,
        "volume_current": None,
        "volume_max":     None,
        "art":            None,
    }

    SMTC_BUTTON_MAP = {
        0: "playpause",   # Play button shown when paused
        1: "playpause",   # Pause button shown when playing
        2: "stop",
        6: "next",
        7: "prev",
    }

    def on_button(sender, event_args):
        btn = SMTC_BUTTON_MAP.get(event_args.button)
        if not btn:
            logger.debug(f"Unknown SMTC button: {event_args.button}")
            return
        logger.info(f"  ← SMTC: {btn}")

        # Optimistically flip the SMTC state so the button updates instantly
        if btn == "playpause":
            current = smtc.playback_status
            smtc.playback_status = (
                MediaPlaybackStatus.PAUSED
                if current == MediaPlaybackStatus.PLAYING
                else MediaPlaybackStatus.PLAYING
            )

        threading.Thread(
            target=send_keyevent, args=(adb_cmd, btn, logger), daemon=True
        ).start()

    # add_button_pressed returns a token — store it for removal on teardown
    button_token = smtc.add_button_pressed(on_button)

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(2.0)

    try:
        logger.debug(f"Connecting to 127.0.0.1:{meta_port}...")
        sock.connect(("127.0.0.1", meta_port))
        logger.info("Connected to metadata server.")
    except (socket.timeout, ConnectionRefusedError) as e:
        logger.error(f"Could not connect to metadata server: {e}")
        smtc.remove_button_pressed(button_token)
        return

    buf = ""
    try:
        while running.is_set():
            try:
                chunk = sock.recv(4096).decode("utf-8")
                if not chunk:
                    logger.warning("Metadata server closed connection.")
                    break

                buf += chunk
                while "\n" in buf:
                    line, buf = buf.split("\n", 1)
                    line = line.strip()
                    if not line:
                        continue

                    try:
                        event = json.loads(line)
                    except json.JSONDecodeError:
                        logger.debug(f"Bad JSON: {line[:80]!r}")
                        continue

                    # Log without the base64 art blob
                    log_event = {k: v for k, v in event.items() if k != "art"}
                    if "art" in event:
                        log_event["art"] = "<bytes>" if event["art"] else None
                    logger.debug(f"← {json.dumps(log_event)}")

                    etype = event.get("event")

                    if etype == "session":
                        state["package"] = event.get("package")
                        pkg = state["package"] or "(none)"
                        logger.info(f"  Session: {Fore.BLUE}{pkg}{Style.RESET_ALL}")
                        if not state["package"]:
                            smtc_clear(smtc, updater)

                    elif etype == "metadata":
                        state["title"]    = event.get("title")
                        state["artist"]   = event.get("artist")
                        state["album"]    = event.get("album")
                        state["duration"] = event.get("duration", 0)
                        state["art"]      = event.get("art")
                        display_metadata(state, logger)
                        smtc_update(smtc, updater, state, logger)

                    elif etype == "playback":
                        new_ps            = event.get("state", 0)
                        state["position"] = event.get("position", 0)
                        state["speed"]    = event.get("speed", 1.0)
                        if new_ps != state["playback_state"]:
                            state["playback_state"] = new_ps
                            display_playback(state, logger)
                            smtc_update(smtc, updater, state, logger)

                    elif etype == "volume":
                        state["volume_current"] = event.get("current")
                        state["volume_max"]     = event.get("max")
                        display_volume(state, logger)

            except socket.timeout:
                continue
            except Exception as e:
                logger.debug(f"Listener error: {e}")
                break
    finally:
        smtc.remove_button_pressed(button_token)
        sock.close()


# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------

def cleanup(
    adb_cmd: list[str],
    meta_port: int,
    server_proc: Optional[subprocess.Popen],
    smtc,
    updater,
    running: threading.Event,
    logger: logging.Logger,
):
    logger.info("Cleaning up...")
    running.clear()

    if smtc and updater:
        try:
            smtc_clear(smtc, updater)
        except Exception:
            pass

    if server_proc:
        try:
            server_proc.terminate()
            server_proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            server_proc.kill()
        except Exception:
            pass

    try:
        adb(adb_cmd, "forward", "--remove", f"tcp:{meta_port}", timeout=5)
    except Exception:
        pass

    logger.info("Done.")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Stream Android media metadata to Windows SMTC."
    )
    parser.add_argument("-s", "--serial", help="ADB device serial")
    parser.add_argument("-p", "--port",   type=int, default=9998, help="Metadata port (default: 9998)")
    parser.add_argument("-j", "--jar",    default="MetaServer.jar", help="Path to MetaServer.jar")
    parser.add_argument("-d", "--debug",  action="store_true")
    args = parser.parse_args()

    logger      = make_logger(args.debug)
    adb_cmd     = ["adb"] + (["-s", args.serial] if args.serial else [])
    running     = threading.Event()
    running.set()
    smtc        = None
    updater     = None
    server_proc = None

    def handle_signal(sig, frame):
        cleanup(adb_cmd, args.port, server_proc, smtc, updater, running, logger)
        sys.exit(0)

    signal.signal(signal.SIGINT,  handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)

    check_adb(logger)
    check_device(adb_cmd, args.serial, logger)
    server_proc   = push_and_start_server(adb_cmd, args.jar, args.port, logger)
    smtc, updater = init_smtc(logger)

    try:
        run(adb_cmd, args.port, smtc, updater, running, logger)
    finally:
        cleanup(adb_cmd, args.port, server_proc, smtc, updater, running, logger)


if __name__ == "__main__":
    main()