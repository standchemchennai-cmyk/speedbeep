package com.utk.speedbeep;

import java.util.Locale;

/**
 * Very small, locally saved GPS geometry when an OSM road cannot be resolved.
 *
 * This is deliberately NOT a substitute for a mapped road: without map geometry
 * we cannot infer the road's name, shape, extent, or legal limit. The supplied
 * speed limit comes from the user and the area of coverage is intentionally small.
 */
public final class GpsRoadFallback {
    public static final double MAX_ACCURACY_M = 10.0;
    private static final double METERS_PER_DEGREE = 111_320.0;

    private GpsRoadFallback() {}

    public static RoadSegment create(double lat, double lon, double accuracyM,
                                     boolean hasBearing, double bearingDeg, double speedKph) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) ||
                lat < -90 || lat > 90 || lon < -180 || lon > 180 ||
                !Double.isFinite(accuracyM) || accuracyM < 0) {
            throw new IllegalArgumentException("Invalid GPS fix");
        }
        if (accuracyM > MAX_ACCURACY_M) {
            throw new IllegalArgumentException(
                    "OSM lookup failed. GPS-only save needs accuracy within 10 m. " +
                    "Try again with clearer GPS, or use SELECT A STRETCH on the map.");
        }

        // With a reliable moving bearing, cover only 50 m of approximately
        // straight road. While stationary, save just a small GPS spot.
        boolean movingWithBearing = hasBearing && Double.isFinite(bearingDeg)
                && Double.isFinite(speedKph) && speedKph >= 8.0;
        double bearing = Math.toRadians(movingWithBearing ? bearingDeg : 0.0);
        double halfLengthM = movingWithBearing ? 25.0 : 1.0;
        double northM = Math.cos(bearing) * halfLengthM;
        double eastM = Math.sin(bearing) * halfLengthM;
        double lonScale = METERS_PER_DEGREE *
                Math.max(0.01, Math.cos(Math.toRadians(lat)));

        String name = String.format(Locale.US, "GPS-only spot %.5f %.5f", lat, lon);
        double corridorM = Math.max(6.0, Math.min(10.0, accuracyM + 2.0));
        return new RoadSegment(name,
                lat - northM / METERS_PER_DEGREE, lon - eastM / lonScale,
                lat + northM / METERS_PER_DEGREE, lon + eastM / lonScale,
                40.0, corridorM, 3.0);
    }
}
