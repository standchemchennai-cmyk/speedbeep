import com.utk.speedbeep.MapStretchRouter;
import com.utk.speedbeep.RoadLimitEditor;
import com.utk.speedbeep.RoadSegment;
import com.utk.speedbeep.GeoMatcher;
import java.util.Arrays;
import java.util.List;

/** Host-only geometry tests: bends, snap, connected route and safe failures. */
public class TestMapStretchRouter {
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
    private static RoadSegment road(String name, double aLat, double aLon,
                                    double bLat, double bLon, double limit) {
        return new RoadSegment(name, aLat, aLon, bLat, bLon, limit, 15, 3);
    }
    private static boolean fails(List<RoadSegment> roads, double aLat, double aLon,
                                 double bLat, double bLon) {
        try {
            MapStretchRouter.route(roads, aLat, aLon, bLat, bLon, false);
            return false;
        } catch (IllegalArgumentException expected) { return true; }
    }
    public static void main(String[] args) {
        RoadSegment vertical = road("OSM Curve Road [way 111]", 13.000, 80.000,
                13.001, 80.000, 60);
        RoadSegment horizontal = road("OSM Curve Road [way 222]", 13.001, 80.000,
                13.001, 80.001, 60);
        RoadSegment crossStreet = road("Cross Street", 12.999, 80.002,
                13.002, 80.002, 30);
        List<RoadSegment> bends = Arrays.asList(vertical, horizontal, crossStreet);

        MapStretchRouter.Route curved = MapStretchRouter.route(bends,
                13.0002, 80.000008, 13.001008, 80.0008, false);
        check(curved.parts.size() == 2, "Curve must be represented by TWO road segments");
        check(!curved.fromOsm && curved.name.equals("Curve Road"), "Offline source and road name");
        check(curved.lengthM > 150 && curved.lengthM < 190, "Expected road-following curved length");
        check(Math.abs(curved.parts.get(0).endLat - 13.001) < 1e-7,
                "First piece must reach the actual road corner");
        check(Math.abs(curved.parts.get(1).startLon - 80) < 1e-7,
                "Second piece must start at corner, not shortcut across block");

        List<RoadSegment> edited = RoadLimitEditor.override(bends, curved.parts, 45, curved.name);
        check(GeoMatcher.findBest(13.0005, 80.000, 0, edited).road.limitKph == 45,
                "Vertical middle must be updated");
        check(GeoMatcher.findBest(13.001, 80.0005, 0, edited).road.limitKph == 45,
                "Horizontal middle must be updated");
        check(GeoMatcher.findBest(13.0000, 80.000, 0, edited).road.limitKph == 60,
                "Outer vertical flank remains original");
        check(GeoMatcher.findBest(13.001, 80.001, 0, edited).road.limitKph == 60,
                "Outer horizontal flank remains original");
        check(edited.contains(crossStreet), "Unrelated road must not be modified");

        MapStretchRouter.Route samePiece = MapStretchRouter.route(
                Arrays.asList(vertical), 13.0002, 80.00001, 13.0008, 80.00001, false);
        check(samePiece.parts.size() == 1, "Snap two taps on one segment");
        check(Math.abs(samePiece.parts.get(0).startLon - 80) < 1e-8,
                "Snap slightly offset tap to centerline");

        MapStretchRouter.Route atVertex = MapStretchRouter.route(bends,
                13.001, 80.000, 13.001, 80.0008, false);
        check(atVertex.lengthM < 100, "Tap at vertex must not take an unnecessary detour");

        check(fails(Arrays.asList(vertical, horizontal), 13.0001, 80.000,
                        13.02, 80.02), "No freehand chord across unmapped location");
        RoadSegment disconnected = road("Curve Road", 13.01, 80.01,
                13.012, 80.01, 60);
        check(fails(Arrays.asList(vertical, disconnected), 13.0003, 80.0,
                        13.011, 80.01), "No direct jump between disconnected pieces");
        RoadSegment different = road("Other Street", 13.001, 80.000,
                13.001, 80.001, 50);
        check(fails(Arrays.asList(vertical, different), 13.0002, 80.0,
                        13.001, 80.0008), "No cross-street speed zone routing");
        System.out.println("MapStretchRouter tests passed.");
    }
}
