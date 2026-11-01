package com.utk.speedbeep;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class SpeedMonitorService extends Service implements LocationListener {
    public static final String ACTION_STOP = "com.utk.speedbeep.STOP";
    public static final String ACTION_RELOAD = "com.utk.speedbeep.RELOAD_ROADS";
    public static final String PREFS = "speedbeep_status";

    public static final String SETTINGS = "speedbeep_settings";
    public static final String KEY_UNIVERSAL_ENABLED = "universal_enabled";
    public static final String KEY_UNIVERSAL_KPH = "universal_kph";
    private static final int NOTIFICATION_ID = 3141;
    private static final String CHANNEL_ID = "speed_monitor";
    private static final long LOCATION_INTERVAL_MS = 1000L;
    private static final float MAX_ACCURACY_M = 60f;
    private static final long OVER_LIMIT_HOLD_MS = 2000L;
    private static final long BEEP_REPEAT_MS = 4000L;

    private LocationManager locationManager;
    private AlarmSoundPlayer alarmPlayer;
    private List<RoadSegment> roads = Collections.emptyList();
    private RoadSegment currentRoad;
    private long overLimitSinceMs = 0L;
    private long lastBeepMs = 0L;
    private long lastNotificationUpdateMs = 0L;
    private long lastLogMs = 0L;
    private SpeedLimitPolicy.Decision activeLimit = SpeedLimitPolicy.none();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification starting = buildNotification("Starting GPS…");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, starting, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, starting);
        }

        alarmPlayer = new AlarmSoundPlayer(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        try {
            roads = LocalRoadStore.load(this);
        } catch (IOException ex) {
            writeStatus(false, 0, null, 0, 0, "CSV error: " + ex.getMessage());
            stopSelf();
            return;
        }

        startLocationUpdates();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RELOAD.equals(intent.getAction())) {
            try {
                roads = LocalRoadStore.load(this);
                currentRoad = null;
                resetOverspeed();
                updateNotification("Speed-limit database updated");
            } catch (IOException ex) {
                updateNotification("Road update failed: " + ex.getMessage());
            }
        }
        return START_STICKY;
    }

    private void startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            writeStatus(false, 0, null, 0, 0, "Location permission missing");
            stopSelf();
            return;
        }

        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);

        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_INTERVAL_MS,
                0f,
                this,
                Looper.getMainLooper());

        if (gpsEnabled) {
            writeStatus(true, 0, null, 0, 0, "Waiting for GPS fix…");
        } else {
            writeStatus(true, 0, null, 0, 0, "GPS is disabled");
            updateNotification("GPS is disabled");
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putFloat("latitude", (float) location.getLatitude())
                .putFloat("longitude", (float) location.getLongitude())
                .putFloat("lastFixAccuracyM", location.hasAccuracy() ? location.getAccuracy() : 999f)
                .putBoolean("lastFixHasBearing", location.hasBearing())
                .putFloat("lastFixBearing", location.hasBearing() ? location.getBearing() : 0f)
                .putFloat("lastFixSpeedKph", (float) getSpeedKph(location))
                .putLong("lastFixAt", System.currentTimeMillis())
                .apply();
        double speedKph = getSpeedKph(location);
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : 999f;

        if (accuracy > MAX_ACCURACY_M) {
            logSample(location, speedKph, accuracy, "WEAK_GPS", null, 0);
            currentRoad = null;
            resetOverspeed();
            String note = String.format(Locale.US, "GPS weak (±%.0f m)", accuracy);
            writeStatus(true, speedKph, null, 0, accuracy, note);
            maybeUpdateNotification(speedKph, null, note);
            return;
        }

        GeoMatcher.Match match = GeoMatcher.findBest(
                location.getLatitude(), location.getLongitude(), accuracy, roads);
        RoadSegment road = match == null ? null : match.road;
        SharedPreferences settings = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        boolean fallbackEnabled = settings.getBoolean("fallback_enabled", false);
        float fallbackLimit = settings.getFloat("fallback_kph", 40f);
        float fallbackMargin = settings.getFloat("fallback_margin", 3f);
        boolean universalEnabled = settings.getBoolean(KEY_UNIVERSAL_ENABLED, false);
        float universalLimit = settings.getFloat(KEY_UNIVERSAL_KPH, 60f);

        SpeedLimitPolicy.Decision selected = SpeedLimitPolicy.select(
                road == null ? null : road.limitKph,
                road == null ? 0 : road.marginKph,
                fallbackEnabled, fallbackLimit, fallbackMargin,
                universalEnabled, universalLimit);

        // Reset hold/repeat when the actual winning alert threshold changes,
        // not merely when the named road changes. One alarm at a time.
        if (!selected.sameAlarmAs(activeLimit)) resetOverspeed();
        activeLimit = selected;
        currentRoad = road;

        final boolean over = selected.isOver(speedKph);
        long now = SystemClock.elapsedRealtime();
        if (over) {
            if (overLimitSinceMs == 0) overLimitSinceMs = now;
            if (now - overLimitSinceMs >= OVER_LIMIT_HOLD_MS &&
                    now - lastBeepMs >= BEEP_REPEAT_MS) {
                String sound = AlarmSoundPlayer.getSelectedMode(this);
                if (selected.source == SpeedLimitPolicy.Source.UNIVERSAL) {
                    alarmPlayer.playUniversal(sound, selected.limitKph);
                } else {
                    alarmPlayer.play(sound, selected.limitKph,
                            selected.source == SpeedLimitPolicy.Source.FALLBACK);
                }
                lastBeepMs = now;
            }
        } else {
            // Keep the last beep cooldown within a continuous monitoring session
            // so noisy GPS near the threshold cannot produce repeated beeps.
            overLimitSinceMs = 0;
        }

        RoadSegment displayedRoad = road;
        if (road == null && fallbackEnabled && selected.source != SpeedLimitPolicy.Source.NONE) {
            // Preserve the existing fallback label in the UI/log only if enabled.
            displayedRoad = new RoadSegment("Unmapped road (user default)",
                    location.getLatitude(), location.getLongitude(),
                    location.getLatitude(), location.getLongitude(),
                    fallbackLimit, 40, fallbackMargin);
        }

        String note;
        if (selected.source == SpeedLimitPolicy.Source.UNIVERSAL) {
            note = over
                    ? String.format(Locale.US, "Over universal ceiling %.0f km/h", selected.limitKph)
                    : String.format(Locale.US, "Universal ceiling %.0f km/h active", selected.limitKph);
        } else if (selected.source == SpeedLimitPolicy.Source.ROAD) {
            note = over ? String.format(Locale.US, "Over %.0f + %.0f margin",
                    selected.limitKph, road.marginKph) : "Monitoring";
        } else if (selected.source == SpeedLimitPolicy.Source.FALLBACK) {
            note = "User fallback limit, not verified legal limit";
        } else {
            note = "No configured road here";
        }
        String result = road != null ? "MATCHED"
                : fallbackEnabled ? "FALLBACK" : "NO_ROAD";
        logSample(location, speedKph, accuracy, result, displayedRoad,
                match == null ? 0 : match.distanceM);
        writeStatus(true, speedKph, displayedRoad,
                match == null ? 0 : match.distanceM, accuracy, note);
        maybeUpdateNotification(speedKph, displayedRoad, note);
    }

    private void logSample(Location location, double speed, float accuracy, String result, RoadSegment road, double distance) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLogMs < 5000L) return;
        lastLogMs = now;
        try { DriveLog.append(this, location, speed, accuracy, result, road, distance); }
        catch (IOException ignored) { }
    }

    private double getSpeedKph(Location location) {
        if (!location.hasSpeed()) return 0.0;
        double kph = location.getSpeed() * 3.6;
        if (kph < 0) return 0.0;
        return Math.min(kph, 300.0);
    }

    private void resetOverspeed() {
        overLimitSinceMs = 0L;
        lastBeepMs = 0L;
        activeLimit = SpeedLimitPolicy.none();
    }

    private void maybeUpdateNotification(double speedKph, RoadSegment road, String note) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationUpdateMs < 2000L) return;
        lastNotificationUpdateMs = now;

        String text;
        if (activeLimit.source == SpeedLimitPolicy.Source.UNIVERSAL) {
            text = String.format(Locale.US, "%.0f km/h • universal cap %.0f km/h",
                    speedKph, activeLimit.limitKph);
        } else if (road == null) {
            text = String.format(Locale.US, "%.0f km/h • %s", speedKph, note);
        } else {
            text = String.format(Locale.US, "%.0f km/h • limit %.0f • %s", speedKph, road.limitKph, road.name);
        }
        updateNotification(text);
    }

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Speed monitoring",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Persistent notification while SpeedBeep monitors GPS speed");
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, SpeedMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SpeedBeep is monitoring")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stopPi).build())
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void writeStatus(boolean running, double speedKph, RoadSegment road,
                             double distanceM, float accuracyM, String note) {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putBoolean("running", running);
        e.putFloat("speedKph", (float) speedKph);
        e.putString("roadName", road == null ? "" : road.name);
        e.putFloat("limitKph", road == null ? 0f : (float) road.limitKph);
        e.putFloat("marginKph", road == null ? 0f : (float) road.marginKph);
        e.putFloat("distanceM", (float) distanceM);
        e.putFloat("accuracyM", accuracyM);
        e.putString("note", note == null ? "" : note);
        e.putString("effectiveSource", activeLimit.source.name());
        e.putFloat("effectiveThresholdKph", (float) activeLimit.thresholdKph);
        e.putFloat("effectiveLimitKph", (float) activeLimit.limitKph);
        e.putLong("updatedAt", System.currentTimeMillis());
        e.apply();
    }

    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onDestroy() {
        if (locationManager != null) locationManager.removeUpdates(this);
        if (alarmPlayer != null) alarmPlayer.close();
        writeStatus(false, 0, null, 0, 0, "Stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
