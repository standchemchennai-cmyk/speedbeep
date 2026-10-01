import com.utk.speedbeep.CsvRoadRepository;
import com.utk.speedbeep.GeoMatcher;
import com.utk.speedbeep.RoadSegment;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class TestCore {
    public static void main(String[] args) throws Exception {
        String csv = "name,start_lat,start_lon,end_lat,end_lon,limit_kph,corridor_m,margin_kph\n" +
                "Test Road,13.000000,80.000000,13.010000,80.000000,60,40,3\n";
        List<RoadSegment> roads = CsvRoadRepository.read(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        if (roads.size() != 1) throw new AssertionError("CSV parse failed");

        double on = GeoMatcher.distancePointToSegmentMeters(13.005, 80.0, 13.0, 80.0, 13.01, 80.0);
        if (on > 1.0) throw new AssertionError("On-segment distance too large: " + on);

        GeoMatcher.Match match = GeoMatcher.findBest(13.005, 80.0001, 5, roads);
        if (match == null) throw new AssertionError("Expected road match");

        GeoMatcher.Match miss = GeoMatcher.findBest(13.005, 80.01, 5, roads);
        if (miss != null) throw new AssertionError("Expected no match");

        System.out.println("Core tests passed. Match distance: " + match.distanceM + " m");
    }
}
