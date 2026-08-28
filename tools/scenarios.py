# SPDX-FileCopyrightText: 2026 AcideFluorhydrique
# SPDX-License-Identifier: GPL-3.0-or-later
"""
戰役關卡的定義。

戰役刻意用**演習陣營**（藍軍／紅軍／綠軍）而不是真實國家：

  - 每一關都是為了教一件事（登陸、補給、山地、防禦、航艦），
    抽象陣營讓關卡設計可以完全服務教學目的，不必遷就任何史實；
  - 這是一款要進 F-Droid 的自由軟體，用虛構番號說故事，
    比把真實國家配對成敵我更乾淨，也不會讓任何人覺得被冒犯。

真實國家留給征服模式 —— 那裡是玩家自己選要跟誰打。
"""

# 代碼 → (English, 正體, 简体, 顏色, 開局資金, AI 性格)
FACTIONS = {
    "BLU": ("Blue Force",  "藍軍", "蓝军", "#4C7BA8", 900, "BALANCED"),
    "RED": ("Red Force",   "紅軍", "红军", "#A8494C", 900, "AGGRESSIVE"),
    "GRN": ("Green Force", "綠軍", "绿军", "#4C8C5C", 700, "TURTLE"),
}

CAMPAIGN = [
    {
        "id": "campaign_01_first_contact",
        "order": 1,
        "map": "europe",
        "player": "BLU",
        "turn_limit": 30,
        "stars": (12, 18),
        "forces": {
            "BLU": ["prov_london", "prov_manchester", "prov_edinburgh", "prov_dublin"],
            "RED": ["prov_paris", "prov_bordeaux", "prov_lyon", "prov_brussels"],
        },
        "wars": [("BLU", "RED")],
        "roster": {
            "BLU": [("INFANTRY", 2, 3), ("ARTILLERY", 1, 1), ("TRANSPORT_SHIP", 1, 2),
                    ("MARINE", 2, 2), ("DESTROYER", 1, 1)],
            "RED": [("INFANTRY", 1, 4), ("ANTI_TANK", 1, 1)],
        },
        "objectives": [("CAPTURE_PROVINCES", ["prov_paris", "prov_brussels"])],
    },
    {
        "id": "campaign_02_desert_supply",
        "order": 2,
        "map": "north_africa",
        "player": "BLU",
        "turn_limit": 35,
        "stars": (16, 24),
        "forces": {
            "BLU": ["prov_cairo", "prov_alexandria", "prov_aswan"],
            "RED": ["prov_tripoli", "prov_tunis", "prov_algiers"],
            "GRN": ["prov_khartoum", "prov_timbuktu"],
        },
        "wars": [("BLU", "RED")],
        "roster": {
            "BLU": [("INFANTRY", 2, 3), ("ARMOUR", 2, 2), ("SUPPLY_TRUCK", 1, 2),
                    ("ARTILLERY", 1, 1), ("RECON", 1, 1)],
            "RED": [("INFANTRY", 2, 3), ("ANTI_TANK", 2, 2), ("ARTILLERY", 1, 1)],
            "GRN": [("INFANTRY", 1, 2)],
        },
        "objectives": [("CAPTURE_PROVINCES", ["prov_tripoli", "prov_tunis"])],
    },
    {
        "id": "campaign_03_island_chain",
        "order": 3,
        "map": "sea_asia",
        "player": "BLU",
        "turn_limit": 40,
        "stars": (20, 30),
        "forces": {
            "BLU": ["prov_manila", "prov_cebu", "prov_taipei", "prov_kaohsiung"],
            "RED": ["prov_jakarta", "prov_surabaya", "prov_singapore", "prov_kuala_lumpur"],
            "GRN": ["prov_bangkok", "prov_ho_chi_minh"],
        },
        "wars": [("BLU", "RED")],
        "roster": {
            "BLU": [("MARINE", 2, 3), ("TRANSPORT_SHIP", 1, 3), ("CARRIER", 2, 1),
                    ("FIGHTER", 2, 2), ("DESTROYER", 2, 2), ("INFANTRY", 1, 2)],
            "RED": [("INFANTRY", 2, 4), ("ANTI_AIR", 2, 2), ("SUBMARINE", 2, 2),
                    ("ARTILLERY", 1, 1)],
            "GRN": [("INFANTRY", 1, 2)],
        },
        "objectives": [("CAPTURE_PROVINCES", ["prov_singapore", "prov_jakarta"])],
    },
    {
        "id": "campaign_04_defence_in_depth",
        "order": 4,
        "map": "east_europe",
        "player": "BLU",
        "turn_limit": 32,
        "stars": (26, 30),
        "forces": {
            "BLU": ["prov_kyiv", "prov_kharkiv", "prov_odesa", "prov_minsk"],
            "RED": ["prov_warsaw", "prov_krakow", "prov_budapest", "prov_bucharest"],
        },
        "wars": [("BLU", "RED")],
        "tech": {"BLU": [2, 0, 2, 0, 0, 2], "RED": [1, 3, 1, 2, 0, 1]},
        "roster": {
            "BLU": [("INFANTRY", 3, 4), ("ANTI_TANK", 2, 3), ("ARTILLERY", 2, 2),
                    ("HEADQUARTERS", 1, 1), ("SUPPLY_TRUCK", 1, 1)],
            "RED": [("ARMOUR", 3, 4), ("INFANTRY", 2, 3), ("ROCKET", 2, 1),
                    ("BOMBER", 2, 1)],
        },
        "objectives": [("HOLD_PROVINCES", ["prov_kyiv", "prov_kharkiv"], 25)],
    },
    {
        "id": "campaign_05_mountain_offensive",
        "order": 5,
        "map": "andes",
        "player": "BLU",
        "turn_limit": 40,
        "stars": (22, 32),
        "forces": {
            "BLU": ["prov_lima", "prov_cusco", "prov_quito", "prov_guayaquil"],
            "RED": ["prov_santiago", "prov_la_paz", "prov_antofagasta"],
            "GRN": ["prov_bogota", "prov_medellin"],
        },
        "wars": [("BLU", "RED")],
        "roster": {
            "BLU": [("MOUNTAIN_INFANTRY", 3, 4), ("ARTILLERY", 2, 2),
                    ("SUPPLY_TRUCK", 1, 2), ("RECON", 2, 1), ("FIGHTER", 2, 1)],
            "RED": [("INFANTRY", 3, 4), ("ANTI_AIR", 2, 2), ("ARTILLERY", 2, 2)],
            "GRN": [("INFANTRY", 1, 2)],
        },
        "objectives": [("CAPTURE_PROVINCES", ["prov_santiago", "prov_la_paz"])],
    },
]
