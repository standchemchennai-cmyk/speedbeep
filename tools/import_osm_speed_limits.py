#!/usr/bin/env python3
"""Populate SpeedBeep with explicit numeric OpenStreetMap maxspeed data for Chennai.

Conservative policy:
- Preserve all existing non-OSM rows as manual/authoritative data.
- Import only motor-vehicle highway classes with an explicit numeric maxspeed.
- Skip ambiguous/non-numeric maxspeed values.
- Skip OSM geometry that overlaps manual routes, so manual limits win.
- Write the same merged CSV to the repo root and Android assets.

OSM data: © OpenStreetMap contributors, ODbL 1.0.
https://www.openstreetmap.org/copyright
"""

from __future__ import annotations

import csv
import io
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROOT_CSV = ROOT / "speed_limits.csv"
ASSET_CSV = ROOT / "app" / "src" / "main" / "assets" / "speed_limits.csv"

# Chennai metro-ish coverage: south of Kelambakkam to north of Minjur,
# west through Sriperumbudur/Avadi and east to the coast.
SOUTH = 12.70
WEST = 79.95
NORTH = 13.32
EAST = 80.36

# Conservative driving-road classes. service is included because Chennai has
# many signed frontage/service roads; non-motor roads are deliberately omitted.
ALLOWED_HIGHWAYS = {
    "motorway", "motorway_link", "trunk", "trunk_link",
    "primary", "primary_link", "secondary", "secondary_link",
    "tertiary", "tertiary_link", "unclassified", "residential",
    "living_street", "service",
}

GENERATED_PREFIX = "OSM "
OSM_CORRIDOR_M = 15
DEFAULT_MARGIN_KPH = 3
MANUAL_EXCLUSION_M = 25.0
MAX_REASONABLE_KPH = 140

OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.nchc.org.tw/api/interpreter",
]

HEADER = [
    "name", "start_lat", "start_lon", "end_lat", "end_lon",
    "limit_kph", "corridor_m", "margin_kph",
]


def parse_numeric_maxspeed(value: str | None) -> int | None:
    if not value:
        return None
    s = value.strip().lower()
    # Strictly one numeric value, optionally with km/h or mph.
    m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*(km/?h|kph|kmh)?", s)
    if m:
        kph = round(float(m.group(1)))
    else:
        m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*mph", s)
        if not m:
            return None
        kph = round(float(m.group(1)) * 1.609344)
    if 5 <= kph <= MAX_REASONABLE_KPH:
        return kph
    return None


