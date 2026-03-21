package com.metaserver;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.List;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import org.json.JSONObject;

import android.content.ComponentName;
import android.os.Looper;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.MediaMetadata;

public class MetaServer {
    private static final String PACKAGE_NAME = "com.android.shell";
    private static final int DEFAULT_PORT = 9998;

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
        try (Socket socket = client;
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))) {

            while (socket.isConnected()) {
                MediaController controller = getPrimarySession();

                if (controller != null) {
                    MediaMetadata metadata = controller.getMetadata();
                    JSONObject metadataEvent = new JSONObject();
                    metadataEvent.put("event", "metadata");
                    if (metadata == null) {
                        metadataEvent.put("title", JSONObject.NULL);
                        metadataEvent.put("artist", JSONObject.NULL);
                        metadataEvent.put("album", JSONObject.NULL);
                        metadataEvent.put("duration", 0);
                        metadataEvent.put("art", JSONObject.NULL);
                    } else {
                        metadataEvent.put("title", metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
                        metadataEvent.put("artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
                        metadataEvent.put("album", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
                        metadataEvent.put("duration", metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
                        metadataEvent.put("art", metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART));
                    }
                    sendEvent(writer, metadataEvent);
                }

                Thread.sleep(500);
            }
        } catch (Exception e) {
            System.out.println("[MetaServer] Client ended: " + e.getMessage());
        }
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