package io.github.cleanyourtv.airemote.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Lightweight accessibility helper for system-wide D-pad navigation and Back key dispatch.
 */
public class RemoteAccessibilityService extends AccessibilityService {

    public static volatile RemoteAccessibilityService sInstance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    public boolean dispatchRemoteKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return performGlobalAction(GLOBAL_ACTION_BACK);
        }
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            return performGlobalAction(GLOBAL_ACTION_HOME);
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) {
                focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            }
            if (focused == null) {
                AccessibilityNodeInfo first = findFirstFocusable(root);
                if (first != null) {
                    first.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    first.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                    first.recycle();
                }
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                boolean clicked = focused.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (!clicked) {
                    AccessibilityNodeInfo parent = focused.getParent();
                    while (parent != null && !clicked) {
                        clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        AccessibilityNodeInfo nextParent = parent.getParent();
                        parent.recycle();
                        parent = nextParent;
                    }
                }
                focused.recycle();
                return true;
            }

            int direction = -1;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) direction = View.FOCUS_UP;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) direction = View.FOCUS_DOWN;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) direction = View.FOCUS_LEFT;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) direction = View.FOCUS_RIGHT;

            if (direction != -1) {
                AccessibilityNodeInfo next = focused.focusSearch(direction);
                if (next != null) {
                    boolean focusedOk = next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    if (!focusedOk) {
                        next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                    }
                    next.recycle();
                    focused.recycle();
                    return true;
                }
            }
            focused.recycle();
        } catch (Exception ignored) {
        } finally {
            try {
                root.recycle();
            } catch (Exception ignored) {}
        }
        return false;
    }

    public boolean dispatchText(String text) {
        if (text == null) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) {
                focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            }
            if (focused != null) {
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                boolean ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                focused.recycle();
                return ok;
            }
        } catch (Exception ignored) {
        } finally {
            try {
                root.recycle();
            } catch (Exception ignored) {}
        }
        return false;
    }

    private AccessibilityNodeInfo findFirstFocusable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isFocusable() || node.isClickable()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo res = findFirstFocusable(child);
                if (res != null) {
                    if (res != child) child.recycle();
                    return res;
                }
                child.recycle();
            }
        }
        return null;
    }
}
