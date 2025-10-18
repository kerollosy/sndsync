package com.kerollosy.metadata;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
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

    private String getBitmapBase64(Bitmap bitmap) {
        if (bitmap == null) return null;

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            byte[] imageBytes = baos.toByteArray();
            baos.close();

            return android.util.Base64.encodeToString(imageBytes, android.util.Base64.DEFAULT);
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert bitmap to base64", e);
            return null;
        }
    }

    private void updatePlayingSongInfo(Context context) {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> controllers =
                    msm.getActiveSessions(new ComponentName(context, MetaNotificationListener.class));

            if (controllers.isEmpty()) {
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
            long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            Bitmap albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);

//            Log.d(TAG, albumArt.toString());

            JSONObject json = new JSONObject();
            json.put("package", controller.getPackageName());
            json.put("title", title != null ? title : "");
            json.put("artist", artist != null ? artist : "");
            json.put("album", album != null ? album : "");
            json.put("duration", duration > 0 ? duration : 0);
            json.put("albumArt", getBitmapBase64(albumArt) != null ? getBitmapBase64(albumArt) : "");

            MetadataWriter.send(json.toString());
        } catch (SecurityException e) {
            Log.e(TAG, "Missing notification access permission", e);
        } catch (Exception e) {
            Log.e(TAG, "updatePlayingSongInfo failed", e);
        }
    }
}