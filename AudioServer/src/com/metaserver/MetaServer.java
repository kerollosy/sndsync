package com.metaserver;

import java.lang.reflect.Field;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaController.PlaybackInfo;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

public class MetaServer {
    private static final String PACKAGE_NAME = "com.android.shell";
    private static final int DEFAULT_PORT = 9998;
    private static final int POLL_MS = 500;

    private static FakeContext context;
    private static MediaSessionManager mediaSessionManager;

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Invalid port number: " + args[0] + ", using default: " + port);
            }
        }

        System.out.println("[MetaServer] Starting meta server on port " + port);

        try {
            prepareMainLooper();

            Workarounds.apply();

            initMediaSessionManager();
            startServer(port);
        } catch (Exception e) {
            System.out.println("[MetaServer] FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
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

    private static void initMediaSessionManager() throws Exception {
        System.out.println("[MetaServer] Initializing MediaSessionManager...");
        if (context == null) {
            System.out.println("[MetaServer] Using FakeContext");
            context = FakeContext.get();
        }

        Object service = context.getSystemService("media_session");
        if (!(service instanceof MediaSessionManager)) {
            throw new RuntimeException("media_session service unavailable");
        }
        mediaSessionManager = (MediaSessionManager) service;
        System.out.println("[MetaServer] MediaSessionManager ready");
    }

    private static void startServer(int port) throws Exception {
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("[MetaServer] Server listening on " + port);

        while (true) {
            Socket client = serverSocket.accept();
            System.out.println("[MetaServer] Client connected: " + client.getInetAddress());

            handleClient(client);
        }
    }

    private static void handleClient(Socket client) {
        String lastPackage = null;
        String lastMetadataJson = null;
        String lastPlaybackJson = null;
        String lastVolumeJson = null;

        try (Socket socket = client;
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))) {

            while (socket.isConnected()) {
                MediaController controller = getPrimarySession();
                String packageName = controller != null ? controller.getPackageName() : null;

                boolean packageChanged;
                if (lastPackage == null) {
                    packageChanged = packageName != null;
                } else {
                    packageChanged = !lastPackage.equals(packageName);
                }

                if (packageChanged) {
                    JSONObject sessionEvent = new JSONObject();
                    sessionEvent.put("event", "session");
                    sessionEvent.put("package", packageName == null ? JSONObject.NULL : packageName);
                    sendEvent(writer, sessionEvent);
                    lastPackage = packageName;
                }

                if (controller != null) {
                    MediaMetadata metadata = controller.getMetadata();
                    JSONObject metadataEvent = new JSONObject();
                    JSONObject comparable = new JSONObject();

                    metadataEvent.put("event", "metadata");

                    if (metadata == null) {
                        metadataEvent.put("title", JSONObject.NULL);
                        metadataEvent.put("artist", JSONObject.NULL);
                        metadataEvent.put("album", JSONObject.NULL);
                        metadataEvent.put("duration", 0);
                        metadataEvent.put("art", JSONObject.NULL);

                        comparable = metadataEvent;
                    } else {
                        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
                        String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
                        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

                        metadataEvent.put("title", title);
                        metadataEvent.put("artist", artist);
                        metadataEvent.put("album", album);
                        metadataEvent.put("duration", duration);
                        metadataEvent.put("art", metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART));

                        // comparable version WITHOUT art
                        comparable.put("title", title);
                        comparable.put("artist", artist);
                        comparable.put("album", album);
                        comparable.put("duration", duration);
                    }

                    String comparableJson = comparable.toString();

                    if (!comparableJson.equals(lastMetadataJson)) {
                        sendEvent(writer, metadataEvent);
                        lastMetadataJson = comparableJson;
                    }

                    PlaybackState playbackState = controller.getPlaybackState();
                    JSONObject playbackEvent = new JSONObject();
                    playbackEvent.put("event", "playback");
                    if (playbackState == null) {
                        playbackEvent.put("state", "None");
                        playbackEvent.put("position", 0);
                        playbackEvent.put("speed", 1.0);
                    } else {
                        playbackEvent.put("state", mapPlaybackLabel(playbackState.getState()));
                        playbackEvent.put("position", playbackState.getPosition());
                        playbackEvent.put("speed", playbackState.getPlaybackSpeed());
                    }
                    String playbackJson = playbackEvent.toString();
                    if (!playbackJson.equals(lastPlaybackJson)) {
                        sendEvent(writer, playbackEvent);
                        lastPlaybackJson = playbackJson;
                    }

                    PlaybackInfo playbackInfo = controller.getPlaybackInfo();
                    JSONObject volumeEvent = new JSONObject();
                    volumeEvent.put("event", "volume");
                    if (playbackInfo == null) {
                        volumeEvent.put("current", JSONObject.NULL);
                        volumeEvent.put("max", JSONObject.NULL);
                    } else {
                        volumeEvent.put("current", playbackInfo.getCurrentVolume());
                        volumeEvent.put("max", playbackInfo.getMaxVolume());
                    }
                    String volumeJson = volumeEvent.toString();
                    if (!volumeJson.equals(lastVolumeJson)) {
                        sendEvent(writer, volumeEvent);
                        lastVolumeJson = volumeJson;
                    }
                }

                Thread.sleep(POLL_MS);
            }
        } catch (Exception e) {
            System.out.println("[MetaServer] Client ended: " + e.getMessage());
        }
    }

    private static String mapPlaybackLabel(int state) {
        if (state == PlaybackState.STATE_STOPPED) {
            return "Stopped";
        }
        if (state == PlaybackState.STATE_PAUSED) {
            return "Paused";
        }
        if (state == PlaybackState.STATE_PLAYING) {
            return "Playing";
        }
        if (state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING) {
            return "Buffering";
        }
        return "None";
    }

    private static MediaController getPrimarySession() {
        try {
            ComponentName componentName = new ComponentName(
                    PACKAGE_NAME,
                    PACKAGE_NAME + ".NotificationListener"
            );
            List<MediaController> sessions = mediaSessionManager.getActiveSessions(componentName);
            if (sessions == null || sessions.isEmpty()) {
                return null;
            }
            return sessions.get(0);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void sendEvent(BufferedWriter writer, JSONObject event) throws Exception {
        System.out.println(event.toString());
        writer.write(event.toString());
        writer.newLine();
        writer.flush();
    }
}