import com.utk.speedbeep.GeoMatcher;
import com.utk.speedbeep.LocalRoadEditMatcher;
import com.utk.speedbeep.RoadLimitEditor;
import com.utk.speedbeep.RoadSegment;

import java.util.Arrays;
import java.util.List;

/** Regression: existing mapped roads must be editable without Overpass at ordinary GPS offsets. */
public class TestLocalRoadEditMatcher {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static RoadSegment road(String name, double lon, double limit) {
        return new RoadSegment(name, 13.0000, lon, 13.0100, lon, limit, 15, 3);
    }

    static boolean ambiguous(List<RoadSegment> roads, double latitude, double longitude, double accuracy) {
        try {
            LocalRoadEditMatcher.match(roads, latitude, longitude, accuracy);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    public static void main(String[] args) {
        RoadSegment bypass = road("OSM Chennai Bypass Expressway [way 835017293]", 80.00000, 120);
        // Roughly 10 m east of the road centreline: the original hardcoded 4 m gate
        // incorrectly sent a mapped road back to OSM and prevented offline editing.
        double lat = 13.0050;
        double tenMeters = 10.0 / (111320.0 * Math.cos(Math.toRadians(lat)));
        GeoMatcher.Match match = LocalRoadEditMatcher.match(
                Arrays.asList(bypass), lat, 80.00000 + tenMeters, 4.0);
        check(match != null && match.road == bypass,
                "Known road 10 m away with GPS +/- 4 m must be editable offline");
        check(LocalRoadEditMatcher.match(Arrays.asList(bypass),
                lat, 80.00000 + tenMeters * 0.2, 4.0) != null,
                "A 2 m match must also be editable offline");
        check(LocalRoadEditMatcher.match(Arrays.asList(bypass),
                lat, 80.00000 + tenMeters * 3.0, 4.0) == null,
                "Distant road must not be silently selected");

        // Many OSM ways describe adjacent pieces of the same road and limit.
        RoadSegment nextPiece = new RoadSegment(
                "OSM Chennai Bypass Expressway [way 835017295]",
                13.01000, 80.00000, 13.02000, 80.00000, 120, 15, 3);
        check(LocalRoadEditMatcher.match(Arrays.asList(bypass, nextPiece),
                13.01000, 80.00000, 4.0) != null,
                "Junction of same-name and same-limit pieces should not block edit");

        // Same route in separate parallel OSM segments with same limit.
        RoadSegment sameRoute = road("OSM Chennai Bypass Expressway [way 835017295]",
                80.00004, 120);
        check(LocalRoadEditMatcher.match(Arrays.asList(bypass, sameRoute),
                lat, 80.00001, 4.0) != null,
                "Same named road may have nearby OSM carriageway segments");

        // Different roads or differently configured speed zones remain ambiguous.
        RoadSegment service = road("OSM Service Lane [way 999]", 80.00005, 40);
        check(ambiguous(Arrays.asList(bypass, service), lat, 80.00002, 4.0),
                "Nearby service lane should not be overwritten by a guess");
        RoadSegment changeOfLimit = road("OSM Chennai Bypass Expressway [way 888]",
                80.00005, 60);
        check(ambiguous(Arrays.asList(bypass, changeOfLimit), lat, 80.00002, 4.0),
                "Do not cross into a distinct existing speed-limit zone");
        check(LocalRoadEditMatcher.match(Arrays.asList(bypass), lat, 80, 35.0) == null,
                "Weak GPS should not allow an unattended edit");

        System.out.println("Local mapped-road offline editing tests passed.");
    }
}
