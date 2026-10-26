package com.yestercar.common.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import processing.data.JSONArray;
import processing.data.JSONObject;

/**
 * 角色**特性**表，读 {@code data/traits.json}。战斗层执行、角色界面显示说明，都读这一份。
 *
 * <p>特性 = 干员自身带着的、不需要玩家操作的被动效果。目前只支持"部署即生效"这一类
 * （{@code trigger = on_deploy}）：桃金娘 —— 部署后阻挡数变 0、每秒回 2 点费用、7 秒后恢复。
 *
 * <p>字段含义：
 * <ul>
 *   <li>{@code operator}：干员 id；</li>
 *   <li>{@code trigger}：{@code on_deploy} = 部署时立刻生效；</li>
 *   <li>{@code duration}：持续秒数（到点自动恢复）；</li>
 *   <li>{@code dpPerSecond}：持续期间每秒回复的费用；</li>
 *   <li>{@code blockOverride}：持续期间的阻挡数（{@code -1} = 不改）；</li>
 *   <li>{@code description}：角色界面上的说明文字。</li>
 * </ul>
 *
 * <p>读不到文件时返回空表 —— 调用方退回"没有特性"，比崩掉好。
 */
public final class TraitTable {

    /**
     * 一个特性。所有效果字段都是"可选"的，缺省即不生效。
     *
     * @param operator    干员 id
     * @param name        特性名（角色界面的小标题）
     * @param trigger     {@code on_deploy} = 部署时立刻生效；{@code passive} = 常驻被动
     * @param duration    持续秒数（被动忽略）
     * @param dpPerSecond 持续期间每秒回复的费用
     * @param dpPerKill   每击败一名敌人回复的费用
     * @param dpPerAttack 每攻击一次回复的费用
     * @param dpPerInterval 每个 {@code interval} 周期回复的费用
     * @param interval    周期长度（秒）
     * @param blockOverride 阻挡数覆盖（{@code -1} = 不改）
     * @param blockDelay  部署多少秒后才改阻挡数（0 = 立刻）
     * @param refundRatio 撤退返还比例（{@code <0} = 用默认的 50%）
     * @param redeployTimeOverride 再部署时间（秒，{@code <0} = 不改）
     * @param atkScale    攻击力倍率（1 = 不变，2.5 = +150%）
     * @param atkScaleDelay 部署多少秒后才生效（0 = 立刻）
     * @param atkScaleDuration 加成持续秒数（0 = 一直生效到撤退）
     * @param damageTypeOverride 攻击伤害类型（{@code null} = 用干员自己的）
     * @param resPierce   无视多少点法抗
     * @param undyingSeconds 血量清零后还能战斗几秒（0 = 没有这个效果）
     * @param panicHeal   血量低于 50% 时是否回满血（每次部署一次）
     * @param panicAtkScale 触发上面那条时的额外攻击力倍率
     * @param damageFloorMaxHp 伤害下限 = 敌方最大生命 × 该比例（0 = 没有）
     * @param defAura     给上下左右相连四格的友方加多少防御
     * @param healPerInterval 每个周期治疗量 = 自身攻击力 × 该比例（0 = 没有）
     * @param reflectRatio 每次挨打反弹给对方 = 自身攻击力 × 该比例（0 = 没有）
     * @param damageTakenAmp 被自己阻挡的敌人受到的全属性伤害加成（0.5 = +50%）
     * @param defPierce   攻击无视敌方多少点防御力
     * @param critChance  每次攻击触发强化的概率（0–1）
     * @param critScale   触发后的攻击力倍率
     * @param focusLowestDef 是否优先打射程内防御最低的敌人
     * @param nthAttack   每多少次攻击之后的那一击被强化（0 = 没有）
     * @param nthAttackScale 被强化那一击的攻击力倍率
     * @param stackAtkPerHit 连续攻击同一目标时每次叠加的攻击力倍率（0.2 = +20%）
     * @param stackAspdPerHit 同上，每层加多少攻速点（10 = +10）
     * @param stackMax    最多叠几层
     * @param defScale    部署初期防御力倍率（2 = +100%），与 atkScale 共用时间窗
     * @param resBonus    部署初期法抗加值，与 atkScale 共用时间窗
     * @param ignoreAllDefRes 攻击是否完全无视敌方防御与法抗
     * @param teamAtkBonus 在场时给友方（或指定职业）的攻击力加成（0.18 = +18%）
     * @param teamAtkBonusClass 只对某个职业生效（{@code null} = 全体）
     * @param killStackAtk 每击杀一名敌人永久提升的攻击力（0.06 = +6%）
     * @param killStackMax 上面最多叠几层
     * @param slowAura    在场时攻击范围内敌方单位的移动速度系数（0.5 = 减半）
     * @param healAuraPercent 攻击范围内友方每秒回复自身最大生命的比例（0.02 = 2%）
     * @param allyHpPct   攻击范围内友方最大生命加成（0.15 = +15%）
     * @param allyDefPct  攻击范围内友方防御加成（0.15 = +15%）
     * @param noHeal      是否不主动治疗友方（改为攻击敌人）
     * @param lifestealPercent 造成伤害时给范围内友方回血 = 自身攻击力 × 该比例
     * @param healAtkBuff 治疗友方时给被治疗者的攻击力加成（0.5 = +50%）
     * @param healAtkBuffDuration 上面那条持续秒数
     * @param defResShred 攻击时给目标挂的防御/法抗削减比例（0.3 = -30%）
     * @param shredDuration 削减持续秒数
     * @param condExtraDamage 满足条件时的额外伤害比例（0.5 = +50%）
     * @param condDefBelow 条件：目标防御低于该值
     * @param condResBelow 条件：目标法抗低于该值
     * @param dotPercent 攻击后残留的每秒伤害 = 自身攻击力 × 该比例
     * @param dotDuration 残留伤害持续秒数
     * @param damageTakenMul 攻击范围内友方受到伤害的倍率（0.9 = 只吃 90%）
     * @param description 角色界面的说明文字
     */
    public record Trait(String operator, String name, String trigger, float duration,
                        float dpPerSecond, float dpPerKill, float dpPerAttack,
                        float dpPerInterval, float interval, int blockOverride,
                        float blockDelay, float refundRatio, float redeployTimeOverride,
                        float atkScale, float atkScaleDelay, float atkScaleDuration,
                        String damageTypeOverride, float resPierce, float undyingSeconds,
                        boolean panicHeal, float panicAtkScale, float damageFloorMaxHp,
                        float defAura, float healPerInterval, float reflectRatio,
                        float damageTakenAmp, float defPierce, float critChance, float critScale,
                        boolean focusLowestDef, int nthAttack, float nthAttackScale,
                        float stackAtkPerHit, float stackAspdPerHit, int stackMax,
                        float defScale, float resBonus, boolean ignoreAllDefRes,
                        float teamAtkBonus, String teamAtkBonusClass,
                        float killStackAtk, int killStackMax, float slowAura,
                        float atkBonusFlat,
                        float healAuraPercent, float allyHpPct, float allyDefPct,
                        boolean noHeal, float lifestealPercent,
                        float healAtkBuff, float healAtkBuffDuration,
                        float defResShred, float shredDuration, float condExtraDamage,
                        float condDefBelow, float condResBelow,
                        float dotPercent, float dotDuration, float damageTakenMul,
                        String description) {

        /** @return 是否"部署即生效" */
        public boolean onDeploy() {
            return "on_deploy".equals(trigger);
        }

        /** @return 是否有"每隔一段时间自动结算一次"的效果（回费或治疗） */
        public boolean periodic() {
            return interval > 0f && (dpPerInterval > 0f || healPerInterval > 0f);
        }

        /** @return 是否有任何改变数值/伤害的效果（这类需要每个部署实例单独记状态） */
        public boolean combatEffect() {
            return atkScale != 1f || blockOverride >= 0 || damageTypeOverride != null
                    || resPierce > 0f || undyingSeconds > 0f || panicHeal
                    || damageFloorMaxHp > 0f || reflectRatio > 0f
                    || defPierce > 0f || critChance > 0f || focusLowestDef
                    || nthAttack > 0 || stackAtkPerHit > 0f
                    || defScale != 1f || resBonus != 0f
                    || ignoreAllDefRes || teamAtkBonus > 0f
                    || killStackAtk > 0f || slowAura > 0f
                    || healAuraPercent > 0f || allyHpPct > 0f || allyDefPct > 0f
                    || noHeal || lifestealPercent > 0f
                    || healAtkBuff > 0f || defResShred > 0f || condExtraDamage > 0f
                    || dotPercent > 0f || damageTakenMul != 1f;
        }
    }

