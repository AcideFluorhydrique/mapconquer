// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.units

/**
 * 部隊走在哪裡。
 *
 * 沒有空軍這一層：飛機不是棋子，是一次性的出擊（見 game.AirOps）。
 */
enum class Domain { LAND, SEA }

/**
 * 被攻擊時算的是哪一類目標。
 *
 * 攻擊力做成「對四類目標各一個數字」，而不是單一攻擊值再乘一堆修正 ——
 * 這是整個戰鬥系統的骨架：反坦克炮打步兵很差、打戰車很好，
 * 這件事直接寫在數字裡，玩家看面板就懂，不必去猜隱藏公式。
 */
enum class TargetClass {
    SOFT, ARMOURED, SHIP,

    /**
     * 沒有任何部隊屬於這一類 —— 飛機已經不是部隊了。攻擊表裡這一欄留著，
     * 意思變成「防空火力」：目標兩格內每一門有這一欄的敵軍，都會削弱空中打擊。
     */
    AIRCRAFT
}

/** 研發分支。升級只影響同分支的單位。 */
enum class TechBranch { INFANTRY, ARMOUR, ARTILLERY, AIR, NAVY, LOGISTICS }

/**
 * 兵種表。
 *
 * 所有數值都是常數：本作沒有隨機生成的單位，一支步兵在任何劇本裡都是同一支步兵，
 * 差別只在等級、補給、指揮官與科技。這讓玩家在戰役學到的東西可以直接帶進征服模式。
 *
 * 攻擊、生命、防禦的數量級對齊參考遊戲（見 docs/original-behavior.md 第 6 節）：
 * 一次攻擊在 [attackMin]..[attackMax] 之間擲一個整數，防禦是個位數到二十幾的小數字，
 * 每 1 點防禦把傷害的分母加大 1.6%。
 *
 * [versus] 是本專案自己的兵種相剋：對四類目標各一個百分比，0 代表打不到。
 * 參考遊戲用兵種特性做同樣的事（反坦克打裝甲有加成、艦炮打陸地減半），
 * 本專案把它攤平成一張表，玩家看面板就懂。四個值依序對應 [TargetClass]。
 */
