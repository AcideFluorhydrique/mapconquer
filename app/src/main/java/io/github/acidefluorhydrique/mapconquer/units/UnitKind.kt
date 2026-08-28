// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.units

/** 單位活動的層面。三層互不重疊，同一格可以同時站一支陸軍與一架飛機。 */
enum class Domain { LAND, SEA, AIR }

/**
 * 被攻擊時算的是哪一類目標。
 *
 * 攻擊力做成「對四類目標各一個數字」，而不是單一攻擊值再乘一堆修正 ——
 * 這是整個戰鬥系統的骨架：反坦克炮打步兵很差、打戰車很好，
 * 這件事直接寫在數字裡，玩家看面板就懂，不必去猜隱藏公式。
 */
enum class TargetClass { SOFT, ARMOURED, SHIP, AIRCRAFT }

/** 研發分支。升級只影響同分支的單位。 */
enum class TechBranch { INFANTRY, ARMOUR, ARTILLERY, AIR, NAVY, LOGISTICS }

/**
 * 兵種表。
 *
 * 所有數值都是常數：本作沒有隨機生成的單位，一支步兵在任何劇本裡都是同一支步兵，
 * 差別只在等級、補給、指揮官與科技。這讓玩家在戰役學到的東西可以直接帶進征服模式。
 *
 * [attack] 的四個值依序對應 [TargetClass] 的四個成員。
 */
