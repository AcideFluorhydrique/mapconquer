# SPDX-FileCopyrightText: 2026 AcideFluorhydrique
# SPDX-License-Identifier: GPL-3.0-or-later
"""
戰役關卡的定義。

戰役分成三章：二戰歐洲、二戰太平洋、冷戰，每一章兩條陣營路線。
同一場仗常常兩邊都能打 —— 兵力與地圖沿用，玩家、目標與星等換掉。

用真實國家而不是抽象番號，是因為識別
本來就該由旗幟承擔 —— 一張地圖上十幾個勢力，光靠顏色分不出誰是誰，
而「🇩🇪 對 🇵🇱」不需要任何說明就看得懂。

陣營就是原版的那兩邊：二戰是軸心對盟國，冷戰的代理人戰爭是西方對東方，
1985 年是北約對華約。交戰關係由陣營推導，不逐對手寫 —— 手寫的版本
遲早會出現「德國對法國宣戰但忘了對比利時宣戰」這種漏。

每一關都挑一件事來教，並且盡量讓史實本身就是那件事的最佳範例：
1939 波蘭是寡不敵眾的縱深防禦，1940 法國是裝甲突破，1942 南洋是航艦與
兩棲，1956 蘇伊士是空降加防空，1968 印度支那是拿防空吃制空。

中立國照史實擺進去（瑞士、瑞典、西班牙、土耳其、愛爾蘭、奧地利）：
它們有城市、有守軍、不宣戰，但擋在路上 —— 繞過去還是打過去是玩家的選擇。
"""


import copy

import places

# 虛構陣營。目前是空的：每一關都用真實國家，識別交給國旗。
# 這張表留著是因為 genworld 的解析路徑走它 —— 將來若要做純教學關，
# 在這裡補一筆就行，不必再改產生器。
#
# 代碼 → (English, 正體, 简体, 顏色, 開局資金, AI 性格)
FACTIONS = {}

NATO_85 = ["DEU", "FRA", "GBR", "NLD", "BEL", "DNK"]
PACT_85 = ["RUS", "POL", "CZE", "HUN", "DDR"]

