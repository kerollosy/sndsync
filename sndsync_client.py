#!/usr/bin/env python3
"""
sndsync - Android Audio Streaming Client
Stream audio from Android devices to desktop in real-time.
"""

import subprocess
import socket
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

init(autoreset=True)


class ColoredFormatter(logging.Formatter):
    """Custom formatter for colored log output."""
    
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
    
    def __init__(self, port: int = 9999, device_serial: Optional[str] = None, debug: bool = False):
        """
        Initialize the sndsync client.
        
        Args:
            port: Local port for audio forwarding
            device_serial: Optional device serial for multiple devices
            debug: Enable debug logging
        """
        self.running = True
        self.port = port
        self.device_serial = device_serial
        
        # Setup logging
        self.logger = logging.getLogger("sndsync")
        self.logger.setLevel(logging.DEBUG if debug else logging.INFO)
        
        handler = logging.StreamHandler()
        handler.setFormatter(ColoredFormatter(
            fmt='%(asctime)s [%(levelname)s] %(message)s',
            datefmt='%Y-%m-%d %H:%M:%S'
        ))
        self.logger.addHandler(handler)
        self.logger.propagate = False
        
        # Resources
        self.socket = None
        self.pyaudio_instance = None
        self.audio_stream = None
        
        # Audio configuration (will be set from server header)
        self.sample_rate = None
        self.channels = None
        self.audio_format = None

        # Build ADB command prefix
        self.adb_cmd = ["adb"]
        if device_serial:
            self.adb_cmd.extend(["-s", device_serial])
    
    def run(self):
        """Execute the complete streaming workflow."""
        self._check_adb()
        self._check_device()
        self._setup_audio_server()
        self._connect()
        self._stream()

    def _check_adb(self):
        """Verify ADB is installed and accessible."""
        self.logger.info("Checking ADB installation...")
        try:
            result = subprocess.run(["adb", "version"], capture_output=True, text=True)
            if result.returncode != 0:
                raise FileNotFoundError
            self.logger.debug(f"ADB version: {result.stdout.strip()}")
        except FileNotFoundError:
            self.logger.error("ADB not found. Please install ADB and ensure it's in your PATH.")
            sys.exit(1)
    
    def _check_device(self):
        """Verify device is connected."""
        self.logger.info("Checking device connection...")
        result = subprocess.run(self.adb_cmd + ["get-state"], capture_output=True, text=True)
        
        if "device" not in result.stdout:
            self.logger.error("No device connected")
            sys.exit(1)
        
        if self.device_serial:
            self.logger.info(f"Using device: {self.device_serial}")
    
    def _setup_audio_server(self):
        """Deploy and start the AudioServer on device."""
        jar_path = Path("lib/AudioServer.jar")
        
        if not jar_path.exists():
            self.logger.error("AudioServer.jar not found in lib/ directory")
            self.logger.error("Please compile the project first using the build script")
            sys.exit(1)
        
        self.logger.info("Pushing AudioServer.jar to device...")
        result = subprocess.run(
            self.adb_cmd + ["push", str(jar_path), "/data/local/tmp/AudioServer.jar"],
            capture_output=True, text=True
        )
        
        if result.returncode != 0:
            self.logger.error("Failed to push JAR to device")
            sys.exit(1)
        
        self.logger.info(f"Setting up port forwarding for port {self.port}...")
        subprocess.run(
            self.adb_cmd + ["forward", f"tcp:{self.port}", f"tcp:{self.port}"],
            capture_output=True
        )
        
        self.logger.info("Starting AudioServer on device...")
        # Start the server in background
        self.server_process = subprocess.Popen(
            self.adb_cmd + ["shell", f"CLASSPATH=/data/local/tmp/AudioServer.jar app_process /data/local/tmp/ AudioServer {self.port}"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )
        
        # Wait a moment for server to start
        time.sleep(2)
    
    def _connect(self):
        """Connect to the audio stream."""
        self.logger.info("Connecting to audio stream...")
        
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            self.socket.connect(("127.0.0.1", self.port))
            self.logger.info("Connected successfully")
            
            # Read audio header from server
            self._read_audio_header()
                
        except socket.error as e:
            self.logger.error(f"Connection failed: {e}")
            sys.exit(1)
    
    def _read_audio_header(self):
        """Read and parse audio configuration from server."""
        self.logger.info("Reading audio configuration...")
        
        header = self.socket.recv(6)
        if len(header) < 6:
            self.logger.error("Could not read complete header")
            sys.exit(1)
        
        # Parse header (big-endian format)
        self.sample_rate = struct.unpack('>I', header[0:4])[0]  # Big-endian int
        self.channels = header[4]
        self.audio_format = header[5]
        
        self.logger.info(f"Audio Configuration:")
        self.logger.info(f"  Sample Rate: {self.sample_rate} Hz")
        self.logger.info(f"  Channels: {self.channels}")
        self.logger.info(f"  Format: {self.audio_format} (2=PCM 16-bit)")
    
    def _stream(self):
        """Stream audio from device to desktop."""
        self.logger.info("Setting up audio playback...")
        
        self.pyaudio_instance = pyaudio.PyAudio()
        self.audio_stream = self.pyaudio_instance.open(
            format=pyaudio.paInt16,  # 16-bit audio
            channels=self.channels,
            rate=self.sample_rate,
            output=True,
            frames_per_buffer=4096
        )
        
        self.logger.info("Streaming audio... Press Ctrl+C to stop")
        
        bytes_received = 0
        try:
            while True:
                data = self.socket.recv(4096)
                if not data:
                    self.logger.info("Connection closed by device")
                    break
                
                self.audio_stream.write(data)
                bytes_received += len(data)
                
                # Print progress every 100KB
                if bytes_received % 102400 == 0:
                    mb_received = bytes_received / (1024 * 1024)
                    self.logger.debug(f"Received: {mb_received:.2f} MB")
                    
        except KeyboardInterrupt:
            self.logger.info("Stopping...")
        except socket.timeout:
            self.logger.error("Socket timeout while waiting for data")
        except socket.error as e:
            self.logger.error(f"Socket error: {e}")
        except Exception as e:
            self.logger.error(f"Error during playback: {e}")
        
        total_mb = bytes_received / (1024 * 1024)
        self.logger.info(f"Total received: {bytes_received} bytes ({total_mb:.2f} MB)")
    
    def cleanup(self):
        """Release all resources."""
        self.logger.info("Cleaning up resources...")
        
        self.running = False
        
        if hasattr(self, 'server_process') and self.server_process:
            try:
                self.server_process.terminate()
                self.server_process.wait(timeout=5)
            except:
                pass

        if self.audio_stream:
            try:
                self.audio_stream.stop_stream()
                self.audio_stream.close()
            except:
                pass
        
        if self.pyaudio_instance:
            try:
                self.pyaudio_instance.terminate()
            except:
                pass
        
        if self.socket:
            try:
                self.socket.close()
            except:
                pass


def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description="Stream audio from Android device to desktop"
    )
    parser.add_argument(
        "-s", "--serial",
        help="Device serial number (for multiple devices)"
    )
    parser.add_argument(
        "-p", "--port",
        type=int,
        default=9999,
        help="Local port for forwarding (default: 9999)"
    )
    parser.add_argument(
        "-d", "--debug",
        action="store_true",
        help="Enable debug logging"
    )
    
    args = parser.parse_args()
    
    client = SndsyncClient(
        port=args.port,
        device_serial=args.serial,
        debug=args.debug
    )
    
    # Setup cleanup on exit
    def signal_handler(sig, frame):
        client.cleanup()
        sys.exit(0)
    
    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)
    
    try:
        client.run()
    finally:
        client.cleanup()


if __name__ == "__main__":
    main()