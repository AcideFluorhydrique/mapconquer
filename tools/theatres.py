# SPDX-FileCopyrightText: 2026 AcideFluorhydrique
# SPDX-License-Identifier: GPL-3.0-or-later
"""
戰區地圖：只畫一場戰役真正發生的那一塊地方。

世界地圖是為了征服模式而扭曲的縮略圖 —— 歐洲被放大、太平洋被壓扁，整個波蘭
只有兩座城。戰役不需要那樣：1939 年的波蘭戰役跟法國無關，1940 年的法國戰役
跟蘇聯無關。把範圍縮到戰場本身，同樣大小的格子就能畫出四倍的細節，而且不必
扭曲：戰區地圖用的是真實比例（見 [grid_for]）。

資料分三種來源：

- **海岸線與河流**是真的：取自 Natural Earth（公有領域），由 naturalearth.py
  裁切後放在 tools/data。
- **城市**是這張表：每個戰區自己列，歸屬是開戰當天的歸屬，不是現代國界。
  同類遊戲的戰役地圖也是這樣 —— 戰役是放大圖，征服才是縮略圖。
- **地勢與植被**（山、丘陵、森林、沼澤、農田）是手繪的近似多邊形。Natural Earth
  沒有高程資料，這一層是照地理常識畫的，不是測量值。

城市欄位：(鍵, 經度, 緯度, 等級, 歸屬, English, 正體, 简体)。
已經存在於 places.PROVINCES 的鍵沿用那邊的譯名，這裡的譯名只在新城市上用到。
"""

import math

import hexraster
import naturalearth

# 尖頂六角格：列距是欄距的 √3/2。
ROW_RATIO = math.sqrt(3.0) / 2.0


