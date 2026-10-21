package com.yestercar.runtime.battle;

/**
 * 一条战斗事件（逻辑 → 表现的单向通知）。
 *
 * <h2>为什么要事件流</h2>
 * 逻辑只产出"离散动作事件 + 每帧状态"，表现层自己播、自己收尾 ——
 * 这是 Defender 的分层方式（{@code server/sim/Battle.js:2255-2266} 的
 * {@code _ev}/{@code drainEvents} 纯缓冲，无回调、无等待）。
 * 上一版我们只让 UI 轮询 {@code unitViews()}，于是"谁在这一帧出手了"完全拿不到。
 *
 * <h2>禁止项（Defender 守住了，我们也守）</h2>
 * <ul>
 *   <li>表现层**不得**把"动画播完"回报给逻辑；</li>
 *   <li>逻辑**不得**等待任何外部信号；</li>
 *   <li>动画时长若要影响逻辑，只能以**数据字段**形式进（如 {@code attackAnim}）。</li>
 * </ul>
 *
 * @param kind  事件类型
 * @param time  游戏时间戳（秒，逻辑时钟）
 * @param a     主体单位 id（没有则 0）
 * @param b     客体单位 id（没有则 0）
 * @param value 数值（伤害/费用等，没有则 0）
 * @param text  附加文本（投射物类型、死亡原因、EXTRA 的种类）
 * @param x     事件发生处的世界 x（格；纯粹给表现层定位用，逻辑不用）
 * @param y     事件发生处的世界 y
 */
public record BattleEvent(Kind kind, float time, int a, int b, float value, String text,
                          float x, float y) {

    /** 事件类型（8 个 + 一个泛化口）。 */
    public enum Kind {
        /** 敌人出场。 */
        SPAWN,
        /** 干员部署。 */
        DEPLOY,
        /** 出手（{@code text} = 投射物类型；{@code value} = 本次攻击间隔）。 */
        ATTACK,
        /** 命中结算（{@code value} = 实际伤害）。 */
        DAMAGE,
        /** 死亡（{@code text} = 原因）。 */
        DIE,
        /** 被阻挡 / 解除阻挡（{@code b} = 干员 id）。 */
        BLOCK,
        /** 漏怪扣血。 */
        LEAK,
        /** 撤退（{@code value} = 返还费用）。 */
        RETREAT,
        /** 泛化口：将来加技能/治疗不必改协议（{@code text} = 子类型）。 */
        EXTRA
    }

    /**
     * 造一条事件。
     *
     * @param kind  类型
     * @param time  游戏时间
     * @param a     主体 id
     * @param b     客体 id
     * @param value 数值
     * @param text  附加文本
     * @return 事件
     */
    public static BattleEvent of(Kind kind, float time, int a, int b, float value, String text) {
        return of(kind, time, a, b, value, text, 0f, 0f);
    }

    /**
     * 造一条**带坐标**的事件（表现层要定位用）。
     *
     * @param kind  类型
     * @param time  游戏时间
     * @param a     主体 id
     * @param b     客体 id
     * @param value 数值
     * @param text  附加文本
     * @param x     世界 x
     * @param y     世界 y
     * @return 事件
     */
    public static BattleEvent of(Kind kind, float time, int a, int b, float value,
                                 String text, float x, float y) {
        return new BattleEvent(kind, time, a, b, value, text == null ? "" : text, x, y);
    }
}
