package com.kerollosy.metadata;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MetadataWriter {
    private static final String TAG = "sndsync-meta";
    private static final int PORT = 9998;

    private static ServerSocket serverSocket;
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static Socket clientSocket;
    private static OutputStream outputStream;
    private static boolean serverStarted = false;

    public static void startServer() {
        synchronized (MetadataWriter.class) {
            if (serverStarted) {
                Log.d(TAG, "Server already started");
                return;
            }
            serverStarted = true;
        }

        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.d(TAG, "Socket server started on port " + PORT);

                while (true) {
                    Socket socket = serverSocket.accept();
                    Log.d(TAG, "Client connected");
                    synchronized (MetadataWriter.class) {
                        clientSocket = socket;
                        outputStream = socket.getOutputStream();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Server error", e);
            }
        });
    }

    public static void send(String metadata) {
        executor.execute(() -> {
            synchronized (MetadataWriter.class) {
                if (clientSocket == null || !clientSocket.isConnected() || outputStream == null) {
                    Log.d(TAG, "No client connected, metadata not sent");
                    return;
                }

                try {
                    outputStream.write((metadata + "\n").getBytes());
                    outputStream.flush();
                    Log.d(TAG, "Metadata sent to client");
                } catch (IOException e) {
                    Log.e(TAG, "Failed to send metadata", e);
                    try {
                        clientSocket.close();
                        clientSocket = null;
                        outputStream = null;
                    } catch (IOException ex) {
                        Log.e(TAG, "Error closing socket", ex);
                    }
                }
            }
        });
    }
}