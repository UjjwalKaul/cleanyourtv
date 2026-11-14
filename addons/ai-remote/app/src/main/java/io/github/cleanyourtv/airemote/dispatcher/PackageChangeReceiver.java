package io.github.cleanyourtv.airemote.dispatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import io.github.cleanyourtv.airemote.MainActivity;

/**
 * Listens for package install/uninstall events and syncs updated app catalog to phones.
 */
public final class PackageChangeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_PACKAGE_ADDED.equals(action) || Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
            try {
                JSONObject updateMsg = new JSONObject();
                updateMsg.put("type", "APPS_UPDATED");
                updateMsg.put("apps", AppCatalog.getAppsJsonArray(context));

                if (MainActivity.sServerInstance != null) {
                    MainActivity.sServerInstance.broadcast(updateMsg.toString());
                }
            } catch (Exception ignored) {}
        }
    }
}
