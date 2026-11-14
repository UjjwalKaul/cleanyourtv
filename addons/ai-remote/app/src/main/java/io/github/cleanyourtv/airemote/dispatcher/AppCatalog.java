package io.github.cleanyourtv.airemote.dispatcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class AppCatalog {

    public static class AppEntry {
        public final String name;
        public final String packageName;

        public AppEntry(String name, String packageName) {
            this.name = name;
            this.packageName = packageName;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("name", name);
            obj.put("package", packageName);
            return obj;
        }
    }

    private AppCatalog() {}

    public static List<AppEntry> getInstalledApps(Context context) {
        PackageManager pm = context.getPackageManager();
        List<AppEntry> entries = new ArrayList<>();
        String selfPkg = context.getPackageName();

        // 1. Query Leanback launcher apps
        Intent leanbackIntent = new Intent(Intent.ACTION_MAIN);
        leanbackIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> activities = pm.queryIntentActivities(leanbackIntent, 0);

        // 2. Fallback to standard launcher apps if none or few
        Intent standardIntent = new Intent(Intent.ACTION_MAIN);
        standardIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> standard = pm.queryIntentActivities(standardIntent, 0);

        for (ResolveInfo ri : activities) {
            addIfNew(entries, pm, ri, selfPkg);
        }
        for (ResolveInfo ri : standard) {
            addIfNew(entries, pm, ri, selfPkg);
        }

        // 3. Scan all installed packages that declare launcher/leanback intents
        try {
            List<android.content.pm.PackageInfo> installedPackages = pm.getInstalledPackages(0);
            for (android.content.pm.PackageInfo pi : installedPackages) {
                if (pi.packageName == null || pi.packageName.equals(selfPkg)) continue;
                boolean alreadyInList = false;
                for (AppEntry e : entries) {
                    if (e.packageName.equals(pi.packageName)) {
                        alreadyInList = true;
                        break;
                    }
                }
                if (alreadyInList) continue;

                Intent li = pm.getLeanbackLaunchIntentForPackage(pi.packageName);
                if (li == null) {
                    li = pm.getLaunchIntentForPackage(pi.packageName);
                }
                if (li != null) {
                    CharSequence label = pi.applicationInfo != null ? pi.applicationInfo.loadLabel(pm) : null;
                    String name = (label != null && label.length() > 0) ? label.toString() : pi.packageName;
                    entries.add(new AppEntry(name, pi.packageName));
                }
            }
        } catch (Exception ignored) {}

        // 4. Ensure well-known TV apps are included if installed
        String[][] commonTvApps = {
            {"YouTube", "com.google.android.youtube.tv"},
            {"YouTube Music", "com.google.android.youtube.tvmusic"},
            {"SmartTube", "com.teamsmart.videomanager.tv"},
            {"SmartTube Next", "com.liskovsoft.videomanager"},
            {"Netflix", "com.netflix.ninja"},
            {"Prime Video", "com.amazon.amazonvideo.livingroom"},
            {"Disney+", "com.disney.disneyplus"},
            {"Apple TV", "com.apple.atve.androidtv.appletv"},
            {"Spotify", "com.spotify.tv.android"},
            {"VLC", "org.videolan.vlc"},
            {"Kodi", "org.xbmc.kodi"},
            {"Plex", "com.plexapp.android"},
            {"Twitch", "tv.twitch.android.app"},
            {"Crunchyroll", "com.crunchyroll.crunchyroll"},
            {"Play Store", "com.android.vending"},
            {"Settings", "com.android.tv.settings"}
        };
        for (String[] app : commonTvApps) {
            String appName = app[0];
            String appPkg = app[1];
            try {
                pm.getPackageInfo(appPkg, 0);
                boolean exists = false;
                for (AppEntry e : entries) {
                    if (e.packageName.equals(appPkg)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    CharSequence label = null;
                    try {
                        label = pm.getApplicationLabel(pm.getApplicationInfo(appPkg, 0));
                    } catch (Exception ignored) {}
                    String finalName = (label != null && label.length() > 0) ? label.toString() : appName;
                    entries.add(new AppEntry(finalName, appPkg));
                }
            } catch (PackageManager.NameNotFoundException ignored) {}
        }

        // Sort alphabetically by name
        Collections.sort(entries, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });

        return entries;
    }

    private static void addIfNew(List<AppEntry> list, PackageManager pm, ResolveInfo ri, String selfPkg) {
        if (ri.activityInfo == null || ri.activityInfo.packageName == null) return;
        String pkg = ri.activityInfo.packageName;
        if (pkg.equals(selfPkg)) return; // Exclude Web Remote itself

        for (AppEntry e : list) {
            if (e.packageName.equals(pkg)) return; // Already present
        }

        CharSequence label = ri.loadLabel(pm);
        String name = label != null ? label.toString() : pkg;
        list.add(new AppEntry(name, pkg));
    }

    public static JSONArray getAppsJsonArray(Context context) {
        JSONArray arr = new JSONArray();
        try {
            for (AppEntry entry : getInstalledApps(context)) {
                arr.put(entry.toJson());
            }
        } catch (JSONException ignored) {}
        return arr;
    }

    public static String getAppsJson(Context context) {
        return getAppsJsonArray(context).toString();
    }
}
