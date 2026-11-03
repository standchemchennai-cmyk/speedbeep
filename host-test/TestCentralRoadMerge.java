import com.utk.speedbeep.CentralRoadMerge;
import com.utk.speedbeep.RoadSegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class TestCentralRoadMerge {
    private static RoadSegment segment(String name, double begin, double end, int limit) {
        return new RoadSegment(name, 12.95, begin, 12.95, end, limit, 30, 3);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        List<RoadSegment> base = Arrays.asList(segment("OMR", 80.20, 80.22, 60));
        List<RoadSegment> empty = CentralRoadMerge.merge(base, Collections.emptyList());
        check(empty.size() == 1 && empty.get(0).limitKph == 60, "Vanilla unchanged");

        List<RoadSegment> local = CentralRoadMerge.add(new ArrayList<>(),
                Collections.singletonList(segment("OMR", 80.205, 80.21, 60)), 40, "OMR");
        check(local.size() == 1 && local.get(0).limitKph == 40, "Edit recorded");

        List<RoadSegment> merged = CentralRoadMerge.merge(base, local);
        check(merged.size() == 3, "Master flanks preserved around local override");
        check(merged.get(0).limitKph == 40, "Local override comes first");

        List<RoadSegment> updatedBase = Arrays.asList(segment("OMR", 80.20, 80.22, 65),
                segment("New road", 80.30, 80.31, 50));
        List<RoadSegment> afterRefresh = CentralRoadMerge.merge(updatedBase, local);
        check(afterRefresh.get(0).limitKph == 40, "Local limit wins after refresh");
        check(afterRefresh.stream().anyMatch(x -> x.name.equals("New road")),
                "New central road survives refresh");

        List<RoadSegment> newer = CentralRoadMerge.add(local,
                Collections.singletonList(segment("OMR", 80.207, 80.209, 40)), 30, "OMR");
        check(newer.get(0).limitKph == 30, "Latest local override wins");
        List<RoadSegment> afterSecondEdit = CentralRoadMerge.merge(updatedBase, newer);
        check(afterSecondEdit.get(0).limitKph == 30, "Newest override highest precedence");
        System.out.println("Central road merge tests passed");
    }
}
