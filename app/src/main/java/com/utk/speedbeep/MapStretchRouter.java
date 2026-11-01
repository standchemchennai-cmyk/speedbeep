package com.utk.speedbeep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Conservative two-tap road-geometry routing for speed-limit edits.
 * Snap the taps to the closest local road pieces and take the shortest
 * connected path on that SAME named street, without drawing an unsafe chord
 * across bends, buildings or unrelated roads. No turn-by-turn navigation.
 */
public final class MapStretchRouter {
    private static final double MAX_SNAP_M = 18;
    private static final double AMBIGUOUS_M = 2.0;
    private static final double MAX_LENGTH_M = 5000;
    private static final int MAX_PIECES = 1500;

    private MapStretchRouter() { }

    /** A missing/disconnected graph can be retried with live OSM geometry. */
    public static final class RouteNotFoundException extends IllegalArgumentException {
        public RouteNotFoundException(String reason) { super(reason); }
    }

    public static final class Route {
        public final String name;
        public final List<RoadSegment> parts;
        public final boolean fromOsm;
        public final double lengthM;

        public Route(String name, List<RoadSegment> parts, boolean fromOsm) {
            if (parts == null || parts.isEmpty()) throw new IllegalArgumentException("Empty snapped route");
            this.name = name;
            this.parts = parts;
            this.fromOsm = fromOsm;
            double length = 0;
            for (RoadSegment p : parts) length += RoadLimitEditor.lengthM(p);
            this.lengthM = length;
            if (length < 2 || length > MAX_LENGTH_M || parts.size() > MAX_PIECES)
                throw new IllegalArgumentException("Selected road stretch must be 2 m–5 km");
        }
    }

    private static final class Hit {
        final RoadSegment road;
        final double distanceM;
        final double[] snapped;
        Hit(RoadSegment r, double d, double[] snapped) {
            this.road = r; this.distanceM = d; this.snapped = snapped;
        }
    }

    private static final class Step {
        final int to;
        final RoadSegment piece;
        final double weight;
        Step(int to, RoadSegment p) {
            this.to = to; this.piece = p; this.weight = RoadLimitEditor.lengthM(p);
        }
    }

    private static final class QueueEntry {
        final int node;
        final double distance;
        QueueEntry(int n, double d) { node = n; distance = d; }
    }

    public static Route route(List<RoadSegment> roads,
                              double aLat, double aLon, double bLat, double bLon,
                              boolean fromOsm) {
        if (!finite(aLat, aLon) || !finite(bLat, bLon))
            throw new IllegalArgumentException("Invalid tap coordinates");
        if (meters(aLat, aLon, bLat, bLon) > 4000)
            throw new IllegalArgumentException("Select road sections shorter than 4 km");
        Hit a = closest(roads, aLat, aLon);
        Hit b = closest(roads, bLat, bLon);
        if (a == null || b == null)
            throw new RouteNotFoundException("One or both taps are farther than 18 m from known road geometry");
        String street = RoadLimitEditor.baseName(a.road.name);
        if (!street.equalsIgnoreCase(RoadLimitEditor.baseName(b.road.name)))
            throw new RouteNotFoundException("Taps are on different streets; choose one road at a time");
        String key = street.toLowerCase(Locale.ROOT);

        List<RoadSegment> sameStreet = new ArrayList<>();
        for (RoadSegment road : roads) {
            if (key.equals(RoadLimitEditor.baseName(road.name).toLowerCase(Locale.ROOT))
                    && RoadLimitEditor.lengthM(road) >= .3) sameStreet.add(road);
        }
        Map<String, Integer> nodes = new HashMap<>();
        List<List<Step>> graph = new ArrayList<>();
        for (RoadSegment r : sameStreet) {
            int x = node(nodes, graph, r.startLat, r.startLon);
            int y = node(nodes, graph, r.endLat, r.endLon);
            addLink(graph, x, y, r);
        }

        Integer first = nodes.get(vertex(a.road.startLat, a.road.startLon));
        Integer second = nodes.get(vertex(a.road.endLat, a.road.endLon));
        Integer third = nodes.get(vertex(b.road.startLat, b.road.startLon));
        Integer fourth = nodes.get(vertex(b.road.endLat, b.road.endLon));
        if (first == null || second == null || third == null || fourth == null)
            throw new RouteNotFoundException("Road graph endpoints missing");

        int source = graph.size();
        graph.add(new ArrayList<>());
        int target = graph.size();
        graph.add(new ArrayList<>());

        addLink(graph, source, first, part(a.road, a.snapped[0], a.snapped[1],
                a.road.startLat, a.road.startLon));
        addLink(graph, source, second, part(a.road, a.snapped[0], a.snapped[1],
                a.road.endLat, a.road.endLon));
        addLink(graph, target, third, part(b.road, b.snapped[0], b.snapped[1],
                b.road.startLat, b.road.startLon));
        addLink(graph, target, fourth, part(b.road, b.snapped[0], b.snapped[1],
                b.road.endLat, b.road.endLon));

        if (a.road == b.road) {
            addLink(graph, source, target, part(a.road,
                    a.snapped[0], a.snapped[1], b.snapped[0], b.snapped[1]));
        } else if (sameGeometry(a.road, b.road)) {
            // Two equivalent copies of an OSM way: allow direct route.
            addLink(graph, source, target, part(a.road,
                    a.snapped[0], a.snapped[1], b.snapped[0], b.snapped[1]));
        }

        double[] distances = new double[graph.size()];
        Arrays.fill(distances, Double.POSITIVE_INFINITY);
        int[] previous = new int[graph.size()];
        Arrays.fill(previous, -1);
        RoadSegment[] incoming = new RoadSegment[graph.size()];
        PriorityQueue<QueueEntry> queue = new PriorityQueue<>(
                Comparator.comparingDouble(x -> x.distance));
        distances[source] = 0;
        queue.add(new QueueEntry(source, 0));

        while (!queue.isEmpty()) {
            QueueEntry top = queue.poll();
            if (top.distance > distances[top.node] + 1e-7) continue;
            if (top.node == target) break;
            if (top.distance > MAX_LENGTH_M) break;
            for (Step s : graph.get(top.node)) {
                double candidate = top.distance + s.weight;
                if (candidate + 1e-7 < distances[s.to]) {
                    distances[s.to] = candidate;
                    previous[s.to] = top.node;
                    incoming[s.to] = s.piece;
                    queue.add(new QueueEntry(s.to, candidate));
                }
            }
        }
        if (!Double.isFinite(distances[target]) || distances[target] > MAX_LENGTH_M)
            throw new RouteNotFoundException("No connected route on this road; add a shorter section");
        double chord = meters(a.snapped[0], a.snapped[1], b.snapped[0], b.snapped[1]);
        if (distances[target] > 4 * chord + 300)
            throw new RouteNotFoundException("Route detours too far; choose closer points on one road");

        List<RoadSegment> reversed = new ArrayList<>();
        int cursor = target;
        while (cursor != source) {
            if (cursor < 0 || previous[cursor] < 0 || reversed.size() > MAX_PIECES)
                throw new RouteNotFoundException("Could not reconstruct snapped road path");
            RoadSegment step = incoming[cursor];
            if (RoadLimitEditor.lengthM(step) >= .3) reversed.add(step);
            cursor = previous[cursor];
        }
        java.util.Collections.reverse(reversed);
        return new Route(street, reversed, fromOsm);
    }

