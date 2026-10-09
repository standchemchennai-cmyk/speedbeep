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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

        tripMap = new OSMTripMap(this);
        root.addView(tripMap, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(380)));
        Button fitMap = new Button(this);
        fitMap.setText("FIT ALL CONFIGURED ROADS");
        fitMap.setOnClickListener(v -> tripMap.fitRoads());
        root.addView(fitMap);
        TextView mapHelp = label("OpenStreetMap: pinch to zoom and drag to pan. Tiles need internet on first view; cached tiles may work offline. Review while parked.", 13, false);
        root.addView(mapHelp);

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

        Button fallbackButton = new Button(this);
        fallbackButton.setText("UNMAPPED ROAD SPEED SETTINGS");
        fallbackButton.setOnClickListener(v -> showFallbackSettings());
        root.addView(fallbackButton);

        Button editButton = new Button(this);
        editButton.setText("EDIT CONFIGURED ROAD LIMITS");
        editButton.setOnClickListener(v -> chooseRoadToEdit());
        root.addView(editButton);

        csvText = label("CSV: checking…", 14, false);
        csvText.setPadding(0, dp(22), 0, dp(8));
        root.addView(csvText);

        Button importButton = new Button(this);
        importButton.setText("IMPORT SPEED_LIMITS.CSV");
        importButton.setOnClickListener(v -> chooseCsv());
        root.addView(importButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        Button exportButton = new Button(this);
        exportButton.setText("EXPORT CURRENT CSV");
        exportButton.setOnClickListener(v -> exportCsv());
        root.addView(exportButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

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

    private boolean editingAllowed() {
        if (getSharedPreferences(SpeedMonitorService.PREFS, MODE_PRIVATE).getBoolean("running", false)) {
            toast("Stop monitoring and park before editing roads");
            return false;
        }
        return true;
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
        try { LocalRoadStore.save(this,roads);refreshCsvInfo();toast("Road changes saved"); }
        catch(IOException ex) { toast("Save failed: "+ex.getMessage()); }
    }

    private void chooseCsv() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    private void exportCsv() {
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
        if (requestCode == REQ_LOCATION || requestCode == REQ_NOTIFICATIONS) startMonitoring();
    }

    private void refreshCsvInfo() {
        try {
            List<RoadSegment> roads = LocalRoadStore.load(this);
            tripMap.setRoads(roads);
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
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override protected void onResume() { super.onResume(); tripMap.onResume(); handler.post(refreshRunnable); }
    @Override protected void onPause() { handler.removeCallbacks(refreshRunnable); tripMap.onPause(); super.onPause(); }
}
