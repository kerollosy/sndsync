package com.audioserver;

import com.audioserver.Workarounds;

import android.os.Build;
import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.AudioFormat;
import android.os.Looper;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;

public class AudioServer {
    private static final int SAMPLE_RATE = 48000;
    private static final int REMOTE_SUBMIX = 8;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNELS = 2;
    public static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_LEFT | AudioFormat.CHANNEL_IN_RIGHT;
    public static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    
    private static AudioRecord recorder;
    private static volatile boolean isRunning = true;

    private static ActivityManager activityManager = null;

    public static void main(String[] args) throws Exception {
        prepareMainLooper();

        int port = 9999;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("[AudioServer] Invalid port, using default: 9999");
            }
        }
        System.out.println("[AudioServer] Starting audio server on port " + port);

        new AudioServer().start(port);
    }

    private static void prepareMainLooper() {
        // Like Looper.prepareMainLooper(), but with quitAllowed set to true
        Looper.prepare();
        synchronized (Looper.class) {
            try {
                Field field = Looper.class.getDeclaredField("sMainLooper");
                field.setAccessible(true);
                field.set(null, Looper.myLooper());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    private void start(int port) throws Exception {
        Workarounds.apply();

        // if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            System.out.println("DETECTED ANDROID 11");
            startWorkaroundAndroid11();
            try {
                tryStartRecording(5, 100);
            } finally {
                stopWorkaroundAndroid11();
            }
        // } else {
        //     startRecording();
        // }

        try {
            // initAudioRecord();
            startServer(port);
        } catch (Exception e) {
            System.out.println("[AudioServer] FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static AudioFormat createAudioFormat() {
        AudioFormat.Builder builder = new AudioFormat.Builder();
        builder.setEncoding(ENCODING);
        builder.setSampleRate(SAMPLE_RATE);
        builder.setChannelMask(CHANNEL_CONFIG);
        return builder.build();
    }

    private static void startWorkaroundAndroid11() {
        // Android 11 requires Apps to be at foreground to record audio.
        // Normally, each App has its own user ID, so Android checks whether the requesting App has the user ID that's at the foreground.
        // But scrcpy server is NOT an App, it's a Java application started from Android shell, so it has the same user ID (2000) with Android
        // shell ("com.android.shell").
        // If there is an Activity from Android shell running at foreground, then the permission system will believe scrcpy is also in the
        // foreground.
        if (activityManager == null) {
            activityManager = ActivityManager.create();
        }
        activityManager.forceStopPackage(FakeContext.PACKAGE_NAME);
    }

    private static void stopWorkaroundAndroid11() {
        // Android 11 requires Apps to be at foreground to record audio.
        // Normally, each App has its own user ID, so Android checks whether the requesting App has the user ID that's at the foreground.
        // But scrcpy server is NOT an App, it's a Java application started from Android shell, so it has the same user ID (2000) with Android
        // shell ("com.android.shell").
        // If there is an Activity from Android shell running at foreground, then the permission system will believe scrcpy is also in the
        // foreground.
        if (activityManager == null) {
            activityManager = ActivityManager.create();
        }
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(FakeContext.PACKAGE_NAME, "com.android.shell.HeapDumpActivity"));
        activityManager.startActivity(intent);
    }

    private void tryStartRecording(int attempts, int delayMs) throws Exception {
        while (attempts-- > 0) {
            // Wait for activity to start
            SystemClock.sleep(delayMs);
            try {
                startRecording();
                return; // it worked
            } catch (UnsupportedOperationException e) {
                if (attempts == 0) {
                    System.err.println("Failed to start audio capture");
                    System.err.println("On Android 11, audio capture must be started in the foreground, make sure that the device is unlocked when starting "
                            + "scrcpy.");
                    throw new Exception();
                } else {
                    System.err.println("Failed to start audio capture, retrying...");
                }
            }
        }
    }

    private void startRecording() throws Exception {
        try {
            recorder = createAudioRecord();
        } catch (NullPointerException e) {
            // Creating an AudioRecord using an AudioRecord.Builder does not work on Vivo phones:
            // - <https://github.com/Genymobile/scrcpy/issues/3805>
            // - <https://github.com/Genymobile/scrcpy/pull/3862>
            recorder = Workarounds.createAudioRecord(REMOTE_SUBMIX, SAMPLE_RATE, CHANNEL_CONFIG, CHANNELS, CHANNEL_MASK, ENCODING);
        }
        recorder.startRecording();
    }

    private static AudioRecord createAudioRecord() throws Exception {
        AudioRecord.Builder builder = new AudioRecord.Builder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // On older APIs, Workarounds.fillAppInfo() must be called beforehand
            builder.setContext(FakeContext.get());
        }
        builder.setAudioSource(REMOTE_SUBMIX);
        builder.setAudioFormat(createAudioFormat());
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
        System.out.println("[AudioServer] Min buffer size: " + minBufferSize + ", using: " + 8 * minBufferSize);
        if (minBufferSize > 0) {
            // This buffer size does not impact latency
            builder.setBufferSizeInBytes(8 * minBufferSize);
        }

        return builder.build();
        // Class<?> audioRecordClass = Class.forName("android.media.AudioRecord");

        // AudioRecord lrecorder = (AudioRecord) audioRecordClass
        // .getConstructor(int.class, int.class, int.class, int.class, int.class)
        // .newInstance(REMOTE_SUBMIX, SAMPLE_RATE, CHANNEL_CONFIG, ENCODING, minBufferSize * 8);

        // return lrecorder;
    }

    private static void initAudioRecord() throws Exception {
        try {
            System.out.println("[AudioServer] Audio config: rate=" + SAMPLE_RATE + 
                            " channels=" + CHANNEL_CONFIG + " encoding=" + ENCODING);
            // Create AudioRecord with REMOTE_SUBMIX source
            try {
                recorder = createAudioRecord();
            } catch (Exception e) {
                System.err.println("[AudioServer] Could not create AudioRecord using AudioRecord.Builder: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }

            // Check initialization state
            int state = recorder.getState();
            
            if (state != 1) { // STATE_INITIALIZED = 1
                throw new Exception("AudioRecord initialization failed. State: " + state);
            }
            
            // Start recording
            recorder.startRecording();

            System.out.println("[AudioServer] AudioRecord started successfully with REMOTE_SUBMIX source");
            
        } catch (Exception e) {
            System.out.println("[AudioServer] AudioRecord init error: " + e.getMessage());
            throw e;
        }
    }

    private void startServer(int port) throws Exception {
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
                    int bytesRead = (int) read.invoke(recorder, buffer, 0, buffer.length);
                    
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