enum class UnitKind(
    val key: String,
    val domain: Domain,
    val targetClass: TargetClass,
    val branch: TechBranch,
    /** 一次攻擊擲骰的下限與上限（含）。 */
    val attackMin: Int,
    val attackMax: Int,
    /** 對 SOFT / ARMOURED / SHIP / AIRCRAFT 的效果百分比。 */
    val versus: IntArray,
    /** 一個編制、一級時的最大生命。 */
    val hp: Int,
    val defence: Int,
    val movement: Int,
    /** 射程下限。> 1 代表這是砲兵，只能遠射、不能近戰。 */
    val minRange: Int,
    val maxRange: Int,
    val vision: Int,
    val cost: Int,
    /** 生產所需的工業等級，城市等級不足就造不出來。 */
    val industry: Int,
    /** 載運格數；0 代表不能載。 */
    val capacity: Int,
    val canCapture: Boolean,
    /** 輪車／履帶：進不了山地、叢林、沼澤。 */
    val vehicle: Boolean,
    /** 每回合基礎補給消耗。 */
    val upkeep: Int,
    val flags: Int
) {

    // ---- 陸軍 ----
    INFANTRY(
        "unit_infantry", Domain.LAND, TargetClass.SOFT, TechBranch.INFANTRY,
        16, 22, intArrayOf(100, 50, 30, 40), 80, 1, 4, 1, 1, 2,
        90, 0, 0, true, false, 4, 0
    ),
    MOUNTAIN_INFANTRY(
        "unit_mountain_infantry", Domain.LAND, TargetClass.SOFT, TechBranch.INFANTRY,
        18, 24, intArrayOf(100, 50, 25, 35), 90, 2, 4, 1, 1, 3,
        130, 1, 0, true, false, 4, Flags.MOUNTAINEER
    ),
    MARINE(
        "unit_marine", Domain.LAND, TargetClass.SOFT, TechBranch.INFANTRY,
        18, 26, intArrayOf(100, 55, 40, 30), 90, 2, 4, 1, 1, 2,
        150, 1, 0, true, false, 5, Flags.AMPHIBIOUS
    ),
    RECON(
        "unit_recon", Domain.LAND, TargetClass.ARMOURED, TechBranch.ARMOUR,
        20, 34, intArrayOf(100, 55, 25, 30), 120, 6, 9, 1, 1, 5,
        140, 1, 0, true, true, 5, Flags.RECON or Flags.ASSAULT
    ),
    ARMOUR(
        "unit_armour", Domain.LAND, TargetClass.ARMOURED, TechBranch.ARMOUR,
        28, 44, intArrayOf(100, 80, 25, 15), 180, 10, 7, 1, 1, 3,
        240, 2, 0, true, true, 8, Flags.BREAKTHROUGH or Flags.ASSAULT
    ),
    /**
     * 反坦克炮把「兵種相剋」的三角關係閉合起來：
     * 反坦克剋裝甲、裝甲剋步兵、步兵剋反坦克。
     *
     * 它被歸類為 SOFT 而不是 ARMOURED —— 那是一組砲班，不是一輛戰車。
     * 這個分類正是三角形的最後一邊：步兵打得動它，所以它不能單獨守在前面。
     * 對步兵的攻擊力刻意壓得很低，讓它在錯的目標面前真的很沒用。
     */
    ANTI_TANK(
        "unit_anti_tank", Domain.LAND, TargetClass.SOFT, TechBranch.ARTILLERY,
        26, 38, intArrayOf(20, 100, 20, 10), 90, 4, 3, 1, 1, 2,
        160, 1, 0, false, true, 5, 0
    ),
    ARTILLERY(
        "unit_artillery", Domain.LAND, TargetClass.SOFT, TechBranch.ARTILLERY,
        32, 46, intArrayOf(100, 70, 50, 0), 100, 5, 3, 2, 3, 2,
        190, 1, 0, false, true, 6, Flags.BOMBARD
    ),
    ROCKET(
        "unit_rocket", Domain.LAND, TargetClass.SOFT, TechBranch.ARTILLERY,
        34, 50, intArrayOf(100, 60, 45, 0), 95, 5, 3, 2, 4, 2,
        260, 2, 0, false, true, 8, Flags.BOMBARD
    ),
    ANTI_AIR(
        "unit_anti_air", Domain.LAND, TargetClass.ARMOURED, TechBranch.ARTILLERY,
        18, 26, intArrayOf(55, 45, 25, 220), 100, 6, 4, 1, 2, 3,
        150, 1, 0, false, true, 5, Flags.INTERCEPT
    ),
    SUPPLY_TRUCK(
        "unit_supply_truck", Domain.LAND, TargetClass.SOFT, TechBranch.LOGISTICS,
        0, 0, intArrayOf(0, 0, 0, 0), 80, 2, 6, 0, 0, 2,
        110, 1, 1, false, true, 3, Flags.SUPPLIER
    ),
    HEADQUARTERS(
        "unit_headquarters", Domain.LAND, TargetClass.SOFT, TechBranch.LOGISTICS,
        10, 16, intArrayOf(100, 70, 0, 50), 120, 4, 5, 1, 1, 4,
        300, 2, 1, true, true, 6, Flags.SUPPLIER or Flags.COMMAND_AURA
    ),

    // ---- 海軍 ----
    TRANSPORT_SHIP(
        "unit_transport_ship", Domain.SEA, TargetClass.SHIP, TechBranch.NAVY,
        0, 0, intArrayOf(0, 0, 0, 0), 120, 4, 7, 0, 0, 3,
        150, 1, 3, false, false, 4, 0
    ),
    DESTROYER(
        "unit_destroyer", Domain.SEA, TargetClass.SHIP, TechBranch.NAVY,
        32, 52, intArrayOf(50, 40, 100, 70), 260, 12, 8, 1, 1, 5,
        220, 2, 0, false, false, 6, Flags.SUB_HUNTER or Flags.INTERCEPT
    ),
    CRUISER(
        "unit_cruiser", Domain.SEA, TargetClass.SHIP, TechBranch.NAVY,
        40, 60, intArrayOf(60, 50, 100, 80), 380, 18, 7, 1, 2, 4,
        320, 3, 0, false, false, 8, Flags.INTERCEPT
    ),
    /**
     * 戰艦的射程 1..3 已經給了它岸轟能力：[Combat.canRetaliate] 只看距離，
     * 隔著兩格開火本來就不會被還手。
     *
     * 它刻意**沒有** [Flags.BOMBARD] —— 那個旗標的意思是「只能遠射、被貼身時還不了手」，
     * 適用於火炮與火箭炮那種薄皮兵器。戰艦貼身照樣開火。
     */
    BATTLESHIP(
        "unit_battleship", Domain.SEA, TargetClass.SHIP, TechBranch.NAVY,
        56, 80, intArrayOf(90, 75, 100, 30), 600, 24, 6, 1, 3, 4,
        480, 4, 0, false, false, 11, 0
    ),
    SUBMARINE(
        "unit_submarine", Domain.SEA, TargetClass.SHIP, TechBranch.NAVY,
        28, 48, intArrayOf(10, 10, 100, 0), 180, 6, 7, 1, 1, 3,
        280, 3, 0, false, false, 7, Flags.STEALTH
    ),
    CARRIER(
        "unit_carrier", Domain.SEA, TargetClass.SHIP, TechBranch.NAVY,
        16, 26, intArrayOf(60, 50, 70, 125), 300, 18, 7, 1, 1, 5,
        520, 4, 0, false, false, 10, Flags.AIRBASE
    );

    val descKey: String get() = key + "_desc"

    /** 砲兵：只能遠射，被貼身時打不還手。 */
    val isBombard: Boolean get() = flags and Flags.BOMBARD != 0
    val isSupplier: Boolean get() = flags and Flags.SUPPLIER != 0
    val isStealth: Boolean get() = flags and Flags.STEALTH != 0
    val isRecon: Boolean get() = flags and Flags.RECON != 0
    val isMountaineer: Boolean get() = flags and Flags.MOUNTAINEER != 0
    val isAmphibious: Boolean get() = flags and Flags.AMPHIBIOUS != 0
    val hasCommandAura: Boolean get() = flags and Flags.COMMAND_AURA != 0
    val isInterceptor: Boolean get() = flags and Flags.INTERCEPT != 0
    val isSubHunter: Boolean get() = flags and Flags.SUB_HUNTER != 0
    val isAirbase: Boolean get() = flags and Flags.AIRBASE != 0
    val isBreakthrough: Boolean get() = flags and Flags.BREAKTHROUGH != 0

    /** 殲滅目標後可以再打一次。這是裝甲之所以能決定戰局的原因。 */
    val isAssault: Boolean get() = flags and Flags.ASSAULT != 0

    val canAttack: Boolean get() = attackMax > 0 && versus.any { it > 0 }

    /** 只能停在海上，且需要臨海城市才造得出來。 */
    val isNaval: Boolean get() = domain == Domain.SEA

    /** 對這類目標的效果百分比；0 代表打不到。 */
    fun versus(target: TargetClass): Int = if (attackMax > 0) versus[target.ordinal] else 0

    /** 平均一擊的攻擊值（未計任何修正），面板與防空估算用。 */
    val averageAttack: Int get() = (attackMin + attackMax) / 2

    /** 對這類目標的平均攻擊值，給玩家看的那個數字。 */
    fun attackAgainst(target: TargetClass): Int = averageAttack * versus(target) / 100

    /** 這個兵種載得動什麼。只剩運輸艦載陸軍；航艦現在是空中任務的起飛點。 */
    fun canCarry(other: UnitKind): Boolean = when {
        capacity <= 0 -> false
        this == TRANSPORT_SHIP -> other.domain == Domain.LAND
        this == SUPPLY_TRUCK || this == HEADQUARTERS -> false
        else -> false
    }

    companion object {
        val ALL: Array<UnitKind> = values()

        private val byKey = HashMap<String, UnitKind>(ALL.size * 2).apply {
            for (k in ALL) {
                put(k.name, k)
                put(k.key, k)
            }
        }

        fun byName(name: String): UnitKind? = byKey[name.trim().uppercase()] ?: byKey[name.trim()]

        /** 依 domain 分組，生產面板照這個順序排。 */
        fun buildable(domain: Domain): List<UnitKind> = ALL.filter { it.domain == domain }
    }
}

