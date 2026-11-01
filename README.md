# SpeedBeep

A tiny local-first Android speed-limit warning app.

- No paid API
- No account
- No cloud database
- No Google Maps dependency
- GPS speed from the phone
- Road limits from a local CSV
- Foreground location service so it can keep working with the screen off
- Selectable overspeed alarms: double-beep, synthesized siren, or spoken road speed limit; warns after 2 seconds over `limit + margin` and repeats every 4 seconds until you slow down

## V1 behavior

1. Tap **Start** when you get into the car.
2. SpeedBeep reads GPS speed once per second.
3. It matches your location to the nearest configured straight road segment.
4. If GPS accuracy is worse than 60 m, it does not issue a speed warning.
5. If there is no configured road segment nearby, it does not issue a warning.
6. If your speed remains over `limit_kph + margin_kph` for 2 seconds, it plays your chosen overspeed alarm.

This is deliberately a driver aid, not an authoritative source of legal speed limits. The CSV is the source of truth, so only enter limits you have verified.

## Alarm sound settings

In the app, tap **Alarm Sound**, choose **Beep beep**, **Siren**, or **Voice: Speed limit XX**, then tap **Save**. Use **Preview** to test a sample 60 km/h announcement or sound **while parked**. Your selection is remembered locally, and changes also apply to an already-running monitoring service.

- **Beep beep** is the original two-tone alert (default for existing installs).
- **Siren** is a locally synthesized rising/falling tone. No sound files are needed.
- **Voice** uses Android's built-in text-to-speech engine. It announces the matched CSV road's configured limit, such as "Speed limit 60" (not your current GPS speed).
- If you enable a fallback limit for unmapped roads, the voice says "User set limit 40" rather than representing the fallback as a verified legal limit.
- If the system text-to-speech engine/English voice is not installed or ready, audible alerts fall back to double-beep. No online speech API is used.

As before, all overspeed warnings require an adequately accurate GPS fix and the existing 2-second hold. They repeat at 4-second intervals until you slow down.

## Fast main-screen speed-limit editing and export

Enter the new speed in the **km/h** box and tap **SET THIS ROAD**.
There are no intermediate dialogs and no parked-vehicle speed lock. The road
name, current speed limit, number of connected pieces and approximate mapped
length appear next to the control before you change anything.

**Existing mapped-road edits are offline:** If the road is already in the local
CSV, SpeedBeep uses the recent GPS fix to select a plausible saved segment,
including normal 2–10 m (and up to 20 m depending on GPS accuracy) mapping
offsets. It no longer makes an Overpass request simply because the saved
segment is over 4 m from the fix. If two different roads or speed zones are
equally plausible, it declines to auto-edit and asks you to select a precise
map stretch or move away from the junction. This same safe target is displayed
on the main screen before tapping the save button.

SpeedBeep gets a foreground GPS fix as soon as the main screen is open and
precise location is permitted; there is no need to start the speed-monitoring
service just to edit a limit. The app accepts speed-limit edits while moving
and reloads the modified database without interrupting speed monitoring.
It requires a recent GPS fix (within 10 seconds, accuracy of 30 metres or better),
so an old position cannot overwrite a different road.

**Automatic road creation when no speed limit is mapped:** Tap
**SET THIS ROAD** as usual. If no near-exact matching segment is in the local
CSV, the app first requests nearby drivable road geometry from an OpenStreetMap
Overpass API server. It uses GPS and (when available) movement direction
to detect the current road, loads connected ways of the same street name within
approximately 2.2 km, and saves the user-entered limit along the detected geometry.
It can split overlapping old speed-limit segments without affecting the flanks.

**Offline fallback when Overpass is unavailable or no OSM road was found:**
The same button now saves a **GPS-only local section** if GPS accuracy is
10 metres or better. With a reliable moving bearing, it stores only an
approximately 50-metre straight section. Otherwise it stores a very small
GPS spot. Its narrow matching corridor is about 6–10 metres, plus the
existing GPS-accuracy matching allowance. This does **not** create a
complete road or verify its legal speed limit. The app explicitly reports
that limited coverage; use **SELECT A STRETCH ON MAP** to mark a longer
road manually. All local edits are saved to the phone's CSV and remain
available offline. If GPS is too inaccurate, the app asks for a better fix.

For an OSM lookup, your current coordinates are sent to the public
Overpass service, never the local CSV. For a street without a name or
reference, only the matched OSM way is added. The 2.2-km discovery radius
does not guarantee the entire legal speed-limit zone. If OSM reports
ambiguous nearby roads (junctions or parallel roads), **no GPS fallback is
saved automatically**: try again away from the junction or manually
select the precise stretch on the map. Public Overpass servers may be
unavailable or overloaded; that no longer blocks a small GPS-only save.
A passenger should make edits while the vehicle is moving; the driver
must keep attention on the road.

