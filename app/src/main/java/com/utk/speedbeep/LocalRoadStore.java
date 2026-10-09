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

    public static void save(Context context, List<RoadSegment> roads) throws IOException {
        if (roads.isEmpty()) throw new IOException("Cannot save empty road database");
        File tmp = new File(context.getFilesDir(), FILE_NAME + ".editing");
        try (java.io.OutputStreamWriter out = new java.io.OutputStreamWriter(
                new FileOutputStream(tmp), java.nio.charset.StandardCharsets.UTF_8)) {
            out.write("name,start_lat,start_lon,end_lat,end_lon,limit_kph,corridor_m,margin_kph\\n");
            for (RoadSegment r : roads) {
                String name = r.name.replace("\\"", "\\"\\"");
                out.write(String.format(java.util.Locale.US,
                        "\\"%s\\",%.7f,%.7f,%.7f,%.7f,%.1f,%.1f,%.1f\\n",
                        name,r.startLat,r.startLon,r.endLat,r.endLon,r.limitKph,r.corridorM,r.marginKph));
            }
        }
        try (FileInputStream check = new FileInputStream(tmp)) { CsvRoadRepository.read(check); }
        try (FileInputStream in = new FileInputStream(tmp)) { importCsv(context, in); }
        tmp.delete();
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
