package com.metaserver;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.List;

import android.content.ComponentName;
import android.os.Looper;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.MediaController.PlaybackInfo;
import android.media.MediaMetadata;

public class MetaServer {
    private static final String PACKAGE_NAME = "com.android.shell";
    private static final int DEFAULT_PORT = 9998;

    private static FakeContext context;

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
        MediaSessionManager mediaSessionManager = (MediaSessionManager) service;
        System.out.println("[MetaServer] MediaSessionManager ready");
        testMediaSessionManager(mediaSessionManager);
    }

    private static void testMediaSessionManager(MediaSessionManager mediaSessionManager) {
        MediaController controller = getPrimarySession(mediaSessionManager);
        String packageName = controller != null ? controller.getPackageName() : null;
        System.out.println("[MetaServer] Primary session package: " + (packageName != null ? packageName : "null"));
        if (controller != null) {
            try {
                PlaybackInfo playbackInfo = controller.getPlaybackInfo();
                MediaMetadata metadata = controller.getMetadata();

                System.out.println("    Session :");
                System.out.println("        PlaybackInfo: " + (playbackInfo != null ? playbackInfo.toString() : "null"));
                System.out.println("        Metadata: " + (metadata != null ? metadata.toString() : "null"));
                System.out.println("            Title: " + (metadata != null ? metadata.getString(MediaMetadata.METADATA_KEY_TITLE) : "null"));
                System.out.println("            Artist: " + (metadata != null ? metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) : "null"));
                System.out.println("            Album: " + (metadata != null ? metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) : "null"));
            } catch (Exception e) {
                System.out.println("    Session test failed: " + e.getMessage());
            }
        } else {
            System.out.println("    No active sessions found.");
        }
    }

    private static MediaController getPrimarySession(MediaSessionManager mediaSessionManager) {
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
}