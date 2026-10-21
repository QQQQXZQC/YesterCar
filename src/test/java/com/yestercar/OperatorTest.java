package com.yestercar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yestercar.common.model.CellOffset;
import com.yestercar.common.model.DeployType;
import com.yestercar.common.model.Operator;
import com.yestercar.common.util.Director;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code data/operators.json} 的契约测试（策划方案 §3.3）。
 *
 * <h2>这些测试防的是什么</h2>
 * 手改 JSON 是常态，而攻击范围那四个坐标数组最容易写错一个数。
 * 一旦写错，战斗里表现为"干员打不到本来该打的怪"，却不会报任何错。
 * 所以这里把文档承诺的数值**逐条钉死**。
 */
class OperatorTest {

    @Test
    void 能读到二十九个干员() {
        Map<String, Operator> all = Operator.loadAll();
        assertEquals(29, all.size(), "operators.json 现在有 29 个干员");
        assertTrue(all.containsKey("chen"));
        assertTrue(all.containsKey("amiya"));
    }

    @Test
    void 每个干员都有职业与两张图() {
        Map<String, Operator> all = Operator.loadAll();
        for (Operator o : all.values()) {
            assertTrue(!o.opClass().isBlank(), o.id() + " 缺 class");
            assertTrue(o.hasPortrait(), o.id() + " 缺立绘");
            assertNotNull(o.illustration(), o.id() + " 缺全身图");
        }
    }

    @Test
    void 职业分布与素材一致() {
        java.util.Map<String, Integer> byClass = new java.util.TreeMap<>();
        for (Operator o : Operator.loadAll().values()) {
            byClass.merge(o.opClass(), 1, Integer::sum);
        }
        assertEquals(7, byClass.size(), "应当有 7 个职业，实际 " + byClass);
        assertEquals(5, (int) byClass.get("Guard"), "近卫 5 人（含玛恩纳）");
    }

    @Test
    void 费用覆盖了从低到高的区间() {
        Map<String, Operator> all = Operator.loadAll();
        int min = all.values().stream().mapToInt(Operator::cost).min().orElse(0);
        int max = all.values().stream().mapToInt(Operator::cost).max().orElse(0);
        assertTrue(min <= 8, "最低费用应当 ≤ 8，实际 " + min);
        assertTrue(max >= 20, "最高费用应当 ≥ 20，实际 " + max);
    }

    @Test
    void 近卫范围是自身加身前一格() {
        Operator guard = Operator.loadAll().get("chen");
        for (Director d : Director.values()) {
            Set<CellOffset> r = guard.rangeFor(d);
            assertEquals(2, r.size(), d + " 方向应当是 2 格");
            assertTrue(r.contains(CellOffset.self()), d + " 必须包含自身格");
            assertTrue(r.contains(new CellOffset(d.dx(), d.dy())),
                    d + " 必须包含身前一格");
        }
    }

    @Test
    void 狙击范围是前后各三格加两侧() {
        Operator sniper = Operator.loadAll().get("kroos");
        for (Director d : Director.values()) {
            assertEquals(12, sniper.rangeFor(d).size(), d + " 方向应当是 12 格");
            assertTrue(sniper.rangeFor(d).contains(CellOffset.self()));
            // 朝右时应当在 (3,0) 与 (0,1) 上
            if (d == Director.RIGHT) {
                assertTrue(sniper.covers(d, 3, 0));
                assertTrue(sniper.covers(d, 0, 1));
                assertFalse(sniper.covers(d, 4, 0), "第 4 格不该在范围内");
            }
        }
    }

    /**
     * 四个朝向必须互为旋转关系。
     *
     * <p>策划 §3.3 说范围以「朝右」为基准书写、其余靠旋转得到。
     * 这条测试把 JSON 里写全的四个朝向与"旋转出来"的结果对比 ——
     * 手改时写错一个坐标会立刻失败，而不是等到战斗里打不到怪才发现。
     */
    @Test
    void 四个朝向互为旋转() {
        for (Operator op : Operator.loadAll().values()) {
            Set<CellOffset> right = op.rangeFor(Director.RIGHT);
            for (Director d : Director.values()) {
                Set<CellOffset> expected = new LinkedHashSet<>();
                for (CellOffset c : right) {
                    int[] r = d.rotate(c.dx(), c.dy());
                    expected.add(new CellOffset(r[0], r[1]));
                }
                assertEquals(expected, op.rangeFor(d),
                        op.name() + " 的 " + d + " 方向与「朝右」旋转结果不一致");
            }
        }
    }

    @Test
    void 未知id退回兜底而不是崩() {
        Map<String, Operator> all = Operator.loadAll();
        assertSame(Operator.FALLBACK, Operator.resolve(all, "不存在的干员"));
        assertSame(Operator.FALLBACK, Operator.resolve(null, "chen"));
        assertSame(Operator.FALLBACK, Operator.resolve(all, null));
    }

    @Test
    void 兜底干员自身是合法的() {
        assertFalse(Operator.FALLBACK.id().isEmpty());
        assertTrue(Operator.FALLBACK.rangeFor(Director.UP).contains(CellOffset.self()));
    }

    @Test
    void 部署类型解析容错() {
        assertEquals(DeployType.HIGH, DeployType.parse("HIGH"));
        assertEquals(DeployType.HIGH, DeployType.parse(" high "));
        assertEquals(DeployType.GROUND, DeployType.parse(null));
        assertEquals(DeployType.GROUND, DeployType.parse("不知道写什么"));
    }

    @Test
    void 范围不可被外部改动() {
        Operator guard = Operator.loadAll().get("chen");
        Set<CellOffset> r = guard.rangeFor(Director.RIGHT);
        try {
            r.add(new CellOffset(9, 9));
            // 能加进去说明是可变集合 —— 那外部就能悄悄改掉干员定义
            throw new AssertionError("rangeFor 返回的集合必须是不可变的");
        } catch (UnsupportedOperationException expected) {
            // 正常
        }
    }
}
