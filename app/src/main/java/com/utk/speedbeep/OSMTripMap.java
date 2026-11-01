package com.utk.speedbeep;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.events.MapEventsReceiver;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Map-only rendering: monitoring and the editable road database remain independent. */
public final class OSMTripMap extends MapView {
    private static final int MAX_TRAIL_POINTS = 4000;
    private final List<RoadSegment> roads = new ArrayList<>();
    private final RoadsOverlay roadsOverlay = new RoadsOverlay();
    private final TrailOverlay trailOverlay = new TrailOverlay();
    private final ExecutorService trailReader = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Marker> editMarkers = new ArrayList<>();
    private Marker position;
    private Polyline routePreview;
    private double positionLat = Double.NaN, positionLon = Double.NaN;
    private double followedLat = Double.NaN, followedLon = Double.NaN;
    private volatile boolean readingLog = false;
    private long lastLogLength = -1, lastLogModified = -1;

    public interface PointTapListener { void onPoint(double latitude, double longitude); }
    private PointTapListener pointTapListener;

    public OSMTripMap(Context context) {
        super(context);
        // A Dialog removes this view from its window on every close. osmdroid
        // otherwise calls onDetach(), permanently shutting down tile providers
        // and leaving blank tiles when this same map is shown a second time.
        // Keep tile providers alive across dialog open/close; MainActivity
        // explicitly calls onDetach() when the activity is destroyed.
        setDestroyMode(false);
        setTileSource(TileSourceFactory.MAPNIK);
        setMultiTouchControls(true);
        setBuiltInZoomControls(false);
        setTilesScaledToDpi(true);
        getController().setZoom(15.0);
        getController().setCenter(new GeoPoint(13.06, 80.24));
        // One canvas overlay per layer, NOT one osmdroid Polyline per CSV or GPS segment.
        getOverlays().add(roadsOverlay);
        getOverlays().add(trailOverlay);
        getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint point) {
                if (pointTapListener == null) return false;
                pointTapListener.onPoint(point.getLatitude(), point.getLongitude());
                return true;
            }
            @Override public boolean longPressHelper(GeoPoint point) { return false; }
        }));
        getOverlays().add(new CopyrightOverlay(context));
    }

    public void setPointTapListener(PointTapListener listener) { pointTapListener = listener; }

    public void centerOn(double latitude, double longitude) {
        if (!valid(latitude, longitude)) return;
        getController().setZoom(17.0);
        getController().setCenter(new GeoPoint(latitude, longitude));
        followedLat = latitude; followedLon = longitude;
    }

    /** Follows a changed GPS fix without resetting user-selected zoom. */
    public void followPosition(double latitude, double longitude) {
        if (!valid(latitude, longitude) || (latitude == followedLat && longitude == followedLon)) return;
        getController().setCenter(new GeoPoint(latitude, longitude));
        followedLat = latitude; followedLon = longitude;
    }

    public void setPosition(double latitude, double longitude) {
        if (!valid(latitude, longitude) || (latitude == positionLat && longitude == positionLon)) return;
        positionLat = latitude; positionLon = longitude;
        if (position == null) {
            position = new Marker(this);
            position.setTitle("Current GPS location");
            position.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            getOverlays().add(position);
        }
        position.setPosition(new GeoPoint(latitude, longitude));
        invalidate();
    }

    public void markEditPoint(double latitude, double longitude, String title) {
        Marker marker = new Marker(this);
        marker.setPosition(new GeoPoint(latitude, longitude));
        marker.setTitle(title);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        editMarkers.add(marker);
        getOverlays().add(marker);
        invalidate();
    }

    /** One highlighted geometry preview, so the user checks bends before saving. */
    public void showRoutePreview(List<RoadSegment> segments) {
        clearRoutePreview();
        if (segments == null || segments.isEmpty()) return;
        List<GeoPoint> points = new ArrayList<>();
        RoadSegment first = segments.get(0);
        points.add(new GeoPoint(first.startLat, first.startLon));
        for (RoadSegment part : segments) {
            points.add(new GeoPoint(part.endLat, part.endLon));
        }
        routePreview = new Polyline();
        routePreview.setPoints(points);
        routePreview.getOutlinePaint().setColor(Color.rgb(255, 161, 20));
        routePreview.getOutlinePaint().setStrokeWidth(13f);
        routePreview.setTitle("Preview: selected road stretch");
        getOverlays().add(routePreview);
        invalidate();
    }

    public void clearRoutePreview() {
        if (routePreview == null) return;
        getOverlays().remove(routePreview);
        routePreview = null;
        invalidate();
    }

    public void clearEditPoints() {
        getOverlays().removeAll(editMarkers);
        editMarkers.clear();
        invalidate();
    }

    public void setRoads(List<RoadSegment> list) {
        roads.clear();
        roads.addAll(list);
        roadsOverlay.setRoads(new ArrayList<>(roads));
        invalidate();
    }

    /** Optional overview only: never fit the whole Chennai database automatically. */
    public void fitRoads() {
        if (roads.isEmpty()) return;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (RoadSegment r : roads) {
            minLat = Math.min(minLat, Math.min(r.startLat, r.endLat));
            maxLat = Math.max(maxLat, Math.max(r.startLat, r.endLat));
            minLon = Math.min(minLon, Math.min(r.startLon, r.endLon));
            maxLon = Math.max(maxLon, Math.max(r.startLon, r.endLon));
        }
        double latPad = Math.max(.005, (maxLat - minLat) * .15);
        double lonPad = Math.max(.005, (maxLon - minLon) * .15);
        final BoundingBox bounds = new BoundingBox(maxLat + latPad, maxLon + lonPad,
                minLat - latPad, minLon - lonPad);
        post(() -> zoomToBoundingBox(bounds, true, 60));
    }

    /** Called only while the full-screen map is visible. File I/O stays off the UI thread. */
    public void reloadTrip() {
        final File file = DriveLog.file(getContext());
        if (!file.exists() || readingLog || trailReader.isShutdown()) return;
        final long length = file.length(), modified = file.lastModified();
        if (length == lastLogLength && modified == lastLogModified) return;
        readingLog = true;
        trailReader.execute(() -> {
            final ArrayDeque<TrackPoint> tail = new ArrayDeque<>(MAX_TRAIL_POINTS);
            boolean loaded = false;
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                reader.readLine(); // CSV header
                String row;
                while ((row = reader.readLine()) != null) {
                    try {
                        String[] p = row.split(",", -1);
                        if (p.length < 9) continue;
                        double lat = Double.parseDouble(p[1]), lon = Double.parseDouble(p[2]);
                        double speed = Double.parseDouble(p[3]), limit = Double.parseDouble(p[7]);
                        if (!valid(lat, lon) || !Double.isFinite(speed) || !Double.isFinite(limit)) continue;
                        if (tail.size() == MAX_TRAIL_POINTS) tail.removeFirst();
                        tail.addLast(new TrackPoint(lat, lon, speed, limit));
                    } catch (RuntimeException badRow) {
                        // One incomplete/invalid GPS row must not erase the rest of the trail.
                    }
                }
                loaded = true;
            } catch (Exception ignored) {
                // Keep the last successfully displayed trail, retry at the next refresh.
            }
            final boolean success = loaded;
            ui.post(() -> {
                readingLog = false;
                if (!success || trailReader.isShutdown()) return;
                lastLogLength = length;
                lastLogModified = modified;
                trailOverlay.setPoints(new ArrayList<>(tail));
                invalidate();
            });
        });
    }

    public void dispose() { trailReader.shutdownNow(); }

    private static boolean valid(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static final class TrackPoint {
        final double lat, lon, speed, limit;
        TrackPoint(double lat, double lon, double speed, double limit) {
            this.lat = lat; this.lon = lon; this.speed = speed; this.limit = limit;
        }
    }

    private static boolean outside(BoundingBox b, double minLat, double maxLat,
                                   double minLon, double maxLon) {
        final double pad = .002;
        return maxLat < b.getLatSouth() - pad || minLat > b.getLatNorth() + pad
                || maxLon < b.getLonWest() - pad || minLon > b.getLonEast() + pad;
    }

    /** Lightweight viewport-culling road layer; no per-road osmdroid objects. */
    private static final class RoadsOverlay extends Overlay {
        private List<RoadSegment> segments = Collections.emptyList();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        void setRoads(List<RoadSegment> segments) { this.segments = segments; }

        @Override public void draw(Canvas canvas, MapView map, boolean shadow) {
            if (shadow || !isEnabled() || map.getZoomLevelDouble() < 12.0) return;
            BoundingBox bounds = map.getBoundingBox();
            Projection projection = map.getProjection();
            Point a = new Point(), b = new Point();
            paint.setColor(Color.rgb(22, 142, 73));
            paint.setStrokeWidth(5f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (RoadSegment r : segments) {
                if (outside(bounds, Math.min(r.startLat, r.endLat), Math.max(r.startLat, r.endLat),
                        Math.min(r.startLon, r.endLon), Math.max(r.startLon, r.endLon))) continue;
                projection.toPixels(new GeoPoint(r.startLat, r.startLon), a);
                projection.toPixels(new GeoPoint(r.endLat, r.endLon), b);
                canvas.drawLine(a.x, a.y, b.x, b.y, paint);
            }
        }
    }

    /** Bounded trail, drawn directly to one canvas overlay rather than thousands of Polylines. */
    private static final class TrailOverlay extends Overlay {
        private List<TrackPoint> points = Collections.emptyList();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        void setPoints(List<TrackPoint> points) { this.points = points; }

        @Override public void draw(Canvas canvas, MapView map, boolean shadow) {
            if (shadow || !isEnabled() || points.size() < 2) return;
            BoundingBox bounds = map.getBoundingBox();
            Projection projection = map.getProjection();
            Point a = new Point(), b = new Point();
            paint.setStrokeWidth(6f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            TrackPoint previous = points.get(0);
            for (int i = 1; i < points.size(); i++) {
                TrackPoint current = points.get(i);
                double dLatM = (current.lat - previous.lat) * 111320;
                double dLonM = (current.lon - previous.lon) * 111320 *
                        Math.cos(Math.toRadians(current.lat));
                if (Math.hypot(dLatM, dLonM) <= 250 &&
                    !outside(bounds, Math.min(previous.lat, current.lat),
                        Math.max(previous.lat, current.lat), Math.min(previous.lon, current.lon),
                        Math.max(previous.lon, current.lon))) {
                    projection.toPixels(new GeoPoint(previous.lat, previous.lon), a);
                    projection.toPixels(new GeoPoint(current.lat, current.lon), b);
                    paint.setColor(current.limit <= 0 ? Color.GRAY
                            : current.speed > current.limit ? Color.RED : Color.BLUE);
                    canvas.drawLine(a.x, a.y, b.x, b.y, paint);
                }
                previous = current;
            }
        }
    }
}