**SET THIS ROAD** changes every *connected, same-named stretch present in the
local CSV* and automatically overwrites its existing speed limit. It does not
change unrelated roads with the same name. The more detailed options remain
available further down the screen:

- **ADVANCED: ENTIRE ROAD OR ONE SEGMENT** offers a choice of the full connected
  road or only the straight segment matched by GPS.
- **SELECT A STRETCH ON MAP (SPLIT EXISTING LIMIT)** lets you tap the start
  and end *within the same configured straight segment*. The app snaps both
  taps onto that segment, assigns your new speed to the selected middle part,
  and preserves the old speed on the unaffected portions before and after it.
- If both selected map endpoints are **unmapped**, the app can create a new
  straight-line limit segment. For bends, draw several short sections. If the
  endpoints belong to different mapped pieces, use the entire-road operation
  or mark each smaller section separately.

The prominent **EXPORT SPEED DATABASE** button uses Android's Save As
file picker to export the complete, currently edited `speed_limits.csv`.
Save it to a location of your choice for backup, spreadsheet editing or import
on another device. It exports the complete database, not only today's edits.

The editor cuts overlapping collinear segments, rather than wiping a longer
road's speed limit. It avoids clipping perpendicular intersections or separate
parallel carriageways. Manual changes appear as `Custom: <road>` in the
local CSV and take priority over the older OSM limits on the modified stretch.

Edits are saved **on this phone** to the app's private `speed_limits.csv`. The
monitoring service reloads them immediately if it is running. Export the
current CSV to back up your work or transfer it to a different device. Changes
do not automatically sync back to GitHub or other phones.

**Scope limitation:** the offline CSV only has geometry for configured speed
segments. The app cannot automatically infer the whole length of a completely
unmapped street from map tiles alone. Roads without speed geometry must be
drawn manually or imported first. Always verify posted/legal limits and the
selected road before saving.

## CSV format

```csv
name,start_lat,start_lon,end_lat,end_lon,limit_kph,corridor_m,margin_kph
OMR segment 1,12.900000,80.220000,12.905000,80.225000,60,40,3
```

Columns:

- `name`: label shown in the app
- `start_lat`, `start_lon`: beginning of the straight road segment
- `end_lat`, `end_lon`: end of the straight road segment
- `limit_kph`: legal/configured speed limit
- `corridor_m`: how far from the line your GPS point can be and still match it; start around 30–50 m
- `margin_kph`: warning tolerance; e.g. limit 60 + margin 3 means warning above 63 km/h

For curved roads, use multiple short rows with the same road name.

## Loading the CSV

The app ships with a one-row sample. Use **Export Current CSV**, edit it in any spreadsheet/text editor, save as CSV, then use **Import speed_limits.csv**.

The imported file is copied into the app's private local storage. After import, the original file can be deleted; the app does not need internet access.

## Build

Requirements:

- Android Studio compatible with Android Gradle Plugin 9.4
- JDK 17+
- Android SDK 36 / Build Tools 36

Open the `SpeedBeep` folder in Android Studio and let Gradle sync. Then **Build > Build APK(s)**.

This source bundle intentionally does not include the Gradle wrapper JAR (a third-party binary). Android Studio can use/install Gradle 9.6 during sync, or you can generate the wrapper locally with `gradle wrapper --gradle-version 9.6.0`.

Project build configuration uses Android Gradle Plugin 9.4.0 and Java 17. There are no third-party runtime libraries.

## Android permissions

- Fine location: needed to obtain GPS speed and position.
- Foreground service + foreground service location: lets monitoring continue after the app goes into the background.
- Notifications: used for the persistent “SpeedBeep is monitoring” notification on modern Android.

V1 does **not** request always-on background location. The foreground service is started while the app is visible, then keeps running until you press Stop or use Stop from the notification.

## Known limitations

- Straight-line matching is intentionally simple. Parallel roads/service lanes can be ambiguous.
- GPS can be inaccurate in flyovers, tunnels, dense urban areas, and under heavy tree/building cover.
- `Location.getSpeed()` is GPS-derived and can lag or jitter briefly.
- The CSV is not a legal database. You are responsible for its contents.
- V1 does not auto-start from car Bluetooth because recent Android versions restrict background starts of location foreground services. A later version can add a user-triggered notification/widget shortcut or investigate Bluetooth-trigger flows.

## License

MIT

## GitHub one-click APK build

The workflow at `.github/workflows/build-apk.yml` builds an installable Android debug APK on each push to `main` and through **Actions → Build Android APK → Run workflow**. A successful run contains a `SpeedBeep-debug-apk` artifact; download its ZIP, extract `app-debug.apk`, and install that APK on an Android phone.

By default, builds use the committed offline Chennai speed-limit database; an OpenStreetMap server outage cannot block APK creation. To request new OSM limits during a manual build, enable the optional `refresh_osm` input. If OSM endpoints are unavailable, the importer keeps the existing bundled road data and proceeds with the build. GitHub Actions only retains APK artifacts for 30 days.