CAMPAIGN = [
    # ------------------------------------------------------------------
    # 第一章：二戰。
    # ------------------------------------------------------------------
    {
        # 教「守」：兵力永遠不夠，所以要靠地形、築壕與撤退換時間。
        "id": "campaign_ww2_01_poland",
        "chapter": "ww2_europe",
        "route": "ALLIES",
        "order": 111,
        "map": "europe",
        "year": 1939,
        "player": "POL",
        "turn_limit": 25,
        # 守城關卡的勝利正好發生在守住的那一回合，所以三星門檻不能早於它 ——
        # 原本是 18，比守住的第 20 回合還早，三星根本拿不到。
        "stars": (20, 23),
        "forces": {
            "POL": ["prov_warsaw", "prov_krakow"],
            "DEU": ["prov_berlin", "prov_hamburg", "prov_munich", "prov_cologne",
                    "prov_prague", "prov_vienna"],
            "RUS": ["prov_moscow", "prov_minsk", "prov_riga"],
            "SWE": ["prov_stockholm"],
        },
        "blocs": {"AXIS": ["DEU"], "ALLIES": ["POL"]},
        "funds": {"POL": 700, "DEU": 1500, "RUS": 600, "SWE": 400},
        "ai": {"DEU": "AGGRESSIVE", "RUS": "TURTLE", "SWE": "TURTLE"},
        "tech": {"DEU": [3, 3, 2, 1, 0, 2], "POL": [1, 1, 1, 0, 0, 1]},
        "roster": {
            "POL": [("INFANTRY", 2, 5), ("ANTI_TANK", 2, 2), ("ARTILLERY", 1, 2),
                    ("RECON", 1, 1), ("ANTI_AIR", 1, 1)],
            "DEU": [("ARMOUR", 2, 4), ("INFANTRY", 2, 5), ("BOMBER", 2, 2),
                    ("FIGHTER", 2, 2), ("ARTILLERY", 1, 2), ("RECON", 1, 2)],
            "RUS": [("INFANTRY", 1, 3)],
            "SWE": [("INFANTRY", 1, 2)],
        },
        "objectives": [("HOLD_PROVINCES", ["prov_warsaw"], 20)],
    },
    {
        # 教「攻」：裝甲突破加突擊連鎖，一回合打穿一條線。
        "id": "campaign_ww2_02_france",
        "chapter": "ww2_europe",
        "route": "AXIS",
        "order": 102,
        "map": "europe",
        "year": 1940,
        "player": "DEU",
        "turn_limit": 30,
        "stars": (16, 22),
        "forces": {
            "DEU": ["prov_berlin", "prov_hamburg", "prov_munich", "prov_cologne"],
            "FRA": ["prov_paris", "prov_lyon", "prov_bordeaux"],
            "GBR": ["prov_london", "prov_manchester", "prov_edinburgh"],
            "BEL": ["prov_brussels"],
            "NLD": ["prov_amsterdam"],
            "CHE": ["prov_zurich"],
            "ESP": ["prov_madrid", "prov_barcelona"],
        },
        "blocs": {"AXIS": ["DEU"], "ALLIES": ["FRA", "GBR", "BEL", "NLD"]},
        "funds": {"DEU": 1600, "FRA": 1100, "GBR": 1000, "BEL": 400, "NLD": 400,
                  "CHE": 500, "ESP": 500},
        "ai": {"FRA": "TURTLE", "GBR": "BALANCED", "CHE": "TURTLE", "ESP": "TURTLE"},
        "tech": {"DEU": [3, 3, 2, 1, 0, 2], "FRA": [2, 1, 2, 0, 0, 1]},
        "roster": {
            "DEU": [("ARMOUR", 3, 5), ("INFANTRY", 2, 5), ("RECON", 2, 2),
                    ("BOMBER", 2, 2), ("FIGHTER", 2, 2), ("SUPPLY_TRUCK", 1, 2),
                    ("HEADQUARTERS", 1, 1)],
            "FRA": [("INFANTRY", 2, 5), ("ANTI_TANK", 2, 3), ("ARTILLERY", 2, 2),
                    ("ARMOUR", 1, 2)],
            "GBR": [("INFANTRY", 2, 3), ("DESTROYER", 2, 2), ("CRUISER", 2, 1),
                    ("FIGHTER", 2, 2)],
            "BEL": [("INFANTRY", 1, 2), ("ANTI_TANK", 1, 1)],
            "NLD": [("INFANTRY", 1, 2)],
            "CHE": [("MOUNTAIN_INFANTRY", 2, 2), ("ANTI_AIR", 1, 1)],
            "ESP": [("INFANTRY", 1, 2)],
        },
        "objectives": [("CAPTURE_PROVINCES", ["prov_paris", "prov_brussels"])],
    },
    {
        # 教「補給」：沙漠上沒有掩蔽，也沒有中途補給，距離才是敵人。
        "id": "campaign_ww2_03_desert",
        "chapter": "ww2_europe",
        "route": "ALLIES",
        "order": 113,
        "map": "north_africa",
        "year": 1942,
        "player": "GBR",
        "turn_limit": 35,
        "stars": (20, 28),
        "forces": {
            "GBR": ["prov_cairo", "prov_alexandria", "prov_aswan", "prov_khartoum"],
            "ITA": ["prov_tripoli"],
            "DEU": ["prov_tunis"],
            "TUR": ["prov_ankara", "prov_izmir"],
            "ESP": ["prov_seville", "prov_casablanca"],
        },
        "blocs": {"AXIS": ["ITA", "DEU"], "ALLIES": ["GBR"]},
        "funds": {"GBR": 1300, "ITA": 800, "DEU": 1000, "TUR": 500, "ESP": 500},
        "ai": {"DEU": "AGGRESSIVE", "ITA": "BALANCED", "TUR": "TURTLE", "ESP": "TURTLE"},
        "roster": {
            "GBR": [("INFANTRY", 2, 4), ("ARMOUR", 2, 3), ("SUPPLY_TRUCK", 1, 3),
                    ("ARTILLERY", 2, 2), ("RECON", 2, 2), ("FIGHTER", 2, 1),
                    ("DESTROYER", 2, 1)],
            "ITA": [("INFANTRY", 2, 4), ("ANTI_TANK", 2, 2), ("ARTILLERY", 1, 2)],
            "DEU": [("ARMOUR", 3, 3), ("ANTI_TANK", 2, 2), ("RECON", 2, 1),
                    ("SUPPLY_TRUCK", 1, 1)],
            "TUR": [("INFANTRY", 1, 2)],
            "ESP": [("INFANTRY", 1, 2)],
        },
        "objectives": [("CAPTURE_PROVINCES", ["prov_tripoli", "prov_tunis"])],
    },
    {
        # 教「海空」：這裡沒有一個目標走得到，制空與航艦決定登陸能不能成立。
        "id": "campaign_ww2_04_south_seas",
        "chapter": "ww2_pacific",
        "route": "AXIS",
        "order": 201,
        "map": "sea_asia",
        "year": 1942,
        "player": "JPN",
        "turn_limit": 40,
        "stars": (22, 30),
        "forces": {
            "JPN": ["prov_taipei", "prov_kaohsiung", "prov_hanoi"],
            "USA": ["prov_manila", "prov_cebu"],
            "GBR": ["prov_singapore", "prov_kuala_lumpur", "prov_yangon",
                    "prov_mandalay", "prov_hong_kong"],
            "NLD": ["prov_jakarta", "prov_surabaya", "prov_medan"],
            "THA": ["prov_bangkok", "prov_chiang_mai"],
        },
        "blocs": {"AXIS": ["JPN"], "ALLIES": ["USA", "GBR", "NLD"]},
        "funds": {"JPN": 1700, "USA": 1100, "GBR": 1100, "NLD": 600, "THA": 500},
        "ai": {"USA": "BALANCED", "GBR": "TURTLE", "NLD": "TURTLE", "THA": "TURTLE"},
        "tech": {"JPN": [2, 1, 3, 3, 0, 2]},
        "roster": {
            "JPN": [("CARRIER", 3, 2), ("FIGHTER", 3, 3), ("BOMBER", 2, 2),
                    ("TRANSPORT_SHIP", 2, 3), ("MARINE", 2, 4), ("DESTROYER", 2, 2),
                    ("CRUISER", 2, 1), ("INFANTRY", 2, 2)],
            "USA": [("INFANTRY", 2, 3), ("ANTI_AIR", 2, 2), ("SUBMARINE", 2, 2),
                    ("DESTROYER", 2, 1)],
            "GBR": [("INFANTRY", 2, 4), ("BATTLESHIP", 2, 1), ("DESTROYER", 2, 2),
                    ("ANTI_AIR", 1, 2), ("FIGHTER", 1, 1)],
            "NLD": [("INFANTRY", 1, 3), ("DESTROYER", 1, 1), ("SUBMARINE", 1, 1)],
            "THA": [("INFANTRY", 1, 2)],
        },
        "objectives": [("CAPTURE_PROVINCES", ["prov_singapore", "prov_manila"])],
    },
    {
        # 教「反攻」：兵力終於夠了，但對手的品質更高 —— 用數量換包圍。
        "id": "campaign_ww2_05_east_front",
        "chapter": "ww2_europe",
        "route": "ALLIES",
        "order": 114,
        "map": "east_europe",
        "year": 1943,
        "player": "RUS",
        "turn_limit": 40,
        "stars": (24, 32),
        "forces": {
            "RUS": ["prov_moscow", "prov_petersburg", "prov_nizhny", "prov_kazan",
                    "prov_volgograd", "prov_rostov"],
            "DEU": ["prov_warsaw", "prov_krakow", "prov_riga", "prov_minsk",
                    "prov_kyiv", "prov_odesa", "prov_kharkiv"],
            "TUR": ["prov_istanbul"],
            "SWE": ["prov_stockholm"],
        },
        "blocs": {"AXIS": ["DEU"], "ALLIES": ["RUS"]},
        "funds": {"RUS": 1800, "DEU": 1600, "TUR": 500, "SWE": 400},
        "ai": {"DEU": "OPPORTUNIST", "TUR": "TURTLE", "SWE": "TURTLE"},
        "tech": {"RUS": [2, 2, 1, 0, 0, 2], "DEU": [3, 3, 2, 1, 0, 2]},
        "roster": {
            "RUS": [("INFANTRY", 2, 6), ("ARMOUR", 2, 4), ("ARTILLERY", 2, 3),
                    ("ROCKET", 2, 2), ("ANTI_TANK", 2, 2), ("SUPPLY_TRUCK", 1, 2),
                    ("FIGHTER", 2, 2), ("HEADQUARTERS", 1, 1)],
            "DEU": [("ARMOUR", 3, 4), ("INFANTRY", 3, 4), ("ANTI_TANK", 3, 2),
                    ("ARTILLERY", 2, 2), ("FIGHTER", 2, 2), ("BOMBER", 2, 1)],
            "TUR": [("INFANTRY", 1, 2)],
            "SWE": [("INFANTRY", 1, 2)],
        },
        "objectives": [("CAPTURE_PROVINCES", ["prov_kyiv", "prov_kharkiv"])],
    },

    # ------------------------------------------------------------------
    # 第二章：冷戰。
    # ------------------------------------------------------------------
    {
        # 教「防空」：對手從天上來，地面守得再好也沒用。
        "id": "campaign_cw_01_suez",
        "chapter": "cold_war",
        "route": "EAST",
        "order": 301,
        "map": "north_africa",
        "year": 1956,
        "player": "EGY",
        "turn_limit": 30,
        "stars": (25, 28),
        "forces": {
            "EGY": ["prov_cairo", "prov_alexandria", "prov_aswan"],
            # 1956 年摩洛哥與突尼西亞已經獨立，法國在北非只剩阿爾及利亞。
            "FRA": ["prov_algiers", "prov_tamanrasset"],
            "ISR": ["prov_jerusalem"],
            "SAU": ["prov_jeddah"],
        },
        "blocs": {"WEST": ["FRA", "ISR"], "EAST": ["EGY"]},
        # 沒點名的省份照 1956 年的樣子補：原本沿用 1939 的合併，衣索比亞會變成義大利的。
        "merge": places.COLD_WAR_1956_MERGE,
        "overrides": places.COLD_WAR_1950_PROVINCE_OWNERS,
        "renames": places.COLD_WAR_RENAMES,
        "funds": {"EGY": 1100, "FRA": 1400, "ISR": 900, "SAU": 500},
        "ai": {"FRA": "AGGRESSIVE", "ISR": "AGGRESSIVE", "SAU": "TURTLE"},
        "tech": {"FRA": [2, 2, 3, 2, 0, 2], "EGY": [1, 1, 1, 3, 0, 1]},
        "roster": {
            "EGY": [("INFANTRY", 2, 5), ("ANTI_AIR", 2, 3), ("ARMOUR", 2, 2),
                    ("ARTILLERY", 2, 2), ("ANTI_TANK", 2, 2)],
            "FRA": [("BOMBER", 3, 2), ("FIGHTER", 3, 2), ("AIR_TRANSPORT", 2, 2),
                    ("MARINE", 3, 3), ("TRANSPORT_SHIP", 2, 2), ("CRUISER", 2, 1)],
            "ISR": [("ARMOUR", 3, 3), ("INFANTRY", 2, 3), ("RECON", 2, 2)],
            "SAU": [("INFANTRY", 1, 2)],
        },
        "objectives": [("HOLD_PROVINCES", ["prov_cairo", "prov_alexandria"], 25)],
    },
    {
        # 教「不對稱」：對手每一項數據都比你好，但他的補給線比你長。
        "id": "campaign_cw_02_indochina",
        "chapter": "cold_war",
        "route": "EAST",
        "order": 302,
        "map": "sea_asia",
        "year": 1968,
        "player": "VNM",
        "turn_limit": 45,
        "stars": (26, 36),
        "forces": {
            "VNM": ["prov_hanoi"],
            "USA": ["prov_ho_chi_minh"],
            "THA": ["prov_bangkok", "prov_chiang_mai"],
            "KHM": ["prov_phnom_penh"],
        },
        "blocs": {"WEST": ["USA", "THA"], "EAST": ["VNM"]},
        "merge": places.COLD_WAR_1980_MERGE,
        "overrides": places.COLD_WAR_1980_PROVINCE_OWNERS,
        "renames": places.COLD_WAR_RENAMES,
        "funds": {"VNM": 1200, "USA": 2000, "THA": 600, "KHM": 400},
        "ai": {"USA": "AGGRESSIVE", "THA": "TURTLE", "KHM": "TURTLE"},
        "tech": {"USA": [3, 3, 3, 2, 0, 3], "VNM": [1, 1, 1, 3, 0, 1]},
        "roster": {
            "VNM": [("INFANTRY", 2, 7), ("ANTI_AIR", 3, 3), ("ARTILLERY", 2, 2),
                    ("ANTI_TANK", 2, 2), ("SUPPLY_TRUCK", 1, 2),
                    ("MOUNTAIN_INFANTRY", 2, 2)],
            "USA": [("ARMOUR", 3, 3), ("INFANTRY", 3, 4), ("FIGHTER", 3, 3),
                    ("BOMBER", 3, 2), ("AIR_TRANSPORT", 2, 2), ("CARRIER", 3, 1),
                    ("DESTROYER", 2, 2), ("HEADQUARTERS", 2, 1)],
            "THA": [("INFANTRY", 1, 2)],
            "KHM": [("INFANTRY", 1, 1)],
        },
        "objectives": [("CAPTURE_PROVINCES", ["prov_ho_chi_minh"])],
    },
    {
        # 教「聯軍」：手上有六個國家，但只有一個能建軍 —— 學會用盟友換時間。
        "id": "campaign_cw_03_central_front",
        "chapter": "cold_war",
        "route": "WEST",
        "order": 313,
        "map": "europe",
        "year": 1985,
        "player": "DEU",
        "turn_limit": 35,
        "stars": (30, 33),
        "forces": {
            # 柏林在東德。原本整省算西德的，那是在沒有東德可用的時候將就的。
            "DEU": ["prov_hamburg", "prov_munich", "prov_cologne"],
            "FRA": ["prov_paris", "prov_lyon", "prov_bordeaux"],
            "GBR": ["prov_london", "prov_manchester", "prov_edinburgh"],
            "NLD": ["prov_amsterdam"],
            "BEL": ["prov_brussels"],
            "DNK": ["prov_copenhagen"],
            "RUS": ["prov_moscow", "prov_petersburg", "prov_rostov"],
            "POL": ["prov_warsaw", "prov_krakow"],
            "CZE": ["prov_prague"],
            "HUN": ["prov_budapest"],
            "DDR": ["prov_berlin"],
            "CHE": ["prov_zurich"],
            "AUT": ["prov_vienna"],
            "SWE": ["prov_stockholm"],
            "IRL": ["prov_dublin"],
        },
        "blocs": {"NATO": NATO_85, "PACT": PACT_85},
        "merge": places.COLD_WAR_1980_MERGE,
        "overrides": places.COLD_WAR_1980_PROVINCE_OWNERS,
        "renames": places.COLD_WAR_RENAMES,
        "funds": {"DEU": 1600, "FRA": 1200, "GBR": 1200, "NLD": 500, "BEL": 500,
                  "DNK": 500, "DDR": 600, "RUS": 2200, "POL": 800, "CZE": 600, "HUN": 500,
                  "CHE": 500, "AUT": 400, "SWE": 500, "IRL": 300},
        "ai": {"RUS": "AGGRESSIVE", "POL": "AGGRESSIVE", "CZE": "BALANCED",
               "HUN": "BALANCED", "DDR": "BALANCED", "FRA": "BALANCED", "GBR": "BALANCED",
               "CHE": "TURTLE", "AUT": "TURTLE", "SWE": "TURTLE", "IRL": "TURTLE"},
        "tech": {"RUS": [3, 3, 3, 2, 0, 3], "DEU": [3, 2, 3, 3, 0, 3]},
        "roster": {
            "DEU": [("ANTI_TANK", 3, 4), ("ARMOUR", 3, 3), ("INFANTRY", 3, 3),
                    ("ARTILLERY", 3, 2), ("ANTI_AIR", 3, 2), ("HEADQUARTERS", 2, 1)],
            "FRA": [("ARMOUR", 3, 2), ("ARTILLERY", 3, 1), ("FIGHTER", 3, 1)],
            "GBR": [("ARMOUR", 3, 2), ("FIGHTER", 3, 2), ("DESTROYER", 3, 1)],
            "NLD": [("INFANTRY", 2, 2)],
            "BEL": [("INFANTRY", 2, 2)],
            "DNK": [("INFANTRY", 2, 1), ("ANTI_AIR", 2, 1)],
            "RUS": [("ARMOUR", 3, 6), ("INFANTRY", 3, 4), ("ROCKET", 3, 3),
                    ("ANTI_AIR", 3, 2), ("FIGHTER", 3, 2), ("BOMBER", 3, 1),
                    ("SUPPLY_TRUCK", 2, 2)],
            "POL": [("ARMOUR", 2, 3), ("INFANTRY", 2, 3)],
            "CZE": [("INFANTRY", 2, 2), ("ARTILLERY", 2, 1)],
            "HUN": [("INFANTRY", 2, 2)],
            "DDR": [("ARMOUR", 2, 2), ("INFANTRY", 2, 2)],
            "CHE": [("MOUNTAIN_INFANTRY", 3, 2), ("ANTI_AIR", 2, 1)],
            "AUT": [("INFANTRY", 2, 1)],
            "SWE": [("INFANTRY", 2, 2)],
            "IRL": [("INFANTRY", 1, 1)],
        },
        "objectives": [("HOLD_PROVINCES", ["prov_hamburg", "prov_munich", "prov_cologne"], 30)],
    },
]


