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
        root.addView(tripMap, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(380)));
        Button fitMap = new Button(this);
        fitMap.setText("FIT ALL CONFIGURED ROADS");
        fitMap.setOnClickListener(v -> tripMap.fitRoads());
        root.addView(fitMap);
        TextView mapHelp = label("OpenStreetMap: pinch to zoom, drag to pan. To override part of a long segment, choose SELECT A STRETCH and tap its start and end. Tiles need internet initially.", 13, false);
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
        pickStretchButton.setText("SELECT A STRETCH ON MAP (SPLIT EXISTING LIMIT)");
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
        final float accuracyM, speedKph;
        final long timestamp;
        CurrentFix(double lat, double lon, float accuracyM, float speedKph, long timestamp) {
            this.lat = lat; this.lon = lon; this.accuracyM = accuracyM;
            this.speedKph = speedKph; this.timestamp = timestamp;
        }
    }

    private CurrentFix currentFix() {
        CurrentFix fix = null;
        if (latestUiLocation != null) {
            long time = latestUiLocation.getTime();
            if (time > 0 && time <= System.currentTimeMillis() + 3000L) {
                fix = new CurrentFix(latestUiLocation.getLatitude(), latestUiLocation.getLongitude(),
                        latestUiLocation.hasAccuracy() ? latestUiLocation.getAccuracy() : 999f,
                        latestUiLocation.hasSpeed() ? latestUiLocation.getSpeed() * 3.6f : 0f, time);
            }
        }
        SharedPreferences p = getSharedPreferences(SpeedMonitorService.PREFS, MODE_PRIVATE);
        long serviceTime = p.getLong("lastFixAt", 0);
        if (serviceTime > 0 && (fix == null || serviceTime > fix.timestamp)) {
            fix = new CurrentFix(p.getFloat("latitude", Float.NaN),
                    p.getFloat("longitude", Float.NaN),
                    p.getFloat("lastFixAccuracyM", 999f), p.getFloat("speedKph", 0f), serviceTime);
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
            GeoMatcher.Match match = GeoMatcher.findBest(fix.lat, fix.lon, fix.accuracyM, cachedRoads);
            RoadSegment candidate = match == null ? null : match.road;
            if (candidate != quickRoadSeed) {
                quickRoadSeed = candidate;
                quickRoadParts = candidate == null
                        ? java.util.Collections.emptyList()
                        : RoadLimitEditor.connectedRoad(cachedRoads, candidate);
            }
        }
        if (quickRoadSeed == null) {
            quickRoadHint.setText("No configured road here. Select a stretch on the map to add one.");
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
        // Match again against the latest CSV. Never apply a cached candidate
        // after an import or another road edit.
        quickSaving = true;
        quickSetButton.setEnabled(false);
        quickSavedHint.setText("Saving road limit…");
        final double lat = fix.lat, lon = fix.lon;
        final float accuracy = fix.accuracyM;
        editExecutor.execute(() -> {
            try {
                List<RoadSegment> currentRoads = LocalRoadStore.load(this);
                GeoMatcher.Match match = GeoMatcher.findBest(lat, lon, accuracy, currentRoads);
                if (match == null) throw new IOException("No mapped road at this GPS position. Select its stretch on the map.");
                List<RoadSegment> parts = RoadLimitEditor.connectedRoad(currentRoads, match.road);
                if (parts.isEmpty()) throw new IOException("No road section to change.");
                String name = RoadLimitEditor.baseName(match.road.name);
                List<RoadSegment> newRoads = RoadLimitEditor.override(
                        currentRoads, parts, newLimit, name);
                LocalRoadStore.save(this, newRoads);
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
                    String saved = String.format(Locale.US,
                            "Saved %.0f km/h on %s (%d pieces).",
                            newLimit, name, parts.size());
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
        SharedPreferences status = getSharedPreferences(SpeedMonitorService.PREFS, MODE_PRIVATE);
        long fixAge = System.currentTimeMillis() - status.getLong("lastFixAt", 0L);
        float accuracy = status.getFloat("lastFixAccuracyM", 999f);
        if (fixAge < 0 || fixAge > 10 * 60 * 1000L || accuracy > 35f) {
            toast("No recent accurate GPS fix. Start monitoring while parked, let GPS settle, then try again.");
            return;
        }
        double latitude = status.getFloat("latitude", Float.NaN);
        double longitude = status.getFloat("longitude", Float.NaN);
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

    private void chooseMapStretch() {
        if (!editingAllowed()) return;
        if (selectingMapStretch) {
            stopMapSelection();
            toast("Map stretch selection cancelled");
            return;
        }
        selectingMapStretch = true;
        stretchStart = null;
        tripMap.clearEditPoints();
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
    }

    private void promptMapStretch(double startLat, double startLon, double endLat, double endLon) {
        try {
            List<RoadSegment> roads = LocalRoadStore.load(this);
            GeoMatcher.Match a = GeoMatcher.findBest(startLat, startLon, 0, roads);
            GeoMatcher.Match b = GeoMatcher.findBest(endLat, endLon, 0, roads);
            RoadSegment piece;
            if (a != null && b != null && a.road == b.road &&
                    a.distanceM <= 20 && b.distanceM <= 20) {
                // Precise snapping permits cutting an existing long segment in THREE parts.
                piece = RoadLimitEditor.betweenTaps(a.road, startLat, startLon, endLat, endLon);
            } else if (a == null && b == null) {
                // No geometry in the offline CSV: the user can define a new straight road.
                piece = new RoadSegment("New unmapped stretch", startLat, startLon, endLat, endLon,
                        40, 20, 3);
                if (RoadLimitEditor.lengthM(piece) < 2) {
                    toast("Choose two different points"); return;
                }
            } else {
                toast("Choose both endpoints on the SAME configured straight segment. " +
                        "For a curved road, edit its connected road or repeat with shorter stretches.");
                return;
            }
            promptRoadLimit(roads, java.util.Collections.singletonList(piece), piece.name);
        } catch (Exception ex) {
            toast("Could not select stretch: " + ex.getMessage());
        }
    }

    private void promptRoadLimit(List<RoadSegment> roads, List<RoadSegment> selected, String suggestedName) {
        if (!editingAllowed()) return;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText name = new EditText(this);
        name.setHint("Road name");
        name.setSingleLine(true);
        name.setText(RoadLimitEditor.baseName(suggestedName));
        layout.addView(name);
        EditText limit = new EditText(this);
        limit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        limit.setHint("New limit, km/h");
        limit.setText(String.valueOf((int) selected.get(0).limitKph));
        layout.addView(limit);
        double total = 0;
        for (RoadSegment section : selected) total += RoadLimitEditor.lengthM(section);
        TextView note = label(String.format(Locale.US,
                "Overwrite limit on %d selected piece(s), approximately %.0f metres. " +
                "Where it overlaps an older longer segment, the unaffected sections keep their limits. " +
                "Please use verified posted limits, not estimates.",
                selected.size(), total), 14, false);
        layout.addView(note);
        new AlertDialog.Builder(this)
                .setTitle("Override road speed limit")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        double value = Double.parseDouble(limit.getText().toString().trim());
                        List<RoadSegment> result = RoadLimitEditor.override(
                                roads, selected, value, name.getText().toString().trim());
                        saveEditedRoads(result);
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

    private void saveEditedRoads(List<RoadSegment> roads) {
        try {
            LocalRoadStore.save(this, roads);
            refreshCsvInfo();
            if (getSharedPreferences(SpeedMonitorService.PREFS, MODE_PRIVATE).getBoolean("running", false)) {
                startService(new Intent(this, SpeedMonitorService.class).setAction(SpeedMonitorService.ACTION_RELOAD));
            }
            toast("Road changes saved");
        }
        catch(IOException ex) { toast("Save failed: "+ex.getMessage()); }
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

        float latitude = p.getFloat("latitude", Float.NaN);
        float longitude = p.getFloat("longitude", Float.NaN);
        if (!Float.isNaN(latitude) && !Float.isNaN(longitude)) tripMap.setPosition(latitude, longitude);
        if (System.currentTimeMillis() - lastMapReload > 5000) {
            lastMapReload = System.currentTimeMillis();
            tripMap.reloadTrip();
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
        tripMap.onResume();
        beginQuickGps();
        handler.post(refreshRunnable);
    }
    @Override protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        endQuickGps();
        tripMap.onPause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        editExecutor.shutdown();
        super.onDestroy();
    }
}
