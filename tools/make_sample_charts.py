#!/usr/bin/env python3
"""Generate sample charts for Derrotero development.

Creates, from the same hand-drawn vector "chart" of Santander bay:

  * ``tools/samples/santander-test.kap``  — BSB/Maptech raster chart (NOAA style)
  * ``tools/samples/santander-test.gpkg`` — GeoPackage raster tile pyramid

They are only used to exercise the readers on a device/emulator; no real nautical data
is included. Requires Pillow (``pip install pillow``).

    python tools/make_sample_charts.py
"""

from __future__ import annotations

import math
import os
import sqlite3
import struct
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "samples")

# Coverage of the fake chart (the mock Signal K boat at 43.30 N 2.95 W sits inside).
LON_MIN, LON_MAX = -3.40, -2.85
LAT_MIN, LAT_MAX = 43.15, 43.50

# BSB palette indexes.
SEA = 1
INK = 2
LAND = 3
SHALLOW = 4
MID = 5
INTERTIDAL = 6
ROUTE = 7
CONTOUR = 8
MAGENTA = 9

PALETTE = {
    SEA: (255, 255, 255),
    INK: (20, 20, 20),
    LAND: (247, 231, 181),
    SHALLOW: (198, 230, 245),
    MID: (150, 205, 230),
    INTERTIDAL: (200, 228, 190),
    ROUTE: (214, 40, 40),
    CONTOUR: (130, 150, 165),
    MAGENTA: (200, 30, 160),
}

# Coastline, west to east (lon, lat). Land lies south of this line.
COAST = [
    (-3.400, 43.300), (-3.370, 43.308), (-3.345, 43.296), (-3.320, 43.283),
    (-3.298, 43.290), (-3.276, 43.277), (-3.252, 43.268), (-3.228, 43.262),
    (-3.205, 43.272), (-3.182, 43.292), (-3.160, 43.302), (-3.140, 43.292),
    (-3.118, 43.272), (-3.096, 43.258), (-3.072, 43.263), (-3.048, 43.250),
    (-3.022, 43.256), (-3.000, 43.260), (-2.972, 43.252), (-2.944, 43.258),
    (-2.916, 43.247), (-2.888, 43.252), (-2.862, 43.262), (-2.850, 43.268),
]


def merc(lat: float) -> float:
    return math.log(math.tan(math.pi / 4.0 + lat * math.pi / 360.0))


def interp_coast(lon: float) -> float:
    """Latitude of the coastline at ``lon`` (linear between control points)."""
    if lon <= COAST[0][0]:
        return COAST[0][1]
    for (lon_a, lat_a), (lon_b, lat_b) in zip(COAST, COAST[1:]):
        if lon_a <= lon <= lon_b:
            t = (lon - lon_a) / (lon_b - lon_a)
            return lat_a + (lat_b - lat_a) * t
    return COAST[-1][1]


