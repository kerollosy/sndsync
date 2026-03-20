package com.audioserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaController.PlaybackInfo;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

public class MetaServer {
    private static final String PACKAGE_NAME = "com.android.shell";
    private static final int DEFAULT_PORT = 9998;

    private static final Set<PrintWriter> clients = new CopyOnWriteArraySet<>();

    private static FakeContext context;
    private static Handler mainHandler;
    private static MediaSessionManager sessionManager;

    private static MediaController trackedController;
    private static MediaController.Callback activeCallback;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.out.println("[MetaServer] Starting");

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("[MetaServer] Invalid port, using default: " + DEFAULT_PORT);
            }
        }

        prepareMainLooper();
        Workarounds.apply();

        context = FakeContext.get();
        mainHandler = new Handler(Looper.getMainLooper());

        initSessionTracking();

        final int finalPort = port;
        new Thread(() -> {
            try {
                startServer(finalPort);
            } catch (IOException e) {
                System.out.println("[MetaServer] Server error: " + e.getMessage());
                System.exit(1);
            }
        }, "tcp-server").start();

        Looper.loop();
    }

    // -------------------------------------------------------------------------
    // Looper bootstrap
    // -------------------------------------------------------------------------

    private static void prepareMainLooper() {
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

    // -------------------------------------------------------------------------
    // Session tracking
    // -------------------------------------------------------------------------

    private static void initSessionTracking() {
        sessionManager = (MediaSessionManager) context.getSystemService("media_session");
        if (sessionManager == null) {
            System.out.println("[MetaServer] MediaSessionManager unavailable");
            return;
        }

        ComponentName listenerComponent = new ComponentName(
                PACKAGE_NAME, PACKAGE_NAME + ".NotificationListener");

        sessionManager.addOnActiveSessionsChangedListener(controllers -> {
            System.out.println("[MetaServer] Sessions changed: "
                    + (controllers != null ? controllers.size() : 0) + " active");
            pickBestSession(controllers);
        }, listenerComponent, mainHandler);

        try {
            List<MediaController> initial = sessionManager.getActiveSessions(listenerComponent);
            pickBestSession(initial);
        } catch (SecurityException e) {
            System.out.println("[MetaServer] No notification-listener permission; waiting passively");
        }
    }

    private static void pickBestSession(List<MediaController> controllers) {
        MediaController best = (controllers != null && !controllers.isEmpty())
                ? controllers.get(0) : null;

        if (best != null && trackedController != null
                && best.getSessionToken().equals(trackedController.getSessionToken())) {
            return;
        }

        if (trackedController != null && activeCallback != null) {
            trackedController.unregisterCallback(activeCallback);
            trackedController = null;
            activeCallback = null;
        }

        if (best == null) {
            broadcast(buildEvent("session", "\"package\":null"));
            return;
        }

        trackedController = best;
        System.out.println("[MetaServer] Tracking session: " + best.getPackageName());
        broadcast(buildEvent("session", "\"package\":\"" + best.getPackageName() + "\""));

        pushMetadata(best.getMetadata());
        pushPlaybackState(best.getPlaybackState());
        pushVolume(best.getPlaybackInfo());

        activeCallback = new MediaController.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                pushMetadata(metadata);
            }

            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                pushPlaybackState(state);
            }

            @Override
            public void onAudioInfoChanged(PlaybackInfo info) {
                pushVolume(info);
            }

            @Override
            public void onSessionDestroyed() {
                System.out.println("[MetaServer] Session destroyed: " + best.getPackageName());
                broadcast(buildEvent("session", "\"package\":null"));
                trackedController = null;
                activeCallback = null;
            }
        };

        best.registerCallback(activeCallback, mainHandler);
    }

    // -------------------------------------------------------------------------
    // Event builders
    // -------------------------------------------------------------------------

    private static void pushMetadata(MediaMetadata metadata) {
        if (metadata == null) {
            broadcast(buildEvent("metadata",
                    "\"title\":null,\"artist\":null,\"album\":null,\"duration\":0,\"art\":null"));
            return;
        }
        String title  = jsonString(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
        String artist = jsonString(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
        String album  = jsonString(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String art    = artToBase64(metadata);

        broadcast(buildEvent("metadata",
                "\"title\":"    + title
                + ",\"artist\":" + artist
                + ",\"album\":"  + album
                + ",\"duration\":" + duration
                + ",\"art\":"    + art));
    }

    /**
     * Extract album art, scale to ≤300×300, compress to JPEG, return as base64 JSON string.
     * Returns JSON null if unavailable.
     */
    private static String artToBase64(MediaMetadata metadata) {
        try {
            Bitmap bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bitmap == null)
                bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (bitmap == null)
                return "null";

            if (bitmap.getWidth() > 300 || bitmap.getHeight() > 300) {
                bitmap = Bitmap.createScaledBitmap(bitmap, 300, 300, true);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            return "\"" + b64 + "\"";
        } catch (Exception e) {
            System.out.println("[MetaServer] Art extraction failed: " + e.getMessage());
            return "null";
        }
    }

    private static void pushPlaybackState(PlaybackState state) {
        if (state == null) {
            broadcast(buildEvent("playback", "\"state\":0,\"position\":0,\"speed\":0.0"));
            return;
        }
        broadcast(buildEvent("playback",
                "\"state\":"    + state.getState()
                + ",\"position\":" + state.getPosition()
                + ",\"speed\":"   + state.getPlaybackSpeed()));
    }

    private static void pushVolume(PlaybackInfo info) {
        if (info == null) return;
        broadcast(buildEvent("volume",
                "\"current\":" + info.getCurrentVolume()
                + ",\"max\":"  + info.getMaxVolume()));
    }

    private static String buildEvent(String type, String body) {
        return "{\"event\":\"" + type + "\"," + body + "}";
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", " ")
                        .replace("\r", " ")
                        .replace("\t", " ") + "\"";
    }

    // -------------------------------------------------------------------------
    // TCP server
    // -------------------------------------------------------------------------

    private static void startServer(int port) throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("[MetaServer] Listening on port " + port);

        //noinspection InfiniteLoopStatement
        while (true) {
            Socket client = serverSocket.accept();
            System.out.println("[MetaServer] Client connected: " + client.getInetAddress());
            new Thread(() -> handleClient(client)).start();
        }
    }

    private static void handleClient(Socket socket) {
        try {
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            clients.add(writer);

            if (trackedController != null) {
                writer.println(buildEvent("session",
                        "\"package\":\"" + trackedController.getPackageName() + "\""));
                pushSnapshotTo(writer);
            }

            socket.getInputStream().read(); // block until client disconnects

        } catch (IOException ignored) {
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            removeClosedClients();
            System.out.println("[MetaServer] Client disconnected: " + socket.getInetAddress());
        }
    }

    private static void pushSnapshotTo(PrintWriter writer) {
        if (trackedController == null) return;
        MediaMetadata metadata = trackedController.getMetadata();
        if (metadata != null) {
            String title  = jsonString(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            String artist = jsonString(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
            String album  = jsonString(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
            long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            String art    = artToBase64(metadata);
            writer.println(buildEvent("metadata",
                    "\"title\":"    + title
                    + ",\"artist\":" + artist
                    + ",\"album\":"  + album
                    + ",\"duration\":" + duration
                    + ",\"art\":"    + art));
        }
        PlaybackState ps = trackedController.getPlaybackState();
        if (ps != null) {
            writer.println(buildEvent("playback",
                    "\"state\":"    + ps.getState()
                    + ",\"position\":" + ps.getPosition()
                    + ",\"speed\":"   + ps.getPlaybackSpeed()));
        }
        PlaybackInfo vi = trackedController.getPlaybackInfo();
        if (vi != null) {
            writer.println(buildEvent("volume",
                    "\"current\":" + vi.getCurrentVolume()
                    + ",\"max\":"  + vi.getMaxVolume()));
        }
    }

    // -------------------------------------------------------------------------
    // Fan-out broadcast
    // -------------------------------------------------------------------------

    private static void broadcast(String message) {
        System.out.println("[MetaServer] → " + message.substring(0,
                Math.min(message.length(), 120)));  // truncate art in logs
        for (PrintWriter writer : clients) {
            writer.println(message);
            if (writer.checkError()) {
                clients.remove(writer);
            }
        }
    }

    private static void removeClosedClients() {
        clients.removeIf(PrintWriter::checkError);
    }
}