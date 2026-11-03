package com.utk.speedbeep;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Public GitHub CSV -> immutable local cache + independently editable per-profile
 * override layer. Driving never depends on network connectivity.
 */
public final class CentralRoadStore {
    public static final String MODE_LOCAL = "local";
    public static final String MODE_VANILLA = "vanilla";
    public static final String MODE_PLUS_LOCAL = "vanilla_plus_local";
    public static final String MASTER_URL =
            "https://raw.githubusercontent.com/standchemchennai-cmyk/speedbeep/main/speed_limits.csv";

    private static final String PREFERENCES = "speedbeep_central_csv";
    private static final String KEY_MODE = "source_mode";
    private static final String KEY_SYNC = "last_successful_sync";
    private static final String MASTER_FILE = "central_master.csv";
    private static final String OVERLAY_DIR = "central_overrides";
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Object LOCK = new Object();

    private CentralRoadStore() {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public static String mode(Context context) {
        String saved = preferences(context).getString(KEY_MODE, MODE_LOCAL);
        return MODE_VANILLA.equals(saved) || MODE_PLUS_LOCAL.equals(saved)
                ? saved : MODE_LOCAL;
    }

    public static void selectMode(Context context, String nextMode) throws IOException {
        if (!MODE_LOCAL.equals(nextMode) && !MODE_VANILLA.equals(nextMode)
                && !MODE_PLUS_LOCAL.equals(nextMode))
            throw new IOException("Unknown road database mode");
        if (!MODE_LOCAL.equals(nextMode)) readMaster(context);
        if (!preferences(context).edit().putString(KEY_MODE, nextMode).commit())
            throw new IOException("Could not save road database mode");
    }

    public static String modeLabel(String mode) {
        if (MODE_VANILLA.equals(mode)) return "GitHub vanilla (read-only)";
        if (MODE_PLUS_LOCAL.equals(mode)) return "GitHub vanilla + local overrides";
        return "Standalone local CSV";
    }

    public static long lastSync(Context context) {
        return preferences(context).getLong(KEY_SYNC, 0L);
    }

    private static File masterFile(Context context) {
        return new File(context.getFilesDir(), MASTER_FILE);
    }

    private static File overlayFile(Context context, String profileId) throws IOException {
        RoadProfileStore.fileFor(context, profileId); // Verify registered safe ID.
        File folder = new File(context.getFilesDir(), OVERLAY_DIR);
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Cannot create local override folder");
        return new File(folder, profileId + ".csv");
    }

    public static List<RoadSegment> readMaster(Context context) throws IOException {
        File cached = masterFile(context);
        if (cached.isFile()) {
            try (InputStream in = new FileInputStream(cached)) {
                return CsvRoadRepository.read(in);
            } catch (IOException invalidCache) {
                // Bundled CSV remains a safe fallback for a corrupt cache.
            }
        }
        try (InputStream in = context.getAssets().open(LocalRoadStore.FILE_NAME)) {
            return CsvRoadRepository.read(in);
        }
    }

    public static List<RoadSegment> readOverrides(Context context, String id) throws IOException {
        File file = overlayFile(context, id);
        if (!file.isFile()) return Collections.emptyList();
        try (InputStream in = new FileInputStream(file)) {
            return CsvRoadRepository.read(in);
        }
    }

    public static int overrideCount(Context context, String id) throws IOException {
        return readOverrides(context, id).size();
    }

    public static List<RoadSegment> loadEffective(Context context, String id,
                                                   String sourceMode) throws IOException {
        if (MODE_LOCAL.equals(sourceMode)) {
            try (InputStream in = new FileInputStream(RoadProfileStore.fileFor(context, id))) {
                return CsvRoadRepository.read(in);
            }
        }
        List<RoadSegment> base = readMaster(context);
        if (MODE_VANILLA.equals(sourceMode)) return base;
        return CentralRoadMerge.merge(base, readOverrides(context, id));
    }

    public static void addOverride(Context context, String id,
                                    List<RoadSegment> parts, double newLimit,
                                    String roadName) throws IOException {
        synchronized (LOCK) {
            List<RoadSegment> edited = CentralRoadMerge.add(
                    readOverrides(context, id), parts, newLimit, roadName);
            byte[] csv = LocalRoadStore.toCsvBytes(edited);
            // Parsing before replacement prevents a bad edit from losing a good file.
            try (InputStream in = new ByteArrayInputStream(csv)) {
                CsvRoadRepository.read(in);
            }
            replaceAtomically(overlayFile(context, id), csv);
        }
    }

    public static void copyOverrides(Context context, String from, String to)
            throws IOException {
        synchronized (LOCK) {
            File original = overlayFile(context, from);
            if (!original.exists()) return;
            try (InputStream in = new FileInputStream(original)) {
                byte[] copy = readBounded(in);
                replaceAtomically(overlayFile(context, to), copy);
            }
        }
    }

    public static void deleteOverrides(Context context, String id) {
        File folder = new File(context.getFilesDir(), OVERLAY_DIR);
        if (id != null && (id.equals("default") || id.matches("p_[0-9a-f-]{36}"))) {
            new File(folder, id + ".csv").delete();
        }
    }

    public static void export(Context context, String id, String sourceMode,
                              OutputStream out) throws IOException {
        if (MODE_LOCAL.equals(sourceMode)) {
            try (InputStream in = new FileInputStream(RoadProfileStore.fileFor(context, id))) {
                copy(in, out);
            }
        } else if (MODE_VANILLA.equals(sourceMode)) {
            File cached = masterFile(context);
            if (cached.isFile()) {
                try (InputStream in = new FileInputStream(cached)) {
                    copy(in, out);
                    return;
                } catch (IOException ex) {
                    // Re-serialize the bundled fallback when cached data is invalid.
                }
            }
            out.write(LocalRoadStore.toCsvBytes(readMaster(context)));
        } else {
            out.write(LocalRoadStore.toCsvBytes(loadEffective(context, id, sourceMode)));
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int length;
        while ((length = in.read(chunk)) != -1) {
            if (out.size() + length > MAX_BYTES) throw new IOException("CSV exceeds 20 MB");
            out.write(chunk, 0, length);
        }
        return out.toByteArray();
    }

    private static void replaceAtomically(File file, byte[] content) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            out.write(content);
            atomic.finishWrite(out);
        } catch (IOException | RuntimeException ex) {
            if (out != null) atomic.failWrite(out);
            throw ex;
        }
    }