## Android full-screen map (performance fix)

Tap **OPEN FULL-SCREEN MAP** from the speedometer screen. The map is no longer
nested inside the screen's vertical scroll area, so dragging and pinching do not
compete with screen scrolling. It initially centers on a recent GPS position
when one is available, otherwise it opens at the last map center (initially
Chennai). Use **FOLLOW GPS** to recenter and track your location without
continually resetting your zoom, **FIT ROADS** for an overview, or manually pan
and zoom to explore.

The map displays saved road geometry in green when zoomed in sufficiently,
a bounded drive trail in blue (over-limit segments red; unknown-limit segments
grey), and a GPS marker. The full database is still used for speed alerts;
the map hides the green road overlay at wide zoom levels only to avoid drawing
thousands of tiny lines. No mapped speed-limit geometry is deleted or changed.

**EDIT STRETCH** (or the existing **SELECT A STRETCH ON MAP** button) enters
two-tap mode on the full-screen map. Tap the beginning and end of the same
configured road piece to split its limit; the existing verification and save
dialog remains in place. Back or Close cancels selection.

**Implementation:** nearby roads and the latest 4,000 trail samples are
rendered with two canvas overlays rather than thousands of individual
Polyline objects. Diagnostic-log parsing happens on a worker thread and
only while the map is visible. Tiles are cached by osmdroid and still need
internet for previously unseen areas. A failed log read preserves the
previously displayed trail. The speed alarm, background service, CSV edit
logic and exported database remain unchanged.

**Map reopen regression (0.4.2):** osmdroid destroys its tile provider when an Android Dialog detaches the MapView. Because SpeedBeep reuses the same MapView on the next open, the tiles previously disappeared. This version disables automatic destruction on temporary window detach and keeps explicit final cleanup in MainActivity.onDestroy(). Verify at least five consecutive open/close cycles, including zooming/panning, followed by background/foreground and opening the map again.\n\n**Manual phone QA before merging:** Open/close the map repeatedly; pinch/drag
near Chennai and use Follow GPS / Fit Roads; verify speed monitoring still
works while the map is open; check blue/red drive trace; edit a stretch and
verify the saved road limit; test missing internet/uncached tiles and repeated
background/foreground transitions. Run the pull-request APK CI as well.

## Map stretch snapping and curves (0.4.3)

**EDIT STRETCH** now snaps the two taps to the closest local speed-limit road
pieces and follows the connected road's actual corners rather than saving a
straight shortcut. A bright orange preview appears on the map before the
speed-limit confirmation. Check the selected geometry carefully, especially
at junctions or divided carriageways. The editor applies one verified speed
limit to the selected stretch only; the unaffected ends of earlier limits are
preserved. Edits must be made while safely parked or by a passenger.

**Offline-first fix (0.4.4):** The local speed-limit CSV contains only roads
with stored limits. The first attempt still automatically snaps start/end taps
to any matching connected CSV segments; it does **not** call OSM Overpass.
If it cannot find a connected route, it offers **TRACE ROAD** rather than
waiting for a slow/unavailable external server.

For an unmapped road, tap **TRACE ROAD** on the full-screen map, then tap
**START**, every bend (up to 150 m between points), and **END**. The orange
line previews the complete multi-segment trace. **UNDO** deletes your last
point; **FINISH** presents the confirmation dialog, asking for the verified
street name and posted speed limit. **CANCEL TRACE** abandons it without
changing the CSV. The trace does **not** automatically snap to unseen OSM
roads or identify official speed limits; position the points carefully and
check the orange line visually. The GPS matching corridor is narrower for
manually traced roads to reduce interference with parallel lanes.

These road edits work without an Overpass connection, including when tiles
are already cached or offline. Limit traces to four kilometres and at most
100 points. On-device QA: (1) snap existing connected roads, (2) fail
automatic snapping and choose TRACE ROAD, (3) make a curved road with
intermediate corners and save, (4) undo a point / cancel / reopen, (5) confirm
speed limits on the curved sections after saving. **Only edit while parked
or as a passenger.**

## Local map editor

Open `tools/speed_limit_editor.html` in a desktop browser.

It provides a visual OpenStreetMap editor for `speed_limits.csv`:

- Import an existing SpeedBeep CSV.
- Click two map points to create a road segment.
- Drag the selected segment's start/end markers to refine it.
- Edit road name, speed limit, GPS corridor and warning margin.
- Duplicate segments for adjoining stretches.
- Export a CSV that can be imported directly into the Android app.
- Autosaves the working set in browser local storage.

The HTML file itself is local and does not upload your CSV anywhere. It loads Leaflet and OpenStreetMap map tiles from the internet, so the basemap requires a network connection.
