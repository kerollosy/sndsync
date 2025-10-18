package com.kerollosy.metada;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String TAG = "sndsync-meta";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(TAG, "MainActivity started");

        if (!hasNotificationAccess()) {
            Toast.makeText(this, "Please grant notification access in settings", Toast.LENGTH_LONG).show();

            // Open notification settings
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        } else {
            Log.d(TAG, "Notification access already granted, service active");
        }

        // Close activity immediately (service runs in background)
        finish();
    }

    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;

        ComponentName cn = new ComponentName(this, MetaNotificationListener.class);
        return enabled.contains(cn.flattenToString());
    }
}