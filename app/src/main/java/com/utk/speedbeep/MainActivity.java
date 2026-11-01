package com.utk.speedbeep;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.CheckBox;
import java.util.ArrayList;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.view.inputmethod.InputMethodManager;
import android.text.InputType;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQ_LOCATION = 100;
    private static final int REQ_NOTIFICATIONS = 101;
    private static final int REQ_IMPORT = 200;
    private static final int REQ_EXPORT = 201;
    private static final int REQ_LOG_EXPORT = 202;

    private OSMTripMap tripMap;
    private android.app.Dialog mapDialog;
    private TextView mapStatusLabel;
    private boolean followMapGps = true;
    private long lastMapReload = 0;
    private TextView bigSpeed;
    private TextView roadText;
    private TextView limitText;
    private TextView detailText;
    private TextView csvText;
    private Button startButton;
    private Button stopButton;
    private Button alarmButton;
    private EditText quickLimitInput;
    private TextView quickRoadHint;
    private TextView quickSavedHint;
    private Button quickSetButton;
    private LocationManager quickLocationManager;
    private LocationListener quickLocationListener;
    private Location latestUiLocation;
    private List<RoadSegment> cachedRoads = java.util.Collections.emptyList();
    private RoadSegment quickRoadSeed;
    private List<RoadSegment> quickRoadParts = java.util.Collections.emptyList();
    private long evaluatedFixTime = -1L;
    private volatile boolean quickSaving = false;
    private boolean startAfterLocationPermission = false;
    private final java.util.concurrent.ExecutorService editExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    // Only a recent GPS sample is allowed, especially when the vehicle is moving.
    // A speed-based lock is unnecessary: edits apply to the road at tap time.
    private static final long MAX_QUICK_FIX_AGE_MS = 10000L;
    private static final float MAX_QUICK_FIX_ACCURACY_M = 30f;
    private boolean selectingMapStretch = false;
    private double[] stretchStart;
    private boolean manualTracing = false;
    private final List<double[]> manualTracePoints = new ArrayList<>();
    private Button traceButton;
    private Button traceUndoButton;
    private Button traceFinishButton;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 750L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Configure tile caching and identification before constructing osmdroid's MapView.
        org.osmdroid.config.Configuration.getInstance().load(
                getApplicationContext(), getSharedPreferences("osmdroid", MODE_PRIVATE));
        org.osmdroid.config.Configuration.getInstance().setUserAgentValue(
                "SpeedBeep/0.4.0 (com.utk.speedbeep)");
        setContentView(buildUi());
        refreshCsvInfo();
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("SpeedBeep");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Local CSV • GPS only • no account • no API");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        root.addView(subtitle);

        bigSpeed = new TextView(this);
        bigSpeed.setText("—");
        bigSpeed.setTextSize(64);
        bigSpeed.setTypeface(Typeface.DEFAULT_BOLD);
        bigSpeed.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(bigSpeed, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView speedLabel = new TextView(this);
        speedLabel.setText("GPS km/h");
        speedLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        speedLabel.setTextSize(14);
        speedLabel.setPadding(0, 0, 0, dp(18));
        root.addView(speedLabel);

        roadText = label("Road: —", 20, true);
        limitText = label("Limit: —", 26, true);
        detailText = label("Stopped", 15, false);
        detailText.setPadding(0, dp(8), 0, dp(16));
        root.addView(roadText);
        root.addView(limitText);
        root.addView(detailText);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = new Button(this);
        startButton.setText("START");
        stopButton = new Button(this);
        stopButton.setText("STOP");
        buttons.addView(startButton, new LinearLayout.LayoutParams(0, dp(56), 1));
        buttons.addView(stopButton, new LinearLayout.LayoutParams(0, dp(56), 1));
        root.addView(buttons);
        startButton.setOnClickListener(v -> startMonitoring());
        stopButton.setOnClickListener(v -> stopMonitoring());

        TextView quickTitle = label("Set speed limit for this road", 18, true);
        quickTitle.setPadding(0, dp(12), 0, dp(3));
        root.addView(quickTitle);

        // Two actions from the main screen: type km/h, tap SET THIS ROAD.
        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setGravity(Gravity.CENTER_VERTICAL);
        quickLimitInput = new EditText(this);
        quickLimitInput.setSingleLine(true);
        quickLimitInput.setHint("km/h");
        quickLimitInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        quickLimitInput.setSelectAllOnFocus(true);
        quickLimitInput.setTextSize(22f);
        quickLimitInput.setContentDescription("New road speed limit in kilometres per hour");
        quickRow.addView(quickLimitInput,
                new LinearLayout.LayoutParams(0, dp(56), 1));

        quickSetButton = new Button(this);
        quickSetButton.setText("SET THIS ROAD");
        quickSetButton.setOnClickListener(v -> setQuickCurrentRoadLimit());
        quickRow.addView(quickSetButton, new LinearLayout.LayoutParams(0, dp(56), 2));
        root.addView(quickRow);

        quickRoadHint = label("Finding your current road…", 14, false);
        quickRoadHint.setPadding(0, dp(4), 0, dp(3));
        root.addView(quickRoadHint);
        quickSavedHint = label("Uses your live GPS road match. Save only verified speed limits.", 13, false);
        root.addView(quickSavedHint);

        Button exportButton = new Button(this);
        exportButton.setText("EXPORT SPEED DATABASE");
        exportButton.setOnClickListener(v -> exportCsv());
        root.addView(exportButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        tripMap = new OSMTripMap(this);
        Button openMap = new Button(this);
        openMap.setText("OPEN FULL-SCREEN MAP");
        openMap.setOnClickListener(v -> showMapDialog());
        root.addView(openMap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        TextView mapHelp = label("Full-screen OpenStreetMap: follow GPS, pinch/drag, see nearby saved road limits and edit a stretch. Internet needed for uncached tiles.", 13, false);
        root.addView(mapHelp);

        alarmButton = new Button(this);
        alarmButton.setOnClickListener(v -> showAlarmSettings());
        updateAlarmButton();
        root.addView(alarmButton);

        Button fallbackButton = new Button(this);
        fallbackButton.setText("UNMAPPED ROAD SPEED SETTINGS");
        fallbackButton.setOnClickListener(v -> showFallbackSettings());
        root.addView(fallbackButton);

        Button editButton = new Button(this);
        editButton.setText("EDIT CONFIGURED ROAD LIMITS");
        editButton.setOnClickListener(v -> chooseRoadToEdit());
        root.addView(editButton);

        Button currentRoadButton = new Button(this);
        currentRoadButton.setText("ADVANCED: ENTIRE ROAD OR ONE SEGMENT");
        currentRoadButton.setOnClickListener(v -> chooseCurrentRoadLimit());
        root.addView(currentRoadButton);

        Button pickStretchButton = new Button(this);
        pickStretchButton.setText("SELECT A STRETCH ON MAP (SNAP TO ROAD)");
        pickStretchButton.setOnClickListener(v -> chooseMapStretch());
        root.addView(pickStretchButton);

        csvText = label("CSV: checking…", 14, false);
        csvText.setPadding(0, dp(22), 0, dp(8));
        root.addView(csvText);

        Button importButton = new Button(this);
        importButton.setText("IMPORT SPEED_LIMITS.CSV");
        importButton.setOnClickListener(v -> chooseCsv());
        root.addView(importButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        Button exportLog = new Button(this);
        exportLog.setText("EXPORT DRIVE DIAGNOSTICS");
        exportLog.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/csv");
            i.putExtra(Intent.EXTRA_TITLE, "drive_diagnostics.csv");
            startActivityForResult(i, REQ_LOG_EXPORT);
        });
        root.addView(exportLog);

        TextView help = label("CSV columns:\nname,start_lat,start_lon,end_lat,end_lon,limit_kph,corridor_m,margin_kph\n\nUse several short rows for curved roads. V1 warns only on roads in your CSV. Tap Start after getting into the car; monitoring continues with the screen off.", 14, false);
        help.setPadding(0, dp(18), 0, dp(12));
        root.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void startMonitoring() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            startAfterLocationPermission = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        try {
            List<RoadSegment> roads = LocalRoadStore.load(this);
            if (roads.isEmpty()) throw new IOException("No road segments");
        } catch (IOException ex) {
            toast("Fix/import CSV first: " + ex.getMessage());
            return;
        }
        startForegroundService(new Intent(this, SpeedMonitorService.class));
        toast("Speed monitoring started");
    }

    private void stopMonitoring() {
        Intent i = new Intent(this, SpeedMonitorService.class).setAction(SpeedMonitorService.ACTION_STOP);
        startService(i);
        refreshStatus();
    }

    private static final class CurrentFix {
        final double lat, lon;
        final float accuracyM, speedKph, bearingDeg;
        final boolean hasBearing;
        final long timestamp;
        CurrentFix(double lat, double lon, float accuracyM, float speedKph,
                   boolean hasBearing, float bearingDeg, long timestamp) {
            this.lat = lat; this.lon = lon; this.accuracyM = accuracyM;
            this.speedKph = speedKph; this.timestamp = timestamp;
            this.hasBearing = hasBearing; this.bearingDeg = bearingDeg;
        }
    }

    private CurrentFix currentFix() {
        CurrentFix fix = null;
        if (latestUiLocation != null) {
            long time = latestUiLocation.getTime();
            if (time > 0 && time <= System.currentTimeMillis() + 3000L) {
                fix = new CurrentFix(latestUiLocation.getLatitude(), latestUiLocation.getLongitude(),
                        latestUiLocation.hasAccuracy() ? latestUiLocation.getAccuracy() : 999f,
                        latestUiLocation.hasSpeed() ? latestUiLocation.getSpeed() * 3.6f : 0f,
                        latestUiLocation.hasBearing(),
                        latestUiLocation.hasBearing() ? latestUiLocation.getBearing() : 0f, time);
            }
        }
        SharedPreferences p = getSharedPreferences(SpeedMonitorService.PREFS, MODE_PRIVATE);
        long serviceTime = p.getLong("lastFixAt", 0);
        if (serviceTime > 0 && (fix == null || serviceTime > fix.timestamp)) {
            fix = new CurrentFix(p.getFloat("latitude", Float.NaN),
                    p.getFloat("longitude", Float.NaN),
                    p.getFloat("lastFixAccuracyM", 999f),
                    p.getFloat("lastFixSpeedKph", p.getFloat("speedKph", 0f)),
                    p.getBoolean("lastFixHasBearing", false),
                    p.getFloat("lastFixBearing", 0f), serviceTime);
        }
        return fix;
    }

    private void beginQuickGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED || quickLocationListener != null) return;
        quickLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (quickLocationManager == null) return;
        quickLocationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                latestUiLocation = location;
                updateQuickRoadStatus();
            }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        };
        try {
            quickLocationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f,
                    quickLocationListener, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException ex) {
            quickLocationListener = null;
            quickRoadHint.setText("Turn on GPS and allow precise location to set this road.");
        }
    }

    private void endQuickGps() {
        if (quickLocationManager != null && quickLocationListener != null) {
            quickLocationManager.removeUpdates(quickLocationListener);
        }
        quickLocationListener = null;
    }

    private void updateQuickRoadStatus() {
        if (quickRoadHint == null || quickSaving) return;
        CurrentFix fix = currentFix();
        long now = System.currentTimeMillis();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            quickRoadSeed = null;
            quickRoadParts = java.util.Collections.emptyList();
            quickRoadHint.setText("Precise location permission needed. Tap SET THIS ROAD to allow it.");
            return;
        }
        if (fix == null || fix.timestamp <= 0 || now < fix.timestamp - 3000L ||
                now - fix.timestamp > MAX_QUICK_FIX_AGE_MS) {
            quickRoadSeed = null;
            quickRoadParts = java.util.Collections.emptyList();
            evaluatedFixTime = -1L;
            quickRoadHint.setText("Waiting for a fresh GPS fix on this road…");
            return;
        }
        if (!Double.isFinite(fix.lat) || !Double.isFinite(fix.lon) ||
                !Float.isFinite(fix.accuracyM) || fix.accuracyM > MAX_QUICK_FIX_ACCURACY_M) {
            quickRoadSeed = null;
            quickRoadParts = java.util.Collections.emptyList();
            quickRoadHint.setText(String.format(Locale.US,
                    "GPS uncertainty ±%.0f m; need ±30 m or better.", fix.accuracyM));
            return;
        }
        // The existing database can contain thousands of rows. Re-evaluate only
        // for a NEW GPS sample, and only rebuild connectivity when the seed changes.
        if (evaluatedFixTime != fix.timestamp) {
            evaluatedFixTime = fix.timestamp;
            try {
                // Show the same safe local target that SET THIS ROAD will edit.
                // The speed-monitoring matcher may accept much wider corridors;
                // an edit must not silently choose a nearby, different road.
                GeoMatcher.Match match = LocalRoadEditMatcher.match(
                        cachedRoads, fix.lat, fix.lon, fix.accuracyM);
                RoadSegment candidate = match == null ? null : match.road;
                if (candidate != quickRoadSeed) {
                    quickRoadSeed = candidate;
                    quickRoadParts = candidate == null
                            ? java.util.Collections.emptyList()
                            : RoadLimitEditor.connectedRoad(cachedRoads, candidate);
                }
            } catch (IllegalArgumentException ambiguous) {
                quickRoadSeed = null;
                quickRoadParts = java.util.Collections.emptyList();
                quickRoadHint.setText(ambiguous.getMessage());
                return;
            }
        }
        if (quickRoadSeed == null) {
            quickRoadHint.setText("No saved limit here • SET THIS ROAD tries OSM, then a short GPS-only section if the lookup fails.");
        } else {
            double lengthM = 0;
            for (RoadSegment segment : quickRoadParts) lengthM += RoadLimitEditor.lengthM(segment);
            quickRoadHint.setText(String.format(Locale.US,
                    "Target: %s • current %.0f km/h • %.0f m (%d linked pieces)",
                    RoadLimitEditor.baseName(quickRoadSeed.name),
                    quickRoadSeed.limitKph, lengthM, quickRoadParts.size()));
        }

    }

    private void setQuickCurrentRoadLimit() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            return;
        }
        if (quickSaving || !editingAllowed()) return;
        CurrentFix fix = currentFix();
        if (fix == null || System.currentTimeMillis() - fix.timestamp > MAX_QUICK_FIX_AGE_MS ||
                fix.accuracyM > MAX_QUICK_FIX_ACCURACY_M || !Double.isFinite(fix.lat) ||
                !Double.isFinite(fix.lon)) {
            toast("GPS not ready. Need a recent fix accurate to 30 m or better.");
            return;
        }
        final double newLimit;
        try {
            newLimit = Double.parseDouble(quickLimitInput.getText().toString().trim());
            if (!Double.isFinite(newLimit) || newLimit < 5 || newLimit > 200) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException ex) {
            quickLimitInput.setError("Enter a speed from 5 to 200 km/h");
            return;
        }
        // Capture the exact GPS point and direction AT THE MOMENT OF THE TAP,
        // so results arriving from a network request do not affect another road.
        quickSaving = true;
        quickSetButton.setEnabled(false);
        quickSavedHint.setText("Detecting road and saving limit…");
        final double lat = fix.lat, lon = fix.lon;
        final float accuracy = fix.accuracyM;
        final boolean hasBearing = fix.hasBearing;
        final float bearing = fix.bearingDeg;
        final float speed = fix.speedKph;

        editExecutor.execute(() -> {
            try {
                List<RoadSegment> currentRoads = LocalRoadStore.load(this);
                // Prefer a reliable locally mapped road at the tap-time GPS fix.
                // A 4 m cutoff was incorrect: normal OSM/GPS offsets can be
                // 10 m or more on the same already-configured road.
                GeoMatcher.Match localMatch = LocalRoadEditMatcher.match(
                        currentRoads, lat, lon, accuracy);
                String roadName;
                String source;
                List<RoadSegment> parts;
                boolean gpsOnly = false;
                if (localMatch != null) {
                    roadName = RoadLimitEditor.baseName(localMatch.road.name);
                    parts = RoadLimitEditor.connectedRoad(currentRoads, localMatch.road);
                    source = "local road database";
                } else {
                    // OSM contains highway geometry even where NO maxspeed tag
                    // exists. Retrieve and save it automatically on this tap.
                    try {
                        OverpassRoadResolver.Result discovered = OverpassRoadResolver.resolve(
                                lat, lon, accuracy, hasBearing, bearing, speed);
                        roadName = discovered.name;
                        parts = discovered.pieces;
                        source = discovered.extended
                                ? "OpenStreetMap road geometry"
                                : "OpenStreetMap way (limited coverage)";
                    } catch (OverpassRoadResolver.LookupUnavailableException |
                             OverpassRoadResolver.RoadNotFoundException unavailable) {
                        // A failed online lookup must not make saving impossible.
                        // Never infer a whole road from one GPS fix: save only a
                        // narrow, short locally identified stretch, or reject a
                        // weak GPS fix. Ambiguous OSM matches still fail safely.
                        RoadSegment gpsPart = GpsRoadFallback.create(
                                lat, lon, accuracy, hasBearing, bearing, speed);
                        roadName = gpsPart.name;
                        parts = java.util.Collections.singletonList(gpsPart);
                        source = "local GPS-only fallback";
                        gpsOnly = true;
                    }
                }
                if (parts.isEmpty()) throw new IOException("No connected road geometry found.");
                final String chosenRoad = roadName;
                final String detectedFrom = source;
                final boolean savedGpsOnly = gpsOnly;
                final int affectedPieces = parts.size();
                double meters = 0;
                for (RoadSegment part : parts) meters += RoadLimitEditor.lengthM(part);
                final double updatedLengthM = meters;

                List<RoadSegment> updated = RoadLimitEditor.override(
                        currentRoads, parts, newLimit, roadName);
                LocalRoadStore.save(this, updated);
                runOnUiThread(() -> {
                    quickSaving = false;
                    quickSetButton.setEnabled(true);
                    quickRoadSeed = null;
                    evaluatedFixTime = -1L;
                    refreshCsvInfo();
                    if (getSharedPreferences(SpeedMonitorService.PREFS, MODE_PRIVATE)
                            .getBoolean("running", false)) {
                        startService(new Intent(this, SpeedMonitorService.class)
                                .setAction(SpeedMonitorService.ACTION_RELOAD));
                    }
                    String saved = savedGpsOnly
                            ? String.format(Locale.US,
                                    "Saved %.0f km/h in a SHORT GPS-only area (~%.0f m centreline, narrow corridor). " +
                                    "Not the entire road. For longer coverage use SELECT A STRETCH ON MAP.",
                                    newLimit, updatedLengthM)
                            : String.format(Locale.US,
                                    "Saved %.0f km/h on %s • %.0f m (%d sections; %s).",
                                    newLimit, chosenRoad, updatedLengthM, affectedPieces, detectedFrom);
                    quickSavedHint.setText(saved);
                    InputMethodManager keyboard =
                            (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (keyboard != null) {
                        keyboard.hideSoftInputFromWindow(quickLimitInput.getWindowToken(), 0);
                    }
                    toast(saved);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    quickSaving = false;
                    quickSetButton.setEnabled(true);
                    quickSavedHint.setText("Not saved: " + ex.getMessage());
                    toast("Road limit not changed: " + ex.getMessage());
                });
            }
        });
    }

    private boolean editingAllowed() {
        // Editing is permitted while monitoring or travelling. GPS validation
        // happens in the GPS-based editors; selecting geometry works offline.
        return true;
    }

    private void chooseCurrentRoadLimit() {
        if (!editingAllowed()) return;
        CurrentFix fix = currentFix();
        long ageMs = fix == null ? Long.MAX_VALUE : System.currentTimeMillis() - fix.timestamp;
        if (fix == null || ageMs < 0 || ageMs > MAX_QUICK_FIX_AGE_MS ||
                !Double.isFinite(fix.lat) || !Double.isFinite(fix.lon) ||
                !Float.isFinite(fix.accuracyM) || fix.accuracyM > MAX_QUICK_FIX_ACCURACY_M) {
            toast("GPS not ready. Need a recent fix accurate to 30 m or better.");
            return;
        }
        double latitude = fix.lat;
        double longitude = fix.lon;
        float accuracy = fix.accuracyM;
        try {
            List<RoadSegment> roads = LocalRoadStore.load(this);
            GeoMatcher.Match nearest = GeoMatcher.findBest(latitude, longitude, accuracy, roads);
            if (nearest == null) {
                toast("No mapped speed-limit road here. Use SELECT A STRETCH on the map to add a new limit.");
                return;
            }
            RoadSegment road = nearest.road;
            List<RoadSegment> connected = RoadLimitEditor.connectedRoad(roads, road);
            double meters = 0;
            for (RoadSegment segment : connected) meters += RoadLimitEditor.lengthM(segment);
            String detail = String.format(Locale.US,
                    "Detected: %s (%.0f m from GPS)\nConnected road: %d pieces, about %.1f km.\n\n" +
                    "Choose ENTIRE ROAD to update all connected pieces of this road name, " +
                    "or THIS PIECE for only the detected CSV section. Other roads will not change. " +
                    "For precise boundaries within a longer piece, use SELECT A STRETCH on the map.",
                    RoadLimitEditor.baseName(road.name), nearest.distanceM, connected.size(), meters / 1000.0);
            new AlertDialog.Builder(this)
                    .setTitle("Set current road limit")
                    .setMessage(detail)
                    .setNegativeButton("Cancel", null)
                    .setNeutralButton("This piece", (d, w) ->
                            promptRoadLimit(roads, java.util.Collections.singletonList(road), road.name))
                    .setPositiveButton("Entire road", (d, w) ->
                            promptRoadLimit(roads, connected, road.name))
                    .show();
        } catch (IOException ex) {
            toast("Unable to read road limits: " + ex.getMessage());
        }
    }


    /** A map cannot reliably compete with the main screen's vertical ScrollView for gestures. */
    private void showMapDialog() {
        if (mapDialog != null && mapDialog.isShowing()) return;
        followMapGps = !selectingMapStretch;
        final android.app.Dialog dialog = new android.app.Dialog(
                this, android.R.style.Theme_Material_Light_NoActionBar);
        mapDialog = dialog;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(8), dp(8), dp(8));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("SpeedBeep Map", 22, true);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button close = new Button(this);
        close.setText("CLOSE");
        close.setOnClickListener(v -> dialog.dismiss());
        heading.addView(close);
        content.addView(heading);

        // Map is attached only while visible: no map tile rendering on the speedometer screen.
        content.addView(tripMap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        mapStatusLabel = label("Drag/pinch to explore. Green: saved roads, blue: GPS trail, red: over limit.",
                13, false);
        content.addView(mapStatusLabel);

        LinearLayout controls = new LinearLayout(this);
        Button follow = new Button(this);
        follow.setText("FOLLOW GPS");
        follow.setOnClickListener(v -> {
            double[] fix = recentMapPosition();
            if (fix == null) {
                toast("Waiting for a fresh GPS position");
                return;
            }
            followMapGps = true;
            tripMap.centerOn(fix[0], fix[1]);
            follow.setText("FOLLOWING");
        });
        controls.addView(follow, new LinearLayout.LayoutParams(0, dp(56), 1));

        Button fit = new Button(this);
        fit.setText("FIT ROADS");
        fit.setOnClickListener(v -> {
            followMapGps = false;
            follow.setText("FOLLOW GPS");
            tripMap.fitRoads();
        });
        controls.addView(fit, new LinearLayout.LayoutParams(0, dp(56), 1));

        Button edit = new Button(this);
        edit.setText("EDIT STRETCH");
        edit.setOnClickListener(v -> chooseMapStretch());
        controls.addView(edit, new LinearLayout.LayoutParams(0, dp(56), 1));
        content.addView(controls);

        LinearLayout traceControls = new LinearLayout(this);
        traceButton = new Button(this);
        traceButton.setText("TRACE ROAD");
        traceButton.setOnClickListener(v -> {
            if (manualTracing) stopManualTrace(true);
            else beginManualTrace();
        });
        traceControls.addView(traceButton, new LinearLayout.LayoutParams(0, dp(56), 1));
        traceUndoButton = new Button(this);
        traceUndoButton.setText("UNDO");
        traceUndoButton.setVisibility(View.GONE);
        traceUndoButton.setOnClickListener(v -> undoManualTracePoint());
        traceControls.addView(traceUndoButton, new LinearLayout.LayoutParams(0, dp(56), 1));
        traceFinishButton = new Button(this);
        traceFinishButton.setText("FINISH");
        traceFinishButton.setVisibility(View.GONE);
        traceFinishButton.setOnClickListener(v -> finishManualTrace());
        traceControls.addView(traceFinishButton, new LinearLayout.LayoutParams(0, dp(56), 1));
        content.addView(traceControls);

        tripMap.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                    && followMapGps && !selectingMapStretch) {
                followMapGps = false;
                follow.setText("FOLLOW GPS");
            }
            return false; // osmdroid must continue receiving all touch gestures
        });

        dialog.setOnDismissListener(d -> {
            tripMap.onPause();
            tripMap.clearRoutePreview();
            tripMap.setOnTouchListener(null);
            if (tripMap.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) tripMap.getParent()).removeView(tripMap);
            if (selectingMapStretch) stopMapSelection();
            if (manualTracing) stopManualTrace(true);
            traceButton = null;
            traceUndoButton = null;
            traceFinishButton = null;
            mapStatusLabel = null;
            mapDialog = null;
        });
        dialog.setContentView(content);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT);
        }
        tripMap.onResume();
        double[] fix = recentMapPosition();
        if (fix != null) {
            tripMap.setPosition(fix[0], fix[1]);
            if (followMapGps) tripMap.centerOn(fix[0], fix[1]);
        }
        follow.setText(followMapGps && fix != null ? "FOLLOWING" : "FOLLOW GPS");
        tripMap.reloadTrip();
    }

    private double[] recentMapPosition() {
        if (latestUiLocation != null &&
                Math.abs(System.currentTimeMillis() - latestUiLocation.getTime()) < 15000L) {
            return new double[]{latestUiLocation.getLatitude(), latestUiLocation.getLongitude()};
        }
        SharedPreferences p = getSharedPreferences(SpeedMonitorService.PREFS, MODE_PRIVATE);
        long updated = p.getLong("updatedAt", 0L);
        if (updated > 0 && Math.abs(System.currentTimeMillis() - updated) < 30000L) {
            float lat = p.getFloat("latitude", Float.NaN);
            float lon = p.getFloat("longitude", Float.NaN);
            if (Float.isFinite(lat) && Float.isFinite(lon)) return new double[]{lat, lon};
        }
        return null;
    }

    private void chooseMapStretch() {
        if (!editingAllowed()) return;
        if (manualTracing) stopManualTrace(true);
        if (selectingMapStretch) {
            stopMapSelection();
            toast("Map stretch selection cancelled");
            return;
        }
        selectingMapStretch = true;
        stretchStart = null;
        followMapGps = false;
        showMapDialog();
        if (mapStatusLabel != null) mapStatusLabel.setText("Editing: tap START, then END of the road stretch.");
        tripMap.clearEditPoints();
        tripMap.clearRoutePreview();
        tripMap.setPointTapListener((lat, lon) -> {
            if (!selectingMapStretch) return;
            if (stretchStart == null) {
                stretchStart = new double[]{lat, lon};
                tripMap.markEditPoint(lat, lon, "New limit begins");
                toast("Start marked. Now tap the END of the stretch on the map.");
            } else {
                double[] start = stretchStart;
                stopMapSelection();
                promptMapStretch(start[0], start[1], lat, lon);
            }
        });
        SharedPreferences status = getSharedPreferences(SpeedMonitorService.PREFS, MODE_PRIVATE);
        float lat = status.getFloat("latitude", Float.NaN);
        float lon = status.getFloat("longitude", Float.NaN);
        if (Float.isFinite(lat) && Float.isFinite(lon)) tripMap.centerOn(lat, lon);
        toast("Tap the START and END of the road stretch. Tap the button again to cancel.");
    }

    private void stopMapSelection() {
        selectingMapStretch = false;
        stretchStart = null;
        tripMap.setPointTapListener(null);
        tripMap.clearEditPoints();
        if (mapStatusLabel != null)
            mapStatusLabel.setText("Drag/pinch to explore. Green: saved roads, blue: trail, red: over limit.");
    }

    /** Add intermediate bends on any road. No network request or OSM assumption. */
    private void beginManualTrace() {
        if (!editingAllowed()) return;
        if (selectingMapStretch) stopMapSelection();
        manualTracing = true;
        manualTracePoints.clear();
        followMapGps = false;
        tripMap.clearEditPoints();
        tripMap.clearRoutePreview();
        if (traceButton != null) traceButton.setText("CANCEL TRACE");
        if (traceUndoButton != null) traceUndoButton.setVisibility(View.VISIBLE);
        if (traceFinishButton != null) traceFinishButton.setVisibility(View.VISIBLE);
        if (mapStatusLabel != null) mapStatusLabel.setText(
                "TRACE ROAD: Tap START, each bend (within 150 m), then END. Press FINISH.");
        tripMap.setPointTapListener((lat, lon) -> {
            if (!manualTracing) return;
            List<double[]> candidate = new ArrayList<>(manualTracePoints);
            candidate.add(new double[]{lat, lon});
            if (candidate.size() > 1) {
                try {
                    ManualRoadTrace.build(candidate);
                } catch (IllegalArgumentException ex) {
                    toast(ex.getMessage());
                    return;
                }
            }
            manualTracePoints.add(new double[]{lat, lon});
            redrawManualTrace();
        });
        toast("Tap along the road: start, bends, end. Press FINISH to review. No internet required.");
    }

    private void redrawManualTrace() {
        tripMap.clearEditPoints();
        for (int i = 0; i < manualTracePoints.size(); i++) {
            double[] point = manualTracePoints.get(i);
            tripMap.markEditPoint(point[0], point[1],
                    i == 0 ? "Road start" : "Road point " + (i + 1));
        }
        if (manualTracePoints.size() >= 2) {
            ManualRoadTrace.Result route = ManualRoadTrace.build(manualTracePoints);
            tripMap.showRoutePreview(route.pieces);
            if (mapStatusLabel != null) mapStatusLabel.setText(String.format(Locale.US,
                    "Orange manual trace: %d points, %.0f m. Tap next bend or press FINISH.",
                    manualTracePoints.size(), route.lengthM));
        } else {
            tripMap.clearRoutePreview();
            if (mapStatusLabel != null) mapStatusLabel.setText(
                    "Road start marked. Tap every bend and the END (150 m maximum between taps).");
        }
        if (traceUndoButton != null)
            traceUndoButton.setEnabled(!manualTracePoints.isEmpty());
        if (traceFinishButton != null)
            traceFinishButton.setEnabled(manualTracePoints.size() >= 2);
    }

    private void undoManualTracePoint() {
        if (!manualTracing || manualTracePoints.isEmpty()) return;
        manualTracePoints.remove(manualTracePoints.size() - 1);
        redrawManualTrace();
    }

    private void stopManualTrace(boolean removePreview) {
        manualTracing = false;
        manualTracePoints.clear();
        tripMap.setPointTapListener(null);
        tripMap.clearEditPoints();
        if (removePreview) tripMap.clearRoutePreview();
        if (traceButton != null) traceButton.setText("TRACE ROAD");
        if (traceUndoButton != null) traceUndoButton.setVisibility(View.GONE);
        if (traceFinishButton != null) traceFinishButton.setVisibility(View.GONE);
        if (mapStatusLabel != null && removePreview) mapStatusLabel.setText(
                "Manual tracing cancelled. No road changes saved.");
    }

    private void finishManualTrace() {
        if (!manualTracing) return;
        final ManualRoadTrace.Result trace;
        try {
            trace = ManualRoadTrace.build(new ArrayList<>(manualTracePoints));
        } catch (IllegalArgumentException ex) {
            toast(ex.getMessage());
            return;
        }
        stopManualTrace(false);
        if (mapStatusLabel != null) mapStatusLabel.setText(String.format(Locale.US,
                "Orange manual trace (NOT automatic OSM snap): %.0f m. Verify path before saving.",
                trace.lengthM));
        editExecutor.execute(() -> {
            try {
                List<RoadSegment> existing = LocalRoadStore.load(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() ||
                            mapDialog == null || !mapDialog.isShowing()) return;
                    promptRoadLimit(existing, trace.pieces,
                            "Manual road trace", true, true, true);
                });
            } catch (IOException ex) {
                runOnUiThread(() -> {
                    if (mapStatusLabel != null)
                        mapStatusLabel.setText("Cannot load local road database: " + ex.getMessage());
                    toast("Could not load road database: " + ex.getMessage());
                });
            }
        });
    }

    private void promptMapStretch(double startLat, double startLon, double endLat, double endLon) {
        // Loading and connected-graph search can be expensive; NEVER block map gestures.
        if (mapStatusLabel != null)
            mapStatusLabel.setText("Finding connected road geometry…");
        final java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final android.app.AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Snapping road stretch")
                .setMessage("Following the mapped road between your two taps…")
                .setNegativeButton("Cancel", (d, w) -> {
                    cancelled.set(true);
                    if (mapStatusLabel != null)
                        mapStatusLabel.setText("Road selection cancelled; no changes saved.");
                })
                .create();
        progress.show();
        editExecutor.execute(() -> {
            MapStretchRouter.Route candidate = null;
            List<RoadSegment> original = null;
            String error = null;
            try {
                original = LocalRoadStore.load(this);
                try {
                    candidate = MapStretchRouter.route(original,
                            startLat, startLon, endLat, endLon, false);
                } catch (MapStretchRouter.RouteNotFoundException missing) {
                    // The local speed-limit CSV does not contain every street.
                    // Do not block editing on a third-party Overpass server.
                    // Offer immediate offline multi-point tracing instead.
                    error = "No connected local road geometry for those taps. " +
                            "Choose TRACE ROAD to add bends manually without internet.";
                }
            } catch (Exception ex) {
                error = ex.getMessage() == null ? "No connected road geometry found" : ex.getMessage();
            }
            final MapStretchRouter.Route route = candidate;
            final List<RoadSegment> snapshot = original;
            final String problem = error;
            runOnUiThread(() -> {
                progress.dismiss();
                if (isFinishing() || isDestroyed() || cancelled.get()) return;
                if (problem != null || route == null || snapshot == null) {
                    String message = problem == null ? "Could not snap road stretch" : problem;
                    if (mapStatusLabel != null) mapStatusLabel.setText("Not saved: " + message);
                    new AlertDialog.Builder(this)
                            .setTitle("No local route found")
                            .setMessage(message + "\n\nTRACE ROAD lets you mark the start, bends and end. " +
                                    "It does not depend on OpenStreetMap connectivity.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("TRACE ROAD", (d, w) -> beginManualTrace())
                            .show();
                    return;
                }
                tripMap.showRoutePreview(route.parts);
                if (mapStatusLabel != null)
                    mapStatusLabel.setText(String.format(Locale.US,
                            "Orange preview: %s, %.0f m over %d pieces. Confirm before saving.",
                            route.name, route.lengthM, route.parts.size()));
                promptRoadLimit(snapshot, route.parts, route.name, true, route.fromOsm);
            });
        });
    }

    private void promptRoadLimit(List<RoadSegment> roads, List<RoadSegment> selected, String suggestedName) {
        promptRoadLimit(roads, selected, suggestedName, false, false, false);
    }

    private void promptRoadLimit(List<RoadSegment> roads, List<RoadSegment> selected,
                                 String suggestedName, boolean isMapPreview, boolean fromOsm) {
        promptRoadLimit(roads, selected, suggestedName, isMapPreview, fromOsm, false);
    }

    private void promptRoadLimit(List<RoadSegment> roads, List<RoadSegment> selected,
                                 String suggestedName, boolean isMapPreview,
                                 boolean fromOsm, boolean tracedManually) {
        if (!editingAllowed()) return;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText name = new EditText(this);
        name.setHint("Road name");
        name.setSingleLine(true);
        name.setText(tracedManually ? "" : RoadLimitEditor.baseName(suggestedName));
        if (tracedManually) name.setHint("Enter verified street name");
        layout.addView(name);
        EditText limit = new EditText(this);
        limit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        limit.setHint("New limit, km/h");
        // OSM way geometry is not evidence of a legal speed limit.
        if (!fromOsm && !tracedManually)
            limit.setText(String.valueOf((int) selected.get(0).limitKph));
        layout.addView(limit);
        double total = 0;
        for (RoadSegment section : selected) total += RoadLimitEditor.lengthM(section);
        TextView note = label(String.format(Locale.US,
                (tracedManually ?
                    "MANUALLY TRACED — not snapped to map data. Check every orange " +
                    "section follows the actual road before saving. " :
                    "Snapped to connected road geometry. ") +
                "Edit %d pieces (approximately %.0f metres). " +
                "Nearby roads could be affected by GPS matching; check alignment. " +
                "Only enter a verified posted speed limit.",
                selected.size(), total), 14, false);
        layout.addView(note);
        new AlertDialog.Builder(this)
                .setTitle("Override road speed limit")
                .setView(layout)
                .setNegativeButton("Cancel", (d, w) -> {
                    if (isMapPreview) tripMap.clearRoutePreview();
                })
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        double value = Double.parseDouble(limit.getText().toString().trim());
                        List<RoadSegment> result = RoadLimitEditor.override(
                                roads, selected, value, name.getText().toString().trim());
                        if (saveEditedRoads(result) && isMapPreview) {
                            tripMap.clearRoutePreview();
                            if (mapStatusLabel != null)
                                mapStatusLabel.setText("Snapped road limit saved. Tap EDIT STRETCH for another section.");
                        }
                    } catch (IllegalArgumentException ex) {
                        toast("Invalid speed limit or road: " + ex.getMessage());
                    }
                }).show();
    }

    private void updateAlarmButton() {
        String mode = AlarmSoundPlayer.getSelectedMode(this);
        String name = AlarmSoundPlayer.labels()[AlarmSoundPlayer.selectedIndex(mode)];
        alarmButton.setText("ALARM SOUND: " + name.toUpperCase(Locale.US));
    }

    private String checkedAlarmMode(RadioGroup group, int[] radioIds) {
        int checked = group.getCheckedRadioButtonId();
        String[] modes = AlarmSoundPlayer.modes();
        for (int i = 0; i < radioIds.length; i++) {
            if (radioIds[i] == checked) return modes[i];
        }
        return AlarmSoundPlayer.BEEP;
    }

    private void showAlarmSettings() {
        SharedPreferences settings = getSharedPreferences(SpeedMonitorService.SETTINGS, MODE_PRIVATE);
        int selected = AlarmSoundPlayer.selectedIndex(AlarmSoundPlayer.getSelectedMode(this));
        String[] labels = AlarmSoundPlayer.labels();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), dp(8));

        RadioGroup choices = new RadioGroup(this);
        choices.setOrientation(RadioGroup.VERTICAL);
        int[] radioIds = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            RadioButton radio = new RadioButton(this);
            radio.setId(View.generateViewId());
            radio.setText(labels[i]);
            radio.setPadding(0, dp(5), 0, dp(5));
            radioIds[i] = radio.getId();
            choices.addView(radio);
        }
        choices.check(radioIds[selected]);
        layout.addView(choices);

        TextView note = label("Voice announces the matched road's configured limit, "
                + "not your current speed. On an unmapped road with user fallback enabled, "
                + "it says 'User set limit XX'. Changes are saved on this phone. "
                + "Only configure or preview sounds while parked.", 13, false);
        note.setPadding(0, dp(12), 0, 0);
        layout.addView(note);

        AlarmSoundPlayer previewPlayer = new AlarmSoundPlayer(this);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Overspeed alarm")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Preview", null)
                .setPositiveButton("Save", (d, which) -> {
                    String chosenMode = checkedAlarmMode(choices, radioIds);
                    settings.edit().putString(AlarmSoundPlayer.KEY_SOUND, chosenMode).apply();
                    updateAlarmButton();
                    toast("Alarm sound saved");
                })
                .create();
        dialog.setOnDismissListener(d -> previewPlayer.close());
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> previewPlayer.preview(checkedAlarmMode(choices, radioIds))));
        dialog.show();
    }

    private void showFallbackSettings() {
        if (!editingAllowed()) return;
        SharedPreferences p = getSharedPreferences(SpeedMonitorService.SETTINGS, MODE_PRIVATE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), dp(8));
        CheckBox enabled = new CheckBox(this);
        enabled.setText("Warn on roads without a configured limit");
        enabled.setChecked(p.getBoolean("fallback_enabled", false));
        layout.addView(enabled);
        EditText limit = new EditText(this);
        limit.setInputType(2);
        limit.setHint("Default km/h");
        limit.setText(String.valueOf((int)p.getFloat("fallback_kph",40)));
        layout.addView(limit);
        EditText margin = new EditText(this);
        margin.setInputType(2);
        margin.setHint("Warning margin km/h");
        margin.setText(String.valueOf((int)p.getFloat("fallback_margin",3)));
        layout.addView(margin);
        new AlertDialog.Builder(this).setTitle("User-defined fallback (not a legal speed limit)")
                .setView(layout).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{
                    try {
                        float v=Float.parseFloat(limit.getText().toString());
                        float m=Float.parseFloat(margin.getText().toString());
                        if(v<5||v>200||m<0||m>50) throw new NumberFormatException();
                        p.edit().putBoolean("fallback_enabled",enabled.isChecked())
                                .putFloat("fallback_kph",v).putFloat("fallback_margin",m).apply();
                        toast("Fallback saved");
                    } catch(NumberFormatException ex) { toast("Use limit 5–200 and margin 0–50"); }
                }).show();
    }

    private void chooseRoadToEdit() {
        if (!editingAllowed()) return;
        try {
            List<RoadSegment> roads=LocalRoadStore.load(this);
            String[] labels=new String[roads.size()];
            for(int i=0;i<roads.size();i++) labels[i]=roads.get(i).name+" — "+(int)roads.get(i).limitKph+" km/h";
            new AlertDialog.Builder(this).setTitle("Select segment to edit")
                    .setItems(labels,(d,index)->editRoad(roads,index)).setNegativeButton("Cancel",null).show();
        } catch(IOException ex) { toast(ex.getMessage()); }
    }

    private void editRoad(List<RoadSegment> roads,int index) {
        RoadSegment original=roads.get(index);
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20),dp(8),dp(20),dp(8));
        EditText name=new EditText(this);name.setText(original.name);name.setHint("Road name");layout.addView(name);
        EditText limit=new EditText(this);limit.setInputType(2);
        limit.setText(String.valueOf((int)original.limitKph));limit.setHint("Limit km/h");layout.addView(limit);
        new AlertDialog.Builder(this).setTitle("Edit segment")
                .setMessage("Changes to a single segment. Geometry remains unchanged.")
                .setView(layout).setNegativeButton("Cancel",null)
                .setNeutralButton("Delete",(d,w)->{
                    if(roads.size()<=1){toast("Cannot delete last road");return;}
                    roads.remove(index);saveEditedRoads(roads);
                }).setPositiveButton("Save",(d,w)->{
                    try {
                        double v=Double.parseDouble(limit.getText().toString());
                        if(v<5||v>200||name.getText().toString().trim().isEmpty()) throw new NumberFormatException();
                        roads.set(index,new RoadSegment(name.getText().toString().trim(),
                                original.startLat,original.startLon,original.endLat,original.endLon,
                                v,original.corridorM,original.marginKph));
                        saveEditedRoads(roads);
                    }catch(NumberFormatException ex){toast("Enter a name and limit 5–200");}
                }).show();
    }

    private boolean saveEditedRoads(List<RoadSegment> roads) {
        try {
            LocalRoadStore.save(this, roads);
            refreshCsvInfo();
            if (getSharedPreferences(SpeedMonitorService.PREFS, MODE_PRIVATE).getBoolean("running", false)) {
                startService(new Intent(this, SpeedMonitorService.class).setAction(SpeedMonitorService.ACTION_RELOAD));
            }
            toast("Road changes saved");
            return true;
        }
        catch(IOException ex) { toast("Save failed: "+ex.getMessage()); return false; }
    }

    private void chooseCsv() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    private void exportCsv() {
        if (quickSaving) {
            toast("Finish saving the speed limit before exporting.");
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "speed_limits.csv");
        startActivityForResult(i, REQ_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Could not open selected file");
                int count = LocalRoadStore.importCsv(this, in);
                toast("Imported " + count + " road segments");
                refreshCsvInfo();
                if (getSharedPreferences(SpeedMonitorService.PREFS, MODE_PRIVATE).getBoolean("running", false)) {
                    startService(new Intent(this, SpeedMonitorService.class).setAction(SpeedMonitorService.ACTION_RELOAD));
                }
            } catch (Exception ex) {
                toast("Import failed: " + ex.getMessage());
            }
        } else if (requestCode == REQ_LOG_EXPORT) {
            try (InputStream in = new java.io.FileInputStream(DriveLog.file(this));
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Could not open export destination");
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                toast("Drive diagnostics exported");
            } catch (Exception ex) { toast("Export failed: " + ex.getMessage()); }
        } else if (requestCode == REQ_EXPORT) {
            try {
                LocalRoadStore.ensureSeedFile(this);
                try (InputStream in = new java.io.FileInputStream(LocalRoadStore.file(this)); OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("Could not create output file");
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                    out.flush();
                }
                toast("CSV exported");
            } catch (Exception ex) {
                toast("Export failed: " + ex.getMessage());
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            toast("Permission is required for speed monitoring");
            return;
        }
        if (requestCode == REQ_LOCATION) {
            beginQuickGps();
            updateQuickRoadStatus();
            // Restore the existing one-tap START flow after permission is granted.
            boolean shouldStart = startAfterLocationPermission;
            startAfterLocationPermission = false;
            if (shouldStart) startMonitoring();
        } else if (requestCode == REQ_NOTIFICATIONS) startMonitoring();
    }

    private void refreshCsvInfo() {
        try {
            List<RoadSegment> roads = LocalRoadStore.load(this);
            cachedRoads = roads;
            quickRoadSeed = null;
            quickRoadParts = java.util.Collections.emptyList();
            evaluatedFixTime = -1L;
            tripMap.setRoads(roads);
            updateQuickRoadStatus();
            csvText.setText("CSV: " + roads.size() + " road segment" + (roads.size() == 1 ? "" : "s") + " loaded");
        } catch (IOException ex) {
            csvText.setText("CSV ERROR: " + ex.getMessage());
        }
    }

    private void refreshStatus() {
        SharedPreferences p = getSharedPreferences(SpeedMonitorService.PREFS, Context.MODE_PRIVATE);
        boolean running = p.getBoolean("running", false);
        float speed = p.getFloat("speedKph", 0f);
        String road = p.getString("roadName", "");
        float limit = p.getFloat("limitKph", 0f);
        float margin = p.getFloat("marginKph", 0f);
        float distance = p.getFloat("distanceM", 0f);
        float accuracy = p.getFloat("accuracyM", 0f);
        String note = p.getString("note", running ? "Monitoring" : "Stopped");
        long lastUpdate = p.getLong("updatedAt", 0L);
        if (running && lastUpdate > 0 && System.currentTimeMillis() - lastUpdate > 15000L) {
            note = "WARNING: no GPS updates for over 15 seconds";
        }

        if (mapDialog != null && mapDialog.isShowing()) {
            double[] fix = recentMapPosition();
            if (fix != null) {
                tripMap.setPosition(fix[0], fix[1]);
                if (followMapGps) tripMap.followPosition(fix[0], fix[1]);
            }
            if (System.currentTimeMillis() - lastMapReload > 5000) {
                lastMapReload = System.currentTimeMillis();
                tripMap.reloadTrip();
            }
        }
        bigSpeed.setText(running ? String.format(Locale.US, "%.0f", speed) : "—");
        roadText.setText(road == null || road.isEmpty() ? "Road: —" : "Road: " + road);
        limitText.setText(limit > 0 ? String.format(Locale.US, "Limit: %.0f km/h  (+%.0f)", limit, margin) : "Limit: —");
        if (running) {
            String gps = accuracy > 0 ? String.format(Locale.US, "GPS ±%.0f m", accuracy) : "GPS";
            String match = road != null && !road.isEmpty() ? String.format(Locale.US, " • road distance %.0f m", distance) : "";
            detailText.setText(note + " • " + gps + match);
        } else {
            detailText.setText(note == null || note.isEmpty() ? "Stopped" : note);
        }
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        updateQuickRoadStatus();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override protected void onResume() {
        super.onResume();
        if (mapDialog != null && mapDialog.isShowing()) tripMap.onResume();
        beginQuickGps();
        handler.post(refreshRunnable);
    }
    @Override protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        endQuickGps();
        if (mapDialog != null && mapDialog.isShowing()) tripMap.onPause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (mapDialog != null) mapDialog.dismiss();
        tripMap.dispose();
        tripMap.onDetach();
        editExecutor.shutdown();
        super.onDestroy();
    }
}