def font(size: int):
    for name in ("arial.ttf", "DejaVuSans.ttf", "calibri.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except Exception:
            continue
    try:
        return ImageFont.load_default(size=size)
    except TypeError:
        return ImageFont.load_default()


def center_text(draw: ImageDraw.ImageDraw, x: float, y: float, text: str, fnt, fill) -> None:
    box = draw.textbbox((0, 0), text, font=fnt)
    draw.text(
        (x - (box[2] - box[0]) / 2 - box[0], y - (box[3] - box[1]) / 2 - box[1]),
        text,
        font=fnt,
        fill=fill,
    )


def draw_chart(
    draw: ImageDraw.ImageDraw,
    project,
    size: int,
    detail: bool = True,
    width: int | None = None,
    height: int | None = None,
    rgb: bool = False,
) -> None:
    """Draws the fake chart with a ``project(lat, lon) -> (x, y)`` callable.

    ``rgb=False`` paints BSB palette indexes into an ``L`` image; ``rgb=True`` paints the
    real colours into an RGB image (used for the GeoPackage tiles).
    """
    unit = size / 2400.0
    line = max(1, round(4 * unit))
    w = width if width is not None else size
    h = height if height is not None else size

    def pt(lon, lat):
        return project(lat, lon)

    def col(index: int):
        return PALETTE[index] if rgb else index

    # Land polygon: coastline then the southern/eastern/western frame.
    land = [pt(lon, lat) for lon, lat in COAST]
    land += [pt(LON_MAX, LAT_MIN), pt(LON_MIN, LAT_MIN)]
    draw.polygon(land, fill=col(LAND))

    # Depth bands north of the coast.
    for offset, index in ((0.030, MID), (0.013, SHALLOW)):
        band = [pt(lon, lat) for lon, lat in COAST]
        band += [pt(lon, lat + offset) for lon, lat in reversed(COAST)]
        draw.polygon(band, fill=col(index))

    # Intertidal strip right on the coast.
    strip = [pt(lon, lat) for lon, lat in COAST]
    strip += [pt(lon, lat + 0.004) for lon, lat in reversed(COAST)]
    draw.polygon(strip, fill=col(INTERTIDAL))

    # Contours and coastline.
    for offset in (0.006, 0.013, 0.030):
        draw.line([pt(lon, lat + offset) for lon, lat in COAST], fill=col(CONTOUR), width=max(1, line - 1))
    draw.line([pt(lon, lat) for lon, lat in COAST], fill=col(INK), width=line + 1)

    # Soundings (the text is what you actually read on a paper chart).
    if detail:
        sounding_font = font(max(9, round(15 * unit)))
        row = 0
        lat = LAT_MIN + 0.02
        while lat < LAT_MAX - 0.01:
            lon = LON_MIN + 0.02
            while lon < LON_MAX - 0.01:
                coast = interp_coast(lon)
                depth = (lat - coast) * 420.0 + 3.0 + ((row * 7) % 5) * 0.6
                if lat > coast + 0.004:
                    x, y = pt(lon, lat)
                    if not (x < w * 0.45 and y < h * 0.15):
                        center_text(draw, x, y, f"{int(depth)}", sounding_font, col(INK))
                lon += 0.020
            row += 1
            lat += 0.014

    # A planned route with waypoints.
    route = [(-3.345, 43.412), (-3.262, 43.360), (-3.170, 43.337), (-3.075, 43.300)]
    draw.line([pt(lon, lat) for lon, lat in route], fill=col(ROUTE), width=max(2, round(5 * unit)))
    for lon, lat in route:
        x, y = pt(lon, lat)
        r = max(3, round(10 * unit))
        draw.ellipse([x - r, y - r, x + r, y + r], outline=col(ROUTE), width=max(1, round(3 * unit)))

    # Compass rose (top-right, away from the title block).
    cx, cy = pt(LON_MAX - 0.06, LAT_MAX - 0.035)
    r = 52 * unit
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col(INK), width=max(1, line - 1))
    draw.line([(cx, cy - r), (cx, cy + r)], fill=col(INK), width=max(1, line - 1))
    draw.line([(cx - r, cy), (cx + r, cy)], fill=col(INK), width=max(1, line - 1))
    draw.polygon(
        [(cx, cy - r * 1.15), (cx - r * 0.18, cy - r * 0.55), (cx + r * 0.18, cy - r * 0.55)],
        fill=col(ROUTE),
    )
    center_text(draw, cx, cy - r * 1.45, "N", font(max(10, round(34 * unit))), col(INK))

    # Magenta navigation sector from a light.
    lon, lat = -3.115, 43.262
    x, y = pt(lon, lat)
    span = 150 * unit
    draw.pieslice(
        [x - span, y - span, x + span, y + span],
        225,
        300,
        fill=None,
        outline=col(MAGENTA),
        width=max(1, line - 1),
    )
    draw.ellipse([x - 4, y - 4, x + 4, y + 4], fill=col(MAGENTA))

    if detail:
        draw.text((60 * unit, 50 * unit), "DERROTERO TEST CHART", fill=col(INK), font=font(max(12, round(46 * unit))))
        draw.text((60 * unit, 115 * unit), "SANTANDER  ·  1:45000  ·  WGS84", fill=col(INK), font=font(max(9, round(26 * unit))))
        draw.text((60 * unit, 155 * unit), "NOT FOR NAVIGATION · SAMPLE DATA", fill=col(ROUTE), font=font(max(8, round(22 * unit))))

    # Neat line / border. In tile rendering each tile would get its own frame, which shows
    # up as a grid, so the border belongs only to the full-chart render.
    if detail:
        margin = 12 * unit
        draw.rectangle(
            [margin, margin, w - margin, h - margin],
            outline=col(INK),
            width=max(1, round(3 * unit)),
        )


