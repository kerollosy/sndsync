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

    private static Object activityThread;
    private static Class<?> activityThreadClass;

    private static FakeContext context;

    public static void main(String[] args) throws Exception {
        System.out.println("[MetaServer] Starting meta server");

        try {
            prepareMainLooper();

            Workarounds.apply();

            initMediaController();
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

    private static void initMediaController() throws Exception {
        System.out.println("[MetaServer] Initializing MediaController...");
        if (context == null) {
            System.out.println("[MetaServer] Using FakeContext");
            context = FakeContext.get();
        }

        try {
            MediaSessionManager service = (MediaSessionManager) context.getSystemService("media_session");
            if (service != null) {
                System.out.println("✓ Available (" + service.getClass().getSimpleName() + ")");

                testMediaSessionManager(service);
            } else {
                System.out.println("✗ Not available");
            }
        } catch (Exception e) {
            System.out.println("✗ Error: " + e.getMessage());
        }
    }

    private static void testMediaSessionManager(MediaSessionManager mediaSessionManager) {
        try {
            // Test getting active sessions (requires notification listener permission)
            try {
                
                // Create a ComponentName for our shell package as notification listener
                ComponentName componentName = new ComponentName(
                    PACKAGE_NAME,
                    PACKAGE_NAME + ".NotificationListener"
                );
                
                List<MediaController> activeSessions = mediaSessionManager.getActiveSessions(componentName);
                
                if (activeSessions != null) {
                    System.out.println("  → Active media sessions: " + activeSessions.size());
                    
                    // Test each active session
                    for (int i = 0; i < Math.min(activeSessions.size(), 3); i++) { // Limit to first 3
                        MediaController session = activeSessions.get(i);
                        testMediaSession(session, i);
                    }
                }
            } catch (SecurityException e) {
                System.out.println("  → Active sessions require notification listener permission: " + e.getMessage());
            } catch (Exception e) {
                System.out.println("  → Active sessions test failed: " + e.getMessage());
            }
            
            // Test getting session manager callbacks (if available)
            try {
                Method addOnActiveSessionsChangedListenerMethod = mediaSessionManager.getClass().getMethod(
                    "addOnActiveSessionsChangedListener", 
                    Class.forName("android.media.session.MediaSessionManager$OnActiveSessionsChangedListener"),
                    android.content.ComponentName.class
                );
                System.out.println("  → Session change listener support: Available");
            } catch (NoSuchMethodException e) {
                System.out.println("  → Session change listener support: Not available");
            } catch (ClassNotFoundException e) {
                System.out.println("  → Session change listener class not found");
            }
            
            // Test if we can create a media session (might require different permissions)
            try {
                Method createSessionMethod = mediaSessionManager.getClass().getMethod("createSession", String.class);
                // Don't actually create one, just check if method exists
                System.out.println("  → Session creation support: Available");
            } catch (NoSuchMethodException e) {
                System.out.println("  → Session creation support: Not available");
            }
            
        } catch (Exception e) {
            System.out.println("  → MediaSessionManager test failed: " + e.getMessage());
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