def load_manual_rows(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    text = path.read_text(encoding="utf-8-sig")
    data_lines = [line for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#")]
    if not data_lines:
        return []
    rows = list(csv.DictReader(io.StringIO("\n".join(data_lines))))
    return [row for row in rows if not str(row.get("name", "")).startswith(GENERATED_PREFIX)]


def row_segment(row: dict[str, str]) -> tuple[float, float, float, float]:
    return (
        float(row["start_lat"]), float(row["start_lon"]),
        float(row["end_lat"]), float(row["end_lon"]),
    )


def point_to_segment_m(lat: float, lon: float, seg: tuple[float, float, float, float]) -> float:
    a_lat, a_lon, b_lat, b_lon = seg
    cos_lat = max(math.cos(math.radians(lat)), 0.01)
    mx = 111_320.0 * cos_lat
    my = 111_320.0
    ax, ay = (a_lon - lon) * mx, (a_lat - lat) * my
    bx, by = (b_lon - lon) * mx, (b_lat - lat) * my
    dx, dy = bx - ax, by - ay
    length2 = dx * dx + dy * dy
    if length2 < 1e-6:
        return math.hypot(ax, ay)
    t = max(0.0, min(1.0, -(ax * dx + ay * dy) / length2))
    return math.hypot(ax + t * dx, ay + t * dy)


def overlaps_manual(a: tuple[float, float], b: tuple[float, float], manual_segments) -> bool:
    # Midpoint is sufficient for detecting same-road overlap while avoiding
    # deleting most nearby parallel streets.
    mid_lat = (a[0] + b[0]) / 2.0
    mid_lon = (a[1] + b[1]) / 2.0
    return any(point_to_segment_m(mid_lat, mid_lon, m) <= MANUAL_EXCLUSION_M for m in manual_segments)


def fetch_overpass() -> dict:
    query = f'''[out:json][timeout:120];
way["highway"]["maxspeed"]({SOUTH},{WEST},{NORTH},{EAST});
out tags geom;'''
    encoded = urllib.parse.urlencode({"data": query}).encode("utf-8")
    headers = {
        "User-Agent": "SpeedBeep/0.1 OSM maxspeed importer (github.com/standchemchennai-cmyk/speedbeep)",
        "Accept": "application/json",
    }
    errors = []
    for endpoint in OVERPASS_ENDPOINTS:
        try:
            req = urllib.request.Request(endpoint, data=encoded, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=180) as resp:
                return json.load(resp)
        except Exception as exc:
            errors.append(f"{endpoint}: {exc}")
            time.sleep(2)
    raise RuntimeError("All Overpass endpoints failed:\n" + "\n".join(errors))


def build_osm_rows(data: dict, manual_segments) -> tuple[list[dict[str, str]], dict[str, int]]:
    out: list[dict[str, str]] = []
    stats = {
        "ways_seen": 0,
        "ways_imported": 0,
        "ways_bad_speed": 0,
        "ways_bad_class": 0,
        "segments_generated": 0,
        "segments_manual_overlap": 0,
    }

    for element in data.get("elements", []):
        if element.get("type") != "way":
            continue
        stats["ways_seen"] += 1
        tags = element.get("tags", {})
        highway = tags.get("highway", "")
        if highway not in ALLOWED_HIGHWAYS:
            stats["ways_bad_class"] += 1
            continue
        limit = parse_numeric_maxspeed(tags.get("maxspeed"))
        if limit is None:
            stats["ways_bad_speed"] += 1
            continue
        geom = element.get("geometry") or []
        if len(geom) < 2:
            continue

        road_name = tags.get("name") or tags.get("ref") or f"way {element.get('id')}"
        label = f"{GENERATED_PREFIX}{road_name} [way {element.get('id')}]"
        imported_this_way = 0
        for p, q in zip(geom, geom[1:]):
            a = (float(p["lat"]), float(p["lon"]))
            b = (float(q["lat"]), float(q["lon"]))
            if a == b:
                continue
            if overlaps_manual(a, b, manual_segments):
                stats["segments_manual_overlap"] += 1
                continue
            out.append({
                "name": label,
                "start_lat": f"{a[0]:.6f}",
                "start_lon": f"{a[1]:.6f}",
                "end_lat": f"{b[0]:.6f}",
                "end_lon": f"{b[1]:.6f}",
                "limit_kph": str(limit),
                "corridor_m": str(OSM_CORRIDOR_M),
                "margin_kph": str(DEFAULT_MARGIN_KPH),
            })
            imported_this_way += 1
            stats["segments_generated"] += 1
        if imported_this_way:
            stats["ways_imported"] += 1
    return out, stats


def write_csv(path: Path, manual_rows: list[dict[str, str]], osm_rows: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        f.write("# SpeedBeep local road database\n")
        f.write("# Manual rows are authoritative and appear first. Generated OSM rows start with 'OSM '.\n")
        f.write("# OSM import includes only explicit numeric maxspeed values on motor-vehicle roads.\n")
        f.write("# OSM data © OpenStreetMap contributors, ODbL 1.0: https://www.openstreetmap.org/copyright\n")
        f.write(f"# OSM coverage bbox: {SOUTH},{WEST},{NORTH},{EAST}\n")
        writer = csv.DictWriter(f, fieldnames=HEADER, lineterminator="\n")
        writer.writeheader()
        for row in manual_rows:
            writer.writerow({k: row.get(k, "") for k in HEADER})
        writer.writerows(osm_rows)


def main() -> int:
    manual_rows = load_manual_rows(ASSET_CSV if ASSET_CSV.exists() else ROOT_CSV)
    manual_segments = []
    for row in manual_rows:
        try:
            manual_segments.append(row_segment(row))
        except Exception:
            print(f"Skipping malformed manual row: {row}", file=sys.stderr)

    print(f"Preserving {len(manual_rows)} manual CSV segments")
    data = fetch_overpass()
    osm_rows, stats = build_osm_rows(data, manual_segments)
    write_csv(ROOT_CSV, manual_rows, osm_rows)
    write_csv(ASSET_CSV, manual_rows, osm_rows)

    print(json.dumps(stats, indent=2, sort_keys=True))
    print(f"Wrote {len(manual_rows)} manual + {len(osm_rows)} OSM = {len(manual_rows)+len(osm_rows)} rows")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
