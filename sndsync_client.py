"""
sndsync - Android Audio Streaming Client
Stream audio from Android devices to desktop in real-time with metadata display.
"""

import subprocess
import socket
import time
import signal
import sys
import argparse
import logging
import struct
import json
import base64
import threading
from pathlib import Path
from typing import Optional
from io import BytesIO

import pyaudio
from PIL import Image
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
    
    def __init__(self, port: int = 9999, device_serial: Optional[str] = None, 
            jar_path: Optional[str] = None, apk_path: Optional[str] = None, 
            debug: bool = False):
        """
        Initialize the sndsync client.
        
        Args:
            port: Local port for audio forwarding
            device_serial: Optional device serial for multiple devices
            jar_path: Path to AudioServer.jar file
            apk_path: Path to MetadataApp.apk file
            debug: Enable debug logging
        """
        self.running = True
        self.port = port
        self.metadata_port = 9998  # Hardcoded for now
        self.device_serial = device_serial
        self.jar_path = Path(jar_path) if jar_path else Path("AudioServer.jar")
        self.apk_path = Path(apk_path) if apk_path else Path("MetadataApp.apk")
        
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
        self.metadata_socket = None
        self.pyaudio_instance = None
        self.audio_stream = None
        self.server_process = None
        
        # Audio configuration (will be set from server header)
        self.sample_rate = None
        self.channels = None
        self.audio_format = None
        
        # Metadata tracking
        self.last_metadata = None
        self.metadata_thread = None

        # Build ADB command prefix
        self.adb_cmd = ["adb"]
        if device_serial:
            self.adb_cmd.extend(["-s", device_serial])
    
    def run(self):
        """Execute the complete streaming workflow."""
        self._check_adb()
        self._check_device()
        self._setup_audio_server()
        
        self.logger.info("Starting audio stream (metadata setup in background)...")
        
        # Start metadata setup in a separate thread so it doesn't block audio streaming
        metadata_thread_setup = threading.Thread(target=self._setup_metadata_in_background, daemon=True)
        metadata_thread_setup.start()
        
        self._connect()
        self._stream()
    
    def _setup_metadata_in_background(self):
        """Setup metadata app and listener in a background thread."""
        try:
            metadata_available = self._setup_metadata_app()
            if metadata_available:
                time.sleep(1)  # Give device time to settle
                self._setup_metadata_forwarding()
                self.metadata_thread = threading.Thread(target=self._metadata_listener, daemon=True)
                self.metadata_thread.start()
        except Exception as e:
            self.logger.debug(f"Background metadata setup error: {e}")
        

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
            if result.stderr:
                self.logger.error(f"ADB error: {result.stderr}")
            sys.exit(1)
        
        if self.device_serial:
            self.logger.info(f"Using device: {self.device_serial}")

    def _setup_audio_server(self):
        """Deploy and start the AudioServer on device."""
        if not self.jar_path.exists():
            self.logger.error(f"AudioServer.jar not found at: {self.jar_path}")
            self.logger.error("Please compile the project first or specify correct path with --jar")
            sys.exit(1)
        
        self.logger.info(f"Pushing {self.jar_path} to device...")
        result = subprocess.run(
            self.adb_cmd + ["push", str(self.jar_path), "/data/local/tmp/AudioServer.jar"],
            capture_output=True, text=True
        )
        
        if result.returncode != 0:
            self.logger.error("Failed to push JAR to device")
            self.logger.error(result.stderr)
            sys.exit(1)
        
        self.logger.info(f"Setting up port forwarding for port {self.port}...")
        result = subprocess.run(
            self.adb_cmd + ["forward", f"tcp:{self.port}", f"tcp:{self.port}"],
            capture_output=True, text=True
        )
        
        if result.returncode != 0:
            self.logger.error("Failed to setup port forwarding")
            self.logger.error(result.stderr)
            sys.exit(1)
        
        self.logger.info("Starting AudioServer on device...")
        # Start the server in background
        self.server_process = subprocess.Popen(
            self.adb_cmd + ["shell", f"CLASSPATH=/data/local/tmp/AudioServer.jar app_process /data/local/tmp/ AudioServer {self.port}"],
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
            self.logger.error("AudioServer failed to start")
            if stdout:
                self.logger.debug(f"Server STDOUT:\n{stdout}")
            if stderr:
                self.logger.error(f"Server STDERR:\n{stderr}")
            sys.exit(1)
        
        self.logger.debug("AudioServer appears to be running")

    def _setup_metadata_app(self):
        """Install and setup the metadata collection app."""
        if not self.apk_path.exists():
            self.logger.warning(f"MetadataApp.apk not found at: {self.apk_path}")
            self.logger.warning("Metadata collection will not be available")
            self.logger.warning("Specify APK path with --apk or place MetadataApp.apk in current directory")
            return False
        
        self.logger.info("Installing metadata app...")
        result = subprocess.run(
            self.adb_cmd + ["install", "-r", str(self.apk_path)],
            capture_output=True, text=True
        )
        
        if result.returncode != 0:
            self.logger.warning("Failed to install metadata app")
            self.logger.debug(result.stderr)
            return False
        
        self.logger.info("Metadata app installed successfully")

        # Notify user about notification permissions
        self.logger.info("=" * 60)
        self.logger.info("IMPORTANT: Please grant notification access to the metadata app!")
        self.logger.info("1. Go to Settings > Apps > Metadata App > Permissions")
        self.logger.info("2. Enable 'Notification access' permission")
        self.logger.info("3. The app will automatically detect music metadata")
        self.logger.info("=" * 60)
        
        return True
    
    def _setup_metadata_forwarding(self):
        """Setup port forwarding for metadata."""
        self.logger.info(f"Setting up port forwarding for metadata port {self.metadata_port}...")
        result = subprocess.run(
            self.adb_cmd + ["forward", f"tcp:{self.metadata_port}", f"tcp:{self.metadata_port}"],
            capture_output=True, text=True
        )
        
        if result.returncode != 0:
            self.logger.warning("Failed to setup metadata port forwarding")
        else:
            self.logger.debug("Metadata port forwarding established")
    
    def _metadata_listener(self):
        """Listen for metadata updates in a separate thread."""
        try:
            self.logger.debug("Starting metadata listener thread...")
            time.sleep(1)  # Give time for connections to establish
            
            self.metadata_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.metadata_socket.settimeout(2.0)
            
            try:
                self.logger.debug(f"Connecting to metadata server on port {self.metadata_port}...")
                self.metadata_socket.connect(("127.0.0.1", self.metadata_port))
                self.logger.info("Connected to metadata server")
            except (socket.timeout, ConnectionRefusedError):
                self.logger.debug("Metadata server not available (this is optional)")
                return
            
            buffer = ""
            while self.running:
                try:
                    data = self.metadata_socket.recv(1024).decode('utf-8')
                    if not data:
                        break
                    
                    buffer += data
                    
                    while '\n' in buffer:
                        line, buffer = buffer.split('\n', 1)
                        if line.strip():
                            try:
                                metadata = json.loads(line)
                                
                                if metadata == self.last_metadata:
                                    continue
                                
                                self.last_metadata = metadata
                                self._display_metadata(metadata)
                                
                            except json.JSONDecodeError:
                                pass
                
                except socket.timeout:
                    continue
                except Exception as e:
                    self.logger.debug(f"Metadata listener error: {e}")
                    break
        
        except Exception as e:
            self.logger.debug(f"Metadata listener thread error: {e}")
        finally:
            if self.metadata_socket:
                try:
                    self.metadata_socket.close()
                except:
                    pass
    
    def _display_metadata(self, metadata):
        """Display metadata in a formatted way."""
        self.logger.info("="*60)
        self.logger.info("Now Playing:")
        self.logger.info(f"  Package: {metadata.get('package', 'Unknown')}")
        self.logger.info(f"  Title:   {metadata.get('title', 'Unknown')}")
        self.logger.info(f"  Artist:  {metadata.get('artist', 'Unknown')}")
        self.logger.info(f"  Album:   {metadata.get('album', 'Unknown')}")
        
        duration = metadata.get('duration', 0)
        if duration:
            minutes = duration // 1000 // 60
            self.logger.info(f"  Duration: {minutes} minutes")
        
        if metadata.get('albumArt'):
            try:
                img_data = base64.b64decode(metadata['albumArt'])
                img = Image.open(BytesIO(img_data))
                img.show()
            except Exception as e:
                self.logger.debug(f"Failed to display album art: {e}")
        
        self.logger.info("="*60)
    
    def _connect(self):
        """Connect to the audio stream."""
        self.logger.info("Connecting to audio stream...")
        
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.socket.settimeout(10)  # 10 second timeout
        
        try:
            self.logger.debug(f"Connecting to 127.0.0.1:{self.port}")
            self.socket.connect(("127.0.0.1", self.port))
            self.logger.info("Connected successfully")
            
            # Read audio header from server
            self._read_audio_header()
                
        except socket.timeout:
            self.logger.error("Connection timed out - server may not be ready")
            sys.exit(1)
        except socket.error as e:
            self.logger.error(f"Connection failed: {e}")
            sys.exit(1)
    
    def _read_audio_header(self):
        """Read and parse audio configuration from server."""
        self.logger.info("Reading audio configuration...")
        
        self.logger.debug("Waiting for 6-byte header...")
        header = self.socket.recv(6)
        if len(header) < 6:
            self.logger.error(f"Could not read complete header (got {len(header)} bytes)")
            if header:
                self.logger.debug(f"Partial header: {header.hex()}")
            sys.exit(1)
        
        self.logger.debug(f"Header bytes: {header.hex()}")
        
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
        
        try:
            self.pyaudio_instance = pyaudio.PyAudio()
            self.audio_stream = self.pyaudio_instance.open(
                format=pyaudio.paInt16,  # 16-bit audio
                channels=self.channels,
                rate=self.sample_rate,
                output=True,
                frames_per_buffer=4096
            )
        except Exception as e:
            self.logger.error(f"Failed to setup audio: {e}")
            sys.exit(1)
        
        self.logger.info("Streaming audio... Press Ctrl+C to stop")
        
        bytes_received = 0
        try:
            while self.running:
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
                self.logger.debug("Terminating server process...")
                self.server_process.terminate()
                self.server_process.wait(timeout=5)
                self.logger.debug("Server process terminated")
            except subprocess.TimeoutExpired:
                self.logger.debug("Server process didn't terminate, killing...")
                try:
                    self.server_process.kill()
                    self.logger.debug("Server process killed")
                except:
                    pass
            except:
                pass

        if self.audio_stream:
            try:
                self.logger.debug("Stopping audio stream...")
                self.audio_stream.stop_stream()
                self.audio_stream.close()
            except:
                pass
        
        if self.pyaudio_instance:
            try:
                self.logger.debug("Terminating PyAudio...")
                self.pyaudio_instance.terminate()
            except:
                pass
        
        if self.socket:
            try:
                self.logger.debug("Closing socket...")
                self.socket.close()
            except:
                pass
        
        try:
            self.logger.debug(f"Removing port forwarding for {self.port}...")
            subprocess.run(self.adb_cmd + ["forward", "--remove", f"tcp:{self.port}"], 
                        capture_output=True, timeout=5)
        except:
            pass
        
        if self.metadata_socket:
            try:
                self.logger.debug("Closing metadata socket...")
                self.metadata_socket.close()
            except:
                pass

            try:
                self.logger.debug(f"Removing port forwarding for metadata {self.metadata_port}...")
                subprocess.run(self.adb_cmd + ["forward", "--remove", f"tcp:{self.metadata_port}"], 
                            capture_output=True, timeout=5)
            except:
                pass


def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description="Stream audio from Android device to desktop with metadata display"
    )
    parser.add_argument(
        "-s", "--serial",
        help="Device serial number (for multiple devices)"
    )
    parser.add_argument(
        "-p", "--port",
        type=int,
        default=9999,
        help="Local port for audio forwarding (default: 9999)"
    )
    parser.add_argument(
        "-j", "--jar",
        help="Path to AudioServer.jar file (default: ./AudioServer.jar)"
    )
    parser.add_argument(
        "-a", "--apk",
        help="Path to MetadataApp.apk file (default: ./MetadataApp.apk)"
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
        jar_path=args.jar,
        apk_path=args.apk,
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