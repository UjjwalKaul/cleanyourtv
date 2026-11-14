package io.github.cleanyourtv.airemote.dispatcher;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.cleanyourtv.airemote.service.RemoteAccessibilityService;

public final class CommandDispatcher {

    private static final String PKG_REGEX = "^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$";
    private static final Set<String> ALLOWED_KEYS = new HashSet<>();
    static {
        ALLOWED_KEYS.add("DPAD_UP");
        ALLOWED_KEYS.add("DPAD_DOWN");
        ALLOWED_KEYS.add("DPAD_LEFT");
        ALLOWED_KEYS.add("DPAD_RIGHT");
        ALLOWED_KEYS.add("DPAD_CENTER");
        ALLOWED_KEYS.add("BACK");
        ALLOWED_KEYS.add("HOME");
        ALLOWED_KEYS.add("VOLUME_UP");
        ALLOWED_KEYS.add("VOLUME_DOWN");
        ALLOWED_KEYS.add("MUTE");
        ALLOWED_KEYS.add("UNMUTE");
        ALLOWED_KEYS.add("TOGGLE_MUTE");
        ALLOWED_KEYS.add("PLAY_PAUSE");
    }

    private final Context context;
    private final ExecutorService keyExecutor = Executors.newSingleThreadExecutor();

    public CommandDispatcher(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject execute(JSONObject cmd) {
        JSONObject response = new JSONObject();
        try {
            String type = cmd.optString("type", "");
            switch (type) {
                case "LAUNCH_APP": {
                    String pkg = cmd.optString("package", "").trim();
                    if (pkg.isEmpty() || !pkg.matches(PKG_REGEX)) {
                        response.put("status", "error");
                        response.put("message", "Invalid package name format");
                        return response;
                    }

                    PackageManager pm = context.getPackageManager();
                    Intent launchIntent = pm.getLeanbackLaunchIntentForPackage(pkg);
                    if (launchIntent == null) {
                        launchIntent = pm.getLaunchIntentForPackage(pkg);
                    }
                    if (launchIntent == null) {
                        Intent query = new Intent(Intent.ACTION_MAIN);
                        query.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                        query.setPackage(pkg);
                        java.util.List<android.content.pm.ResolveInfo> list = pm.queryIntentActivities(query, 0);
                        if (list != null && !list.isEmpty()) {
                            android.content.pm.ResolveInfo ri = list.get(0);
                            launchIntent = new Intent(Intent.ACTION_MAIN);
                            launchIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                            launchIntent.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                        }
                    }
                    if (launchIntent == null) {
                        Intent query = new Intent(Intent.ACTION_MAIN);
                        query.addCategory(Intent.CATEGORY_LAUNCHER);
                        query.setPackage(pkg);
                        java.util.List<android.content.pm.ResolveInfo> list = pm.queryIntentActivities(query, 0);
                        if (list != null && !list.isEmpty()) {
                            android.content.pm.ResolveInfo ri = list.get(0);
                            launchIntent = new Intent(Intent.ACTION_MAIN);
                            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                            launchIntent.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                        }
                    }
                    if (launchIntent == null) {
                        Intent query = new Intent(Intent.ACTION_MAIN);
                        query.setPackage(pkg);
                        java.util.List<android.content.pm.ResolveInfo> list = pm.queryIntentActivities(query, 0);
                        if (list != null && !list.isEmpty()) {
                            android.content.pm.ResolveInfo ri = list.get(0);
                            launchIntent = new Intent(Intent.ACTION_MAIN);
                            launchIntent.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                        }
                    }
                    // Explicit component fallbacks for known TV packages
                    if (launchIntent == null && "com.google.android.youtube.tv".equals(pkg)) {
                        launchIntent = new Intent(Intent.ACTION_MAIN);
                        launchIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                        launchIntent.setClassName("com.google.android.youtube.tv", "com.google.android.apps.youtube.tv.activity.ShellActivity");
                    }
                    if (launchIntent == null && "com.google.android.youtube.tvmusic".equals(pkg)) {
                        launchIntent = new Intent(Intent.ACTION_MAIN);
                        launchIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                        launchIntent.setClassName("com.google.android.youtube.tvmusic", "com.google.android.apps.youtube.tvmusic.activity.ShellActivity");
                    }
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        context.startActivity(launchIntent);

                        String appName = cmd.optString("name", "").trim();
                        if (appName.isEmpty()) {
                            try {
                                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                                CharSequence label = pm.getApplicationLabel(ai);
                                if (label != null && label.length() > 0) {
                                    appName = label.toString();
                                }
                            } catch (Exception ignored) {}
                        }
                        if (appName.isEmpty()) {
                            appName = pkg;
                        }

                        response.put("status", "success");
                        response.put("launched", appName);
                        response.put("package", pkg);
                    } else {
                        response.put("status", "error");
                        response.put("message", "App not found or cannot be launched: " + pkg);
                    }
                    break;
                }

                case "DEEP_LINK": {
                    String uriStr = cmd.optString("uri", "").trim();
                    if (uriStr.isEmpty()) {
                        response.put("status", "error");
                        response.put("message", "Empty URI");
                        return response;
                    }

                    Uri uri = Uri.parse(uriStr);
                    String scheme = uri.getScheme();
                    if (scheme == null || (!scheme.equalsIgnoreCase("http") &&
                                           !scheme.equalsIgnoreCase("https") &&
                                           !scheme.equalsIgnoreCase("netflix") &&
                                           !scheme.equalsIgnoreCase("vnd.youtube"))) {
                        response.put("status", "error");
                        response.put("message", "Unsupported or insecure URI scheme: " + scheme);
                        return response;
                    }

                    Intent viewIntent = new Intent(Intent.ACTION_VIEW, uri);
                    viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    String targetPkg = cmd.optString("package", "").trim();
                    if (!targetPkg.isEmpty() && targetPkg.matches(PKG_REGEX)) {
                        viewIntent.setPackage(targetPkg);
                    }
                    context.startActivity(viewIntent);
                    response.put("status", "success");
                    break;
                }

                case "KEY": {
                    String code = cmd.optString("code", "").toUpperCase();
                    if (!ALLOWED_KEYS.contains(code)) {
                        response.put("status", "error");
                        response.put("message", "Key code not allowed: " + code);
                        return response;
                    }

                    // Direct system handlers for Home, Volume, Mute, Media
                    if (handleSystemKey(code)) {
                        response.put("status", "success");
                        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                        if (am != null) {
                            response.put("isMuted", am.isStreamMute(AudioManager.STREAM_MUSIC));
                        }
                        return response;
                    }

                    int keyCode = mapKeyCode(code);
                    if (keyCode != 0) {
                        dispatchKey(keyCode);
                        response.put("status", "success");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Unsupported key code");
                    }
                    break;
                }

                case "GET_APPS": {
                    response.put("status", "success");
                    response.put("apps", AppCatalog.getInstalledApps(context));
                    break;
                }

                case "TEXT_INPUT": {
                    String text = cmd.optString("text", "");
                    if (!text.isEmpty()) {
                        dispatchText(text);
                        response.put("status", "success");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Empty text");
                    }
                    break;
                }

                default:
                    response.put("status", "error");
                    response.put("message", "Unknown command type: " + type);
                    break;
            }
        } catch (Exception e) {
            try {
                response.put("status", "error");
                response.put("message", e.getMessage());
            } catch (JSONException ignored) {}
        }
        return response;
    }

