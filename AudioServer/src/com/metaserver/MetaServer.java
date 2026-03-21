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

        MediaSessionManager service = (MediaSessionManager) context.getSystemService("media_session");
        if (service != null) {
            System.out.println("✓ Available (" + service.getClass().getSimpleName() + ")");

            testMediaSessionManager(service);
        } else {
            System.out.println("✗ Not available");
        }

    }

    private static void testMediaSessionManager(MediaSessionManager mediaSessionManager) {
        MediaController primarySession = getPrimarySession(mediaSessionManager);
        if (primarySession != null) {
            testMediaSession(primarySession, 0);
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

    private static void testMediaSession(MediaController session, int index) {
        try {
            // Get session info
            String packageName = session.getPackageName();
            System.out.println("    Session " + index + ": " + packageName);
            
            // Try to get playback info
            try {
                PlaybackInfo playbackInfo = session.getPlaybackInfo();
                
                if (playbackInfo != null) {
                    int playbackType = playbackInfo.getPlaybackType();
                    System.out.println("      → Playback type: " + (playbackType == 1 ? "Local" : "Remote"));
                    
                    int currentVolume = playbackInfo.getCurrentVolume();
                    int maxVolume = playbackInfo.getMaxVolume();
                    System.out.println("      → Volume: " + currentVolume + "/" + maxVolume);
                }
            } catch (Exception e) {
                System.out.println("      → Playback info unavailable: " + e.getMessage());
            }
            
            // Try to get metadata
            try {
                MediaMetadata metadata = session.getMetadata();
                
                if (metadata != null) {
                    String title = metadata.getString("android.media.metadata.TITLE");
                    String artist = metadata.getString("android.media.metadata.ARTIST");
                    
                    if (title != null || artist != null) {
                        System.out.println("      → Now playing: " + 
                            (title != null ? title : "Unknown") + 
                            (artist != null ? " by " + artist : ""));
                    }
                }
            } catch (Exception e) {
                // Metadata might not be available or accessible
                System.out.println("      → Metadata unavailable");
            }
            
        } catch (Exception e) {
            System.out.println("    Session " + index + " test failed: " + e.getMessage());
        }
    }
}