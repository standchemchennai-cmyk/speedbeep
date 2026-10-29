import com.utk.speedbeep.CsvRoadRepository;
import com.utk.speedbeep.GeoMatcher;
import com.utk.speedbeep.GpsRoadFallback;
import com.utk.speedbeep.RoadLimitEditor;
import com.utk.speedbeep.RoadSegment;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Offline GPS save behavior: pure JVM, independent of Android and Overpass. */
public class TestGpsRoadFallback {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        RoadSegment moving = GpsRoadFallback.create(13.05000, 80.26000, 4, true, 90, 35);
        check(Math.abs(RoadLimitEditor.lengthM(moving) - 50) < 0.5,
                "Moving GPS save covers approximately 50 m, not the whole road");
        check(moving.name.startsWith("GPS-only spot"), "Must identify local approximation");
        check(moving.corridorM <= 10, "Do not match a wide nearby roadway");
        check(moving.endLon > moving.startLon, "Eastbound bearing should point east");

        List<RoadSegment> changed = RoadLimitEditor.override(
                Arrays.asList(new RoadSegment("Other road", 13.10, 80.25,
                        13.11, 80.25, 40, 15, 3)),
                Arrays.asList(moving), 60, moving.name);
        check(changed.size() == 2, "Unrelated existing road must survive");
        GeoMatcher.Match center = GeoMatcher.findBest(13.05, 80.26, 4, changed);
        check(center != null && center.road.limitKph == 60,
                "Saved GPS-only limit should match its location");
        check(GeoMatcher.findBest(13.05, 80.262, 4, changed) == null,
                "GPS-only save must not accidentally cover the rest of the road");

        // The resulting geometry can be represented in the persistent CSV format.
        String csv = String.format(java.util.Locale.US,
                "name,start_lat,start_lon,end_lat,end_lon,limit_kph,corridor_m,margin_kph\\n" +
                "\"%s\",%.7f,%.7f,%.7f,%.7f,60,%.1f,3\\n",
                changed.get(0).name, changed.get(0).startLat, changed.get(0).startLon,
                changed.get(0).endLat, changed.get(0).endLon, changed.get(0).corridorM);
        List<RoadSegment> parsed = CsvRoadRepository.read(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        check(GeoMatcher.findBest(13.05, 80.26, 4, parsed) != null,
                "GPS fallback should survive CSV round trip");

        RoadSegment stationary = GpsRoadFallback.create(13.05, 80.26, 4, false, 0, 0);
        check(RoadLimitEditor.lengthM(stationary) < 3,
                "Without heading, only a small GPS spot may be saved");

        boolean refused = false;
        try { GpsRoadFallback.create(13.05, 80.26, 17, true, 90, 30); }
        catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "Weak GPS fixes must not be automatically mapped");

        refused = false;
        try { GpsRoadFallback.create(Double.NaN, 80.26, 4, true, 90, 30); }
        catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "Invalid coordinates must not be accepted");
        System.out.println("GPS-only offline fallback tests passed.");
    }
}
