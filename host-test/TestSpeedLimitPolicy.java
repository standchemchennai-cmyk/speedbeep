import com.utk.speedbeep.SpeedLimitPolicy;
import com.utk.speedbeep.SpeedLimitPolicy.Decision;
import com.utk.speedbeep.SpeedLimitPolicy.Source;

/** Regression coverage for global ceiling priority against road and fallback. */
public class TestSpeedLimitPolicy {
    private static void check(boolean good, String message) {
        if (!good) throw new AssertionError(message);
    }
    private static Decision decide(Double road, double roadMargin, boolean fallback,
                                   double fallbackLimit, double fallbackMargin,
                                   boolean universal, double cap) {
        return SpeedLimitPolicy.select(road, roadMargin, fallback, fallbackLimit,
                fallbackMargin, universal, cap);
    }
    public static void main(String[] args) {
        Decision capUnmapped = decide(null, 0, false, 40, 3, true, 60);
        check(capUnmapped.source == Source.UNIVERSAL, "Universal works on unconfigured road");
        check(!capUnmapped.isOver(60) && capUnmapped.isOver(60.1),
                "Universal is strict above the cap, no inherited margin");

        Decision capMapped = decide(80.0, 3, false, 40, 3, true, 60);
        check(capMapped.source == Source.UNIVERSAL && capMapped.isOver(65),
                "Universal overrides a higher matched speed limit");

        Decision roadStricter = decide(40.0, 3, false, 40, 3, true, 60);
        check(roadStricter.source == Source.ROAD && roadStricter.thresholdKph == 43,
                "Global cap must never suppress stricter local road warning");
        check(roadStricter.isOver(44) && !roadStricter.isOver(42),
                "Matched road warning still works");

        Decision fallbackStricter = decide(null, 0, true, 30, 3, true, 60);
        check(fallbackStricter.source == Source.FALLBACK &&
                fallbackStricter.thresholdKph == 33,
                "Separate unmapped fallback remains more restrictive");

        Decision capStricterThanFallback = decide(null, 0, true, 80, 5, true, 60);
        check(capStricterThanFallback.source == Source.UNIVERSAL,
                "Universal triggers on unmapped roads even if fallback is higher");
        check(decide(null, 0, false, 40, 3, false, 60).source == Source.NONE,
                "Both disabled must not create an alarm");
        check(decide(70.0, 3, false, 40, 3, false, 60).source == Source.ROAD,
                "Turning universal off leaves mapped road logic unchanged");
        check(decide(null, 0, true, 50, 4, false, 60).source == Source.FALLBACK,
                "Turning universal off leaves fallback logic unchanged");
        check(decide(null, 0, false, 40, 3, true, Double.NaN).source == Source.NONE,
                "Bad universal setting fails safely without false limit");
        check(decide(80.0, 5, false, 40, 3, true, 85).source == Source.ROAD,
                "Equal effective thresholds prefer original road warning");

        Decision first = decide(null, 0, false, 40, 3, true, 50);
        Decision same = decide(null, 0, false, 40, 3, true, 50);
        Decision changed = decide(null, 0, false, 40, 3, true, 55);
        check(first.sameAlarmAs(same) && !first.sameAlarmAs(changed),
                "Adjusting global threshold should reset overspeed hold");
        System.out.println("SpeedLimitPolicy tests passed.");
    }
}