# ----------------------------------------------------------------------
# 1944 菲律賓：太平洋這一章原本每條路線只有一關。
# ----------------------------------------------------------------------
PHILIPPINES = {
    # 教「跳島」：不必把每一座島都拿下，拿下切斷敵人航線的那幾座，其餘的會自己餓死。
    # 美軍 1944 年的跳板是新幾內亞與摩鹿加群島，地圖上最近的兩個省是摩士比港與望加錫。
    "id": "campaign_ww2_06_philippines",
    "chapter": "ww2_pacific",
    "route": "ALLIES",
    "order": 212,
    "map": "sea_asia",
    "year": 1944,
    "player": "USA",
    "turn_limit": 40,
    "stars": (24, 32),
    "forces": {
        "USA": ["prov_port_moresby", "prov_makassar"],
        "JPN": ["prov_manila", "prov_cebu", "prov_jakarta", "prov_surabaya", "prov_medan",
                "prov_banjarmasin", "prov_kota_kinabalu", "prov_kuala_lumpur",
                "prov_singapore", "prov_yangon", "prov_mandalay", "prov_hanoi",
                "prov_ho_chi_minh", "prov_phnom_penh", "prov_taipei", "prov_kaohsiung",
                "prov_hong_kong", "prov_guangzhou"],
        "THA": ["prov_bangkok", "prov_chiang_mai"],
    },
    "blocs": {"AXIS": ["JPN", "THA"], "ALLIES": ["USA"]},
    "funds": {"USA": 2000, "JPN": 1400, "THA": 400},
    "ai": {"JPN": "BALANCED", "THA": "TURTLE"},
    "tech": {"USA": [3, 3, 3, 3, 0, 3], "JPN": [2, 2, 2, 3, 0, 2]},
    "roster": {
        "USA": [("CARRIER", 3, 2), ("FIGHTER", 3, 2), ("BOMBER", 3, 1),
                ("TRANSPORT_SHIP", 2, 3), ("MARINE", 3, 3), ("BATTLESHIP", 3, 1),
                ("DESTROYER", 2, 3), ("SUBMARINE", 2, 2)],
        "JPN": [("INFANTRY", 2, 6), ("ANTI_AIR", 2, 3), ("ARTILLERY", 2, 2),
                ("DESTROYER", 2, 2), ("CRUISER", 2, 1), ("FIGHTER", 2, 2),
                ("SUBMARINE", 2, 1)],
        "THA": [("INFANTRY", 1, 2)],
    },
    "objectives": [("CAPTURE_PROVINCES", ["prov_manila", "prov_cebu"])],
}
CAMPAIGN.append(PHILIPPINES)


