package com.utk.speedbeep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Offline trace for roads absent from the speed-limit-only local database.
 *
 * Every tap is an explicit point; this does NOT claim to snap to OSM geometry.
 * Short consecutive links make curves possible, without fabricating a single
 * straight chord across multiple bends.
 */
public final class ManualRoadTrace {
    private static final int MAX_POINTS = 100;
    private static final double MAX_LEG_M = 150;
    private static final double MAX_TOTAL_M = 4000;
    private static final double MIN_LEG_M = 1;
    private ManualRoadTrace() { }

    public static final class Result {
        public final List<RoadSegment> pieces;
        public final double lengthM;
        Result(List<RoadSegment> pieces, double lengthM) {
            this.pieces = Collections.unmodifiableList(pieces);
            this.lengthM = lengthM;
        }
    }

    /**
     * All points are the roadway centreline as supplied by the user.
     * The caller previews the result and asks for a verified posted limit
     * before modifying the speed-limit database.
     */
    public static Result build(List<double[]> points) {
        if (points == null || points.size() < 2)
            throw new IllegalArgumentException("Tap at least two road points");
        if (points.size() > MAX_POINTS)
            throw new IllegalArgumentException("Too many trace points (maximum 100)");
        List<RoadSegment> pieces = new ArrayList<>();
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1), b = points.get(i);
            if (!valid(a) || !valid(b))
                throw new IllegalArgumentException("Invalid road coordinates");
            RoadSegment edge = new RoadSegment("Manual road trace",
                    a[0], a[1], b[0], b[1], 40, 12, 3);
            double metres = RoadLimitEditor.lengthM(edge);
            if (metres < MIN_LEG_M)
                throw new IllegalArgumentException("Points too close; tap further along the road");
            if (metres > MAX_LEG_M)
                throw new IllegalArgumentException("Points too far apart: add a bend point every 150 m or less");
            total += metres;
            if (total > MAX_TOTAL_M)
                throw new IllegalArgumentException("Trace exceeds 4 km; save a shorter stretch");
            pieces.add(edge);
        }
        return new Result(pieces, total);
    }

    private static boolean valid(double[] point) {
        return point != null && point.length >= 2 &&
                Double.isFinite(point[0]) && Double.isFinite(point[1]) &&
                point[0] >= -90 && point[0] <= 90 &&
                point[1] >= -180 && point[1] <= 180;
    }
}
