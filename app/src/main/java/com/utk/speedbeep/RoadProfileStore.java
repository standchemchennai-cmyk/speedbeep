package com.utk.speedbeep;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Named, isolated CSV databases in app-private storage.
 *
 * First-use migration COPIES the existing speed_limits.csv (including saved
 * local edits) to road_profiles/default.csv. It never deletes the legacy CSV.
 * The initial default profile is byte-for-byte identical to the old database.
 */
public final class RoadProfileStore {
    private static final String PREFERENCES = "speedbeep_road_profiles";
    private static final String ACTIVE = "active_id";
    private static final String IDS = "profile_ids";
    private static final String LABEL_PREFIX = "label_";
    private static final String DEFAULT_ID = "default";
    private static final String DIRECTORY = "road_profiles";
    private static final long MAX_IMPORT_BYTES = 20L * 1024L * 1024L;
    private static final Object LOCK = new Object();

    public static final class Profile {
        public final String id;
        public final String name;
        Profile(String id, String name) { this.id = id; this.name = name; }
    }

    private RoadProfileStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static File directory(Context context) throws IOException {
        File folder = new File(context.getFilesDir(), DIRECTORY);
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Cannot create road profile directory");
        return folder;
    }

    private static File path(Context context, String id) throws IOException {
        // Only internally generated UUIDs and the fixed migration ID are valid.
        if (!DEFAULT_ID.equals(id) && !id.matches("p_[0-9a-f-]{36}"))
            throw new IOException("Invalid profile ID");
        return new File(directory(context), id + ".csv");
    }

