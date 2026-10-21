package com.yestercar.common.model;

/**
 * 伤害类型。
 *
 * <p>对应 Defender {@code damage.js:141-157} 的四档；我们目前只用前两档，
 * 真实/元素伤害先把枚举位留着，将来加技能不用改数据格式。
 */
public enum DamageType {
    /** 物理：{@code max(atk − def, 5% × atk)}。 */
    PHYS("phys"),
    /** 法术：{@code max(atk × (1 − res/100), 5% × atk)}。 */
    ARTS("arts"),
    /** 真实：无视防御与法抗。 */
    TRUE("true"),
    /** 元素。 */
    ELEMENTAL("elemental"),
    /** 治疗（医疗干员的攻击是给友军回血）。 */
    HEAL("heal");

    private final String jsonName;

    DamageType(String jsonName) {
        this.jsonName = jsonName;
    }

    /**
     * @return JSON / CSV 里写的名字
     */
    public String jsonName() {
        return jsonName;
    }

    /**
     * 解析；认不出来时退回 {@link #PHYS}（数据写错不该让游戏崩）。
     *
     * @param raw 原始字符串
     * @return 类型
     */
    public static DamageType parse(String raw) {
        if (raw == null) {
            return PHYS;
        }
        String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (DamageType d : values()) {
            if (d.jsonName.equals(t)) {
                return d;
            }
        }
        return PHYS;
    }
}
