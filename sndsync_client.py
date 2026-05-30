"""
sndsync - Android Audio Streaming Client
Stream audio and metadata from Android devices to desktop in real-time.
"""

import json
import subprocess
import socket
import threading
import time
import signal
import sys
import argparse
import logging
import struct
from pathlib import Path
from typing import Optional

import pyaudio
from colorama import init, Fore, Style
from smtc_bridge import SmtcBridge

init(autoreset=True)

# Maps logical media key names to Android key event codes.
KEYEVENT = {
    "play": "126",
    "pause": "127",
    "next": "87",
    "prev": "88",
    "stop": "86",
}


class ColoredFormatter(logging.Formatter):
    """Custom logging formatter that colorizes level names for terminal output."""
    
    COLORS = {
        'DEBUG': Fore.BLUE,
        'INFO': Fore.GREEN,
        'WARNING': Fore.YELLOW,
        'ERROR': Fore.RED,
    }
    
    def format(self, record):
        color = self.COLORS.get(record.levelname, '')
        record.levelname = f"{color}{record.levelname}{Style.RESET_ALL}"
        return super().format(record)


class SndsyncClient:
    """Android audio streaming client using ADB and socket communication."""
    
    def __init__(
        self,
        jar_path: str,
        audio_port: int = 9999,
        meta_port: int = 9998,
        device_serial: Optional[str] = None,
        enable_audio: bool = True,
        enable_metadata: bool = True,
        stereo: bool = False,
        debug: bool = False
    ):
        """
        Initialize the sndsync client.

        Args:
            jar_path: Path to sndsync-server.jar.
            audio_port: Local TCP port for audio stream forwarding.
            meta_port: Local TCP port for metadata stream forwarding.
            device_serial: ADB device serial for targeting a specific device.
            enable_audio: Start the AudioServer and stream audio locally.
            enable_metadata: Start the MetaServer and update SMTC.
            stereo: Record in stereo instead of mono.
            debug: Enable debug logging

        Raises:
            ValueError: If neither enable_audio nor enable_metadata is True.
        """
        if not enable_audio and not enable_metadata:
            raise ValueError("At least one stream must be enabled (remove --no-audio or --no-metadata).")

        self.jar_path = Path(jar_path)
        self.audio_port = audio_port
        self.meta_port = meta_port
        self.device_serial = device_serial
        self.enable_audio = enable_audio
        self.enable_metadata = enable_metadata
        self.stereo = stereo
        self.running = True

        # Logging
        self.logger = logging.getLogger("sndsync")
        self.logger.setLevel(logging.DEBUG if debug else logging.INFO)
        handler = logging.StreamHandler()
        handler.setFormatter(ColoredFormatter(
            fmt='%(asctime)s [%(levelname)s] %(message)s',
            datefmt='%Y-%m-%d %H:%M:%S'
        ))
        self.logger.addHandler(handler)
        self.logger.propagate = False

        # Subprocess / socket resources
        self.server_process: Optional[subprocess.Popen] = None
        self._logcat_process: Optional[subprocess.Popen] = None
        self.audio_socket: Optional[socket.socket] = None
        self.metadata_socket: Optional[socket.socket] = None

        # PyAudio resources
        self.pyaudio_instance: Optional[pyaudio.PyAudio] = None
        self.audio_stream: Optional[pyaudio.Stream] = None

        # Metadata thread control
        self.metadata_running: threading.Event = threading.Event()
        self.metadata_thread: Optional[threading.Thread] = None

        self.smtc = SmtcBridge(self.logger, self._send_media_key)

        # Audio configuration (will be set from server header)
        self.sample_rate: Optional[int] = None
        self.channels: Optional[int] = None
        self.audio_format: Optional[int] = None

        # Build ADB command prefix - all subprocess calls prepend this.
        self.adb_cmd = ["adb"]
        if device_serial:
            self.adb_cmd.extend(["-s", device_serial])

    def run(self):
        """Execute the complete streaming workflow."""
        self._check_adb()
        self._check_device()
        self._setup_server()

        if self.enable_metadata:
            self.smtc.initialize()
            self.metadata_running.set()
            self.metadata_thread = threading.Thread(
                target=self.metadata_listener,
                daemon=True,
            )
            self.metadata_thread.start()

        if self.enable_audio:
            self._connect_audio()
            self._stream()
        else:
            # Only metadata server running - keep process alive
            self.logger.info("Metadata server active. Press Ctrl+C to stop.")
            while self.running:
                time.sleep(1)

    def _check_adb(self):
        """Verify ADB is installed and accessible."""
        self.logger.info("Checking ADB installation...")
        try:
            result = subprocess.run(self.adb_cmd + ["version"], capture_output=True, text=True)
            if result.returncode != 0:
                raise FileNotFoundError
            self.logger.debug(f"ADB version: {result.stdout.splitlines()[0]}")
        except FileNotFoundError:
            self.logger.error("ADB not found. Please install ADB and ensure it's in your PATH.")
            sys.exit(1)

    def _check_device(self):
        """Verify that the target device is connected and available."""
        self.logger.info("Checking device connection...")
        result = subprocess.run(self.adb_cmd + ["get-state"], capture_output=True, text=True)

        if "device" not in result.stdout:
            self.logger.error("No device connected.")
            if result.stderr:
                self.logger.error(f"ADB error: {result.stderr.strip()}")
            sys.exit(1)

        if self.device_serial:
            self.logger.info(f"Using device: {self.device_serial}")

    def _setup_server(self):
        """Push the JAR, forward ports, and launch the sndsync server process."""
        if not self.jar_path.exists():
            self.logger.error(f"sndsync-server.jar not found at: {self.jar_path}")
            self.logger.error("Please compile the project first or specify correct path with --jar")
            sys.exit(1)

        self.logger.info(f"Pushing {self.jar_path.name} to device...")
        result = subprocess.run(
            self.adb_cmd + ["push", str(self.jar_path), "/data/local/tmp/sndsync-server.jar"],
            capture_output=True, text=True
        )
        if result.returncode != 0:
            self.logger.error("Failed to push JAR to device.")
            self.logger.error(result.stderr.strip())
            sys.exit(1)

        self._clear_logcat()

        if self.enable_audio:
            self._forward_port(self.audio_port)
        
        if self.enable_metadata:
            self._forward_port(self.meta_port)

        logcat_tags = ["sndsync"]
        if self.enable_audio:
            logcat_tags.append("SndsyncAudioServer")
        if self.enable_metadata:
            logcat_tags.append("SndsyncMetaServer")

        # Build server arguments using the --audio- / --meta- prefix.
        # Main.java strips the prefix before forwarding to each server's own main().
        server_args = []
        if self.enable_audio:
            server_args += ["--audio-port", str(self.audio_port)]
            if self.stereo:
                server_args += ["--audio-stereo"]
        if self.enable_metadata:
            server_args += ["--meta-port", str(self.meta_port)]

        self.logger.info("Starting sndsync server on device...")
        self.server_process = subprocess.Popen(
            self.adb_cmd + [
                "shell",
                "CLASSPATH=/data/local/tmp/sndsync-server.jar"
                " app_process /data/local/tmp/ com.sndsync.Main "
                + " ".join(server_args),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )

        # Wait a moment for server to start
        self.logger.debug("Waiting for server to start...")
        time.sleep(2)

        # Check if server started successfully
        if self.server_process.poll() is not None:
            stdout, stderr = self.server_process.communicate()
            self.logger.error("sndsync server failed to start.")
            if stdout:
                self.logger.debug(f"Server STDOUT:\n{stdout.strip()}")
            if stderr:
                self.logger.error(f"Server STDERR:\n{stderr.strip()}")
            
            for tag in logcat_tags:
                result = subprocess.run(
                    self.adb_cmd + ["logcat", "-d", "-s", f"{tag}:E", "-v", "brief"],
                    capture_output=True, text=True,
                )
                if result.stdout.strip():
                    self.logger.error(f"logcat [{tag}]:\n{result.stdout.strip()}")

            sys.exit(1)
        
        # Drain subprocess pipes (logcat is the real output channel)
        self._drain_pipe(self.server_process.stdout, "server-stdout")
        self._drain_pipe(self.server_process.stderr, "server-stderr")

        # Tail logcat for all server components under one process
        self._logcat_process = self._start_logcat_tail(logcat_tags)
        self.logger.debug("Server is running.")

    def _forward_port(self, port: int):
        """Set up ADB TCP port forwarding for the given port."""
        self.logger.info(f"Forwarding port {port}...")
        result = subprocess.run(
            self.adb_cmd + ["forward", f"tcp:{port}", f"tcp:{port}"],
            capture_output=True, text=True
        )
        if result.returncode != 0:
            self.logger.error(f"Failed to set up port forwarding for port {port}.")
            self.logger.error(result.stderr.strip())
            sys.exit(1)
    
    # Logcat tailing

    def _start_logcat_tail(self, tags: list[str]) -> subprocess.Popen:
        """
        Start a logcat process that streams all log levels for the given tags
        and forwards each line to the Python logger at DEBUG level.
        """
        tag_filter = " ".join(f"{t}:*" for t in tags)
        process = subprocess.Popen(
            self.adb_cmd + ["logcat", "-s", tag_filter, "-v", "time"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
        )
        threading.Thread(
            target=self._tail_logcat_pipe,
            args=(process,),
            daemon=True,
        ).start()
        return process

    def _tail_logcat_pipe(self, process: subprocess.Popen):
        """Read lines from a logcat process and forward them to the Python logger."""
        try:
            for line in process.stdout:
                line = line.rstrip()
                if line:
                    self.logger.debug(f"[device] {line}")
        except Exception as e:
            self.logger.debug(f"Logcat tail ended: {e}")

    def _drain_pipe(self, pipe, stream: str):
        """Drain a subprocess pipe in a background thread to prevent buffer deadlock."""
        def _drain():
            try:
                for _ in pipe:
                    pass
            except Exception:
                pass
        threading.Thread(target=_drain, name=f"drain-{stream}", daemon=True).start()

    def _clear_logcat(self):
        """Clear the device log buffer before starting a new run."""
        self.logger.info("Clearing logcat buffer...")
        result = subprocess.run(
            self.adb_cmd + ["logcat", "-c"],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            self.logger.warning("Failed to clear logcat buffer.")
            if result.stderr:
                self.logger.warning(result.stderr.strip())

    # Metadata streaming

    def metadata_listener(self):
        """
        Connect to the MetaServer socket and forward JSON events to the SMTC bridge.
        Runs in a daemon thread, exits when _metadata_running is cleared.
        """
        self.logger.info("Connecting to metadata stream...")

        self.metadata_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.metadata_socket.settimeout(2)

        try:
            self.logger.debug(f"Connecting to 127.0.0.1:{self.meta_port}")
            self.metadata_socket.connect(("127.0.0.1", self.meta_port))
            self.logger.info("Connected to MetaServer.")
        except socket.timeout:
            self.logger.error("Metadata connection timed out - MetaServer may not be ready.")
            return
        except socket.error as e:
            self.logger.error(f"Metadata connection failed: {e}")
            return

        recv_buffer = ""
        try:
            while self.metadata_running.is_set():
                try:
                    data = self.metadata_socket.recv(4096).decode("utf-8")
                except socket.timeout:
                    # Expected when metadata is unchanged.
                    continue

                if not data:
                    self.logger.info("Metadata connection closed by device.")
                    break

                recv_buffer += data
                while "\n" in recv_buffer:
                    line, recv_buffer = recv_buffer.split("\n", 1)
                    line = line.strip()
                    if not line:
                        continue

                    try:
                        event = json.loads(line)
                    except json.JSONDecodeError:
                        self.logger.debug(f"Malformed JSON from MetaServer: {line!r}")
                        continue

                    self.smtc.update_from_event(event)

        except socket.error as e:
            self.logger.error(f"Metadata socket error: {e}")
        except Exception as e:
            self.logger.error(f"Unexpected error in metadata listener: {e}")

    # Media key handling

    def _send_media_key(self, key: str) -> None:
        """Send an Android media key event via ADB input."""
        code = KEYEVENT.get(key)
        if not code:
            self.logger.debug(f"Unknown media key: {key}")
            return

        try:
            self.logger.debug(f"Sending keyevent '{key}' (code {code})")
            subprocess.run(
                self.adb_cmd + ["shell", "input", "keyevent", code],
                capture_output=True,
                text=True,
                timeout=5,
            )
        except Exception as e:
            self.logger.debug(f"Keyevent send failed: {e}")

    # Audio streaming
    
    def _connect_audio(self):
        """Connect to the AudioServer socket and read the configuration header."""
        self.logger.info("Connecting to audio stream...")
        
        self.audio_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.audio_socket.settimeout(10)

        try:
            self.logger.debug(f"Connecting to 127.0.0.1:{self.audio_port}")
            self.audio_socket.connect(("127.0.0.1", self.audio_port))
            self.logger.info("Connected to AudioServer.")
            self._read_audio_header()

        except socket.timeout:
            self.logger.error("Connection timed out - AudioServer may not be ready.")
            sys.exit(1)
        except socket.error as e:
            self.logger.error(f"Connection failed: {e}")
            sys.exit(1)

    def _read_audio_header(self):
        """Read and parse the 6-byte audio configuration header from the server."""        
        self.logger.debug("Waiting for 6-byte configuration header...")
        header = self.audio_socket.recv(6)
        if len(header) < 6:
            self.logger.error(f"Incomplete header ({len(header)}/6 bytes).")
            if header:
                self.logger.debug(f"Partial header (hex): {header.hex()}")
            sys.exit(1)

        self.logger.debug(f"Header (hex): {header.hex()}")

        # Parse header (big-endian format)
        self.sample_rate = struct.unpack('>I', header[0:4])[0]  # Big-endian int
        self.channels = header[4]
        self.audio_format = header[5]

        self.logger.info("Audio configuration received:")
        self.logger.info(f"  Sample Rate: {self.sample_rate} Hz")
        self.logger.info(f"  Channels: {self.channels}")
        self.logger.info(f"  Format: {self.audio_format} (2=PCM 16-bit)")

    def _stream(self):
        """Stream audio from device to desktop."""
        self.logger.info("Setting up audio playback...")

        try:
            self.pyaudio_instance = pyaudio.PyAudio()
            self.audio_stream = self.pyaudio_instance.open(
                format=pyaudio.paInt16,
                channels=self.channels,
                rate=self.sample_rate,
                output=True,
                frames_per_buffer=4096
            )
        except Exception as e:
            self.logger.error(f"Failed to open audio output: {e}")
            sys.exit(1)

        self.logger.info("Streaming audio... Press Ctrl+C to stop.")

        bytes_received = 0
        last_logged_bytes = 0
        log_interval      = 100 * 1024  # log a progress line every 100 KB
        try:
            while self.running:
                data = self.audio_socket.recv(4096)
                if not data:
                    self.logger.info("Connection closed by device.")
                    break

                self.audio_stream.write(data)
                bytes_received += len(data)

                if bytes_received - last_logged_bytes >= log_interval:
                    last_logged_bytes = bytes_received
                    self.logger.debug(f"Received: {bytes_received / (1024 * 1024):.2f} MB")

        except KeyboardInterrupt:
            self.logger.info("Interrupted by user.")
        except socket.timeout:
            self.logger.error("Socket timed out while waiting for audio data.")
        except socket.error as e:
            self.logger.error(f"Socket error during playback: {e}")
        except Exception as e:
            self.logger.error(f"Unexpected error during playback: {e}")

        self.logger.info(
            f"Stream ended. Total received: {bytes_received / (1024 * 1024):.2f} MB"
        )

    def cleanup(self):
        """Terminate the server process and release all resources."""
        self.logger.info("Cleaning up resources...")

        self.running = False
        self.metadata_running.clear()

        if self._logcat_process:
            try:
                self._logcat_process.terminate()
            except Exception as e:
                self.logger.debug(f"Could not terminate logcat process: {e}")

        if self.server_process:
            self.logger.debug("Terminating sndsync server...")
            try:
                self.server_process.terminate()
                self.server_process.wait(timeout=5)
                self.logger.debug("Server terminated.")
            except subprocess.TimeoutExpired:
                self.logger.debug("Server did not exit, sending SIGKILL...")
                try:
                    self.server_process.kill()
                    self.logger.debug("Server killed")
                except Exception as e:
                    self.logger.debug(f"Could not kill server: {e}")
            except Exception as e:
                self.logger.debug(f"Error terminating server: {e}")

        if self.audio_stream:
            try:
                self.logger.debug("Stopping audio stream...")
                self.audio_stream.stop_stream()
                self.audio_stream.close()
            except Exception as e:
                self.logger.debug(f"Error stopping audio stream: {e}")
        
        if self.pyaudio_instance:
            try:
                self.logger.debug("Terminating PyAudio...")
                self.pyaudio_instance.terminate()
            except Exception as e:
                self.logger.debug(f"Error terminating PyAudio: {e}")
        
        if self.audio_socket:
            try:
                self.logger.debug("Closing audio socket...")
                self.audio_socket.close()
            except Exception as e:
                self.logger.debug(f"Error closing audio socket: {e}")

        if self.metadata_socket:
            try:
                self.logger.debug("Closing metadata socket...")
                self.metadata_socket.close()
            except Exception as e:
                self.logger.debug(f"Error closing metadata socket: {e}")
        
        if self.jar_path:
            try:
                self.logger.debug(f"Removing port forwarding for metadata port {self.meta_port}...")
                subprocess.run(
                    self.adb_cmd + ["forward", "--remove", f"tcp:{self.meta_port}"],
                    capture_output=True,
                    timeout=5
                )
            except Exception as e:
                self.logger.debug(f"Error removing metadata port forwarding: {e}")

            try:
                self.logger.debug(f"Removing port forwarding for audio port {self.audio_port}...")
                subprocess.run(
                    self.adb_cmd + ["forward", "--remove", f"tcp:{self.audio_port}"],
                    capture_output=True,
                    timeout=5
                )
            except Exception as e:
                self.logger.debug(f"Error removing audio port forwarding: {e}")

        self.smtc.clear()


def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description="Stream audio and/or metadata from Android device to desktop"
    )
    parser.add_argument(
        "-j", "--jar",
        help="Path to sndsync-server.jar file"
    )
    parser.add_argument(
        "-ap", "--audio-port",
        type=int,
        default=9999,
        help="Local TCP port for audio stream forwarding (default: 9999)"
    )
    parser.add_argument(
        "-mp", "--metadata-port",
        type=int,
        default=9998,
        help="Local TCP port for metadata stream forwarding (default: 9998)"
    )
    parser.add_argument(
        "-s", "--serial",
        help="ADB device serial (required when multiple devices are connected).",
    )
    parser.add_argument(
        "--no-audio",
        action="store_true",
        help="Disable audio streaming",
    )
    parser.add_argument(
        "--no-metadata",
        action="store_true",
        help="Disable metadata streaming",
    )
    parser.add_argument(
        "--stereo",
        action="store_true",
        help="Record in stereo instead of mono"
    )
    parser.add_argument(
        "-d", "--debug",
        action="store_true",
        help="Enable debug logging"
    )
    
    args = parser.parse_args()

    try:
        client = SndsyncClient(
            jar_path=args.jar,
            audio_port=args.audio_port,
            meta_port=args.metadata_port,
            device_serial=args.serial,
            enable_audio=not args.no_audio,
            enable_metadata=not args.no_metadata,
            stereo=args.stereo,
            debug=args.debug
        )
    except ValueError as e:
        print(f"Error: {e}", file=sys.stderr)
        parser.print_help()
        sys.exit(1)
    
    # Setup cleanup on exit
    def signal_handler(sig, frame):
        client.running = False
        client.metadata_running.clear()
    
    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)
    
    try:
        client.run()
    finally:
        client.cleanup()


if __name__ == "__main__":
    main()