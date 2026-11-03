package com.utk.speedbeep;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CsvRoadRepository {
    private CsvRoadRepository() {}

    public static List<RoadSegment> read(InputStream inputStream) throws IOException {
        List<RoadSegment> roads = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            Map<String, Integer> header = null;
            int lineNo = 0;

            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                List<String> cells = parseCsvLine(line);
                if (header == null) {
                    header = buildHeader(cells);
                    require(header, "name", "start_lat", "start_lon", "end_lat", "end_lon", "limit_kph");
                    continue;
                }

                try {
                    String name = cell(cells, header, "name");
                    double startLat = number(cells, header, "start_lat");
                    double startLon = number(cells, header, "start_lon");
                    double endLat = number(cells, header, "end_lat");
                    double endLon = number(cells, header, "end_lon");
                    double limitKph = number(cells, header, "limit_kph");
                    double corridorM = optionalNumber(cells, header, "corridor_m", 40.0);
                    double marginKph = optionalNumber(cells, header, "margin_kph", 3.0);

                    validate(name, startLat, startLon, endLat, endLon, limitKph, corridorM, marginKph);
                    roads.add(new RoadSegment(name, startLat, startLon, endLat, endLon,
                            limitKph, corridorM, marginKph));
                } catch (RuntimeException ex) {
                    throw new IOException("CSV line " + lineNo + ": " + ex.getMessage(), ex);
                }
            }
        }

        if (roads.isEmpty()) {
            throw new IOException("CSV contains no road segments");
        }
        return roads;
    }

    private static void validate(String name, double aLat, double aLon, double bLat, double bLon,
                                 double limit, double corridor, double margin) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("name is blank");
        if (!Double.isFinite(aLat) || !Double.isFinite(aLon)
                || !Double.isFinite(bLat) || !Double.isFinite(bLon)
                || !Double.isFinite(limit) || !Double.isFinite(corridor)
                || !Double.isFinite(margin))
            throw new IllegalArgumentException("non-finite CSV value");
        if (!latOk(aLat) || !latOk(bLat)) throw new IllegalArgumentException("latitude outside -90..90");
        if (!lonOk(aLon) || !lonOk(bLon)) throw new IllegalArgumentException("longitude outside -180..180");
        if (limit < 5 || limit > 200) throw new IllegalArgumentException("limit_kph outside 5..200");
        if (corridor < 5 || corridor > 1000) throw new IllegalArgumentException("corridor_m outside 5..1000");
        if (margin < 0 || margin > 50) throw new IllegalArgumentException("margin_kph outside 0..50");
    }

    private static boolean latOk(double n) { return n >= -90 && n <= 90; }
    private static boolean lonOk(double n) { return n >= -180 && n <= 180; }

    private static Map<String, Integer> buildHeader(List<String> cells) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            out.put(cells.get(i).trim().toLowerCase(Locale.US), i);
        }
        return out;
    }

    private static void require(Map<String, Integer> header, String... names) throws IOException {
        for (String n : names) {
            if (!header.containsKey(n)) throw new IOException("Missing CSV column: " + n);
        }
    }

    private static String cell(List<String> cells, Map<String, Integer> header, String name) {
        Integer i = header.get(name);
        if (i == null || i < 0 || i >= cells.size()) throw new IllegalArgumentException("missing value for " + name);
        return cells.get(i).trim();
    }

    private static double number(List<String> cells, Map<String, Integer> header, String name) {
        String s = cell(cells, header, name);
        if (s.isEmpty()) throw new IllegalArgumentException("missing value for " + name);
        return Double.parseDouble(s);
    }

    private static double optionalNumber(List<String> cells, Map<String, Integer> header, String name, double defaultValue) {
        Integer i = header.get(name);
        if (i == null || i < 0 || i >= cells.size()) return defaultValue;
        String s = cells.get(i).trim();
        return s.isEmpty() ? defaultValue : Double.parseDouble(s);
    }

    static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells;
    }
}
