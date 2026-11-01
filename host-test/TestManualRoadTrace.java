import com.utk.speedbeep.ManualRoadTrace;
import com.utk.speedbeep.GeoMatcher;
import com.utk.speedbeep.RoadLimitEditor;
import com.utk.speedbeep.RoadSegment;
import java.util.Arrays;
import java.util.List;

/** Offline/manual map editing regression tests; no Android or network needed. */
public class TestManualRoadTrace {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static double[] point(double lat, double lon) { return new double[]{lat, lon}; }
    private static boolean rejects(double[]... pts) {
        try {
            ManualRoadTrace.build(Arrays.asList(pts));
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
    public static void main(String[] args) {
        double[] start = point(13, 80);
        double[] corner = point(13.0006, 80);
        double[] end = point(13.0006, 80.0006);
        ManualRoadTrace.Result trace = ManualRoadTrace.build(
                Arrays.asList(start, corner, end));
        check(trace.pieces.size() == 2, "Curved road must have two separate pieces");
        check(trace.lengthM > 125 && trace.lengthM < 140, "Trace length must include bend");
        check(trace.pieces.get(0).endLon == trace.pieces.get(1).startLon,
                "Connected trace must use the selected corner");
        check(Math.abs(trace.pieces.get(1).startLat - corner[0]) < 1e-9,
                "Second road section must start at bend, not direct shortcut");
        check("Manual road trace".equals(trace.pieces.get(0).name), "Trace road name placeholder");
        check(rejects(start), "Cannot save a one-point trace");
        check(rejects(start, point(13.003, 80)), "Reject >150m unsupported straight jump");
        check(rejects(start, start), "Reject identical points");
        check(rejects(start, point(Double.NaN, 80)), "Reject invalid GPS latitude");
        check(rejects(start, point(95, 80)), "Reject out-of-range latitude");

        List<RoadSegment> saved = RoadLimitEditor.override(
                java.util.Collections.emptyList(), trace.pieces, 40, "Example Road");
        check(saved.size() == 2, "Save must preserve every traced piece");
        check(GeoMatcher.findBest(13.0003, 80.0, 0, saved).road.limitKph == 40,
                "First section must get saved speed limit");
        check(GeoMatcher.findBest(13.0006, 80.0003, 0, saved).road.limitKph == 40,
                "Second section must get saved speed limit");
        System.out.println("ManualRoadTrace tests passed.");
    }
}
