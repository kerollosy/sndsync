package com.metaserver;

import android.util.Log;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaController.PlaybackInfo;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

public class MetaServer {
    private static final String TAG = "SndsyncMetaServer";

    private static final String PACKAGE_NAME = "com.android.shell";
    private static final int DEFAULT_PORT = 9998;
    private static final int POLL_MS = 500;
    private static final int MAX_ART_DIMENSION = 300; // Limit album art sizing to protect memory heap

    private static FakeContext context;
    private static MediaSessionManager mediaSessionManager;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid port number: " + args[0] + ", using default: " + port);
            }
        }

        Log.i(TAG, "Starting MetaServer on port: " + port);

        try {
            prepareMainLooper();
            Workarounds.apply();
            initMediaSessionManager();
            startServer(port);
        } catch (Exception e) {
            Log.e(TAG, "FATAL SERVER ERROR: " + e.getMessage());
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
        if (context == null) {
            context = FakeContext.get();
        }

        Object service = context.getSystemService("media_session");
        if (!(service instanceof MediaSessionManager)) {
            throw new RuntimeException("media_session framework pipeline unavailable.");
        }
        mediaSessionManager = (MediaSessionManager) service;
        Log.i(TAG, "MediaSessionManager linked successfully.");
    }

    private static void startServer(int port) throws Exception {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new java.net.InetSocketAddress(port));

            while (true) {
                Socket clientSocket = serverSocket.accept();
                Log.i(TAG, "Client paired: " + clientSocket.getInetAddress());
                new Thread(() -> handleClient(clientSocket)).start();
            }
        }
    }

    private static void handleClient(Socket client) {
        // Cache primitives to avoid JSON thrashing allocations on every tick
        String lastPackage = "";
        String lastTitle = "";
        String lastArtist = "";
        long lastDuration = -1;
        int lastState = -1;
        int lastVolume = -1;

        try (Socket socket = client;
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))) {

            while (!socket.isClosed()) {
                MediaController controller = getPrimarySession();
                String packageName = (controller != null) ? controller.getPackageName() : "None";

                // Package Sync Check
                if (!lastPackage.equals(packageName)) {
                    JSONObject sessionEvent = new JSONObject();
                    sessionEvent.put("event", "session");
                    sessionEvent.put("package", packageName.equals("None") ? JSONObject.NULL : packageName);
                    sendEvent(writer, sessionEvent);
                    lastPackage = packageName;
                }

                if (controller != null) {
                    MediaMetadata metadata = controller.getMetadata();
                    
                    // Extracts metadata elements
                    String title = (metadata != null) ? metadata.getString(MediaMetadata.METADATA_KEY_TITLE) : "";
                    String artist = (metadata != null) ? metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) : "";
                    String album = (metadata != null) ? metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) : "";
                    long duration = (metadata != null) ? metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;

                    if (title == null) title = "";
                    if (artist == null) artist = "";
                    if (album == null) album = "";

                    // Metadata Changes Check
                    if (!lastTitle.equals(title) || !lastArtist.equals(artist) || lastDuration != duration) {
                        JSONObject metadataEvent = new JSONObject();
                        metadataEvent.put("event", "metadata");
                        metadataEvent.put("title", title.isEmpty() ? JSONObject.NULL : title);
                        metadataEvent.put("artist", artist.isEmpty() ? JSONObject.NULL : artist);
                        metadataEvent.put("album", album.isEmpty() ? JSONObject.NULL : album);
                        metadataEvent.put("duration", duration);

                        Bitmap artBitmap = null;
                        if (metadata != null) {
                            artBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                            if (artBitmap == null) {
                                artBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
                            }
                        }
                        String artBase64 = bitmapToBase64(artBitmap);
                        metadataEvent.put("art", artBase64 == null ? JSONObject.NULL : artBase64);

                        sendEvent(writer, metadataEvent);
                        
                        lastTitle = title;
                        lastArtist = artist;
                        lastDuration = duration;
                    }

                    // Playback State Check
                    PlaybackState playbackState = controller.getPlaybackState();
                    int currentState = (playbackState != null) ? playbackState.getState() : -1;
                    if (lastState != currentState) {
                        JSONObject playbackEvent = new JSONObject();
                        playbackEvent.put("event", "playback");
                        playbackEvent.put("state", mapPlaybackLabel(currentState));
                        playbackEvent.put("position", playbackState != null ? playbackState.getPosition() : 0);
                        playbackEvent.put("speed", playbackState != null ? playbackState.getPlaybackSpeed() : 1.0);
                        
                        sendEvent(writer, playbackEvent);
                        lastState = currentState;
                    }

                    // Volume Metric Check
                    PlaybackInfo playbackInfo = controller.getPlaybackInfo();
                    int currentVolume = (playbackInfo != null) ? playbackInfo.getCurrentVolume() : -1;
                    if (lastVolume != currentVolume) {
                        JSONObject volumeEvent = new JSONObject();
                        volumeEvent.put("event", "volume");
                        volumeEvent.put("current", currentVolume == -1 ? JSONObject.NULL : currentVolume);
                        volumeEvent.put("max", playbackInfo != null ? playbackInfo.getMaxVolume() : JSONObject.NULL);
                        
                        sendEvent(writer, volumeEvent);
                        lastVolume = currentVolume;
                    }
                }

                Thread.sleep(POLL_MS);
            }
        } catch (IOException e) {
            Log.i(TAG, "Client safely disconnected from metadata engine.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Exception handling metadata client thread: " + e.getMessage());
        }
    }

    private static String bitmapToBase64(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }

        try {
            // Memory Safe Downscaling Protection Layer
            if (bitmap.getWidth() > MAX_ART_DIMENSION || bitmap.getHeight() > MAX_ART_DIMENSION) {
                bitmap = Bitmap.createScaledBitmap(bitmap, MAX_ART_DIMENSION, MAX_ART_DIMENSION, true);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
            byte[] bytes = out.toByteArray();
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Throwable e) {
            Log.e(TAG, "Bitmap safety compression failure: " + e.getMessage());
            return null;
        }
    }

    private static String mapPlaybackLabel(int state) {
        switch (state) {
            case PlaybackState.STATE_STOPPED: return "Stopped";
            case PlaybackState.STATE_PAUSED: return "Paused";
            case PlaybackState.STATE_PLAYING: return "Playing";
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING: return "Buffering";
            default: return "None";
        }
    }

    private static MediaController getPrimarySession() {
        try {
            ComponentName componentName = new ComponentName(PACKAGE_NAME, PACKAGE_NAME + ".NotificationListener");
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
        String jsonPayload = event.toString();
        Log.d(TAG, jsonPayload);
        writer.write(jsonPayload);
        writer.newLine();
        writer.flush();
    }
}