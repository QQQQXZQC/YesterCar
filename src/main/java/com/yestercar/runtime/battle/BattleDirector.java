package com.yestercar.runtime.battle;

import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.common.model.LevelParams;
import com.yestercar.common.model.Operator;
import com.yestercar.common.util.Director;
import com.yestercar.runtime.pathfinding.FlowField;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 战斗引擎门面：把费用 / 部署 / 阻挡 / 攻击 / 胜负五个系统收成一个入口。
 *
 * <h2>与 Defender 的关系（第 17 轮决定）</h2>
 * <b>模式</b>是标准塔防（策划口径）：部署扣费、撤退返还 50%、击杀不回费。
 * <b>单场战斗内部规则</b>以 Defender 为准：
 * <ul>
 *   <li>固定 {@link #TICK} = 1/30 秒步长，外部真实 dt 只进累加器（可复现）；</li>
 *   <li>帧内顺序：费用 → 阻挡 → <b>敌人出手</b> → 移动 → 阻挡 → 干员出手 → 清死 → 胜负；</li>
 *   <li>阻挡用平方半径（{@link BlockSystem}），按 {@code blockWeight} 求和；</li>
 *   <li>干员攻击<b>没有目标不消耗冷却</b>；</li>
 *   <li>目标链见 {@link TargetSelector}；</li>
 *   <li>伤害 float、保底 5%（{@link Combat}）；</li>
 *   <li>远程敌人未被阻挡也主动攻击。</li>
 * </ul>
 * 另外本类产出**事件流**（{@link BattleEvent}），表现层据此播动画，逻辑从不等待动画。
 */
public final class BattleDirector {

    /**
     * 固定模拟步长（Defender {@code constants.js:7} 的 {@code TICK = 1/30}）。
     *
     * <p>为什么必须定步长：上一版直接用真实帧间隔，帧率不同结果就不同，
     * 同一份编队在 60fps 与 30fps 下会打出不同结局，**完全无法复现**。
     */
    public static final float TICK = 1f / 30f;

    /** 一帧最多补多少 tick（防止卡顿后一次追几百帧把界面冻住）。 */
    private static final int MAX_TICKS_PER_FRAME = 8;

    /**
     * 同时在场上限（第 17 轮决定：固定 8，所有地图相同）。
     *
     * <p>语义是"**同时**在场"—— 撤退/阵亡后空出的位置可以再放一个。
     * 不阻止开战：编队 12 人但只有 8 个位置，本身就是塔防的策略空间。
     */
    public static final int DEPLOY_LIMIT = 8;

    /**
     * 给 UI 看的干员只读快照。
     *
     * @param col       列
     * @param row       行
     * @param facing    朝向
     * @param name      显示名
     * @param hp        当前生命（float）
     * @param maxHp     最大生命
     * @param blocking  已占用的阻挡权重
     * @param block     阻挡上限
     * @param attackAge 距上次出手的时间（秒，占位动画用）
     * @param hitFlash  受击闪白强度（0–1）
     */
    public record UnitView(int col, int row, Director facing, String name,
                           float hp, float maxHp, int blocking, int block,
                           float attackAge, float hitFlash,
                           int atk, int def, int res) {
    }

    private final BattleScene scene;
    private final TileMap map;
    private final CostSystem cost;
    private final DeploymentSystem deployment;

    /** 被挡住、正在挨打的敌人 → 挡住它的干员。 */
    private final Map<Enemy, OperatorUnit> blockers = new IdentityHashMap<>();

    /** 敌人的出生序号（目标选择的末级排序键）。 */
    private final Map<Enemy, Long> spawnSeq = new IdentityHashMap<>();
    private long nextSpawnSeq = 1;

    /** 本帧产生的事件；表现层 {@link #drainEvents()} 取走后清空。 */
    private final List<BattleEvent> events = new ArrayList<>();

    /** 累积的未消耗时间。 */
    private float accumulator;

    /** 已经放出与计划放出的敌人数量（由 UI 每帧同步进来）。 */
    private int spawned;
    private int spawnLimit;

    private float time;
    /** 出怪是否已全部放完（由 UI 每 tick 从 SpawnManager 同步进来）。 */
    private boolean allSpawned;

    private boolean finished;
    private boolean won;
    private int stars;

    /**
     * @param scene  场景（提供敌人与流场）
     * @param map    地图（判定部署地形）
     * @param params 关卡参数（初始费用、回费）
     * @param squad  编队
     */
    public BattleDirector(BattleScene scene, TileMap map, LevelParams params,
                          List<Operator> squad) {
        this.scene = scene;
        this.map = map;
        this.cost = new CostSystem(params);
        this.deployment = new DeploymentSystem(map, cost, squad);
    }

    /**
     * UI 每帧告诉引擎"出怪是不是全放完了"。
     *
     * @param done 全部放完为 {@code true}
     */
    public void setAllSpawned(boolean done) {
        this.allSpawned = done;
    }

    /**
     * UI 每帧告诉引擎"已经放了几只 / 计划放几只"。
     *
     * @param newSpawned 已放出
     * @param newLimit   上限
     */
    public void setSpawnProgress(int newSpawned, int newLimit) {
        this.spawned = Math.max(0, newSpawned);
        this.spawnLimit = Math.max(0, newLimit);
    }

    /**
     * 推进一帧（内部跑固定步长）。
     *
     * @param dt 真实帧间隔（秒）
     */
    public void update(float dt) {
        if (dt <= 0f || finished) {
            return;
        }
        accumulator += dt;
        int ticks = 0;
        while (accumulator >= TICK && ticks < MAX_TICKS_PER_FRAME) {
            step(TICK);
            accumulator -= TICK;
            ticks++;
            if (finished) {
                accumulator = 0f;
                return;
            }
        }
        // 长时间卡顿后丢掉积压，避免"追帧"把界面拖死
        if (accumulator > TICK * MAX_TICKS_PER_FRAME) {
            accumulator = 0f;
        }
    }

    /**
     * 走一个固定步长。
     *
     * <h2>顺序（照 Defender {@code Battle.js:391-446}）</h2>
     * <pre>
     *   ① 费用与再部署冷却
     *   ② 阻挡分配
     *   ③ 敌人出手（**先于干员**，同归于尽时结果会不同）
     *   ④ 场景推进（未被阻挡的敌人移动、到达蓝门扣血）
     *   ⑤ 再分配一次阻挡（刚走进干员格的）
     *   ⑥ 干员出手
     *   ⑦ 清死 + 释放阻挡
     *   ⑧ 胜负
     * </pre>
     *
     * @param dt 固定步长
     */
    private void step(float dt) {
        time += dt;
        cost.update(dt);
        deployment.update(dt);

        refreshBlocking();
        enemiesAct(dt);
        int leakedBefore = scene.leaked();
        scene.update(dt);
        if (scene.leaked() > leakedBefore) {
            emit(BattleEvent.Kind.LEAK, 0, 0, scene.leaked() - leakedBefore, "leak");
        }
        refreshBlocking();
        operatorsAct(dt);
        sweepDead();
        judge();
    }

    /**
     * 重算阻挡关系（Defender {@code _checkBlock}）。
     *
     * <p>已建立的阻挡保持不变；未被阻挡的敌人才尝试接上新干员。
     */
    private void refreshBlocking() {
        List<OperatorUnit> units = deployment.deployed();
        for (Enemy e : scene.enemies()) {
            if (!e.alive()) {
                continue;
            }
            OperatorUnit holder = blockers.get(e);
            if (holder != null && holder.alive()) {
                e.setBlocked(true);
                continue;
            }
            if (holder != null) {
                holder.removeBlocking(e.type().blockWeight());
                blockers.remove(e);
                e.setBlocked(false);
                emit(BattleEvent.Kind.BLOCK, holder.deploySeq(), idOf(e), 0f, "release");
            }
            OperatorUnit got = BlockSystem.tryBlock(units, e.x(), e.y(),
                    e.col(), e.row(), e.type().blockWeight());
            if (got != null) {
                blockers.put(e, got);
                e.setBlocked(true);
                spawnSeq.putIfAbsent(e, nextSpawnSeq++);
                emit(BattleEvent.Kind.BLOCK, got.deploySeq(), idOf(e), 0f, "block");
            }
        }
    }

    /**
     * 远程索敌：按 {@code enemy_ranges.json} 的**格偏移**判定（朝右），
     * 找不到配置时才退回半径。
     */
    private OperatorUnit nearestInCells(Enemy e, List<OperatorUnit> units) {
        com.yestercar.runtime.battle.EnemyType.ensureRangesLoaded();
        java.util.List<int[]> cells = e.type().rangeCells();
        if (cells.isEmpty()) {
            return nearestInRadius(e, units);
        }
        int ec = (int) Math.floor(e.x());
        int er = (int) Math.floor(e.y());
        OperatorUnit best = null;
        float bestD = Float.MAX_VALUE;
        for (OperatorUnit u : units) {
            if (!u.alive()) {
                continue;
            }
            int dx = u.col() - ec;
            int dy = u.row() - er;
            for (int[] c : cells) {
                if (c[0] == dx && c[1] == dy) {
                    float d = dx * dx + dy * dy;
                    if (d < bestD) {
                        bestD = d;
                        best = u;
                    }
                    break;
                }
            }
        }
        return best;
    }

    /** 敌人行动：被挡住就打挡住它的人；远程且未被挡则主动打。 */
    private void enemiesAct(float dt) {
        List<OperatorUnit> units = deployment.deployed();
        for (Enemy e : scene.enemies()) {
            if (!e.alive()) {
                continue;
            }
            OperatorUnit holder = blockers.get(e);
            boolean blocked = holder != null && holder.alive();

            // 目标：挡住我的人；未阻挡且远程 → 半径内最近的干员
            OperatorUnit target = null;
            if (blocked) {
                target = TargetSelector.pickOperator(e, units, u -> u == holder);
            } else if (e.type().isRanged() && e.type().rangeRadius() > 0) {
                target = nearestInCells(e, units);
            }
            // 攻击力 0（如幽灵）= 不攻击
            if (e.type().atk() <= 0 || target == null) {
                continue;
            }
            // 远程**未被阻挡**时才需要"站定开火"；
            // 被干员挡住时本来就不移动，直接按原逻辑出手。
            if (!blocked && e.type().isRanged() && !e.holding(time)) {
                if (!e.attackReady()) {
                    e.tickCooldown(dt);             // 只走冷却，别把这次出手吃掉
                    continue;
                }
                e.holdUntil(time + Math.min(0.5f, e.type().attackInterval() * 0.5f));
                continue;                           // 这一帧只负责站定
            }
            if (!e.tickAttack(dt)) {
                continue;
            }
            emit(BattleEvent.Kind.ATTACK, idOf(e), target.deploySeq(),
                    0f, e.type().isRanged() ? "enemy" : "none");

            // 术师 / 源石虫走法术伤害，其余物理
            boolean arts = "arts".equalsIgnoreCase(e.type().damageType());
            float dmg = arts
                    ? Combat.artsDamage(e.type().atk(), target.type().res())
                    : Combat.physicalDamage(e.type().atk(), target.type().def());
            target.takeDamage(dmg);
            emit(BattleEvent.Kind.DAMAGE, idOf(e), target.deploySeq(), dmg,
                    arts ? "arts" : "phys");
            if (!target.alive()) {
                emit(BattleEvent.Kind.DIE, target.deploySeq(), 0, 0f, "killed",
                        target.col() + 0.5f, target.row() + 0.5f);
                releaseBlock(target);
                deployment.onDeath(target);
            }
        }
    }

    /**
     * 远程敌人在半径内找干员（切比雪夫距离，含边界）。
     *
     * @param e     敌人
     * @param units 场上干员
     * @return 最近的干员；没有返回 {@code null}
     */
    private OperatorUnit nearestInRadius(Enemy e, List<OperatorUnit> units) {
        int r = e.type().rangeRadius();
        OperatorUnit best = null;
        int bestD = Integer.MAX_VALUE;
        for (OperatorUnit u : units) {
            if (!u.alive()) {
                continue;
            }
            int d = Math.max(Math.abs(u.col() - e.col()), Math.abs(u.row() - e.row()));
            if (d <= r && d < bestD) {
                best = u;
                bestD = d;
            }
        }
        return best;
    }

    /** 干员出手（Defender {@code updateAlly}：没有目标**不消耗**冷却）。 */
    private void operatorsAct(float dt) {
        List<Enemy> enemies = scene.enemies();
        for (OperatorUnit u : deployment.deployed()) {
            if (!u.alive()) {
                continue;
            }
            u.tickVisual(dt);
            if (!u.tickAttack(dt)) {
                continue;
            }
            // 医疗：给射程内血量比例最低的友军回血；没有伤员就不出手
            if (u.type().damageType() == com.yestercar.common.model.DamageType.HEAL) {
                OperatorUnit wounded = null;
                float worst = 1f;
                for (OperatorUnit ally : deployment.deployed()) {
                    if (!ally.alive() || ally.hpRatio() >= 1f) {
                        continue;
                    }
                    int d = Math.max(Math.abs(ally.col() - u.col()),
                            Math.abs(ally.row() - u.row()));
                    if (d <= 3 && ally.hpRatio() < worst) {
                        worst = ally.hpRatio();
                        wounded = ally;
                    }
                }
                if (wounded == null) {
                    continue;
                }
                float healed = wounded.heal(u.type().atk());
                if (healed <= 0f) {
                    continue;
                }
                emit(BattleEvent.Kind.EXTRA, u.deploySeq(), wounded.deploySeq(),
                        healed, "heal", wounded.col() + 0.5f, wounded.row() + 0.5f);
                u.markAttacked();
                continue;
            }

            Enemy target = TargetSelector.pickEnemy(u, enemies,
                    e -> blockers.get(e) == u,
                    this::remainingDistance,
                    e -> e.type().taunt(),
                    e -> spawnSeq.getOrDefault(e, 0L));
            if (target == null) {
                // 冷却保持 0：目标一进范围当 tick 就打
                continue;
            }
            emit(BattleEvent.Kind.ATTACK, u.deploySeq(), idOf(target),
                    u.type().attackInterval(), "none");
            float dmg = Combat.damage(u.type().atk(), target.type().def(),
                    target.type().res(), u.type().damageType());
            target.takeDamage(dmg);
            emit(BattleEvent.Kind.DAMAGE, u.deploySeq(), idOf(target), dmg,
                    u.type().damageType().jsonName());

            // 辅助：命中后减速（法术伤害照常结算）
            if ("Supporter".equals(u.type().opClass())) {
                target.applySlow(time, 0.5f, 2f);
                emit(BattleEvent.Kind.EXTRA, u.deploySeq(), idOf(target), 2f, "slow",
                        target.x(), target.y());
            }
            u.markAttacked();
            if (!target.alive()) {
                emit(BattleEvent.Kind.DIE, idOf(target), 0, 0f, "killed",
                        target.x(), target.y());
                releaseBlock(target);
            }
        }
    }

    /** 清理死亡单位并释放阻挡位。 */
    private void sweepDead() {
        for (Enemy e : new ArrayList<>(blockers.keySet())) {
            OperatorUnit u = blockers.get(e);
            if (!e.alive()) {
                if (u != null) {
                    u.removeBlocking(e.type().blockWeight());
                }
                blockers.remove(e);
                e.setBlocked(false);
            } else if (u != null && !u.alive()) {
                blockers.remove(e);
                e.setBlocked(false);
            }
        }
        for (OperatorUnit u : deployment.deployed()) {
            if (!u.alive()) {
                deployment.onDeath(u);
            }
        }
    }

    /**
     * 释放某个敌人的阻挡关系。
     *
     * @param e 敌人
     */
    private void releaseBlock(Enemy e) {
        OperatorUnit u = blockers.remove(e);
        if (u != null) {
            u.removeBlocking(e.type().blockWeight());
        }
        e.setBlocked(false);
    }

    /**
     * 释放**某个干员**身上的全部阻挡（撤退/阵亡时）。
     *
     * @param u 干员
     */
    private void releaseBlock(OperatorUnit u) {
        for (Enemy e : new ArrayList<>(blockers.keySet())) {
            if (blockers.get(e) == u) {
                releaseBlock(e);
            }
        }
    }

    /** 胜负与星级（策划 §2.3）。 */
    private void judge() {
        if (finished) {
            return;
        }
        if (scene.allDoorsLost()) {
            finished = true;
            won = false;
            stars = 0;
            return;
        }
        // 以"调度器说全部放完"为准；同时保留数量兜底（没有调度器时靠它）
        boolean allOut = allSpawned || (spawnLimit > 0 && spawned >= spawnLimit);
        if (allOut && scene.enemies().isEmpty()) {
            finished = true;
            won = true;
            stars = scene.leaked() == 0 ? 3 : 2;
        }
    }

    /**
     * 敌人到终点的剩余路程。
     *
     * <p>⚠️ **不要取整**：上一版 {@code Math.round(distanceAt(...))} 造成大量并列，
     * 让次级排序彻底失效。Defender 用的是 float 几何路程。
     *
     * @param e 敌人
     * @return 剩余路程（格）；拿不到流场时返回 {@link Float#MAX_VALUE}
     */
    private float remainingDistance(Enemy e) {
        FlowField f = scene.fieldFor(e.targetBlueDoorId());
        return f == null ? Float.MAX_VALUE : f.distanceAt(e.col(), e.row());
    }

    /**
     * @param e 敌人
     * @return 稳定的 id（identity hash，够与事件对上）
     */
    private int idOf(Enemy e) {
        return System.identityHashCode(e);
    }

    /**
     * 记一条事件。
     *
     * @param kind  类型
     * @param a     主体
     * @param b     客体
     * @param value 数值
     * @param text  文本
     */
    private void emit(BattleEvent.Kind kind, long a, long b, float value, String text) {
        events.add(BattleEvent.of(kind, time, (int) a, (int) b, value, text));
    }

    /**
     * 记一条**带坐标**的事件。
     *
     * @param kind  类型
     * @param a     主体
     * @param b     客体
     * @param value 数值
     * @param text  文本
     * @param x     世界 x
     * @param y     世界 y
     */
    private void emit(BattleEvent.Kind kind, long a, long b, float value, String text,
                      float x, float y) {
        events.add(BattleEvent.of(kind, time, (int) a, (int) b, value, text, x, y));
    }

    // ------------------------------------------------------------------
    // 事件与查询
    // ------------------------------------------------------------------

    /**
     * 取走本帧产生的事件（取完清空）。
     *
     * @return 事件列表；没有返回空表
     */
    public List<BattleEvent> drainEvents() {
        if (events.isEmpty()) {
            return List.of();
        }
        List<BattleEvent> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    /**
     * 尝试部署。
     *
     * @param type   干员类型
     * @param col    列
     * @param row    行
     * @param facing 朝向
     * @return 成功返回 {@code null}；失败返回给玩家看的原因
     */
    public String deploy(Operator type, int col, int row, Director facing) {
        if (deployment.deployed().size() >= DEPLOY_LIMIT) {
            return "部署位已满（" + DEPLOY_LIMIT + "/" + DEPLOY_LIMIT + "），先撤退一个";
        }
        DeploymentSystem.Result r = deployment.deploy(type, col, row, facing);
        if (r == DeploymentSystem.Result.OK) {
            OperatorUnit u = deployment.deployedOf(type.id());
            emit(BattleEvent.Kind.DEPLOY, u == null ? 0 : u.deploySeq(), 0,
                    type.cost(), type.id());
            return null;
        }
        return r.message();
    }

    /**
     * 只判定不执行：这一格能不能放这个干员。
     *
     * @param type 干员
     * @param col  列
     * @param row  行
     * @return 能放返回 {@code null}；不能放返回原因
     */
    public String checkDeploy(Operator type, int col, int row) {
        if (deployment.deployed().size() >= DEPLOY_LIMIT) {
            return "部署位已满";
        }
        DeploymentSystem.Result r = deployment.check(type, col, row);
        return r == DeploymentSystem.Result.OK ? null : r.message();
    }

    /**
     * 撤退某格上的干员。
     *
     * @param col 列
     * @param row 行
     * @return 实际返还费用；该格没人返回 0
     */
    public int retreatAt(int col, int row) {
        OperatorUnit u = deployment.unitAt(col, row);
        if (u == null) {
            return 0;
        }
        releaseBlock(u);
        int back = deployment.retreat(u);
        emit(BattleEvent.Kind.RETREAT, u.deploySeq(), 0, back, u.type().id());
        return back;
    }

    /**
     * @return 场上干员的只读快照
     */
    public List<UnitView> unitViews() {
        List<UnitView> out = new ArrayList<>();
        for (OperatorUnit u : deployment.deployed()) {
            out.add(new UnitView(u.col(), u.row(), u.facing(), u.type().name(),
                    u.hp(), u.type().hp(), u.blockingWeight(), u.type().block(),
                    u.timeSinceAttack(), u.hitFlash(),
                    u.type().atk(), u.type().def(), u.type().res()));
        }
        return out;
    }

    /**
     * @return 编队
     */
    public List<Operator> squad() {
        return deployment.squad();
    }

    /**
     * @param id 干员 id
     * @return 再部署剩余冷却（秒）
     */
    public float cooldownOf(String id) {
        return deployment.cooldownOf(id);
    }

    /**
     * @param id 干员 id
     * @return 冷却好了没（不考虑是否已在场）
     */
    public boolean readyToDeploy(String id) {
        return deployment.readyToDeploy(id);
    }

    /**
     * 某个干员是否**已经在场上**。
     *
     * @param id 干员 id
     * @return 在场返回 {@code true}
     */
    public boolean onField(String id) {
        return deployment.deployedOf(id) != null;
    }

    /**
     * @return 当前在场人数
     */
    public int deployedCount() {
        return deployment.deployed().size();
    }

    /**
     * @return 当前费用（向下取整）
     */
    public int costWhole() {
        return cost.whole();
    }

    /**
     * @return 费用显示的文本
     */
    public String costText() {
        return cost.summary();
    }

    /**
     * @return 是否已经分出胜负
     */
    public boolean finished() {
        return finished;
    }

    /**
     * @return 是否胜利（仅 {@link #finished()} 为真时有意义）
     */
    public boolean won() {
        return won;
    }

    /**
     * @return 星级（0/2/3，策划 §2.3）
     */
    public int stars() {
        return stars;
    }

    /**
     * @return 这一局的时长（秒）
     */
    public float time() {
        return time;
    }

    /**
     * @return 场上还在被挡住的敌人数（HUD/调试用）
     */
    /** @return 已消灭的敌人数 */
    public int killedCount() {
        return scene.killedCount();
    }

    /** @return 本关敌人总数（由 UI 同步进来的 spawnLimit） */
    public int totalEnemies() {
        return spawnLimit;
    }

    public int blockedCount() {
        return blockers.size();
    }

    /**
     * @return 地图（UI 判定格子用）
     */
    public TileMap map() {
        return map;
    }
}
