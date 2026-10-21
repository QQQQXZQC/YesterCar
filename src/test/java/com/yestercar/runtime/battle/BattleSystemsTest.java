package com.yestercar.runtime.battle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.model.TileType;
import com.yestercar.common.model.LevelParams;
import com.yestercar.common.model.Operator;
import com.yestercar.common.util.Director;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 战斗五系统的规则测试（策划方案 §2.4 / §2.5 / §2.7 / §2.8 / §2.9）。
 *
 * <h2>为什么这些测试值得写</h2>
 * 这五条规则的错误**都不会抛异常**，只会表现为"打不过 / 一直不扣血 / 一个干员挡一片"。
 * 没有测试就只能靠肉眼，而肉眼分不清是规则错还是数值不合适。
 *
 * <h2>为什么本测试和被测类同包</h2>
 * 这些系统是包级私有（{@code final class} 不带 {@code public}）——
 * 它们是战斗引擎的内部件，不该被 UI 直接调用。同包测试是 Java 的标准做法，
 * 比在 main 里加一层"test access"外壳要干净。
 */
class BattleSystemsTest {

    /** 干员类型表（真实读 operators.json）。 */
    private static final Map<String, Operator> OPS = Operator.loadAll();

    /**
     * 零费副本：本组用例测的是阻挡/攻击/冷却，费用高低不该影响结果。
     *
     * @param src 原干员
     * @return 费用为 0 的副本
     */
    private static Operator free(Operator src) {
        return new Operator(src.id(), src.name(), src.deployType(), 10, src.block(),
                src.hp(), src.atk(), src.def(), src.attackInterval(), src.redeployTime(),
                src.portrait(), src.range(), src.taunt(), src.damageType(), src.res(),
                src.opClass(), src.illustration());
    }

    /**
     * @param src   原干员
     * @param block 新阻挡数
     * @return 改了阻挡数的副本
     */
    private static Operator withBlock(Operator src, int block) {
        return new Operator(src.id(), src.name(), src.deployType(), src.cost(), block,
                src.hp(), src.atk(), src.def(), src.attackInterval(), src.redeployTime(),
                src.portrait(), src.range(), src.taunt(), src.damageType(), src.res(),
                src.opClass(), src.illustration());
    }

    private static Operator guard() {
        return free(OPS.get("chen"));
    }

    private static Operator sniper() {
        return free(OPS.get("kroos"));
    }

    /**
     * 造一张 6×4 的小地图：
     * <pre>
     *   row 0: 全是不可通行
     *   row 1: (3,1)(4,1) 地面
     *   row 2: (3,2) 地面，(2,2) 高台
     * </pre>
     */
    private static TileMap map() {
        TileMap m = new TileMap("test", 6, 4);
        for (int c = 0; c < 6; c++) {
            for (int r = 0; r < 4; r++) {
                m.setTile(c, r, TileType.IMPASSABLE);
            }
        }
        m.setTile(3, 1, TileType.GROUND);
        m.setTile(4, 1, TileType.GROUND);
        m.setTile(3, 2, TileType.GROUND);
        m.setTile(2, 2, TileType.HIGH);
        return m;
    }

    private static CostSystem cost() {
        return new CostSystem(LevelParams.DEFAULT);
    }

    /**
     * 造一个临时地面干员（测试用）。
     *
     * <p>"同一干员同时只能在场一个"这条规则生效后，用同一个干员测不了
     * OCCUPIED / NO_COST —— 会先被 ALREADY_DEPLOYED 拦下。所以这里造第二个地面干员。
     *
     * @param id   标识
     * @param cost 费用
     * @return 干员
     */
    private static Operator groundOp(String id, int cost) {
        return new Operator(id, id, com.yestercar.common.model.DeployType.GROUND,
                cost, 1, 500, 50, 0, 1f, 20, null, Operator.FALLBACK.range(), 0,
                com.yestercar.common.model.DamageType.PHYS, 0, "", null);
    }

    private static Enemy enemyAt(int col, int row) {
        EnemyType t = new EnemyType("soldier", "士兵", 300, 50, 0, 1f, 1f, 1, false, 0, 0, 0, null, "phys");
        return new Enemy(t, col + 0.5f, row + 0.5f, 1);
    }

    // ------------------------------------------------------------------
    // 伤害公式（策划未规定，取自参考实现的官方规则）
    // ------------------------------------------------------------------