POLAND_1939 = {
    "id": "poland_1939",
    "cols": 50,
    "lon_min": 12.8, "lon_max": 26.0, "lat_min": 48.8, "lat_max": 55.2,
    # 只畫打這一仗時真的擋路的幹流；每一條支流都畫的話，五分之一的陸地會變成河。
    "rivers": ["Vistula", "Oder", "Warta", "Bug", "Zakhidnyy Buh", "Narew", "San", "Bzura"],
    "cities": [
        # ---- 波蘭 ----
        ("prov_warsaw",        21.01, 52.23, 3, "POL", "Warsaw", "華沙", "华沙"),
        ("prov_krakow",        19.94, 50.06, 2, "POL", "Krakow", "克拉科夫", "克拉科夫"),
        ("prov_lodz",          19.46, 51.76, 2, "POL", "Lodz", "羅茲", "罗兹"),
        ("prov_poznan",        16.93, 52.41, 2, "POL", "Poznan", "波茲南", "波兹南"),
        ("prov_lwow",          24.03, 49.84, 2, "POL", "Lwow", "利沃夫", "利沃夫"),
        ("prov_wilno",         25.28, 54.69, 2, "POL", "Wilno", "維爾諾", "维尔诺"),
        ("prov_lublin",        22.57, 51.25, 2, "POL", "Lublin", "盧布林", "卢布林"),
        ("prov_brest_litovsk", 23.69, 52.10, 1, "POL", "Brest-Litovsk", "布列斯特", "布列斯特"),
        ("prov_bialystok",     23.16, 53.13, 1, "POL", "Bialystok", "比亞維斯托克", "比亚韦斯托克"),
        ("prov_katowice",      19.02, 50.26, 2, "POL", "Katowice", "卡托維治", "卡托维兹"),
        ("prov_bydgoszcz",     18.00, 53.12, 2, "POL", "Bydgoszcz", "比得哥什", "比得哥什"),
        ("prov_radom",         21.15, 51.40, 1, "POL", "Radom", "拉多姆", "拉多姆"),
        ("prov_przemysl",      22.77, 49.78, 1, "POL", "Przemysl", "普熱梅希爾", "普热梅希尔"),
        ("prov_grodno",        23.83, 53.68, 1, "POL", "Grodno", "格羅德諾", "格罗德诺"),
        # ---- 德國（含波希米亞與摩拉維亞保護國） ----
        ("prov_berlin",         13.40, 52.52, 4, "DEU", "Berlin", "柏林", "柏林"),
        ("prov_breslau",        17.03, 51.11, 3, "DEU", "Breslau", "布雷斯勞", "布雷斯劳"),
        ("prov_stettin",        14.55, 53.43, 2, "DEU", "Stettin", "斯德丁", "斯德丁"),
        ("prov_konigsberg",     20.51, 54.71, 3, "DEU", "Konigsberg", "柯尼斯堡", "柯尼斯堡"),
        ("prov_dresden",        13.74, 51.05, 3, "DEU", "Dresden", "德勒斯登", "德累斯顿"),
        ("prov_frankfurt_oder", 14.55, 52.34, 1, "DEU", "Frankfurt (Oder)", "奧得河畔法蘭克福", "奥得河畔法兰克福"),
        ("prov_oppeln",         17.93, 50.67, 1, "DEU", "Oppeln", "奧珀倫", "奥珀伦"),
        ("prov_allenstein",     20.48, 53.78, 1, "DEU", "Allenstein", "阿倫施泰因", "阿伦施泰因"),
        ("prov_schneidemuhl",   16.74, 53.15, 1, "DEU", "Schneidemuhl", "施奈德米爾", "施奈德米尔"),
        ("prov_stolp",          17.03, 54.46, 1, "DEU", "Stolp", "斯托爾普", "斯托尔普"),
        # 但澤自由市：名義上不屬於德國，但市政府由納粹黨掌握，開戰當天就併入。
        ("prov_danzig",         18.65, 54.35, 2, "DEU", "Danzig", "但澤", "但泽"),
        ("prov_prague",         14.42, 50.09, 3, "DEU", "Prague", "布拉格", "布拉格"),
        ("prov_brno",           16.61, 49.19, 2, "DEU", "Brunn", "布爾諾", "布尔诺"),
        ("prov_ostrava",        18.29, 49.83, 2, "DEU", "Moravian Ostrava", "俄斯特拉發", "俄斯特拉发"),
        # ---- 旁觀國 ----
        ("prov_kaunas",         23.90, 54.90, 2, "LTU", "Kaunas", "考納斯", "考纳斯"),
        ("prov_zilina",         18.74, 49.22, 1, "SVK", "Zilina", "日利納", "日利纳"),
        ("prov_presov",         21.24, 49.00, 1, "SVK", "Presov", "普雷紹夫", "普雷绍夫"),
    ],
    "relief": [
        ("h", [(17.8, 49.9), (19.5, 49.85), (21.0, 49.8), (22.8, 49.6), (23.5, 49.3),
               (23.5, 48.8), (17.8, 48.8)]),                                   # 喀爾巴阡山前
        ("h", [(14.6, 51.0), (15.8, 51.0), (16.9, 50.6), (17.6, 50.3), (17.4, 50.0),
               (16.4, 50.1), (15.4, 50.5), (14.6, 50.75)]),                    # 蘇台德
        ("h", [(12.8, 50.75), (14.4, 50.95), (14.4, 50.6), (12.8, 50.3)]),     # 厄爾士山
        ("h", [(15.0, 49.9), (16.4, 49.8), (16.6, 49.2), (15.6, 48.9), (14.8, 49.2)]),  # 波希米亞-摩拉維亞高地
        ("h", [(12.8, 49.6), (13.6, 49.2), (14.2, 48.8), (12.8, 48.8)]),       # 波希米亞森林
        ("h", [(20.3, 51.05), (21.3, 50.95), (21.4, 50.7), (20.4, 50.75)]),    # 聖十字山
        ("h", [(19.2, 50.9), (19.8, 50.8), (19.9, 50.2), (19.4, 50.2)]),       # 克拉科夫-琴斯托霍瓦高地
        ("h", [(22.6, 50.7), (23.6, 50.3), (23.9, 49.9), (23.3, 50.0), (22.5, 50.5)]),  # 羅茲托切
        ("^", [(19.7, 49.32), (20.35, 49.32), (20.35, 49.1), (19.7, 49.1)]),   # 高塔特拉
        ("^", [(19.0, 49.02), (20.2, 49.02), (20.2, 48.85), (19.0, 48.85)]),   # 低塔特拉
        ("^", [(15.4, 50.85), (15.95, 50.8), (15.9, 50.65), (15.4, 50.7)]),    # 巨人山
    ],
    "cover": [
        (":", [(16.0, 52.9), (18.6, 52.9), (18.6, 51.6), (16.0, 51.6)]),       # 大波蘭
        (":", [(18.6, 52.9), (20.3, 52.6), (20.2, 51.9), (18.6, 51.9)]),       # 庫亞維、西馬佐夫舍
        (":", [(16.2, 51.4), (18.0, 51.2), (18.2, 50.5), (16.8, 50.6)]),       # 西里西亞低地
        (":", [(22.0, 51.5), (23.6, 51.3), (23.5, 50.7), (22.0, 50.8)]),       # 盧布林高地
        (":", [(13.8, 50.5), (15.5, 50.4), (15.3, 49.9), (13.8, 49.9)]),       # 波希米亞盆地
        (":", [(16.8, 49.7), (17.6, 49.7), (17.4, 49.0), (16.6, 49.0)]),       # 摩拉維亞
        (":", [(23.8, 50.0), (26.0, 50.2), (26.0, 49.2), (24.0, 49.2)]),       # 波多里亞
        (":", [(12.8, 51.6), (14.2, 51.5), (14.0, 51.1), (12.8, 51.1)]),       # 薩克森低地
        (":", [(19.8, 54.9), (22.4, 54.9), (22.4, 54.25), (19.8, 54.2)]),      # 東普魯士
        (":", [(18.6, 54.3), (19.6, 54.3), (19.5, 53.9), (18.6, 53.9)]),       # 維斯瓦三角洲
        ("f", [(20.0, 54.1), (22.8, 54.2), (22.8, 53.5), (20.0, 53.45)]),      # 馬祖里湖區
        ("f", [(23.2, 53.3), (24.3, 53.2), (24.3, 52.5), (23.4, 52.55)]),      # 比亞沃維耶扎
        ("f", [(17.3, 54.0), (18.4, 53.95), (18.4, 53.4), (17.3, 53.4)]),      # 圖霍拉森林
        ("f", [(15.3, 54.0), (17.2, 54.2), (17.2, 53.5), (15.3, 53.4)]),       # 波美拉尼亞湖區
        ("f", [(14.6, 52.2), (15.8, 52.1), (15.8, 51.3), (14.6, 51.3)]),       # 盧布斯卡、下西里西亞森林
        ("f", [(13.6, 52.1), (14.5, 52.0), (14.4, 51.6), (13.5, 51.7)]),       # 施普雷森林
        ("f", [(23.0, 54.4), (24.6, 54.6), (24.6, 53.8), (23.0, 53.8)]),       # 奧古斯圖夫森林
        ("f", [(21.5, 50.5), (22.4, 50.5), (22.4, 50.1), (21.5, 50.1)]),       # 桑多梅日森林
        ("s", [(23.9, 52.3), (26.0, 52.4), (26.0, 51.4), (24.2, 51.4)]),       # 普里皮亞季沼澤
        ("s", [(22.3, 53.7), (23.0, 53.6), (22.9, 53.2), (22.2, 53.3)]),       # 別布扎沼澤
    ],
}


