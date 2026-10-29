package com.utk.speedbeep;

import java.util.List;

/**
 * Conservative selection of a road ALREADY stored in the local CSV.
 *
 * Editing an existing mapped road does not need a network request. A four-metre
 * gate was too narrow for normal GPS-to-OSM offsets (including 10 m on a road
 * shown as the current target). We still reject uncertain nearby roads rather
 * than automatically overwriting a parallel service lane or intersecting road.
 */
public final class LocalRoadEditMatcher {
    private LocalRoadEditMatcher() {}

    public static GeoMatcher.Match match(List<RoadSegment> roads,
                                         double lat, double lon, double accuracyM) {
        if (roads == null || !Double.isFinite(lat) || !Double.isFinite(lon) ||
                !Double.isFinite(accuracyM) || accuracyM < 0 || accuracyM > 30) {
            return null;
        }

        // Only accept a local road plausibly beneath the accurate GPS fix,
        // even if its CSV corridor would allow a more distant match.
        double maxDistanceM = Math.min(20.0, Math.max(12.0, accuracyM + 10.0));
        GeoMatcher.Match best = GeoMatcher.findBest(lat, lon, accuracyM, roads);
        if (best == null || best.distanceM > maxDistanceM) return null;

        // If another road is equally plausible, selecting a whole road is
        // unsafe. Same-named segments with the SAME speed are not competing:
        // many imported OSM ways form successive or parallel pieces of one road.
        double ambiguityM = Math.max(4.0, Math.min(8.0, accuracyM + 2.0));
        String bestName = RoadLimitEditor.baseName(best.road.name);
        for (RoadSegment candidate : roads) {
            if (candidate == best.road) continue;
            double distance = GeoMatcher.distancePointToSegmentMeters(lat, lon,
                    candidate.startLat, candidate.startLon,
                    candidate.endLat, candidate.endLon);
            if (distance > maxDistanceM || distance > best.distanceM + ambiguityM) continue;
            boolean sameName = bestName.equalsIgnoreCase(RoadLimitEditor.baseName(candidate.name));
            boolean sameLimit = Math.abs(candidate.limitKph - best.road.limitKph) < 0.01;
            if (!sameName || !sameLimit) {
                throw new IllegalArgumentException(
                        "More than one configured road is near this GPS fix. " +
                        "Move away from the junction or select the exact stretch on the map.");
            }
        }
        return best;
    }
}
