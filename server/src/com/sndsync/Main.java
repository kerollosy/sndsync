package com.sndsync;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Field;

import android.os.Looper;
import android.os.HandlerThread;
import android.os.Handler;

/**
 * Entry point for the sndsync server JAR.
 *
 * <p>Routes prefixed arguments to each server and starts the requested ones in
 * their own threads. Each server is responsible for parsing its own arguments.
 *
 * <p>Arguments prefixed with {@code --audio-} are forwarded to {@link AudioServer}
 * with the prefix stripped (e.g. {@code --audio-port 9999} → {@code --port 9999}).
 * Arguments prefixed with {@code --meta-} are forwarded to {@link MetaServer}
 * the same way. At least one prefixed argument must be present.
 *
 * <p>Usage (via app_process):
 * <pre>
 *   CLASSPATH=/data/local/tmp/sndsync-server.jar \
 *     app_process /data/local/tmp/ com.sndsync.Main \
 *       --audio-port 9999 --audio-stereo \
 *       --meta-port 9998
 * </pre>
 */
public class Main {
    private static final String TAG = "sndsync";

    private static final String AUDIO_PREFIX = "--audio-";
    private static final String META_PREFIX  = "--meta-";
    private static FakeContext context;

    public static void main(String[] args) throws Exception {
        // Initialize Main Looper and apply system workarounds immediately on the main thread
        prepareMainLooper();
        Workarounds.apply();
        context = FakeContext.get();

        List<String> audioArgs = new ArrayList<>();
        List<String> metaArgs  = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith(AUDIO_PREFIX)) {
                audioArgs.add("--" + arg.substring(AUDIO_PREFIX.length()));
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    audioArgs.add(args[i + 1]);
                    i++;
                }
            } else if (arg.startsWith(META_PREFIX)) {
                metaArgs.add("--" + arg.substring(META_PREFIX.length()));
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    metaArgs.add(args[i + 1]);
                    i++;
                }
            } else {
                Log.w(TAG, "Unrecognised argument (expected --audio-* or --meta-* prefix): " + arg);
            }
        }

        if (audioArgs.isEmpty() && metaArgs.isEmpty()) {
            Log.e(TAG, "No server arguments provided.");
            Log.e(TAG, "Usage: com.sndsync.Main [--audio-<flag> ...] [--meta-<flag> ...]");
            Log.e(TAG, "Example: --audio-port 9999 --audio-stereo --meta-port 9998");
            System.exit(1);
        }
    
        // Start each requested server in its own thread. Threads are non-daemon
        // so the process stays alive as long as either server is running.
        if(!audioArgs.isEmpty()) {
            Log.i(TAG, "Starting AudioServer with args: " + audioArgs);
            new Thread(() -> {
                try {
                    AudioServer.main(audioArgs.toArray(new String[0]));
                } catch (Exception e) {
                    Log.e(TAG, "AudioServer crashed: " + e.getMessage());
                    e.printStackTrace();
                }
            }, "AudioServer").start();
        }

        if(!metaArgs.isEmpty()) {
            Log.i(TAG, "Starting MetaServer with args: " + metaArgs);
            new Thread(() -> {
                    try {
                        MetaServer.main(metaArgs.toArray(new String[0]));
                    } catch (Exception e) {
                        Log.e(TAG, "MetaServer crashed: " + e.getMessage());
                    e.printStackTrace();
                    System.exit(1);
                }
            }, "MetaServer").start();
        }

        /*
         * CRITICAL SYSTEM ARCHITECTURE CONTEXT:
         * Because this program executes as a raw native server binary via 'app_process' rather than 
         * inside a standard managed Android Application sandbox, hitting the end of main() will 
         * immediately terminate the entire process (and tear down the background daemon worker threads).
         * * Calling Looper.loop() blocks the primary main thread in an infinite message dispatch pipeline.
         * This forces the process to stay alive indefinitely while perfectly managing underlying system 
         * Binder IPC transactions and callback events required by managers like MediaSessionManager.
         */
        Log.i(TAG, "Sndsync process watchdog armed. System active.");
        Looper.loop(); 
    }

    private static void prepareMainLooper() {
        if (Looper.myLooper() != null) {
            Log.i(TAG, "Thread already has an active Looper. Skipping initialization.");
            return;
        }
        
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
}