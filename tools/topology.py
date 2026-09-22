# SPDX-FileCopyrightText: 2026 AcideFluorhydrique
# SPDX-License-Identifier: GPL-3.0-or-later
"""
世界地圖的拓撲檢查：哪些國家之間該有陸路、哪些之間一定隔著海。

世界地圖是刻意變形的（歐洲、日本放大，兩大洋壓扁，見 genworld 的分段權重），
變形本身不會改變拓撲 —— 經緯度對格子的映射在兩個軸上都是單調的。會改壞拓撲
的是別的東西：手繪多邊形之間留了縫（芬蘭、印度河流域曾經整片是海）、海峽窄
到網格上只剩零格（釜山與福岡相鄰，日本的部隊可以走進韓國）、省份從太少的
城市往外長，長過了國界。這些錯誤在產出的檔案裡完全看不出來，只有玩起來才會
發現國家擺錯了地方。

所以把地理事實寫成兩張表，每次產生地圖都驗一遍，錯了就讓產生器失敗：

- LAND_BORDERS：真實世界裡接壤、地圖上也必須有陸路相接的國家。
- SEA_BETWEEN：真實世界裡隔著海、地圖上絕對不能陸地相鄰的國家。

兩張表都用現代國家代碼，對應 places.PROVINCES 的歸屬 —— 劇本會把國家合併
（1939 年朝鮮歸日本），但地理是合併之前的事。沒有城市的國家不在地圖上，
它的鄰國在地圖上直接相接是預期中的（沒有約旦，以色列就跟沙烏地接壤），
所以表裡只收兩邊都有城市的配對。
"""

LAND_BORDERS = [
    # 北歐：芬蘭夾在瑞典與俄羅斯之間，不是一塊浮在波羅的海上的地。
    ("FIN", "SWE"), ("FIN", "RUS"), ("NOR", "SWE"),
    # 北愛爾蘭：愛爾蘭島上唯一的陸上國界。
    ("GBR", "IRL"),
    # 直布羅陀
    ("GBR", "ESP"),
    # 西歐與中歐
    ("ESP", "FRA"), ("ESP", "PRT"), ("FRA", "BEL"), ("FRA", "DEU"),
    ("FRA", "CHE"), ("FRA", "ITA"), ("DEU", "POL"), ("DEU", "NLD"),
    ("DEU", "DNK"), ("DEU", "CZE"), ("DEU", "AUT"), ("POL", "UKR"),
    ("POL", "BLR"), ("RUS", "UKR"), ("RUS", "BLR"),
    # 中東與南亞
    ("EGY", "ISR"), ("EGY", "LBY"), ("EGY", "SDN"), ("IRQ", "IRN"),
    ("IRQ", "SAU"), ("IRQ", "SYR"), ("SAU", "YEM"), ("ARE", "SAU"),
    ("ARE", "OMN"), ("IRN", "TUR"), ("IRN", "AFG"), ("IRN", "PAK"),
    ("AFG", "PAK"), ("IND", "PAK"), ("IND", "CHN"), ("IND", "NPL"),
    ("IND", "BGD"), ("IND", "MMR"),
    # 東亞：朝鮮半島接在中國身上。
    ("KOR", "PRK"), ("PRK", "CHN"), ("CHN", "RUS"), ("CHN", "MNG"),
    ("CHN", "VNM"),
    # 美洲。巴西與阿根廷只在米西奧內斯接壤，一度寬，這個解析度下由
    # 巴拉圭與烏拉圭隔開是可以接受的，不列。
    ("CAN", "USA"), ("USA", "MEX"), ("ARG", "CHL"),
]