FRANCE_1940 = {
    "id": "france_1940",
    "cols": 56,
    "lon_min": -5.5, "lon_max": 10.5, "lat_min": 42.6, "lat_max": 53.6,
    # 原始檔裡有幾條河的名字掉了重音字母（Rhne、Sane），照原樣列。
    "rivers": ["Seine", "Marne", "Maas", "Somme", "Loire", "Rhône", "Rhne", "Saône", "Sane",
               "Garonne", "Rhine", "Rhein", "Rhin", "Mosel", "Waal", "Thames", "Po"],
    "cities": [
        # ---- 法國 ----
        ("prov_paris",       2.35, 48.86, 4, "FRA", "Paris", "巴黎", "巴黎"),
        ("prov_lyon",        4.83, 45.76, 3, "FRA", "Lyon", "里昂", "里昂"),
        ("prov_marseille",   5.37, 43.30, 3, "FRA", "Marseille", "馬賽", "马赛"),
        ("prov_bordeaux",   -0.58, 44.84, 2, "FRA", "Bordeaux", "波爾多", "波尔多"),
        ("prov_lille",       3.06, 50.63, 2, "FRA", "Lille", "里爾", "里尔"),
        ("prov_strasbourg",  7.75, 48.58, 2, "FRA", "Strasbourg", "史特拉斯堡", "斯特拉斯堡"),
        ("prov_metz",        6.18, 49.12, 1, "FRA", "Metz", "梅斯", "梅斯"),
        ("prov_reims",       4.03, 49.26, 1, "FRA", "Reims", "蘭斯", "兰斯"),
        ("prov_sedan",       4.94, 49.70, 1, "FRA", "Sedan", "色當", "色当"),
        ("prov_dunkirk",     2.38, 51.03, 1, "FRA", "Dunkirk", "敦克爾克", "敦刻尔克"),
        ("prov_amiens",      2.30, 49.89, 1, "FRA", "Amiens", "亞眠", "亚眠"),
        ("prov_le_havre",    0.11, 49.49, 2, "FRA", "Le Havre", "勒阿弗爾", "勒阿弗尔"),
        ("prov_cherbourg",  -1.62, 49.64, 1, "FRA", "Cherbourg", "瑟堡", "瑟堡"),
        ("prov_brest",      -4.49, 48.39, 1, "FRA", "Brest", "布雷斯特", "布雷斯特"),
        ("prov_nantes",     -1.55, 47.22, 2, "FRA", "Nantes", "南特", "南特"),
        ("prov_orleans",     1.91, 47.90, 1, "FRA", "Orleans", "奧爾良", "奥尔良"),
        ("prov_dijon",       5.04, 47.32, 1, "FRA", "Dijon", "第戎", "第戎"),
        ("prov_toulouse",    1.44, 43.60, 2, "FRA", "Toulouse", "土魯斯", "图卢兹"),
        ("prov_limoges",     1.26, 45.83, 1, "FRA", "Limoges", "利摩日", "利摩日"),
        ("prov_clermont",    3.09, 45.78, 1, "FRA", "Clermont-Ferrand", "克萊蒙費朗", "克莱蒙费朗"),
        ("prov_perpignan",   2.90, 42.70, 1, "FRA", "Perpignan", "佩皮尼昂", "佩皮尼昂"),
        # ---- 低地國 ----
        ("prov_brussels",    4.35, 50.85, 3, "BEL", "Brussels", "布魯塞爾", "布鲁塞尔"),
        ("prov_antwerp",     4.40, 51.22, 2, "BEL", "Antwerp", "安特衛普", "安特卫普"),
        ("prov_liege",       5.57, 50.63, 2, "BEL", "Liege", "列日", "列日"),
        ("prov_amsterdam",   4.90, 52.37, 3, "NLD", "Amsterdam", "阿姆斯特丹", "阿姆斯特丹"),
        ("prov_rotterdam",   4.48, 51.92, 2, "NLD", "Rotterdam", "鹿特丹", "鹿特丹"),
        ("prov_groningen",   6.57, 53.22, 1, "NLD", "Groningen", "格羅寧根", "格罗宁根"),
        ("prov_eindhoven",   5.47, 51.44, 1, "NLD", "Eindhoven", "恩荷芬", "埃因霍温"),
        ("prov_luxembourg",  6.13, 49.61, 1, "LUX", "Luxembourg", "盧森堡", "卢森堡"),
        # ---- 德國 ----
        ("prov_cologne",     6.96, 50.94, 3, "DEU", "Cologne", "科隆", "科隆"),
        ("prov_essen",       7.01, 51.46, 3, "DEU", "Essen", "埃森", "埃森"),
        ("prov_frankfurt",   8.68, 50.11, 3, "DEU", "Frankfurt", "法蘭克福", "法兰克福"),
        ("prov_stuttgart",   9.18, 48.78, 2, "DEU", "Stuttgart", "斯圖加特", "斯图加特"),
        ("prov_aachen",      6.08, 50.78, 1, "DEU", "Aachen", "亞琛", "亚琛"),
        ("prov_koblenz",     7.59, 50.36, 1, "DEU", "Koblenz", "科布倫茲", "科布伦茨"),
        ("prov_saarbrucken", 6.99, 49.24, 1, "DEU", "Saarbrucken", "薩爾布呂肯", "萨尔布吕肯"),
        ("prov_munster",     7.63, 51.96, 1, "DEU", "Munster", "明斯特", "明斯特"),
        ("prov_bremen",      8.80, 53.08, 2, "DEU", "Bremen", "不來梅", "不来梅"),
        ("prov_hamburg",     9.99, 53.55, 3, "DEU", "Hamburg", "漢堡", "汉堡"),
        ("prov_hanover",     9.73, 52.37, 2, "DEU", "Hanover", "漢諾威", "汉诺威"),
        ("prov_freiburg",    7.85, 48.00, 1, "DEU", "Freiburg", "弗萊堡", "弗莱堡"),
        # ---- 英國 ----
        ("prov_london",      -0.13, 51.51, 4, "GBR", "London", "倫敦", "伦敦"),
        ("prov_dover",        1.31, 51.13, 1, "GBR", "Dover", "多佛", "多佛"),
        ("prov_southampton", -1.40, 50.90, 2, "GBR", "Southampton", "南安普敦", "南安普敦"),
        ("prov_bristol",     -2.59, 51.45, 2, "GBR", "Bristol", "布里斯托", "布里斯托尔"),
        ("prov_plymouth",    -4.14, 50.37, 1, "GBR", "Plymouth", "普利茅斯", "普利茅斯"),
        ("prov_birmingham",  -1.90, 52.48, 3, "GBR", "Birmingham", "伯明罕", "伯明翰"),
        ("prov_norwich",      1.30, 52.63, 1, "GBR", "Norwich", "諾里奇", "诺里奇"),
        ("prov_cardiff",     -3.18, 51.48, 2, "GBR", "Cardiff", "加地夫", "加的夫"),
        # ---- 旁觀國 ----
        ("prov_zurich",       8.54, 47.38, 2, "CHE", "Zurich", "蘇黎世", "苏黎世"),
        ("prov_bern",         7.45, 46.95, 2, "CHE", "Bern", "伯恩", "伯尔尼"),
        ("prov_geneva",       6.14, 46.20, 1, "CHE", "Geneva", "日內瓦", "日内瓦"),
        ("prov_turin",        7.69, 45.07, 3, "ITA", "Turin", "杜林", "都灵"),
        ("prov_milan",        9.19, 45.46, 3, "ITA", "Milan", "米蘭", "米兰"),
        ("prov_genoa",        8.93, 44.41, 2, "ITA", "Genoa", "熱那亞", "热那亚"),
        ("prov_san_sebastian", -1.98, 43.32, 1, "ESP", "San Sebastian", "聖塞巴斯提安", "圣塞巴斯蒂安"),
    ],
    "relief": [
        ("h", [(1.6, 46.0), (3.9, 46.2), (4.5, 45.2), (4.2, 44.2), (3.2, 43.6), (2.0, 43.6),
               (1.5, 44.6)]),                                                  # 中央高原
        ("h", [(5.5, 46.1), (6.1, 46.1), (7.3, 47.4), (6.6, 47.5), (5.6, 46.7)]),     # 侏羅山
        ("h", [(6.6, 48.9), (7.35, 48.9), (7.3, 47.7), (6.6, 47.7)]),          # 孚日山
        ("h", [(7.7, 48.9), (8.6, 48.9), (8.6, 47.6), (7.7, 47.6)]),           # 黑森林
        ("h", [(4.5, 50.3), (5.4, 50.6), (6.4, 50.7), (7.2, 50.5), (7.0, 49.9), (6.2, 49.6),
               (5.0, 49.6), (4.4, 49.9)]),                                     # 亞爾丁、艾費爾
        ("h", [(6.6, 49.9), (7.9, 49.9), (8.0, 49.1), (6.9, 49.1)]),           # 洪斯呂克、普法爾茨
        ("h", [(7.4, 51.3), (9.0, 51.4), (9.2, 50.3), (7.8, 50.2)]),           # 陶努斯、紹爾蘭
        ("h", [(9.0, 51.9), (10.5, 51.9), (10.5, 50.2), (9.2, 50.3)]),         # 黑森山地
        ("h", [(8.6, 48.6), (10.5, 48.9), (10.5, 48.2), (8.7, 47.9)]),         # 施瓦本侏羅
        ("h", [(8.2, 44.6), (10.5, 44.7), (10.5, 44.1), (9.2, 44.1), (8.2, 44.2)]),   # 利古里亞亞平寧
        ("h", [(5.2, 44.5), (6.2, 44.3), (6.9, 43.9), (6.6, 43.4), (5.4, 43.6)]),     # 普羅旺斯前山
        ("h", [(3.8, 47.5), (4.9, 47.5), (4.8, 46.8), (3.8, 46.8)]),           # 莫爾旺
        ("h", [(-4.4, 48.5), (-2.8, 48.5), (-2.8, 48.1), (-4.4, 48.1)]),       # 布列塔尼丘陵
        ("h", [(-5.3, 53.3), (-3.1, 53.3), (-2.9, 51.7), (-5.3, 51.7)]),       # 威爾斯
        ("h", [(-5.0, 50.7), (-3.6, 50.8), (-3.6, 50.3), (-5.0, 50.2)]),       # 德文、康瓦爾荒原
        ("h", [(-2.3, 53.6), (-1.5, 53.6), (-1.6, 52.9), (-2.2, 52.9)]),       # 本寧山南端
        ("^", [(6.0, 46.2), (7.0, 46.6), (8.2, 46.9), (9.4, 47.2), (10.5, 47.3), (10.5, 46.0),
               (9.2, 46.0), (8.0, 45.6), (7.4, 44.9), (7.6, 44.2), (6.9, 43.9), (6.2, 44.3),
               (5.8, 45.0), (5.9, 45.7)]),                                     # 阿爾卑斯
        ("^", [(-1.4, 43.05), (0.5, 42.95), (2.4, 42.75), (2.6, 42.6), (-1.6, 42.6)]),  # 庇里牛斯
        ("^", [(2.5, 45.6), (3.0, 45.6), (3.1, 44.9), (2.5, 44.9)]),           # 奧弗涅火山
    ],
    "cover": [
        (":", [(0.8, 49.9), (4.3, 49.9), (4.4, 48.4), (3.0, 47.6), (0.9, 47.9)]),      # 巴黎盆地
        (":", [(2.2, 51.2), (3.4, 51.6), (4.0, 52.9), (5.2, 53.2), (6.0, 52.4), (6.0, 51.0),
               (4.6, 50.5), (2.4, 50.5)]),                                     # 法蘭德斯、布拉班特、荷蘭
        (":", [(6.0, 51.6), (7.2, 51.6), (7.1, 50.7), (6.1, 50.8)]),           # 下萊茵
        (":", [(7.4, 49.0), (8.5, 49.6), (8.6, 49.0), (7.9, 47.6), (7.4, 47.6)]),      # 上萊茵平原
        (":", [(7.4, 45.5), (10.5, 45.6), (10.5, 44.8), (7.6, 44.6)]),         # 波河平原
        (":", [(-0.6, 45.4), (1.6, 44.6), (1.8, 43.5), (0.0, 43.4), (-0.2, 44.6)]),    # 亞奎丹
        (":", [(-1.6, 47.6), (0.9, 47.9), (0.9, 47.1), (-1.6, 46.9)]),         # 羅亞爾河谷
        (":", [(-1.4, 49.4), (0.8, 49.8), (0.8, 48.6), (-1.2, 48.6)]),         # 諾曼第
        (":", [(4.5, 47.3), (5.3, 47.3), (5.1, 44.2), (4.5, 44.2)]),           # 隆河-索恩河走廊
        (":", [(2.6, 43.6), (4.1, 43.8), (4.1, 43.4), (2.9, 43.0)]),           # 朗格多克
        (":", [(-1.8, 52.9), (1.7, 52.9), (1.5, 51.9), (-1.8, 51.8)]),         # 東英格蘭、中部
        (":", [(6.6, 46.9), (8.9, 47.6), (9.2, 47.4), (7.0, 46.5)]),           # 瑞士高原
        (":", [(7.0, 53.3), (9.3, 53.3), (9.3, 52.2), (7.0, 52.2)]),           # 北德平原
        ("f", [(-1.4, 44.8), (-0.3, 44.7), (-0.2, 43.8), (-1.4, 43.7)]),       # 朗德森林
        ("f", [(1.4, 47.8), (2.4, 47.8), (2.4, 47.3), (1.4, 47.3)]),           # 索洛涅
        ("f", [(4.7, 49.4), (5.6, 49.3), (5.6, 48.7), (4.8, 48.7)]),           # 阿爾貢
        ("f", [(9.4, 53.3), (10.5, 53.3), (10.5, 52.7), (9.4, 52.7)]),         # 呂訥堡石楠原
        ("f", [(-0.4, 51.2), (0.9, 51.2), (0.8, 50.95), (-0.4, 50.95)]),       # 威爾德
        ("s", [(4.2, 43.7), (4.9, 43.7), (4.9, 43.35), (4.2, 43.4)]),          # 卡馬格
    ],
}


