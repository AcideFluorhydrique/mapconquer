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
import topology

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAPS_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "maps")
SCEN_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "scenarios")

# 世界地圖的分段權重。權重是「每一度分到幾格」的相對值。
#
# 等距投影在遊戲上是行不通的：太平洋吃掉三分之一的寬度，而歐洲 ——
# 整個戰役與大半征服的舞台 —— 窄到英國放不下一枚城市徽章。原版遊戲也不是
# 等距的，它把歐洲與日本放大、把兩大洋壓扁。這裡照做。
#
# 代價是誠實的：巴西與北大西洋共用一個經度帶，壓掉大西洋就會一起壓到巴西。
# 選擇是把權重放在遊戲會發生的地方。
WORLD_LON_BANDS = [
    (-180.0, -170.0, 0.40),  # 太平洋東緣
    (-170.0, -125.0, 0.50),  # 太平洋、阿拉斯加
    (-125.0,  -65.0, 1.15),  # 北美
    ( -65.0,  -32.0, 0.88),  # 南美、西大西洋
    ( -32.0,  -12.0, 0.45),  # 大西洋
    ( -12.0,   45.0, 1.95),  # 歐洲、西非、中東西緣
    (  45.0,   72.0, 1.00),  # 中東、中亞
    (  72.0,  100.0, 1.10),  # 印度、中國西部
    ( 100.0,  146.0, 1.70),  # 東亞、日本
    ( 146.0,  180.0, 0.40),  # 太平洋西緣
]

WORLD_LAT_BANDS = [
    ( -56.0, -35.0, 0.60),  # 南半球高緯
    ( -35.0,   0.0, 0.85),  # 南美南部、南非、澳洲
    (   0.0,  30.0, 1.00),  # 熱帶
    (  30.0,  66.0, 1.75),  # 歐洲、北歐、日本、美國、中國
    (  66.0,  72.0, 1.00),  # 北極圈：拉普蘭、摩爾曼斯克、阿拉斯加北岸
    (  72.0,  78.0, 0.30),  # 極地冰原
]

MAP_DEFS = [
    dict(map_id="world", cols=120, rows=76,
         lon_min=-180.0, lon_max=180.0, lat_max=78.0, lat_min=-56.0,
         lon_bands=WORLD_LON_BANDS, lat_bands=WORLD_LAT_BANDS),
    dict(map_id="europe", cols=54, rows=42,
         lon_min=-12.0, lon_max=42.0, lat_max=62.0, lat_min=34.0),
    dict(map_id="north_africa", cols=54, rows=36,
         lon_min=-14.0, lon_max=42.0, lat_max=40.0, lat_min=6.0),
    dict(map_id="sea_asia", cols=54, rows=40,
         lon_min=94.0, lon_max=152.0, lat_max=26.0, lat_min=-12.0),
    dict(map_id="east_europe", cols=48, rows=34,
         lon_min=14.0, lon_max=50.0, lat_max=60.0, lat_min=40.0),
    dict(map_id="andes", cols=40, rows=50,
         lon_min=-84.0, lon_max=-48.0, lat_max=10.0, lat_min=-44.0),
]


class BuiltMap:
    """一張產生好的地圖，外加省份索引，讓劇本可以用鍵去查 id。"""

    def __init__(self, map_id, grid, terrain, is_land, provinces):
        self.id = map_id
        self.grid = grid
        self.terrain = terrain
        self.is_land = is_land
        self.provinces = provinces           # [(key, tier, nation, tile)]
        self.dropped = []                    # [(key, 原因)]，放不下的省份
        self.rescued = 0                     # 靠錨點才站上陸地的城市數
        self.index_by_key = {p[0]: i for i, p in enumerate(provinces)}
        self.province_of = None              # 逐格的省份 id


