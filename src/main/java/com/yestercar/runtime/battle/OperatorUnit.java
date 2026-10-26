package com.yestercar.runtime.battle;

import com.yestercar.common.model.CellOffset;
import com.yestercar.common.model.Operator;
import com.yestercar.common.util.Director;

/**
 * 场上一个**已部署**的干员实例。
 *
 * <p>{@link Operator} 是不可变的类型定义（同一份数值被多个实例共享），
 * 本类是可变的战场状态：位置、朝向、当前生命、攻击计时、已阻挡数。
 *
 * <h2>⚠️ 阻挡数是"这个干员当前挡住了几个敌人"，不是 {@code type.block()}</h2>
 * {@code type.block()} 是**上限**（能同时挡几个），本类的 {@link #blocking()} 是**当前占用**。
 * 两者混用会让"阻挡数满 → 敌人穿过"这条规则永远不生效（显得一个干员能挡无限）。
 */
final class OperatorUnit {

    /** 全局部署序号：越大越晚部署（策划 §2.9 敌人优先打最后部署的干员）。 */
    private static long nextSeq = 1;

    private final long deploySeq;
    private final Operator type;
    private final int col;
    private final int row;
    private final Director facing;

    private float hp;
    private float attackTimer;

    /** 已占用的阻挡权重之和（不是"人数"—— 权重 3 的敌人占 3）。 */
    private int blockingWeight;

    /** 正在被它阻挡的敌人数量（HUD 显示用）。 */
    private int blockingCount;

    /** 受击闪白剩余（秒，纯表现）。 */
    private float hitFlash;
    private boolean alive = true;

    /**
     * 角色特性对阻挡数的临时覆盖（{@code -1} = 没有覆盖）。
     *
     * <p>桃金娘的特性：部署后 7 秒内阻挡数变成 0（回费期间不挡人），结束后回到
     * {@link com.yestercar.common.model.Operator#block()}。
     */
    private int blockOverride = -1;

    /** 攻击间隔缩放（能天使「火力电台」叠攻速用）：1 = 不变，越小攻速越快。 */
    private float intervalScale = 1f;

    /** 最大生命倍率（凯尔希「遗尘守望」攻击范围内 +15%）：1 = 不变。 */
    private float hpScale = 1f;

    /**
     * @param type   类型定义
     * @param col    所在列
     * @param row    所在行
     * @param facing 部署朝向（决定攻击范围旋转）
     */
    OperatorUnit(Operator type, int col, int row, Director facing) {
        this.deploySeq = nextSeq++;
        this.type = type;
        this.col = col;
        this.row = row;
        this.facing = facing == null ? Director.RIGHT : facing;
        this.hp = type.hp();
        // 放下即可攻击，不需要等一个间隔
        this.attackTimer = 0f;
    }

    /**
     * @return 部署序号（越大越晚）
     */
    long deploySeq() {
        return deploySeq;
    }

    Operator type() {
        return type;
    }

    int col() {
        return col;
    }

    int row() {
        return row;
    }

    Director facing() {
        return facing;
    }

    float hp() {
        return hp;
    }

    boolean alive() {
        return alive && hp > 0;
    }

    /** 当前已阻挡的敌人**数量**。 */
    int blocking() {
        return blockingCount;
    }

    /** 已占用的阻挡**权重**。 */
    int blockingWeight() {
        return blockingWeight;
    }

    /**
     * 还能不能接下这个权重的敌人。
     *
     * @param weight 敌人权重（≥1）
     * @return 放得下返回 {@code true}
     */
    boolean hasBlockRoom(int weight) {
        return alive() && blockingWeight + Math.max(1, weight) <= effectiveBlock();
    }

    /**
     * 角色特性对阻挡数的临时覆盖。
     *
     * @param value 覆盖值；{@code -1} 表示取消覆盖（回到 {@code type.block()}）
     */
    void setBlockOverride(int value) {
        this.blockOverride = value < 0 ? -1 : value;
        // 覆盖把上限压到当前占用以下时，多余的挡位立刻放掉（敌人回到路径上）
        while (blockingWeight > effectiveBlock()) {
            blockingWeight--;
            blockingCount = Math.max(0, blockingCount - 1);
        }
    }

    /**
     * @return 当前生效的阻挡上限（特性覆盖优先）
     */
    int effectiveBlock() {
        return blockOverride >= 0 ? blockOverride : type.block();
    }

    /**
     * 占用阻挡位（按权重）。
     *
     * @param weight 敌人权重
     */
    void addBlocking(int weight) {
        blockingWeight += Math.max(1, weight);
        blockingCount++;
    }