# ----------------------------------------------------------------------
# 同一場仗換邊打。
# ----------------------------------------------------------------------
BY_ID = {m["id"]: m for m in CAMPAIGN}


def flipped(base_id, **changes):
    """沿用兵力與地圖，換掉玩家、目標、星等與路線。

    AI 性格要跟著補：原本由玩家操作的那一邊現在換電腦打，而它在原關卡裡
    沒有設定性格。守城關卡的三星門檻就是守住的那一回合 —— 勝利只會在那一刻發生。
    """
    mission = copy.deepcopy(BY_ID[base_id])
    ai = changes.pop("ai", None)
    mission.update(changes)
    if ai:
        mission["ai"] = dict(mission.get("ai", {}), **ai)
    return mission


CAMPAIGN += [
    flipped("campaign_ww2_01_poland", id="campaign_ww2_01_poland_axis",
            route="AXIS", order=101, player="DEU", turn_limit=20, stars=(12, 16),
            objectives=[("CAPTURE_PROVINCES", ["prov_warsaw", "prov_krakow"])],
            ai={"POL": "TURTLE"}),
    flipped("campaign_ww2_02_france", id="campaign_ww2_02_france_allies",
            route="ALLIES", order=112, player="FRA", turn_limit=26, stars=(20, 23),
            objectives=[("HOLD_PROVINCES", ["prov_paris"], 20)],
            ai={"DEU": "AGGRESSIVE"}),
    flipped("campaign_ww2_03_desert", id="campaign_ww2_03_desert_axis",
            route="AXIS", order=103, player="DEU", turn_limit=38, stars=(24, 32),
            objectives=[("CAPTURE_PROVINCES", ["prov_alexandria", "prov_cairo"])],
            ai={"GBR": "BALANCED"}),
    flipped("campaign_ww2_05_east_front", id="campaign_ww2_05_east_front_axis",
            route="AXIS", order=104, player="DEU", turn_limit=34, stars=(28, 31),
            objectives=[("HOLD_PROVINCES", ["prov_kyiv", "prov_kharkiv"], 28)],
            ai={"RUS": "AGGRESSIVE"}),
    flipped("campaign_ww2_04_south_seas", id="campaign_ww2_04_south_seas_allies",
            route="ALLIES", order=211, player="GBR", turn_limit=28, stars=(22, 25),
            objectives=[("HOLD_PROVINCES", ["prov_singapore"], 22)],
            ai={"JPN": "AGGRESSIVE"}),
    flipped("campaign_ww2_06_philippines", id="campaign_ww2_06_philippines_axis",
            route="AXIS", order=202, player="JPN", turn_limit=32, stars=(26, 29),
            objectives=[("HOLD_PROVINCES", ["prov_manila", "prov_cebu"], 26)],
            ai={"USA": "AGGRESSIVE"}),
    flipped("campaign_cw_01_suez", id="campaign_cw_01_suez_west",
            route="WEST", order=311, player="FRA", turn_limit=30, stars=(18, 24),
            objectives=[("CAPTURE_PROVINCES", ["prov_alexandria", "prov_cairo"])],
            ai={"EGY": "TURTLE"}),
    flipped("campaign_cw_02_indochina", id="campaign_cw_02_indochina_west",
            route="WEST", order=312, player="USA", turn_limit=36, stars=(30, 33),
            objectives=[("HOLD_PROVINCES", ["prov_ho_chi_minh"], 30)],
            ai={"VNM": "AGGRESSIVE"}),
    flipped("campaign_cw_03_central_front", id="campaign_cw_03_central_front_pact",
            route="EAST", order=303, player="RUS", turn_limit=35, stars=(22, 30),
            objectives=[("CAPTURE_PROVINCES", ["prov_hamburg", "prov_munich"])],
            ai={"DEU": "BALANCED"}),
]
