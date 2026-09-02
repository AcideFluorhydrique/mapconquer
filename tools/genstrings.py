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
    "hud_objectives": ("Goals", "目標", "目标"),
    "hud_build": ("Build", "生產", "生产"),
    "hud_repair": ("Repair", "整補", "整补"),
    "hud_wait": ("Wait", "待命", "待命"),
    "hud_next_unit": ("Next", "下一支", "下一支"),
    "hud_undo": ("Undo", "撤回", "撤回"),
    "hud_toggle_grid": ("Grid", "格線", "格线"),
    "hud_toggle_supply": ("Supply", "補給", "补给"),
    "hud_neutral": ("Neutral", "中立", "中立"),
    "hud_terrain_line": ("%1$s · defence +%2$d%%", "%1$s · 防禦 +%2$d%%", "%1$s · 防御 +%2$d%%"),
    "hud_unit_level": ("Level %1$d · moves %2$d/%3$d", "等級 %1$d · 移動 %2$d/%3$d", "等级 %1$d · 移动 %2$d/%3$d"),
    "hud_entrenched": ("Entrenched %1$d", "築壕 %1$d 級", "筑壕 %1$d 级"),
    "hud_ai_turn": ("%1$s is moving…", "%1$s 行動中…", "%1$s 行动中…"),

    # 面板
    "panel_production": ("Production", "生產", "生产"),
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

    # 事件
    "event_city_captured": ("%1$s captured by %2$s", "%2$s 攻下 %1$s", "%2$s 攻下 %1$s"),
    "event_province_captured": ("%2$s occupies %1$s", "%2$s 佔領 %1$s", "%2$s 占领 %1$s"),
    "event_nation_eliminated": ("%1$s has been eliminated", "%1$s 已被消滅", "%1$s 已被消灭"),
    "event_unit_destroyed": ("%2$s lost a %1$s", "%2$s 損失一支 %1$s", "%2$s 损失一支 %1$s"),
    "event_unit_starved": ("%1$s ran out of supply", "%1$s 補給耗盡", "%1$s 补给耗尽"),
    "event_unit_built": ("%1$s built in %2$s", "%2$s 生產了 %1$s", "%2$s 生产了 %1$s"),
    "event_tech_advanced": ("%2$s advanced its research", "%2$s 完成了一項研發", "%2$s 完成了一项研发"),
    "event_war_declared": ("%1$s declares war on %2$s", "%1$s 向 %2$s 宣戰", "%1$s 向 %2$s 宣战"),

    # 目標
    "objective_capture": ("Capture the marked provinces", "攻下指定省份", "攻下指定省份"),
    "objective_hold": ("Hold the marked provinces until turn %2$d", "守住指定省份至第 %2$d 回合", "守住指定省份至第 %2$d 回合"),
    "objective_eliminate": ("Eliminate %3$s", "消滅 %3$s", "消灭 %3$s"),
    "objective_survive": ("Survive until turn %2$d", "撐過第 %2$d 回合", "撑过第 %2$d 回合"),
    "objective_control": ("Control %1$d provinces", "控制 %1$d 個省份", "控制 %1$d 个省份"),
    "objective_conquest": ("Control %1$d%% of the world", "控制世界 %1$d%% 的省份", "控制世界 %1$d%% 的省份"),

    # 難度
    "difficulty_recruit": ("Recruit", "新兵", "新兵"),
    "difficulty_officer": ("Officer", "軍官", "军官"),
    "difficulty_commander": ("Commander", "指揮官", "指挥官"),
    "difficulty_marshal": ("Marshal", "元帥", "元帅"),

    # AI 性格
    "ai_turtle": ("Defensive", "固守", "固守"),
    "ai_balanced": ("Balanced", "穩健", "稳健"),
    "ai_aggressive": ("Aggressive", "侵略", "侵略"),
    "ai_opportunist": ("Opportunist", "投機", "投机"),

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
        "Every unit has four separate attack values: against infantry, armour, ships and aircraft. "
        "Anti-tank guns shred armour and bounce off infantry; fighters own the sky and do nothing on the ground. "
        "Artillery strikes from two or more hexes away and takes no return fire, but is nearly helpless once something reaches it — "
        "keep infantry in front of it. Terrain, city walls and entrenchment all raise the defender's strength, "
        "so attacking a dug-in unit in the mountains is a very different proposition from catching it on open farmland.",
        "每支部隊都有四個獨立的攻擊值：對步兵、對裝甲、對艦艇、對空。"
        "反坦克炮打戰車勢如破竹，打步兵卻軟弱無力；戰鬥機制空無敵，對地面卻幾乎沒有作用。"
        "火炮從兩格以外開火且不會被反擊，可是一旦被貼身就幾乎沒有自衛能力 —— 前面一定要有步兵擋著。"
        "地形、城牆與築壕都會提高守方的防禦，所以「打山裡挖好壕溝的敵人」跟「在農地上逮到它」是兩回事。",
        "每支部队都有四个独立的攻击值：对步兵、对装甲、对舰艇、对空。"
        "反坦克炮打战车势如破竹，打步兵却软弱无力；战斗机制空无敌，对地面却几乎没有作用。"
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
    "help_victory": ("Winning", "勝利條件", "胜利条件"),
    "help_victory_body": (
        "Campaign missions state their objective up front — check the Goals panel any time. Finishing early earns more stars, "
        "and stars pay out medals you spend on commanders, who then serve you in every later game. "
        "Conquest has no script: pick any nation on the world map, and win by controlling 80% of its provinces. "
        "Everyone starts at peace, so the first war is yours to declare — or to survive.",
        "戰役關卡一開始就會說明目標，隨時可以打開「目標」面板查看。提早通關可以拿到更多星等，"
        "星等換成勳章，勳章用來招募指揮官，而指揮官在之後的每一局都能用。"
        "征服模式沒有劇本：在世界地圖上挑任何一個國家，控制八成的省份就算贏。"
        "所有人開局都是和平狀態，所以第一場戰爭由你來宣 —— 或者由你來撐過去。",
        "战役关卡一开始就会说明目标，随时可以打开「目标」面板查看。提早通关可以拿到更多星等，"
        "星等换成勋章，勋章用来招募指挥官，而指挥官在之后的每一局都能用。"
        "征服模式没有剧本：在世界地图上挑任何一个国家，控制八成的省份就算赢。"
        "所有人开局都是和平状态，所以第一场战争由你来宣 —— 或者由你来撑过去。"),
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
        "Reaches two hexes into the sky. Enemy aircraft simply cannot operate over it.",
        "對空射程兩格。敵機根本沒辦法在它頭上活動。",
        "对空射程两格。敌机根本没办法在它头上活动。"),
    "unit_supply_truck": ("Supply Truck", "補給車", "补给车",
        "Carries a small supply bubble with it. This is how an offensive keeps moving.",
        "自帶一個小型補給圈。攻勢能不能持續，全看它跟不跟得上。",
        "自带一个小型补给圈。攻势能不能持续，全看它跟不跟得上。"),
    "unit_headquarters": ("Headquarters", "司令部", "司令部",
        "Grants +10% attack to friendly units within three hexes, and supplies them too.",
        "為三格內的友軍提供 +10% 攻擊力，同時也是移動的補給站。",
        "为三格内的友军提供 +10% 攻击力，同时也是移动的补给站。"),
    "unit_fighter": ("Fighter", "戰鬥機", "战斗机",
        "Owns the sky. Nearly worthless against ground targets — that is the point.",
        "制空無敵。對地面目標幾乎無用 —— 這正是它的定位。",
        "制空无敌。对地面目标几乎无用 —— 这正是它的定位。"),
    "unit_bomber": ("Bomber", "轟炸機", "轰炸机",
        "Hits harder than anything on the ground, and falls to the first fighter that finds it.",
        "對地火力冠絕全場，卻會被第一架找到它的戰鬥機打下來。",
        "对地火力冠绝全场，却会被第一架找到它的战斗机打下来。"),
    "unit_air_transport": ("Air Transport", "運輸機", "运输机",
        "Lifts two foot units over mountains, water and enemy lines alike.",
        "可以把兩支徒步部隊直接吊過山脈、海面與敵人的戰線。",
        "可以把两支徒步部队直接吊过山脉、海面与敌人的战线。"),
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
        "Carries three aircraft and keeps them supplied far from any friendly airfield.",
        "可載三架飛機，並在遠離任何友方機場的地方維持它們的補給。",
        "可载三架飞机，并在远离任何友方机场的地方维持它们的补给。"),
}

