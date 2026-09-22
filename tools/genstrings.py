#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AcideFluorhydrique
# SPDX-License-Identifier: GPL-3.0-or-later
"""
產生三份 strings.xml。

    python3 tools/genstrings.py

三個語系寫在同一張表裡，而不是三個檔案各自維護：
漏翻譯在這裡是「元組長度不對」的即時錯誤，而不是要等到跑起來
才看到畫面上冒出一個 prov_xxx。省份與國家的譯名直接取自 places.py，
所以地圖與翻譯永遠是同步的。
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import places
import scenarios as scn

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

# key -> (English, 正體中文, 简体中文)
UI = {
    "app_name": ("MapConquer", "寰宇征服", "寰宇征服"),
    "menu_tagline": ("Hex-grid grand strategy, free and open",
                     "六角格大戰略，自由且開放",
                     "六角格大战略，自由且开放"),
    "menu_continue": ("Continue", "繼續遊戲", "继续游戏"),
    "menu_continue_summary": ("%1$s · turn %2$d", "%1$s · 第 %2$d 回合", "%1$s · 第 %2$d 回合"),
    "menu_campaign": ("Campaign", "戰役", "战役"),
    "menu_conquest": ("Conquest", "征服", "征服"),
    "menu_commanders": ("Commanders", "指揮官", "指挥官"),
    "menu_settings": ("Settings", "設定", "设置"),
    "menu_help": ("How to Play", "遊戲說明", "游戏说明"),
    "menu_progress": ("Missions %1$d/%2$d   ★ %3$d   Medals %4$d",
                      "關卡 %1$d/%2$d   ★ %3$d   勳章 %4$d",
                      "关卡 %1$d/%2$d   ★ %3$d   勋章 %4$d"),
    "common_back": ("Back", "返回", "返回"),
    "campaign_locked": ("Locked", "未解鎖", "未解锁"),

    # 設定
    "settings_title": ("Settings", "設定", "设置"),
    "settings_language": ("Language", "語言", "语言"),
    "settings_language_system": ("System default", "跟隨系統", "跟随系统"),
    "settings_sound": ("Sound effects", "音效", "音效"),
    "settings_animations": ("Unit animations", "部隊動畫", "部队动画"),
    "settings_on": ("On", "開", "开"),
    "settings_off": ("Off", "關", "关"),
    "settings_licence": ("Free software under GPL-3.0-or-later. No ads, no trackers, no permissions.",
                         "本作為 GPL-3.0-or-later 自由軟體。無廣告、無追蹤、無權限。",
                         "本作为 GPL-3.0-or-later 自由软件。无广告、无追踪、无权限。"),

    # 開局設定
    "setup_title": ("Choose your nation", "選擇國家", "选择国家"),
    "setup_difficulty": ("Difficulty", "難度", "难度"),
    "setup_funds": ("Funds %1$d", "資金 %1$d", "资金 %1$d"),
    "setup_start": ("Start", "開始", "开始"),

    # HUD
    "hud_turn": ("Turn %1$d", "第 %1$d 回合", "第 %1$d 回合"),
    "hud_funds": ("Funds %1$d", "資金 %1$d", "资金 %1$d"),
    "hud_provinces": ("Provinces %1$d", "省份 %1$d", "省份 %1$d"),
    "hud_end_turn": ("End Turn", "結束回合", "结束回合"),
    "hud_menu": ("Menu", "選單", "菜单"),
    "hud_tech": ("Tech", "研發", "研发"),
    "hud_air": ("Air", "空軍", "空军"),
    "hud_air_pick_target": ("%1$s: tap a target, or elsewhere to cancel",
                            "%1$s：點選目標，點別處取消",
                            "%1$s：点选目标，点别处取消"),
    "panel_air": ("Air missions", "空中任務", "空中任务"),
    "panel_air_subtitle": ("Funds %1$d · each airfield and carrier flies once a turn",
                           "資金 %1$d · 每座機場與航艦每回合出擊一次",
                           "资金 %1$d · 每座机场与航舰每回合出击一次"),
    "air_blocked_base": ("No airfield ready this turn", "這回合沒有可起飛的機場", "这回合没有可起飞的机场"),
    "toast_air_no_targets": ("No target within range", "航程內沒有目標", "航程内没有目标"),
    "toast_airdrop": ("Paratroopers have landed", "傘兵已著陸", "伞兵已着陆"),
    "toast_air_strike": ("Air strike: %1$s lost %2$d", "空襲：%1$s 損失 %2$d", "空袭：%1$s 损失 %2$d"),
    "event_air_strike": ("%2$s %1$s hit from the air (−%3$d)", "%2$s 的 %1$s 遭空襲（−%3$d）",
                         "%2$s 的 %1$s 遭空袭（−%3$d）"),
    "event_airdrop": ("%1$s paratroopers landed", "%1$s 傘兵著陸", "%1$s 伞兵着陆"),
    "hud_objectives": ("Goals", "目標", "目标"),
    "hud_build": ("Build", "生產", "生产"),
    "hud_repair": ("Repair", "整補", "整补"),
    "hud_wait": ("Wait", "待命", "待命"),
    "hud_next_unit": ("Next", "下一支", "下一支"),
    "hud_undo": ("Undo", "撤回", "撤回"),
    "hud_declare_war": ("Declare war", "宣戰", "宣战"),
    "hud_confirm_war": ("Confirm", "確認宣戰", "确认宣战"),
    "toast_war_armed": ("Tap again to declare war on %1$s", "再按一次，對 %1$s 宣戰", "再按一次，对 %1$s 宣战"),
    "hud_toggle_grid": ("Grid", "格線", "格线"),
    "hud_toggle_supply": ("Supply", "補給", "补给"),
    "hud_neutral": ("Neutral", "中立", "中立"),
    "toast_city_shelled": ("%1$s: city defence −%2$d", "%1$s：城防 −%2$d", "%1$s：城防 −%2$d"),
    "event_city_shelled": ("%1$s took %2$d damage to its defences",
                           "%1$s 的城防被打掉 %2$d", "%1$s 的城防被打掉 %2$d"),
    "hud_city_defence": ("City defence %1$d/%2$d", "城防 %1$d/%2$d", "城防 %1$d/%2$d"),
    "hud_terrain_line": ("%1$s · defence +%2$d%%", "%1$s · 防禦 +%2$d%%", "%1$s · 防御 +%2$d%%"),
    "hud_unit_level": ("Level %1$d · moves %2$d/%3$d", "等級 %1$d · 移動 %2$d/%3$d", "等级 %1$d · 移动 %2$d/%3$d"),
    "hud_entrenched": ("Entrenched %1$d", "築壕 %1$d 級", "筑壕 %1$d 级"),
    "hud_ai_turn": ("%1$s is moving…", "%1$s 行動中…", "%1$s 行动中…"),

    # 面板
    "panel_production": ("Production", "生產", "生产"),
    "panel_production_size": ("Formation", "編制", "编制"),
    "panel_production_subtitle": ("%1$s · industry %2$d · funds %3$d",
                                  "%1$s · 工業 %2$d · 資金 %3$d",
                                  "%1$s · 工业 %2$d · 资金 %3$d"),
    "panel_production_stats": ("ATK %1$d  DEF %2$d  MOV %3$d", "攻 %1$d  防 %2$d  動 %3$d", "攻 %1$d  防 %2$d  动 %3$d"),
    "panel_tech": ("Research", "研發", "研发"),
    "panel_objectives": ("Objectives", "戰役目標", "战役目标"),
    "panel_standings": ("Standings", "各國局勢", "各国局势"),
    "panel_paused": ("Paused", "暫停", "暂停"),
    "panel_unit": ("Unit", "部隊", "部队"),
    "tech_maxed": ("Max", "已滿級", "已满级"),
    "pause_resume": ("Resume", "繼續", "继续"),
    "pause_save": ("Save", "儲存", "保存"),
    "pause_quit": ("Quit to menu", "回到主選單", "回到主菜单"),

    # 生產受阻
    "build_blocked_industry": ("Industry too low", "工業等級不足", "工业等级不足"),
    "build_blocked_coastal": ("Needs a port", "需要臨海城市", "需要临海城市"),
    "build_blocked_room": ("No room to deploy", "沒有空位部署", "没有空位部署"),
    "build_blocked_funds": ("Not enough funds", "資金不足", "资金不足"),
    "build_blocked_city": ("No city here", "此省沒有城市", "此省没有城市"),
    "build_blocked_owner": ("Not your province", "不是你的省份", "不是你的省份"),

    # 結算
    "result_victory": ("Victory", "勝利", "胜利"),
    "result_defeat": ("Defeat", "失敗", "失败"),
    "result_turns": ("Turns used", "使用回合", "使用回合"),
    "result_provinces": ("Provinces held", "持有省份", "持有省份"),
    "result_killed": ("Enemy units destroyed", "殲滅敵軍", "歼灭敌军"),
    "result_lost": ("Own units lost", "我軍損失", "我军损失"),
    "result_continue": ("Continue", "確定", "确定"),

    # 單位詳情
    "target_soft": ("vs Infantry", "對步兵", "对步兵"),
    "target_armoured": ("vs Armour", "對裝甲", "对装甲"),
    "target_ship": ("vs Ships", "對艦艇", "对舰艇"),
    "target_aircraft": ("vs Aircraft", "對空", "对空"),
    "unit_level_line": ("Level %1$d · %2$d XP", "等級 %1$d · 經驗 %2$d", "等级 %1$d · 经验 %2$d"),
    "unit_stat_line": ("DEF %1$d · MOV %2$d · range %3$d-%4$d · vision %5$d",
                       "防禦 %1$d · 移動 %2$d · 射程 %3$d-%4$d · 視野 %5$d",
                       "防御 %1$d · 移动 %2$d · 射程 %3$d-%4$d · 视野 %5$d"),
    "unit_assign_commander": ("Assign commander", "指派指揮官", "指派指挥官"),

    # 指揮官
    "commander_medals": ("Medals %1$d", "勳章 %1$d", "勋章 %1$d"),
    "commander_recruit": ("Recruit %1$d", "招募 %1$d", "招募 %1$d"),
    "commander_recruited": ("Recruited", "已招募", "已招募"),
    "commander_recruited_toast": ("%1$s has joined your staff", "%1$s 加入了你的參謀部", "%1$s 加入了你的参谋部"),

    # 提示
    "toast_saved": ("Game saved", "已儲存", "已保存"),
    "toast_built": ("%1$s ready", "%1$s 已完成", "%1$s 已完成"),
    "toast_combat": ("%1$s took %2$d damage, dealt %3$d back", "%1$s 受創 %2$d，反擊 %3$d", "%1$s 受创 %2$d，反击 %3$d"),
    "toast_no_idle_units": ("No units left to move", "沒有可行動的部隊了", "没有可行动的部队了"),
    "toast_no_commanders": ("No commanders available", "沒有可指派的指揮官", "没有可指派的指挥官"),
    "toast_commander_assigned": ("%1$s takes command", "%1$s 接掌指揮", "%1$s 接掌指挥"),
    "toast_commander_cleared": ("Commander stood down", "已解除指揮官職務", "已解除指挥官职务"),
    "toast_map_missing": ("That map could not be loaded", "地圖載入失敗", "地图加载失败"),
    "toast_save_broken": ("The save file could not be read", "存檔無法讀取", "存档无法读取"),
    "toast_save_outdated": ("That save was made on an older version of the map and can't be continued",
                            "這個存檔用的是舊版地圖，無法繼續", "这个存档用的是旧版地图，无法继续"),

    # 事件
    "event_city_captured": ("%1$s captured by %2$s", "%2$s 攻下 %1$s", "%2$s 攻下 %1$s"),
    "event_province_captured": ("%2$s occupies %1$s", "%2$s 佔領 %1$s", "%2$s 占领 %1$s"),
    "event_nation_eliminated": ("%1$s has been eliminated", "%1$s 已被消滅", "%1$s 已被消灭"),
    "event_nation_surrendered": ("%1$s has surrendered to %2$s", "%1$s 向 %2$s 投降", "%1$s 向 %2$s 投降"),
    "event_unit_destroyed": ("%2$s lost a %1$s", "%2$s 損失一支 %1$s", "%2$s 损失一支 %1$s"),
    "event_unit_starved": ("%1$s ran out of supply", "%1$s 補給耗盡", "%1$s 补给耗尽"),
    "event_unit_built": ("%1$s built in %2$s", "%2$s 生產了 %1$s", "%2$s 生产了 %1$s"),
    "event_tech_advanced": ("%2$s advanced its research", "%2$s 完成了一項研發", "%2$s 完成了一项研发"),
    "event_war_declared": ("%1$s declares war on %2$s", "%1$s 向 %2$s 宣戰", "%1$s 向 %2$s 宣战"),
    "event_war_entered": ("%1$s enters the war", "%1$s 參戰", "%1$s 参战"),

    # 目標
    "objective_capture": ("Capture the marked provinces", "攻下指定省份", "攻下指定省份"),
    "objective_hold": ("Hold the marked provinces until turn %2$d", "守住指定省份至第 %2$d 回合", "守住指定省份至第 %2$d 回合"),
    "objective_eliminate": ("Eliminate %3$s", "消滅 %3$s", "消灭 %3$s"),
    "objective_survive": ("Survive until turn %2$d", "撐過第 %2$d 回合", "撑过第 %2$d 回合"),
    "objective_control": ("Control %1$d provinces", "控制 %1$d 個省份", "控制 %1$d 个省份"),
    "objective_conquest": ("Take every city of every nation at war with you (allies and neutrals don't count)",
                           "攻下所有敵國的全部城市（盟國與中立國不算）",
                           "攻下所有敌国的全部城市（盟国与中立国不算）"),
    "objective_conquest_solo": ("You are at peace with everyone: declare war to have something to conquer",
                                "你與所有國家和平相處，宣戰之後才有征服的對象",
                                "你与所有国家和平相处，宣战之后才有征服的对象"),

    # 難度
    "difficulty_recruit": ("Recruit", "新兵", "新兵"),
    "difficulty_officer": ("Officer", "軍官", "军官"),
    "difficulty_commander": ("Commander", "指揮官", "指挥官"),
    "difficulty_marshal": ("Marshal", "元帥", "元帅"),

    # AI 性格
    "nation_sun": ("Soviet Union", "蘇聯", "苏联"),
    "nation_frg": ("West Germany", "西德", "西德"),
    "nation_prc": ("People's Republic of China", "中華人民共和國", "中华人民共和国"),

    "chapter_ww2_europe": ("WWII · Europe", "二戰 · 歐洲", "二战 · 欧洲"),
    "chapter_ww2_pacific": ("WWII · Pacific", "二戰 · 太平洋", "二战 · 太平洋"),
    "chapter_cold_war": ("Cold War", "冷戰", "冷战"),

    "bloc_axis":    ("Axis",         "軸心國",   "轴心国"),
    "bloc_allies":  ("Allies",       "同盟國",   "同盟国"),
    "bloc_west":    ("Western bloc", "西方陣營", "西方阵营"),
    "bloc_east":    ("Eastern bloc", "東方陣營", "东方阵营"),
    "bloc_nato":    ("NATO",         "北約",     "北约"),
    "bloc_pact":    ("Warsaw Pact",  "華約",     "华约"),
    "bloc_neutral": ("Neutral",      "中立",     "中立"),

    # 研發分支
    "tech_infantry": ("Infantry", "步兵", "步兵"),
    "tech_armour": ("Armour", "裝甲", "装甲"),
    "tech_artillery": ("Artillery", "火炮", "火炮"),
    "tech_air": ("Air Force", "空軍", "空军"),
    "tech_navy": ("Navy", "海軍", "海军"),
    "tech_logistics": ("Logistics", "後勤", "后勤"),

    # 地形
    "terrain_ocean": ("Ocean", "大洋", "大洋"),
    "terrain_sea": ("Coastal waters", "近海", "近海"),
    "terrain_plain": ("Plains", "平原", "平原"),
    "terrain_farmland": ("Farmland", "農地", "农地"),
    "terrain_forest": ("Forest", "森林", "森林"),
    "terrain_jungle": ("Jungle", "叢林", "丛林"),
    "terrain_hills": ("Hills", "丘陵", "丘陵"),
    "terrain_mountain": ("Mountains", "山地", "山地"),
    "terrain_desert": ("Desert", "沙漠", "沙漠"),
    "terrain_swamp": ("Swamp", "沼澤", "沼泽"),
    "terrain_tundra": ("Tundra", "凍原", "冻原"),
    "terrain_ice": ("Ice", "冰原", "冰原"),
    "terrain_river": ("River valley", "河谷", "河谷"),

    # 說明
    "help_basics": ("The basics", "基本操作", "基本操作"),
    "help_basics_body": (
        "Tap a unit to select it. Blue hexes are where it can move; red hexes hold an enemy it can attack. "
        "Tap the same unit again for its full stats. Drag to pan the map, pinch to zoom. "
        "A land unit that ends its move on a city takes the whole province — its income, its supply and its factories.",
        "點一下部隊即可選取。藍色格是它走得到的地方，紅色格上有它打得到的敵人。"
        "再點一次同一支部隊會打開詳細資料。拖曳可以平移地圖，兩指捏合可以縮放。"
        "陸軍只要在城市上結束移動，整個省份就會易主 —— 連同它的收入、補給與工業。",
        "点一下部队即可选取。蓝色格是它走得到的地方，红色格上有它打得到的敌人。"
        "再点一次同一支部队会打开详细资料。拖曳可以平移地图，两指捏合可以缩放。"
        "陆军只要在城市上结束移动，整个省份就会易主 —— 连同它的收入、补给与工业。"),
    "help_combat": ("Combat", "戰鬥", "战斗"),
    "help_morale": ("Morale and encirclement", "士氣與包圍", "士气与包围"),
    "help_morale_body": (
        "Morale is not a bar that drains -- it is a read of the position a unit is in right now. "
        "Enemies on two opposite sides leave it shaken; four or more around it leave it broken; "
        "one step further and it is disrupted, unable to attack or even return fire. "
        "A commander with Rumour pushes the target one step lower on a hit, so encircle plus one "
        "rumour, or a flank plus two, or three rumours alone all reach the same place. "
        "Because morale reads the position, it comes straight back the moment the ring opens up.",
        "士氣不是會慢慢流失的一條槽，而是「這支部隊現在站在什麼處境」的讀數。"
        "對向兩格有敵人就是夾擊、士氣下降；四面以上有敵人就是包圍、士氣嚴重下降；"
        "再降一級就是混亂 —— 無法攻擊，也無法還手。"
        "帶謠言的指揮官每次命中都有機會再壓一級，所以包圍加一次謠言、夾擊加兩次、"
        "或是純粹三次謠言，結果都一樣。"
        "正因為它讀的是處境，包圍圈一鬆開，士氣就立刻回來。",
        "士气不是会慢慢流失的一条槽，而是「这支部队现在站在什么处境」的读数。"
        "对向两格有敌人就是夹击、士气下降；四面以上有敌人就是包围、士气严重下降；"
        "再降一级就是混乱 —— 无法攻击，也无法还手。"
        "带谣言的指挥官每次命中都有机会再压一级，所以包围加一次谣言、夹击加两次、"
        "或是纯粹三次谣言，结果都一样。"
        "正因为它读的是处境，包围圈一松开，士气就立刻回来。"),
    "help_combat_body": (
        "Every unit has four separate attack values: against infantry, armour, ships, and anti-air fire. "
        "Anti-tank guns shred armour and bounce off infantry. "
        "Artillery strikes from two or more hexes away and takes no return fire, but is nearly helpless once something reaches it — "
        "keep infantry in front of it. Terrain, city walls and entrenchment all raise the defender's strength, "
        "so attacking a dug-in unit in the mountains is a very different proposition from catching it on open farmland.",
        "每支部隊都有四個獨立的攻擊值：對步兵、對裝甲、對艦艇、對空（對空就是防空火力）。"
        "反坦克炮打戰車勢如破竹，打步兵卻軟弱無力。"
        "火炮從兩格以外開火且不會被反擊，可是一旦被貼身就幾乎沒有自衛能力 —— 前面一定要有步兵擋著。"
        "地形、城牆與築壕都會提高守方的防禦，所以「打山裡挖好壕溝的敵人」跟「在農地上逮到它」是兩回事。",
        "每支部队都有四个独立的攻击值：对步兵、对装甲、对舰艇、对空（对空就是防空火力）。"
        "反坦克炮打战车势如破竹，打步兵却软弱无力。"
        "火炮从两格以外开火且不会被反击，可是一旦被贴身就几乎没有自卫能力 —— 前面一定要有步兵挡着。"
        "地形、城墙与筑壕都会提高守方的防御，所以「打山里挖好壕沟的敌人」跟「在农地上逮到它」是两回事。"),
    "morale_elevated": ("Elevated", "士氣高昂", "士气高昂"),
    "morale_steady": ("Steady", "正常", "正常"),
    "morale_shaken": ("Shaken", "士氣下降", "士气下降"),
    "morale_broken": ("Broken", "士氣嚴重下降", "士气严重下降"),
    "morale_disrupted": ("Disrupted", "混亂", "混乱"),
    "hud_morale": ("Morale: %1$s", "士氣：%1$s", "士气：%1$s"),

    "help_supply": ("Supply lines", "補給線", "补给线"),
    "help_supply_body": (
        "Supply spreads out from your own cities along your own territory, and mountains cost more to reach across than plains. "
        "A unit inside supply refills and repairs each turn; a unit outside it loses supply, then strength, then dies. "
        "Supply trucks and headquarters carry a small supply bubble with them, which is how an offensive keeps moving "
        "once it has outrun its cities. Turn on the Supply overlay to see exactly how far your logistics reach.",
        "補給從你自己的城市沿著自己的領土擴散，而越過山脈要付出的代價遠高於平原。"
        "在補給範圍內的部隊每回合會回補與整補；範圍外的部隊會先掉補給、再掉戰力，最後餓死。"
        "補給車與司令部自帶一個小型補給圈，攻勢跑贏城市之後就靠它們續命。"
        "打開「補給」圖層，就看得到自己的後勤到底伸得多遠。",
        "补给从你自己的城市沿着自己的领土扩散，而越过山脉要付出的代价远高于平原。"
        "在补给范围内的部队每回合会回补与整补；范围外的部队会先掉补给、再掉战力，最后饿死。"
        "补给车与司令部自带一个小型补给圈，攻势跑赢城市之后就靠它们续命。"
        "打开「补给」图层，就看得到自己的后勤到底伸得多远。"),
    "help_formations": ("Formations", "編制", "编制"),
    "help_formations_body": (
        "A formation is chosen when you build: pick ×1 to ×4 in the production panel and pay for each one. "
        "Units cannot be merged afterwards. A bigger formation hits harder and loses a smaller share of its "
        "strength to each blow, but it is never worth as much as the same number of separate units: two "
        "formations of one will out-fight one formation of two. Build big when you need weight in a single "
        "hex — a narrow front, or a target one unit cannot move — and build small when you need to cover "
        "ground. Upkeep and repairs scale with the size.",
        "編制在造兵時就決定：在生產面板選 ×1 到 ×4，照編制數付錢，造出來之後不能再併。"
        "編制越大打得越重，每次挨打掉的比例也越少，但永遠比不上同樣數量拆開的部隊：兩支一編制的部隊，"
        "打得贏一支兩編制的。需要在一格裡集中重量時才造大編制 —— 守窄正面，或是單支部隊打不動的目標；"
        "要佔地盤就造小的。維持費與整補費都照編制數算。",
        "编制在造兵时就决定：在生产面板选 ×1 到 ×4，按编制数付钱，造出来之后不能再并。"
        "编制越大打得越重，每次挨打掉的比例也越少，但永远比不上同样数量拆开的部队：两支一编制的部队，"
        "打得赢一支两编制的。需要在一格里集中重量时才造大编制 —— 守窄正面，或是单支部队打不动的目标；"
        "要占地盘就造小的。维持费与整补费都照编制数算。"),
    "help_cities": ("Cities and sieges", "城市與攻城", "城市与攻城"),
    "help_cities_body": (
        "A city has defence points as well as income. While they stand, a unit inside the city takes only half "
        "the damage of an attack and the city absorbs a further share on top, so a defended capital costs several "
        "times what the same unit costs in the open. A province with a city does not change hands until its defence "
        "reaches zero: shell it from range, or stand on it and grind it down, then walk in.\\n\\n"
        "The relationship runs both ways. A city repairs the garrison standing in it far faster than open ground "
        "does, and that garrison is what rebuilds the city's defence — an empty city never recovers on its own, "
        "and neither recovers while an enemy is adjacent. A captured city keeps only a third of its defence, so "
        "whether you leave someone behind to restore it is the difference between holding a place and passing "
        "through it. Only land units that can capture may stop in an enemy city; guns, trucks and ships "
        "cannot park there.",
        "城市除了收入之外還有城防。城防還在的時候，城裡的部隊只吃一半傷害，城市再替它多擋一層，"
        "所以攻下一座有守軍的首都，代價是野戰的好幾倍。有城的省份在城防歸零之前不會易主："
        "遠遠轟掉它，或是站上去慢慢磨，然後走進去。\\n\\n"
        "這個關係是雙向的。城市替駐軍回血的速度遠高於野外，而城防也正是靠那支駐軍補回來的 —— "
        "空城永遠不會自己長回來，而且只要旁邊有敵人，兩邊都停。打下來的城市只剩三分之一的城防，"
        "所以要不要留一支部隊把它補起來，就是「佔領」與「路過」的差別。"
        "只有能佔領的陸軍可以停進敵方城市；火砲、補給車與軍艦都不行。",
        "城市除了收入之外还有城防。城防还在的时候，城里的部队只吃一半伤害，城市再替它多挡一层，"
        "所以攻下一座有守军的首都，代价是野战的好几倍。有城的省份在城防归零之前不会易主："
        "远远轰掉它，或是站上去慢慢磨，然后走进去。\\n\\n"
        "这个关系是双向的。城市替驻军回血的速度远高于野外，而城防也正是靠那支驻军补回来的 —— "
        "空城永远不会自己长回来，而且只要旁边有敌人，两边都停。打下来的城市只剩三分之一的城防，"
        "所以要不要留一支部队把它补起来，就是「占领」与「路过」的差别。"
        "只有能占领的陆军可以停进敌方城市；火炮、补给车与军舰都不行。"),
    "help_air": ("Air power", "空軍", "空军"),
    "help_air_body": (
        "Aircraft are not units on the map. Open the Air panel, pick a mission, pay for it and tap a target: "
        "the planes take off from one of your cities or carriers, do their job and go home. "
        "A fighter strike tears into infantry, a bomber strike smashes tanks and ships and can bomb an empty "
        "city's walls, and an airdrop puts a fresh infantry unit on any open land hex in range. "
        "Every airfield and carrier flies once a turn, so more big cities mean more sorties. "
        "Enemy anti-air, cruisers and destroyers within two hexes of the target blunt every strike — "
        "clear the flak first, or send the planes somewhere else.",
        "飛機不是地圖上的部隊。打開「空軍」面板，選一種任務、付錢、點目標：飛機從你的某座城市或航艦起飛，"
        "打完就回去。戰鬥機出擊專打步兵，轟炸機出擊專打戰車與軍艦、也能炸空城的城牆，"
        "空降則在航程內任一空著的陸地格放下一支新的步兵。每座機場與航艦每回合出擊一次，"
        "所以大城越多，架次越多。目標兩格內的敵方防空炮、巡洋艦與驅逐艦會削弱每一次空襲 —— "
        "先拔掉防空，或者換個地方炸。",
        "飞机不是地图上的部队。打开「空军」面板，选一种任务、付钱、点目标：飞机从你的某座城市或航舰起飞，"
        "打完就回去。战斗机出击专打步兵，轰炸机出击专打战车与军舰、也能炸空城的城墙，"
        "空降则在航程内任一空着的陆地格放下一支新的步兵。每座机场与航舰每回合出击一次，"
        "所以大城越多，架次越多。目标两格内的敌方防空炮、巡洋舰与驱逐舰会削弱每一次空袭 —— "
        "先拔掉防空，或者换个地方炸。"),
    "help_victory": ("Winning", "勝利條件", "胜利条件"),
    "help_victory_body": (
        "Campaign missions state their objective up front — check the Goals panel any time. Finishing early earns more stars, "
        "and stars pay out medals you spend on commanders, who then serve you in every later game. "
        "Conquest has no script: pick a nation on either side of the war, and win by knocking out every nation "
        "of the other bloc. Neutral countries cannot be chosen — they have nobody to fight.",
        "戰役關卡一開始就會說明目標，隨時可以打開「目標」面板查看。提早通關可以拿到更多星等，"
        "星等換成勳章，勳章用來招募指揮官，而指揮官在之後的每一局都能用。"
        "征服模式沒有劇本：挑戰爭任一方的國家，把敵對陣營的國家全部打垮就算贏。"
        "中立國不能選 —— 它沒有人可打。",
        "战役关卡一开始就会说明目标，随时可以打开「目标」面板查看。提早通关可以拿到更多星等，"
        "星等换成勋章，勋章用来招募指挥官，而指挥官在之后的每一局都能用。"
        "征服模式没有剧本：挑战争任一方的国家，把敌对阵营的国家全部打垮就算赢。"
        "中立国不能选 —— 它没有人可打。"),
}

UNITS = {
    "unit_infantry": ("Infantry", "步兵", "步兵",
        "Cheap, tough and the only thing that reliably takes cities. Everything else supports it.",
        "便宜、耐打，而且是唯一穩定拿得下城市的兵種。其餘一切都是為它服務的。",
        "便宜、耐打，而且是唯一稳定拿得下城市的兵种。其余一切都是为它服务的。"),
    "unit_mountain_infantry": ("Mountain Infantry", "山地步兵", "山地步兵",
        "Crosses mountains and jungle at half the usual cost. On broken ground it outruns armour.",
        "通過山地與叢林只要一半的移動成本。在破碎地形上，它比裝甲部隊還快。",
        "通过山地与丛林只要一半的移动成本。在破碎地形上，它比装甲部队还快。"),
    "unit_marine": ("Marines", "陸戰隊", "陆战队",
        "The only unit that can land from a ship and still fight the same turn.",
        "唯一能夠下船之後同一回合就開打的部隊。",
        "唯一能够下船之后同一回合就开打的部队。"),
    "unit_recon": ("Recon Vehicle", "偵察車", "侦察车",
        "Very fast, sees far, dies easily. Use it to find the enemy, not to fight one.",
        "跑得很快、看得很遠、也死得很快。用它找出敵人，別用它去打敵人。",
        "跑得很快、看得很远、也死得很快。用它找出敌人，别用它去打敌人。"),
    "unit_armour": ("Armour", "裝甲", "装甲",
        "Ignores enemy zones of control once per move, which makes it the tool for opening a breach.",
        "移動時可以無視敵方控制區，因此它是打開缺口的那把鑰匙。",
        "移动时可以无视敌方控制区，因此它是打开缺口的那把钥匙。"),
    "unit_anti_tank": ("Anti-Tank Gun", "反坦克炮", "反坦克炮",
        "Devastating against armour, almost useless against anything else.",
        "打裝甲毀天滅地，打其他東西幾乎沒用。",
        "打装甲毁天灭地，打其他东西几乎没用。"),
    "unit_artillery": ("Artillery", "火炮", "火炮",
        "Strikes from two to three hexes with no return fire. Helpless in close combat.",
        "從二到三格外開火且不會被反擊。一旦被貼身就毫無反抗能力。",
        "从二到三格外开火且不会被反击。一旦被贴身就毫无反抗能力。"),
    "unit_rocket": ("Rocket Artillery", "火箭炮", "火箭炮",
        "Longer reach and heavier punch than artillery, at a much higher price.",
        "射程更遠、火力更猛，代價是貴得多。",
        "射程更远、火力更猛，代价是贵得多。"),
    "unit_anti_air": ("Anti-Air", "防空炮", "防空炮",
        "Blunts every enemy air strike and airdrop within two hexes.",
        "削弱兩格內每一次敵方空襲與空降。",
        "削弱两格内每一次敌方空袭与空降。"),
    "unit_supply_truck": ("Supply Truck", "補給車", "补给车",
        "Carries a small supply bubble with it. This is how an offensive keeps moving.",
        "自帶一個小型補給圈。攻勢能不能持續，全看它跟不跟得上。",
        "自带一个小型补给圈。攻势能不能持续，全看它跟不跟得上。"),
    "unit_headquarters": ("Headquarters", "司令部", "司令部",
        "Grants +10% attack to friendly units within three hexes, and supplies them too.",
        "為三格內的友軍提供 +10% 攻擊力，同時也是移動的補給站。",
        "为三格内的友军提供 +10% 攻击力，同时也是移动的补给站。"),
    # 空中任務不是兵種，但名稱與說明的形狀一樣，所以放在同一張表。
    "mission_fighter": ("Fighter Strike", "戰鬥機出擊", "战斗机出击",
        "Strafes infantry hard; barely scratches tanks and ships.",
        "重創步兵；對戰車與軍艦幾乎無效。",
        "重创步兵；对战车与军舰几乎无效。"),
    "mission_bomber": ("Bomber Strike", "轟炸機出擊", "轰炸机出击",
        "Wrecks tanks and ships, or bombs an empty city's defences. Flies from large cities.",
        "重創戰車與軍艦，也能炸空城的城防。只能從大城起飛。",
        "重创战车与军舰，也能炸空城的城防。只能从大城起飞。"),
    "mission_airdrop": ("Airdrop", "空降", "空降",
        "Drops a fresh infantry unit on an open land hex in range.",
        "在航程內空著的陸地格放下一支新的步兵。",
        "在航程内空着的陆地格放下一支新的步兵。"),
    "unit_transport_ship": ("Transport Ship", "運輸艦", "运输舰",
        "Carries three land units. Defenceless — never sail one without an escort.",
        "可載三支陸軍。毫無自衛能力 —— 絕對不要讓它單獨出海。",
        "可载三支陆军。毫无自卫能力 —— 绝对不要让它单独出海。"),
    "unit_destroyer": ("Destroyer", "驅逐艦", "驱逐舰",
        "Hunts submarines and screens the fleet. The cheapest ship worth building.",
        "獵殺潛艇並為艦隊擔任屏衛。最便宜而值得造的軍艦。",
        "猎杀潜艇并为舰队担任屏卫。最便宜而值得造的军舰。"),
    "unit_cruiser": ("Cruiser", "巡洋艦", "巡洋舰",
        "Balanced gunnery plus real anti-air cover for the ships around it.",
        "火力均衡，並為周圍的艦艇提供真正的防空掩護。",
        "火力均衡，并为周围的舰艇提供真正的防空掩护。"),
    "unit_battleship": ("Battleship", "戰艦", "战舰",
        "Shells the coast from three hexes out. Nothing afloat trades with it and wins.",
        "從三格外砲擊海岸。水面上沒有任何東西換得過它。",
        "从三格外炮击海岸。水面上没有任何东西换得过它。"),
    "unit_submarine": ("Submarine", "潛艇", "潜艇",
        "Invisible until something gets close or a destroyer sweeps for it. Murders transports.",
        "除非有東西靠得夠近或驅逐艦來掃，否則看不見。專門獵殺運輸艦。",
        "除非有东西靠得够近或驱逐舰来扫，否则看不见。专门猎杀运输舰。"),
    "unit_carrier": ("Carrier", "航空母艦", "航空母舰",
        "A moving airfield: flies one air mission a turn from wherever it is.",
        "移動的機場：每回合可以從所在位置出擊一次空中任務。",
        "移动的机场：每回合可以从所在位置出击一次空中任务。"),
}

SKILLS = {
    "skill_offensive": ("Offensive", "攻勢", "攻势", "+12% attack", "攻擊力 +12%", "攻击力 +12%"),
    "skill_defensive": ("Defensive", "防禦", "防御", "+15% defence", "防禦力 +15%", "防御力 +15%"),
    "skill_blitz": ("Blitz", "疾行", "疾行", "+1 movement", "移動力 +1", "移动力 +1"),
    "skill_logistics": ("Logistics", "後勤", "后勤", "Faster resupply", "補給回復更快", "补给回复更快"),
    "skill_armour_expert": ("Armour Expert", "裝甲專家", "装甲专家", "+20% attack with armour", "裝甲部隊攻擊 +20%", "装甲部队攻击 +20%"),
    "skill_air_expert": ("Air Expert", "空戰專家", "空战专家", "+20% to air strikes within three hexes", "三格內的空襲威力 +20%", "三格内的空袭威力 +20%"),
    "skill_naval_expert": ("Naval Expert", "海戰專家", "海战专家", "+20% attack with ships", "海軍部隊攻擊 +20%", "海军部队攻击 +20%"),
    "skill_artillery_expert": ("Gunnery Expert", "炮術專家", "炮术专家", "+20% attack with artillery", "火炮部隊攻擊 +20%", "火炮部队攻击 +20%"),
    "skill_fortress": ("Fortress", "堡壘", "堡垒", "+20% defence when well entrenched", "築壕兩級以上時防禦 +20%", "筑壕两级以上时防御 +20%"),
    "skill_siege": ("Siege", "攻堅", "攻坚", "+25% attack against entrenched enemies", "對已築壕的敵人攻擊 +25%", "对已筑壕的敌人攻击 +25%"),
    "skill_veteran": ("Veteran", "老兵", "老兵", "New units start at level 2", "新部隊從二級起跳", "新部队从二级起跳"),
    "skill_field_medic": ("Field Medic", "野戰醫護", "野战医护", "+8 HP repaired each turn", "每回合多回復 8 點兵力", "每回合多回复 8 点兵力"),
    "skill_scout": ("Scout", "斥候", "斥候", "+1 vision", "視野 +1", "视野 +1"),
    "skill_iron_will": ("Iron Will", "鋼鐵意志", "钢铁意志", "Half attrition when out of supply", "斷補給時損耗減半", "断补给时损耗减半"),
    "skill_rumour": ("Rumour", "謠言", "谣言",
                     "Attacks may push the target one morale step lower",
                     "攻擊後有機會讓目標士氣再降一級",
                     "攻击后有机会让目标士气再降一级"),
}

COMMANDERS = {
    "cmd_ashby":     ("Col. Ashby",      "艾許比上校",   "艾什比上校"),
    "cmd_bergstrom": ("Col. Bergstrom",  "貝格斯壯上校", "贝格斯特伦上校"),
    "cmd_okonkwo":   ("Gen. Okonkwo",    "奧孔克沃將軍", "奥孔克沃将军"),
    "cmd_varela":    ("Gen. Varela",     "瓦雷拉將軍",   "瓦雷拉将军"),
    "cmd_lindqvist": ("Gen. Lindqvist",  "林德奎斯特將軍", "林德奎斯特将军"),
    "cmd_haddad":    ("Gen. Haddad",     "哈達德將軍",   "哈达德将军"),
    "cmd_novak":     ("Gen. Novak",      "諾瓦克將軍",   "诺瓦克将军"),
    "cmd_tanaka":    ("Adm. Tanaka",     "田中提督",     "田中提督"),
    "cmd_moreau":    ("Gen. Moreau",     "莫羅將軍",     "莫罗将军"),
    "cmd_ferreira":  ("Gen. Ferreira",   "費雷拉將軍",   "费雷拉将军"),
    "cmd_reyes":     ("Gen. Reyes",      "雷耶斯將軍",   "雷耶斯将军"),
    "cmd_adeyemi":   ("Mar. Adeyemi",    "阿德耶米元帥", "阿德耶米元帅"),
    "cmd_sorokin":   ("Mar. Sorokin",    "索羅金元帥",   "索罗金元帅"),
    "cmd_kaur":      ("Mar. Kaur",       "考爾元帥",     "考尔元帅"),
    "cmd_lindholm":  ("Adm. Lindholm",   "林霍姆提督",   "林霍姆提督"),
    "cmd_alvarez":   ("Mar. Alvarez",    "阿爾瓦雷斯元帥", "阿尔瓦雷斯元帅"),
}

SCENARIO_TEXT = {
    "scn_conquest_1943": ("1943 · The Turning Point", "1943 · 轉捩點", "1943 · 转折点"),
    "scn_conquest_1943_desc": (
        "March 1943. The Axis holds more ground than it ever will again, from the Atlantic coast to the Donets and from Manchuria to the Indies, and Stalingrad has just fallen back into Soviet hands. Everyone who is going to fight is fighting. Pick a side and see whether the tide turns.",
        "1943 年 3 月。軸心國的版圖到了頂點，從大西洋岸到頓涅茨、從滿洲到南洋，而史達林格勒剛剛回到蘇聯手上。該參戰的都已經參戰。選一邊，看戰局會不會就此逆轉。",
        "1943 年 3 月。轴心国的版图到了顶点，从大西洋岸到顿涅茨、从满洲到南洋，而斯大林格勒刚刚回到苏联手上。该参战的都已经参战。选一边，看战局会不会就此逆转。"),
    "scn_conquest_1950": ("1950 · The Two Camps", "1950 · 兩大陣營", "1950 · 两大阵营"),
    "scn_conquest_1950_desc": (
        "January 1950. Germany and Korea are divided, the People's Republic of China is three months old, and the empires still hold most of Africa. West and East face each other across a line drawn through Berlin, and everyone else would rather not choose.",
        "1950 年 1 月。德國與朝鮮一分為二，中華人民共和國成立才三個月，非洲大半仍是殖民地。西方與東方隔著一條穿過柏林的線對峙，其他國家寧可不選邊。",
        "1950 年 1 月。德国与朝鲜一分为二，中华人民共和国成立才三个月，非洲大半仍是殖民地。西方与东方隔着一条穿过柏林的线对峙，其他国家宁可不选边。"),
    "scn_conquest_1980": ("1980 · NATO and the Pact", "1980 · 北約與華約", "1980 · 北约与华约"),
    "scn_conquest_1980_desc": (
        "January 1980. The empires are gone and most of the world belongs to itself. NATO and the Warsaw Pact face each other across Europe, while China, India and the non-aligned world stand outside both.",
        "1980 年 1 月。帝國都已經散了，世界大半屬於自己。北約與華約在歐洲對峙，中國、印度與不結盟國家站在兩者之外。",
        "1980 年 1 月。帝国都已经散了，世界大半属于自己。北约与华约在欧洲对峙，中国、印度与不结盟国家站在两者之外。"),
    "scn_conquest_1939": ("1939 · The World", "1939 · 全球", "1939 · 全球"),
    "scn_conquest_1939_desc": (
        "The world as the empires held it: Africa and southern Asia belong to London, Paris and Rome rather than to themselves. Axis and Allies are already at war, though the Soviet Union and the United States will not join for some turns yet. Pick anyone.",
        "帝國手中的世界：非洲與南亞不屬於自己，屬於倫敦、巴黎與羅馬。軸心與同盟已經開戰，蘇聯與美國要再過幾個回合才會加入。想選誰都行。",
        "帝国手中的世界：非洲与南亚不属于自己，属于伦敦、巴黎与罗马。轴心与同盟已经开战，苏联与美国要再过几个回合才会加入。想选谁都行。"),

    "scn_campaign_ww2_01_poland_axis": ("1939 · Case White", "1939 · 白色方案", "1939 · 白色方案"),
    "scn_campaign_ww2_01_poland_axis_desc": (
        "You have the armour, the aircraft and the numbers. What you do not have is time: take Warsaw and Krakow before the turn limit, and do not let a dug-in defence buy it back.",
        "裝甲、飛機、兵力，你樣樣都有，唯獨沒有時間：在回合用完之前拿下華沙與克拉科夫，別讓挖好壕溝的守軍把時間拖回去。",
        "装甲、飞机、兵力，你样样都有，唯独没有时间：在回合用完之前拿下华沙与克拉科夫，别让挖好壕沟的守军把时间拖回去。"),
    "scn_campaign_ww2_02_france_allies": ("1940 · Holding Paris", "1940 · 守住巴黎", "1940 · 守住巴黎"),
    "scn_campaign_ww2_02_france_allies_desc": (
        "The line is in the wrong place and the armour is pouring through the gap. You do not need to win this one. Paris must still be French on turn 20.",
        "防線擺錯了地方，裝甲正從缺口湧進來。這一仗你不必贏，只要第 20 回合巴黎還在法國手上。",
        "防线摆错了地方，装甲正从缺口涌进来。这一仗你不必赢，只要第 20 回合巴黎还在法国手上。"),
    "scn_campaign_ww2_03_desert_axis": ("1942 · Road to Alexandria", "1942 · 通往亞歷山大港", "1942 · 通往亚历山大港"),
    "scn_campaign_ww2_03_desert_axis_desc": (
        "Your tanks are better and your supply line is longer. Every hex toward the Nile is one more hex from Tunis. Take Alexandria and Cairo before the desert finishes the war for the enemy.",
        "你的坦克比較好，補給線也比較長。往尼羅河多走一格，就離突尼斯遠一格。在沙漠替敵人把仗打完之前，拿下亞歷山大港與開羅。",
        "你的坦克比较好，补给线也比较长。往尼罗河多走一格，就离突尼斯远一格。在沙漠替敌人把仗打完之前，拿下亚历山大港与开罗。"),
    "scn_campaign_ww2_05_east_front_axis": ("1943 · Holding the Dnieper", "1943 · 守住聶伯河", "1943 · 守住聂伯河"),
    "scn_campaign_ww2_05_east_front_axis_desc": (
        "The initiative is gone and it is not coming back. Hold Kyiv and Kharkiv to turn 28 against an enemy with more of everything, and do not let yourself be surrounded while you do it.",
        "主動權已經沒了，也不會再回來。面對樣樣都比你多的對手，把基輔與哈爾科夫守到第 28 回合，而且守的時候別讓自己被包圍。",
        "主动权已经没了，也不会再回来。面对样样都比你多的对手，把基辅与哈尔科夫守到第 28 回合，而且守的时候别让自己被包围。"),
    "scn_campaign_ww2_04_south_seas_allies": ("1942 · Fortress Singapore", "1942 · 新加坡要塞", "1942 · 新加坡要塞"),
    "scn_campaign_ww2_04_south_seas_allies_desc": (
        "Singapore's guns face the sea, and the enemy is coming through the jungle. Hold it to turn 22. The fleet will not save you, so do not spend it trying.",
        "新加坡的砲口朝著海，敵人卻從叢林裡來。守到第 22 回合。艦隊救不了你，別把它耗在這上面。",
        "新加坡的炮口朝着海，敌人却从丛林里来。守到第 22 回合。舰队救不了你，别把它耗在这上面。"),
    "scn_campaign_ww2_06_philippines": ("1944 · Return to the Philippines", "1944 · 重返菲律賓", "1944 · 重返菲律宾"),
    "scn_campaign_ww2_06_philippines_desc": (
        "You do not have to take every island. Take the ones that cut the enemy's shipping and the rest will starve on their own. Manila and Cebu.",
        "不必把每一座島都拿下。拿下切斷敵人航線的那幾座，其餘的會自己餓死。目標是馬尼拉與塞布。",
        "不必把每一座岛都拿下。拿下切断敌人航线的那几座，其余的会自己饿死。目标是马尼拉与塞布。"),
    "scn_campaign_ww2_06_philippines_axis": ("1944 · The Last Perimeter", "1944 · 最後防線", "1944 · 最后防线"),
    "scn_campaign_ww2_06_philippines_axis_desc": (
        "The fleet that won this ocean is gone. Hold Manila and Cebu to turn 26 with what is left, and make every landing cost more than the one before.",
        "打贏這片海洋的艦隊已經沒了。用剩下的東西把馬尼拉與塞布守到第 26 回合，讓每一次登陸都比上一次更貴。",
        "打赢这片海洋的舰队已经没了。用剩下的东西把马尼拉与塞布守到第 26 回合，让每一次登陆都比上一次更贵。"),
    "scn_campaign_cw_01_suez_west": ("1956 · Operation Musketeer", "1956 · 火槍手行動", "1956 · 火枪手行动"),
    "scn_campaign_cw_01_suez_west_desc": (
        "Paratroops first, then the landing. Take Alexandria and Cairo before the rest of the world decides this war should not be happening.",
        "先空降，再登陸。在全世界決定這場仗不該打之前，拿下亞歷山大港與開羅。",
        "先空降，再登陆。在全世界决定这场仗不该打之前，拿下亚历山大港与开罗。"),
    "scn_campaign_cw_02_indochina_west": ("1968 · Holding Saigon", "1968 · 守住西貢", "1968 · 守住西贡"),
    "scn_campaign_cw_02_indochina_west_desc": (
        "The sky and the sea are yours, and neither of them holds ground. The enemy only has to still be there. Keep Saigon to turn 30.",
        "天空與海洋都是你的，但它們都守不住土地。對手只要還在就算贏。把西貢守到第 30 回合。",
        "天空与海洋都是你的，但它们都守不住土地。对手只要还在就算赢。把西贡守到第 30 回合。"),
    "scn_campaign_cw_03_central_front_pact": ("1985 · Through the Fulda Gap", "1985 · 穿越富爾達缺口", "1985 · 穿越富尔达缺口"),
    "scn_campaign_cw_03_central_front_pact_desc": (
        "The plan is speed: reach the Rhine before reinforcements cross the Atlantic. Take Hamburg and Munich, and do not stop to fight for anything that is not on the way.",
        "計畫就是速度：在增援橫渡大西洋之前衝到萊茵河。拿下漢堡與慕尼黑，不在路上的東西都別停下來打。",
        "计划就是速度：在增援横渡大西洋之前冲到莱茵河。拿下汉堡与慕尼黑，不在路上的东西都别停下来打。"),
    "scn_campaign_ww2_01_poland": ("1939 \u00b7 Poland", "1939 \u00b7 \u6ce2\u862d", "1939 \u00b7 \u6ce2\u5170"),
    "scn_campaign_ww2_01_poland_desc": (
        "You are outnumbered in armour, in aircraft and in men. This one cannot be won \u2014 it can be made expensive. Hold Warsaw to turn 20.",
        "\u88dd\u7532\u3001\u98db\u6a5f\u3001\u4eba\u6578\uff0c\u4f60\u6a23\u6a23\u90fd\u5c11\u3002\u9019\u4e00\u95dc\u8d0f\u4e0d\u4e86\uff0c\u4f46\u53ef\u4ee5\u8b93\u5c0d\u65b9\u4ed8\u51fa\u4ee3\u50f9\uff1a\u5b88\u4f4f\u83ef\u6c99\u5230\u7b2c 20 \u56de\u5408\u3002",
        "\u88c5\u7532\u3001\u98de\u673a\u3001\u4eba\u6570\uff0c\u4f60\u6837\u6837\u90fd\u5c11\u3002\u8fd9\u4e00\u5173\u8d62\u4e0d\u4e86\uff0c\u4f46\u53ef\u4ee5\u8ba9\u5bf9\u65b9\u4ed8\u51fa\u4ee3\u4ef7\uff1a\u5b88\u4f4f\u534e\u6c99\u5230\u7b2c 20 \u56de\u5408\u3002"),
    "scn_campaign_ww2_02_france": ("1940 \u00b7 France", "1940 \u00b7 \u6cd5\u862d\u897f", "1940 \u00b7 \u6cd5\u5170\u897f"),
    "scn_campaign_ww2_02_france_desc": (
        "Armour does not grind through a line, it pours through the gap. Tear the front open, then keep going \u2014 an assault unit that destroys its target can strike again.",
        "\u88dd\u7532\u4e0d\u662f\u7528\u4f86\u78e8\u7a7f\u6230\u7dda\u7684\uff0c\u662f\u7528\u4f86\u704c\u9032\u7f3a\u53e3\u7684\u3002\u6495\u958b\u6b63\u9762\uff0c\u7136\u5f8c\u5225\u505c \u2014\u2014 \u7a81\u64ca\u90e8\u968a\u6253\u6389\u76ee\u6a19\u4e4b\u5f8c\u9084\u80fd\u518d\u6253\u4e00\u6b21\u3002",
        "\u88c5\u7532\u4e0d\u662f\u7528\u6765\u78e8\u7a7f\u6218\u7ebf\u7684\uff0c\u662f\u7528\u6765\u704c\u8fdb\u7f3a\u53e3\u7684\u3002\u6495\u5f00\u6b63\u9762\uff0c\u7136\u540e\u522b\u505c \u2014\u2014 \u7a81\u51fb\u90e8\u961f\u6253\u6389\u76ee\u6807\u4e4b\u540e\u8fd8\u80fd\u518d\u6253\u4e00\u6b21\u3002"),
    "scn_campaign_ww2_03_desert": ("1942 \u00b7 Western Desert", "1942 \u00b7 \u897f\u90e8\u6c99\u6f20", "1942 \u00b7 \u897f\u90e8\u6c99\u6f20"),
    "scn_campaign_ww2_03_desert_desc": (
        "No cover out here, and no supply either. Unit for unit the Afrika Korps outclasses you; the distance to Tripoli is what beats it.",
        "\u9019\u88e1\u6c92\u6709\u63a9\u853d\uff0c\u4e5f\u6c92\u6709\u88dc\u7d66\u3002\u55ae\u4f4d\u5c0d\u55ae\u4f4d\u4f60\u6253\u4e0d\u8d0f\u975e\u6d32\u8ecd\uff0c\u4f46\u5230\u7684\u9ece\u6ce2\u91cc\u7684\u8ddd\u96e2\u6703\u66ff\u4f60\u6253\u8d0f\u3002",
        "\u8fd9\u91cc\u6ca1\u6709\u63a9\u853d\uff0c\u4e5f\u6ca1\u6709\u8865\u7ed9\u3002\u5355\u4f4d\u5bf9\u5355\u4f4d\u4f60\u6253\u4e0d\u8d62\u975e\u6d32\u519b\uff0c\u4f46\u5230\u7684\u9ece\u6ce2\u91cc\u7684\u8ddd\u79bb\u4f1a\u66ff\u4f60\u6253\u8d62\u3002"),
    "scn_campaign_ww2_04_south_seas": ("1942 \u00b7 The South Seas", "1942 \u00b7 \u5357\u6d0b", "1942 \u00b7 \u5357\u6d0b"),
    "scn_campaign_ww2_04_south_seas_desc": (
        "Nothing here is reachable on foot. Carriers buy the sky, transports carry the infantry, and a landing missing either one is a drowning.",
        "\u9019\u88e1\u6c92\u6709\u4e00\u500b\u76ee\u6a19\u8d70\u5f97\u5230\u3002\u822a\u8266\u8cb7\u4e0b\u5929\u7a7a\uff0c\u904b\u8f38\u8266\u8f09\u8457\u6b65\u5175\uff0c\u5169\u8005\u7f3a\u4e00\u7684\u767b\u9678\u53eb\u6eba\u6c34\u3002",
        "\u8fd9\u91cc\u6ca1\u6709\u4e00\u4e2a\u76ee\u6807\u8d70\u5f97\u5230\u3002\u822a\u8230\u4e70\u4e0b\u5929\u7a7a\uff0c\u8fd0\u8f93\u8230\u8f7d\u7740\u6b65\u5175\uff0c\u4e24\u8005\u7f3a\u4e00\u7684\u767b\u9646\u53eb\u6eba\u6c34\u3002"),
    "scn_campaign_ww2_05_east_front": ("1943 \u00b7 Eastern Front", "1943 \u00b7 \u6771\u7dda", "1943 \u00b7 \u4e1c\u7ebf"),
    "scn_campaign_ww2_05_east_front_desc": (
        "The numbers are yours now and the better tanks are still theirs. Do not trade evenly \u2014 surround. A unit with enemies on four sides falls apart.",
        "\u73fe\u5728\u6578\u91cf\u5728\u4f60\u9019\u908a\uff0c\u597d\u5766\u514b\u9084\u5728\u5c0d\u9762\u3002\u4e0d\u8981\u5c0d\u7b49\u4ea4\u63db \u2014\u2014 \u5305\u570d\u3002\u56db\u9762\u53d7\u6575\u7684\u90e8\u968a\u6703\u5d29\u6f70\u3002",
        "\u73b0\u5728\u6570\u91cf\u5728\u4f60\u8fd9\u8fb9\uff0c\u597d\u5766\u514b\u8fd8\u5728\u5bf9\u9762\u3002\u4e0d\u8981\u5bf9\u7b49\u4ea4\u6362 \u2014\u2014 \u5305\u56f4\u3002\u56db\u9762\u53d7\u654c\u7684\u90e8\u961f\u4f1a\u5d29\u6e83\u3002"),

    "scn_campaign_cw_01_suez": ("1956 \u00b7 Suez", "1956 \u00b7 \u8607\u4f0a\u58eb", "1956 \u00b7 \u82cf\u4f0a\u58eb"),
    "scn_campaign_cw_01_suez_desc": (
        "The landing arrives from the air before it arrives from the sea. Your tanks are useless against paratroops; your anti-air is not.",
        "\u767b\u9678\u5f9e\u5929\u4e0a\u5148\u4f86\uff0c\u6d77\u4e0a\u5f8c\u5230\u3002\u4f60\u7684\u5766\u514b\u5c0d\u5098\u5175\u6c92\u7528\uff0c\u4f60\u7684\u9632\u7a7a\u6709\u7528\u3002",
        "\u767b\u9646\u4ece\u5929\u4e0a\u5148\u6765\uff0c\u6d77\u4e0a\u540e\u5230\u3002\u4f60\u7684\u5766\u514b\u5bf9\u4f1e\u5175\u6ca1\u7528\uff0c\u4f60\u7684\u9632\u7a7a\u6709\u7528\u3002"),
    "scn_campaign_cw_02_indochina": ("1968 \u00b7 Indochina", "1968 \u00b7 \u5370\u5ea6\u652f\u90a3", "1968 \u00b7 \u5370\u5ea6\u652f\u90a3"),
    "scn_campaign_cw_02_indochina_desc": (
        "Every number on their side is bigger, and every one of those things has to fly in. Keep the sky expensive and the jungle handles the rest.",
        "\u5c0d\u9762\u6bcf\u4e00\u9805\u6578\u64da\u90fd\u6bd4\u4f60\u5927\uff0c\u800c\u90a3\u4e9b\u6771\u897f\u90fd\u5f97\u98db\u9032\u4f86\u3002\u8b93\u5929\u7a7a\u8b8a\u8cb4\uff0c\u53e2\u6797\u6703\u8655\u7406\u5269\u4e0b\u7684\u3002",
        "\u5bf9\u9762\u6bcf\u4e00\u9879\u6570\u636e\u90fd\u6bd4\u4f60\u5927\uff0c\u800c\u90a3\u4e9b\u4e1c\u897f\u90fd\u5f97\u98de\u8fdb\u6765\u3002\u8ba9\u5929\u7a7a\u53d8\u8d35\uff0c\u4e1b\u6797\u4f1a\u5904\u7406\u5269\u4e0b\u7684\u3002"),
    "scn_campaign_cw_03_central_front": ("1985 \u00b7 Central Front", "1985 \u00b7 \u4e2d\u6b50\u6b63\u9762", "1985 \u00b7 \u4e2d\u6b27\u6b63\u9762"),
    "scn_campaign_cw_03_central_front_desc": (
        "Six allied flags, one army you actually command. The others buy you turns \u2014 those are to be spent, not saved. Hold three cities to turn 30.",
        "\u516d\u9762\u76df\u65d7\uff0c\u4f46\u4f60\u53ea\u6307\u63ee\u5f97\u52d5\u4e00\u652f\u8ecd\u968a\u3002\u5176\u4ed6\u4eba\u66ff\u4f60\u63db\u56de\u5408 \u2014\u2014 \u90a3\u662f\u62ff\u4f86\u82b1\u7684\uff0c\u4e0d\u662f\u62ff\u4f86\u5b58\u7684\u3002\u5b88\u4f4f\u4e09\u5ea7\u57ce\u5230\u7b2c 30 \u56de\u5408\u3002",
        "\u516d\u9762\u76df\u65d7\uff0c\u4f46\u4f60\u53ea\u6307\u6325\u5f97\u52a8\u4e00\u652f\u519b\u961f\u3002\u5176\u4ed6\u4eba\u66ff\u4f60\u6362\u56de\u5408 \u2014\u2014 \u90a3\u662f\u62ff\u6765\u82b1\u7684\uff0c\u4e0d\u662f\u62ff\u6765\u5b58\u7684\u3002\u5b88\u4f4f\u4e09\u5ea7\u57ce\u5230\u7b2c 30 \u56de\u5408\u3002"),
}


def escape(text):
    out = (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
               .replace("'", "\\'").replace('"', '\\"'))
    return out


# lint 的 StringFormatDetector 把字串裡的任何 % 都當成格式指示符的開頭，
# 所以「攻擊力 +12%」這種純文字百分比會被判成 StringFormatInvalid ——
# 那是 error 級，會直接擋掉 lintDebug。
#
# 不能改寫成 %% 跳脫：這些說明文字是用 getString(id) 直接取的，沒有經過
# String.format，%% 會原封不動顯示成兩個百分號。官方的出口是宣告
# formatted="false"，意思正是「這條字串不拿去 format」。
POSITIONAL = re.compile(r"%(\d+\$[-+ #0,(]*\d*(?:\.\d+)?[a-zA-Z]|%)")


def has_literal_percent(text):
    """扣掉 %1$s 這類指示符與 %% 跳脫之後，還剩下裸的百分號。"""
    return "%" in POSITIONAL.sub("", text)


def is_format_string(text):
    return bool(re.search(r"%\d+\$", text))


def collect(locale_index):
    """locale_index: 0 = English, 1 = 正體, 2 = 简体。"""
    entries = []

    def add(key, values):
        entries.append((key, values[locale_index]))

    for key, values in UI.items():
        add(key, values)

    entries.append(("__section__", "Units"))
    for key, values in UNITS.items():
        add(key, values[0:3])
        add(key + "_desc", values[3:6])

    entries.append(("__section__", "Commander skills"))
    for key, values in SKILLS.items():
        add(key, values[0:3])
        add(key + "_desc", values[3:6])

    entries.append(("__section__", "Commanders"))
    for key, values in COMMANDERS.items():
        add(key, values)

    entries.append(("__section__", "Scenarios"))
    for key, values in SCENARIO_TEXT.items():
        add(key, values)

    entries.append(("__section__", "Nations"))
    for code, row in sorted(places.NATIONS.items()):
        add("nation_" + code.lower(), row[0:3])
    for code, row in sorted(places.EXTRA_NATIONS.items()):
        add("nation_" + code.lower(), row[0:3])
    for code, row in sorted(scn.FACTIONS.items()):
        add("nation_" + code.lower(), row[0:3])

    entries.append(("__section__", "Provinces"))
    for row in places.PROVINCES:
        add(row[0], row[5:8])

    return entries


def write_locale(directory, entries, unformatted):
    os.makedirs(directory, exist_ok=True)
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!--",
        "    SPDX-FileCopyrightText: 2026 AcideFluorhydrique",
        "    SPDX-License-Identifier: GPL-3.0-or-later",
        "",
        "    由 tools/genstrings.py 產生，請勿手動編輯。",
        "    要改字串請改 tools/genstrings.py（介面文字）或 tools/places.py（地名與國名）。",
        "-->",
        "<resources>",
    ]
    for key, value in entries:
        if key == "__section__":
            lines.append("")
            lines.append("    <!-- %s -->" % value)
            continue
        attribute = ' formatted="false"' if key in unformatted else ""
        lines.append(
            '    <string name="%s"%s>%s</string>' % (key, attribute, escape(value))
        )
    lines.append("</resources>")
    path = os.path.join(directory, "strings.xml")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    return path


def main():
    targets = [
        (os.path.join(RES, "values"), 0),
        (os.path.join(RES, "values-b+zh+Hant"), 1),
        (os.path.join(RES, "values-b+zh+Hans"), 2),
    ]
    collected = [(directory, collect(index)) for directory, index in targets]

    # 標記是逐鍵而不是逐語系決定的：同一個鍵在三個語系裡要嘛都是格式字串、
    # 要嘛都不是，不然翻譯一改就會冒出只在某一語系觸發的 lint 錯誤。
    unformatted = set()
    for _, entries in collected:
        for key, value in entries:
            if key != "__section__" and has_literal_percent(value):
                unformatted.add(key)

    for _, entries in collected:
        for key, value in entries:
            if key in unformatted and is_format_string(value):
                raise SystemExit(
                    "%s 同時含有 %%1$s 這類指示符與裸百分號 —— "
                    "多半是翻譯把指示符打錯了：%s" % (key, value)
                )

    for directory, entries in collected:
        path = write_locale(directory, entries, unformatted)
        count = sum(1 for k, _ in entries if k != "__section__")
        print(
            "%-70s %d strings（%d 條標為 formatted=\"false\"）"
            % (os.path.relpath(path, ROOT), count, len(unformatted))
        )


if __name__ == "__main__":
    main()
