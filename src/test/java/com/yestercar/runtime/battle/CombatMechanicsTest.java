package com.yestercar.runtime.battle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yestercar.common.model.Operator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 战斗机制测试：覆盖最近新增但此前没有测试的几项。
 *
 * <ul>
 *   <li>治疗（医疗干员回血、不超上限）；</li>
 *   <li>减速（系数、重复命中只刷新时长）；</li>
 *   <li>敌人攻击范围格表（高阶术师 = 狙击同形）；</li>
 *   <li>幽灵：阻挡权重 0 不可阻挡、攻击力 0 不出手；</li>
 *   <li>远程敌人"站定开火"：冷却只推进不消耗。</li>
 * </ul>
 */
class CombatMechanicsTest {

    private static EnemyType type(String id) {
        EnemyType t = EnemyType.loadAll().get(id);
        assertNotNull(t, "敌人表中应当有 " + id);
        return t;
    }

    // ------------------------------------------------------------------
    // 治疗
    // ------------------------------------------------------------------

    @Test
    @DisplayName("治疗不超过生命上限")
    void 治疗不会溢出() {
        Operator medic = Operator.loadAll().get("kaltsit");
        assertNotNull(medic);
        OperatorUnit u = new OperatorUnit(medic, 0, 0, com.yestercar.common.util.Director.RIGHT);
        u.takeDamage(medic.hp() * 0.5f);
        float healed = u.heal(medic.hp() * 10f);
        assertTrue(healed > 0f, "应当真的回血");
        assertEquals(medic.hp(), u.hp(), 0.01f, "回血不应超过上限");
    }

    @Test
    @DisplayName("满血时治疗量为 0")
    void 满血治疗无效() {
        Operator medic = Operator.loadAll().get("kaltsit");
        OperatorUnit u = new OperatorUnit(medic, 0, 0, com.yestercar.common.util.Director.RIGHT);
        assertEquals(0f, u.heal(100f), 0.001f);
    }

    // ------------------------------------------------------------------
    // 减速
    // ------------------------------------------------------------------

    @Test
    @DisplayName("减速生效期间速度打折，到期恢复")
    void 减速系数与到期() {
        Enemy e = new Enemy(type("soldier"), 0f, 0f, 0);
        e.applySlow(10f, 0.5f, 2f);
        assertEquals(0.5f, e.slowFactor(10.5f), 1e-6);
        assertEquals(1f, e.slowFactor(12.5f), 1e-6, "到期后应恢复全速");
    }

    @Test
    @DisplayName("重复命中只刷新时长，不叠加")
    void 减速不叠加() {
        Enemy e = new Enemy(type("soldier"), 0f, 0f, 0);
        e.applySlow(0f, 0.5f, 2f);
        e.applySlow(1f, 0.5f, 2f);
        assertEquals(0.5f, e.slowFactor(2.5f), 1e-6, "仍然只有 0.5 倍");
        assertEquals(1f, e.slowFactor(3.5f), 1e-6, "第二次命中把到期推到 3s");
    }

    // ------------------------------------------------------------------
    // 敌人攻击范围格表
    // ------------------------------------------------------------------

    @Test
    @DisplayName("高阶术师的攻击范围与狙击同形（4×3 含自身）")
    void 高阶术师范围格表() {
        EnemyType.ensureRangesLoaded();
        List<int[]> cells = type("caster_elite").rangeCells();
        assertEquals(12, cells.size(), "应当是 3 行 × 4 列");
        boolean hasSelf = cells.stream().anyMatch(c -> c[0] == 0 && c[1] == 0);
        assertTrue(hasSelf, "必须包含自身格");
        boolean hasBack = cells.stream().anyMatch(c -> c[0] == -1);
        assertFalse(hasBack, "不应打到身后一列");
    }

    @Test
    @DisplayName("近战敌人的攻击范围只有自身一格")
    void 近战敌人只有自身格() {
        EnemyType.ensureRangesLoaded();
        List<int[]> cells = type("soldier").rangeCells();
        assertEquals(1, cells.size());
        assertEquals(0, cells.get(0)[0]);
        assertEquals(0, cells.get(0)[1]);
    }

    // ------------------------------------------------------------------
    // 幽灵：不可阻挡 + 不攻击
    // ------------------------------------------------------------------

    @Test
    @DisplayName("幽灵阻挡权重为 0，不会被阻挡")
    void 幽灵不可阻挡() {
        EnemyType ghost = type("phantom");
        assertEquals(0, ghost.blockWeight());
        OperatorUnit chen = new OperatorUnit(Operator.loadAll().get("chen"),
                2, 2, com.yestercar.common.util.Director.RIGHT);
        assertNull(BlockSystem.tryBlock(List.of(chen), 2.5f, 2.5f, 2, 2, ghost.blockWeight()),
                "权重 0 的单位不该被接住");
    }

    @Test
    @DisplayName("幽灵：官方攻击力 100，但不可阻挡 → 近战永远没有目标，实际不攻击")
    void 幽灵不攻击() {
        EnemyType ghost = type("phantom");
        // PRTS「幽灵」级别0：攻击力 100、攻击方式=不攻击、无法被阻挡。
        // 本工程没有"攻击方式"字段，靠"近战 + 阻挡权重 0（不可阻挡）"
        // 让它在 enemiesAct 里永远拿不到目标 —— 行为与官方一致。
        assertEquals(100, ghost.atk(), "官方攻击力 100");
        assertEquals(0, ghost.blockWeight(), "不可阻挡 → 没有阻挡者可以打");
        assertFalse(ghost.isRanged(), "近战 → 也不会主动找远程目标");
    }

    // ------------------------------------------------------------------
    // 远程"站定开火"：冷却推进不消耗出手
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tickCooldown 只走冷却，不消耗这次攻击")
    void 冷却推进不吞攻击() {
        Enemy e = new Enemy(type("caster_elite"), 0f, 0f, 0);
        float interval = type("caster_elite").attackInterval();
        // 先正常打完一发
        assertTrue(e.tickAttack(interval + 0.01f), "冷却结束后应当能出手");
        // 之后用 tickCooldown 推进到刚好归零
        e.tickCooldown(interval + 0.01f);
        assertTrue(e.attackReady(), "冷却应当已就绪");
        assertTrue(e.tickAttack(0.01f), "就绪后必须能再次出手（不能被 tickCooldown 吞掉）");
    }

    @Test
    @DisplayName("holdUntil 期间 holding 为真，到期后为假")
    void 停步状态() {
        Enemy e = new Enemy(type("caster_elite"), 0f, 0f, 0);
        e.holdUntil(5f);
        assertTrue(e.holding(4.9f));
        assertFalse(e.holding(5.1f));
    }
}
