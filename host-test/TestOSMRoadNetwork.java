import com.utk.speedbeep.GeoMatcher;
import com.utk.speedbeep.OSMRoadNetwork;
import com.utk.speedbeep.RoadLimitEditor;
import com.utk.speedbeep.RoadSegment;

import java.util.Arrays;
import java.util.List;

public class TestOSMRoadNetwork {
    private static OSMRoadNetwork.Way way(long id, String name, String ref, double... coords) {
        java.util.ArrayList<double[]> points = new java.util.ArrayList<>();
        for (int i = 0; i < coords.length; i += 2)
            points.add(new double[]{coords[i], coords[i+1]});
        return new OSMRoadNetwork.Way(id, name, ref, "residential", points);
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        OSMRoadNetwork.Way first = way(1, "Test Road", "",
                13.000, 80.000, 13.002, 80.000, 13.004, 80.000);
        OSMRoadNetwork.Way second = way(2, "Test Road", "",
                13.004, 80.000, 13.006, 80.000);
        OSMRoadNetwork.Way other = way(3, "Other Road", "",
                13.003, 80.001, 13.003, 80.003);
        OSMRoadNetwork.Way parallel = way(4, "Parallel Road", "",
                13.000, 80.00030, 13.006, 80.00030);
        List<OSMRoadNetwork.Way> ways = Arrays.asList(first, second, other, parallel);
        OSMRoadNetwork.Match hit = OSMRoadNetwork.match(
                ways, 13.001, 80.0000, 6, true, 0, 38);
        check(hit != null && hit.way.id == 1, "Should match actual road");
        List<RoadSegment> pieces = OSMRoadNetwork.connectedPieces(ways, hit.way);
        check(pieces.size() == 3, "Must follow connected pieces with same road name");
        for (RoadSegment part : pieces)
            check(RoadLimitEditor.baseName(part.name).equals("Test Road"), "Wrong road selected");

        List<RoadSegment> saved = RoadLimitEditor.override(
                Arrays.asList(new RoadSegment("Existing 80", 13.000, 80.000,
                        13.008, 80.000, 80, 20, 3)),
                pieces, 40, "Test Road");
        check(GeoMatcher.findBest(13.005, 80, 0, saved).road.limitKph == 40,
                "New OSM road must become the new speed-limit segment");
        check(GeoMatcher.findBest(13.007, 80, 0, saved).road.limitKph == 80,
                "Old longer speed-limit segment must keep remainder");

        OSMRoadNetwork.Way crossing = way(5, "Cross Street", "",
                13.000, 80.001, 13.004, 80.001);
        OSMRoadNetwork.Way correct = way(6, "North Street", "",
                13.002, 80.000, 13.002, 80.002);
        OSMRoadNetwork.Match head = OSMRoadNetwork.match(
                Arrays.asList(crossing,correct),13.002,80.001,5,true,90,35);
        check(head != null && head.way.id == 6,
                "Moving GPS bearing should distinguish crossing roads");

        OSMRoadNetwork.Way ambiguousA = way(7,"Side lane A","",
                13.000,80,13.003,80);
        OSMRoadNetwork.Way ambiguousB = way(8,"Side lane B","",
                13.000,80.000015,13.003,80.000015);
        boolean rejected = false;
        try {
            OSMRoadNetwork.match(Arrays.asList(ambiguousA, ambiguousB),
                    13.001,80.000005,7,false,0,0);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "Ambiguous closely spaced roads must not be auto-overwritten");
        OSMRoadNetwork.Way unnamed = way(9,"","",13.007,80,13.008,80);
        check(OSMRoadNetwork.connectedPieces(Arrays.asList(unnamed,first),unnamed).size()==1,
                "An unnamed OSM way must not expand into unrelated named streets");

        System.out.println("OSM road geometry matching tests passed");
    }
}
