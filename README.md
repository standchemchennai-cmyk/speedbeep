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
