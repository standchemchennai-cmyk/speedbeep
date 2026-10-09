package com.utk.speedbeep;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Offline schematic map: configured road segments plus recorded GPS trail. */
public final class TripMapView extends View {
    private final Paint paint = new Paint(3);
    private List<RoadSegment> roads = new ArrayList<>();
    private final List<double[]> trip = new ArrayList<>();
    private double lat = Double.NaN, lon = Double.NaN;
    private double zoom = 1;
    private double panEastM = 0, panNorthM = 0;
    private float lastX, lastY;
    private final ScaleGestureDetector pinch = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
        @Override public boolean onScale(ScaleGestureDetector detector) {
            zoom = Math.max(.15, Math.min(100, zoom * detector.getScaleFactor()));
            invalidate(); return true;
        }
    });
    public TripMapView(Context context) { super(context); setBackgroundColor(Color.rgb(239,243,240)); }
    public void setRoads(List<RoadSegment> value) { roads = value; fitRoads(); invalidate(); }
    public void resetView() { panEastM=0; panNorthM=0; fitRoads(); invalidate(); }
    private void fitRoads() {
        if (roads.isEmpty()) return;
        double minLat=90,maxLat=-90,minLon=180,maxLon=-180;
        for(RoadSegment r:roads) {
            minLat=Math.min(minLat,Math.min(r.startLat,r.endLat));maxLat=Math.max(maxLat,Math.max(r.startLat,r.endLat));
            minLon=Math.min(minLon,Math.min(r.startLon,r.endLon));maxLon=Math.max(maxLon,Math.max(r.startLon,r.endLon));
        }
        double midLat=(minLat+maxLat)/2, midLon=(minLon+maxLon)/2;
        double width=(maxLon-minLon)*111320*Math.cos(Math.toRadians(midLat));
        double height=(maxLat-minLat)*111320;
        zoom=Math.max(.15,Math.min(100,3500/Math.max(300,Math.max(width,height))*0.85));
        panEastM=0;panNorthM=0;
        centerLat=midLat;centerLon=midLon;
    }
    private double centerLat=Double.NaN,centerLon=Double.NaN;
    public void setPosition(double latitude, double longitude) { lat=latitude; lon=longitude; invalidate(); }
    public void reloadTrip() {
        trip.clear();
        try (BufferedReader reader = new BufferedReader(new FileReader(DriveLog.file(getContext())))) {
            String line;
            reader.readLine();
            while ((line=reader.readLine())!=null) {
                String[] p=line.split(",",-1);
                if(p.length<9) continue;
                double a=Double.parseDouble(p[1]), b=Double.parseDouble(p[2]);
                double speed=Double.parseDouble(p[3]), limit=Double.parseDouble(p[7]);
                trip.add(new double[]{a,b,speed,limit});
            }
        } catch (Exception ignored) { }
        if(trip.size()>1500) trip.subList(0,trip.size()-1500).clear();
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        double centerLat=this.centerLat,centerLon=this.centerLon;
        if(!Double.isFinite(centerLat)) {
            if(!trip.isEmpty()) { double[] p=trip.get(trip.size()-1); centerLat=p[0];centerLon=p[1]; }
            else if(!roads.isEmpty()) {centerLat=roads.get(0).startLat;centerLon=roads.get(0).startLon;}
            else return;
        }
        centerLat += panNorthM/111320.0;
        centerLon += panEastM/(111320.0*Math.max(.01,Math.cos(Math.toRadians(centerLat))));
        double cos=Math.max(.01,Math.cos(Math.toRadians(centerLat)));
        double scale=Math.min(getWidth(),getHeight())/3500.0*zoom;
        for(RoadSegment r:roads) {
            float x1=(float)(getWidth()/2+(r.startLon-centerLon)*111320*cos*scale);
            float y1=(float)(getHeight()/2-(r.startLat-centerLat)*111320*scale);
            float x2=(float)(getWidth()/2+(r.endLon-centerLon)*111320*cos*scale);
            float y2=(float)(getHeight()/2-(r.endLat-centerLat)*111320*scale);
            paint.setColor(Color.rgb(31,143,83));paint.setStrokeWidth(5);canvas.drawLine(x1,y1,x2,y2,paint);
            if(Math.hypot(x2-x1,y2-y1)>55) {
                paint.setColor(Color.rgb(20,82,48));paint.setTextSize(25);
                canvas.drawText(String.format(Locale.US,"%.0f",r.limitKph),(x1+x2)/2,(y1+y2)/2-8,paint);
            }
        }
        for(int i=1;i<trip.size();i++) {
            double[] a=trip.get(i-1),b=trip.get(i);
            if(Math.hypot((a[0]-b[0])*111320,(a[1]-b[1])*111320*cos)>250)continue;
            paint.setColor(b[3]<=0?Color.GRAY:b[2]>b[3]?Color.RED:Color.rgb(33,110,226));
            paint.setStrokeWidth(7);
            canvas.drawLine((float)(getWidth()/2+(a[1]-centerLon)*111320*cos*scale),
                    (float)(getHeight()/2-(a[0]-centerLat)*111320*scale),
                    (float)(getWidth()/2+(b[1]-centerLon)*111320*cos*scale),
                    (float)(getHeight()/2-(b[0]-centerLat)*111320*scale),paint);
        }
        if(Double.isFinite(lat)) {
            float px=(float)(getWidth()/2+(lon-centerLon)*111320*cos*scale);
            float py=(float)(getHeight()/2-(lat-centerLat)*111320*scale);
            paint.setColor(Color.WHITE);canvas.drawCircle(px,py,13,paint);
            paint.setColor(Color.BLUE);canvas.drawCircle(px,py,9,paint);
        }
        paint.setColor(Color.DKGRAY);paint.setTextSize(27);
        canvas.drawText("Green: limits  Blue: trip  Red: over  Grey: unknown",12,34,paint);
        canvas.drawText("Offline road diagram (not a street basemap)",12,getHeight()-15,paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        pinch.onTouchEvent(event);
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                lastX=event.getX();lastY=event.getY();return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                return true;
            case MotionEvent.ACTION_MOVE:
                if(event.getPointerCount()==1 && !pinch.isInProgress()) {
                    double scale=Math.min(getWidth(),getHeight())/3500.0*zoom;
                    if(scale>0) {
                        panEastM -= (event.getX()-lastX)/scale;
                        panNorthM += (event.getY()-lastY)/scale;
                    }
                    invalidate();
                }
                lastX=event.getX();lastY=event.getY();return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);return true;
        }
        return true;
    }
}
