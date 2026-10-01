package com.utk.speedbeep;

import java.util.List;

public final class GeoMatcher {
    private static final double METERS_PER_DEGREE_LAT = 111_320.0;

    private GeoMatcher() {}

    public static Match findBest(double lat, double lon, double accuracyM, List<RoadSegment> roads) {
        RoadSegment best = null;
        double bestDistance = Double.POSITIVE_INFINITY;

        double accuracyAllowance = Math.min(Math.max(accuracyM, 0.0), 20.0);

        for (RoadSegment road : roads) {
            double distance = distancePointToSegmentMeters(
                    lat, lon,
                    road.startLat, road.startLon,
                    road.endLat, road.endLon);

            if (distance <= road.corridorM + accuracyAllowance && distance < bestDistance) {
                best = road;
                bestDistance = distance;
            }
        }

        return best == null ? null : new Match(best, bestDistance);
    }

    public static double distancePointToSegmentMeters(
            double pLat, double pLon,
            double aLat, double aLon,
            double bLat, double bLon) {

        double cosLat = Math.cos(Math.toRadians(pLat));
        double metersPerDegreeLon = METERS_PER_DEGREE_LAT * Math.max(cosLat, 0.01);

        double ax = (aLon - pLon) * metersPerDegreeLon;
        double ay = (aLat - pLat) * METERS_PER_DEGREE_LAT;
        double bx = (bLon - pLon) * metersPerDegreeLon;
        double by = (bLat - pLat) * METERS_PER_DEGREE_LAT;

        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;

        if (len2 < 0.0001) {
            return Math.hypot(ax, ay);
        }

        double t = -(ax * dx + ay * dy) / len2;
        t = Math.max(0.0, Math.min(1.0, t));

        double closestX = ax + t * dx;
        double closestY = ay + t * dy;
        return Math.hypot(closestX, closestY);
    }

    public static final class Match {
        public final RoadSegment road;
        public final double distanceM;

        public Match(RoadSegment road, double distanceM) {
            this.road = road;
            this.distanceM = distanceM;
        }
    }
}