    /**
     * Called only on a background thread. Failed/unavailable downloads leave the
     * previous cache completely untouched.
     *
     * The URL is fixed and public: no GitHub token is stored on the phone.
     */
    public static int refreshOnline(Context context) throws IOException {
        synchronized (LOCK) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(MASTER_URL).openConnection();
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept", "text/csv,text/plain,*/*");
                connection.setRequestProperty("Cache-Control", "no-cache");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)
                    throw new IOException("GitHub returned HTTP " + connection.getResponseCode());
                if (connection.getContentLengthLong() > MAX_BYTES)
                    throw new IOException("GitHub CSV exceeds 20 MB");
                byte[] bytes;
                try (InputStream in = connection.getInputStream()) {
                    bytes = readBounded(in);
                }
                List<RoadSegment> parsed;
                try (InputStream verify = new ByteArrayInputStream(bytes)) {
                    parsed = CsvRoadRepository.read(verify);
                }
                // Guard against accidentally publishing a truncated database.
                int previous = readMaster(context).size();
                if (previous >= 1000 && parsed.size() < previous / 4)
                    throw new IOException("Online CSV unexpectedly shrank; keeping cached copy");
                replaceAtomically(masterFile(context), bytes);
                preferences(context).edit()
                        .putLong(KEY_SYNC, System.currentTimeMillis()).apply();
                return parsed.size();
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }
}
