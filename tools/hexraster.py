# SPDX-FileCopyrightText: 2026 AcideFluorhydrique
# SPDX-License-Identifier: GPL-3.0-or-later
"""把經緯度多邊形柵格化成尖頂六角格的地形陣列。"""

import math

import geodata


class Grid:
    """一張等距圓柱投影的六角格。奇數列偏移，與遊戲端的座標約定一致。"""

    def __init__(self, cols, rows, lon_min, lon_max, lat_max, lat_min):
        self.cols = cols
        self.rows = rows
        self.lon_min = lon_min
        self.lon_max = lon_max
        self.lat_max = lat_max
        self.lat_min = lat_min

    def lonlat(self, col, row):
        # 奇數列在畫面上往右偏半格，取樣點也要跟著偏，
        # 否則生成出來的海岸線會跟渲染出來的格子錯開半格。
        shift = 0.5 if row % 2 == 1 else 0.0
        fx = (col + shift + 0.5) / self.cols
        fy = (row + 0.5) / self.rows
        lon = self.lon_min + fx * (self.lon_max - self.lon_min)
        lat = self.lat_max - fy * (self.lat_max - self.lat_min)
        return lon, lat

    def index(self, col, row):
        return row * self.cols + col

    def neighbours(self, col, row):
        odd = row % 2
        candidates = [
            (col + 1, row),
            (col + 1 if odd else col, row - 1),
            (col if odd else col - 1, row - 1),
            (col - 1, row),
            (col if odd else col - 1, row + 1),
            (col + 1 if odd else col, row + 1),
        ]
        return [
            (c, r) for c, r in candidates
            if 0 <= c < self.cols and 0 <= r < self.rows
        ]


def point_in_polygon(x, y, polygon):
    """射線法。多邊形視為封閉，不必重複首尾點。"""
    inside = False
    n = len(polygon)
    j = n - 1
    for i in range(n):
        xi, yi = polygon[i]
        xj, yj = polygon[j]
        if (yi > y) != (yj > y):
            t = (y - yi) / (yj - yi)
            if x < xi + t * (xj - xi):
                inside = not inside
        j = i
    return inside


def distance_to_segment(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def distance_to_polyline(px, py, points):
    return min(
        distance_to_segment(px, py, points[i][0], points[i][1], points[i + 1][0], points[i + 1][1])
        for i in range(len(points) - 1)
    )


def in_any(x, y, polygons):
    return any(point_in_polygon(x, y, poly) for poly in polygons)


def build_terrain(grid):
    """回傳 (terrain_codes, is_land)，兩者都是逐格的一維串列。"""
    land_polys = list(geodata.LAND.values())
    sea_polys = list(geodata.SEA.values())
    desert_polys = list(geodata.DESERTS.values())
    jungle_polys = list(geodata.JUNGLES.values())
    farm_polys = list(geodata.FARMLANDS.values())

    count = grid.cols * grid.rows
    is_land = [False] * count
    lonlat = [None] * count

    for row in range(grid.rows):
        for col in range(grid.cols):
            i = grid.index(col, row)
            lon, lat = grid.lonlat(col, row)
            lonlat[i] = (lon, lat)
            is_land[i] = in_any(lon, lat, land_polys) and not in_any(lon, lat, sea_polys)

    terrain = ['~'] * count
    for row in range(grid.rows):
        for col in range(grid.cols):
            i = grid.index(col, row)
            lon, lat = lonlat[i]
            if not is_land[i]:
                # 靠岸的深海降級成淺海：登陸與沿岸航行都需要它。
                touches_land = any(is_land[grid.index(c, r)] for c, r in grid.neighbours(col, row))
                terrain[i] = '-' if touches_land else '~'
                continue
            terrain[i] = classify_land(lon, lat, desert_polys, jungle_polys, farm_polys)

    apply_mountains(grid, terrain, is_land, lonlat)
    apply_rivers(grid, terrain, is_land, lonlat)
    return terrain, is_land


def classify_land(lon, lat, desert_polys, jungle_polys, farm_polys):
    # 極區優先：緯度夠高就沒有其他可能。
    if lat >= 72 or lat <= -60:
        return '*'
    if lat >= 66:
        return 't'
    if in_any(lon, lat, farm_polys):
        return ':'
    if in_any(lon, lat, desert_polys):
        return 'd'
    if in_any(lon, lat, jungle_polys):
        return 'j'
    if lat >= 55 or lat <= -48:
        return 'f'
    # 溫帶的森林帶：北緯 45–55 與南半球對應區間多為林地。
    if 44 <= lat < 55:
        return 'f'
    # 熱帶輻合帶邊緣的濕地。
    if -6 <= lat <= 6 and in_any(lon, lat, jungle_polys):
        return 's'
    return '.'


def apply_mountains(grid, terrain, is_land, lonlat):
    for _name, line, radius in geodata.MOUNTAINS:
        for row in range(grid.rows):
            for col in range(grid.cols):
                i = grid.index(col, row)
                if not is_land[i]:
                    continue
                lon, lat = lonlat[i]
                # 經度方向的實際距離隨緯度收縮，不修正的話高緯度的山脈會胖得離譜。
                scale = max(0.25, math.cos(math.radians(lat)))
                d = distance_to_polyline(lon * scale, lat, [(x * scale, y) for x, y in line])
                if d <= radius:
                    terrain[i] = '^'
                elif d <= radius * 1.8 and terrain[i] in '.:f':
                    terrain[i] = 'h'


def apply_rivers(grid, terrain, is_land, lonlat):
    for _name, line, radius in geodata.RIVERS:
        for row in range(grid.rows):
            for col in range(grid.cols):
                i = grid.index(col, row)
                if not is_land[i] or terrain[i] in '^*':
                    continue
                lon, lat = lonlat[i]
                scale = max(0.25, math.cos(math.radians(lat)))
                d = distance_to_polyline(lon * scale, lat, [(x * scale, y) for x, y in line])
                if d <= radius:
                    terrain[i] = 'r'


def preview(grid, terrain):
    out = []
    for row in range(grid.rows):
        prefix = '' if row % 2 == 0 else ' '
        line = ''.join(terrain[grid.index(col, row)] for col in range(grid.cols))
        out.append(prefix + line.replace('~', ' ').replace('-', '.'))
    return '\n'.join(out)
