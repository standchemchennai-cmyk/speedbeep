package com.utk.speedbeep;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public final class LocalRoadStore {
    public static final String FILE_NAME = "speed_limits.csv";

    private LocalRoadStore() {}

    public static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static void ensureSeedFile(Context context) throws IOException {
        File dst = file(context);
        if (dst.exists()) return;
        try (InputStream in = context.getAssets().open(FILE_NAME);
             FileOutputStream out = new FileOutputStream(dst)) {
            copy(in, out);
        }
    }

    public static List<RoadSegment> load(Context context) throws IOException {
        ensureSeedFile(context);
        try (FileInputStream in = new FileInputStream(file(context))) {
            return CsvRoadRepository.read(in);
        }
    }

    public static int importCsv(Context context, InputStream incoming) throws IOException {
        File tmp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            copy(incoming, out);
        }

        List<RoadSegment> parsed;
        try (FileInputStream check = new FileInputStream(tmp)) {
            parsed = CsvRoadRepository.read(check);
        } catch (IOException ex) {
            tmp.delete();
            throw ex;
        }

        File dst = file(context);
        if (dst.exists() && !dst.delete()) {
            tmp.delete();
            throw new IOException("Could not replace existing CSV");
        }
        if (!tmp.renameTo(dst)) {
            try (FileInputStream in = new FileInputStream(tmp);
                 FileOutputStream out = new FileOutputStream(dst)) {
                copy(in, out);
            }
            tmp.delete();
        }
        return parsed.size();
    }

    private static void copy(InputStream in, FileOutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            out.write(buffer, 0, n);
        }
        out.flush();
    }
}
