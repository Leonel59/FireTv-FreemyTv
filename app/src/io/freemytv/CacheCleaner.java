package io.freemytv;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import java.io.File;
import java.util.UUID;

/**
 * High-efficiency cache cleaner for Fire TV / Android TV.
 * Uses both system StorageManager cache eviction and privileged local ADB trim-caches
 * to purge application caches across all installed apps without deleting user logins or app data.
 */
public final class CacheCleaner {
    private CacheCleaner() {}

    public static final class Result {
        public final long bytesBefore;
        public final long bytesAfter;
        public final long bytesFreed;
        public final long totalBytes;
        public final boolean adbUsed;
        public final boolean storageManagerUsed;
        public final String message;

        public Result(long bytesBefore, long bytesAfter, long totalBytes, boolean adbUsed, boolean storageManagerUsed, String message) {
            this.bytesBefore = bytesBefore;
            this.bytesAfter = bytesAfter;
            this.bytesFreed = Math.max(0, bytesAfter - bytesBefore);
            this.totalBytes = totalBytes;
            this.adbUsed = adbUsed;
            this.storageManagerUsed = storageManagerUsed;
            this.message = message;
        }

        public String formattedFreed() {
            return formatSize(bytesFreed);
        }

        public String formattedAvailable() {
            return formatSize(bytesAfter);
        }

        public String formattedTotal() {
            return formatSize(totalBytes);
        }
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 MB";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024.0) {
            return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.0f MB", mb);
    }

    public static long getAvailableBytes() {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                return stat.getAvailableBytes();
            } else {
                return ((long) stat.getAvailableBlocks()) * stat.getBlockSize();
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    public static long getTotalBytes() {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                return stat.getTotalBytes();
            } else {
                return ((long) stat.getBlockCount()) * stat.getBlockSize();
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Executes complete cache clearing across all apps.
     */
    public static Result clean(Context context) {
        long before = getAvailableBytes();
        long total = getTotalBytes();
        boolean adbSuccess = false;
        boolean smSuccess = false;
        StringBuilder log = new StringBuilder();

        // 1. StorageManager allocatable cache eviction (official Android system API)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
                if (sm != null) {
                    UUID uuid = sm.getUuidForPath(context.getFilesDir());
                    long allocatable = sm.getAllocatableBytes(uuid);
                    if (allocatable > 0) {
                        sm.allocateBytes(uuid, allocatable);
                        smSuccess = true;
                        log.append("StorageManager: ").append(formatSize(allocatable)).append(". ");
                    }
                }
            }
        } catch (Throwable t) {
            AppLog.w("StorageManager trim error: " + t.getMessage());
        }

        // 2. High-privilege local ADB cache trim
        try (Adb adb = Adb.connect(context, 4000, null)) {
            adb.shell("cmd package trim-caches 100G internal");
            adb.shell("rm -rf /sdcard/Android/data/*/cache/* 2>/dev/null");
            adbSuccess = true;
            log.append("ADB trim-caches OK. ");
        } catch (Throwable t) {
            android.util.Log.e("FreeMyTV", "Adb cleaner error: " + t, t);
        }

        // 3. Local app cache cleanup
        try {
            deleteRecursive(context.getCacheDir());
            File ext = context.getExternalCacheDir();
            if (ext != null) deleteRecursive(ext);
        } catch (Throwable ignored) {}

        // Settle file system stat
        try { Thread.sleep(250); } catch (InterruptedException ignored) {}

        long after = getAvailableBytes();
        long freed = Math.max(0, after - before);

        String msg;
        if (freed > 0) {
            msg = "¡Se liberaron " + formatSize(freed) + " de espacio!";
        } else {
            msg = "El almacenamiento ya se encontraba en estado óptimo.";
        }

        return new Result(before, after, total, adbSuccess, smSuccess, msg);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File child : files) deleteRecursive(child);
        }
        f.delete();
    }
}