def nearest_land_tile(grid, is_land, lon, lat, allowed=None):
    """
    把一個經緯度座標吸附到最近的陸地格。找不到就回 None。

    [allowed] 過濾候選格：城市只能落在自己那塊陸地上，否則福岡會被吸到
    對岸的釜山旁邊，只因為那一格在網格上比九州近。
    """
    best = None
    best_d = None
    for row in range(grid.rows):
        for col in range(grid.cols):
            i = grid.index(col, row)
            if not is_land[i] or (allowed is not None and not allowed(i)):
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

    # 沒被走到的陸地（沒有城市的離島）整座島歸給直線最近的種子。
    # 以島為單位而不是逐格：逐格分的話，西西里這種夾在兩國之間的島會被
    # 對半切給兩邊，憑空多出一條跨海的「國界」。
    for i in range(count):
        if not is_land[i] or owner[i] != -1:
            continue
        island = [i]
        owner[i] = -2
        head = 0
        while head < len(island):
            tile = island[head]
            head += 1
            for c, r in grid.neighbours(tile % grid.cols, tile // grid.cols):
                j = grid.index(c, r)
                if is_land[j] and owner[j] == -1:
                    owner[j] = -2
                    island.append(j)
        best, best_d = -1, None
        for tile in island:
            lon, lat = grid.lonlat(tile % grid.cols, tile // grid.cols)
            for pid, seed in enumerate(seeds):
                if seed is None:
                    continue
                slon, slat = grid.lonlat(seed % grid.cols, seed // grid.cols)
                d = (slon - lon) ** 2 + (slat - lat) ** 2
                if best_d is None or d < best_d:
                    best_d, best = d, pid
        for tile in island:
            owner[tile] = best
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


def build_map(map_id, cols, rows, lon_min, lon_max, lat_max, lat_min,
              lon_bands=None, lat_bands=None):
    grid = hexraster.Grid(cols, rows, lon_min, lon_max, lat_max, lat_min,
                          lon_bands, lat_bands)

    # 只收落在這張地圖範圍內的省份。
    margin = 0.0
    candidates = [
        p for p in places.PROVINCES
        if lon_min - margin <= p[1] <= lon_max + margin
        and lat_min - margin <= p[2] <= lat_max + margin
    ]

    # 城市座標先於海岸線：每座城市所在的格子一定是陸地。
    terrain, is_land, landmass, _anchors = hexraster.build_terrain(
        grid, [(p[1], p[2]) for p in candidates]
    )

    # 有多少城市是靠錨點才站上陸地的。這個數字是海岸線品質的量尺：
    # 突然從幾十跳到幾百，代表哪個多邊形被改壞了。
    land_polys = list(hexraster.geodata.LAND.values())
    sea_polys = list(hexraster.geodata.SEA.values())
    rescued = sum(
        1 for q in candidates
        if not (hexraster.in_any(q[1], q[2], land_polys)
                and not hexraster.in_any(q[1], q[2], sea_polys))
    )

    seeds = []
    provinces = []
    used = set()
    # 放不下的省份要講出來。這件事本來是靜悄悄發生的：臺灣在世界地圖上
    # 只有一格，兩座城搶同一格，高雄就從地圖上消失了，而產出的檔案看起來
    # 完全正常。省份數少一個，沒有任何一行輸出提到它。
    dropped = []
    for key, lon, lat, tier, nation, *_names in candidates:
        home = hexraster.landmass_of(lon, lat)
        tile, dist = nearest_land_tile(grid, is_land, lon, lat,
                                       lambda i, home=home: not hexraster.apart(landmass[i], home))
        if tile is None:
            dropped.append((key, "這張地圖上沒有陸地"))
            continue
        # 吸得太遠代表這座城市在這張地圖上其實沒有陸地可放，跳過。
        if dist > 36.0:
            dropped.append((key, "離最近的陸地 %.0f 度" % dist))
            continue
        if tile in used:
            # 同一格擠了兩座城：讓給先到的那一座，另一座往外挪一格。
            moved = False
            col, row = tile % cols, tile // cols
            for c, r in grid.neighbours(col, row):
                j = grid.index(c, r)
                if is_land[j] and j not in used and not hexraster.apart(landmass[j], home):
                    tile = j
                    moved = True
                    break
            if not moved:
                dropped.append((key, "同一格已被佔用，四周也沒有空的陸地"))
                continue
        used.add(tile)
        seeds.append(tile)
        provinces.append((key, tier, nation, tile))

    province_of = assign_provinces(grid, is_land, seeds)
    built = BuiltMap(map_id, grid, terrain, is_land, provinces)
    built.province_of = province_of
    built.dropped = dropped
    built.rescued = rescued
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
    # 只有涵蓋整個經度圈的地圖才環繞。區域地圖是平面的。
    if abs((built.grid.lon_max - built.grid.lon_min) - 360.0) < 0.001:
        lines.append("wrap x")
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


def write_conquest(built, scenario_id, name_key, desc_key, order, merge,
                   start_year, turtle=None, blocs=None, overrides=None,
                   renames=None, war_turns=None, start_month=None):
    """把 places 的國家分佈展開成一份征服劇本。"""
    owners = {}
    for pid, (key, _tier, nation, _tile) in enumerate(built.provinces):
        code = merge.get(nation, nation) if merge else nation
        # 逐省覆寫排在合併之後：香港不會因為它在中國境內就跟著中國走。
        if overrides:
            code = overrides.get(key, code)
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
    if start_month:
        lines.append("startMonth %d" % start_month)
    lines.append("")
    lines.append("[nations]")
    ordered = sorted(owners.keys(), key=lambda c: (-nation_funds[c], c))
    for code in ordered:
        english, _t, _s, colour, profile, _funds = nation_row(code)
        # 中立國的性格不是國情，是年代 —— 同一個西班牙在 1900 與 1939 的
        # 意願差很多，所以由劇本指定，而不是寫死在國家表裡。
        if turtle and code in turtle:
            profile = "TURTLE"
        funds = nation_funds[code]
        capital = max(owners[code], key=lambda pid: built.provinces[pid][1])
        lines.append("%s|%s|%s|%d|%s|%d|%s|%s|%s|%d" % (
            code, (renames or {}).get(code, "nation_" + code.lower()),
            colour, capital, profile, funds,
            ",".join(str(v) for v in tech_for(funds)),
            places.FLAGS.get(code, ""),
            bloc_of(code, blocs),
            (war_turns or {}).get(code, 1),
        ))
    wars = bloc_wars(blocs, set(ordered), war_turns)
    if wars:
        lines.append("")
        lines.append("[relations]")
        lines.extend(wars)
    lines.append("")
    lines.append("[owners]")
    for code in ordered:
        lines.append("%s: %s" % (code, compress_ids(owners[code])))
    lines.append("")
    lines.append("[units]")
    lines.extend(garrison_lines(built, owners, nation_funds))
    lines.append("")
    lines.append("[playable]")
    # 可選國家：有陣營的才能選。中立國不參戰、也沒有敵對陣營可打垮，
    # 選了它就是對著一張和平的地圖發呆。劇本沒分陣營的話才全部開放。
    belligerents = [code for code in ordered if bloc_of(code, blocs)]
    lines.append(",".join(belligerents or ordered))
    lines.append("")
    lines.append("[objectives]")
    lines.append("# 沒有列出目標 = 征服模式的預設條件（拿下地圖上八成的省份）")

    path = os.path.join(SCEN_DIR, scenario_id + ".scn")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    return path


def unaligned(blocs):
    """不在任何陣營裡的國家。它們是旁觀國，被宣戰之後也只會固守。"""
    members = {c for group in blocs.values() for c in group}
    return (set(places.NATIONS) | set(places.EXTRA_NATIONS)) - members


def bloc_of(code, blocs):
    """一個國家屬於哪個陣營；沒列到的是中立。"""
    if not blocs:
        return ""
    for name, members in blocs.items():
        if code in members:
            return name
    return ""


def bloc_wars(blocs, present, war_turns=None):
    """開局就在打的那些：不同陣營、而且雙方都在第 1 回合參戰。

    參戰回合晚於 1 的國家不寫進關係表 —— 它們的戰爭由遊戲在那一回合
    自己開啟，劇本檔只描述開局的樣子。
    """
    if not blocs:
        return []
    war_turns = war_turns or {}
    names = [n for n in blocs]
    lines = []
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            for x in blocs[a]:
                for y in blocs[b]:
                    if x not in present or y not in present:
                        continue
                    if war_turns.get(x, 1) > 1 or war_turns.get(y, 1) > 1:
                        continue
                    lines.append("%s %s WAR" % (x, y))
    return lines


def campaign_profile(code, mission):
    """關卡裡一個勢力的（顏色、資金、AI 性格）。

    演習陣營來自 scn.FACTIONS，真實國家直接沿用 places 的設定 —— 兩張表的
    欄位順序不同（FACTIONS 是資金在前、places 是性格在前），所以在這裡收斂
    成同一個形狀，呼叫端不必知道這件事。關卡可以用 funds／ai 覆寫，因為
    同一個國家在 1939 與 1985 的份量差很多。
    """
    if code in scn.FACTIONS:
        _e, _t, _s, colour, funds, profile = scn.FACTIONS[code]
    else:
        row = places.NATIONS.get(code) or places.EXTRA_NATIONS[code]
        _e, _t, _s, colour, profile, funds = row
    funds = mission.get("funds", {}).get(code, funds)
    profile = mission.get("ai", {}).get(code, profile)
    return colour, funds, profile


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

    # 地圖上沒被劇本點名的省份，交還給它們真正的主人，而且是中立的。
    #
    # 原本這些省份沒有主人：地圖邊上一整排沒有國旗的空城，AI 走過去就白拿。
    # 那不是中立國，那是無人區 —— 而歐洲在 1939 年沒有無人區。
    bystanders = set()
    claimed = set()
    for ids in owners.values():
        claimed.update(ids)
    merge = mission.get("merge", places.WW2_MERGE)
    overrides = mission.get("overrides", places.WW2_PROVINCE_OWNERS)
    for pid, (key, _tier, nation, _tile) in enumerate(built.provinces):
        if pid in claimed:
            continue
        code = overrides.get(key, merge.get(nation, nation))
        owners.setdefault(code, []).append(pid)
        if code not in mission["forces"]:
            bystanders.add(code)

    nation_funds = {code: campaign_profile(code, mission)[1] for code in owners}

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
    lines.append("chapter %s" % mission["chapter"])
    lines.append("route %s" % mission["route"])
    lines.append("turnLimit %d" % mission["turn_limit"])
    lines.append("startYear %d" % mission.get("year", 2026))
    lines.append("starTurns %d,%d" % tuple(mission["stars"]))
    lines.append("")
    lines.append("[nations]")
    for code in owners:
        colour, funds, profile = campaign_profile(code, mission)
        # 補進來的旁觀國一律固守：它們的作用是佔著地方，不是參戰。
        if code in bystanders:
            profile = "TURTLE"
        capital = max(owners[code], key=lambda pid: built.provinces[pid][1]) if owners[code] else -1
        lines.append("%s|%s|%s|%d|%s|%d|%s|%s|%s|%d" % (
            code,
            mission.get("renames", places.WW2_RENAMES).get(code, "nation_" + code.lower()),
            colour, capital, profile, funds,
            ",".join(str(v) for v in mission.get("tech", {}).get(code, [1, 1, 1, 0, 0, 1])),
            places.FLAGS.get(code, ""),
            bloc_of(code, mission["blocs"]),
            mission.get("entry", {}).get(code, 1),
        ))
    lines.append("")
    lines.append("[relations]")
    lines.extend(bloc_wars(mission["blocs"], set(owners), mission.get("entry")))
    lines.append("")
    lines.append("[owners]")
    for code, ids in owners.items():
        lines.append("%s: %s" % (code, compress_ids(ids)))
    lines.append("")
    lines.append("[units]")
    taken = set()
    lines.extend(campaign_units(built, owners, mission, taken))
    lines.extend(garrison_only(built, owners, bystanders, taken))
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


NAVAL_KINDS = ("DESTROYER", "CRUISER", "TRANSPORT_SHIP", "SUBMARINE",
               "CARRIER", "BATTLESHIP")


def land_pool(built, ids, rings=4):
    """一個國家全部領土裡可以站人的陸地格，由各省會同時往外展開。

    展開只走自己的省份，所以不會把開局部隊放到別人家裡；同時展開而不是
    一省一省填完，是為了讓部隊散在各個省會而不是全部擠在首都。
    """
    grid = built.grid
    owned = set(ids)
    order = []
    seen = set()
    frontier = []
    for pid in ids:
        capital = built.provinces[pid][3]
        if capital in seen:
            continue
        seen.add(capital)
        order.append(capital)
        frontier.append(capital)
    for _ring in range(rings):
        nxt = []
        for tile in frontier:
            col, row = tile % grid.cols, tile // grid.cols
            for c, r in grid.neighbours(col, row):
                j = grid.index(c, r)
                if j in seen:
                    continue
                seen.add(j)
                if built.province_of[j] not in owned:
                    continue
                order.append(j)
                nxt.append(j)
        frontier = nxt
    return [t for t in order if built.terrain[t] not in "~-"]


def water_pool(built, ids, rings=3):
    """國家近岸的海格，給開局的艦艇停泊。"""
    grid = built.grid
    owned = set(ids)
    seen = set()
    frontier = []
    order = []
    for tile in range(len(built.terrain)):
        if built.province_of[tile] in owned and built.terrain[tile] not in "~-":
            seen.add(tile)
            frontier.append(tile)
    for _ring in range(rings):
        nxt = []
        for tile in frontier:
            col, row = tile % grid.cols, tile // grid.cols
            for c, r in grid.neighbours(col, row):
                j = grid.index(c, r)
                if j in seen:
                    continue
                seen.add(j)
                if built.terrain[j] in "~-":
                    order.append(j)
                nxt.append(j)
        frontier = nxt
    return order


def garrison_only(built, owners, codes, taken):
    """旁觀國的守軍：每座城一支步兵。

    有城無兵的中立國等於一張邀請函 —— 玩家與 AI 都會順手拿走。給一支守軍，
    「繞過去還是打過去」才會是一個真的選擇。
    """
    lines = []
    for code in sorted(codes):
        for pid in owners.get(code, []):
            tile = built.provinces[pid][3]
            if tile in taken:
                continue
            taken.add(tile)
            col, row = tile % built.grid.cols, tile // built.grid.cols
            lines.append("%s|%d,%d|INFANTRY|1|-" % (code, col, row))
    return lines


def campaign_units(built, owners, mission, taken):
    """關卡的開局部隊由 mission 的編制表決定，好讓每一關教一件事。

    放不下的部隊會讓產生器直接失敗，不會默默消失 —— 這件事發生過：
    只有一座城的國家在舊的放置法下丟掉了整批空軍，關卡因此少了一半戰力，
    而產出的檔案看起來完全正常。
    """
    lines = []
    missing = []
    # 一格只容得下一支部隊，所以配置要跨國家共用同一份已佔用清單 ——
    # 各國的近岸海格會重疊，兩支艦隊擠進同一格的話劇本根本載不起來。
    for code, roster in mission["roster"].items():
        ids = owners.get(code, [])
        if not ids:
            continue
        # 自己的地方擺不下時，往同陣營的領土擺。非洲軍本來就是駐在
        # 義屬利比亞的 —— 盟友的港口對開局部署來說就是自己的港口。
        allied = []
        for other, members in mission["blocs"].items():
            if code not in members:
                continue
            for mate in members:
                if mate != code:
                    allied.extend(owners.get(mate, []))
        land = land_pool(built, ids) + land_pool(built, allied)
        water = water_pool(built, ids) + water_pool(built, allied)
        land_at = 0
        water_at = 0
        # 司令部先放，才會落在省會上 —— 它的加成是以自己為圓心算的。
        ordered = sorted(roster, key=lambda entry: entry[0] != "HEADQUARTERS")
        for kind, level, count in ordered:
            for _ in range(count):
                if kind in NAVAL_KINDS:
                    while water_at < len(water) and water[water_at] in taken:
                        water_at += 1
                    if water_at >= len(water):
                        missing.append("%s %s（沒有空的近岸海格）" % (code, kind))
                        continue
                    tile = water[water_at]
                    water_at += 1
                else:
                    while land_at < len(land) and land[land_at] in taken:
                        land_at += 1
                    if land_at >= len(land):
                        missing.append("%s %s（領土上沒有空格）" % (code, kind))
                        continue
                    tile = land[land_at]
                    land_at += 1
                taken.add(tile)
                col, row = tile % built.grid.cols, tile // built.grid.cols
                lines.append("%s|%d,%d|%s|%d|-" % (code, col, row, kind, level))
    if missing:
        raise SystemExit(
            "%s：以下部隊放不下，請縮小編制或多給幾座城 ——\n  %s"
            % (mission["id"], "\n  ".join(missing))
        )
    return lines


def main():
    os.makedirs(MAPS_DIR, exist_ok=True)
    os.makedirs(SCEN_DIR, exist_ok=True)

    built_maps = {}
    for definition in MAP_DEFS:
        built = build_map(**definition)
        built_maps[built.id] = built
        path = write_map(built)
        land = sum(1 for t in built.terrain if t not in "~-")
        print("map %-13s %3dx%-3d  provinces %3d  land %4d  -> %s"
              % (built.id, built.grid.cols, built.grid.rows,
                 len(built.provinces), land, os.path.relpath(path, ROOT)))
        if built.rescued:
            print("    海岸線內縮，靠城市錨點補救 %d 座城" % built.rescued)
        for key, reason in built.dropped:
            print("    - %s 放不下：%s" % (key, reason))

    world = built_maps["world"]
    errors = topology.check(world)
    if errors:
        print("世界地圖的拓撲檢查失敗（見 tools/topology.py）：", file=sys.stderr)
        for line in errors:
            print("    " + line, file=sys.stderr)
        sys.exit(1)

    print(write_conquest(world, "conquest_1939", "scn_conquest_1939",
                         "scn_conquest_1939_desc", 10, places.WW2_MERGE, 1939,
                         turtle=places.WW2_NEUTRALS, blocs=places.WW2_BLOCS,
                         overrides=places.WW2_PROVINCE_OWNERS,
                         renames=places.WW2_RENAMES,
                         war_turns=places.WW2_WAR_TURNS, start_month=9))
    print(write_conquest(world, "conquest_1943", "scn_conquest_1943",
                         "scn_conquest_1943_desc", 20, places.WW2_MERGE, 1943,
                         turtle=places.WW2_NEUTRALS, blocs=places.WW2_1943_BLOCS,
                         overrides=places.WW2_1943_PROVINCE_OWNERS,
                         renames=places.WW2_RENAMES, start_month=3))
    print(write_conquest(world, "conquest_1950", "scn_conquest_1950",
                         "scn_conquest_1950_desc", 30, places.COLD_WAR_1950_MERGE, 1950,
                         turtle=unaligned(places.COLD_WAR_1950_BLOCS),
                         blocs=places.COLD_WAR_1950_BLOCS,
                         overrides=places.COLD_WAR_1950_PROVINCE_OWNERS,
                         renames=places.COLD_WAR_RENAMES, start_month=1))
    print(write_conquest(world, "conquest_1980", "scn_conquest_1980",
                         "scn_conquest_1980_desc", 40, places.COLD_WAR_1980_MERGE, 1980,
                         turtle=unaligned(places.COLD_WAR_1980_BLOCS),
                         blocs=places.COLD_WAR_1980_BLOCS,
                         overrides=places.COLD_WAR_1980_PROVINCE_OWNERS,
                         renames=places.COLD_WAR_RENAMES, start_month=1))

    for mission in scn.CAMPAIGN:
        built = built_maps[mission["map"]]
        print(write_campaign(built, mission))


if __name__ == "__main__":
    main()
