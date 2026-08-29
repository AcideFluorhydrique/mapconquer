// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.units

/**
 * 指揮官技能。
 *
 * 每個技能只碰一個數字，而且都是乘法或加法的小修正 —— 這是刻意的：
 * 玩家要能在戰前就大致算出「這位配上去差多少」，
 * 而不是打完才發現有隱藏規則在後面翻盤。
 */
enum class CommanderSkill(val key: String) {
    OFFENSIVE("skill_offensive"),
    DEFENSIVE("skill_defensive"),
    BLITZ("skill_blitz"),
    LOGISTICS("skill_logistics"),
    ARMOUR_EXPERT("skill_armour_expert"),
    AIR_EXPERT("skill_air_expert"),
    NAVAL_EXPERT("skill_naval_expert"),
    ARTILLERY_EXPERT("skill_artillery_expert"),
    FORTRESS("skill_fortress"),
    SIEGE("skill_siege"),
    VETERAN("skill_veteran"),
    FIELD_MEDIC("skill_field_medic"),
    SCOUT("skill_scout"),
    IRON_WILL("skill_iron_will"),

    /**
     * 謠言：攻擊之後有機會讓目標的士氣再降一級。
     *
     * 它之所以是全遊戲最泛用的技能，是因為它把「打不死」變成「打殘廢」——
     * 累積到混亂的敵人完全無法攻擊也無法還手，等於少了一個單位。
     */
    RUMOUR("skill_rumour");

    val descKey: String get() = key + "_desc"
}

/**
 * 指揮官。
 *
 * 名字全部是虛構的，並且刻意跨語系取材 —— 這是一款自由軟體遊戲，
 * 不掛任何真實人物，也不影射任何既有作品的角色表。
 * 玩家用戰役賺到的勳章招募他們，招募一次就永久可用，
 * 所以戰役有理由重打（拿三星＝多拿勳章），征服模式也有理由回頭練。
 */
class Commander(
    val id: String,
    val nameKey: String,
    /** 1..5，決定招募價與技能數量。 */
    val rank: Int,
    val skills: List<CommanderSkill>,
    /** 頭像底色。沒有點陣圖素材，用色塊 + 姓名縮寫代替，也省下 APK 體積。 */
    val colour: String,
    val medalCost: Int
) {
    fun has(skill: CommanderSkill): Boolean = skills.contains(skill)

    /** 顯示用縮寫：取譯名的頭一個字元。 */
    fun initial(displayName: String): String =
        if (displayName.isEmpty()) "?" else displayName.substring(0, 1)

    companion object {

        /**
         * 全部指揮官。開局免費給第一位，其餘用勳章解鎖。
         *
         * 技能組合刻意留出「專精」與「通用」兩條線：專精的加成高但只吃一種兵，
         * 通用的加成低卻到處能用 —— 這樣勳章要花在哪本身就是一個決策。
         */
        val ALL: List<Commander> = listOf(
            Commander("cmd_ashby", "cmd_ashby", 2, listOf(CommanderSkill.OFFENSIVE), "#8C5A3C", 0),
            Commander("cmd_bergstrom", "cmd_bergstrom", 2, listOf(CommanderSkill.DEFENSIVE), "#3F6B57", 6),
            Commander("cmd_okonkwo", "cmd_okonkwo", 3, listOf(CommanderSkill.BLITZ, CommanderSkill.OFFENSIVE), "#9A6B2F", 12),
            Commander("cmd_varela", "cmd_varela", 3, listOf(CommanderSkill.ARTILLERY_EXPERT, CommanderSkill.SIEGE), "#7A4A66", 12),
            Commander("cmd_lindqvist", "cmd_lindqvist", 3, listOf(CommanderSkill.LOGISTICS, CommanderSkill.RUMOUR), "#436C87", 14),
            Commander("cmd_haddad", "cmd_haddad", 3, listOf(CommanderSkill.SCOUT, CommanderSkill.RUMOUR), "#8A6A34", 12),
            Commander("cmd_novak", "cmd_novak", 4, listOf(CommanderSkill.ARMOUR_EXPERT, CommanderSkill.BLITZ), "#6A5B8C", 22),
            Commander("cmd_tanaka", "cmd_tanaka", 4, listOf(CommanderSkill.NAVAL_EXPERT, CommanderSkill.SCOUT), "#2F6076", 22),
            Commander("cmd_moreau", "cmd_moreau", 4, listOf(CommanderSkill.AIR_EXPERT, CommanderSkill.OFFENSIVE), "#7C4A3A", 24),
            Commander("cmd_ferreira", "cmd_ferreira", 4, listOf(CommanderSkill.FORTRESS, CommanderSkill.DEFENSIVE), "#4C6A3F", 20),
            Commander("cmd_reyes", "cmd_reyes", 4, listOf(CommanderSkill.FIELD_MEDIC, CommanderSkill.VETERAN), "#8C4F5A", 24),
            Commander("cmd_adeyemi", "cmd_adeyemi", 5, listOf(CommanderSkill.OFFENSIVE, CommanderSkill.BLITZ, CommanderSkill.VETERAN), "#96702B", 40),
            Commander("cmd_sorokin", "cmd_sorokin", 5, listOf(CommanderSkill.DEFENSIVE, CommanderSkill.IRON_WILL, CommanderSkill.RUMOUR), "#4A5C74", 40),
            Commander("cmd_kaur", "cmd_kaur", 5, listOf(CommanderSkill.ARMOUR_EXPERT, CommanderSkill.OFFENSIVE, CommanderSkill.RUMOUR), "#7E4470", 44),
            Commander("cmd_lindholm", "cmd_lindholm", 5, listOf(CommanderSkill.NAVAL_EXPERT, CommanderSkill.AIR_EXPERT, CommanderSkill.SCOUT), "#356B7E", 44),
            Commander("cmd_alvarez", "cmd_alvarez", 5, listOf(CommanderSkill.ARTILLERY_EXPERT, CommanderSkill.LOGISTICS, CommanderSkill.VETERAN), "#7A5A34", 42)
        )

        private val lookup = ALL.associateBy { it.id }

        fun byId(id: String): Commander? = lookup[id]

        /** 開局就有的那一位。 */
        val starter: Commander get() = ALL.first()
    }
}