THEATRES = [POLAND_1939, FRANCE_1940]
BY_ID = {t["id"]: t for t in THEATRES}

# 所有戰區的城市，鍵不重複。給 genstrings 補譯名用。
CITIES = {}
for _theatre in THEATRES:
    for _city in _theatre["cities"]:
        CITIES.setdefault(_city[0], _city)


def grid_for(theatre):
    """
    真實比例的格子。

    欄數是給定的；列數由它推出來，讓一格在東西向與南北向代表同樣的公里數
    （以戰區中央的緯度為準）。這個範圍內等距圓柱投影的變形只有幾個百分點，
    所以地圖上的形狀就是地圖集上的形狀。
    """
    lon_span = theatre["lon_max"] - theatre["lon_min"]
    lat_span = theatre["lat_max"] - theatre["lat_min"]
    middle = math.radians((theatre["lat_max"] + theatre["lat_min"]) / 2.0)
    column_degrees = lon_span / theatre["cols"]
    row_degrees = column_degrees * math.cos(middle) * ROW_RATIO
    rows = max(1, int(round(lat_span / row_degrees)))
    return hexraster.Grid(theatre["cols"], rows,
                          theatre["lon_min"], theatre["lon_max"],
                          theatre["lat_max"], theatre["lat_min"])


def _in_land(lon, lat, land):
    """點是否在陸地上：在某個多邊形的外環裡，而且不在它的任何內環（內海）裡。"""
    for rings in land:
        if hexraster.point_in_polygon(lon, lat, rings[0]):
            if not any(hexraster.point_in_polygon(lon, lat, hole) for hole in rings[1:]):
                return True
    return False


