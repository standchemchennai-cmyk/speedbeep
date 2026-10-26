package com.utk.speedbeep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Local, geometry-aware speed-limit overrides.
 *
 * A new stretch takes precedence over older collinear geometry. Existing
 * segments are physically clipped, preserving their speed limits outside the
 * modified interval (including BOTH ends of a longer original segment).
 * Crossing streets and parallel service roads are not modified.
 */
public final class RoadLimitEditor {
    private static final double METERS_PER_DEGREE = 111_320.0;
    private static final double JOIN_TOLERANCE_M = 3.5;
    private static final double COLLINEAR_TOLERANCE_M = 2.5;
    private static final double MAX_SIN_ANGLE = 0.16;
    private static final double MIN_LENGTH_M = 0.3;

    private RoadLimitEditor() {}

    /** All directly connected CSV pieces of the same named road, not all roads near the GPS fix. */
    public static List<RoadSegment> connectedRoad(List<RoadSegment> roads, RoadSegment seed) {
        if (seed == null) return Collections.emptyList();
        List<RoadSegment> found = new ArrayList<>();
        Set<RoadSegment> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        found.add(seed);
        seen.add(seed);
        String roadName = baseName(seed.name);
        List<RoadSegment> sameName = new ArrayList<>();
        for (RoadSegment road : roads) {
            if (roadName.equals(baseName(road.name))) sameName.add(road);
        }
        // Endpoint connectivity only: no jumping across parallel roads or disconnected streets.
        for (int cursor = 0; cursor < found.size(); cursor++) {
            RoadSegment candidate = found.get(cursor);
            for (RoadSegment road : sameName) {
                if (seen.contains(road)) continue;
                if (connected(candidate, road)) {
                    seen.add(road);
                    found.add(road);
                }
            }
        }
        return found;
    }

    public static String baseName(String name) {
        String n = name.trim();
        if (n.startsWith("OSM ")) n = n.substring(4);
        if (n.startsWith("Custom: ")) n = n.substring(8);
        n = n.replaceFirst(" \\[way [0-9]+\\]$", "");
        return n;
    }

    public static double lengthM(RoadSegment r) {
        double latitude = (r.startLat + r.endLat) * 0.5;
        double x = (r.endLon - r.startLon) * lonScale(latitude);
        double y = (r.endLat - r.startLat) * METERS_PER_DEGREE;
        return Math.hypot(x, y);
    }

    private static boolean connected(RoadSegment a, RoadSegment b) {
        return near(a.startLat, a.startLon, b.startLat, b.startLon)
                || near(a.startLat, a.startLon, b.endLat, b.endLon)
                || near(a.endLat, a.endLon, b.startLat, b.startLon)
                || near(a.endLat, a.endLon, b.endLat, b.endLon);
    }

    private static boolean near(double lat, double lon, double lat2, double lon2) {
        return Math.hypot((lat - lat2) * METERS_PER_DEGREE,
                (lon - lon2) * lonScale((lat + lat2) * 0.5)) <= JOIN_TOLERANCE_M;
    }

    private static double lonScale(double lat) {
        return METERS_PER_DEGREE * Math.max(0.01, Math.cos(Math.toRadians(lat)));
    }

    /**
     * Snap a map tap to the point along one configured CSV segment.
     * Used when cutting a piece out of the middle of an existing long limit.
     */
    public static double[] snap(double lat, double lon, RoadSegment road) {
        double xScale = lonScale(lat);
        double x = (lon - road.startLon) * xScale;
        double y = (lat - road.startLat) * METERS_PER_DEGREE;
        double vx = (road.endLon - road.startLon) * xScale;
        double vy = (road.endLat - road.startLat) * METERS_PER_DEGREE;
        double len2 = vx * vx + vy * vy;
        double t = len2 < 0.0001 ? 0 : Math.max(0, Math.min(1, (x * vx + y * vy) / len2));
        return new double[]{road.startLat + t * (road.endLat - road.startLat),
                road.startLon + t * (road.endLon - road.startLon)};
    }

    /** Snap two map taps on the SAME mapped piece, allowing a precise subsegment override. */
    public static RoadSegment betweenTaps(RoadSegment road,
                                           double startLat, double startLon,
                                           double endLat, double endLon) {
        double[] a = snap(startLat, startLon, road);
        double[] b = snap(endLat, endLon, road);
        RoadSegment section = new RoadSegment(road.name, a[0], a[1], b[0], b[1],
                road.limitKph, road.corridorM, road.marginKph);
        if (lengthM(section) < MIN_LENGTH_M) {
            throw new IllegalArgumentException("Pick two different points along the road");
        }
        return section;
    }

