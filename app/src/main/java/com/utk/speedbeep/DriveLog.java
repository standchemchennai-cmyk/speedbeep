package com.utk.speedbeep;

import android.content.Context;
import android.location.Location;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A rolling, on-device GPS diagnostic log. The file is never uploaded.
 * Coordinates are sensitive: export it only when parked and share deliberately.
 */
public final class DriveLog {
    private static final String FILE_NAME = "drive_diagnostics.csv";
    private static final long MAX_BYTES = 2_000_000L;
    private static final String HEADER =
            "time_epoch_ms,latitude,longitude,speed_kph,gps_accuracy_m,result,road,limit_kph,road_distance_m\n";

    private DriveLog() { }

    public static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static void append(Context context, Location location, double speedKph,
                              float accuracyM, String result, RoadSegment road,
                              double distanceM) throws IOException {
        File file = file(context);
        boolean append = file.exists() && file.length() < MAX_BYTES;
        boolean needsHeader = !append || file.length() == 0;
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file, append), StandardCharsets.UTF_8)) {
            if (needsHeader) writer.write(HEADER);
            writer.write(String.format(Locale.US,
                    "%d,%.6f,%.6f,%.1f,%.0f,%s,%s,%s,%s\n",
                    System.currentTimeMillis(),
                    location.getLatitude(), location.getLongitude(),
                    speedKph, accuracyM,
                    escape(result),
                    escape(road == null ? "" : road.name),
                    road == null ? "" : String.format(Locale.US, "%.0f", road.limitKph),
                    road == null ? "" : String.format(Locale.US, "%.1f", distanceM)));
        }
    }

    private static String escape(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