# 判斷一格是陸是海時的取樣點（以格寬、格高為單位的偏移）：中心加四角。
# 只取中心的話，海岸線剛好擦過格子中心的那幾格會隨機地忽陸忽海。
_SAMPLES = ((0.0, 0.0), (-0.28, -0.28), (0.28, -0.28), (-0.28, 0.28), (0.28, 0.28))


def build_terrain(theatre, grid):
    """
    回傳 (terrain_codes, is_land)，逐格的一維串列。

    城市所在的格子一定是陸地、而且車輛進得去 —— 這件事由呼叫端在放好城市之後
    用 [settle_city] 保證，這裡只管自然地理。
    """
    data = naturalearth.load(theatre["id"])
    land = data["land"]
    count = grid.cols * grid.rows
    lon_step = (theatre["lon_max"] - theatre["lon_min"]) / grid.cols
    lat_step = (theatre["lat_max"] - theatre["lat_min"]) / grid.rows

    lonlat = [None] * count
    is_land = [False] * count
    for row in range(grid.rows):
        for col in range(grid.cols):
            i = grid.index(col, row)
            lon, lat = grid.lonlat(col, row)
            lonlat[i] = (lon, lat)
            hits = sum(
                1 for dx, dy in _SAMPLES
                if _in_land(lon + dx * lon_step, lat + dy * lat_step, land)
            )
            is_land[i] = hits * 2 > len(_SAMPLES)

    terrain = ["~"] * count
    for i in range(count):
        if not is_land[i]:
            continue
        lon, lat = lonlat[i]
        code = "."
        for kind, polygon in theatre.get("cover", ()):
            if hexraster.point_in_polygon(lon, lat, polygon):
                code = kind
        for kind, polygon in theatre.get("relief", ()):
            if hexraster.point_in_polygon(lon, lat, polygon):
                code = kind
        terrain[i] = code

    # 河流：沿線取樣，落在哪一格就把那一格標成河，得到一條連續、一格寬的河道。
    # 山裡的河不畫。
    for river in data["rivers"]:
        for i in _cells_along(theatre, grid, river["points"]):
            if is_land[i] and terrain[i] not in "^":
                terrain[i] = "r"

    # 海：離岸兩格以內是淺海（登陸與沿岸航行都需要它），再外面才是深海。
    near = set()
    for i in range(count):
        if is_land[i]:
            continue
        col, row = i % grid.cols, i // grid.cols
        if any(is_land[grid.index(c, r)] for c, r in grid.neighbours(col, row)):
            near.add(i)
    shallow = set(near)
    for i in near:
        for c, r in grid.neighbours(i % grid.cols, i // grid.cols):
            j = grid.index(c, r)
            if not is_land[j]:
                shallow.add(j)
    for i in shallow:
        terrain[i] = "-"

    return terrain, is_land


def _cell_at(theatre, grid, lon, lat):
    """經緯度落在哪一格；在範圍外回 None。戰區的格子是等距的，可以直接算。"""
    fy = (theatre["lat_max"] - lat) / (theatre["lat_max"] - theatre["lat_min"])
    row = int(math.floor(fy * grid.rows))
    if not 0 <= row < grid.rows:
        return None
    fx = (lon - theatre["lon_min"]) / (theatre["lon_max"] - theatre["lon_min"])
    shift = 0.5 if row % 2 == 1 else 0.0
    col = int(math.floor(fx * grid.cols - shift))
    # 奇數列往右偏半格，所以最左邊那半格算進第 0 欄。
    col = max(col, 0)
    if col >= grid.cols:
        return None
    return grid.index(col, row)


# 取樣間距是格子短邊的 1/8；一條河在一格裡至少要留下這麼多個取樣點（約半格長）才算流經它。
_RIVER_SAMPLES_PER_CELL = 8
_MIN_RIVER_SAMPLES = 4


def _cells_along(theatre, grid, points):
    """一條折線流經的格子（見 [_MIN_RIVER_SAMPLES]）。"""
    step = min((theatre["lon_max"] - theatre["lon_min"]) / grid.cols,
               (theatre["lat_max"] - theatre["lat_min"]) / grid.rows) / _RIVER_SAMPLES_PER_CELL
    hits = {}
    order = []
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        steps = max(1, int(math.hypot(x1 - x0, y1 - y0) / step) + 1)
        for k in range(steps):
            t = k / steps
            i = _cell_at(theatre, grid, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
            if i is None:
                continue
            if i not in hits:
                hits[i] = 0
                order.append(i)
            hits[i] += 1
    # 河只擦過一角的格子不算：不濾掉的話，斜向的河道會變成兩格寬的階梯。
    return [i for i in order if hits[i] >= _MIN_RIVER_SAMPLES]


def settle_city(terrain, tile):
    """城市格要讓所有陸軍都進得去：山與沼澤上的城改成丘陵與平原，河上的城改成平原。"""
    if terrain[tile] == "^":
        terrain[tile] = "h"
    elif terrain[tile] in "sr":
        terrain[tile] = "."
