package com.utk.speedbeep;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure-Java map matching for OSM road ways, including roads without maxspeed tags. */
public final class OSMRoadNetwork {
    private OSMRoadNetwork() {}

    public static final class Way {
        public final long id;
        public final String name, ref, highway;
        public final List<double[]> points;

        public Way(long id, String name, String ref, String highway, List<double[]> points) {
            this.id = id;
            this.name = name == null ? "" : name.trim();
            this.ref = ref == null ? "" : ref.trim();
            this.highway = highway == null ? "" : highway;
            this.points = points;
        }

        public String roadName() {
            if (!name.isEmpty()) return name;
            if (!ref.isEmpty()) return ref;
            return "way " + id;
        }

        /** Empty means this way has no stable street identity outside its own OSM way. */
        public String groupKey() {
            if (!name.isEmpty()) return "name:" + name.trim().toLowerCase(Locale.ROOT);
            if (!ref.isEmpty()) return "ref:" + ref.trim().toLowerCase(Locale.ROOT);
            return "way:" + id;
        }
    }

    public static final class Match {
        public final Way way;
        public final RoadSegment piece;
        public final double distanceM, bearingDifference;
        Match(Way way, RoadSegment piece, double distanceM, double bearingDifference) {
            this.way = way;
            this.piece = piece;
            this.distanceM = distanceM;
            this.bearingDifference = bearingDifference;
        }
    }

    private static final class Scored {
        final Match hit;
        final double score;
        Scored(Match hit, double score) { this.hit = hit; this.score = score; }
    }

    /** Find the best vehicle road. For ambiguous parallel/crossing streets, fail safely. */
    public static Match match(List<Way> ways, double lat, double lon, double gpsAccuracyM,
                              boolean hasHeading, double headingDeg, double speedKph) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) ||
                !Double.isFinite(gpsAccuracyM) || gpsAccuracyM < 0) {
            throw new IllegalArgumentException("Invalid GPS position");
        }
        double maxDistance = Math.min(30, Math.max(13, gpsAccuracyM + 8));
        boolean reliableHeading = hasHeading && Double.isFinite(headingDeg) && speedKph >= 8;
        List<Scored> choices = new ArrayList<>();
        for (Way way : ways) {
            if (way.points == null || way.points.size() < 2) continue;
            Match bestPiece = null;
            for (int i = 1; i < way.points.size(); i++) {
                double[] a = way.points.get(i - 1);
                double[] b = way.points.get(i);
                double distance = GeoMatcher.distancePointToSegmentMeters(
                        lat, lon, a[0], a[1], b[0], b[1]);
                if (distance > maxDistance) continue;
                RoadSegment piece = new RoadSegment(way.roadName(), a[0], a[1], b[0], b[1],
                        40, 15, 3);
                if (RoadLimitEditor.lengthM(piece) < 0.5) continue;
                double difference = reliableHeading
                        ? undirectedHeadingDifference(headingDeg, a[0], a[1], b[0], b[1])
                        : 0;
                if (reliableHeading && difference > 60) continue;
                Match hit = new Match(way, piece, distance, difference);
                if (bestPiece == null || distance + difference * 0.15 <
                        bestPiece.distanceM + bestPiece.bearingDifference * 0.15) {
                    bestPiece = hit;
                }
            }
            if (bestPiece != null) {
                choices.add(new Scored(bestPiece,
                        bestPiece.distanceM + (reliableHeading ? bestPiece.bearingDifference * 0.15 : 0)));
            }
        }
        if (choices.isEmpty()) return null;
        choices.sort((a, b) -> Double.compare(a.score, b.score));
        Scored best = choices.get(0);
        // At a junction or parallel road, a single GPS fix can point to two
        // equally likely roads. Never write to the wrong road automatically.
        for (int i = 1; i < choices.size(); i++) {
            Scored alternate = choices.get(i);
            if (alternate.score - best.score > 6) break;
            if (!alternate.hit.way.groupKey().equals(best.hit.way.groupKey())) {
                throw new IllegalArgumentException("Nearby roads are ambiguous; try again after the next GPS fix away from the junction");
            }
        }
        return best.hit;
    }

    public static List<RoadSegment> connectedPieces(List<Way> ways, Way chosen) {
        List<RoadSegment> all = new ArrayList<>();
        RoadSegment seed = null;
        String group = chosen.groupKey();
        for (Way way : ways) {
            if (!group.equals(way.groupKey()) || way.points == null) continue;
            for (int i = 1; i < way.points.size(); i++) {
                double[] a = way.points.get(i - 1);
                double[] b = way.points.get(i);
                RoadSegment piece = new RoadSegment("OSM " + way.roadName() + " [way " + way.id + "]",
                        a[0], a[1], b[0], b[1], 40, 15, 3);
                if (RoadLimitEditor.lengthM(piece) < 0.5) continue;
                all.add(piece);
                if (seed == null && way.id == chosen.id) seed = piece;
            }
        }
        return seed == null ? new ArrayList<>() : RoadLimitEditor.connectedRoad(all, seed);
    }

    private static double undirectedHeadingDifference(double bearing, double aLat, double aLon,
                                                        double bLat, double bLon) {
        double north = (bLat - aLat) * 111320;
        double east = (bLon - aLon) * 111320 * Math.cos(Math.toRadians((aLat + bLat) * 0.5));
        double roadBearing = Math.toDegrees(Math.atan2(east, north));
        double difference = Math.abs(((bearing - roadBearing) % 360 + 540) % 360 - 180);
        return Math.min(difference, 180 - difference);
    }
}
