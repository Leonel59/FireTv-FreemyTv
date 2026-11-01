package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dynamic wallpapers: the home screen and the screensaver each keep a small pool of fresh backgrounds (safe-for-work
 * pictures from Wallhaven's most-favorited) and change to the next one every hour (or what Settings says).
 *
 * Everything here is quiet and safe: it never blocks the UI (downloads run on one background thread), a failed download
 * only waits and tries again later, and with nothing downloaded the screens keep using the bundled wallpapers.
 * The views ask {@link #currentKey} now and then; when it changes they load {@link #decode} and fade to it.
 */
final class Wallpapers {
    private Wallpapers() {}

    static final int SCREENSAVER = 0, HOME = 1;
    private static final String[] DIRS = {"screensaver", "home"};

    /** Pictures kept per screen (the oldest go first), and how many we want before the first rotation. */
    private static final int KEEP = 24, MIN_POOL = 4;
    private static final long RETRY_MS = 3 * 60_000L, RETRY_FAR_MS = 15 * 60_000L;
    private static final int MAX_BYTES = 12 * 1024 * 1024;

    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wallpapers");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean fetching;
    private static final long[] nextTry = new long[2];

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
    }

    private static File dir(Context c, int kind) {
        File d = new File(new File(c.getApplicationContext().getFilesDir(), "wallpapers"), DIRS[kind]);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** The pictures of one screen, the newest first. */
    private static List<File> pool(Context c, int kind) {
        File[] files = dir(c, kind).listFiles((d, name) -> name.endsWith(".jpg"));
        List<File> list = new ArrayList<>();
        if (files != null) list.addAll(Arrays.asList(files));
        Collections.sort(list, (a, b) -> b.getName().compareTo(a.getName()));
        return list;
    }

    // MARK: - Which one is on now

    /** Length of one turn, in ms; days start at local midnight. */
    private static long slotOf(long now) {
        long period = Prefs.wallpaperMinutes() * 60_000L;
        long offset = java.util.TimeZone.getDefault().getOffset(now);
        return (now + offset) / period;
    }

    /**
     * The picture to show now, or null (dynamic wallpapers off, or nothing downloaded yet). It stays the same for a whole
     * turn; when the turn changes it moves to the newest picture not shown yet, or to a random other one.
     */
    static synchronized File current(Context c, int kind) {
        if (!Prefs.wallpapers()) return null;
        List<File> files = pool(c, kind);
        if (files.isEmpty()) return null;
        SharedPreferences sp = sp(c);
        long slot = slotOf(System.currentTimeMillis());
        String last = sp.getString("cur" + kind, "");
        File keep = null;
        for (File f : files) if (f.getName().equals(last)) keep = f;
        if (keep != null && sp.getLong("slot" + kind, -1) == slot) return keep;

        Set<String> shown = new HashSet<>(Arrays.asList(sp.getString("shown" + kind, "").split(",")));
        File next = null;
        for (File f : files) {
            if (!shown.contains(f.getName()) && !f.getName().equals(last)) {
                next = f;
                break;
            }
        }
        if (next == null) {
            // Everything was shown: start over (not with the one on screen).
            shown.clear();
            List<File> others = new ArrayList<>(files);
            if (others.size() > 1) others.remove(keep);
            next = others.get(new Random().nextInt(others.size()));
        }
        shown.add(next.getName());
        StringBuilder sb = new StringBuilder();
        for (String s : shown) if (!s.isEmpty()) sb.append(sb.length() == 0 ? "" : ",").append(s);
        sp.edit().putString("cur" + kind, next.getName()).putLong("slot" + kind, slot)
                .putString("shown" + kind, sb.toString()).apply();
        return next;
    }

    /** Identifies the picture on now (changes when it's another one), "" for the bundled wallpaper. */
    static String currentKey(Context c, int kind) {
        File f = current(c, kind);
        return f == null ? "" : f.getName();
    }

    // MARK: - Loading

    /**
     * The picture ready for the screen: 1920×1080 (cropped from the middle), with soft shadows baked in where the clock,
     * the weather, the prices and the shelf go, stronger if the picture is bright there, so the text always reads.
     * Slow (decoding): call it from a background thread. Null if the file is bad.
     */
    static Bitmap decode(File f) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= 1920 && bounds.outHeight / (opts.inSampleSize * 2) >= 1080) {
                opts.inSampleSize *= 2;
            }
            Bitmap src = BitmapFactory.decodeFile(f.getPath(), opts);
            if (src == null) return null;
            // Centered crop to 16:9.
            int sw = src.getWidth(), sh = src.getHeight();
            int cw = sw, ch = sh;
            if (sw * 9 > sh * 16) cw = sh * 16 / 9; else ch = sw * 9 / 16;
            Bitmap crop = Bitmap.createBitmap(src, (sw - cw) / 2, (sh - ch) / 2, cw, ch);
            if (crop != src) src.recycle();
            Bitmap out = crop.getWidth() == 1920 ? crop : Bitmap.createScaledBitmap(crop, 1920, 1080, true);
            if (out != crop) crop.recycle();
            if (!out.isMutable()) {
                Bitmap m = out.copy(Bitmap.Config.ARGB_8888, true);
                out.recycle();
                out = m;
            }
            return shade(out);
        } catch (Exception | OutOfMemoryError e) {
            AppLog.w("wallpaper decode: " + e.getMessage());
            return null;
        }
    }

    /** Average brightness (0 to 1) of a part of the picture, sampled. */
    private static float light(Bitmap b, float x0, float y0, float x1, float y1) {
        int w = b.getWidth(), h = b.getHeight();
        long sum = 0;
        int n = 0;
        for (int y = (int) (y0 * h); y < (int) (y1 * h); y += 12) {
            for (int x = (int) (x0 * w); x < (int) (x1 * w); x += 12) {
                int p = b.getPixel(x, y);
                sum += (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
                n++;
            }
        }
        return n == 0 ? 0 : sum / (255f * n);
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }

    private static Bitmap shade(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        float left = light(b, 0f, 0.12f, 0.48f, 0.88f), bottom = light(b, 0f, 0.74f, 1f, 1f);
        float sl = Math.max(0.22f, Math.min(0.78f, 0.30f + (left - 0.25f) * 0.95f));
        float sb = Math.max(0.22f, Math.min(0.72f, 0.28f + (bottom - 0.25f) * 0.9f));
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.DITHER_FLAG);
        // Left side: date, clock, weather and prices.
        p.setShader(new LinearGradient(0, 0, w * 0.66f, 0,
                new int[] {black(sl), black(0.62f * sl), black(0.2f * sl), 0},
                new float[] {0, 0.4f, 0.75f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w * 0.66f, h, p);
        // Bottom: the shelf of apps and the prices.
        p.setShader(new LinearGradient(0, h, 0, h * 0.5f,
                new int[] {black(sb), black(0.5f * sb), black(0.14f * sb), 0},
                new float[] {0, 0.4f, 0.75f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.5f, w, h, p);
        // Top: the buttons in the corner.
        p.setShader(new LinearGradient(0, 0, 0, h * 0.25f, new int[] {black(0.28f), black(0.07f), 0},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.25f, p);
        return b;
    }

    // MARK: - Fetching

    /** The wallpaper bundled in the app (for when dynamic wallpapers are off), 1920×1080; null if it can't be read. */
    static Bitmap bundled(Context c, int kind) {
        String[] names = kind == SCREENSAVER ? new String[] {"screensaver.jpg", "wallpaper.jpg"} : new String[] {"wallpaper.jpg"};
        for (String name : names) {
            try (InputStream in = c.getAssets().open(name)) {
                Bitmap raw = BitmapFactory.decodeStream(in);
                if (raw == null) continue;
                Bitmap out = Bitmap.createScaledBitmap(raw, 1920, 1080, true);
                if (out != raw) raw.recycle();
                return out;
            } catch (Exception | OutOfMemoryError ignored) {
                // try the next one
            }
        }
        return null;
    }

    /** Forgets the downloaded pictures (after the look changed): new ones come in the new style. */
    static synchronized void clear(Context context) {
        Context c = context.getApplicationContext();
        for (int kind = 0; kind < 2; kind++) {
            for (File f : pool(c, kind)) //noinspection ResultOfMethodCallIgnored
                f.delete();
            sp(c).edit().remove("cur" + kind).remove("slot" + kind).remove("shown" + kind).remove("fetched" + kind).apply();
            synchronized (nextTry) { nextTry[kind] = 0; }
        }
        refreshIfStale(c);
    }

    /**
     * Brings in a new picture for each screen when it's time (or when the pool is small), in the background. Cheap to
     * call often: it does nothing while a download is running or before the next try.
     */
    static void refreshIfStale(Context context) {
        final Context c = context.getApplicationContext();
        if (!Prefs.wallpapers() || fetching) return;
        long now = System.currentTimeMillis();
        boolean due = false;
        for (int kind = 0; kind < 2; kind++) if (needsOne(c, kind, now)) due = true;
        if (!due) return;
        fetching = true;
        worker.execute(() -> {
            try {
                for (int kind = 0; kind < 2; kind++) {
                    long t = System.currentTimeMillis();
                    if (!needsOne(c, kind, t)) continue;
                    boolean ok = fetchOne(c, kind);
                    long wait = ok ? 0 : (pool(c, kind).size() < MIN_POOL ? RETRY_MS : RETRY_FAR_MS);
                    synchronized (nextTry) { nextTry[kind] = System.currentTimeMillis() + wait; }
                    if (ok) {
                        sp(c).edit().putLong("fetched" + kind, System.currentTimeMillis()).apply();
                        prune(c, kind);
                    }
                }
            } catch (Throwable e) {
                AppLog.w("wallpapers: " + e);
            } finally {
                fetching = false;
            }
        });
    }

    private static boolean needsOne(Context c, int kind, long now) {
        synchronized (nextTry) { if (now < nextTry[kind]) return false; }
        int have = pool(c, kind).size();
        if (have < MIN_POOL) return true;
        long every = Prefs.wallpaperMinutes() * 60_000L;
        return now - sp(c).getLong("fetched" + kind, 0) >= every;
    }

    private static void prune(Context c, int kind) {
        List<File> files = pool(c, kind);
        String cur = sp(c).getString("cur" + kind, "");
        for (int i = KEEP; i < files.size(); i++) {
            if (!files.get(i).getName().equals(cur)) //noinspection ResultOfMethodCallIgnored
                files.get(i).delete();
        }
    }

    private static boolean fetchOne(Context c, int kind) {
        String style = Prefs.wallpaperStyle();
        boolean dark = style.equals("dark") || (style.equals("both") && new Random().nextBoolean());
        File tmp = new File(dir(c, kind), "download.tmp");
        try {
            String id = fetchWeb(c, tmp, dark);
            if (id == null || !valid(tmp)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            File out = new File(dir(c, kind), String.format("%013d-%s.jpg", System.currentTimeMillis(), id));
            if (tmp.renameTo(out)) {
                AppLog.w("wallpaper (" + DIRS[kind] + "): " + id);
                return true;
            }
        } catch (Exception | OutOfMemoryError e) {
            AppLog.w("wallpaper download: " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        return false;
    }

    /** A real picture, wide enough and about 16:9 (no broken or tiny downloads). */
    private static boolean valid(File f) {
        if (f.length() < 20_000) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth < 1280 || o.outHeight < 720) return false;
        float ratio = o.outWidth / (float) o.outHeight;
        return ratio > 1.5f && ratio < 2.2f;
    }

    // MARK: - Wallhaven (real pictures, safe-for-work only, the most favorited first)

    private static final String[] WEB_DARK = {"dark abstract", "cyberpunk", "neon", "dark minimal", "futuristic",
            "3d render dark", "iridescent", "dark city night", "dark space", "low poly dark", "synthwave", "dark fantasy"};
    private static final String[] WEB_VARIED = {"landscape", "mountains", "nebula", "architecture", "city skyline", "forest",
            "ocean", "aurora", "desert", "waterfall", "galaxy", "sunset lake"};

    private static String fetchWeb(Context c, File out, boolean dark) throws Exception {
        Random rnd = new Random();
        String[] topics = dark ? WEB_DARK : WEB_VARIED;
        String query = topics[rnd.nextInt(topics.length)];
        // A random page among the first ones: always good pictures, and plenty of variety.
        int page = 1 + rnd.nextInt(8);
        JSONArray data = search(query, page);
        if (data.length() == 0 && page > 1) data = search(query, 1);   // that topic has fewer pages
        Set<String> used = new HashSet<>(Arrays.asList(sp(c).getString("used", "").split(",")));
        List<JSONObject> options = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.getJSONObject(i);
            if (used.contains(o.optString("id")) || o.optLong("file_size") > MAX_BYTES || o.optString("path").isEmpty()) continue;
            if (!"sfw".equals(o.optString("purity", "sfw"))) continue;
            if (dark && !mostlyDark(o.optJSONArray("colors"))) continue;   // "dark" means dark: no bright pictures
            options.add(o);
        }
        if (options.isEmpty()) return null;
        JSONObject pick = options.get(rnd.nextInt(options.size()));
        download(pick.getString("path"), out, false);
        String id = pick.getString("id");
        // Remember the last 200 so the same one doesn't come twice.
        String all = sp(c).getString("used", "");
        String[] parts = all.isEmpty() ? new String[0] : all.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, parts.length - 199); i < parts.length; i++) sb.append(parts[i]).append(',');
        sp(c).edit().putString("used", sb.append(id).toString()).apply();
        return id;
    }

    private static JSONArray search(String query, int page) throws Exception {
        String url = "https://wallhaven.cc/api/v1/search?q=" + URLEncoder.encode(query, "UTF-8")
                + "&categories=100&purity=100&atleast=1920x1080&ratios=16x9&sorting=favorites&page=" + page;
        return new JSONObject(readText(url)).getJSONArray("data");
    }

    /** Wallhaven lists each picture's main colors: at least three of the five must be dark. */
    private static boolean mostlyDark(JSONArray colors) {
        if (colors == null || colors.length() == 0) return true;
        int dark = 0;
        for (int i = 0; i < colors.length(); i++) {
            try {
                int rgb = Integer.parseInt(colors.getString(i).replace("#", ""), 16);
                int luma = (((rgb >> 16) & 255) * 299 + ((rgb >> 8) & 255) * 587 + (rgb & 255) * 114) / 1000;
                if (luma < 115) dark++;
            } catch (Exception ignored) {
                // an odd value: not counted
            }
        }
        return dark * 5 >= colors.length() * 3;
    }

    // MARK: - HTTP

    private static HttpURLConnection open(String url, boolean slow) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15_000);
        h.setReadTimeout(slow ? 120_000 : 30_000);
        h.setRequestProperty("User-Agent", "FreeMyTV");
        h.setInstanceFollowRedirects(true);
        return h;
    }

    private static String readText(String url) throws Exception {
        HttpURLConnection h = open(url, false);
        try {
            if (h.getResponseCode() != 200) throw new java.io.IOException("HTTP " + h.getResponseCode());
            try (InputStream in = h.getInputStream()) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    b.write(buf, 0, n);
                    if (b.size() > 2_000_000) throw new java.io.IOException("too long");
                }
                return b.toString("UTF-8");
            }
        } finally {
            h.disconnect();
        }
    }

    private static void download(String url, File out, boolean slow) throws Exception {
        HttpURLConnection h = open(url, slow);
        try {
            if (h.getResponseCode() != 200) throw new java.io.IOException("HTTP " + h.getResponseCode());
            String type = h.getContentType();
            if (type == null || !type.startsWith("image/")) throw new java.io.IOException("not an image: " + type);
            long total = 0;
            try (InputStream in = h.getInputStream(); FileOutputStream f = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > MAX_BYTES) throw new java.io.IOException("too big");
                    f.write(buf, 0, n);
                }
                f.getFD().sync();
            }
        } finally {
            h.disconnect();
        }
    }
}
