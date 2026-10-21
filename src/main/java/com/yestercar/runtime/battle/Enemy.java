package com.yestercar.runtime.battle;

/**
 * 一个敌人实例。
 *
 * <p>位置用<strong>格为单位的浮点坐标</strong>（不是像素）：
 * 流场算出来的是格子，移动速度按「格/秒」定义，
 * 保持在格子坐标系里就少一层换算，绘制时再乘 {@code tileSize} 即可。
 *
 * <p>格子 {@code (col,row)} 的<strong>中心</strong>是 {@code (col+0.5, row+0.5)}。
 * 敌人走在格子中心而不是角上，这样"贴不贴边"不会影响观感与到达判定。
 *
 * <h2>生命值目前只在受击时用</h2>
 * P2.3 没有攻击，所以 {@link #takeDamage} 暂时没人调 ——
 * 但它是 {@code Enemy} 的固有属性，P4.5 做互相攻击时直接用。
 * 留着而不是删掉，是因为"敌人没有血"会让 P4.5 回头改这个类。
 */
public final class Enemy {

    private final EnemyType type;

    /** 位置（格为单位）。 */
    private float x;
    private float y;

    /** 目标蓝门 id。 */
    private final int targetBlueDoorId;

    private float hp;

    /** 是否还活着。到达蓝门（漏怪）或被打死都置为 false。 */
    private boolean alive = true;

    /**
     * 是否**正被干员挡住**（策划 §2.7：被挡住的敌人停下攻击干员，不再沿流场移动）。
     *
     * <p>⚠️ 这个标记由战场每帧重新计算后写进来，不是敌人自己决定的 ——
     * 敌人不需要知道是谁挡住了它（那会把 Enemy 和 OperatorUnit 绑在一起）。
     */
    private boolean blocked;

    /** 攻击冷却剩余（秒）。敌人只在被阻挡时攻击（策划 §2.9）。 */
    private float attackTimer;

    /**
     * 已算好的 waypoint 链（格子下标），以及走到第几个。
     *
     * <p>{@code null} 表示"还没规划过"，下一步移动时才向流场要。
     * 地形变动后由 {@code BattleScene} 置回 {@code null} 触发重算。
     *
     * <p>存下来而不是每帧重问，是为了避免在格子边界振荡 ——
     * 见 {@code FlowField.waypoints} 的说明。
     */
    private int[][] waypoints;
    private int waypointIndex;

    /**
     * @param type             敌人类型
     * @param x                初始 x（格，可为小数）
     * @param y                初始 y（格）
     * @param targetBlueDoorId 目标蓝门 id
     */
    public Enemy(EnemyType type, float x, float y, int targetBlueDoorId) {
        this.type = type == null ? EnemyType.FALLBACK : type;
        this.x = x;
        this.y = y;
        this.targetBlueDoorId = targetBlueDoorId;
        this.hp = this.type.hp();
    }

    // ------------------------------------------------------------------
    // 位置
    // ------------------------------------------------------------------

    /** @return x（格） */
    public float x() {
        return x;
    }

    /** @return y（格） */
    public float y() {
        return y;
    }

    /**
     * 直接设置位置。
     *
     * @param newX 新 x（格）
     * @param newY 新 y（格）
     */
    public void setPosition(float newX, float newY) {
        this.x = newX;
        this.y = newY;
    }

    /**
     * @return 所在列（向下取整）
     */
    public int col() {
        return (int) Math.floor(x);
    }