/** 兵種旗標。用位元而不是一堆 Boolean 欄位，純粹是為了讓上面的表讀得完。 */
object Flags {
    /**
     * 只能遠射，被貼身時還不了手。
     * 帶這個旗標的兵種 minRange 必須 > 1 —— 「射程涵蓋一格」與「近戰無力」
     * 是互相矛盾的兩件事，CombatTest 會把這條不變量釘住。
     */
    const val BOMBARD = 1 shl 0
    const val SUPPLIER = 1 shl 1
    const val STEALTH = 1 shl 2
    const val RECON = 1 shl 3
    const val MOUNTAINEER = 1 shl 4
    const val AMPHIBIOUS = 1 shl 5
    const val COMMAND_AURA = 1 shl 6
    const val INTERCEPT = 1 shl 7
    const val SUB_HUNTER = 1 shl 8
    /** 空中任務的起飛點：航艦把機場帶到海上。 */
    const val AIRBASE = 1 shl 9
    const val BREAKTHROUGH = 1 shl 10

    /**
     * 突擊：擊毀目標之後可以立刻再攻擊一次，能連鎖下去。
     *
     * 這一條讓裝甲從「數值比較高的兵」變成「能一口氣打穿一條戰線的兵」，
     * 也是戰車值得那個價錢的唯一理由。連鎖會自然停止 ——
     * 打不死下一個目標就結束了。
     */
    const val ASSAULT = 1 shl 11
}
