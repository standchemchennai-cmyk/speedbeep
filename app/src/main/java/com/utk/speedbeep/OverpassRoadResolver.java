package com.utk.speedbeep;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Explicit on-demand OSM road-geometry lookup. Only called when the user
 * presses SET THIS ROAD. It never overwrites OSM or uploads the local CSV.
 *
 * Road geometry, unlike speed-limit rows, is required to add an unmapped road
 * without forcing the user to draw a polyline.
 */
public final class OverpassRoadResolver {
    private static final String[] ENDPOINTS = {
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
    };
    private static final int NEARBY_RADIUS_M = 130;
    private static final int EXPAND_RADIUS_M = 2200;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final String MOTOR_ROADS =
            "^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|" +
            "secondary|secondary_link|tertiary|tertiary_link|unclassified|" +
            "residential|living_street|service)$";

    private OverpassRoadResolver() {}

    /** No configured geometry was found, but an accurate local GPS spot may be saved. */
    public static final class RoadNotFoundException extends IOException {
        RoadNotFoundException(String message) { super(message); }
    }

    /** Every OSM endpoint failed; a small, explicit GPS-only save can still work. */
    public static final class LookupUnavailableException extends IOException {
        LookupUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class Result {
        public final String name;
        public final List<RoadSegment> pieces;
        public final double matchDistanceM;
        public final boolean extended;
        public final int searchRadiusM;

        Result(String name, List<RoadSegment> pieces, double matchDistanceM, boolean extended) {
            this.name = name;
            this.pieces = pieces;
            this.matchDistanceM = matchDistanceM;
            this.extended = extended;
            this.searchRadiusM = extended ? EXPAND_RADIUS_M : NEARBY_RADIUS_M;
        }

        public double lengthM() {
            double meters = 0;
            for (RoadSegment piece : pieces) meters += RoadLimitEditor.lengthM(piece);
            return meters;
        }
    }

    public static Result resolve(double lat, double lon, double accuracyM,
                                 boolean hasHeading, double bearingDeg, double speedKph)
            throws IOException {
        String nearby = String.format(Locale.US,
                "[out:json][timeout:20];way[\"highway\"~\"%s\"]" +
                "(around:%d,%.7f,%.7f);out tags geom;",
                MOTOR_ROADS, NEARBY_RADIUS_M, lat, lon);
        List<OSMRoadNetwork.Way> local = request(nearby);
        OSMRoadNetwork.Match closest;
        try {
            closest = OSMRoadNetwork.match(local, lat, lon, accuracyM,
                    hasHeading, bearingDeg, speedKph);
        } catch (IllegalArgumentException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        if (closest == null) throw new RoadNotFoundException(
                "No drivable OpenStreetMap road at this GPS position.");

        OSMRoadNetwork.Way selected = closest.way;
        List<OSMRoadNetwork.Way> collection = local;
        boolean extended = false;
        String filter = null;
        if (!selected.name.isEmpty()) {
            filter = "[\"name\"=\"" + escapeTag(selected.name) + "\"]";
        } else if (!selected.ref.isEmpty()) {
            filter = "[\"ref\"=\"" + escapeTag(selected.ref) + "\"]";
        }
        if (filter != null) {
            String expansion = String.format(Locale.US,
                    "[out:json][timeout:25];way[\"highway\"~\"%s\"]%s" +
                    "(around:%d,%.7f,%.7f);out tags geom;",
                    MOTOR_ROADS, filter, EXPAND_RADIUS_M, lat, lon);
            try {
                List<OSMRoadNetwork.Way> more = request(expansion);
                Map<Long, OSMRoadNetwork.Way> byId = new LinkedHashMap<>();
                for (OSMRoadNetwork.Way w : local) byId.put(w.id, w);
                for (OSMRoadNetwork.Way w : more) byId.put(w.id, w);
                collection = new ArrayList<>(byId.values());
                extended = true;
            } catch (IOException ignored) {
                // Still save the correctly identified OSM way geometry: do not
                // pretend the whole named route was discovered on network failure.
                collection = local;
            }
        }

        List<RoadSegment> connected = OSMRoadNetwork.connectedPieces(collection, selected);
        if (connected.isEmpty()) {
            throw new IOException("OpenStreetMap road geometry was incomplete.");
        }
        if (connected.size() > 2500) {
            throw new IOException("Road extends beyond the safe local editing limit.");
        }
        return new Result(selected.roadName(), connected, closest.distanceM, extended);
    }

    private static String escapeTag(String value) {
        String out = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return out.replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    private static List<OSMRoadNetwork.Way> request(String query) throws IOException {
        IOException error = null;
        for (String endpoint : ENDPOINTS) {
            try {
                return parse(fetch(endpoint, query));
            } catch (IOException ex) {
                error = ex;
            }
        }
        String reason = error == null ? "unknown network failure" : error.getMessage();
        throw new LookupUnavailableException(
                "OpenStreetMap lookup unavailable (" + reason + ").", error);
    }

    private static byte[] fetch(String endpoint, String query) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", "SpeedBeep/0.4 (Android; road limit editor)");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setConnectTimeout(6500);
            conn.setReadTimeout(14000);
            conn.setDoOutput(true);
            byte[] request = ("data=" + URLEncoder.encode(query, "UTF-8"))
                    .getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) { out.write(request); }
            int status = conn.getResponseCode();
            if (status != 200) throw new IOException("Overpass HTTP " + status);
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > MAX_BODY_BYTES) {
                        throw new IOException("Road geometry response too large");
                    }
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<OSMRoadNetwork.Way> parse(byte[] json) throws IOException {
        try {
            JSONArray elements = new JSONObject(new String(json, StandardCharsets.UTF_8))
                    .getJSONArray("elements");
            if (elements.length() > 12000) throw new IOException("Too many OSM ways returned");
            List<OSMRoadNetwork.Way> ways = new ArrayList<>();
            for (int i = 0; i < elements.length(); i++) {
                JSONObject item = elements.getJSONObject(i);
                if (!"way".equals(item.optString("type"))) continue;
                JSONObject tags = item.optJSONObject("tags");
                JSONArray shape = item.optJSONArray("geometry");
                if (tags == null || shape == null || shape.length() < 2 ||
                        shape.length() > 3000) continue;
                if ("yes".equals(tags.optString("area")) ||
                        "no".equals(tags.optString("access")) ||
                        "private".equals(tags.optString("access")) ||
                        "no".equals(tags.optString("motor_vehicle")) ||
                        "no".equals(tags.optString("vehicle"))) continue;
                List<double[]> points = new ArrayList<>(shape.length());
                for (int j = 0; j < shape.length(); j++) {
                    JSONObject point = shape.getJSONObject(j);
                    points.add(new double[]{point.getDouble("lat"), point.getDouble("lon")});
                }
                ways.add(new OSMRoadNetwork.Way(item.getLong("id"),
                        tags.optString("name"), tags.optString("ref"),
                        tags.optString("highway"), points));
            }
            return ways;
        } catch (JSONException ex) {
            throw new IOException("Invalid OpenStreetMap road data", ex);
        }
    }
}
