# SPDX-FileCopyrightText: 2026 AcideFluorhydrique
# SPDX-License-Identifier: GPL-3.0-or-later
"""把經緯度多邊形柵格化成尖頂六角格的地形陣列。"""

import math

import geodata


def axis_map(low, high, bands):
    """
    回傳 f(t)：把 [0,1] 的畫面比例映射到座標值，並且單調遞增。

    bands 是 [(起, 迄, 權重), ...]。權重是「每一度分到幾格」的相對值，
    所以權重 2 的區段在畫面上佔的格子是權重 1 的兩倍。沒被涵蓋的區段
    自動補權重 1。bands 為 None 就是等距，跟原本的行為一樣。

    這是為了讓地圖能玩：等距圓柱投影把 360 度平均分給格子，結果太平洋
    佔掉三分之一的螢幕，而整個西歐 —— 遊戲真正發生的地方 —— 只有幾格寬，
    英國小到放不下一個城市徽章。真實比例在這裡不是優點。
    """
    if not bands:
        return lambda t: low + t * (high - low)

    segments = []
    cursor = low
    for start, end, weight in sorted(bands):
        start = max(start, low)
        end = min(end, high)
        if end <= start:
            continue
        if start > cursor:
            segments.append((cursor, start, 1.0))
        segments.append((start, end, weight))
        cursor = end
    if cursor < high:
        segments.append((cursor, high, 1.0))

    total = sum((e - s) * w for s, e, w in segments)
    table = []
    acc = 0.0
    for s, e, w in segments:
        share = (e - s) * w / total
        table.append((acc, acc + share, s, e))
        acc += share

    def f(t):
        t = min(max(t, 0.0), 1.0)
        for t0, t1, s, e in table:
            if t <= t1:
                return s if t1 <= t0 else s + (t - t0) / (t1 - t0) * (e - s)
        return high

    return f


class Grid:
    """
    一張圓柱投影的六角格。奇數列偏移，與遊戲端的座標約定一致。

    經緯度到格子的對映可以分段加權（見 [axis_map]），預設仍是等距。
    """

    def __init__(self, cols, rows, lon_min, lon_max, lat_max, lat_min,
                 lon_bands=None, lat_bands=None):
        self.cols = cols
        self.rows = rows
        self.lon_min = lon_min
        self.lon_max = lon_max
        self.lat_max = lat_max
        self.lat_min = lat_min
        self._lon = axis_map(lon_min, lon_max, lon_bands)
        self._lat = axis_map(lat_min, lat_max, lat_bands)

    def lonlat(self, col, row):
        # 奇數列在畫面上往右偏半格，取樣點也要跟著偏，
        # 否則生成出來的海岸線會跟渲染出來的格子錯開半格。
        shift = 0.5 if row % 2 == 1 else 0.0
        fx = (col + shift + 0.5) / self.cols
        fy = (row + 0.5) / self.rows
        # fy = 0 是畫面上緣，對應 lat_max，所以要反過來查。
        return self._lon(fx), self._lat(1.0 - fy)

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


def anchor_cells(grid, anchors):
    """
    回傳每個錨點（城市經緯度）所落在的格子索引。

    錨點是真實城市的座標，所以它們**必定**在陸地上。手繪的海岸線只是
    近似，兩者衝突時以城市為準：這裡把城市所在的那一格標成陸地，一格，
    不外擴。沒有這一條，海岸線畫得內縮一點，港口城市就會被吸附到內陸
    幾百公里外，而畫面上完全看不出發生過什麼。
    """
    out = {}
    for lon, lat in anchors:
        best = None
        best_d = None
        for row in range(grid.rows):
            for col in range(grid.cols):
                clon, clat = grid.lonlat(col, row)
                dlon = (clon - lon + 180.0) % 360.0 - 180.0
                d = dlon * dlon + (clat - lat) * (clat - lat)
                if best_d is None or d < best_d:
                    best_d, best = d, grid.index(col, row)
        if best is not None:
            out[best] = (lon, lat)
    return out


def polyline_cells(grid, points, step=0.12):
    """沿著折線取樣，回傳沿途最近的格子索引集合。

    用「沿線找最近格」而不是「算點到線的距離再設門檻」：門檻要跟格子大小
    掛鉤，而這張圖的格子大小是逐區變化的，一個固定門檻在歐洲會挖太寬、
    在太平洋又挖不斷。沿線取樣得到的一定是一條連續、剛好一格寬的水道。
    """
    cells = set()
    for i in range(len(points) - 1):
        x0, y0 = points[i]
        x1, y1 = points[i + 1]
        span = math.hypot(x1 - x0, y1 - y0)
        steps = max(2, int(span / step) + 1)
        for k in range(steps + 1):
            t = k / steps
            lon = x0 + (x1 - x0) * t
            lat = y0 + (y1 - y0) * t
            best = None
            best_d = None
            for row in range(grid.rows):
                for col in range(grid.cols):
                    clon, clat = grid.lonlat(col, row)
                    dlon = (clon - lon + 180.0) % 360.0 - 180.0
                    d = dlon * dlon + (clat - lat) * (clat - lat)
                    if best_d is None or d < best_d:
                        best_d, best = d, grid.index(col, row)
            if best is not None:
                cells.add(best)
    return cells


def _island_by_polygon():
    out = {}
    for group, names in geodata.ISLANDS.items():
        for name in names:
            out[name] = group
    return out


_ISLAND_OF = _island_by_polygon()
_SEAS = {frozenset(pair) for pair in geodata.SEAS}