# ----------------------------------------------------------------------------- KAP


def encode_run(value: int, count: int, color_size: int = 4) -> bytes:
    """One BSB run for ``color_size`` bits of palette index."""
    count_mask = (1 << (7 - color_size)) - 1
    shift = 7 - color_size
    n = count - 1
    k = 0
    while (n >> (7 * k)) > count_mask:
        k += 1
    low = n >> (7 * k) if k else n
    rest = n & ((1 << (7 * k)) - 1) if k else 0
    control = (value << shift) | low
    if k:
        control |= 0x80
    out = bytearray([control])
    for i in range(k):
        digit = (rest >> (7 * (k - 1 - i))) & 0x7F
        out.append(digit | (0x80 if i < k - 1 else 0))
    return bytes(out)


def rle_row(marker: int, pixels: bytes, color_size: int = 4) -> bytes:
    out = bytearray()
    # Base-128 line marker, most significant first.
    digits = []
    value = marker
    while True:
        digits.insert(0, value & 0x7F)
        value >>= 7
        if value == 0:
            break
    for i, digit in enumerate(digits):
        out.append(digit | (0x80 if i < len(digits) - 1 else 0))

    width = len(pixels)
    x = 0
    while x < width:
        value = pixels[x]
        run = 1
        while x + run < width and pixels[x + run] == value:
            run += 1
        out += encode_run(value, run, color_size)
        x += run
    out.append(0)
    return bytes(out)


def make_kap(path: str, width: int = 2400, height: int = 1800) -> None:
    image = Image.new("L", (width, height), SEA)
    draw = ImageDraw.Draw(image)

    def project(lat: float, lon: float):
        x = (lon - LON_MIN) / (LON_MAX - LON_MIN) * width
        y = (merc(LAT_MAX) - merc(lat)) / (merc(LAT_MAX) - merc(LAT_MIN)) * height
        return x, y

    draw_chart(draw, project, 2400, width=width, height=height)

    header = [
        "!Derrotero generated sample chart",
        "VER/3.0",
        f"BSB/NA=DERROTERO TEST SANTANDER,NU=999900,RA={width},{height},DU=254",
        "KNP/SC=45000,GD=WGS84,PR=Mercator",
        "    PP=43.30,PI=0.033333,SP=,SK=0.0,TA=90.0",
        "     UN=Metres,SD=HHWLT,DX=2.5,DY=2.5",
    ]
    for index, (r, g, b) in sorted(PALETTE.items()):
        header.append(f"RGB/{index},{r},{g},{b}")
    refs = [
        (0, 0, LAT_MAX, LON_MIN),
        (width, 0, LAT_MAX, LON_MAX),
        (width, height, LAT_MIN, LON_MAX),
        (0, height, LAT_MIN, LON_MIN),
    ]
    for i, (px, py, lat, lon) in enumerate(refs, start=1):
        header.append(f"REF/{i},{px},{py},{lat:.9f},{lon:.9f}")
    header.append(f"PLY/1,{LAT_MAX:.9f},{LON_MIN:.9f}")
    header.append(f"PLY/2,{LAT_MAX:.9f},{LON_MAX:.9f}")
    header.append(f"PLY/3,{LAT_MIN:.9f},{LON_MAX:.9f}")
    header.append(f"PLY/4,{LAT_MIN:.9f},{LON_MIN:.9f}")

    text = ("\r\n".join(header) + "\r\n").encode("latin-1")
    rows = image.tobytes()

    out = bytearray(text)
    out += b"\x1a\x00"
    out.append(4)  # 4 bits per palette index

    offsets = []
    for y in range(height):
        offsets.append(len(out))
        out += rle_row(y + 1, rows[y * width:(y + 1) * width])

    table_offset = len(out)
    for offset in offsets:
        out += struct.pack(">i", offset)
    out += struct.pack(">i", table_offset)

    with open(path, "wb") as handle:
        handle.write(out)
    print(f"{path}: {width}x{height}, {len(out) / 1024:.0f} kB")