    @Test
    void 物理伤害等于攻击减防御() {
        assertEquals(50, Combat.physicalDamage(100, 50));
        assertEquals(100, Combat.physicalDamage(100, 0));
    }

    @Test
    void 防御高于攻击时仍有保底伤害() {
        // 官方规则：至少造成攻击力 5%
        assertEquals(5, Combat.physicalDamage(100, 9999));
        assertEquals(0, Combat.physicalDamage(0, 100), "攻击力为 0 就是 0，不该有保底");
    }

    // ------------------------------------------------------------------
    // 费用（§2.4）
    // ------------------------------------------------------------------

    @Test
    void 初始费用与每秒回费() {
        CostSystem c = cost();
        assertEquals(10, c.whole());
        c.update(1f);
        assertEquals(11, c.whole());
        c.update(0.5f);
        assertEquals(11, c.whole(), "11.5 向下取整仍是 11");
    }

    @Test
    void 费用不足时扣不动() {
        CostSystem c = cost();
        assertFalse(c.spend(11));
        assertEquals(10, c.whole());
        assertTrue(c.spend(10));
        assertEquals(0, c.whole());
    }

    @Test
    void 撤退返还一半向下取整() {
        CostSystem c = cost();
        assertEquals(5, c.refund(10));
        assertEquals(6, c.refund(13), "13 × 50% = 6.5，向下取整 6");
    }

    // ------------------------------------------------------------------
    // 部署（§2.5 / §2.8）
    // ------------------------------------------------------------------

    @Test
    void 地面干员不能放高台_高台干员不能放地面() {
        DeploymentSystem d = new DeploymentSystem(map(), cost(), List.of(guard(), sniper()));
        // 高台 (2,2) 放近卫
        assertEquals(DeploymentSystem.Result.WRONG_TERRAIN,
                d.deploy(guard(), 2, 2, Director.RIGHT));
        // 地面 (3,1) 放狙击
        assertEquals(DeploymentSystem.Result.WRONG_TERRAIN,
                d.deploy(sniper(), 3, 1, Director.RIGHT));
    }

    @Test
    void 同格不能放两个干员() {
        Operator other = groundOp("g2", 10);
        DeploymentSystem d = new DeploymentSystem(map(), cost(), List.of(guard(), other));
        assertEquals(DeploymentSystem.Result.OK, d.deploy(guard(), 3, 1, Director.RIGHT));
        assertEquals(DeploymentSystem.Result.OCCUPIED, d.deploy(other, 3, 1, Director.RIGHT));
    }

    @Test
    void 费用不足放不下去且不扣钱() {
        Operator pricey = groundOp("g3", 999);
        DeploymentSystem d = new DeploymentSystem(map(), cost(), List.of(guard(), pricey));
        assertEquals(DeploymentSystem.Result.OK, d.deploy(guard(), 3, 1, Director.RIGHT));
        int after = d.deployed().size();
        // 近卫 10 费花完，此时剩 0；999 费的放不下
        assertEquals(DeploymentSystem.Result.NO_COST, d.deploy(pricey, 4, 1, Director.RIGHT));
        assertEquals(after, d.deployed().size(), "失败不该往场上加人");
    }

    @Test
    void 不在编队里不能部署() {
        DeploymentSystem d = new DeploymentSystem(map(), cost(), List.of(guard()));
        assertEquals(DeploymentSystem.Result.NOT_IN_SQUAD,
                d.deploy(sniper(), 2, 2, Director.RIGHT));
    }

    @Test
    void 同一干员同时只能在场一个() {
        DeploymentSystem d = new DeploymentSystem(map(), cost(), List.of(guard()));
        assertEquals(DeploymentSystem.Result.OK, d.deploy(guard(), 3, 1, Director.RIGHT));
        // 还有另一块地面 (4,1)，钱也够（先加钱），但同一个人不能再放一次
        CostSystem c = cost();
        DeploymentSystem d2 = new DeploymentSystem(map(), c, List.of(guard()));
        assertEquals(DeploymentSystem.Result.OK, d2.deploy(guard(), 3, 1, Director.RIGHT));
        c.update(20f);      // 补够费用
        assertEquals(DeploymentSystem.Result.ALREADY_DEPLOYED,
                d2.deploy(guard(), 4, 1, Director.RIGHT));
    }

