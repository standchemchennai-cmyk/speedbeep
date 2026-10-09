package com.utk.speedbeep;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
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
    private boolean follow = true;
    private double zoom = 1;
    private float downX, downY;
    public TripMapView(Context context) { super(context); setBackgroundColor(Color.rgb(239,243,240)); }
    public void setRoads(List<RoadSegment> value) { roads = value; invalidate(); }
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
        double centerLat=lat,centerLon=lon;
        if(!Double.isFinite(centerLat)) {
            if(!trip.isEmpty()) { double[] p=trip.get(trip.size()-1); centerLat=p[0];centerLon=p[1]; }
            else if(!roads.isEmpty()) {centerLat=roads.get(0).startLat;centerLon=roads.get(0).startLon;}
            else return;
        }
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
            paint.setColor(Color.WHITE);canvas.drawCircle(getWidth()/2f,getHeight()/2f,13,paint);
            paint.setColor(Color.BLUE);canvas.drawCircle(getWidth()/2f,getHeight()/2f,9,paint);
        }
        paint.setColor(Color.DKGRAY);paint.setTextSize(27);
        canvas.drawText("Green: limits  Blue: trip  Red: over  Grey: unknown",12,34,paint);
        canvas.drawText("Offline road diagram (not a street basemap)",12,getHeight()-15,paint);
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        if(event.getAction()==android.view.MotionEvent.ACTION_DOWN){downX=event.getX();downY=event.getY();return true;}
        if(event.getAction()==android.view.MotionEvent.ACTION_UP){
            zoom=Math.max(.25,Math.min(15,zoom*(event.getY()<downY?1.5:.67)));invalidate();return true;
        }
        return true;
    }
}
