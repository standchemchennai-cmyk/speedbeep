package com.utk.speedbeep;

import android.content.Context;
import android.location.Location;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class DriveLog {
    private static final String NAME = "drive_diagnostics.csv";
    private static final long MAX_BYTES = 2000000L;
    private DriveLog() {}
    public static File file(Context context) { return new File(context.getFilesDir(), NAME); }
    public static void append(Context context, Location location, double speed, float accuracy,
                              String result, RoadSegment road, double distance) throws IOException {
        File f = file(context);
        boolean append = f.exists() && f.length() < MAX_BYTES;
        String header = "timestamp,latitude,longitude,speed_kph,gps_accuracy_m,result,road,limit_kph,distance_m\n";
        String name = road == null ? "" : road.name.replace(",", " ").replace("\n", " ");
        String row = String.format(Locale.US, "%d,%.6f,%.6f,%.1f,%.0f,%s,%s,%.0f,%.1f\n",
                System.currentTimeMillis(), location.getLatitude(), location.getLongitude(),
                speed, accuracy, result, name, road == null ? 0 : road.limitKph, distance);
        try (FileOutputStream out = new FileOutputStream(f, append)) {
            if (!append || f.length() == 0) out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(row.getBytes(StandardCharsets.UTF_8));
        }
    }
}