    @Test
    void 撤退后进入再部署冷却_冷却结束才能重放() {
        DeploymentSystem d = new DeploymentSystem(map(), cost(), List.of(guard(), sniper()));
        assertEquals(DeploymentSystem.Result.OK, d.deploy(guard(), 3, 1, Director.RIGHT));
        OperatorUnit u = d.deployed().get(0);

        int back = d.retreat(u);
        assertEquals(5, back, "10 费撤退返还 5");
        assertFalse(d.readyToDeploy("chen"), "撤退后应当进冷却");
        assertEquals(DeploymentSystem.Result.COOLDOWN,
                d.deploy(guard(), 3, 1, Director.RIGHT));

        d.update(20f);
        assertFalse(d.readyToDeploy("chen"), "陈的再部署是 22 秒，20 秒时还没好");
        d.update(3f);
        assertTrue(d.readyToDeploy("chen"), "23 秒后应当就绪");
    }

    @Test
    void 阵亡不返还费用但同样进冷却() {
        CostSystem c = cost();
        DeploymentSystem d = new DeploymentSystem(map(), c, List.of(guard()));
        assertEquals(DeploymentSystem.Result.OK, d.deploy(guard(), 3, 1, Director.RIGHT));
        assertEquals(0, c.whole(), "先花掉 10 费，剩余 0");
        d.onDeath(d.deployed().get(0));
        assertEquals(0, d.deployed().size(), "阵亡的干员应当离场");
        assertEquals(0, c.whole(), "策划 §2.4：干员死亡不返还费用");
        assertFalse(d.readyToDeploy("chen"), "阵亡同样要进再部署冷却");
    }

    // ------------------------------------------------------------------
    // 阻挡（Defender 平方半径 + 权重）
    // ------------------------------------------------------------------

    /** 敌人世界坐标：格中心 = col + 0.5。 */
    private static float center(int cell) {
        return cell + 0.5f;
    }

    @Test
    void 阻挡位满了敌人直接穿过() {
        Operator one = withBlock(guard(), 1);
        DeploymentSystem d = new DeploymentSystem(map(), cost(), List.of(one));
        assertEquals(DeploymentSystem.Result.OK, d.deploy(one, 3, 1, Director.RIGHT));
        List<OperatorUnit> units = d.deployed();

        assertNotNull(BlockSystem.tryBlock(units, center(3), center(1), 3, 1, 1),
                "第一个敌人应当被接住");
        assertNull(BlockSystem.tryBlock(units, center(3), center(1), 3, 1, 1),
                "阻挡位满了第二个必须穿过");
    }

    @Test
    void 释放阻挡位后能再接一个() {
        DeploymentSystem d = new DeploymentSystem(map(), cost(), List.of(guard()));
        assertEquals(DeploymentSystem.Result.OK, d.deploy(guard(), 3, 1, Director.RIGHT));
        List<OperatorUnit> units = d.deployed();

        OperatorUnit u = BlockSystem.tryBlock(units, center(3), center(1), 3, 1, 1);
        assertNotNull(u);
        u.removeBlocking(1);
        assertNotNull(BlockSystem.tryBlock(units, center(3), center(1), 3, 1, 1),
                "释放后应当能再接一个");
    }

    @Test
    void 阻挡数不会低于零() {
        OperatorUnit u = new OperatorUnit(guard(), 3, 1, Director.RIGHT);
        u.removeBlocking(1);
        u.removeBlocking(1);
        assertEquals(0, u.blockingWeight());
        assertEquals(0, u.blocking());
    }

    @Test
    void 阻挡用半径而不是格子中心() {
        // 近卫 block=1，站在 (3,1)。敌人从右边走过来但还没进格（x=3.9，距中心 0.4）
        DeploymentSystem d = new DeploymentSystem(map(), cost(), List.of(guard()));
        assertEquals(DeploymentSystem.Result.OK, d.deploy(guard(), 3, 1, Director.RIGHT));
        List<OperatorUnit> units = d.deployed();

        // 0.4 格 < 0.7071 → 应当被抓住（按格子判定则不会，因为还没进格）
        assertNotNull(BlockSystem.tryBlock(units, 3.9f, center(1), 3, 1, 1),
                "距格中心 0.4 格时应当被阻挡（半径 0.7071）");
    }

