/* Adapted from GD656Killicon by Minecraft_GD656 (MIT); see META-INF/GD656-NOTICE.txt. */
package gale.kill.gd.common.bonus;

/** 客户端统计归类（记录到 ClientStatsManager）。 */
public enum StatCategory {
    /** 不记录统计。 */
    NONE,
    ASSIST,
    REVIVE,
    /** 伤害类（计入累计伤害）。 */
    DAMAGE
}