    private static void copy(InputStream in, File out) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(out)) {
            byte[] bytes = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(bytes)) != -1) {
                total += read;
                if (total > MAX_IMPORT_BYTES)
                    throw new IOException("CSV profile exceeds 20 MB");
                stream.write(bytes, 0, read);
            }
            stream.getFD().sync();
        }
    }

    public static void ensure(Context context) throws IOException {
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            if (p.contains(ACTIVE)) return;
            File dst = path(context, DEFAULT_ID);
            File legacy = new File(context.getFilesDir(), LocalRoadStore.FILE_NAME);
            File tmp = new File(directory(context), "migration.tmp");
            try {
                if (legacy.exists()) {
                    try (InputStream in = new FileInputStream(legacy)) { copy(in, tmp); }
                } else {
                    try (InputStream in = context.getAssets().open(LocalRoadStore.FILE_NAME)) {
                        copy(in, tmp);
                    }
                }
                // Never mark migration successful without a usable road database.
                try (InputStream in = new FileInputStream(tmp)) {
                    CsvRoadRepository.read(in);
                }
                if (!dst.exists()) {
                    if (!tmp.renameTo(dst))
                        throw new IOException("Could not create the default CSV profile");
                }
                if (!p.edit()
                        .putStringSet(IDS, Collections.singleton(DEFAULT_ID))
                        .putString(LABEL_PREFIX + DEFAULT_ID, "Default roads")
                        .putString(ACTIVE, DEFAULT_ID)
                        .commit())
                    throw new IOException("Could not save road profile selection");
            } finally {
                tmp.delete();
            }
        }
    }

    public static List<Profile> list(Context context) throws IOException {
        synchronized (LOCK) {
            ensure(context);
            SharedPreferences p = prefs(context);
            List<Profile> out = new ArrayList<>();
            for (String id : p.getStringSet(IDS, Collections.emptySet())) {
                out.add(new Profile(id, p.getString(LABEL_PREFIX + id, id)));
            }
            out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            return out;
        }
    }

    public static String activeId(Context context) throws IOException {
        synchronized (LOCK) {
            ensure(context);
            return prefs(context).getString(ACTIVE, DEFAULT_ID);
        }
    }

    public static Profile active(Context context) throws IOException {
        synchronized (LOCK) {
            ensure(context);
            String id = activeId(context);
            return new Profile(id, prefs(context).getString(LABEL_PREFIX + id, id));
        }
    }

    public static File activeFile(Context context) throws IOException {
        return fileFor(context, activeId(context));
    }

    public static File fileFor(Context context, String id) throws IOException {
        synchronized (LOCK) {
            ensure(context);
            if (!prefs(context).getStringSet(IDS, Collections.emptySet()).contains(id))
                throw new IOException("Road profile no longer exists");
            File file = path(context, id);
            if (!file.isFile()) throw new IOException("Road profile file missing");
            return file;
        }
    }

    private static void checkName(Context context, String wanted, String excludingId)
            throws IOException {
        List<String> existing = new ArrayList<>();
        for (Profile profile : list(context)) {
            if (!profile.id.equals(excludingId)) existing.add(profile.name);
        }
        RoadProfileNames.unique(wanted, existing);
    }

    private static Profile register(Context context, String id, String name) throws IOException {
        SharedPreferences p = prefs(context);
        Set<String> ids = new HashSet<>(p.getStringSet(IDS, Collections.emptySet()));
        ids.add(id);
        if (!p.edit().putStringSet(IDS, ids)
                .putString(LABEL_PREFIX + id, name).commit())
            throw new IOException("Could not register road profile");
        return new Profile(id, name);
    }

    public static Profile duplicate(Context context, String sourceId, String name) throws IOException {
        synchronized (LOCK) {
            ensure(context);
            String checked = RoadProfileNames.checked(name);
            checkName(context, checked, null);
            File original = fileFor(context, sourceId);
            String id = "p_" + UUID.randomUUID();
            File destination = path(context, id);
            try (InputStream in = new FileInputStream(original)) {
                copy(in, destination);
            }
            try {
                return register(context, id, checked);
            } catch (IOException ex) {
                destination.delete();
                throw ex;
            }
        }
    }

    public static Profile createImported(Context context, String name, InputStream source)
            throws IOException {
        synchronized (LOCK) {
            ensure(context);
            String checked = RoadProfileNames.checked(name);
            checkName(context, checked, null);
            String id = "p_" + UUID.randomUUID();
            File destination = path(context, id);
            File tmp = new File(directory(context), id + ".tmp");
            try {
                copy(source, tmp);
                try (InputStream verify = new FileInputStream(tmp)) {
                    CsvRoadRepository.read(verify);
                }
                if (!tmp.renameTo(destination))
                    throw new IOException("Could not store imported CSV");
                try {
                    return register(context, id, checked);
                } catch (IOException ex) {
                    destination.delete();
                    throw ex;
                }
            } finally {
                tmp.delete();
            }
        }
    }

    public static void select(Context context, String id) throws IOException {
        synchronized (LOCK) {
            File chosen = fileFor(context, id);
            // Validate BEFORE changing active; reject corrupt imports/files.
            try (InputStream in = new FileInputStream(chosen)) {
                CsvRoadRepository.read(in);
            }
            if (!prefs(context).edit().putString(ACTIVE, id).commit())
                throw new IOException("Could not activate road profile");
        }
    }

    public static void rename(Context context, String id, String name) throws IOException {
        synchronized (LOCK) {
            fileFor(context, id);
            String checked = RoadProfileNames.checked(name);
            checkName(context, checked, id);
            if (!prefs(context).edit().putString(LABEL_PREFIX + id, checked).commit())
                throw new IOException("Could not rename road profile");
        }
    }

    public static void delete(Context context, String id) throws IOException {
        synchronized (LOCK) {
            ensure(context);
            if (activeId(context).equals(id))
                throw new IOException("Select another profile before deleting this one");
            Set<String> ids = new HashSet<>(prefs(context).getStringSet(IDS, Collections.emptySet()));
            if (!ids.contains(id)) throw new IOException("Road profile not found");
            if (ids.size() < 2) throw new IOException("Keep at least one road profile");
            File file = fileFor(context, id);
            if (!file.delete()) throw new IOException("Could not delete road profile CSV");
            ids.remove(id);
            if (!prefs(context).edit().putStringSet(IDS, ids)
                    .remove(LABEL_PREFIX + id).commit())
                throw new IOException("Could not update road profile list");
        }
    }
}