    @Test
    void 半径之外不会被阻挡() {
        DeploymentSystem d = new DeploymentSystem(map(), cost(), List.of(guard()));
        assertEquals(DeploymentSystem.Result.OK, d.deploy(guard(), 3, 1, Director.RIGHT));
        List<OperatorUnit> units = d.deployed();

        // 1.0 格 > 0.7071 → 抓不住
        assertNull(BlockSystem.tryBlock(units, 4.5f, center(1), 4, 1, 1),
                "距格中心 1.0 格时不该被阻挡");
    }

    /** 造一个指定阻挡数的临时地面干员（费用 0，避免被费用卡住）。 */
    private static Operator blockOp(int block) {
        return new Operator("blocky_" + block, "测试阻挡" + block,
                com.yestercar.common.model.DeployType.GROUND,
                0, block, 500, 10, 0, 1f, 20, null, Operator.FALLBACK.range(), 0,
                com.yestercar.common.model.DamageType.PHYS, 0, "", null);
    }

    @Test
    void 阻挡按权重占用而不是按人数() {
        Operator guard = blockOp(4);
        DeploymentSystem d = new DeploymentSystem(map(), cost(), List.of(guard));
        assertEquals(DeploymentSystem.Result.OK, d.deploy(guard, 3, 1, Director.RIGHT));
        List<OperatorUnit> units = d.deployed();

        // 一只权重 3 的敌人占 3 位，block=4 还能再放一只权重 1 的
        assertNotNull(BlockSystem.tryBlock(units, center(3), center(1), 3, 1, 3));
        assertNotNull(BlockSystem.tryBlock(units, center(3), center(1), 3, 1, 1));
        // 第三只权重 1 就放不下了（3+1+1 > 4）
        assertNull(BlockSystem.tryBlock(units, center(3), center(1), 3, 1, 1));
    }

    // ------------------------------------------------------------------
    // 选敌（Defender 优先级链）
    // ------------------------------------------------------------------

    @Test
    void 干员优先打离蓝门更近的敌人() {
        OperatorUnit u = new OperatorUnit(guard(), 3, 1, Director.DOWN);
        Enemy near = enemyAt(3, 2);
        Enemy far = enemyAt(3, 2);

        Enemy picked = TargetSelector.pickEnemy(u, List.of(far, near),
                e -> false, e -> e == near ? 1.0 : 9.0, e -> 0, e -> 0L);
        assertEquals(near, picked, "应当选中剩余路程更小的那只");
    }

    @Test
    void 距离相同时按出生序号() {
        OperatorUnit u = new OperatorUnit(guard(), 3, 1, Director.DOWN);
        Enemy early = enemyAt(3, 2);
        Enemy late = enemyAt(3, 2);

        Enemy picked = TargetSelector.pickEnemy(u, List.of(late, early),
                e -> false, e -> 5.0, e -> 0, e -> e == early ? 1L : 2L);
        assertEquals(early, picked);
    }

    @Test
    void 自己挡住的敌人优先于更近的() {
        OperatorUnit u = new OperatorUnit(guard(), 3, 1, Director.DOWN);
        Enemy blockedByMe = enemyAt(3, 2);
        Enemy closer = enemyAt(3, 2);

        Enemy picked = TargetSelector.pickEnemy(u, List.of(closer, blockedByMe),
                e -> e == blockedByMe,
                e -> e == blockedByMe ? 9.0 : 1.0,
                e -> 0, e -> 0L);
        assertEquals(blockedByMe, picked, "阻挡优先应当在距离之前");
    }

    @Test
    void 自己挡住的敌人_即使不在射程内也能打() {
        // 面朝右的近卫只覆盖 (3,1) 与 (4,1)；敌人被挡在左边 (2,1) —— 射程外。
        OperatorUnit u = new OperatorUnit(guard(), 3, 1, Director.RIGHT);
        Enemy blockedLeft = enemyAt(2, 1);

        assertFalse(u.covers(blockedLeft.col(), blockedLeft.row()), "前提：它确实不在射程内");
        Enemy picked = TargetSelector.pickEnemy(u, List.of(blockedLeft),
                e -> e == blockedLeft, e -> 5.0, e -> 0, e -> 0L);
        assertEquals(blockedLeft, picked,
                "被自己阻挡的敌人必须能被选中（Defender ai.js:133-137）");
    }