# ----------------------------------------------------------------------------- GPKG


def png_bytes(image: Image.Image) -> bytes:
    buffer = BytesIO()
    image.save(buffer, format="PNG", optimize=True)
    return buffer.getvalue()


def tile_project(z: int, x: int, y: int, size: int = 256):
    n = 2 ** z

    def project(lat: float, lon: float):
        px = (lon + 180.0) / 360.0 * n * size - x * size
        py = (1.0 - math.log(math.tan(math.pi / 4 + lat * math.pi / 360.0)) / math.pi) / 2.0 * n * size - y * size
        return px, py

    return project


def tile_range(z: int):
    n = 2 ** z

    def x_of(lon):
        return int((lon + 180.0) / 360.0 * n)

    def y_of(lat):
        m = math.log(math.tan(math.pi / 4 + lat * math.pi / 360.0)) / math.pi
        return int((1.0 - m) / 2.0 * n)

    return (
        x_of(LON_MIN), x_of(LON_MAX),
        y_of(LAT_MAX), y_of(LAT_MIN),
    )


def make_gpkg(path: str) -> None:
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    cur = db.cursor()
    cur.executescript(
        """
        PRAGMA application_id = 1196444487;
        PRAGMA user_version = 10200;
        CREATE TABLE gpkg_spatial_ref_sys (
            srs_name TEXT NOT NULL, srs_id INTEGER PRIMARY KEY, organization TEXT NOT NULL,
            organization_coordsys_id INTEGER NOT NULL, definition TEXT NOT NULL, description TEXT);
        CREATE TABLE gpkg_contents (
            table_name TEXT NOT NULL PRIMARY KEY, data_type TEXT NOT NULL,
            identifier TEXT UNIQUE, description TEXT DEFAULT '',
            last_change DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
            min_x DOUBLE, min_y DOUBLE, max_x DOUBLE, max_y DOUBLE, srs_id INTEGER,
            CONSTRAINT fk_gc_r_srs_id FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id));
        CREATE TABLE gpkg_tile_matrix_set (
            table_name TEXT NOT NULL PRIMARY KEY, srs_id INTEGER NOT NULL,
            min_x DOUBLE NOT NULL, min_y DOUBLE NOT NULL, max_x DOUBLE NOT NULL, max_y DOUBLE NOT NULL,
            CONSTRAINT fk_gtms_table_name FOREIGN KEY (table_name) REFERENCES gpkg_contents(table_name),
            CONSTRAINT fk_gtms_srs FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id));
        CREATE TABLE gpkg_tile_matrix (
            table_name TEXT NOT NULL, zoom_level INTEGER NOT NULL,
            matrix_width INTEGER NOT NULL, matrix_height INTEGER NOT NULL,
            tile_width INTEGER NOT NULL, tile_height INTEGER NOT NULL,
            pixel_x_size DOUBLE NOT NULL, pixel_y_size DOUBLE NOT NULL,
            CONSTRAINT pk_ttm PRIMARY KEY (table_name, zoom_level),
            CONSTRAINT fk_tmm_table_name FOREIGN KEY (table_name) REFERENCES gpkg_contents(table_name));
        CREATE TABLE tiles (
            zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL,
            tile_row INTEGER NOT NULL, tile_data BLOB NOT NULL);
        CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row);
        """
    )
    cur.execute(
        "INSERT INTO gpkg_spatial_ref_sys VALUES (?,?,?,?,?,?)",
        ("WGS 84 / Pseudo-Mercator", 3857, "EPSG", 3857,
         "PROJCS[\"WGS 84 / Pseudo-Mercator\",GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\","
         "SPHEROID[\"WGS 84\",6378137,298.257223563]],PRIMEM[\"Greenwich\",0],"
         "UNIT[\"degree\",0.0174532925199433]],PROJECTION[\"Mercator_1SP\"],"
         "PARAMETER[\"central_meridian\",0],PARAMETER[\"scale_factor\",1],"
         "PARAMETER[\"false_easting\",0],PARAMETER[\"false_northing\",0],"
         "UNIT[\"metre\",1],AUTHORIZATION[\"EPSG\",\"3857\"]]", "Web Mercator"),
    )
    cur.execute(
        "INSERT INTO gpkg_spatial_ref_sys VALUES (?,?,?,?,?,?)",
        ("WGS 84", 4326, "EPSG", 4326,
         'GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],'
         'PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]]', "WGS 84"),
    )

    radius = 6378137.0
    west = math.radians(LON_MIN) * radius
    east = math.radians(LON_MAX) * radius
    south = merc(LAT_MIN) * radius
    north = merc(LAT_MAX) * radius

    cur.execute(
        "INSERT INTO gpkg_contents VALUES (?,?,?,?,?,?,?,?,?,?)",
        ("tiles", "tiles", "santander-test", "Derrotero sample", "2026-01-01T00:00:00.000Z",
         west, south, east, north, 3857),
    )
    cur.execute(
        "INSERT INTO gpkg_tile_matrix_set VALUES (?,?,?,?,?,?)",
        ("tiles", 3857, west, south, east, north),
    )

    min_zoom, max_zoom = 10, 14
    total = 0
    for z in range(min_zoom, max_zoom + 1):
        n = 2 ** z
        cur.execute(
            "INSERT INTO gpkg_tile_matrix VALUES (?,?,?,?,?,?,?,?)",
            ("tiles", z, n, n, 256, 256,
             2 * math.pi * radius / (256 * n), 2 * math.pi * radius / (256 * n)),
        )
        x0, x1, y0, y1 = tile_range(z)
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                image = Image.new("RGB", (256, 256), (255, 255, 255))
                draw = ImageDraw.Draw(image)
                draw_chart(draw, tile_project(z, x, y), 256, detail=False, rgb=True)
                cur.execute(
                    "INSERT INTO tiles VALUES (?,?,?,?)",
                    (z, x, y, sqlite3.Binary(png_bytes(image))),
                )
                total += 1

    db.commit()
    db.close()
    print(f"{path}: zooms {min_zoom}-{max_zoom}, {total} tiles")


