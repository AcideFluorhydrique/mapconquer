#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AcideFluorhydrique
# SPDX-License-Identifier: GPL-3.0-or-later
"""
產生 app/src/main/assets 底下的地圖與劇本。

    python3 tools/genworld.py

輸出是可以直接 commit 的純文字，遊戲端不會在執行期做任何生成 ——
所有玩家看到的都是同一張地圖，存檔也就永遠對得起來。
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import hexraster
import places
import scenarios as scn

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAPS_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "maps")
SCEN_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "scenarios")

# (id, cols, rows, lon_min, lon_max, lat_max, lat_min)
MAP_DEFS = [
    ("world",       96, 60, -180.0, 180.0,  78.0, -56.0),
    ("europe",      54, 42,  -12.0,  42.0,  62.0,  34.0),
    ("north_africa", 54, 36, -14.0,  42.0,  40.0,   6.0),
    ("sea_asia",    54, 40,   94.0, 152.0,  26.0, -12.0),
    ("east_europe", 48, 34,   14.0,  50.0,  60.0,  40.0),
    ("andes",       40, 50,  -84.0, -48.0,  10.0, -44.0),
]


class BuiltMap:
    """一張產生好的地圖，外加省份索引，讓劇本可以用鍵去查 id。"""

    def __init__(self, map_id, grid, terrain, is_land, provinces):
        self.id = map_id
        self.grid = grid
        self.terrain = terrain
        self.is_land = is_land
        self.provinces = provinces           # [(key, tier, nation, tile)]
        self.index_by_key = {p[0]: i for i, p in enumerate(provinces)}
        self.province_of = None              # 逐格的省份 id


def nearest_land_tile(grid, is_land, lon, lat):
    """把一個經緯度座標吸附到最近的陸地格。找不到就回 None。"""
    best = None
    best_d = None
    for row in range(grid.rows):
        for col in range(grid.cols):
            i = grid.index(col, row)
            if not is_land[i]:
                continue
            glon, glat = grid.lonlat(col, row)
            dlon = glon - lon
            # 經度差在高緯度要收縮，否則北方的城市會被吸到隔壁大陸。
            scale = max(0.2, abs(1.0 - abs(lat) / 95.0))
            d = (dlon * scale) ** 2 + (glat - lat) ** 2
            if best_d is None or d < best_d:
                best_d = d
                best = i
    return best, (best_d if best_d is not None else 1e9)


def assign_provinces(grid, is_land, seeds):
    """
    多源 BFS：每個陸地格歸給「沿陸地走最近」的那顆種子。

    走陸地連通而不是直線距離，是為了讓省界看起來合理 ——
    直線 Voronoi 會讓海峽對面的島被劃進大陸的省裡。
    孤立的小島（沒有種子可以走到）最後再用直線距離補上。
    """
    count = grid.cols * grid.rows
    owner = [-1] * count
    frontier = []
    for pid, tile in enumerate(seeds):
        if tile is None:
            continue
        owner[tile] = pid
        frontier.append(tile)

    head = 0
    while head < len(frontier):
        tile = frontier[head]
        head += 1
        col, row = tile % grid.cols, tile // grid.cols
        for c, r in grid.neighbours(col, row):
            j = grid.index(c, r)
            if not is_land[j] or owner[j] != -1:
                continue
            owner[j] = owner[tile]
            frontier.append(j)

    # 沒被走到的陸地（離島）改用直線最近的種子。
    for i in range(count):
        if not is_land[i] or owner[i] != -1:
            continue
        col, row = i % grid.cols, i // grid.cols
        lon, lat = grid.lonlat(col, row)
        best, best_d = None, None
        for pid, tile in enumerate(seeds):
            if tile is None:
                continue
            slon, slat = grid.lonlat(tile % grid.cols, tile // grid.cols)
            d = (slon - lon) ** 2 + (slat - lat) ** 2
            if best_d is None or d < best_d:
                best_d, best = d, pid
        owner[i] = best if best is not None else -1
    return owner


def run_length(values):
    """把一列整數壓成 `連續幾格:值` 的字串。"""
    out = []
    run = 1
    for i in range(1, len(values)):
        if values[i] == values[i - 1]:
            run += 1
        else:
            out.append("%d:%d" % (run, values[i - 1]))
            run = 1
    out.append("%d:%d" % (run, values[-1]))
    return " ".join(out)


def build_map(map_id, cols, rows, lon_min, lon_max, lat_max, lat_min):
    grid = hexraster.Grid(cols, rows, lon_min, lon_max, lat_max, lat_min)
    terrain, is_land = hexraster.build_terrain(grid)

    # 只收落在這張地圖範圍內的省份。
    margin = 0.0
    candidates = [
        p for p in places.PROVINCES
        if lon_min - margin <= p[1] <= lon_max + margin
        and lat_min - margin <= p[2] <= lat_max + margin
    ]

    seeds = []
    provinces = []
    used = set()
    for key, lon, lat, tier, nation, *_names in candidates:
        tile, dist = nearest_land_tile(grid, is_land, lon, lat)
        if tile is None:
            continue
        # 吸得太遠代表這座城市在這張地圖上其實沒有陸地可放，跳過。
        if dist > 36.0:
            continue
        if tile in used:
            # 同一格擠了兩座城：讓給等級高的那一座，另一座往外挪一格。
            moved = False
            col, row = tile % cols, tile // cols
            for c, r in grid.neighbours(col, row):
                j = grid.index(c, r)
                if is_land[j] and j not in used:
                    tile = j
                    moved = True
                    break
            if not moved:
                continue
        used.add(tile)
        seeds.append(tile)
        provinces.append((key, tier, nation, tile))

    province_of = assign_provinces(grid, is_land, seeds)
    built = BuiltMap(map_id, grid, terrain, is_land, provinces)
    built.province_of = province_of
    return built


def write_map(built):
    grid = built.grid
    lines = []
    lines.append("# SPDX-FileCopyrightText: 2026 AcideFluorhydrique")
    lines.append("# SPDX-License-Identifier: GPL-3.0-or-later")
    lines.append("# 由 tools/genworld.py 產生，請勿手動編輯。")
    lines.append("format 1")
    lines.append("id %s" % built.id)
    lines.append("cols %d" % grid.cols)
    lines.append("rows %d" % grid.rows)
    lines.append("")
    lines.append("[terrain]")
    for row in range(grid.rows):
        lines.append("".join(built.terrain[grid.index(col, row)] for col in range(grid.cols)))
    lines.append("")
    lines.append("[provinces]")
    for row in range(grid.rows):
        base = row * grid.cols
        lines.append(run_length(built.province_of[base:base + grid.cols]))
    lines.append("")
    lines.append("[meta]")
    for pid, (key, tier, _nation, tile) in enumerate(built.provinces):
        col, row = tile % grid.cols, tile // grid.cols
        lines.append("%d|%s|%d|%d,%d" % (pid, key, tier, col, row))

    path = os.path.join(MAPS_DIR, built.id + ".map")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    return path


def compress_ids(ids):
    """[1,2,3,7,9,10] → '1-3,7,9-10'"""
    if not ids:
        return ""
    ids = sorted(ids)
    parts = []
    start = prev = ids[0]
    for value in ids[1:]:
        if value == prev + 1:
            prev = value
            continue
        parts.append(str(start) if start == prev else "%d-%d" % (start, prev))
        start = prev = value
    parts.append(str(start) if start == prev else "%d-%d" % (start, prev))
    return ",".join(parts)


def tech_for(funds):
    """開局科技跟著國力走，讓大國一開始就真的比較強。"""
    if funds >= 1400:
        return [2, 2, 2, 2, 2, 1]
    if funds >= 900:
        return [1, 1, 1, 1, 1, 1]
    if funds >= 500:
        return [1, 1, 0, 0, 0, 1]
    return [0, 0, 0, 0, 0, 0]


def free_land_tiles(built, province_index, occupied):
    """
    省內還沒放兵、且陸軍站得下的格子，由省會往外一圈一圈找。

    要往外找兩圈是因為很多小國只有一兩格：如果只看省會，
    第二支部隊就永遠放不下，開局的軍隊會憑空少掉一半。
    """
    grid = built.grid
    _key, _tier, _nation, capital = built.provinces[province_index]
    order = [capital]
    seen = {capital}
    frontier = [capital]
    for _ring in range(2):
        nxt = []
        for tile in frontier:
            col, row = tile % grid.cols, tile // grid.cols
            for c, r in grid.neighbours(col, row):
                j = grid.index(c, r)
                if j in seen:
                    continue
                seen.add(j)
                order.append(j)
                nxt.append(j)
        frontier = nxt
    for tile in order:
        if tile in occupied:
            continue
        if built.province_of[tile] != province_index:
            continue
        if built.terrain[tile] in "~-":
            continue
        yield tile


def adjacent_water(built, tile, occupied):
    grid = built.grid
    col, row = tile % grid.cols, tile // grid.cols
    for c, r in grid.neighbours(col, row):
        j = grid.index(c, r)
        if built.terrain[j] in "~-" and ("sea", j) not in occupied:
            return j
    return None


def garrison_lines(built, owners, nation_funds):
    """
    依國力鋪開局部隊。

    原則：每個國家一定有能守首都的東西，但不會多到讓開局變成清兵。
    大國多一支裝甲與一架戰機，臨海的大國多一艘驅逐艦 ——
    這樣玩家第一眼就看得出誰是強權。
    """
    lines = []
    occupied = set()
    for code, province_ids in owners.items():
        if not province_ids:
            continue
        funds = nation_funds.get(code, 300)
        # 首都取等級最高的省。
        capital_pid = max(province_ids, key=lambda pid: built.provinces[pid][1])
        wanted = [(capital_pid, "INFANTRY", 1)]
        if funds >= 400:
            wanted.append((capital_pid, "ARTILLERY", 1))
        if funds >= 700:
            wanted.append((capital_pid, "ARMOUR", 1))
        if funds >= 1100:
            wanted.append((capital_pid, "FIGHTER", 2))
        # 其他大城各留一支守備。
        for pid in province_ids:
            if pid == capital_pid:
                continue
            if built.provinces[pid][1] >= 3:
                wanted.append((pid, "INFANTRY", 1))

        for pid, kind, level in wanted:
            placed = False
            for tile in free_land_tiles(built, pid, occupied):
                occupied.add(tile)
                col, row = tile % built.grid.cols, tile // built.grid.cols
                lines.append("%s|%d,%d|%s|%d|-" % (code, col, row, kind, level))
                placed = True
                break
            if not placed:
                continue

        if funds >= 900:
            water = adjacent_water(built, built.provinces[capital_pid][3], occupied)
            if water is not None:
                occupied.add(("sea", water))
                col, row = water % built.grid.cols, water // built.grid.cols
                lines.append("%s|%d,%d|DESTROYER|1|-" % (code, col, row))
    return lines


def write_conquest(built, scenario_id, name_key, desc_key, order, merge, start_year):
    """把 places 的國家分佈展開成一份征服劇本。"""
    owners = {}
    for pid, (_key, _tier, nation, _tile) in enumerate(built.provinces):
        code = merge.get(nation, nation) if merge else nation
        owners.setdefault(code, []).append(pid)

    def nation_row(code):
        if code in places.NATIONS:
            return places.NATIONS[code]
        return places.EXTRA_NATIONS[code]

    nation_funds = {}
    for code, pids in owners.items():
        base = nation_row(code)[5]
        if merge:
            # 併成帝國之後，資金按吃下的省份數重算，否則母國會比殖民地還窮。
            base = int(base * (1.0 + 0.05 * len(pids)))
        nation_funds[code] = base

    lines = []
    lines.append("# SPDX-FileCopyrightText: 2026 AcideFluorhydrique")
    lines.append("# SPDX-License-Identifier: GPL-3.0-or-later")
    lines.append("# 由 tools/genworld.py 產生，請勿手動編輯。")
    lines.append("format 1")
    lines.append("id %s" % scenario_id)
    lines.append("map %s" % built.id)
    lines.append("mode CONQUEST")
    lines.append("nameKey %s" % name_key)
    lines.append("descKey %s" % desc_key)
    lines.append("order %d" % order)
    lines.append("turnLimit 0")
    lines.append("startYear %d" % start_year)
    lines.append("")
    lines.append("[nations]")
    ordered = sorted(owners.keys(), key=lambda c: (-nation_funds[c], c))
    for code in ordered:
        english, _t, _s, colour, profile, _funds = nation_row(code)
        funds = nation_funds[code]
        capital = max(owners[code], key=lambda pid: built.provinces[pid][1])
        lines.append("%s|nation_%s|%s|%d|%s|%d|%s" % (
            code, code.lower(), colour, capital, profile, funds,
            ",".join(str(v) for v in tech_for(funds)),
        ))
    lines.append("")
    lines.append("[owners]")
    for code in ordered:
        lines.append("%s: %s" % (code, compress_ids(owners[code])))
    lines.append("")
    lines.append("[units]")
    lines.extend(garrison_lines(built, owners, nation_funds))
    lines.append("")
    lines.append("[playable]")
    # 可選國家：有城市的都能選，讓「用小國翻盤」也是一種玩法。
    lines.append(",".join(ordered))
    lines.append("")
    lines.append("[objectives]")
    lines.append("# 沒有列出目標 = 征服模式的預設條件（拿下地圖上八成的省份）")

    path = os.path.join(SCEN_DIR, scenario_id + ".scn")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    return path


def write_campaign(built, mission):
    """戰役關卡：只有演習部隊，其餘全部中立。"""
    owners = {}
    missing = []
    for code, keys in mission["forces"].items():
        ids = []
        for key in keys:
            pid = built.index_by_key.get(key)
            if pid is None:
                missing.append(key)
                continue
            ids.append(pid)
        owners[code] = ids
    if missing:
        print("  ! %s: 這些省份不在 %s 上：%s" % (mission["id"], built.id, ", ".join(missing)))

    nation_funds = {code: scn.FACTIONS[code][4] for code in owners}

    lines = []
    lines.append("# SPDX-FileCopyrightText: 2026 AcideFluorhydrique")
    lines.append("# SPDX-License-Identifier: GPL-3.0-or-later")
    lines.append("# 由 tools/genworld.py 產生，請勿手動編輯。")
    lines.append("format 1")
    lines.append("id %s" % mission["id"])
    lines.append("map %s" % built.id)
    lines.append("mode CAMPAIGN")
    lines.append("nameKey %s" % ("scn_" + mission["id"]))
    lines.append("descKey %s" % ("scn_" + mission["id"] + "_desc"))
    lines.append("order %d" % mission["order"])
    lines.append("turnLimit %d" % mission["turn_limit"])
    lines.append("startYear %d" % mission.get("year", 2026))
    lines.append("starTurns %d,%d" % tuple(mission["stars"]))
    lines.append("")
    lines.append("[nations]")
    for code in owners:
        english, _t, _s, colour, funds, profile = scn.FACTIONS[code]
        capital = max(owners[code], key=lambda pid: built.provinces[pid][1]) if owners[code] else -1
        lines.append("%s|nation_%s|%s|%d|%s|%d|%s" % (
            code, code.lower(), colour, capital, profile, funds,
            ",".join(str(v) for v in mission.get("tech", {}).get(code, [1, 1, 1, 0, 0, 1])),
        ))
    lines.append("")
    lines.append("[relations]")
    for a, b in mission["wars"]:
        lines.append("%s %s WAR" % (a, b))
    lines.append("")
    lines.append("[owners]")
    for code, ids in owners.items():
        lines.append("%s: %s" % (code, compress_ids(ids)))
    lines.append("")
    lines.append("[units]")
    lines.extend(campaign_units(built, owners, mission))
    lines.append("")
    lines.append("[playable]")
    lines.append(mission["player"])
    lines.append("")
    lines.append("[objectives]")
    for objective in mission["objectives"]:
        kind = objective[0]
        if kind in ("CAPTURE_PROVINCES", "HOLD_PROVINCES"):
            ids = [built.index_by_key[k] for k in objective[1] if k in built.index_by_key]
            turn = objective[2] if len(objective) > 2 else 0
            lines.append("%s|%s|%d" % (kind, compress_ids(ids), turn))
        elif kind == "ELIMINATE_NATION":
            lines.append("%s|%s" % (kind, objective[1]))
        else:
            lines.append("%s||%d" % (kind, objective[1]))

    path = os.path.join(SCEN_DIR, mission["id"] + ".scn")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    return path


def campaign_units(built, owners, mission):
    """關卡的開局部隊由 mission 的編制表決定，好讓每一關教一件事。"""
    lines = []
    occupied = set()
    for code, roster in mission["roster"].items():
        ids = owners.get(code, [])
        if not ids:
            continue
        capital = max(ids, key=lambda pid: built.provinces[pid][1])
        cursor = 0
        for kind, level, count in roster:
            for _ in range(count):
                if kind in ("DESTROYER", "CRUISER", "TRANSPORT_SHIP", "SUBMARINE", "CARRIER", "BATTLESHIP"):
                    anchor = built.provinces[ids[cursor % len(ids)]][3]
                    water = adjacent_water(built, anchor, occupied)
                    if water is None:
                        continue
                    occupied.add(("sea", water))
                    col, row = water % built.grid.cols, water // built.grid.cols
                    lines.append("%s|%d,%d|%s|%d|-" % (code, col, row, kind, level))
                    cursor += 1
                    continue
                pid = ids[cursor % len(ids)] if kind != "HEADQUARTERS" else capital
                cursor += 1
                placed = False
                for tile in free_land_tiles(built, pid, occupied):
                    occupied.add(tile)
                    col, row = tile % built.grid.cols, tile // built.grid.cols
                    lines.append("%s|%d,%d|%s|%d|-" % (code, col, row, kind, level))
                    placed = True
                    break
                if not placed:
                    # 省會周圍塞滿了就往首都擠，塞不下就放棄這一支。
                    for tile in free_land_tiles(built, capital, occupied):
                        occupied.add(tile)
                        col, row = tile % built.grid.cols, tile // built.grid.cols
                        lines.append("%s|%d,%d|%s|%d|-" % (code, col, row, kind, level))
                        break
    return lines


def main():
    os.makedirs(MAPS_DIR, exist_ok=True)
    os.makedirs(SCEN_DIR, exist_ok=True)

    built_maps = {}
    for definition in MAP_DEFS:
        built = build_map(*definition)
        built_maps[built.id] = built
        path = write_map(built)
        land = sum(1 for t in built.terrain if t not in "~-")
        print("map %-13s %3dx%-3d  provinces %3d  land %4d  -> %s"
              % (built.id, built.grid.cols, built.grid.rows,
                 len(built.provinces), land, os.path.relpath(path, ROOT)))

    world = built_maps["world"]
    print(write_conquest(world, "conquest_modern", "scn_conquest_modern",
                         "scn_conquest_modern_desc", 10, None, 2026))
    print(write_conquest(world, "conquest_empires", "scn_conquest_empires",
                         "scn_conquest_empires_desc", 20, places.EMPIRE_MERGE, 1900))

    for mission in scn.CAMPAIGN:
        built = built_maps[mission["map"]]
        print(write_campaign(built, mission))


if __name__ == "__main__":
    main()
