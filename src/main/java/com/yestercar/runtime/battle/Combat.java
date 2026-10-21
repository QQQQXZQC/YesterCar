package com.yestercar.runtime.battle;

/**
 * 伤害公式（策划方案没有规定，取自参考实现 {@code Defender/server/sim/damage.js}）。
 *
 * <h2>为什么用这条公式</h2>
 * 策划 §2.8 / §2.9 只写了"干员攻击" / "敌人攻击"，<strong>没有写伤害怎么算</strong>，
 * 而 {@code operators.json} 与 {@code enemies.json} 里都有 {@code def} 字段 ——
 * 有字段却无公式，等于这个字段没有意义。参考实现用的是官方规则：
 *
 * <pre>
 *   物理伤害 = max(攻击力 − 防御力, 攻击力 × 5%)
 * </pre>
 *
 * <p>那个 5% 下限是原作的"刮痧伤害"：防御再高也至少能磨掉一点，否则高防单位
 * 会变成物理免疫，关卡设计会退化。参考实现里它叫 {@code MIN_DAMAGE_RATIO}。
 *
 * <h2>本轮只做物理伤害</h2>
 * 参考实现还有法术伤害（按百分比减伤）与元素伤害。策划 §1.3 明确"先做原型"，
 * 干员/敌人数据里也没有法术抗性字段，所以这里只实现物理那一条。
 * 将来加术师时，照着参考实现的 {@code mitigate(amount, 'arts', ...)} 再加一条即可。
 */
final class Combat {

    /** 最低伤害比例（参考实现的 {@code MIN_DAMAGE_RATIO = 0.05}）。 */
    static final float MIN_DAMAGE_RATIO = 0.05f;

    private Combat() {
    }

    /**
     * 算一次物理伤害。
     *
     * @param atk 攻击方攻击力
     * @param def 防守方防御力
     * @return 实际伤害，<strong>至少 0</strong>；攻击力为 0 时为 0
     */
    static float physicalDamage(float atk, float def) {
        if (atk <= 0f) {
            return 0f;
        }
        // Defender/damage.js: max(amount − def, 0.05 × amount)，**float、不取整、无"至少 1"**。
        // 我们之前写成 max(1, round(5%))，atk=10 打 def=100 会得 1，而正确值是 0.5。
        float floor = MIN_DAMAGE_RATIO * atk;
        return Math.max(Math.max(0f, atk - Math.max(0f, def)), floor);
    }

    /**
     * 按类型结算一次攻击。
     *
     * @param atk  攻击力
     * @param def  目标防御（物理用）
     * @param res  目标法抗 0–100（法术用）
     * @param type 伤害类型
     * @return 实际伤害（float）
     */
    static float damage(float atk, float def, int res, com.yestercar.common.model.DamageType type) {
        if (type == com.yestercar.common.model.DamageType.ARTS) {
            return artsDamage(atk, res);
        }
        if (type == com.yestercar.common.model.DamageType.TRUE) {
            return Math.max(0f, atk);
        }
        return physicalDamage(atk, def);
    }

    /**
     * 法术伤害（Defender/damage.js 的 arts 分支）。
     *
     * @param atk 攻击力
     * @param res 法术抗性（0–100）
     * @return 实际伤害
     */
    static float artsDamage(float atk, float res) {
        if (atk <= 0f) {
            return 0f;
        }
        float r = Math.max(0f, Math.min(100f, res));
        return Math.max(atk * (1f - r / 100f), MIN_DAMAGE_RATIO * atk);
    }
}
