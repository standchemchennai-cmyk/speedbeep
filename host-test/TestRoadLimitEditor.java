import com.utk.speedbeep.RoadLimitEditor;
import com.utk.speedbeep.GeoMatcher;
import com.utk.speedbeep.RoadSegment;
import com.utk.speedbeep.CsvRoadRepository;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JVM tests for the road override geometry; no Android SDK required. */
public class TestRoadLimitEditor {
    static RoadSegment road(String name, double a, double b, double speed) {
        return new RoadSegment(name, a, 80, b, 80, speed, 15, 3);
    }
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    static double at(List<RoadSegment> roads, double latitude, double longitude) {
        GeoMatcher.Match hit = GeoMatcher.findBest(latitude, longitude, 0, roads);
        return hit == null ? -1 : hit.road.limitKph;
    }
    public static void main(String[] args) throws Exception {
        RoadSegment original = road("Old 60", 13.000, 13.010, 60);
        RoadSegment middle = road("Old 60", 13.003, 13.007, 60);
        List<RoadSegment> edited = RoadLimitEditor.override(
                Arrays.asList(original), Arrays.asList(middle), 40, "Old 60");
        check(edited.size() == 3, "Expected new middle plus both original flanks");
        check(at(edited, 13.001, 80) == 60, "Before must remain 60");
        check(at(edited, 13.005, 80) == 40, "Middle must change to 40");
        check(at(edited, 13.009, 80) == 60, "After must remain 60");
        check(at(edited, 13.003, 80) == 40, "New speed wins at lower boundary");
        check(at(edited, 13.007, 80) == 40, "New speed wins at upper boundary");
        check(Math.abs(edited.get(1).endLat - 13.003) < 0.000001, "Left cut coordinate");
        check(Math.abs(edited.get(2).startLat - 13.007) < 0.000001, "Right cut coordinate");

        List<RoadSegment> second = RoadLimitEditor.override(edited,
                Arrays.asList(road("Custom: Old 60", 13.004, 13.006, 40)), 30, "Old 60");
        check(at(second, 13.002, 80) == 60, "Second edit must preserve old left");
        check(at(second, 13.0035, 80) == 40, "Second edit must preserve first edit flank");
        check(at(second, 13.005, 80) == 30, "Second edit must overwrite middle");
        check(at(second, 13.0065, 80) == 40, "Second edit must preserve other flank");
        check(at(second, 13.009, 80) == 60, "Second edit must preserve old right");

        // Nearby parallel lanes and crossing streets must be unchanged.
        RoadSegment parallel = new RoadSegment("Service lane", 13.000, 80.00005,
                13.010, 80.00005, 50, 15, 3);
        RoadSegment crossing = new RoadSegment("Cross street", 13.005, 79.999,
                13.005, 80.001, 25, 15, 3);
        List<RoadSegment> protectedRoads = RoadLimitEditor.override(
                Arrays.asList(original, parallel, crossing),
                Arrays.asList(middle), 40, "Old 60");
        check(protectedRoads.size() == 5, "Parallel/crossing road should not be clipped");
        check(protectedRoads.contains(parallel), "Parallel roadway must remain intact");
        check(protectedRoads.contains(crossing), "Crossing road must remain intact");

        RoadSegment way1 = road("OSM Anna Salai [way 1234]", 13.010, 13.011, 60);
        RoadSegment way2 = road("OSM Anna Salai [way 5678]", 13.011, 13.012, 60);
        RoadSegment disconnected = road("OSM Anna Salai [way 9999]", 13.050, 13.051, 60);
        RoadSegment otherName = road("OSM Other road [way 1]", 13.012, 13.013, 60);
        check("Anna Salai".equals(RoadLimitEditor.baseName(way1.name)), "Strip OSM way suffix");
        List<RoadSegment> group = RoadLimitEditor.connectedRoad(
                Arrays.asList(way1, way2, disconnected, otherName), way1);
        check(group.size() == 2 && group.contains(way1) && group.contains(way2),
                "Connected same-named road only");

        double[] snapStart = RoadLimitEditor.snap(13.003, 80.00005, original);
        double[] snapEnd = RoadLimitEditor.snap(13.007, 80.00005, original);
        check(Math.abs(snapStart[1] - 80) < 0.00000001, "Snap off-center point to road");
        check(Math.abs(snapEnd[0] - 13.007) < 0.00000001, "Snap end latitude");
        RoadSegment section = RoadLimitEditor.betweenTaps(original,
                13.003, 80.00005, 13.007, 80.00005);
        check(Math.abs(section.startLon - 80) < 0.00000001,
                "Map-selected stretch follows existing road centerline");

        // Adjacent edited pieces cut a long road exactly once, leaving both outside flanks.
        List<RoadSegment> multiparts = RoadLimitEditor.override(Arrays.asList(original),
                Arrays.asList(road("Old", 13.002, 13.004, 60),
                        road("Old", 13.004, 13.008, 60)), 45, "Old");
        check(at(multiparts, 13.001, 80) == 60, "Multi-piece start untouched");
        check(at(multiparts, 13.003, 80) == 45, "Multi-piece part one");
        check(at(multiparts, 13.007, 80) == 45, "Multi-piece part two");
        check(at(multiparts, 13.009, 80) == 60, "Multi-piece end untouched");

        boolean invalid = false;
        try { RoadLimitEditor.override(Arrays.asList(original), Arrays.asList(middle), 300, "X"); }
        catch (IllegalArgumentException expected) { invalid = true; }
        check(invalid, "Reject invalid limits");
        System.out.println("RoadLimitEditor tests passed.");
    }
}
