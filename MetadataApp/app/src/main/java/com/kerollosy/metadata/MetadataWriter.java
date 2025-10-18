package com.kerollosy.metada;

import android.util.Log;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class MetadataWriter {
    private static final String TAG = "sndsync-meta";
    private static Socket socket = null;
    private static OutputStream out = null;
    private static final int METADATA_PORT = 9998;

    public static void send(String jsonData) {
        new Thread(() -> {
            try {
                if (socket == null || socket.isClosed()) {
                    socket = new Socket("127.0.0.1", METADATA_PORT);
                    out = socket.getOutputStream();
                    Log.d(TAG, "Connected to metadata server");
                }

                byte[] data = jsonData.getBytes(StandardCharsets.UTF_8);
                out.write(data);
                out.flush();

                Log.d(TAG, "Metadata sent: " + jsonData);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send metadata", e);
                socket = null;
            }
        }).start();
    }
}