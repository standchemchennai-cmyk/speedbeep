package com.utk.speedbeep;

import android.content.Context;
import android.util.AtomicFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Every load/save/import uses only the selected named CSV profile. */
public final class LocalRoadStore {
    public static final String FILE_NAME = "speed_limits.csv";
    private static final int MAX_CSV_BYTES = 20 * 1024 * 1024;

    private LocalRoadStore() {}

    public static File file(Context context) throws IOException {
        return RoadProfileStore.activeFile(context);
    }

    public static void ensureSeedFile(Context context) throws IOException {
        RoadProfileStore.ensure(context);
    }

    public static List<RoadSegment> load(Context context) throws IOException {
        return loadForProfile(context, RoadProfileStore.activeId(context));
    }

    public static List<RoadSegment> loadForProfile(Context context, String id)
            throws IOException {
        return CentralRoadStore.loadEffective(context, id, CentralRoadStore.mode(context));
    }

    public static int importCsv(Context context, InputStream incoming) throws IOException {
        return importCsvForProfile(context, RoadProfileStore.activeId(context), incoming);
    }

    public static int importCsvForProfile(Context context, String id, InputStream incoming)
            throws IOException {
        if (incoming == null) throw new IOException("No CSV file supplied");
        // Snapshot the requested profile destination: a delayed import cannot
        // accidentally replace another profile that was selected meanwhile.
        File target = RoadProfileStore.fileFor(context, id);
        byte[] bytes = readBounded(incoming);
        List<RoadSegment> parsed = parse(bytes);
        atomicReplace(target, bytes);
        return parsed.size();
    }

    public static void save(Context context, List<RoadSegment> roads) throws IOException {
        saveForProfile(context, RoadProfileStore.activeId(context), roads);
    }

    public static void saveForProfile(Context context, String id, List<RoadSegment> roads)
            throws IOException {
        if (roads == null || roads.isEmpty())
            throw new IOException("Cannot save empty road database");
        if (!CentralRoadStore.MODE_LOCAL.equals(CentralRoadStore.mode(context)))
            throw new IOException("Central CSV edits must be saved as local overrides");
        File target = RoadProfileStore.fileFor(context, id);
        byte[] content = toCsvBytes(roads);
        parse(content);
        atomicReplace(target, content);
    }

    /** A single portable CSV containing the final, fully merged road database. */
    public static byte[] toCsvBytes(List<RoadSegment> roads) throws IOException {
        if (roads == null || roads.isEmpty())
            throw new IOException("Cannot export an empty road database");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStreamWriter out = new OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
            out.write("name,start_lat,start_lon,end_lat,end_lon,limit_kph,corridor_m,margin_kph\n");
            for (RoadSegment r : roads) {
                String safeName = r.name.replace("\"", "\"\"");
                out.write(String.format(Locale.US,
                        "\"%s\",%.7f,%.7f,%.7f,%.7f,%.1f,%.1f,%.1f\n",
                        safeName, r.startLat, r.startLon, r.endLat, r.endLon,
                        r.limitKph, r.corridorM, r.marginKph));
                if (bytes.size() > MAX_CSV_BYTES)
                    throw new IOException("Edited road profile exceeds 20 MB");
            }
        }
        return bytes.toByteArray();
    }

    private static List<RoadSegment> parse(byte[] bytes) throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            return CsvRoadRepository.read(in);
        }
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            if (bytes.size() + n > MAX_CSV_BYTES)
                throw new IOException("CSV must be 20 MB or smaller");
            bytes.write(chunk, 0, n);
        }
        return bytes.toByteArray();
    }

    /** Android AtomicFile protects an existing profile from partial writes. */
    private static void atomicReplace(File destination, byte[] bytes) throws IOException {
        AtomicFile atomic = new AtomicFile(destination);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            out.write(bytes);
            atomic.finishWrite(out);
        } catch (IOException | RuntimeException ex) {
            if (out != null) atomic.failWrite(out);
            throw ex;
        }
    }
}
