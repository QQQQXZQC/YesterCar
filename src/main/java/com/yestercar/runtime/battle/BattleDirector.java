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
     * 同时在场上限（固定 10，所有地图相同）。
     *
     * <p>语义是"**同时**在场"—— 撤退/阵亡后空出的位置可以再放一个。
     * 不阻止开战：编队 12 人但只有 10 个位置，本身就是塔防的策略空间。
     */
    public static final int DEPLOY_LIMIT = 10;

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

    // ------------------------------------------------------------------
    // 投射物（普攻特效）：法球离手 → 命中那一 tick 才结算伤害
    // ------------------------------------------------------------------

    /** 一枚在飞的投射物（近战也走这条：{@code flight = 0}，命中时刻就是挥砍打到的那一下）。 */
    private static final class Shot {
        float x0;
        float y0;
        float tx;
        float ty;
        float elapsed;
        float releaseAt;
        float flight;
        float speed;
        float damage;
        String damageType;
        Enemy target;
        OperatorUnit attacker;
        long attackerSeq;
        boolean supporter;
        boolean released;
    }

    /** 在飞的投射物。 */
    private final List<Shot> shots = new ArrayList<>();

    /**
     * 正在生效的角色**特性**（目前只有"部署即生效"这一类）。
     *
     * <p>特性是**数据**（{@code data/traits.json}），引擎每 tick 解释它 —— 现在只支持
     * 一种效果组合：持续期间改阻挡数 + 每秒回费，到点自动恢复。
     */
    private static final class ActiveTrait {
        OperatorUnit unit;
        com.yestercar.common.model.TraitTable.Trait def;
        float remaining;
    }

    private final List<ActiveTrait> activeTraits = new ArrayList<>();

    /**
     * 每个"有战斗效果"的特性实例状态（攻击力倍率、延迟生效的阻挡、伤害类型、无视法抗、
     * 不死时间、半血除颤、伤害下限）。
     *
     * <p>一次部署一份，撤退/阵亡时丢掉；重新部署会重新计时（斯卡蒂的 15 秒、玛恩纳的 50 秒）。
     */
    private static final class TraitState {
        OperatorUnit unit;
        com.yestercar.common.model.TraitTable.Trait def;
        float atkScale = 1f;
        float panicScale = 1f;
        boolean atkOn;
        float atkActiveAt;
        float atkExpireAt = -1f;
        boolean blockApplied;
        float blockAt;
        boolean panicUsed;
        boolean undying;
        float undyingUntil;
        /** 四月：已经打了几次（每满 nthAttack 次，下一击强化）。 */
        int attacks;
        boolean chargeReady;
        /** 能天使：当前叠了几层、上一击打的是谁。 */
        int stacks;
        Enemy stackTarget;
        /** 夕「点睛」：靠击杀累计的攻击力层数。 */
        int killStacks;
    }

    private final Map<OperatorUnit, TraitState> traitStates = new IdentityHashMap<>();

    /**
     * 角色特性给的**防御力加成**（蛇屠箱「壳状防御」：周围相连四格）。每 tick 重算一次，
     * 所以干员走位/撤退后立刻失效，不需要额外清理。
     */
    private final Map<OperatorUnit, Float> defBonus = new IdentityHashMap<>();

    /**
     * 角色特性给的**攻击力加成**（阿米娅「情绪吸收」全场 +18%、艾雅法拉「炎息」术士 +30%）。
     * 每 tick 重算，值含义是"乘数加成"（0.18 = ×1.18）。
     */
    private final Map<OperatorUnit, Float> atkAura = new IdentityHashMap<>();

    /** 角色特性给的**防御百分比加成**（凯尔希「遗尘守望」范围内 +15%）。 */
    private final Map<OperatorUnit, Float> defPctAura = new IdentityHashMap<>();

    /** 角色特性给的**最大生命倍率**（同上）。 */
    private final Map<OperatorUnit, Float> hpScaleAura = new IdentityHashMap<>();

    /** 一次性的限时攻击力加成（华法琳「不稳定血浆」）：{倍率, 到期时刻}。 */
    private final Map<OperatorUnit, float[]> tempAtkBuff = new IdentityHashMap<>();

    /**
     * 还没生效的**敌方攻击**（命中帧/弹体飞行段）。见 {@link EnemyHit}。
     */
    private final List<EnemyHit> enemyHits = new java.util.ArrayList<>();

    /**
     * 待表现层取走的敌方远程攻击（{@code {x0, y0, x1, y1}}，世界格）。
     * 引擎只管"什么时候有弹体飞出来"，画什么由渲染层决定（与干员普攻特效同一分工）。
     */
    private final List<float[]> enemyFx = new java.util.ArrayList<>();

    /**
     * 一次敌方攻击的"延迟命中"。
     *
     * <p>为什么要延迟：动画里近战是"抬刀 → 挥下来打到人"、远程是"抬手 → 法球飞过去"，
     * 扣血必须落在**打到的那一下**，否则会出现"人还没被打到就掉血 / 弹体还在半路人就死了"。
     *
     * <p>参数全部在出手那一刻定好（伤害值、目标），只有"什么时候生效"被推迟：
     * <ul>
     *   <li>近战（{@code speed = 0}）：{@code elapsed >= releaseAt} 时生效；</li>
     *   <li>远程（{@code speed > 0}）：{@code releaseAt} 那一刻锁定目标位置并算出飞行时间
     *       {@code flight}，到 {@code releaseAt + flight} 才生效。</li>
     * </ul>
     */
    private static final class EnemyHit {
        final Enemy source;
        final OperatorUnit target;
        final float releaseAt;
        final float damage;
        final boolean arts;
        final float speed;
        /** 飞行段是否已经开始（远程 = 已离手）。 */
        boolean released;
        float elapsed;
        float flight;
        float x0;
        float y0;
        float tx;
        float ty;

        EnemyHit(Enemy source, OperatorUnit target, float releaseAt, float damage, boolean arts,
                 float speed) {
            this.source = source;
            this.target = target;
            this.releaseAt = Math.max(0f, releaseAt);
            this.damage = damage;
            this.arts = arts;
            this.speed = speed;
        }
    }

    /**
     * 孢子（波登可「孢子扩散」）：敌人 → {剩余秒数, 每秒伤害}。每次命中刷新时长，不叠层。
     */
    private final Map<Enemy, float[]> spores = new IdentityHashMap<>();

    /** @return 本帧在飞的投射物数（诊断用） */
    public int shotsInFlight() {
        return shots.size();
    }

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
        tickTraits(dt);

        refreshBlocking();
        enemiesAct(dt);
        advanceEnemyHits(dt);
        int leakedBefore = scene.leaked();
        scene.update(dt);
        if (scene.leaked() > leakedBefore) {
            emit(BattleEvent.Kind.LEAK, 0, 0, scene.leaked() - leakedBefore, "leak");
        }
        refreshBlocking();
        operatorsAct(dt);
        advanceShots(dt);
        sweepDead();
        judge();
    }

    /**
     * 推进在飞的投射物；**到达那一 tick 才结算伤害**（方案 B）。
     *
     * <p>与即时结算路径的差别只有"什么时候扣血"：伤害值在出手时就算好了（和原来同一个
     * {@code Combat.damage} 调用），命中后照旧发 {@code DAMAGE} 事件、击杀时发 {@code DIE}
     * 并释放阻挡位 —— 所以向下游（表现层、结算、掉落）观察，行为和以前完全一样，只是晚了几百毫秒。
     *
     * <p>落点用**出手那一刻锁定的位置**：这样"弹体飞到哪"和"伤害打在哪"是同一个点，
     * 表现层用同一条弹道画出来，爆炸与掉血天然同帧。
     *
     * @param dt 本 tick 时长（秒）
     */
    private void advanceShots(float dt) {
        if (shots.isEmpty()) {
            return;
        }
        java.util.Iterator<Shot> it = shots.iterator();
        while (it.hasNext()) {
            Shot s = it.next();
            s.elapsed += dt;
            // 离手那一 tick：按"目标此刻的位置"重算落点与飞行时长 ——
            // 表现层也是在离手这一刻朝敌人当前位置画弹道的，两边因此完全一致。
            // 近战没有飞行段（speed=0），这里保持 flight=0，命中就在挥砍打到的那一 tick。
            if (!s.released && s.elapsed >= s.releaseAt) {
                s.released = true;
                Enemy t = s.target;
                if (t != null && t.alive() && s.speed > 0f) {
                    float dx = t.x() - s.x0;
                    float dy = t.y() - s.y0;
                    s.tx = t.x();
                    s.ty = t.y();
                    s.flight = Math.max(0.03f,
                            (float) Math.sqrt(dx * dx + dy * dy) / Math.max(0.01f, s.speed));
                } else if (t != null) {
                    s.tx = t.x();
                    s.ty = t.y();
                }
            }
            if (s.elapsed < s.releaseAt + s.flight) {
                continue;                       // 还没飞到
            }
            it.remove();
            Enemy t = s.target;
            if (t == null || !t.alive()) {
                continue;                       // 目标先倒了：这一发落空（不放伤害）
            }
            t.takeDamage(s.damage);
            emit(BattleEvent.Kind.DAMAGE, s.attackerSeq, idOf(t), s.damage, s.damageType);
            healOnHit(s.attacker);              // 焰影苇草「灼痕映曜」
            applyOnHitTrait(s.attacker, t);     // 巫恋减益 / 波登可孢子
            // 辅助：命中后减速（法术伤害照常结算）
            if (s.supporter) {
                t.applySlow(time, 0.5f, 2f);
                emit(BattleEvent.Kind.EXTRA, s.attackerSeq, idOf(t), 2f, "slow", t.x(), t.y());
            }
            if (!t.alive()) {
                emit(BattleEvent.Kind.DIE, idOf(t), 0, 0f, "killed", t.x(), t.y());
                releaseBlock(t);
                grantKillCost(s.attacker);
            }
        }
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
        java.util.List<int[]> cells = e.type().rangeCells(e.facing());
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
                    ? Combat.artsDamage(e.type().atk(), resOf(target))
                    : Combat.physicalDamage(e.type().atk(), defOf(target));
            dmg *= damageTakenMul(target);          // 铃兰「画地为牢」

            // 命中时机：近战等攻击动画"打到"那一帧、远程等弹体飞到（data/enemy-attack.csv）
            com.yestercar.common.model.EnemyAttackTable.Spec atk =
                    com.yestercar.common.model.EnemyAttackTable.get(e.type().id());
            if (atk == null || atk.release() <= 0f) {
                strikeOperator(e, target, dmg, arts);       // 没有时序数据：出手即结算
                continue;
            }
            enemyHits.add(new EnemyHit(e, target, atk.release(), dmg, arts, atk.speed()));
        }
    }

    /**
     * 推进敌方攻击：到点才真正扣血（近战＝命中帧，远程＝弹体飞到那一 tick）。
     *
     * <p>与干员的 {@code advanceShots} 完全对称：伤害值在出手时就算好了，这里只管生效时刻。
     *
     * @param dt 帧时长（秒）
     */
    private void advanceEnemyHits(float dt) {
        if (enemyHits.isEmpty()) {
            return;
        }
        java.util.Iterator<EnemyHit> it = enemyHits.iterator();
        while (it.hasNext()) {
            EnemyHit h = it.next();
            h.elapsed += dt;
            if (!h.released && h.elapsed >= h.releaseAt) {
                // 离手：锁定目标此刻的位置，算出飞行时间；远程记一笔给表现层放弹体
                h.released = true;
                h.x0 = h.source == null ? 0f : h.source.x();
                h.y0 = h.source == null ? 0f : h.source.y();
                h.tx = h.target == null ? h.x0 : h.target.col() + 0.5f;
                h.ty = h.target == null ? h.y0 : h.target.row() + 0.5f;
                float dx = h.tx - h.x0;
                float dy = h.ty - h.y0;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                h.flight = h.speed > 0f ? Math.max(0.03f, dist / h.speed) : 0f;
                if (h.speed > 0f) {
                    enemyFx.add(new float[] {h.x0, h.y0, h.tx, h.ty});
                }
            }
            if (!h.released || h.elapsed < h.releaseAt + h.flight) {
                continue;
            }
            it.remove();
            if (h.target == null || !h.target.alive()) {
                continue;                       // 目标已经倒了：这一下打空
            }
            if (h.speed <= 0f && (h.source == null || !h.source.alive())) {
                continue;                       // 近战：出手的人先死了，这一刀就没了
            }
            strikeOperator(h.source, h.target, h.damage, h.arts);
        }
    }

    /**
     * 敌方攻击**真正生效**的那一下：扣血、特性兜底（不死）、反伤、阵亡。
     *
     * @param e      出手的敌人（可能已经死了，事件里仍用它做来源）
     * @param target 挨打的干员
     * @param dmg    伤害值（出手时已算好）
     * @param arts   是否法术伤害
     */
    private void strikeOperator(Enemy e, OperatorUnit target, float dmg, boolean arts) {
        if (target == null || !target.alive()) {
            return;
        }
        TraitState tst = traitStateOf(target);
        if (tst != null && tst.undying) {
            // 已经"血量清零"，8 秒内继续战斗：这期间挨打不再掉血
            emit(BattleEvent.Kind.DAMAGE, idOf(e), target.deploySeq(), 0f,
                    arts ? "arts" : "phys");
            return;
        }
        if (tst != null && tst.def.undyingSeconds() > 0f && !tst.undying
                && target.hp() - dmg <= 0f) {
            // 特性挡住这次死亡：血留 1 点（界面上等于清零），开始 8 秒倒计时
            target.takeDamage(Math.max(0f, target.hp() - 1f));
            tst.undying = true;
            tst.undyingUntil = time + tst.def.undyingSeconds();
            if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
                System.out.printf("[trait] UNDIE %s t=%.2f until=%.2f%n",
                        target.type().id(), time, tst.undyingUntil);
            }
            emit(BattleEvent.Kind.EXTRA, target.deploySeq(), 0,
                    tst.def.undyingSeconds(), "undying",
                    target.col() + 0.5f, target.row() + 0.5f);
            return;
        }
        target.takeDamage(dmg);
        emit(BattleEvent.Kind.DAMAGE, idOf(e), target.deploySeq(), dmg,
                arts ? "arts" : "phys");
        reflectDamage(target, e);
        if (!target.alive()) {
            emit(BattleEvent.Kind.DIE, target.deploySeq(), 0, 0f, "killed",
                    target.col() + 0.5f, target.row() + 0.5f);
            releaseBlock(target);
            deployment.onDeath(target);
        }
    }

    /**
     * 取走本帧"刚离手"的敌方远程攻击（表现层据此放弹体）。
     *
     * @return {@code {x0, y0, x1, y1}} 列表（世界格）；没有返回空列表
     */
    public List<float[]> drainEnemyAttackFx() {
        if (enemyFx.isEmpty()) {
            return java.util.List.of();
        }
        List<float[]> out = new java.util.ArrayList<>(enemyFx);
        enemyFx.clear();
        return out;
    }

    /**
     * 远程敌人在半径内找干员（切比雪夫距离，含边界）。
     *
     * <p>官方"攻击范围半径"是小数（高阶术师 2.2），这里按格子做切比雪夫比较，
     * 所以取 {@code ceil} —— 2.2 格 → 含到 3 格，与"范围内可打"的观感一致。
     *
     * @param e     敌人
     * @param units 场上干员
     * @return 最近的干员；没有返回 {@code null}
     */
    private OperatorUnit nearestInRadius(Enemy e, List<OperatorUnit> units) {
        int r = (int) Math.ceil(e.type().rangeRadius());
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
            com.yestercar.common.model.TraitTable.Trait unitTrait =
                    com.yestercar.common.model.TraitTable.of(u.type().id());
            boolean attacksInstead = unitTrait != null && unitTrait.noHeal();
            if (!attacksInstead
                    && u.type().damageType() == com.yestercar.common.model.DamageType.HEAL) {
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
                applyHealBuff(u, wounded);       // 华法琳「不稳定血浆」
                u.markAttacked();
                continue;
            }

            // 阻挡数为 0 时不攻击（玛恩纳：先是 0 挡不出手，部署 50 秒后特性把阻挡改成 4 才开始打）。
            // 冷却保持 0，所以 attackAge 一直停在攻击间隔上，表现层据此播 Idle 而不是 Attack。
            if (u.effectiveBlock() <= 0) {
                continue;
            }

            Enemy target = TargetSelector.pickEnemy(u, enemies,
                    e -> blockers.get(e) == u,
                    this::remainingDistance,
                    e -> e.type().taunt(),
                    e -> spawnSeq.getOrDefault(e, 0L));
            TraitState atkState = traitStates.get(u);
            if (atkState != null && atkState.def.focusLowestDef()) {
                // 四月「熟练猎手」：射程内优先打防御最低的（同等防御仍按原来的规则）
                Enemy lowest = null;
                float lowestDef = Float.MAX_VALUE;
                for (Enemy cand : enemies) {
                    if (!cand.alive() || !u.covers(cand.col(), cand.row())) {
                        continue;
                    }
                    float d = cand.type().def();
                    if (d < lowestDef - 1e-4f) {
                        lowestDef = d;
                        lowest = cand;
                    }
                }
                if (lowest != null) {
                    target = lowest;
                }
            }
            if (target == null) {
                // 冷却保持 0：目标一进范围当 tick 就打
                continue;
            }
            emit(BattleEvent.Kind.ATTACK, u.deploySeq(), idOf(target),
                    u.type().attackInterval(), "none");
            grantAttackCost(u);
            float dmg = damageOf(u, target, rollAttack(u, target));

            com.yestercar.common.model.AttackFxTable.Spec fxSpec =
                    com.yestercar.common.model.AttackFxTable.get(u.type().id());
            if (fxSpec != null) {
                // 只是"出手"：伤害等弹体到达（远程）或挥砍打到（近战）再结算，见 advanceShots
                Shot s = new Shot();
                s.x0 = u.col() + 0.5f;
                s.y0 = u.row() + 0.5f;
                s.tx = target.x();
                s.ty = target.y();
                float dist = (float) Math.sqrt((s.tx - s.x0) * (s.tx - s.x0)
                        + (s.ty - s.y0) * (s.ty - s.y0));
                s.releaseAt = fxSpec.release();
                s.speed = fxSpec.speed();
                // 近战没有飞行段：命中时刻 = 挥砍打到的那一下
                s.flight = fxSpec.melee() ? 0f : Math.max(0.03f, dist / Math.max(0.01f, s.speed));
                s.elapsed = 0f;
                s.damage = dmg;
                s.damageType = damageTypeOf(u).jsonName();
                s.target = target;
                s.attacker = u;
                s.attackerSeq = u.deploySeq();
                s.supporter = "Supporter".equals(u.type().opClass());
                shots.add(s);
                u.markAttacked();
                continue;
            }

            target.takeDamage(dmg);
            emit(BattleEvent.Kind.DAMAGE, u.deploySeq(), idOf(target), dmg,
                    damageTypeOf(u).jsonName());
            healOnHit(u);                       // 焰影苇草「灼痕映曜」
            applyOnHitTrait(u, target);         // 巫恋减益 / 波登可孢子

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
                grantKillCost(u);
            }
        }
    }

    /**
     * 推进正在生效的角色特性。
     *
     * <p>按数据逐项解释（目前支持：持续期间改阻挡数、每秒回费），到期就恢复并把这条特性摘掉。
     * 干员死了/被撤退时直接摘掉，不必恢复（人不在了）。
     *
     * @param dt 本 tick 时长（秒）
     */
    private void tickTraits(float dt) {
        tickTraitStates();
        if (activeTraits.isEmpty()) {
            return;
        }
        java.util.Iterator<ActiveTrait> it = activeTraits.iterator();
        while (it.hasNext()) {
            ActiveTrait s = it.next();
            if (!s.unit.alive() || !deployment.deployed().contains(s.unit)) {
                it.remove();
                continue;
            }
            cost.gain(s.def.dpPerSecond() * dt);
            s.remaining -= dt;
            if (s.def.periodic()) {
                // 周期型：每满一个 interval 结算一次（德克萨斯回费 / 古米治疗），不会结束
                if (s.remaining <= 0f) {
                    s.remaining += s.def.interval();
                    cost.gain(s.def.dpPerInterval());
                    if (s.def.healPerInterval() > 0f) {
                        healAround(s.unit, s.def.healPerInterval());
                    }
                    if (s.def.dpPerInterval() > 0f
                            && "1".equals(System.getProperty("yestercar.trait.debug"))) {
                        System.out.printf("[trait] TICK  %s +%.0f cost=%.1f%n",
                                s.unit.type().id(), s.def.dpPerInterval(), cost.cost());
                    }
                }
                continue;
            }
            if (s.remaining > 0f) {
                continue;
            }
            // 到期：恢复阻挡数，摘掉特性
            if (s.def.blockOverride() >= 0) {
                s.unit.setBlockOverride(-1);
            }
            it.remove();
            if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
                System.out.printf("[trait] END  %s t=%.2f cost=%.1f block=%d dp=%.1f/s%n",
                        s.unit.type().id(), time, cost.cost(), s.unit.effectiveBlock(),
                        s.def.dpPerSecond());
            }
            emit(BattleEvent.Kind.EXTRA, s.unit.deploySeq(), 0, 0f, "traitEnd",
                    s.unit.col() + 0.5f, s.unit.row() + 0.5f);
        }
    }

    /**
     * 推进战斗类特性：延迟生效的攻击力/阻挡、限时加成到期、半血除颤、不死到期。
     */
    private void tickTraitStates() {
        refreshDefAura();
        refreshAtkAura();
        refreshSupportAuras();
        tickTempBuffs();
        tickSpores();
        tickSlowAura();
        if (traitStates.isEmpty()) {
            return;
        }
        boolean dbg = "1".equals(System.getProperty("yestercar.trait.debug"));
        java.util.Iterator<Map.Entry<OperatorUnit, TraitState>> it =
                traitStates.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<OperatorUnit, TraitState> entry = it.next();
            OperatorUnit u = entry.getKey();
            TraitState st = entry.getValue();
            if (!u.alive() || !deployment.deployed().contains(u)) {
                it.remove();
                continue;
            }
            // 攻击力：到点开启、到点关闭
            if (!st.atkOn && st.def.atkScale() != 1f
                    && time >= st.atkActiveAt
                    && (st.atkExpireAt < 0f || time < st.atkExpireAt)) {
                st.atkOn = true;
                st.atkScale = st.def.atkScale();
                if (dbg) {
                    System.out.printf("[trait] ATKUP %s x%.2f t=%.2f%n",
                            u.type().id(), st.atkScale, time);
                }
            }
            if (st.atkOn && st.atkExpireAt >= 0f && time >= st.atkExpireAt) {
                st.atkOn = false;
                st.atkScale = 1f;
                if (dbg) {
                    System.out.printf("[trait] ATKEND %s t=%.2f%n", u.type().id(), time);
                }
            }
            // 阻挡：延迟覆盖
            if (!st.blockApplied && st.def.blockOverride() >= 0 && time >= st.blockAt) {
                u.setBlockOverride(st.def.blockOverride());
                st.blockApplied = true;
                if (dbg) {
                    System.out.printf("[trait] BLOCK %s -> %d t=%.2f%n",
                            u.type().id(), u.effectiveBlock(), time);
                }
            }
            // 半血除颤：回满血 + 攻击力加成，每次部署只触发一次
            if (st.def.panicHeal() && !st.panicUsed && u.hpRatio() < 0.5f) {
                u.heal(u.type().hp());
                st.panicUsed = true;
                st.panicScale = st.def.panicAtkScale();
                if (dbg) {
                    System.out.printf("[trait] PANIC %s hp=%.0f/%.0f atk x%.2f t=%.2f%n",
                            u.type().id(), u.hpRatio() * (float) u.type().hp(),
                            (float) u.type().hp(), st.panicScale, time);
                }
                emit(BattleEvent.Kind.EXTRA, u.deploySeq(), 0, 0f, "panic",
                        u.col() + 0.5f, u.row() + 0.5f);
            }
            // 不死到期：这时候才真的倒下
            if (st.undying && time >= st.undyingUntil) {
                st.undying = false;
                it.remove();
                if (dbg) {
                    System.out.printf("[trait] UNDIEEND %s t=%.2f%n", u.type().id(), time);
                }
                u.takeDamage(u.hp() + 1f);
                if (!u.alive()) {
                    emit(BattleEvent.Kind.DIE, u.deploySeq(), 0, 0f, "killed",
                            u.col() + 0.5f, u.row() + 0.5f);
                    releaseBlock(u);
                    deployment.onDeath(u);
                }
            }
        }
    }

    /**
     * 重算"周围相连四格 +防御"的光环（蛇屠箱「壳状防御」）。
     *
     * <p>只认**上下左右**四格（不是八格），且只给友方干员加，不含自己。
     */
    private void refreshDefAura() {
        if (defBonus.isEmpty()) {
            boolean any = false;
            for (OperatorUnit u : deployment.deployed()) {
                com.yestercar.common.model.TraitTable.Trait t =
                        com.yestercar.common.model.TraitTable.of(u.type().id());
                if (t != null && t.defAura() > 0f) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return;
            }
        }
        defBonus.clear();
        for (OperatorUnit aura : deployment.deployed()) {
            if (!aura.alive()) {
                continue;
            }
            com.yestercar.common.model.TraitTable.Trait t =
                    com.yestercar.common.model.TraitTable.of(aura.type().id());
            if (t == null || t.defAura() <= 0f) {
                continue;
            }
            for (OperatorUnit ally : deployment.deployed()) {
                if (!ally.alive()) {
                    continue;
                }
                if (ally != aura
                        && Math.abs(ally.col() - aura.col())
                        + Math.abs(ally.row() - aura.row()) != 1) {
                    continue;       // 上下左右相连的四格；**自身格也算**（蛇屠箱）
                }
                defBonus.merge(ally, t.defAura(), Float::sum);
            }
        }
    }

    /**
     * 重算队伍攻击力光环（阿米娅全场 +18%；艾雅法拉只给术士 +30%）。
     */
    private void refreshAtkAura() {
        atkAura.clear();
        for (OperatorUnit src : deployment.deployed()) {
            if (!src.alive()) {
                continue;
            }
            com.yestercar.common.model.TraitTable.Trait t =
                    com.yestercar.common.model.TraitTable.of(src.type().id());
            if (t == null || t.teamAtkBonus() <= 0f) {
                continue;
            }
            for (OperatorUnit ally : deployment.deployed()) {
                if (!ally.alive()) {
                    continue;
                }
                if (t.teamAtkBonusClass() != null
                        && !t.teamAtkBonusClass().equalsIgnoreCase(ally.type().opClass())) {
                    continue;
                }
                atkAura.merge(ally, t.teamAtkBonus(), Float::sum);
            }
        }
    }

    /**
     * 治疗/辅助类光环：
     * <ul>
     *   <li>凯尔希「遗尘守望」：攻击范围内友方 最大生命 +15%、防御 +15%；</li>
     *   <li>纯烬艾雅法拉「氤氲」：攻击范围内友方每秒回复 2% 生命。</li>
     * </ul>
     */
    private void refreshSupportAuras() {
        defPctAura.clear();
        hpScaleAura.clear();
        for (OperatorUnit src : deployment.deployed()) {
            if (!src.alive()) {
                continue;
            }
            com.yestercar.common.model.TraitTable.Trait t =
                    com.yestercar.common.model.TraitTable.of(src.type().id());
            if (t == null) {
                continue;
            }
            boolean stat = t.allyHpPct() > 0f || t.allyDefPct() > 0f;
            boolean heal = t.healAuraPercent() > 0f;
            if (!stat && !heal) {
                continue;
            }
            for (OperatorUnit ally : deployment.deployed()) {
                if (!ally.alive() || !src.covers(ally.col(), ally.row())) {
                    continue;
                }
                if (stat) {
                    hpScaleAura.merge(ally, t.allyHpPct(), Float::sum);
                    defPctAura.merge(ally, t.allyDefPct(), Float::sum);
                }
                if (heal) {
                    ally.heal(ally.maxHp() * t.healAuraPercent() * (1f / 30f));
                }
            }
        }
        for (OperatorUnit ally : deployment.deployed()) {
            ally.setHpScale(1f + hpScaleAura.getOrDefault(ally, 0f));
        }
    }

    /**
     * 推进限时攻击力加成（华法琳「不稳定血浆」）。
     */
    private void tickTempBuffs() {
        if (tempAtkBuff.isEmpty()) {
            return;
        }
        java.util.Iterator<Map.Entry<OperatorUnit, float[]>> it = tempAtkBuff.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<OperatorUnit, float[]> e = it.next();
            if (!e.getKey().alive() || time >= e.getValue()[1]) {
                it.remove();
            }
        }
    }

    /**
     * 给"被治疗的单位"挂一次限时攻击力加成（华法琳）。
     *
     * @param healer 施疗者
     * @param target 被治疗者
     */
    private void applyHealBuff(OperatorUnit healer, OperatorUnit target) {
        com.yestercar.common.model.TraitTable.Trait t =
                com.yestercar.common.model.TraitTable.of(healer.type().id());
        if (t == null || t.healAtkBuff() <= 0f || target == null) {
            return;
        }
        tempAtkBuff.put(target,
                new float[] {t.healAtkBuff(), time + Math.max(0.1f, t.healAtkBuffDuration())});
        if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
            System.out.printf("[trait] PLASMA %s -> %s +%.0f%% %.1fs%n",
                    healer.type().id(), target.type().id(),
                    t.healAtkBuff() * 100f, t.healAtkBuffDuration());
        }
    }

    /**
     * 造成伤害时给范围内友方回血（焰影苇草「灼痕映曜」）。
     *
     * @param src 造成伤害的干员
     */
    private void healOnHit(OperatorUnit src) {
        if (src == null) {
            return;
        }
        com.yestercar.common.model.TraitTable.Trait t =
                com.yestercar.common.model.TraitTable.of(src.type().id());
        if (t == null || t.lifestealPercent() <= 0f) {
            return;
        }
        float amount = src.type().atk() * t.lifestealPercent();
        for (OperatorUnit ally : deployment.deployed()) {
            if (!ally.alive() || !src.covers(ally.col(), ally.row())) {
                continue;
            }
            float healed = ally.heal(amount);
            if (healed > 0f) {
                emit(BattleEvent.Kind.EXTRA, src.deploySeq(), ally.deploySeq(), healed,
                        "heal", ally.col() + 0.5f, ally.row() + 0.5f);
                if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
                    System.out.printf("[trait] LIFESTEAL %s -> %s %.1f%n",
                            src.type().id(), ally.type().id(), healed);
                }
            }
        }
    }

    /**
     * 推进孢子（波登可「孢子扩散」）：每秒结算一次法术伤害。
     */
    private void tickSpores() {
        if (spores.isEmpty()) {
            return;
        }
        java.util.Iterator<Map.Entry<Enemy, float[]>> it = spores.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Enemy, float[]> entry = it.next();
            Enemy e = entry.getKey();
            float[] st = entry.getValue();     // {剩余秒数, 每秒伤害, 距下次结算秒数}
            if (!e.alive()) {
                it.remove();
                continue;
            }
            st[0] -= 1f / 30f;
            if (st[0] <= 0f) {
                it.remove();
                continue;
            }
            st[2] -= 1f / 30f;
            if (st[2] > 0f) {
                continue;
            }
            st[2] += 1f;
            float dmg = st[1];
            e.takeDamage(dmg);
            emit(BattleEvent.Kind.DAMAGE, 0, idOf(e), dmg, "arts");
            if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
                System.out.printf("[trait] SPORE %s %.1f%n", e.type().id(), dmg);
            }
            if (!e.alive()) {
                emit(BattleEvent.Kind.DIE, idOf(e), 0, 0f, "killed", e.x(), e.y());
                releaseBlock(e);
                it.remove();
            }
        }
    }

    /**
     * 命中后给对方挂减益（巫恋「溃败暗示」）与孢子（波登可「孢子扩散」）。
     *
     * @param src 出手的干员
     * @param t   被打中的敌人
     */
    private void applyOnHitTrait(OperatorUnit src, Enemy t) {
        if (src == null || t == null) {
            return;
        }
        com.yestercar.common.model.TraitTable.Trait tr =
                com.yestercar.common.model.TraitTable.of(src.type().id());
        if (tr == null) {
            return;
        }
        if (tr.defResShred() > 0f && t.alive()) {
            float keep = 1f - tr.defResShred();
            t.applyDefResDebuff(time, keep, keep, Math.max(0.1f, tr.shredDuration()));
            if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
                System.out.printf("[trait] SHRED %s def/res x%.2f %.1fs%n",
                        t.type().id(), keep, tr.shredDuration());
            }
        }
        if (tr.dotPercent() > 0f && t.alive()) {
            spores.put(t, new float[] {Math.max(0.1f, tr.dotDuration()),
                    src.type().atk() * tr.dotPercent(), 1f});
            if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
                System.out.printf("[trait] SPORE+ %s -> %s dps=%.1f %.1fs (spores=%d)%n",
                        src.type().id(), t.type().id(), src.type().atk() * tr.dotPercent(),
                        tr.dotDuration(), spores.size());
            }
        }
    }

    /**
     * 铃兰「画地为牢」：攻击范围内友方受到的伤害 ×0.9。
     *
     * @param target 被打的干员
     * @return 伤害倍率（1 = 不减）
     */
    private float damageTakenMul(OperatorUnit target) {
        float mul = 1f;
        for (OperatorUnit src : deployment.deployed()) {
            if (!src.alive()) {
                continue;
            }
            com.yestercar.common.model.TraitTable.Trait t =
                    com.yestercar.common.model.TraitTable.of(src.type().id());
            if (t == null || t.damageTakenMul() >= 1f) {
                continue;
            }
            if (src.covers(target.col(), target.row())) {
                mul *= t.damageTakenMul();
            }
        }
        return mul;
    }

    /**
     * 莫斯提马「主观缓时」：攻击范围内的敌人移动速度减半（与辅助的减速相乘叠加）。
     */
    private void tickSlowAura() {
        for (OperatorUnit src : deployment.deployed()) {
            if (!src.alive()) {
                continue;
            }
            com.yestercar.common.model.TraitTable.Trait t =
                    com.yestercar.common.model.TraitTable.of(src.type().id());
            if (t == null || t.slowAura() <= 0f) {
                continue;
            }
            for (Enemy e : scene.enemies()) {
                if (e.alive() && src.covers(e.col(), e.row())) {
                    e.applySlowAura(time, t.slowAura(), 0.2f);
                }
            }
        }
    }

    /**
     * @param u 干员
     * @return 实际防御力（含角色特性的光环加成）
     */
    private float defOf(OperatorUnit u) {
        float base = u.type().def();
        TraitState st = traitStates.get(u);
        if (st != null && st.atkOn && st.def.defScale() != 1f) {
            base *= st.def.defScale();          // W「设伏」：部署后 10 秒内防御力翻倍
        }
        base *= 1f + defPctAura.getOrDefault(u, 0f);   // 凯尔希「遗尘守望」+15%
        float total = base + defBonus.getOrDefault(u, 0f);
        if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
            System.out.printf("[trait] DEF %s base=%.1f pct=%.2f -> %.1f%n",
                    u.type().id(), (float) u.type().def(),
                    defPctAura.getOrDefault(u, 0f), total);
        }
        return total;
    }

    /**
     * @param u 干员
     * @return 实际法抗（含特性加值，例如 W 部署初期的 +15）
     */
    private int resOf(OperatorUnit u) {
        int base = u.type().res();
        TraitState st = traitStates.get(u);
        if (st != null && st.atkOn && st.def.resBonus() != 0f) {
            base += Math.round(st.def.resBonus());
        }
        return Math.max(0, base);
    }

    /**
     * 出手瞬间结算"这一击额外乘多少"：四月的第 N 击、克洛丝的暴击、能天使的叠层。
     *
     * <p>副作用是更新计数/叠层（每次攻击只调一次）。
     *
     * @param u 出手的干员
     * @param t 目标
     * @return 攻击力倍率（1 = 无额外加成）
     */
    private float rollAttack(OperatorUnit u, Enemy t) {
        TraitState st = traitStates.get(u);
        if (st == null) {
            return 1f;
        }
        float mul = 1f;
        boolean dbg = "1".equals(System.getProperty("yestercar.trait.debug"));
        // 四月「熟练猎手」：每 3 次攻击之后的下一击 +70%
        if (st.def.nthAttack() > 0) {
            if (st.chargeReady) {
                mul *= st.def.nthAttackScale();
                st.chargeReady = false;
                if (dbg) {
                    System.out.printf("[trait] CHARGE %s x%.2f%n",
                            u.type().id(), st.def.nthAttackScale());
                }
            } else {
                st.attacks++;
                if (st.attacks >= st.def.nthAttack()) {
                    st.attacks = 0;
                    st.chargeReady = true;
                }
            }
        }
        // 克洛丝「中的」：30% 概率当次 +100%
        if (st.def.critChance() > 0f && Math.random() < st.def.critChance()) {
            mul *= st.def.critScale();
            if (dbg) {
                System.out.printf("[trait] CRIT %s x%.2f%n", u.type().id(), st.def.critScale());
            }
        }
        // 能天使「火力电台」：连续打同一个目标叠层（攻击力 + 攻速）
        if (st.def.stackAtkPerHit() > 0f) {
            if (st.stackTarget == t) {
                st.stacks = Math.min(st.def.stackMax(), st.stacks + 1);
            } else {
                st.stacks = 0;
                st.stackTarget = t;
            }
            mul *= 1f + st.def.stackAtkPerHit() * st.stacks;
            float aspd = st.def.stackAspdPerHit() * st.stacks;
            u.setIntervalScale(aspd > 0f ? 100f / (100f + aspd) : 1f);
            if (dbg && st.stacks > 0) {
                System.out.printf("[trait] STACK %s %d 层 atk x%.2f 攻速 +%.0f%n",
                        u.type().id(), st.stacks, 1f + st.def.stackAtkPerHit() * st.stacks, aspd);
            }
        }
        return mul;
    }

    /**
     * 反弹伤害（星熊「荆棘」）：被打一次就还给对方 50% 攻击力的物理伤害。
     *
     * @param holder 挨打的干员
     * @param e      打人的敌人
     */
    private void reflectDamage(OperatorUnit holder, Enemy e) {
        com.yestercar.common.model.TraitTable.Trait t =
                com.yestercar.common.model.TraitTable.of(holder.type().id());
        if (t == null || t.reflectRatio() <= 0f || e == null || !e.alive()) {
            return;
        }
        float back = Combat.physicalDamage(holder.type().atk() * t.reflectRatio(),
                e.type().def());
        if (back <= 0f) {
            return;
        }
        e.takeDamage(back);
        emit(BattleEvent.Kind.DAMAGE, holder.deploySeq(), idOf(e), back, "phys");
        if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
            System.out.printf("[trait] REFLECT %s -> %s %.1f%n",
                    holder.type().id(), e.type().id(), back);
        }
        if (!e.alive()) {
            emit(BattleEvent.Kind.DIE, idOf(e), 0, 0f, "killed", e.x(), e.y());
            releaseBlock(e);
            grantKillCost(holder);
        }
    }

    /**
     * 周期治疗（古米「备用军粮」）：给周围八格里血量比例最低的友方回一次血。
     *
     * @param u     发动的干员
     * @param ratio 本次治疗 = 自身攻击力 × 该比例
     */
    private void healAround(OperatorUnit u, float ratio) {
        OperatorUnit best = null;
        float worst = 1f;
        for (OperatorUnit ally : deployment.deployed()) {
            if (!ally.alive() || ally.hpRatio() >= 1f) {
                continue;
            }
            // 「周围八格」含自身格（古米可以治疗自己）
            if (Math.max(Math.abs(ally.col() - u.col()), Math.abs(ally.row() - u.row())) > 1) {
                continue;
            }
            if (ally.hpRatio() < worst) {
                worst = ally.hpRatio();
                best = ally;
            }
        }
        if (best == null) {
            return;
        }
        float healed = best.heal(u.type().atk() * ratio);
        if (healed <= 0f) {
            return;
        }
        emit(BattleEvent.Kind.EXTRA, u.deploySeq(), best.deploySeq(), healed, "heal",
                best.col() + 0.5f, best.row() + 0.5f);
        if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
            System.out.printf("[trait] HEAL %s -> %s %.1f%n", u.type().id(), best.type().id(),
                    healed);
        }
    }

    /**
     * 这个干员当前的攻击力倍率（特性加成）。
     *
     * @param u 干员
     * @return 倍率（1 = 无加成）
     */
    private float atkScaleOf(OperatorUnit u) {
        float mul = 1f;
        TraitState st = traitStates.get(u);
        if (st != null) {
            mul = st.atkScale * st.panicScale;
            if (st.def.killStackAtk() > 0f) {
                mul *= 1f + st.def.killStackAtk() * st.killStacks;   // 夕「点睛」
            }
        }
        mul *= 1f + atkAura.getOrDefault(u, 0f);                     // 队伍光环
        float[] temp = tempAtkBuff.get(u);
        if (temp != null && time < temp[1]) {
            mul *= 1f + temp[0];                                    // 华法琳「不稳定血浆」
        }
        return mul;
    }

    /**
     * 固定攻击力加成（特性里的 {@code atkBonusFlat}，例如莫斯提马 +200）。
     *
     * @param u 干员
     * @return 加成值（0 = 无）
     */
    private float atkFlatOf(OperatorUnit u) {
        TraitState st = traitStates.get(u);
        return st == null ? 0f : Math.max(0f, st.def.atkBonusFlat());
    }

    /**
     * 这个干员当前的攻击伤害类型（史尔特尔的"熔火"会把物理改成法术）。
     *
     * @param u 干员
     * @return 类型
     */
    /**
     * 按干员显示名取**实时**伤害类型（含特性覆盖）。
     *
     * @param name 干员中文名（{@code UnitView.name()}）
     * @return 类型；找不到返回 {@code null}
     */
    public com.yestercar.common.model.DamageType damageTypeOfName(String name) {
        for (OperatorUnit u : deployment.deployed()) {
            if (u.type().name().equals(name)) {
                return damageTypeOf(u);
            }
        }
        return null;
    }

    private com.yestercar.common.model.DamageType damageTypeOf(OperatorUnit u) {
        TraitState st = traitStates.get(u);
        if (st != null && "arts".equalsIgnoreCase(st.def.damageTypeOverride())) {
            return com.yestercar.common.model.DamageType.ARTS;
        }
        return u.type().damageType();
    }

    /**
     * 算一次普攻伤害：套用攻击力倍率、伤害类型、无视法抗、伤害下限（陈的 5% 生命）。
     *
     * @param u 出手的干员
     * @param t 目标
     * @return 伤害
     */
    private float damageOf(OperatorUnit u, Enemy t) {
        return damageOf(u, t, 1f);
    }

    /**
     * @param u      出手的干员
     * @param t      目标
     * @param atkMul 这一击的额外倍率（四月蓄力 / 克洛丝暴击 / 能天使叠层）
     * @return 伤害
     */
    private float damageOf(OperatorUnit u, Enemy t, float atkMul) {
        com.yestercar.common.model.DamageType type = damageTypeOf(u);
        TraitState st = traitStates.get(u);
        float atk = u.type().atk() * atkScaleOf(u) * atkMul + atkFlatOf(u);
        int res = Math.round(t.type().res() * t.resFactor(time));   // 巫恋「溃败暗示」
        float def = t.type().def() * t.defFactor(time);
        if (st != null && st.def.resPierce() > 0f) {
            res = Math.max(0, Math.round(res - st.def.resPierce()));
        }
        if (st != null && st.def.defPierce() > 0f) {
            def = Math.max(0f, def - st.def.defPierce());     // W「设伏」：无视 300 防御
        }
        if (st != null && st.def.ignoreAllDefRes()) {
            def = 0f;                                        // 阿米娅「情绪吸收」：防御、法抗全无视
            res = 0;
        }
        float dmg = Combat.damage(atk, def, res, type);
        // 塞雷娅「钙质化」：被她挡住的敌人受到的全属性伤害 +50%
        OperatorUnit holder = blockers.get(t);
        if (holder != null && holder.alive()) {
            com.yestercar.common.model.TraitTable.Trait amp =
                    com.yestercar.common.model.TraitTable.of(holder.type().id());
            if (amp != null && amp.damageTakenAmp() > 0f) {
                dmg *= 1f + amp.damageTakenAmp();
            }
        }
        // 安洁莉娜「漂浮大地之上」：打"低防低抗"的目标额外 +50% 法术伤害
        if (st != null && st.def.condExtraDamage() > 0f
                && t.type().def() < st.def.condDefBelow()
                && t.type().res() < st.def.condResBelow()) {
            dmg *= 1f + st.def.condExtraDamage();
            if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
                System.out.printf("[trait] COND %s -> %s +%.0f%%%n", u.type().id(),
                        t.type().id(), st.def.condExtraDamage() * 100f);
            }
        }
        if (st != null && st.def.damageFloorMaxHp() > 0f) {
            // 陈「形意洞照」：取"攻击力结算值"与"敌方生命 5%"里更大的那个
            dmg = Math.max(dmg, t.type().hp() * st.def.damageFloorMaxHp());
        }
        return dmg;
    }

    /**
     * 拿到这个干员的特性状态（没有返回 {@code null}）。
     *
     * @param u 干员
     * @return 状态
     */
    private TraitState traitStateOf(OperatorUnit u) {
        return traitStates.get(u);
    }

    /**
     * 出手回费（角色特性：伊内丝的"影织" —— 每攻击一次回 1 点费用）。
     *
     * <p>在"确实打出了一次攻击"的那一 tick 调用（近战/远程两条路径共用同一个 ATTACK 事件点）。
     *
     * @param attacker 出手的干员（可能为 {@code null}）
     */
    private void grantAttackCost(OperatorUnit attacker) {
        if (attacker == null) {
            return;
        }
        com.yestercar.common.model.TraitTable.Trait t =
                com.yestercar.common.model.TraitTable.of(attacker.type().id());
        if (t == null || t.dpPerAttack() <= 0f) {
            return;
        }
        cost.gain(t.dpPerAttack());
        if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
            System.out.printf("[trait] ATK   %s +%.0f cost=%.1f%n",
                    attacker.type().id(), t.dpPerAttack(), cost.cost());
        }
    }

    /**
     * 击杀回费（角色特性：风笛的"军事传统" —— 每击败一名敌人回 2 点费用）。
     *
     * <p>只算"这个干员打死的"，漏怪/别的干员击杀都不算。
     *
     * @param killer 打出最后一击的干员（可能为 {@code null}）
     */
    private void grantKillCost(OperatorUnit killer) {
        if (killer == null) {
            return;
        }
        com.yestercar.common.model.TraitTable.Trait t =
                com.yestercar.common.model.TraitTable.of(killer.type().id());
        if (t == null) {
            return;
        }
        if (t.dpPerKill() > 0f) {
            cost.gain(t.dpPerKill());
            if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
                System.out.printf("[trait] KILL  %s +%.0f cost=%.1f%n",
                        killer.type().id(), t.dpPerKill(), cost.cost());
            }
        }
        // 夕「点睛」：每击杀一名敌人永久 +6%，最多 18 层
        TraitState st = traitStates.get(killer);
        if (t.killStackAtk() > 0f && st != null && st.killStacks < t.killStackMax()) {
            st.killStacks++;
            if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
                System.out.printf("[trait] KILLSTACK %s %d 层 x%.2f%n",
                        killer.type().id(), st.killStacks,
                        1f + t.killStackAtk() * st.killStacks);
            }
        }
    }

    /**
     * 部署成功时发动"部署即生效"的角色特性（桃金娘）。
     *
     * @param u 刚部署的干员
     */
    private void triggerDeployTrait(OperatorUnit u) {
        com.yestercar.common.model.TraitTable.Trait def =
                com.yestercar.common.model.TraitTable.of(u.type().id());
        if (def == null) {
            return;
        }
        // ⚠️ 被动特性（如安洁莉娜的加伤）也必须有 TraitState：
        //    伤害结算里写的是 `if (st != null && st.def.condExtraDamage() ...)`，
        //    之前只为 combatEffect 建状态，被动特性一律被判空跳过。
        TraitState st = new TraitState();
        st.unit = u;
        st.def = def;
        traitStates.put(u, st);
        if (def.combatEffect()) {
            st.blockAt = time + Math.max(0f, def.blockDelay());
            st.atkActiveAt = time + Math.max(0f, def.atkScaleDelay());
            st.atkExpireAt = def.atkScaleDuration() > 0f
                    ? st.atkActiveAt + def.atkScaleDuration() : -1f;
            // 立即生效的那一类（延迟为 0）当场应用，免得晚一个 tick
            if (def.blockOverride() >= 0 && def.blockDelay() <= 0f) {
                u.setBlockOverride(def.blockOverride());
                st.blockApplied = true;
            }
            if (def.atkScale() != 1f && def.atkScaleDelay() <= 0f) {
                st.atkOn = true;
                st.atkScale = def.atkScale();
            }
        }
        if (!def.onDeploy() && !def.periodic()) {
            return;                     // 纯被动（如击杀回费）不需要计时器
        }
        if (def.periodic() && !def.onDeploy()) {
            // 周期型：部署后开始跑第一个周期
            ActiveTrait t = new ActiveTrait();
            t.unit = u;
            t.def = def;
            t.remaining = def.interval();
            activeTraits.add(t);
            if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
                System.out.printf("[trait] START %s t=%.2f cost=%.1f every=%.1fs +%.0f%n",
                        u.type().id(), time, cost.cost(), def.interval(), def.dpPerInterval());
            }
            return;
        }
        if (def.blockOverride() >= 0) {
            u.setBlockOverride(def.blockOverride());
        }
        ActiveTrait s = new ActiveTrait();
        s.unit = u;
        s.def = def;
        s.remaining = def.duration();
        activeTraits.add(s);
        if ("1".equals(System.getProperty("yestercar.trait.debug"))) {
            System.out.printf("[trait] START %s t=%.2f cost=%.1f block=%d dur=%.1fs dp=%.1f/s%n",
                    u.type().id(), time, cost.cost(), u.effectiveBlock(), def.duration(),
                    def.dpPerSecond());
        }
        emit(BattleEvent.Kind.EXTRA, u.deploySeq(), 0, def.duration(), "traitStart",
                u.col() + 0.5f, u.row() + 0.5f);
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
            if (u != null) {
                triggerDeployTrait(u);
            }
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
        // 角色特性可以把撤退返还比例改成 100%（风笛的"军事传统"）
        float ratio = com.yestercar.common.model.TraitTable.refundRatioOf(
                u.type().id(), CostSystem.REFUND_RATIO);
        int back = deployment.retreat(u, ratio);
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
                    u.hp(), u.maxHp(), u.blockingWeight(), u.effectiveBlock(),
                    u.timeSinceAttack(), u.hitFlash(),
                    Math.round(u.type().atk() * atkScaleOf(u) + atkFlatOf(u)),
                    Math.round(defOf(u)), Math.round(resOf(u))));
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
