#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AcideFluorhydrique
# SPDX-License-Identifier: GPL-3.0-or-later
"""
戰區地圖用的真實地理資料：海岸線與河流，取自 Natural Earth。

Natural Earth（https://www.naturalearthdata.com）的資料屬於公有領域，
可以任意使用、修改與散布，不需要署名。這裡仍然註明出處。

整包資料有幾十 MB，而一張戰區地圖只用得到它涵蓋的那一小塊，所以流程分兩步：

1. **裁切**（偶爾做一次，需要下載好的原始檔）：

       python3 tools/naturalearth.py <放 geojson 的目錄>

   依 theatres.THEATRES 的範圍，把每個戰區用得到的海岸線與河流裁下來，
   寫進 tools/data/theatre_<id>.json。需要的原始檔：

       ne_50m_land.geojson
       ne_10m_rivers_lake_centerlines.geojson
       ne_10m_rivers_europe.geojson         （歐洲的支流；沒有也行）

2. **產生地圖**（每次跑 genworld.py）：只讀 tools/data 底下裁好的檔案，
   不需要網路，也不需要原始檔。裁好的檔案跟著版本庫走，CI 重跑產生器時
   才會得到位元相同的結果。
"""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(HERE, "data")

LAND_SOURCE = "ne_50m_land.geojson"
RIVER_SOURCES = ("ne_10m_rivers_lake_centerlines.geojson", "ne_10m_rivers_europe.geojson")

# 裁切時在戰區四周多留的邊（度）。格子的取樣點都在範圍內，留邊只是保險。
MARGIN = 0.5

# 座標保留三位小數：約一百公尺，遠比一格（十幾公里）細，檔案卻小得多。
PRECISION = 3


def theatre_path(theatre_id):
    return os.path.join(DATA_DIR, "theatre_%s.json" % theatre_id)


def load(theatre_id):
    """讀一個戰區裁好的資料：{"land": [[環, ...], ...], "rivers": [{"name", "points"}, ...]}。"""
    path = theatre_path(theatre_id)
    if not os.path.exists(path):
        raise SystemExit(
            "找不到 %s。\n請先下載 Natural Earth 的原始檔，再執行 "
            "python3 tools/naturalearth.py <目錄>（見檔頭說明）。" % os.path.relpath(path)
        )
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


# ----------------------------------------------------------------------
# 幾何
# ----------------------------------------------------------------------

def clip_ring(ring, box):
    """Sutherland–Hodgman：把一個環裁進矩形 (lon_min, lon_max, lat_min, lat_max)。

    裁出來的環沿著矩形邊界可能有重疊的退化邊，但對「點在不在多邊形裡」的
    判斷沒有影響 —— 而那是這份資料唯一的用途。
    """
    lon_min, lon_max, lat_min, lat_max = box

    def clip(points, inside, cross):
        out = []
        for i, cur in enumerate(points):
            prev = points[i - 1]
            if inside(cur):
                if not inside(prev):
                    out.append(cross(prev, cur))
                out.append(cur)
            elif inside(prev):
                out.append(cross(prev, cur))
        return out

    def at_lon(x):
        return lambda a, b: (x, a[1] + (b[1] - a[1]) * (x - a[0]) / (b[0] - a[0]))

    def at_lat(y):
        return lambda a, b: (a[0] + (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]), y)

    points = [tuple(p[:2]) for p in ring]
    if points and points[0] == points[-1]:
        points = points[:-1]
    for inside, cross in (
        (lambda p: p[0] >= lon_min, at_lon(lon_min)),
        (lambda p: p[0] <= lon_max, at_lon(lon_max)),
        (lambda p: p[1] >= lat_min, at_lat(lat_min)),
        (lambda p: p[1] <= lat_max, at_lat(lat_max)),
    ):
        if not points:
            break
        points = clip(points, inside, cross)
    return points


def clip_line(points, box):
    """把一條折線裁成落在矩形裡的幾段（只留端點在範圍內的線段，不補交點）。"""
    lon_min, lon_max, lat_min, lat_max = box
    pieces, current = [], []
    for p in points:
        x, y = p[0], p[1]
        if lon_min <= x <= lon_max and lat_min <= y <= lat_max:
            current.append((x, y))
        else:
            if len(current) >= 2:
                pieces.append(current)
            current = []
    if len(current) >= 2:
        pieces.append(current)
    return pieces


def _rounded(points):
    out = []
    for x, y in points:
        p = [round(x, PRECISION), round(y, PRECISION)]
        if not out or out[-1] != p:
            out.append(p)
    return out


def _polygons(geometry):
    if geometry is None:
        return []
    if geometry["type"] == "Polygon":
        return [geometry["coordinates"]]
    if geometry["type"] == "MultiPolygon":
        return geometry["coordinates"]
    return []


def _lines(geometry):
    if geometry is None:
        return []
    if geometry["type"] == "LineString":
        return [geometry["coordinates"]]
    if geometry["type"] == "MultiLineString":
        return geometry["coordinates"]
    return []


# ----------------------------------------------------------------------
# 裁切
# ----------------------------------------------------------------------

def crop(source_dir, theatre):
    box = (
        theatre["lon_min"] - MARGIN, theatre["lon_max"] + MARGIN,
        theatre["lat_min"] - MARGIN, theatre["lat_max"] + MARGIN,
    )

    with open(os.path.join(source_dir, LAND_SOURCE), encoding="utf-8") as fh:
        land_features = json.load(fh)["features"]
    land = []
    for feature in land_features:
        for polygon in _polygons(feature["geometry"]):
            rings = []
            for index, ring in enumerate(polygon):
                clipped = _rounded(clip_ring(ring, box))
                if len(clipped) >= 3:
                    rings.append(clipped)
                elif index == 0:
                    break       # 外環整個在範圍外，內環也不必看了
            if rings:
                land.append(rings)

    wanted = set(theatre.get("rivers", ()))
    rivers = []
    seen = set()
    for name in RIVER_SOURCES:
        path = os.path.join(source_dir, name)
        if not os.path.exists(path):
            print("  （沒有 %s，略過）" % name)
            continue
        with open(path, encoding="utf-8") as fh:
            features = json.load(fh)["features"]
        for feature in features:
            props = feature["properties"]
            river = props.get("name") or props.get("NAME") or ""
            if river not in wanted:
                continue
            for line in _lines(feature["geometry"]):
                for piece in clip_line(line, box):
                    points = _rounded(piece)
                    key = (river, tuple(points[0]), tuple(points[-1]), len(points))
                    if len(points) >= 2 and key not in seen:
                        seen.add(key)
                        rivers.append({"name": river, "points": points})
    rivers.sort(key=lambda r: (r["name"], r["points"][0], r["points"][-1]))

    missing = sorted(wanted - {r["name"] for r in rivers})
    if missing:
        print("  ! %s：原始檔裡找不到這些河：%s" % (theatre["id"], "、".join(missing)))

    os.makedirs(DATA_DIR, exist_ok=True)
    out = {
        "source": "Natural Earth (public domain), naturalearthdata.com",
        "land": land,
        "rivers": rivers,
    }
    path = theatre_path(theatre["id"])
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, separators=(",", ":"))
        fh.write("\n")
    print("%-14s land %3d polygons, %3d river segments -> %s (%d KB)" % (
        theatre["id"], len(land), len(rivers), os.path.relpath(path), os.path.getsize(path) // 1024))


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    sys.path.insert(0, HERE)
    import theatres
    for theatre in theatres.THEATRES:
        crop(sys.argv[1], theatre)


if __name__ == "__main__":
    main()