def apart(a, b):
    """
    兩個陸地多邊形之間是否一定隔著海。

    島嶼群組只跟同群組相連；[geodata.SEAS] 列出的大陸多邊形兩兩不相連；
    其餘的大陸多邊形彼此相連（歐洲與斯堪地那維亞、中國與朝鮮）。
    """
    if a == b:
        return False
    ga, gb = _ISLAND_OF.get(a), _ISLAND_OF.get(b)
    if ga is not None or gb is not None:
        return ga != gb
    return frozenset((a, b)) in _SEAS


def _ring_distance(lon, lat, polygon):
    if point_in_polygon(lon, lat, polygon):
        return 0.0
    return distance_to_polyline(lon, lat, list(polygon) + [polygon[0]])


def landmass_of(lon, lat):
    """
    一個點屬於哪個陸地多邊形（[geodata.LAND] 的鍵）。

    落在好幾個多邊形裡時以大陸為準 —— 薩哈林的多邊形跟西伯利亞有重疊，
    重疊處算西伯利亞，島嶼規則只管真正獨立的那一塊。都不在的點
    （城市錨點常常落在手繪海岸線外面一點）取最近的多邊形。
    """
    best_name, best_d = None, None
    for name, polygon in geodata.LAND.items():
        d = _ring_distance(lon, lat, polygon)
        if d == 0.0 and name not in _ISLAND_OF:
            return name
        if best_d is None or d < best_d:
            best_name, best_d = name, d
    return best_name


def separate_landmasses(grid, is_land, landmass, kept):
    """
    讓每一對 [apart] 的陸地之間至少隔一格海。

    兩格相鄰卻屬於不該相連的陸地時挖掉一格：先挖不是城市的那一格；
    兩格都不是城市時，島嶼對大陸挖大陸那一側（島嶼格子稀缺，日本全部
    也就十幾格），否則挖格子多的那一塊。兩格都是城市時，把其中一座城
    （島上的優先）搬到同一塊陸地上最近、且四周沒有對岸陸地的格子 ——
    城市座標只是錨點，站在哪一格是這裡決定的。

    [kept] 是 {格子: 城市經緯度}，搬家時一併更新。
    """
    size = {}
    for i, name in enumerate(landmass):
        if is_land[i]:
            size[name] = size.get(name, 0) + 1

    def neighbours(i):
        return [grid.index(c, r) for c, r in grid.neighbours(i % grid.cols, i // grid.cols)]

    def touches_across(i, ignore=None):
        return any(
            k != ignore and is_land[k] and apart(landmass[k], landmass[i])
            for k in neighbours(i)
        )

    def relocate(tile):
        lon0, lat0 = grid.lonlat(tile % grid.cols, tile // grid.cols)
        best, best_d = None, None
        for j in range(len(is_land)):
            if j == tile or not is_land[j] or j in kept:
                continue
            if apart(landmass[j], landmass[tile]) or touches_across(j, ignore=tile):
                continue
            lon, lat = grid.lonlat(j % grid.cols, j // grid.cols)
            d = (lon - lon0) ** 2 + (lat - lat0) ** 2
            if best_d is None or d < best_d:
                best, best_d = j, d
        if best is None:
            raise ValueError("找不到能安置城市 %r 的格子" % (kept[tile],))
        kept[best] = kept.pop(tile)
        landmass[best] = landmass[tile]
        is_land[tile] = False

    def bigger(i, j):
        return i if size.get(landmass[i], 0) >= size.get(landmass[j], 0) else j

    changed = True
    while changed:
        changed = False
        for i in range(len(is_land)):
            if not is_land[i]:
                continue
            for j in neighbours(i):
                if not is_land[i]:
                    break
                if not is_land[j] or not apart(landmass[i], landmass[j]):
                    continue
                island_i = landmass[i] in _ISLAND_OF
                island_j = landmass[j] in _ISLAND_OF
                if (i in kept) != (j in kept):
                    victim = j if i in kept else i
                elif i in kept:
                    relocate(i if island_i or not island_j else j)
                    changed = True
                    continue
                elif island_i != island_j:
                    victim = j if island_i else i
                else:
                    victim = bigger(i, j)
                is_land[victim] = False
                changed = True
    # 防呆：跑完之後不應該還有任何一對不該相連的陸地相鄰。
    for i in range(len(is_land)):
        if is_land[i] and touches_across(i):
            raise AssertionError("陸塊分離沒有收斂：格子 %d" % i)


def build_terrain(grid, anchors=()):
    """
    回傳 (terrain_codes, is_land, landmass, anchor_tiles)，前三者是逐格的一維串列。

    landmass 是每一格所屬的陸地多邊形（見 [landmass_of]），海洋格是 None。
    anchor_tiles 是 {城市經緯度: 格子}，陸塊分離搬過家的城市以它為準。
    """
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

    kept = anchor_cells(grid, anchors)
    for i in kept:
        is_land[i] = True

    # 海峽最後挖，但挖不到城市所在的格 —— 伊斯坦堡坐在海峽上。
    for points in geodata.STRAITS.values():
        for i in polyline_cells(grid, points):
            if i not in kept:
                is_land[i] = False

    # 城市格的陸塊看城市本身，不看格子中心：格子中心可能落在對岸。
    landmass = [None] * count
    for i in range(count):
        if not is_land[i]:
            continue
        lon, lat = kept[i] if i in kept else lonlat[i]
        landmass[i] = landmass_of(lon, lat)
    separate_landmasses(grid, is_land, landmass, kept)
    for i in range(count):
        if not is_land[i]:
            landmass[i] = None
    anchor_tiles = {coord: tile for tile, coord in kept.items()}

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
    return terrain, is_land, landmass, anchor_tiles


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
