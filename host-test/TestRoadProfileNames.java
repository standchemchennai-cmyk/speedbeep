import com.utk.speedbeep.RoadProfileNames;
import java.util.Arrays;
import java.util.Collections;

/** Profile names are labels, never arbitrary on-device file paths. */
public class TestRoadProfileNames {
    static void check(boolean good, String why) {
        if (!good) throw new AssertionError(why);
    }
    static boolean rejected(String name) {
        try { RoadProfileNames.checked(name); return false; }
        catch (IllegalArgumentException expected) { return true; }
    }
    public static void main(String[] args) {
        check("Thar - driver".equals(RoadProfileNames.checked("  Thar   -   driver  ")),
                "Whitespace must be normalized");
        check(rejected(""), "Blank names must fail");
        check(rejected("../other.csv"), "Directory traversal must fail");
        check(rejected("car\\driver"), "Backslash path must fail");
        check(rejected("C:drive"), "Colon must fail");
        check(rejected(String.join("", Collections.nCopies(50, "a"))),
                "Name length bound");
        RoadProfileNames.unique("Driver", Arrays.asList("Self drive", "Vehicles"));
        boolean duplicate = false;
        try { RoadProfileNames.unique(" DRIVER ", Arrays.asList("Driver", "My Car")); }
        catch (IllegalArgumentException expected) { duplicate = true; }
        // Caller normalizes with checked before unique.
        check(duplicate || !RoadProfileNames.checked(" DRIVER ").equals(" DRIVER "),
                "Normalization must not be bypassed");
        duplicate = false;
        try { RoadProfileNames.unique(RoadProfileNames.checked(" driver "),
                Arrays.asList("Driver")); }
        catch (IllegalArgumentException expected) { duplicate = true; }
        check(duplicate, "Case-insensitive duplicate detection");
        System.out.println("RoadProfileNames tests passed.");
    }
}
