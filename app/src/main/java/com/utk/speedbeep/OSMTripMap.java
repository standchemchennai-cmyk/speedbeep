package com.utk.speedbeep;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.views.overlay.MapEventsOverlay;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class OSMTripMap extends MapView {
    private final List<RoadSegment> roads = new ArrayList<>();
    private final List<org.osmdroid.views.overlay.Overlay> drawn = new ArrayList<>();
    private Marker position;
    private final List<Marker> editMarkers = new ArrayList<>();
    public interface PointTapListener { void onPoint(double latitude, double longitude); }
    private PointTapListener pointTapListener;
    private long lastLogLength = -1;
    public OSMTripMap(Context context) {
        super(context);
        Configuration.getInstance().setUserAgentValue("SpeedBeep/0.2 (com.utk.speedbeep)");
        setTileSource(TileSourceFactory.MAPNIK);
        setMultiTouchControls(true);
        setBuiltInZoomControls(false);
        setTilesScaledToDpi(true);
        getController().setZoom(13.0);
        getController().setCenter(new GeoPoint(12.93,80.20));
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
    public void setPointTapListener(PointTapListener listener) {
        pointTapListener = listener;
    }
    public void centerOn(double latitude, double longitude) {
        getController().setZoom(17.0);
        getController().setCenter(new GeoPoint(latitude, longitude));
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
    public void clearEditPoints() {
        getOverlays().removeAll(editMarkers);
        editMarkers.clear();
        invalidate();
    }
    public void setRoads(List<RoadSegment> list) {
        roads.clear();roads.addAll(list);
        drawLayers();fitRoads();
    }
    public void fitRoads() {
        if(roads.isEmpty()) return;
        double minLat=90,maxLat=-90,minLon=180,maxLon=-180;
        for(RoadSegment r:roads) {
            minLat=Math.min(minLat,Math.min(r.startLat,r.endLat));
            maxLat=Math.max(maxLat,Math.max(r.startLat,r.endLat));
            minLon=Math.min(minLon,Math.min(r.startLon,r.endLon));
            maxLon=Math.max(maxLon,Math.max(r.startLon,r.endLon));
        }
        double latPad=Math.max(.005,(maxLat-minLat)*.15);
        double lonPad=Math.max(.005,(maxLon-minLon)*.15);
        BoundingBox box=new BoundingBox(maxLat+latPad,maxLon+lonPad,minLat-latPad,minLon-lonPad);
        post(()->zoomToBoundingBox(box,true,60));
    }
    public void setPosition(double lat,double lon) {
        if(!Double.isFinite(lat)||!Double.isFinite(lon))return;
        if(position==null) {
            position=new Marker(this);
            position.setTitle("Current GPS location");
            getOverlays().add(position);
        }
        position.setPosition(new GeoPoint(lat,lon));
        position.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_CENTER);
        invalidate();
    }
    public void reloadTrip() {
        java.io.File f=DriveLog.file(getContext());
        if(!f.exists()||f.length()==lastLogLength)return;
        lastLogLength=f.length();
        drawLayers();
    }
    private void drawLayers() {
        getOverlays().removeAll(drawn);drawn.clear();
        for(RoadSegment r:roads) {
            Polyline line=new Polyline();
            line.setPoints(Arrays.asList(new GeoPoint(r.startLat,r.startLon),new GeoPoint(r.endLat,r.endLon)));
            line.getOutlinePaint().setColor(Color.rgb(22,142,73));
            line.getOutlinePaint().setStrokeWidth(7);
            line.setTitle(r.name+" — "+(int)r.limitKph+" km/h");
            drawn.add(line);getOverlays().add(line);
        }
        try(BufferedReader reader=new BufferedReader(new FileReader(DriveLog.file(getContext())))) {
            String row=reader.readLine();
            double[] last=null;
            int count=0;
            while((row=reader.readLine())!=null&&count++<20000) {
                String[] p=row.split(",",-1);if(p.length<9)continue;
                double[] current={Double.parseDouble(p[1]),Double.parseDouble(p[2]),Double.parseDouble(p[3]),Double.parseDouble(p[7])};
                if(last!=null&&Math.hypot((current[0]-last[0])*111320,(current[1]-last[1])*111320*Math.cos(Math.toRadians(current[0])))<250) {
                    Polyline line=new Polyline();
                    line.setPoints(Arrays.asList(new GeoPoint(last[0],last[1]),new GeoPoint(current[0],current[1])));
                    line.getOutlinePaint().setColor(current[3]<=0?Color.GRAY:current[2]>current[3]?Color.RED:Color.BLUE);
                    line.getOutlinePaint().setStrokeWidth(8);
                    drawn.add(line);getOverlays().add(line);
                }
                last=current;
            }
        } catch(Exception ignored) {}
        invalidate();
    }
}
