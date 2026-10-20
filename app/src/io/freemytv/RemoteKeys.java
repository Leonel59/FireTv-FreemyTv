package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/**
 * The remote's app buttons. On TCL the stock home screen opens the apps for Netflix and Prime (and TCL Channel); with
 * it off, the keys still reach us, so we open the app the user picked (Settings > Remote Buttons). Disney and
 * Globoplay are opened by the system itself, so they are not listed. The YouTube button (BUTTON_3) never reaches an
 * app's window; only the accessibility service's key filter can see it.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** Opens the app for this key on its first press. True if the key is one of ours (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        boolean ours = false;
        for (int key : Prefs.KEYS) ours |= key == e.getKeyCode();
        if (!ours) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            String pkg = Prefs.keyApp(e.getKeyCode());
            Intent intent = pkg.isEmpty() ? null : HomeApps.launchIntent(c.getPackageManager(), pkg);
            AppLog.w("remote key " + e.getKeyCode() + " -> " + (pkg.isEmpty() ? "nothing" : pkg + (intent == null ? " (not installed)" : "")));
            if (intent != null) c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return true;
    }
}