    /** 资源路径。 */
    private static final String RESOURCE = "data/traits.json";

    /** 解析结果（进程内只读一次）。 */
    private static volatile Map<String, Trait> cache;

    private TraitTable() {
    }

    /**
     * @param operatorId 干员 id
     * @return 特性；没有返回 {@code null}
     */
    public static Trait of(String operatorId) {
        if (operatorId == null) {
            return null;
        }
        return loadAll().get(operatorId);
    }

    /**
     * @param operatorId 干员 id
     * @return 部署即生效的特性；没有返回 {@code null}
     */
    public static Trait onDeploy(String operatorId) {
        Trait t = of(operatorId);
        return t != null && t.onDeploy() ? t : null;
    }

    /**
     * @param operatorId 干员 id
     * @return 角色界面要显示的小标题（特性名）；没有返回 {@code null}
     */
    public static String nameOf(String operatorId) {
        Trait t = of(operatorId);
        return t == null || t.name() == null || t.name().isBlank() ? null : t.name();
    }

    /**
     * @param operatorId 干员 id
     * @param dflt       没有特性/没写比例时的兜底
     * @return 撤退返还比例
     */
    public static float refundRatioOf(String operatorId, float dflt) {
        Trait t = of(operatorId);
        return t == null || t.refundRatio() < 0f ? dflt : t.refundRatio();
    }