enum class UnitKind(
    val key: String,
    val domain: Domain,
    val targetClass: TargetClass,
    val branch: TechBranch,
    /** 對 SOFT / ARMOURED / SHIP / AIRCRAFT 的攻擊力。 */
    val attack: IntArray,
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
        intArrayOf(22, 10, 6, 8), 26, 4, 1, 1, 2,
        90, 0, 0, true, false, 4, 0
    ),
    MOUNTAIN_INFANTRY(
        "unit_mountain_infantry", Domain.LAND, TargetClass.SOFT, TechBranch.INFANTRY,
        intArrayOf(24, 12, 6, 8), 28, 4, 1, 1, 3,
        130, 1, 0, true, false, 4, Flags.MOUNTAINEER
    ),
    MARINE(
        "unit_marine", Domain.LAND, TargetClass.SOFT, TechBranch.INFANTRY,
        intArrayOf(26, 14, 10, 8), 26, 4, 1, 1, 2,
        150, 1, 0, true, false, 5, Flags.AMPHIBIOUS
    ),
    RECON(
        "unit_recon", Domain.LAND, TargetClass.ARMOURED, TechBranch.ARMOUR,
        intArrayOf(24, 14, 6, 8), 22, 9, 1, 1, 5,
        140, 1, 0, true, true, 5, Flags.RECON
    ),
    ARMOUR(
        "unit_armour", Domain.LAND, TargetClass.ARMOURED, TechBranch.ARMOUR,
        intArrayOf(40, 32, 10, 6), 38, 7, 1, 1, 3,
        240, 2, 0, true, true, 8, Flags.BREAKTHROUGH
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
        intArrayOf(8, 50, 8, 4), 24, 3, 1, 1, 2,
        160, 1, 0, false, true, 5, 0
    ),
    ARTILLERY(
        "unit_artillery", Domain.LAND, TargetClass.SOFT, TechBranch.ARTILLERY,
        intArrayOf(38, 26, 20, 0), 14, 3, 2, 3, 2,
        190, 1, 0, false, true, 6, Flags.BOMBARD
    ),
    ROCKET(
        "unit_rocket", Domain.LAND, TargetClass.SOFT, TechBranch.ARTILLERY,
        intArrayOf(46, 28, 20, 0), 12, 3, 2, 4, 2,
        260, 2, 0, false, true, 8, Flags.BOMBARD
    ),
    ANTI_AIR(
        "unit_anti_air", Domain.LAND, TargetClass.ARMOURED, TechBranch.ARTILLERY,
        intArrayOf(12, 10, 6, 52), 20, 4, 1, 2, 3,
        150, 1, 0, false, true, 5, Flags.INTERCEPT
    ),
    SUPPLY_TRUCK(
        "unit_supply_truck", Domain.LAND, TargetClass.SOFT, TechBranch.LOGISTICS,
        intArrayOf(0, 0, 0, 0), 10, 6, 0, 0, 2,
        110, 1, 1, false, true, 3, Flags.SUPPLIER
    ),
    HEADQUARTERS(
        "unit_headquarters", Domain.LAND, TargetClass.SOFT, TechBranch.LOGISTICS,
        intArrayOf(8, 6, 0, 4), 18, 5, 1, 1, 4,
        300, 2, 1, true, true, 6, Flags.SUPPLIER or Flags.COMMAND_AURA
    ),

    // ---- 空軍 ----
    FIGHTER(
        "unit_fighter", Domain.AIR, TargetClass.AIRCRAFT, TechBranch.AIR,
        intArrayOf(18, 12, 14, 54), 30, 9, 1, 1, 5,
        220, 2, 0, false, false, 10, Flags.INTERCEPT
    ),
    BOMBER(
        "unit_bomber", Domain.AIR, TargetClass.AIRCRAFT, TechBranch.AIR,
        intArrayOf(50, 42, 44, 10), 22, 7, 1, 1, 4,
        320, 3, 0, false, false, 12, 0
    ),
    AIR_TRANSPORT(
        "unit_air_transport", Domain.AIR, TargetClass.AIRCRAFT, TechBranch.LOGISTICS,
        intArrayOf(0, 0, 0, 0), 16, 10, 0, 0, 4,
        200, 2, 2, false, false, 9, Flags.SUPPLIER
    ),

    // ---- 海軍 ----
    TRANSPORT_SHIP(
        "unit_transport_ship", Domain.SEA, TargetClass.SHIP, TechBranch.NAVY,
        intArrayOf(0, 0, 0, 0), 16, 7, 0, 0, 3,
        150, 1, 3, false, false, 4, 0
    ),
    DESTROYER(
        "unit_destroyer", Domain.SEA, TargetClass.SHIP, TechBranch.NAVY,
        intArrayOf(20, 16, 40, 30), 30, 8, 1, 1, 5,
        220, 2, 0, false, false, 6, Flags.SUB_HUNTER or Flags.INTERCEPT
    ),
    CRUISER(
        "unit_cruiser", Domain.SEA, TargetClass.SHIP, TechBranch.NAVY,
        intArrayOf(30, 24, 46, 40), 40, 7, 1, 2, 4,
        320, 3, 0, false, false, 8, Flags.INTERCEPT
    ),
    BATTLESHIP(
        "unit_battleship", Domain.SEA, TargetClass.SHIP, TechBranch.NAVY,
        intArrayOf(54, 46, 60, 20), 55, 6, 1, 3, 4,
        480, 4, 0, false, false, 11, Flags.BOMBARD
    ),
    SUBMARINE(
        "unit_submarine", Domain.SEA, TargetClass.SHIP, TechBranch.NAVY,
        intArrayOf(6, 6, 62, 0), 18, 7, 1, 1, 3,
        280, 3, 0, false, false, 7, Flags.STEALTH
    ),
    CARRIER(
        "unit_carrier", Domain.SEA, TargetClass.SHIP, TechBranch.NAVY,
        intArrayOf(10, 8, 14, 26), 42, 7, 1, 1, 5,
        520, 4, 3, false, false, 10, Flags.AIRBASE
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

    val canAttack: Boolean get() = attack.any { it > 0 }

    /** 只能停在海上，且需要臨海城市才造得出來。 */
    val isNaval: Boolean get() = domain == Domain.SEA

    fun attackAgainst(target: TargetClass): Int = attack[target.ordinal]

    /** 這個兵種載得動什麼。運輸艦載陸軍、航艦載飛機、卡車載步兵。 */
    fun canCarry(other: UnitKind): Boolean = when {
        capacity <= 0 -> false
        this == CARRIER -> other.domain == Domain.AIR
        this == AIR_TRANSPORT -> other.domain == Domain.LAND && !other.vehicle
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
    const val BOMBARD = 1 shl 0
    const val SUPPLIER = 1 shl 1
    const val STEALTH = 1 shl 2
    const val RECON = 1 shl 3
    const val MOUNTAINEER = 1 shl 4
    const val AMPHIBIOUS = 1 shl 5
    const val COMMAND_AURA = 1 shl 6
    const val INTERCEPT = 1 shl 7
    const val SUB_HUNTER = 1 shl 8
    const val AIRBASE = 1 shl 9
    const val BREAKTHROUGH = 1 shl 10
}