def make_nos(kap_path: str, nos_path: str, geo_path: str, width: int, height: int) -> None:
    data = Path(kap_path).read_bytes()
    marker = data.index(b"\x1a\x00")
    header = data[:marker].decode("latin-1")
    body = data[marker:]
    lines = [line for line in header.splitlines() if not line.startswith(("REF/", "PLY/"))]
    lines = ["NOS/" + line[4:] if line.startswith("BSB/") else line for line in lines]
    nos_header = ("\r\n".join(lines) + "\r\n").encode("latin-1")

    # The KAP index table stores absolute offsets; after shrinking the header they are stale,
    # so drop it and let the reader walk the scanlines sequentially.
    table_offset = int.from_bytes(body[-4:], "big")
    if marker < table_offset < len(data):
        body = body[: table_offset - marker]

    Path(nos_path).write_bytes(nos_header + body)
    points = [
        (LON_MIN, LAT_MAX, 0, 0),
        (LON_MAX, LAT_MAX, 0, width),
        (LON_MAX, LAT_MIN, height, width),
        (LON_MIN, LAT_MIN, height, 0),
    ]
    Path(geo_path).write_text(
        "\n".join(
            f"Point{i}={lon:.6f} {lat:.6f} {line} {pixel}"
            for i, (lon, lat, line, pixel) in enumerate(points, start=1)
        ) + "\n",
        encoding="latin-1",
    )
    print(f"{nos_path}: NOS/GEO {width}x{height} + {Path(geo_path).name}")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    kap = os.path.join(OUT, "santander-test.kap")
    make_kap(kap)
    make_nos(kap, os.path.join(OUT, "santander-test.nos"), os.path.join(OUT, "santander-test.geo"), 2400, 1800)
    make_gpkg(os.path.join(OUT, "santander-test.gpkg"))