    private static Hit closest(List<RoadSegment> roads, double lat, double lon) {
        Hit best = null;
        for (RoadSegment r : roads) {
            double distance = GeoMatcher.distancePointToSegmentMeters(
                    lat, lon, r.startLat, r.startLon, r.endLat, r.endLon);
            if (distance <= MAX_SNAP_M && (best == null || distance < best.distanceM)) {
                best = new Hit(r, distance, RoadLimitEditor.snap(lat, lon, r));
            }
        }
        if (best != null) {
            // A junction or overlapping separate road is not safe to auto-edit.
            for (RoadSegment r : roads) {
                if (r == best.road || RoadLimitEditor.baseName(r.name)
                        .equalsIgnoreCase(RoadLimitEditor.baseName(best.road.name))) continue;
                double distance = GeoMatcher.distancePointToSegmentMeters(
                        lat, lon, r.startLat, r.startLon, r.endLat, r.endLon);
                if (distance <= best.distanceM + AMBIGUOUS_M)
                    throw new IllegalArgumentException(
                            "Tap is ambiguous near another street; zoom in and tap away from junction");
            }
        }
        return best;
    }

    private static int node(Map<String, Integer> ids, List<List<Step>> graph,
                            double lat, double lon) {
        String key = vertex(lat, lon);
        Integer found = ids.get(key);
        if (found != null) return found;
        int id = graph.size();
        ids.put(key, id);
        graph.add(new ArrayList<>());
        return id;
    }

    // Same imported OSM vertex (up to 7 decimal places); do not merge parallel
    // lanes merely because they happen to be a few metres apart.
    private static String vertex(double lat, double lon) {
        return Math.round(lat * 1e7) + ":" + Math.round(lon * 1e7);
    }

    private static boolean sameGeometry(RoadSegment a, RoadSegment b) {
        return (vertex(a.startLat, a.startLon).equals(vertex(b.startLat, b.startLon))
                        && vertex(a.endLat, a.endLon).equals(vertex(b.endLat, b.endLon)))
                || (vertex(a.startLat, a.startLon).equals(vertex(b.endLat, b.endLon))
                        && vertex(a.endLat, a.endLon).equals(vertex(b.startLat, b.startLon)));
    }

    private static RoadSegment part(RoadSegment base,
                                    double aLat, double aLon, double bLat, double bLon) {
        return new RoadSegment(base.name, aLat, aLon, bLat, bLon,
                base.limitKph, base.corridorM, base.marginKph);
    }

    private static void addLink(List<List<Step>> graph, int a, int b, RoadSegment piece) {
        if (a == b || RoadLimitEditor.lengthM(piece) < .01) return;
        graph.get(a).add(new Step(b, piece));
        graph.get(b).add(new Step(a, part(piece, piece.endLat, piece.endLon,
                piece.startLat, piece.startLon)));
    }

    private static boolean finite(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon) &&
                lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static double meters(double aLat, double aLon, double bLat, double bLon) {
        return Math.hypot((aLat - bLat) * 111320,
                (aLon - bLon) * 111320 * Math.cos(Math.toRadians((aLat + bLat) / 2)));
    }
}
