package com.yestercar.runtime.battle;

import com.yestercar.common.model.Wave;
import com.yestercar.common.model.Wave.EnemySpawn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("出怪调度（读关卡 waves）")
class SpawnManagerTest {

    /** 一步 1/30 秒，与战斗里的定步长一致。 */
    private static final float TICK = 1f / 30f;

    /**
     * 推进若干秒（按固定步长走，模拟真实调用方式）。
     *
     * @param m     调度器
     * @param secs  秒数
     * @return 这段时间里放出的敌人总数
     */
    private static int advance(SpawnManager m, float secs) {
        int n = 0;
        int ticks = Math.round(secs / TICK);
        for (int i = 0; i < ticks; i++) {
            m.update(TICK);
            n += m.drainDue().size();
        }
        return n;
    }

    @Test
    void 按间隔逐只放出() {
        SpawnManager m = new SpawnManager(List.of(
                new Wave(0f, List.of(new EnemySpawn("soldier", 0, 0, 3, 1f)))));

        assertEquals(1, advance(m, 0.05f), "t=0 的波次立刻出第一只");
        assertEquals(1, advance(m, 1.0f), "1 秒后出第二只");
        assertEquals(1, advance(m, 1.0f), "2 秒后出第三只");
        assertEquals(0, advance(m, 1.0f), "放完了不该再出");
        assertEquals(3, m.spawnedCount());
        assertTrue(m.allSpawned());
    }

    @Test
    void 按时刻触发多个波次() {
        SpawnManager m = new SpawnManager(List.of(
                new Wave(0f, List.of(new EnemySpawn("soldier", 0, 0, 1, 1f))),
                new Wave(5f, List.of(new EnemySpawn("caster_elite", 0, 0, 1, 1f)))));

        assertEquals(1, advance(m, 0.1f), "第一波在 t=0");
        assertEquals(0, advance(m, 4.0f), "4 秒时第二波还没到");
        assertEquals(1, advance(m, 1.0f), "t=5 触发第二波");
        assertEquals(2, m.spawnedCount());
        assertTrue(m.allSpawned());
    }

    @Test
    void 同一波里的多组各走各的间隔() {
        SpawnManager m = new SpawnManager(List.of(
                new Wave(1f, List.of(
                        new EnemySpawn("soldier", 0, 0, 2, 1f),
                        new EnemySpawn("hound_pro", 1, 0, 3, 0.5f)))));

        // t=1 才触发：先走 0.5 秒什么都不出，再走 0.5 秒正好到点
        assertEquals(0, advance(m, 0.5f), "还没到 t=1");
        assertEquals(2, advance(m, 0.5f), "到点后两组各出第一只");
        assertEquals(1, advance(m, 0.5f), "0.5 秒后只有猎狗");
        assertEquals(2, advance(m, 1.0f), "1 秒后：猎狗 + 士兵各一只");
        assertEquals(5, m.spawnedCount());
        assertTrue(m.allSpawned());
    }

    @Test
    void 还没放完时不算全出完() {
        SpawnManager m = new SpawnManager(List.of(
                new Wave(0f, List.of(new EnemySpawn("soldier", 0, 0, 5, 2f)))));
        advance(m, 0.1f);
        assertFalse(m.allSpawned(), "还剩 4 只没放");
        assertEquals(5, m.totalCount());
        assertEquals(1, m.spawnedCount());
    }

    @Test
    void 没有波次时退化成默认波次() {
        SpawnManager m = new SpawnManager(List.of());
        assertEquals(1, m.waveCount(), "应当补一个默认波次");
        assertEquals(5, m.totalCount(), "默认 5 只士兵");
    }

    @Test
    void 波次会按时刻排序_数据写乱也不要紧() {
        SpawnManager m = new SpawnManager(List.of(
                new Wave(9f, List.of(new EnemySpawn("big_bob", 0, 0, 1, 1f))),
                new Wave(0f, List.of(new EnemySpawn("soldier", 0, 0, 1, 1f)))));

        assertEquals(1, advance(m, 0.1f), "t=0 那波先出（尽管它在列表里排第二）");
        assertEquals(0, advance(m, 3.0f), "t=9 那波还没到");
    }

    @Test
    void 出怪组保留门下标() {
        SpawnManager m = new SpawnManager(List.of(
                new Wave(0f, List.of(new EnemySpawn("soldier", 2, 1, 1, 1f)))));
        m.update(0.1f);
        List<EnemySpawn> due = m.drainDue();
        assertEquals(1, due.size());
        assertEquals(2, due.get(0).door(), "红门下标要原样传出去");
        assertEquals(1, due.get(0).targetBlueDoor(), "蓝门下标要原样传出去");
        assertEquals("soldier", due.get(0).enemy());
    }
}