SEA_BETWEEN = [
    # 島國對大陸
    ("GBR", "FRA"), ("GBR", "BEL"), ("GBR", "NLD"),
    ("JPN", "KOR"), ("JPN", "PRK"), ("JPN", "CHN"), ("JPN", "RUS"),
    ("TWN", "CHN"), ("LKA", "IND"), ("CUB", "USA"), ("MDG", "MOZ"),
    ("AUS", "IDN"), ("AUS", "PNG"), ("NZL", "AUS"), ("PHL", "CHN"),
    ("PHL", "MYS"), ("ISL", "GBR"), ("ISL", "NOR"),
    # 波羅的海與北海：芬蘭灣、波的尼亞灣、斯卡格拉克。
    ("FIN", "LVA"), ("FIN", "BLR"), ("SWE", "LVA"), ("SWE", "POL"),
    ("SWE", "DEU"), ("NOR", "DNK"), ("DNK", "SWE"),
    # 地中海
    ("ITA", "TUN"), ("ITA", "LBY"), ("ESP", "MAR"), ("GBR", "MAR"), ("GRC", "EGY"),
    ("GRC", "LBY"), ("TUR", "EGY"), ("SYR", "EGY"),
    # 紅海、曼德海峽、荷莫茲、阿拉伯海
    ("SAU", "SDN"), ("SAU", "EGY"), ("YEM", "ETH"), ("YEM", "SOM"),
    ("ARE", "IRN"), ("OMN", "IRN"), ("OMN", "PAK"), ("OMN", "IND"),
    ("SAU", "IRN"),
]

# 本土必須連成一片的國家（不含離島）。挪威曾經被瑞典的省從中間切斷：
# 松茲瓦爾一路長到大西洋岸，奧斯陸與納爾維克之間沒有陸路。
CONTIGUOUS = ["NOR", "SWE", "FIN", "DNK", "CHL", "VNM", "KOR", "PRK", "ESP", "FRA", "DEU", "POL"]


def _neighbours(grid, tile, wrap):
    col, row = tile % grid.cols, tile // grid.cols
    odd = row % 2
    for dc, dr in ((1, 0), (-1, 0),
                   (1 if odd else 0, -1), (0 if odd else -1, -1),
                   (1 if odd else 0, 1), (0 if odd else -1, 1)):
        c, r = col + dc, row + dr
        if not 0 <= r < grid.rows:
            continue
        if wrap:
            c %= grid.cols
        elif not 0 <= c < grid.cols:
            continue
        yield grid.index(c, r)


def nation_land_borders(built, wrap):
    """{(A, B): 相鄰的格子邊數}，A < B，以 places 的國家歸屬計算。"""
    nation = [p[2] for p in built.provinces]
    out = {}
    for tile, land in enumerate(built.is_land):
        if not land:
            continue
        a = nation[built.province_of[tile]]
        for other in _neighbours(built.grid, tile, wrap):
            if not built.is_land[other]:
                continue
            b = nation[built.province_of[other]]
            if a < b:
                out[(a, b)] = out.get((a, b), 0) + 1
    return out


def check(built, wrap=True):
    """回傳違規說明的串列；空串列代表通過。"""
    present = {p[2] for p in built.provinces}
    borders = nation_land_borders(built, wrap)

    def touching(a, b):
        return borders.get((min(a, b), max(a, b)), 0) > 0

    errors = []
    for a, b in LAND_BORDERS:
        if a in present and b in present and not touching(a, b):
            errors.append("%s 與 %s 應該陸地接壤，地圖上卻不相連" % (a, b))
    for a, b in SEA_BETWEEN:
        if a in present and b in present and touching(a, b):
            errors.append("%s 與 %s 之間應該隔著海，地圖上卻陸地相鄰" % (a, b))
    for code in CONTIGUOUS:
        pieces = _land_pieces(built, code, wrap)
        if pieces > 1:
            errors.append("%s 的國土被切成 %d 塊" % (code, pieces))
    return errors


def _land_pieces(built, code, wrap):
    """[code] 的陸地格分成幾個連通塊。"""
    nation = [p[2] for p in built.provinces]
    tiles = {t for t, land in enumerate(built.is_land)
             if land and nation[built.province_of[t]] == code}
    pieces = 0
    while tiles:
        pieces += 1
        stack = [tiles.pop()]
        while stack:
            t = stack.pop()
            for n in _neighbours(built.grid, t, wrap):
                if n in tiles:
                    tiles.remove(n)
                    stack.append(n)
    return pieces