    private boolean handleSystemKey(String code) {
        switch (code) {
            case "HOME": {
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(homeIntent);
                return true;
            }
            case "VOLUME_UP": {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                    return true;
                }
                return false;
            }
            case "VOLUME_DOWN": {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                    return true;
                }
                return false;
            }
            case "MUTE":
            case "UNMUTE":
            case "TOGGLE_MUTE": {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    if ("UNMUTE".equals(code)) {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
                    } else if ("MUTE".equals(code)) {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
                    } else {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                    }
                    return true;
                }
                return false;
            }
            case "PLAY_PAUSE": {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    long now = SystemClock.uptimeMillis();
                    KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
                    KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
                    am.dispatchMediaKeyEvent(down);
                    am.dispatchMediaKeyEvent(up);
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    private int mapKeyCode(String code) {
        switch (code) {
            case "DPAD_UP": return KeyEvent.KEYCODE_DPAD_UP;
            case "DPAD_DOWN": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "DPAD_LEFT": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "DPAD_RIGHT": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "DPAD_CENTER": return KeyEvent.KEYCODE_DPAD_CENTER;
            case "BACK": return KeyEvent.KEYCODE_BACK;
            default: return 0;
        }
    }

    private void dispatchKey(final int keyCode) {
        keyExecutor.execute(new Runnable() {
            @Override
            public void run() {
                // 1. Try AccessibilityService first (System-wide navigation)
                if (RemoteAccessibilityService.sInstance != null) {
                    if (RemoteAccessibilityService.sInstance.dispatchRemoteKey(keyCode)) {
                        return;
                    }
                }

                // 2. Fallback to Instrumentation (Window / foreground navigation)
                try {
                    Instrumentation inst = new Instrumentation();
                    inst.sendKeyDownUpSync(keyCode);
                } catch (Exception ignored) {}
            }
        });
    }

    private void dispatchText(final String text) {
        keyExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (RemoteAccessibilityService.sInstance != null) {
                    RemoteAccessibilityService.sInstance.dispatchText(text);
                }
            }
        });
    }

    public void shutdown() {
        keyExecutor.shutdown();
    }
}
