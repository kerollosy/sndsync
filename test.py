import base64
from io import BytesIO
import subprocess
import socket
import json
import sys
import time
from PIL import Image

def run_adb_command(cmd):
    """Execute an adb command and return output"""
    try:
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
        return result.stdout.strip()
    except Exception as e:
        print(f"Error running adb command: {e}")
        return None

def setup_port_forward():
    """Set up port forwarding via adb"""
    print("Setting up port forwarding...")
    output = run_adb_command("adb forward tcp:9998 tcp:9998")
    if output is not None:
        print(f"Port forwarding established: {output}")
        return True
    else:
        print("Failed to set up port forwarding")
        return False

def connect_and_listen():
    """Connect to the metadata server and listen for updates"""
    host = 'localhost'
    port = 9998
    
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(1.0)  # 1 second timeout to allow KeyboardInterrupt
        print(f"Connecting to {host}:{port}...")
        sock.connect((host, port))
        print("Connected to metadata server!")
        
        buffer = ""
        while True:
            try:
                data = sock.recv(1024).decode('utf-8')
                if not data:
                    print("Connection closed by server")
                    break
                
                buffer += data
                
                # Process complete JSON objects (newline-separated)
                while '\n' in buffer:
                    line, buffer = buffer.split('\n', 1)
                    if line.strip():
                        try:
                            metadata = json.loads(line)
                            print("\n" + "="*50)
                            print("Now Playing:")
                            print(f"  Package: {metadata.get('package', 'Unknown')}")
                            print(f"  Title:   {metadata.get('title', 'Unknown')}")
                            print(f"  Artist:  {metadata.get('artist', 'Unknown')}")
                            print(f"  Album:   {metadata.get('album', 'Unknown')}")
                            print(f"  Duration: {metadata.get('duration', 'Unknown') // 1000 // 60} minutes")
                            if metadata.get('albumArt'):
                                img_data = base64.b64decode(metadata['albumArt'])
                                img = Image.open(BytesIO(img_data))
                                img.show()  # Display the image
                            print("="*50)
                        except json.JSONDecodeError as e:
                            print(f"Failed to parse JSON: {e}")
                            print(f"Raw data: {line}")
            
            except socket.timeout:
                print(f"Error receiving data: {e}")
                continue
    
    except ConnectionRefusedError:
        print(f"Failed to connect to {host}:{port}")
        print("Make sure the app is running and port forwarding is active")
    except Exception as e:
        print(f"Connection error: {e}")
    finally:
        sock.close()
        print("Disconnected")

def main():
    print("Sndsync Metadata Client")
    print("-" * 50)
    
    if not setup_port_forward():
        sys.exit(1)
    
    time.sleep(1)  # Give port forwarding time to establish
    
    try:
        connect_and_listen()
    except KeyboardInterrupt:
        print("\nExiting...")
        sys.exit(0)

if __name__ == "__main__":
    main()