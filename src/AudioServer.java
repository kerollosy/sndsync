import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;

public class AudioServer {
    private static final int SAMPLE_RATE = 48000;
    private static final int REMOTE_SUBMIX = 8;
    
    private static Object audioRecord;
    private static volatile boolean isRunning = true;

    public static void main(String[] args) throws Exception {
        int port = 9999;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("[AudioServer] Invalid port, using default: 9999");
            }
        }

        System.out.println("[AudioServer] Starting audio server on port " + port);
        
        try {
            initAudioRecord();
            startServer(port);
        } catch (Exception e) {
            System.out.println("[AudioServer] FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void initAudioRecord() throws Exception {
        try {
            Class<?> audioFormatClass = Class.forName("android.media.AudioFormat");
            Class<?> audioRecordClass = Class.forName("android.media.AudioRecord");
            
            // Get constants from AudioFormat
            int CHANNEL_IN_MONO = audioFormatClass.getField("CHANNEL_IN_MONO").getInt(null);
            int ENCODING_PCM_16BIT = audioFormatClass.getField("ENCODING_PCM_16BIT").getInt(null);
            
            System.out.println("[AudioServer] Audio config: rate=" + SAMPLE_RATE + 
                            " channels=" + CHANNEL_IN_MONO + " encoding=" + ENCODING_PCM_16BIT);
            
            // Get minimum buffer size
            Method getMinBufferSize = audioRecordClass.getMethod("getMinBufferSize", 
                int.class, int.class, int.class);
            int minBufferSize = (int) getMinBufferSize.invoke(null, SAMPLE_RATE, CHANNEL_IN_MONO, ENCODING_PCM_16BIT);
            int bufferSize = minBufferSize * 2;
            
            System.out.println("[AudioServer] Min buffer size: " + minBufferSize + ", using: " + bufferSize);
            
            // Create AudioRecord with REMOTE_SUBMIX source
            audioRecord = audioRecordClass.getConstructor(
                int.class, int.class, int.class, int.class, int.class
            ).newInstance(REMOTE_SUBMIX, SAMPLE_RATE, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, bufferSize);
            
            // Check initialization state
            Method getState = audioRecordClass.getMethod("getState");
            int state = (int) getState.invoke(audioRecord);
            
            if (state != 1) { // STATE_INITIALIZED = 1
                throw new Exception("AudioRecord initialization failed. State: " + state);
            }
            
            // Start recording
            Method startRecording = audioRecordClass.getMethod("startRecording");
            startRecording.invoke(audioRecord);
            
            System.out.println("[AudioServer] AudioRecord started successfully with REMOTE_SUBMIX source");
            
        } catch (Exception e) {
            System.out.println("[AudioServer] AudioRecord init error: " + e.getMessage());
            throw e;
        }
    }

    private static void startServer(int port) throws Exception {
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("[AudioServer] Server listening on port " + port);

        while (isRunning) {
            Socket clientSocket = serverSocket.accept();
            System.out.println("[AudioServer] Client connected: " + clientSocket.getInetAddress());
            
            new Thread(() -> handleClient(clientSocket)).start();
        }

        serverSocket.close();
    }

    private static void handleClient(Socket clientSocket) {
        try {
            OutputStream out = clientSocket.getOutputStream();
            byte[] buffer = new byte[4096];

            // Send configuration header
            sendHeader(out);
            System.out.println("[AudioServer] Header sent to client");

            // Get the read method
            Class<?> audioRecordClass = Class.forName("android.media.AudioRecord");
            Method read = audioRecordClass.getMethod("read", byte[].class, int.class, int.class);

            System.out.println("[AudioServer] Starting audio stream...");
            long bytesStreamed = 0;

            while (isRunning && clientSocket.isConnected()) {
                try {
                    int bytesRead = (int) read.invoke(audioRecord, buffer, 0, buffer.length);
                    
                    if (bytesRead > 0) {
                        out.write(buffer, 0, bytesRead);
                        out.flush();
                        bytesStreamed += bytesRead;
                        
                        // Log progress every ~1 second (48KB at 48kHz * 16-bit)
                        if (bytesStreamed % 102400 < 4096) {
                            System.out.println("[AudioServer] Streamed: " + (bytesStreamed / 1024) + " KB");
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[AudioServer] Error during read: " + e.getMessage());
                    break;
                }
            }
            
            System.out.println("[AudioServer] Client stream ended. Total: " + (bytesStreamed / 1024) + " KB");
            
        } catch (Exception e) {
            System.out.println("[AudioServer] Client error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
                System.out.println("[AudioServer] Client disconnected");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void sendHeader(OutputStream out) throws Exception {
        byte[] header = new byte[6];
        
        // Sample rate (48000) as big-endian int
        header[0] = (byte) ((SAMPLE_RATE >> 24) & 0xFF);
        header[1] = (byte) ((SAMPLE_RATE >> 16) & 0xFF);
        header[2] = (byte) ((SAMPLE_RATE >> 8) & 0xFF);
        header[3] = (byte) (SAMPLE_RATE & 0xFF);
        
        // Channels (1 for mono)
        header[4] = 1;
        
        // Format (2 for PCM 16-bit)
        header[5] = 2;
        
        out.write(header);
        out.flush();
    }
}