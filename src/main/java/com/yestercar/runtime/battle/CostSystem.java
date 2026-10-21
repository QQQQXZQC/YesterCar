package com.yestercar.runtime.battle;

import com.yestercar.common.model.LevelParams;

/**
 * 费用系统（策划方案 §2.4）。
 *
 * <p>规则逐条对应策划：
 * <table border="1">
 *   <caption>费用规则</caption>
 *   <tr><th>项</th><th>决策</th><th>本类</th></tr>
 *   <tr><td>初始费用</td><td>默认 10，编辑器可改</td><td>{@link #cost()} 初值</td></tr>
 *   <tr><td>每秒回费</td><td>1</td><td>{@link #update(float)}</td></tr>
 *   <tr><td>击杀回费</td><td>不回</td><td>没有对应方法 —— 刻意不写</td></tr>
 *   <tr><td>干员撤退</td><td>返还 50%，向下取整</td><td>{@link #refund(int)}</td></tr>
 * </table>
 *
 * <h2>为什么费用是 float 而不是 int</h2>
 * 每秒 +1 但 {@code dt} 是任意小数，用 int 累计会把零头全部丢掉
 * （60fps 下每帧 +0.0167 取整后恒为 0，费用永远不涨）。对外用
 * {@link #whole()} 取整数显示与扣费。
 *
 * <h2>为什么"击杀不回费"是一个显式的空</h2>
 * 策划明确写了"击杀不回费"，所以本类**不提供** {@code addOnKill} 之类的方法。
 * 如果将来要改，改这里比翻遍战斗循环安全。
 */
final class CostSystem {

    /** 撤退返还比例（策划 §2.4：50%，向下取整）。 */
    static final float REFUND_RATIO = 0.5f;

    private float cost;
    private final float perSecond;
    private final float cap;

    /**
     * @param params 关卡参数（提供初始费用与每秒回费）
     */
    CostSystem(LevelParams params) {
        LevelParams p = params == null ? LevelParams.DEFAULT : params;
        this.cost = Math.min(99f, p.initialCost());
        this.perSecond = p.costPerSecond();
        // 费用上限：99（用户指定，作为全局封顶）
        this.cap = 99f;
    }

    /**
     * 推进时间。
     *
     * @param dt 步长（秒）
     */
    void update(float dt) {
        if (dt <= 0f) {
            return;
        }
        cost = Math.min(cap, cost + perSecond * dt);
    }

    /**
     * @return 当前费用（含小数）
     */
    float cost() {
        return cost;
    }

    /**
     * @return 当前费用向下取整 —— 显示与扣费都用它
     */
    int whole() {
        return (int) Math.floor(cost);
    }

    /**
     * @param amount 需要的费用
     * @return 够不够
     */
    boolean canAfford(int amount) {
        return whole() >= amount;
    }

    /**
     * 扣费。
     *
     * @param amount 金额
     * @return 扣成功返回 {@code true}；不够则不动并返回 {@code false}
     */
    boolean spend(int amount) {
        if (amount <= 0) {
            return true;
        }
        if (whole() < amount) {
            return false;
        }
        cost -= amount;
        return true;
    }

    /**
     * 返还（撤退）。
     *
     * @param spentCost 当初部署花费的费用
     * @return 实际返还的金额（{@code floor(spent × 50%)}）
     */
    int refund(int spentCost) {
        int back = (int) Math.floor(Math.max(0, spentCost) * REFUND_RATIO);
        cost = Math.min(cap, cost + back);
        return back;
    }

    /**
     * @return 供 HUD 与日志用的一行摘要
     */
    String summary() {
        return String.format("费用 %d（%.1f，+%.1f/s）", whole(), cost, perSecond);
    }
}