    @Test
    void 嘲讽值高的敌人优先于更近的() {
        OperatorUnit u = new OperatorUnit(guard(), 3, 1, Director.DOWN);
        Enemy closer = enemyAt(3, 2);
        Enemy taunting = enemyAt(3, 2);

        Enemy picked = TargetSelector.pickEnemy(u, List.of(closer, taunting),
                e -> false,
                e -> e == taunting ? 9.0 : 1.0,
                e -> e == taunting ? 5 : 0,
                e -> 0L);
        assertEquals(taunting, picked, "嘲讽（②）应当排在距离（④）之前");
    }

    @Test
    void 范围外的敌人不会被选中() {
        OperatorUnit u = new OperatorUnit(guard(), 3, 1, Director.RIGHT);
        Enemy behind = enemyAt(3, 2);        // 在下方，朝右的近卫打不到
        assertNull(TargetSelector.pickEnemy(u, List.of(behind),
                e -> false, e -> 0.0, e -> 0, e -> 0L));
    }

    @Test
    void 敌人优先打最后部署的干员() {
        OperatorUnit first = new OperatorUnit(guard(), 3, 1, Director.RIGHT);
        OperatorUnit second = new OperatorUnit(guard(), 4, 1, Director.RIGHT);
        Enemy e = enemyAt(3, 2);

        OperatorUnit picked = TargetSelector.pickOperator(e, List.of(first, second), u -> false);
        assertEquals(second, picked, "应当打后部署的那个");
    }

    @Test
    void 敌人优先打正挡住它的干员() {
        OperatorUnit early = new OperatorUnit(guard(), 3, 1, Director.RIGHT);
        OperatorUnit blocker = new OperatorUnit(guard(), 4, 1, Director.RIGHT);
        Enemy e = enemyAt(3, 2);

        OperatorUnit picked = TargetSelector.pickOperator(e, List.of(blocker, early),
                u -> u == blocker);
        assertEquals(blocker, picked, "阻挡者优先于后部署");
    }

    // ------------------------------------------------------------------
    // 攻击冷却语义（Defender：没有目标不消耗）
    // ------------------------------------------------------------------

    @Test
    void 没有目标不消耗冷却() {
        OperatorUnit u = new OperatorUnit(guard(), 3, 1, Director.RIGHT);
        // 冷却初值 0 → 立刻"就绪"
        assertTrue(u.tickAttack(1f), "初始应当就绪");
        // 但**没有出手**，所以再次查询仍然就绪
        assertTrue(u.tickAttack(1f), "没出手就不该进入冷却");
        // 真的出手后才进冷却
        u.markAttacked();
        assertFalse(u.tickAttack(0.5f), "出手后 0.5 秒应当还在冷却");
        assertTrue(u.tickAttack(0.6f), "1.1 秒后应当就绪");
    }

    // ------------------------------------------------------------------
    // 伤害（float，保底 5%）
    // ------------------------------------------------------------------

    @Test
    void 术师打高防敌人明显更疼() {
        // 重装防御者：def 150、res 10
        float phys = Combat.damage(200f, 150f, 10, com.yestercar.common.model.DamageType.PHYS);
        float arts = Combat.damage(200f, 150f, 10, com.yestercar.common.model.DamageType.ARTS);
        assertEquals(50f, phys, 1e-4, "物理：200 − 150 = 50");
        assertEquals(180f, arts, 1e-4, "法术：200 × (1 − 10%) = 180");
        assertTrue(arts > phys * 3, "术师对重甲应当有压倒性优势");
    }

    @Test
    void 高法抗的敌人吃法术伤害会打折() {
        // 高阶术师自己 res 40
        float arts = Combat.damage(200f, 0f, 40, com.yestercar.common.model.DamageType.ARTS);
        assertEquals(120f, arts, 1e-4, "200 × (1 − 40%) = 120");
    }

    @Test
    void 伤害是浮点且保底百分之五() {
        assertEquals(50f, Combat.physicalDamage(100f, 50f), 1e-6);
        // 10 攻打 100 防 → 0.5（不是 1）
        assertEquals(0.5f, Combat.physicalDamage(10f, 100f), 1e-6);
        assertEquals(0f, Combat.physicalDamage(0f, 100f), 1e-6);
    }
}