    /**
     * @param operatorId 干员 id
     * @param dflt       没有特性/没写数值时的兜底
     * @return 再部署时间（秒）
     */
    public static float redeployTimeOf(String operatorId, float dflt) {
        Trait t = of(operatorId);
        return t == null || t.redeployTimeOverride() < 0f ? dflt : t.redeployTimeOverride();
    }

    /**
     * @param operatorId 干员 id
     * @return 角色界面要显示的说明；没有返回 {@code null}
     */
    public static String descriptionOf(String operatorId) {
        Trait t = of(operatorId);
        return t == null || t.description() == null || t.description().isBlank()
                ? null : t.description();
    }

    /**
     * @return {@code 干员 id → 特性}，保持文件顺序
     */
    public static Map<String, Trait> loadAll() {
        Map<String, Trait> local = cache;
        if (local != null) {
            return local;
        }
        synchronized (TraitTable.class) {
            if (cache != null) {
                return cache;
            }
            Map<String, Trait> out = new LinkedHashMap<>();
            try (InputStream in = TraitTable.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    System.err.println("[TraitTable] 找不到资源: " + RESOURCE);
                } else {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    JSONObject root = JSONObject.parse(text);
                    JSONArray arr = root.getJSONArray("traits");
                    for (int i = 0; arr != null && i < arr.size(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String op = o.getString("operator", null);
                        if (op == null) {
                            continue;
                        }
                        out.put(op, new Trait(op,
                                o.getString("name", ""),
                                o.getString("trigger", ""),
                                o.getFloat("duration", 0f),
                                o.getFloat("dpPerSecond", 0f),
                                o.getFloat("dpPerKill", 0f),
                                o.getFloat("dpPerAttack", 0f),
                                o.getFloat("dpPerInterval", 0f),
                                o.getFloat("interval", 0f),
                                o.getInt("blockOverride", -1),
                                o.getFloat("blockDelay", 0f),
                                o.getFloat("refundRatio", -1f),
                                o.getFloat("redeployTimeOverride", -1f),
                                o.getFloat("atkScale", 1f),
                                o.getFloat("atkScaleDelay", 0f),
                                o.getFloat("atkScaleDuration", 0f),
                                o.getString("damageTypeOverride", null),
                                o.getFloat("resPierce", 0f),
                                o.getFloat("undyingSeconds", 0f),
                                o.getBoolean("panicHeal", false),
                                o.getFloat("panicAtkScale", 1f),
                                o.getFloat("damageFloorMaxHp", 0f),
                                o.getFloat("defAura", 0f),
                                o.getFloat("healPerInterval", 0f),
                                o.getFloat("reflectRatio", 0f),
                                o.getFloat("damageTakenAmp", 0f),
                                o.getFloat("defPierce", 0f),
                                o.getFloat("critChance", 0f),
                                o.getFloat("critScale", 1f),
                                o.getBoolean("focusLowestDef", false),
                                o.getInt("nthAttack", 0),
                                o.getFloat("nthAttackScale", 1f),
                                o.getFloat("stackAtkPerHit", 0f),
                                o.getFloat("stackAspdPerHit", 0f),
                                o.getInt("stackMax", 0),
                                o.getFloat("defScale", 1f),
                                o.getFloat("resBonus", 0f),
                                o.getBoolean("ignoreAllDefRes", false),
                                o.getFloat("teamAtkBonus", 0f),
                                o.getString("teamAtkBonusClass", null),
                                o.getFloat("killStackAtk", 0f),
                                o.getInt("killStackMax", 0),
                                o.getFloat("slowAura", 0f),
                                o.getFloat("atkBonusFlat", 0f),
                                o.getFloat("healAuraPercent", 0f),
                                o.getFloat("allyHpPct", 0f),
                                o.getFloat("allyDefPct", 0f),
                                o.getBoolean("noHeal", false),
                                o.getFloat("lifestealPercent", 0f),
                                o.getFloat("healAtkBuff", 0f),
                                o.getFloat("healAtkBuffDuration", 0f),
                                o.getFloat("defResShred", 0f),
                                o.getFloat("shredDuration", 0f),
                                o.getFloat("condExtraDamage", 0f),
                                o.getFloat("condDefBelow", 0f),
                                o.getFloat("condResBelow", 0f),
                                o.getFloat("dotPercent", 0f),
                                o.getFloat("dotDuration", 0f),
                                o.getFloat("damageTakenMul", 1f),
                                o.getString("description", null)));
                    }
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("[TraitTable] 读取失败: " + e.getMessage());
            }
            cache = Collections.unmodifiableMap(out);
            return cache;
        }
    }
}
