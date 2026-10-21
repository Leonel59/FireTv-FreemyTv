package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, and on Fire OS intercepts window changes.
 * When Fire OS displays com.amazon.tv.launcher (e.g. on pressing the remote's Home button),
 * it immediately brings Free My TV HomeActivity to the front.
 */
public class RemoteKeyService extends AccessibilityService {
    private static long lastRedirect = 0;

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                long now = System.currentTimeMillis();
                if (now - lastRedirect > 150) {
                    lastRedirect = now;
                    Intent home = new Intent(this, HomeActivity.class);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(home);
                }
            }
            return true;
        }
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            CharSequence pkg = event.getPackageName();
            CharSequence cls = event.getClassName();
            if (pkg != null) {
                String p = pkg.toString();
                String c = cls != null ? cls.toString() : "";

                // NEVER intercept Settings activities or system setup
                if (p.contains("settings") || p.contains("Settings")
                        || c.contains("Settings") || c.contains("settings")
                        || c.contains("DeviceActivity") || c.contains("NetworkActivity")
                        || c.contains("ApplicationsActivity") || c.contains("Setup")) {
                    return;
                }

                // Redirect when Amazon Home screen, HomeStarter or Amazon Screensaver is displayed
                if ("com.amazon.firehomestarter".equals(p)
                        || "com.amazon.ftv.screensaver".equals(p)
                        || "com.amazon.tv.launcher.ui.HomeActivity_vNext".equals(c)
                        || ("com.amazon.tv.launcher".equals(p) && !c.contains("MainSettingsActivity") && !c.contains("SettingsActivity"))) {
                    long now = System.currentTimeMillis();
                    if (now - lastRedirect > 150) {
                        lastRedirect = now;
                        Intent home = new Intent(this, HomeActivity.class);
                        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(home);
                    }
                }
            }
        }
    }

    @Override public void onInterrupt() {}
}