    /**
     * Apply new speed to the selected physical stretches. Existing pieces are
     * cut at the new boundaries rather than blanket-overwritten.
     *
     * The supplied parts must be accurately aligned with the intended roadway.
     * For unmapped roads, a manually drawn straight section may be supplied.
     */
    public static List<RoadSegment> override(List<RoadSegment> oldRoads,
                                              List<RoadSegment> selectedParts,
                                              double newLimitKph,
                                              String name) {
        if (!Double.isFinite(newLimitKph) || newLimitKph < 5 || newLimitKph > 200) {
            throw new IllegalArgumentException("Speed limit must be 5–200 km/h");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Road name required");
        }
        if (selectedParts.isEmpty()) throw new IllegalArgumentException("No stretch selected");

        List<RoadSegment> result = new ArrayList<>();
        List<RoadSegment> overrides = new ArrayList<>();
        for (RoadSegment part : selectedParts) {
            if (lengthM(part) < MIN_LENGTH_M) continue;
            // The Custom prefix preserves edits when the OSM importer is run later.
            overrides.add(new RoadSegment("Custom: " + baseName(name),
                    part.startLat, part.startLon, part.endLat, part.endLon,
                    newLimitKph, part.corridorM, part.marginKph));
        }
        if (overrides.isEmpty()) throw new IllegalArgumentException("Selected stretch is too short");
        // Overrides first: even on exact shared endpoints, the latest manually
        // configured limit wins in the existing nearest-segment matcher.
        result.addAll(overrides);

        for (RoadSegment original : oldRoads) {
            double total = lengthM(original);
            if (total < MIN_LENGTH_M) {
                result.add(original);
                continue;
            }
            List<double[]> cuts = new ArrayList<>();
            for (RoadSegment replacement : overrides) {
                double[] overlap = alignedInterval(original, replacement);
                if (overlap != null) cuts.add(overlap);
            }
            if (cuts.isEmpty()) {
                result.add(original);
                continue;
            }
            cuts.sort(Comparator.comparingDouble(interval -> interval[0]));
            double start = 0;
            for (double[] cut : cuts) {
                if (cut[0] > start) addRemainder(result, original, start, cut[0]);
                start = Math.max(start, cut[1]);
            }
            if (start < 1) addRemainder(result, original, start, 1);
        }
        return result;
    }

    private static void addRemainder(List<RoadSegment> out, RoadSegment r, double from, double to) {
        if ((to - from) * lengthM(r) < MIN_LENGTH_M) return;
        out.add(new RoadSegment(r.name,
                r.startLat + (r.endLat - r.startLat) * from,
                r.startLon + (r.endLon - r.startLon) * from,
                r.startLat + (r.endLat - r.startLat) * to,
                r.startLon + (r.endLon - r.startLon) * to,
                r.limitKph, r.corridorM, r.marginKph));
    }

    private static double[] alignedInterval(RoadSegment old, RoadSegment edit) {
        double latitude = (old.startLat + old.endLat) * 0.5;
        double scaleLon = lonScale(latitude);
        double vx = (old.endLon - old.startLon) * scaleLon;
        double vy = (old.endLat - old.startLat) * METERS_PER_DEGREE;
        double wx = (edit.endLon - edit.startLon) * scaleLon;
        double wy = (edit.endLat - edit.startLat) * METERS_PER_DEGREE;
        double length = Math.hypot(vx, vy);
        double replacementLength = Math.hypot(wx, wy);
        if (length < MIN_LENGTH_M || replacementLength < MIN_LENGTH_M) return null;
        if (Math.abs(vx * wy - vy * wx) / (length * replacementLength) > MAX_SIN_ANGLE) {
            return null;
        }

        double ax = (edit.startLon - old.startLon) * scaleLon;
        double ay = (edit.startLat - old.startLat) * METERS_PER_DEGREE;
        double bx = (edit.endLon - old.startLon) * scaleLon;
        double by = (edit.endLat - old.startLat) * METERS_PER_DEGREE;
        // Do not clip an adjacent parallel carriageway or a crossing road.
        if (Math.abs(vx * ay - vy * ax) / length > COLLINEAR_TOLERANCE_M ||
                Math.abs(vx * by - vy * bx) / length > COLLINEAR_TOLERANCE_M) return null;
        double p = (vx * ax + vy * ay) / (length * length);
        double q = (vx * bx + vy * by) / (length * length);
        double begin = Math.max(0, Math.min(p, q));
        double end = Math.min(1, Math.max(p, q));
        if ((end - begin) * length < MIN_LENGTH_M) return null;
        return new double[]{begin, end};
    }
}