    /**
     * @return 所在行（向下取整）
     */
    public int row() {
        return (int) Math.floor(y);
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /** @return 是否还活着 */
    /**
     * @return 是否正被阻挡（被阻挡时不移动）
     */
    /**
     * 推进攻击冷却。
     *
     * @param dt 步长（秒）
     * @return 该出手了返回 {@code true}
     */
    /**
     * @return 攻击冷却是否已就绪（不消耗冷却）
     */
    /**
     * 只推进冷却，不消耗这次出手（用于"边走边等冷却"）。
     *
     * <p>⚠️ 不能用 {@link #tickAttack(float)} 代替：它在计时器归零时会
     * <b>把这次攻击消耗掉并重置计时器</b>（返回值被忽略时攻击就静默丢失了）。
     *
     * @param dt 帧时长
     */
    public void tickCooldown(float dt) {
        if (alive) {
            attackTimer = Math.max(0f, attackTimer - dt);
        }
    }

    public boolean attackReady() {
        return alive && attackTimer <= 0f;
    }

    public boolean tickAttack(float dt) {
        if (!alive) {
            return false;
        }
        attackTimer -= dt;
        if (attackTimer <= 0f) {
            attackTimer = Math.max(0.01f, type.attackInterval());
            return true;
        }
        return false;
    }

    public boolean blocked() {
        return blocked;
    }

    /**
     * 设置阻挡状态。
     *
     * @param value 是否正被阻挡
     */
    public void setBlocked(boolean value) {
        this.blocked = value;
    }

    public boolean alive() {
        return alive;
    }

    /** 让它消失（到达蓝门 / 被打死 / 被清除）。幂等。 */
    public void kill() {
        alive = false;
    }

    /** @return 当前生命值 */
    /** 减速到期时刻（游戏时间）；{@code <=0} 表示没被减速。 */
    private float slowUntil;

    /** 减速系数（1 = 不减速）。 */
    private float slowMul = 1f;

    /**
     * 施加减速（重复命中只刷新时长，不叠加）。
     *
     * @param now    当前游戏时间
     * @param mul    速度系数（0.5 = 半速）
     * @param durSec 持续时间（秒）
     */
    public void applySlow(float now, float mul, float durSec) {
        this.slowMul = Math.max(0.05f, Math.min(1f, mul));
        this.slowUntil = Math.max(slowUntil, now + durSec);
    }

    /**
     * @param now 当前游戏时间
     * @return 当前速度系数（1 = 正常）
     */
    public float slowFactor(float now) {
        return now < slowUntil ? slowMul : 1f;
    }

    /** 停步攻击的截止时刻（游戏时间）；小于当前时间表示可以继续走。 */
    private float holdUntil;

    /**
     * 让敌人原地停留到指定时刻（远程攻击期间不移动）。
     *
     * @param t 截止时间（游戏时间，秒）
     */
    public void holdUntil(float t) {
        this.holdUntil = Math.max(this.holdUntil, t);
    }

    /**
     * @param now 当前游戏时间
     * @return 是否正处在"停下攻击"状态
     */
    public boolean holding(float now) {
        return now < holdUntil;
    }

    public float hp() {
        return hp;
    }

    /** @return 最大生命值（类型的 hp） */
    public float maxHp() {
        return type.hp();
    }

    /**
     * 受到伤害。血量归零自动 {@link #kill()}。
     *
     * @param damage 伤害值；非正数时忽略
     */
    public void takeDamage(float damage) {
        if (damage <= 0 || !alive) {
            return;
        }
        hp -= damage;
        if (hp <= 0) {
            hp = 0;
            kill();
        }
    }

    /** @return 敌人类型 */
    public EnemyType type() {
        return type;
    }

    /** @return 目标蓝门 id */
    public int targetBlueDoorId() {
        return targetBlueDoorId;
    }

    // ------------------------------------------------------------------
    // waypoint 链
    // ------------------------------------------------------------------

    /** @return 已算好的 waypoint 链；还没规划过时为 {@code null} */
    public int[][] waypoints() {
        return waypoints;
    }

    /** @return 正在走向第几个 waypoint */
    public int waypointIndex() {
        return waypointIndex;
    }

    /**
     * 换一条新的 waypoint 链。下标归零。
     *
     * @param wps 新链；{@code null} 表示清空（下次移动时重算）
     */
    public void setWaypoints(int[][] wps) {
        this.waypoints = wps;
        this.waypointIndex = 0;
    }

    /**
     * 记录走到第几个了。
     *
     * @param index 新下标
     */
    public void setWaypointIndex(int index) {
        this.waypointIndex = index;
    }

    /**
     * 让下一次移动重新规划。
     *
     * <p>地形/门变动后必须调用 —— 旧链可能已经穿过新放下的障碍。
     */
    public void invalidatePath() {
        this.waypoints = null;
        this.waypointIndex = 0;
    }

    /** @return 是否已经走完了整条链（用于诊断） */
    public boolean pathFinished() {
        return waypoints != null && waypointIndex >= waypoints.length;
    }

    /**
     * @return 供状态栏与测试用的一行摘要
     */
    public String summary() {
        return String.format("%s (%.2f,%.2f) hp=%.0f/%.0f→门%d",
                type.id(), x, y, hp, (float) type.hp(), targetBlueDoorId);
    }

    @Override
    public String toString() {
        return "Enemy[" + summary() + "]";
    }
}
