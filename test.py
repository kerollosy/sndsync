"""
metasync - Android Media Metadata Client
Streams live media metadata from an Android device via ADB.
"""

import argparse
import json
import logging
import signal
import socket
import subprocess
import sys
import time
import threading
from typing import Optional

from colorama import init, Fore, Style

init(autoreset=True)

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
    handler = logging.StreamHandler()
    handler.setFormatter(ColoredFormatter(
        fmt="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    ))
    logger.addHandler(handler)
    logger.propagate = False
    return logger


# ---------------------------------------------------------------------------
# ADB helpers
# ---------------------------------------------------------------------------

def adb(adb_cmd: list[str], *args, **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(adb_cmd + list(args), capture_output=True, text=True, **kwargs)


def check_adb(logger: logging.Logger):
    try:
        result = subprocess.run(["adb", "version"], capture_output=True, text=True)
        if result.returncode != 0:
            raise FileNotFoundError
        logger.debug(f"ADB: {result.stdout.splitlines()[0]}")
    except FileNotFoundError:
        logger.error("ADB not found. Install it and make sure it's in PATH.")
        sys.exit(1)


def check_device(adb_cmd: list[str], serial: Optional[str], logger: logging.Logger):
    result = adb(adb_cmd, "get-state")
    if "device" not in result.stdout:
        logger.error("No device connected." + (f" ({result.stderr.strip()})" if result.stderr else ""))
        sys.exit(1)
    logger.debug(f"Device ready" + (f": {serial}" if serial else ""))


def push_and_start_server(
    adb_cmd: list[str],
    jar_path: str,
    meta_port: int,
    logger: logging.Logger,
) -> subprocess.Popen:
    logger.info(f"Pushing MetaServer.jar to device...")
    result = adb(adb_cmd, "push", jar_path, "/data/local/tmp/MetaServer.jar")
    if result.returncode != 0:
        logger.error(f"Push failed: {result.stderr.strip()}")
        sys.exit(1)

    logger.info(f"Setting up port forwarding ({meta_port})...")
    result = adb(adb_cmd, "forward", f"tcp:{meta_port}", f"tcp:{meta_port}")
    if result.returncode != 0:
        logger.error(f"Port forwarding failed: {result.stderr.strip()}")
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

    # Give it a moment to bind the port
    time.sleep(2)

    if proc.poll() is not None:
        out, err = proc.communicate()
        logger.error("MetaServer failed to start.")
        if out: logger.debug(f"stdout:\n{out}")
        if err: logger.error(f"stderr:\n{err}")
        sys.exit(1)

    logger.debug("MetaServer running.")
    return proc


# ---------------------------------------------------------------------------
# Display
# ---------------------------------------------------------------------------

PLAYBACK_STATES = {
    1: "None",
    2: f"{Fore.RED}Stopped{Style.RESET_ALL}",
    3: f"{Fore.YELLOW}Paused{Style.RESET_ALL}",
    4: f"{Fore.GREEN}Playing{Style.RESET_ALL}",
    6: f"{Fore.CYAN}Buffering{Style.RESET_ALL}",
}

def fmt_duration(ms: int) -> str:
    s = ms // 1000
    return f"{s // 60}:{s % 60:02d}"

def fmt_position(ms: int) -> str:
    s = ms // 1000
    return f"{s // 60}:{s % 60:02d}"

def display_metadata(state: dict, logger: logging.Logger):
    title    = state.get("title")  or "Unknown"
    artist   = state.get("artist") or "Unknown"
    album    = state.get("album")  or "Unknown"
    duration = state.get("duration", 0) or 0
    package  = state.get("package") or "Unknown"

    logger.info(f"{Fore.CYAN}{'─' * 52}{Style.RESET_ALL}")
    logger.info(f"  {Fore.WHITE}{Style.BRIGHT}{title}{Style.RESET_ALL}")
    logger.info(f"  {Fore.YELLOW}{artist}{Style.RESET_ALL}  ·  {Fore.WHITE}{album}{Style.RESET_ALL}")
    if duration:
        logger.info(f"  Duration: {fmt_duration(duration)}")
    logger.info(f"  {Fore.BLUE}{package}{Style.RESET_ALL}")
    logger.info(f"{Fore.CYAN}{'─' * 52}{Style.RESET_ALL}")

def display_playback(state: dict, logger: logging.Logger):
    ps    = state.get("playback_state", 0)
    label = PLAYBACK_STATES.get(ps, f"State {ps}")
    pos   = state.get("position", 0) or 0
    speed = state.get("speed", 1.0) or 1.0
    speed_str = f"  ×{speed}" if speed != 1.0 else ""
    logger.info(f"  ▶ {label}  {fmt_position(pos)}{speed_str}")

def display_volume(state: dict, logger: logging.Logger):
    cur = state.get("volume_current")
    mx  = state.get("volume_max")
    if cur is None:
        return
    filled = round((cur / mx) * 20) if mx else 0
    bar = f"{Fore.GREEN}{'█' * filled}{Fore.WHITE}{'░' * (20 - filled)}{Style.RESET_ALL}"
    logger.info(f"  🔊 {bar}  {cur}/{mx}")


# ---------------------------------------------------------------------------
# Metadata listener
# ---------------------------------------------------------------------------

def metadata_listener(
    meta_port: int,
    logger: logging.Logger,
    running: threading.Event,
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
    }

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(2.0)

    try:
        logger.debug(f"Connecting to metadata server on port {meta_port}...")
        sock.connect(("127.0.0.1", meta_port))
        logger.info("Connected to metadata server.")
    except (socket.timeout, ConnectionRefusedError) as e:
        logger.error(f"Could not connect to metadata server: {e}")
        return

    buf = ""
    try:
        while running.is_set():
            try:
                data = sock.recv(4096).decode("utf-8")
                if not data:
                    logger.warning("Metadata server closed connection.")
                    break

                buf += data
                while "\n" in buf:
                    line, buf = buf.split("\n", 1)
                    line = line.strip()
                    if not line:
                        continue

                    try:
                        event = json.loads(line)
                    except json.JSONDecodeError:
                        logger.debug(f"Bad JSON: {line!r}")
                        continue

                    event_type = event.get("event")
                    logger.debug(f"← {line}")

                    if event_type == "session":
                        state["package"] = event.get("package")
                        pkg = state["package"] or "(none)"
                        logger.info(f"  Session: {Fore.BLUE}{pkg}{Style.RESET_ALL}")

                    elif event_type == "metadata":
                        state["title"]    = event.get("title")
                        state["artist"]   = event.get("artist")
                        state["album"]    = event.get("album")
                        state["duration"] = event.get("duration", 0)
                        display_metadata(state, logger)

                    elif event_type == "playback":
                        new_ps = event.get("state", 0)
                        state["position"] = event.get("position", 0)
                        state["speed"]    = event.get("speed", 1.0)
                        if new_ps != state["playback_state"]:
                            state["playback_state"] = new_ps
                            display_playback(state, logger)

                    elif event_type == "volume":
                        state["volume_current"] = event.get("current")
                        state["volume_max"]     = event.get("max")
                        display_volume(state, logger)

            except socket.timeout:
                continue
            except Exception as e:
                logger.debug(f"Listener error: {e}")
                break
    finally:
        sock.close()


# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------

def cleanup(
    adb_cmd: list[str],
    meta_port: int,
    server_proc: Optional[subprocess.Popen],
    running: threading.Event,
    logger: logging.Logger,
):
    logger.info("Cleaning up...")
    running.clear()

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
        description="Stream live media metadata from an Android device."
    )
    parser.add_argument("-s", "--serial",      help="ADB device serial (for multiple devices)")
    parser.add_argument("-p", "--port",        type=int, default=9998, help="Metadata port (default: 9998)")
    parser.add_argument("-j", "--jar",         default="MetaServer.jar", help="Path to MetaServer.jar")
    parser.add_argument("-d", "--debug",       action="store_true", help="Enable debug logging")
    args = parser.parse_args()

    logger = make_logger(args.debug)

    adb_cmd = ["adb"] + (["-s", args.serial] if args.serial else [])

    check_adb(logger)
    check_device(adb_cmd, args.serial, logger)

    server_proc = push_and_start_server(adb_cmd, args.jar, args.port, logger)
    running = threading.Event()
    running.set()

    def handle_signal(sig, frame):
        cleanup(adb_cmd, args.port, server_proc, running, logger)
        sys.exit(0)

    signal.signal(signal.SIGINT,  handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)

    # Run listener on main thread — it blocks until disconnected or Ctrl+C
    try:
        metadata_listener(args.port, logger, running)
    finally:
        cleanup(adb_cmd, args.port, server_proc, running, logger)


if __name__ == "__main__":
    main()