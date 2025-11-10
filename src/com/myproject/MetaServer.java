package com.myproject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

import android.content.Context;
import android.content.ContextWrapper;
import android.app.Application;
import android.app.NotificationManager;
import android.content.pm.ApplicationInfo;
import android.service.notification.StatusBarNotification;
import android.os.Looper;
import android.os.Build;
import android.content.ClipData;
import android.app.ActivityThread;

public class MetaServer {
    private static final String PACKAGE_NAME = "com.android.shell";

    private static Object activityThread;
    private static Class<?> activityThreadClass;

    private static Context context;

    public static void main(String[] args) throws Exception {
        System.out.println("[MetaServer] Starting meta server");
        try {
            // Initialize Android runtime environment first
            initAndroidRuntime();
            
            initMediaController();
        } catch (Exception e) {
            System.out.println("[MetaServer] FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void initAndroidRuntime() throws Exception {
        try {
            // Prepare Looper for the main thread
            Looper.prepareMainLooper();

            // Initialize ActivityThread (similar to scrcpy's Workarounds)
            activityThreadClass = Class.forName("android.app.ActivityThread");
            Constructor<?> activityThreadConstructor = activityThreadClass.getDeclaredConstructor();
            activityThreadConstructor.setAccessible(true);
            activityThread = activityThreadConstructor.newInstance();

            // Set as current ActivityThread
            Field sCurrentActivityThreadField = activityThreadClass.getDeclaredField("sCurrentActivityThread");
            sCurrentActivityThreadField.setAccessible(true);
            sCurrentActivityThreadField.set(null, activityThread);

            // Mark as system thread
            Field mSystemThreadField = activityThreadClass.getDeclaredField("mSystemThread");
            mSystemThreadField.setAccessible(true);
            mSystemThreadField.setBoolean(activityThread, true);

            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                fillConfigurationController();
            }

            // Fill app info
            fillAppInfo();
            
            // Fill app context
            fillAppContext();

            ActivityThread.initializeMainlineModules();

            System.out.println("[MetaServer] Android runtime initialized successfully");
        } catch (Exception e) {
            System.out.println("[MetaServer] Failed to initialize Android runtime: " + e.getMessage());
            throw e;
        }
    }

    private static void fillConfigurationController() {
        try {
            Class<?> configurationControllerClass = Class.forName("android.app.ConfigurationController");
            Class<?> activityThreadInternalClass = Class.forName("android.app.ActivityThreadInternal");

            // configurationController = new ConfigurationController(ACTIVITY_THREAD);
            Constructor<?> configurationControllerConstructor = configurationControllerClass.getDeclaredConstructor(activityThreadInternalClass);
            configurationControllerConstructor.setAccessible(true);
            Object configurationController = configurationControllerConstructor.newInstance(activityThread);

            // ACTIVITY_THREAD.mConfigurationController = configurationController;
            Field configurationControllerField = activityThreadClass.getDeclaredField("mConfigurationController");
            configurationControllerField.setAccessible(true);
            configurationControllerField.set(activityThread, configurationController);
        } catch (Exception throwable) {
            System.err.println("Could not fill configuration: " + throwable.getMessage());
        }
    }

    private static void fillAppInfo() {
        try {
            // Create AppBindData
            Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> appBindDataConstructor = appBindDataClass.getDeclaredConstructor();
            appBindDataConstructor.setAccessible(true);
            Object appBindData = appBindDataConstructor.newInstance();

            ApplicationInfo applicationInfo = new ApplicationInfo();
            applicationInfo.packageName = PACKAGE_NAME;

            // Set app info
            Field appInfoField = appBindDataClass.getDeclaredField("appInfo");
            appInfoField.setAccessible(true);
            appInfoField.set(appBindData, applicationInfo);

            // Set bound application
            Field mBoundApplicationField = activityThreadClass.getDeclaredField("mBoundApplication");
            mBoundApplicationField.setAccessible(true);
            mBoundApplicationField.set(activityThread, appBindData);

            System.out.println("[MetaServer] App info filled");
        } catch (Exception e) {
            System.out.println("[MetaServer] Could not fill app info: " + e.getMessage());
        }
    }

    private static void fillAppContext() {
        try {
            Application app = new Application();
            Field baseField = ContextWrapper.class.getDeclaredField("mBase");
            baseField.setAccessible(true);
            baseField.set(app, FakeContext.get());

            // Set initial application
            Field mInitialApplicationField = activityThreadClass.getDeclaredField("mInitialApplication");
            mInitialApplicationField.setAccessible(true);
            mInitialApplicationField.set(activityThread, app);

            System.out.println("[MetaServer] App context filled");
        } catch (Exception e) {
            System.out.println("[MetaServer] Could not fill app context: " + e.getMessage());
        }
    }

    static Context getSystemContext() {
        try {
            Method getSystemContextMethod = activityThreadClass.getDeclaredMethod("getSystemContext");
            return (Context) getSystemContextMethod.invoke(activityThread);
        } catch (Exception throwable) {
            // this is a workaround, so failing is not an error
            System.err.println("Could not get system context: " + throwable.getMessage());
            return null;
        }
    }

    private static void initMediaController() throws Exception {
        System.out.println("[MetaServer] Initializing MediaController...");
        if (context == null) {
            System.out.println("[MetaServer] Using FakeContext");
            context = FakeContext.get();
        }

        System.out.println(context.getPackageManager().getInstalledApplications(128).size() + " installed applications");
        
        // Test other system services
        testSystemServices();
    }
    
    private static void testSystemServices() {
        System.out.println("[MetaServer] Testing system services...");
        
        // List of system services to test with their names
        String[] services = {
            "audio",           // AudioManager
            "window",          // WindowManager  
            "activity",        // ActivityManager
            "power",           // PowerManager
            "wifi",            // WifiManager
            "connectivity",    // ConnectivityManager
            "telephony",       // TelephonyManager
            "location",        // LocationManager
            "vibrator",        // VibratorManager
            "input_method",    // InputMethodManager
            "notification",    // NotificationManager
            "alarm",           // AlarmManager
            "media_session"    // MediaSessionManager (might fail)
        };
        
        for (String serviceName : services) {
            testSystemService(serviceName);
        }
    }
    
    private static void testSystemService(String serviceName) {
        try {
            System.out.print("Testing " + serviceName + ": ");
            Object service = context.getSystemService(serviceName);
            if (service != null) {
                System.out.println("✓ Available (" + service.getClass().getSimpleName() + ")");
                
                // Special handling for specific services
                if ("audio".equals(serviceName)) {
                    testAudioManager(service);
                } else if ("window".equals(serviceName)) {
                    testWindowManager(service);
                } else if ("media_session".equals(serviceName)) {
                    testMediaSessionManager(service);
                }
            } else {
                System.out.println("✗ Not available");
            }
        } catch (Exception e) {
            System.out.println("✗ Error: " + e.getMessage());
        }
    }
    
    private static void testAudioManager(Object audioManager) {
        try {
            // Test getting volume
            Method getStreamVolumeMethod = audioManager.getClass().getMethod("getStreamVolume", int.class);
            int musicVolume = (int) getStreamVolumeMethod.invoke(audioManager, 3); // STREAM_MUSIC = 3
            System.out.println("  → Music volume: " + musicVolume);
            
            // Test getting max volume
            Method getStreamMaxVolumeMethod = audioManager.getClass().getMethod("getStreamMaxVolume", int.class);
            int maxMusicVolume = (int) getStreamMaxVolumeMethod.invoke(audioManager, 3);
            System.out.println("  → Max music volume: " + maxMusicVolume);
            
        } catch (Exception e) {
            System.out.println("  → AudioManager test failed: " + e.getMessage());
        }
    }
    
    private static void testWindowManager(Object windowManager) {
        try {
            Method getDefaultDisplayMethod = windowManager.getClass().getMethod("getDefaultDisplay");
            Object display = getDefaultDisplayMethod.invoke(windowManager);
            
            if (display != null) {
                Method getWidthMethod = display.getClass().getMethod("getWidth");
                Method getHeightMethod = display.getClass().getMethod("getHeight");
                
                int width = (int) getWidthMethod.invoke(display);
                int height = (int) getHeightMethod.invoke(display);
                
                System.out.println("  → Display size: " + width + "x" + height);
            }
        } catch (Exception e) {
            System.out.println("  → WindowManager test failed: " + e.getMessage());
        }
    }

    private static void testMediaSessionManager(Object mediaSessionManager) {
        try {
            // Test getting active sessions (requires notification listener permission)
            try {
                Method getActiveSessionsMethod = mediaSessionManager.getClass().getMethod("getActiveSessions", android.content.ComponentName.class);
                
                // Create a ComponentName for our shell package as notification listener
                android.content.ComponentName componentName = new android.content.ComponentName(
                    PACKAGE_NAME, 
                    PACKAGE_NAME + ".NotificationListener"
                );
                
                Object activeSessions = getActiveSessionsMethod.invoke(mediaSessionManager, componentName);
                
                if (activeSessions != null) {
                    java.util.List<?> sessionList = (java.util.List<?>) activeSessions;
                    System.out.println("  → Active media sessions: " + sessionList.size());
                    
                    // Test each active session
                    for (int i = 0; i < Math.min(sessionList.size(), 3); i++) { // Limit to first 3
                        Object session = sessionList.get(i);
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

    private static void testMediaSession(Object session, int index) {
        try {
            // Get session info
            Method getPackageNameMethod = session.getClass().getMethod("getPackageName");
            String packageName = (String) getPackageNameMethod.invoke(session);
            
            Method getSessionTokenMethod = session.getClass().getMethod("getSessionToken");
            Object token = getSessionTokenMethod.invoke(session);
            
            System.out.println("    Session " + index + ": " + packageName);
            
            // Try to get playback info
            try {
                Method getPlaybackInfoMethod = session.getClass().getMethod("getPlaybackInfo");
                Object playbackInfo = getPlaybackInfoMethod.invoke(session);
                
                if (playbackInfo != null) {
                    Method getPlaybackTypeMethod = playbackInfo.getClass().getMethod("getPlaybackType");
                    int playbackType = (int) getPlaybackTypeMethod.invoke(playbackInfo);
                    System.out.println("      → Playback type: " + (playbackType == 1 ? "Local" : "Remote"));
                    
                    Method getCurrentVolumeMethod = playbackInfo.getClass().getMethod("getCurrentVolume");
                    int currentVolume = (int) getCurrentVolumeMethod.invoke(playbackInfo);
                    
                    Method getMaxVolumeMethod = playbackInfo.getClass().getMethod("getMaxVolume");
                    int maxVolume = (int) getMaxVolumeMethod.invoke(playbackInfo);
                    
                    System.out.println("      → Volume: " + currentVolume + "/" + maxVolume);
                }
            } catch (Exception e) {
                System.out.println("      → Playback info unavailable: " + e.getMessage());
            }
            
            // Try to get metadata
            try {
                Method getMetadataMethod = session.getClass().getMethod("getMetadata");
                Object metadata = getMetadataMethod.invoke(session);
                
                if (metadata != null) {
                    Method getStringMethod = metadata.getClass().getMethod("getString", String.class);
                    
                    String title = (String) getStringMethod.invoke(metadata, "android.media.metadata.TITLE");
                    String artist = (String) getStringMethod.invoke(metadata, "android.media.metadata.ARTIST");
                    
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