SKILLS = {
    "skill_offensive": ("Offensive", "攻勢", "攻势", "+12% attack", "攻擊力 +12%", "攻击力 +12%"),
    "skill_defensive": ("Defensive", "防禦", "防御", "+15% defence", "防禦力 +15%", "防御力 +15%"),
    "skill_blitz": ("Blitz", "疾行", "疾行", "+1 movement", "移動力 +1", "移动力 +1"),
    "skill_logistics": ("Logistics", "後勤", "后勤", "Faster resupply", "補給回復更快", "补给回复更快"),
    "skill_armour_expert": ("Armour Expert", "裝甲專家", "装甲专家", "+20% attack with armour", "裝甲部隊攻擊 +20%", "装甲部队攻击 +20%"),
    "skill_air_expert": ("Air Expert", "空戰專家", "空战专家", "+20% attack with aircraft", "空軍部隊攻擊 +20%", "空军部队攻击 +20%"),
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
    "scn_conquest_modern": ("Modern World", "現代世界", "现代世界"),
    "scn_conquest_modern_desc": (
        "Every nation on the map, everyone at peace. Pick one and decide who moves first.",
        "地圖上所有國家，開局全部和平。挑一個，然後決定誰先動手。",
        "地图上所有国家，开局全部和平。挑一个，然后决定谁先动手。"),
    "scn_conquest_empires": ("Age of Empires", "帝國時代", "帝国时代"),
    "scn_conquest_empires_desc": (
        "The same world carved into two dozen sprawling empires. Fewer players, far bigger fronts.",
        "同一個世界，被切成二十來個橫跨大洋的帝國。玩家更少，戰線大得多。",
        "同一个世界，被切成二十来个横跨大洋的帝国。玩家更少，战线大得多。"),

    "scn_campaign_01_first_contact": ("Exercise 1 · First Contact", "演習一 · 初次接敵", "演习一 · 初次接敌"),
    "scn_campaign_01_first_contact_desc": (
        "Blue Force holds the islands, Red Force the coast opposite. Cross the water and take the two ports.",
        "藍軍據有島嶼，紅軍在對岸。渡過海峽，拿下對面的兩座港口。",
        "蓝军据有岛屿，红军在对岸。渡过海峡，拿下对面的两座港口。"),
    "scn_campaign_02_desert_supply": ("Exercise 2 · Desert Supply", "演習二 · 沙漠補給", "演习二 · 沙漠补给"),
    "scn_campaign_02_desert_supply_desc": (
        "A long advance across open desert. The enemy is not the problem — the distance is.",
        "橫越開闊沙漠的長程推進。敵人不是問題，距離才是。",
        "横越开阔沙漠的长程推进。敌人不是问题，距离才是。"),
    "scn_campaign_03_island_chain": ("Exercise 3 · Island Chain", "演習三 · 島鏈作戰", "演习三 · 岛链作战"),
    "scn_campaign_03_island_chain_desc": (
        "Nothing here is reachable on foot. Escort the transports, win the air, then land.",
        "這裡沒有一個目標走得到。護住運輸艦、奪下制空，然後登陸。",
        "这里没有一个目标走得到。护住运输舰、夺下制空，然后登陆。"),
    "scn_campaign_04_defence_in_depth": ("Exercise 4 · Defence in Depth", "演習四 · 縱深防禦", "演习四 · 纵深防御"),
    "scn_campaign_04_defence_in_depth_desc": (
        "Red Force has better armour and more of it. Dig in, hold the two cities, and let the attack burn itself out.",
        "紅軍的裝甲更好也更多。挖好壕溝、守住兩座城，讓對方的攻勢自己耗盡。",
        "红军的装甲更好也更多。挖好壕沟、守住两座城，让对方的攻势自己耗尽。"),
    "scn_campaign_05_mountain_offensive": ("Exercise 5 · Mountain Offensive", "演習五 · 山地攻勢", "演习五 · 山地攻势"),
    "scn_campaign_05_mountain_offensive_desc": (
        "High ground everywhere. Vehicles cannot follow you — this one belongs to the mountain troops.",
        "到處都是高地。輪車與履帶跟不上來 —— 這一關是山地部隊的舞台。",
        "到处都是高地。轮车与履带跟不上来 —— 这一关是山地部队的舞台。"),
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
