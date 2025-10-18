package com.kerollosy.metadata;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MetadataWriter {
    private static final String TAG = "sndsync-meta";
    private static volatile OutputStream out = null;
    private static final Object LOCK = new Object();
    private static final int METADATA_PORT = 9998;

    static void setOutput(OutputStream o) {
        synchronized (LOCK) {
            out = o;
        }
    }

    static void clearOutput() {
        synchronized (LOCK) {
            out = null;
        }
    }

    public static void send(String jsonData) {
        synchronized (LOCK) {
            if (out == null) {
                Log.w(TAG, "MetadataWriter.send() called but out == null");
                return;
            }
            try {
                byte[] data = (jsonData + "\n").getBytes(StandardCharsets.UTF_8);
                out.write(data);
                out.flush();
                Log.d(TAG, "Metadata sent: " + jsonData);
            } catch (IOException e) {
                Log.e(TAG, "Failed to write metadata", e);
                try { out.close(); } catch (IOException ignored) {}
                out = null;
            }
        }
    }

    public static void startServer() {
        new Thread(() -> {
            try {
                Log.i(TAG, "Opening metadata socket on port " + METADATA_PORT);
                LocalServerSocket  metaServer = new LocalServerSocket(TAG);
                try (LocalSocket metaSocket = metaServer.accept()) {
                    Log.i(TAG, "Metadata socket accepted connection!");
                    setOutput(metaSocket.getOutputStream());

                    // Keep thread alive until socket closes
                    byte[] tmp = new byte[1];
                    while (metaSocket.getInputStream().read(tmp) != -1) {
                        // Ignore input; this is only to detect disconnect
                    }
                } finally {
                    clearOutput();
                    Log.i(TAG, "Metadata socket disconnected");
                    try { metaServer.close(); } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                Log.w(TAG, "Metadata socket error", e);
            }
        }).start();
    }
}