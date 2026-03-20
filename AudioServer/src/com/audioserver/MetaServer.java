package com.audioserver;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaController.PlaybackInfo;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

public class MetaServer {
    private static final String PACKAGE_NAME = "com.android.shell";
    private static final int DEFAULT_PORT = 9998;

    // Connected clients — thread-safe so the callback can write without locking
    private static final Set<PrintWriter> clients = new CopyOnWriteArraySet<>();

    private static FakeContext context;
    private static Handler mainHandler;
    private static MediaSessionManager sessionManager;

    // The single session we are currently tracking
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

        // Run the TCP server on a background thread so THIS thread can pump the looper
        final int finalPort = port;
        new Thread(() -> {
            try {
                startServer(finalPort);
            } catch (IOException e) {
                System.out.println("[MetaServer] Server error: " + e.getMessage());
                System.exit(1);
            }
        }, "tcp-server").start();

        // Pump the main looper — this is what actually delivers MediaController callbacks
        Looper.loop();
    }

    // -------------------------------------------------------------------------
    // Looper bootstrap (same pattern as AudioServer)
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
            System.out.println("[MetaServer] MediaSessionManager unavailable — metadata won't be sent");
            return;
        }

        ComponentName listenerComponent = new ComponentName(
                PACKAGE_NAME, PACKAGE_NAME + ".NotificationListener");

        // Watch for session list changes
        sessionManager.addOnActiveSessionsChangedListener(controllers -> {
            System.out.println("[MetaServer] Sessions changed: "
                    + (controllers != null ? controllers.size() : 0) + " active");
            pickBestSession(controllers);
        }, listenerComponent, mainHandler);

        // Seed with whatever is already active
        try {
            List<MediaController> initial = sessionManager.getActiveSessions(listenerComponent);
            pickBestSession(initial);
        } catch (SecurityException e) {
            System.out.println("[MetaServer] No notification-listener permission; waiting for sessions passively");
        }
    }

    /**
     * Choose the first (highest-priority) session from the list and start
     * tracking it.  If the list is empty, detach from the current session.
     */
    private static void pickBestSession(List<MediaController> controllers) {
        MediaController best = (controllers != null && !controllers.isEmpty())
                ? controllers.get(0) : null;

        // Same session — nothing to do
        if (best != null && trackedController != null
                && best.getSessionToken().equals(trackedController.getSessionToken())) {
            return;
        }

        // Detach old callback
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

        // Push current state immediately so the client isn't waiting for a change
        pushMetadata(best.getMetadata());
        pushPlaybackState(best.getPlaybackState());
        pushVolume(best.getPlaybackInfo());

        // Register callback for future changes
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
            broadcast(buildEvent("metadata", "\"title\":null,\"artist\":null,\"album\":null,\"duration\":0"));
            return;
        }
        String title  = jsonString(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
        String artist = jsonString(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
        String album  = jsonString(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        broadcast(buildEvent("metadata",
                "\"title\":" + title
                + ",\"artist\":" + artist
                + ",\"album\":" + album
                + ",\"duration\":" + duration));
    }

    private static void pushPlaybackState(PlaybackState state) {
        if (state == null) {
            broadcast(buildEvent("playback", "\"state\":0,\"position\":0,\"speed\":0.0"));
            return;
        }
        broadcast(buildEvent("playback",
                "\"state\":" + state.getState()
                + ",\"position\":" + state.getPosition()
                + ",\"speed\":" + state.getPlaybackSpeed()));
    }

    private static void pushVolume(PlaybackInfo info) {
        if (info == null) return;
        broadcast(buildEvent("volume",
                "\"current\":" + info.getCurrentVolume()
                + ",\"max\":" + info.getMaxVolume()));
    }

    /**
     * Wraps key-value pairs into a minimal JSON line:
     *   {"event":"<type>",<body>}
     */
    private static String buildEvent(String type, String body) {
        return "{\"event\":\"" + type + "\"," + body + "}";
    }

    /** Null-safe JSON string literal (null → JSON null, otherwise quoted + escaped). */
    private static String jsonString(String s) {
        if (s == null) return "null";
        // Escape backslash and double-quote; replace control chars with spaces
        String escaped = s.replace("\\", "\\\\")
                          .replace("\"", "\\\"")
                          .replace("\n", " ")
                          .replace("\r", " ")
                          .replace("\t", " ");
        return "\"" + escaped + "\"";
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
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), /* autoFlush= */ true);
            clients.add(writer);

            // Immediately push current state to the new client
            if (trackedController != null) {
                writer.println(buildEvent("session",
                        "\"package\":\"" + trackedController.getPackageName() + "\""));
                pushSnapshotTo(writer);
            }

            // Block until the client disconnects (we detect it on the next write)
            // A simple keep-alive: wait for the socket to close
            socket.getInputStream().read(); // blocks; -1 on EOF / disconnect

        } catch (IOException ignored) {
            // Client closed connection — normal
        } finally {
            // Clean up: remove writer (next broadcast to it will also fail, but
            // CopyOnWriteArraySet makes that safe to handle in broadcast())
            try {
                socket.close();
            } catch (IOException ignored) {}
            removeClosedClients();
            System.out.println("[MetaServer] Client disconnected: " + socket.getInetAddress());
        }
    }

    /** Push a full state snapshot to a single newly-connected client. */
    private static void pushSnapshotTo(PrintWriter writer) {
        if (trackedController == null) return;
        MediaMetadata metadata = trackedController.getMetadata();
        if (metadata != null) {
            String title  = jsonString(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            String artist = jsonString(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
            String album  = jsonString(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
            long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            writer.println(buildEvent("metadata",
                    "\"title\":" + title + ",\"artist\":" + artist
                    + ",\"album\":" + album + ",\"duration\":" + duration));
        }
        PlaybackState ps = trackedController.getPlaybackState();
        if (ps != null) {
            writer.println(buildEvent("playback",
                    "\"state\":" + ps.getState()
                    + ",\"position\":" + ps.getPosition()
                    + ",\"speed\":" + ps.getPlaybackSpeed()));
        }
        PlaybackInfo vi = trackedController.getPlaybackInfo();
        if (vi != null) {
            writer.println(buildEvent("volume",
                    "\"current\":" + vi.getCurrentVolume()
                    + ",\"max\":" + vi.getMaxVolume()));
        }
    }

    // -------------------------------------------------------------------------
    // Fan-out broadcast
    // -------------------------------------------------------------------------

    /** Send a JSON line to every connected client, pruning dead connections. */
    private static void broadcast(String message) {
        System.out.println("[MetaServer] → " + message);
        for (PrintWriter writer : clients) {
            writer.println(message);
            if (writer.checkError()) {
                // Socket gone — will be cleaned up next connect/disconnect cycle
                clients.remove(writer);
            }
        }
    }

    private static void removeClosedClients() {
        clients.removeIf(PrintWriter::checkError);
    }
}