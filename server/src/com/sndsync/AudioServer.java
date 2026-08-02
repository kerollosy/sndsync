package com.sndsync;

import android.util.Log;
import android.os.Build;
import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import android.media.AudioRecord;
import android.media.AudioFormat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class AudioServer {
    private static final String TAG = "SndsyncAudioServer";

    private static final int DEFAULT_PORT = 9999;
    private static final int REMOTE_SUBMIX = 8;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHANNEL_MASK_STEREO = AudioFormat.CHANNEL_IN_LEFT | AudioFormat.CHANNEL_IN_RIGHT;
    private static int sampleRate = 48000;
    private static int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    
    private static AudioRecord recorder;
    private static ActivityManager activityManager;

    public static void main(String[] args) throws Exception {        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.e(TAG, "AudioRecord with REMOTE_SUBMIX source requires Android 11 (API 30) or higher");
            throw new Exception("Unsupported Android version: " + Build.VERSION.SDK_INT);
        }

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid port number: " + args[0] + ", using default: " + port);
            }
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equalsIgnoreCase("--port") && i + 1 < args.length) {
                try {
                    port = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid port number: " + args[i + 1]);
                }
            }
            if (arg.equalsIgnoreCase("--stereo")) {
                channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            } else if (arg.equalsIgnoreCase("--16000")) {
                sampleRate = 16000;
            } else if (arg.equalsIgnoreCase("--44100")) {
                sampleRate = 44100;
            }
        }

        Log.i(TAG, "Starting AudioServer on port: " + port);
        Log.i(TAG, "Config: " + sampleRate + "Hz, " + 
            (channelConfig == AudioFormat.CHANNEL_IN_STEREO ? "Stereo" : "Mono"));

        try {
            startServer(port);
        } catch (Exception e) {
            Log.e(TAG, "FATAL SERVER ERROR: " + e.getMessage());
            releaseAudioRecord();
            System.exit(1);
        }
    }

    public static AudioFormat createAudioFormat() {
        return new AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();
    }

    private static AudioRecord createAudioRecord() throws Exception {
        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, ENCODING);
        Log.i(TAG, "Min buffer size: " + minBufferSize + ", using: " + 8 * minBufferSize);
        
        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(REMOTE_SUBMIX)
                .setAudioFormat(createAudioFormat());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setContext(FakeContext.get());
        }

        if (minBufferSize > 0) {
            builder.setBufferSizeInBytes(8 * minBufferSize);
        }

        return builder.build();
    }

    private static int getChannelCount() {
        return channelConfig == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1;
    }

    private static int getChannelMaskForWorkaround() {
        return channelConfig == AudioFormat.CHANNEL_IN_STEREO ? CHANNEL_MASK_STEREO : AudioFormat.CHANNEL_IN_MONO;
    }

    private static void startWorkaroundAndroid11() {
        if (activityManager == null) {
            activityManager = ActivityManager.create();
        }
        activityManager.forceStopPackage(FakeContext.PACKAGE_NAME);
    }

    private static void stopWorkaroundAndroid11() {
        if (activityManager == null) {
            activityManager = ActivityManager.create();
        }

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(FakeContext.PACKAGE_NAME, "com.android.shell.HeapDumpActivity"));
        activityManager.startActivity(intent);
    }

    private static void tryStartRecording(int attempts, int delayMs) throws Exception {
        while (attempts-- > 0) {
            SystemClock.sleep(delayMs);
            try {
                startRecording();
                return;
            } catch (UnsupportedOperationException e) {
                if (attempts == 0) {
                    Log.e(TAG, "Failed to start audio capture on Android 11 foreground workaround");
                    throw e;
                }
                Log.w(TAG, "Audio capture start failed, retrying...");
            }
        }
    }

    private static void startRecording() throws Exception {
        try {
            recorder = createAudioRecord();
        } catch (NullPointerException e) {
            Log.w(TAG, "AudioRecord.Builder failed, trying workaround constructor", e);
            recorder = Workarounds.createAudioRecord(
                    REMOTE_SUBMIX,
                    sampleRate,
                    channelConfig,
                    getChannelCount(),
                    getChannelMaskForWorkaround(),
                    ENCODING
            );
        }

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new Exception("AudioRecord target state uninitialized. State: " + recorder.getState());
        }
        recorder.startRecording();
    }

    private static void initAudioRecord() throws Exception {
        if (recorder != null) return; // Already initialized

        try {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                startWorkaroundAndroid11();
                try {
                    tryStartRecording(5, 100);
                } finally {
                    stopWorkaroundAndroid11();
                }
            } else {
                startRecording();
            }

            Log.i(TAG, "AudioRecord started successfully.");
        } catch (Exception e) {
            releaseAudioRecord();
            throw e;
        }
    }

    private static void releaseAudioRecord() {
        if (recorder != null) {
            try {
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop();
                }
                recorder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing AudioRecord: " + e.getMessage());
            } finally {
                recorder = null;
            }
        }
    }

    private static void startServer(int port) throws Exception {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new java.net.InetSocketAddress(port));

            while (true) {
                Socket clientSocket = serverSocket.accept();
                Log.i(TAG, "Client paired: " + clientSocket.getInetAddress());
                handleClient(clientSocket);
            }
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (Socket socket = clientSocket; 
             OutputStream out = socket.getOutputStream()) {
            
            initAudioRecord();
            sendHeader(out);

            byte[] buffer = new byte[4096];
            long bytesStreamed = 0;

            while (true) {
                int bytesRead = recorder.read(buffer, 0, buffer.length);
                
                if (bytesRead > 0) {
                    out.write(buffer, 0, bytesRead);
                    out.flush();
                    bytesStreamed += bytesRead;
                } else if (bytesRead < 0) {
                    Log.e(TAG, "Audio hardware read error code: " + bytesRead);
                    break;
                }
            }
            Log.i(TAG, "Stream closed safely. Data processed: " + (bytesStreamed / 1024) + " KB");
            
        } catch (IOException e) {
            Log.i(TAG, "Client disconnected or network dropped: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Internal handling error: " + e.getMessage());
        } finally {
            releaseAudioRecord();
            Log.i(TAG, "Client cleaned up and audio recording stopped.");
        }
    }

    private static void sendHeader(OutputStream out) throws Exception {
        byte[] header = new byte[6];
        header[0] = (byte) ((sampleRate >> 24) & 0xFF);
        header[1] = (byte) ((sampleRate >> 16) & 0xFF);
        header[2] = (byte) ((sampleRate >> 8) & 0xFF);
        header[3] = (byte) (sampleRate & 0xFF);
        header[4] = (byte) (channelConfig == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1);
        header[5] = (byte) (ENCODING == AudioFormat.ENCODING_PCM_16BIT ? 2 : 1);
        
        out.write(header);
        out.flush();
    }
}