package com.kerollosy.metadata;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONObject;

import java.util.List;

public class MetaNotificationListener extends NotificationListenerService {
    private static final String TAG = "sndsync-meta";

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "Notification listener connected");
        MetadataWriter.startServer();
        updatePlayingSongInfo(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;

        // Only react to media-style notifications
        if (sbn.getNotification().extras.containsKey("android.mediaSession")) {
            updatePlayingSongInfo(this);
        }
    }

    private void updatePlayingSongInfo(Context context) {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> controllers =
                    msm.getActiveSessions(new ComponentName(context, MetaNotificationListener.class));

            if (controllers == null || controllers.isEmpty()) {
                Log.d(TAG, "No active media sessions");
                return;
            }

            // Find the first active playback controller
            MediaController controller = null;
            for (MediaController c : controllers) {
                if (c.getPlaybackState() != null &&
                        c.getPlaybackState().getState() ==
                                android.media.session.PlaybackState.STATE_PLAYING) {
                    controller = c;
                    break;
                }
            }
            if (controller == null) {
                controller = controllers.get(0); // fallback
            }
            
            MediaMetadata metadata = controller.getMetadata();
            if (metadata == null) {
                Log.d(TAG, "No metadata found");
                return;
            }

            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);

            JSONObject json = new JSONObject();
            json.put("package", controller.getPackageName());
            json.put("title", title != null ? title : "");
            json.put("artist", artist != null ? artist : "");
            json.put("album", album != null ? album : "");

            MetadataWriter.send(json.toString());
            Log.d(TAG, "Sent metadata: " + json);

        } catch (SecurityException e) {
            Log.e(TAG, "Missing notification access permission", e);
        } catch (Exception e) {
            Log.e(TAG, "updatePlayingSongInfo failed", e);
        }
    }
}