    /**
     * 释放阻挡位。
     *
     * @param weight 敌人权重
     */
    void removeBlocking(int weight) {
        blockingWeight = Math.max(0, blockingWeight - Math.max(1, weight));
        blockingCount = Math.max(0, blockingCount - 1);
    }

    /**
     * 判断某个格子是否在攻击范围内。
     *
     * @param targetCol 目标列
     * @param targetRow 目标行
     * @return 在范围内返回 {@code true}
     */
    boolean covers(int targetCol, int targetRow) {
        int dx = targetCol - col;
        int dy = targetRow - row;
        return type.rangeFor(facing).contains(new CellOffset(dx, dy));
    }

    /**
     * 受一次伤害。
     *
     * @param damage 已算好的伤害（见 {@link Combat#physicalDamage}）
     */
    /**
     * 治疗（不超过上限）。
     *
     * @param amount 回复量
     * @return 实际回复量
     */
    float heal(float amount) {
        if (!alive || amount <= 0f) {
            return 0f;
        }
        float max = maxHp();
        float before = hp;
        hp = Math.min(max, hp + amount);
        return hp - before;
    }

    /**
     * @return 血量比例（0–1）
     */
    float hpRatio() {
        float max = maxHp();
        return max <= 0f ? 0f : Math.max(0f, hp / max);
    }

    /**
     * @return 当前最大生命（含特性加成）
     */
    float maxHp() {
        return type.hp() * hpScale;
    }

    /**
     * 最大生命倍率（光环内 +15%）。
     *
     * @param scale 倍率（1 = 不变）
     */
    void setHpScale(float scale) {
        this.hpScale = scale <= 0f ? 1f : scale;
    }

    void takeDamage(float damage) {
        if (!alive || damage <= 0f) {
            return;
        }
        hp -= damage;
        hitFlash = 0.15f;
        if (hp <= 0f) {
            hp = 0f;
            alive = false;
            // 死亡时把阻挡位全部释放 —— 否则敌人会永远停在一个已经死了的干员身上
            blockingWeight = 0;
            blockingCount = 0;
        }
    }

    /**
     * 推进表现用的计时（受击闪白）。
     *
     * @param dt 步长（秒）
     */
    void tickVisual(float dt) {
        hitFlash = Math.max(0f, hitFlash - dt);
    }

    /**
     * 受击闪白强度（0–1，纯表现）。
     *
     * @return 强度
     */
    float hitFlash() {
        return hitFlash / 0.15f;
    }

    /**
     * 推进攻击计时。
     *
     * <p>⚠️ 只**推进并回答"现在能不能打"**，不重置冷却 ——
     * 重置必须由调用方在**真的出手之后**调 {@link #markAttacked()}。
     * 上一版在这里就重置了，于是"没有目标"也会白扣一个冷却
     * （Defender 的 {@code atkCd} 只在 {@code performAttack} 之后才置为 interval，
     * 目标一进范围当 tick 就打）。
     *
     * @param dt 步长（秒）
     * @return 现在就绪返回 {@code true}
     */
    boolean tickAttack(float dt) {
        if (!alive()) {
            return false;
        }
        attackTimer = Math.max(0f, attackTimer - dt);
        return attackTimer <= 0f;
    }

    /**
     * 距上次出手过了多久（秒）—— 占位动画用它算"攻击动作播到哪了"。
     *
     * @return 秒；从未出手时等于当前冷却剩余
     */
    float timeSinceAttack() {
        float iv = Math.max(0.01f, type.attackInterval());
        return Math.max(0f, iv - attackTimer);
    }

    /**
     * 出手之后重置冷却。
     *
     * <p>{@code += interval} 而不是 {@code = interval}：保留余量，平均间隔精确
     * （Defender 用 {@code =}，把间隔量化到 1/30 秒；我们保留 {@code +=}，
     * 它对帧步长更稳，且不改变"无目标不消耗"这条语义）。
     */
    /**
     * 攻速缩放（1 = 原始攻击间隔）。
     *
     * @param scale 缩放系数（0.5 = 攻击间隔减半）
     */
    void setIntervalScale(float scale) {
        this.intervalScale = scale <= 0f ? 1f : scale;
    }

    void markAttacked() {
        float iv = Math.max(0.01f, type.attackInterval() * intervalScale);
        attackTimer += iv;
        if (attackTimer <= 0f) {
            attackTimer = iv;
        }
    }

    /**
     * @return 供日志与测试用的一行摘要
     */
    String summary() {
        return type.name() + "@(" + col + "," + row + ") " + facing
                + " hp=" + String.format("%.1f", hp) + "/" + type.hp()
                + " 挡=" + blockingWeight + "/" + type.block();
    }
}
