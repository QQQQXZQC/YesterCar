package com.yestercar.campaigneditor;

import com.yestercar.common.model.Wave;
import com.yestercar.common.model.Wave.EnemySpawn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("波次表编辑模型（不依赖 UI）")
class WaveEditorTest {

    private static final List<String> ENEMIES =
            List.of("soldier", "hound_pro", "caster_elite");

    /** 含 Boss 的完整可选列表（Boss 校验用）。 */
    private static final List<String> ALL =
            List.of("soldier", "hound_pro", "caster_elite", "big_bob", "patriot");

    private static WaveEditor withOne() {
        return new WaveEditor(List.of(
                new Wave(5f, List.of(new EnemySpawn("soldier", 0, 0, 3, 1f)))));
    }

    @Test
    void 新增与删除波次() {
        WaveEditor e = new WaveEditor(null);
        assertEquals(0, e.size());

        int idx = e.addWave();
        assertEquals(0, idx);
        assertEquals(1, e.size());
        assertNotNull(e.wave(0));
        assertEquals(1, e.wave(0).totalCount(), "默认一组 1 只");

        assertTrue(e.removeWave(0));
        assertEquals(0, e.size());
    }

    @Test
    void 改时刻不能小于零() {
        WaveEditor e = withOne();
        e.setWaveTime(0, -10f);
        assertEquals(0f, e.wave(0).time(), 1e-6, "负数被夹到 0");
        e.setWaveTime(0, 12.5f);
        assertEquals(12.5f, e.wave(0).time(), 1e-6);
    }

    @Test
    void 列表按时刻排序_但改时刻不会丢数据() {
        WaveEditor e = new WaveEditor(List.of(
                new Wave(30f, List.of(new EnemySpawn("soldier", 0, 0, 1, 1f))),
                new Wave(10f, List.of(new EnemySpawn("hound_pro", 0, 0, 2, 1f)))));
        assertEquals(10f, e.wave(0).time(), 1e-6, "先显示 10 秒那波");
        assertEquals(30f, e.wave(1).time(), 1e-6);

        // 把 10 秒那波挪到 50 秒，它应该排到后面去
        e.setWaveTime(0, 50f);
        assertEquals(30f, e.wave(0).time(), 1e-6);
        assertEquals(50f, e.wave(1).time(), 1e-6);
    }

    @Test
    void 出怪组的增删() {
        WaveEditor e = withOne();
        assertTrue(e.addSpawn(0));
        assertEquals(2, e.wave(0).spawns().size());

        assertTrue(e.removeSpawn(0, 1));
        assertEquals(1, e.wave(0).spawns().size());
        assertFalse(e.removeSpawn(0, 5), "越界不该崩");
    }

    @Test
    void 数量下限是一_间隔下限零点一() {
        WaveEditor e = withOne();
        e.bumpCount(0, 0, -10);
        assertEquals(1, e.wave(0).spawns().get(0).count(), "数量不能降到 0");
        e.bumpInterval(0, 0, -10f);
        assertEquals(0.1f, e.wave(0).spawns().get(0).interval(), 1e-4, "间隔不能降到 0");
    }

    @Test
    void 敌人选择循环切换() {
        WaveEditor e = withOne();
        assertEquals("hound_pro", e.cycleSpawnEnemy(0, 0, ENEMIES, 1));
        assertEquals("caster_elite", e.cycleSpawnEnemy(0, 0, ENEMIES, 1));
        assertEquals("soldier", e.cycleSpawnEnemy(0, 0, ENEMIES, 1), "循环回到第一个");
        assertEquals("caster_elite", e.cycleSpawnEnemy(0, 0, ENEMIES, -1), "反向也能切");
    }

    @Test
    void 未知敌人会被拒绝() {
        WaveEditor e = withOne();
        assertFalse(e.setSpawnEnemy(0, 0, "big_bob", ENEMIES), "不在可选列表里的 id 要拒绝");
        assertTrue(e.setSpawnEnemy(0, 0, "caster_elite", ENEMIES));
        assertEquals("caster_elite", e.wave(0).spawns().get(0).enemy());
    }

    @Test
    void 门下标会夹到非负() {
        WaveEditor e = withOne();
        e.setSpawnDoors(0, 0, -3, -1);
        assertEquals(0, e.wave(0).spawns().get(0).door());
        assertEquals(0, e.wave(0).spawns().get(0).targetBlueDoor());
    }

    @Test
    void 空表校验不通过() {
        List<WaveEditor.Problem> p = new WaveEditor(null).validate(1, 1, ENEMIES);
        assertEquals(1, p.size());
        assertTrue(p.get(0).message().contains("至少"));
    }

    @Test
    void 正常表校验通过() {
        assertTrue(withOne().validate(1, 1, ENEMIES).isEmpty());
    }

    @Test
    void 引用不存在的门会被拦下() {
        WaveEditor e = new WaveEditor(List.of(
                new Wave(0f, List.of(new EnemySpawn("soldier", 3, 0, 1, 1f)))));
        List<WaveEditor.Problem> p = e.validate(1, 1, ENEMIES);
        assertFalse(p.isEmpty());
        assertTrue(p.get(0).message().contains("红门"));
    }

    @Test
    void 未知敌人会被校验拦下() {
        WaveEditor e = new WaveEditor(List.of(
                new Wave(0f, List.of(new EnemySpawn("not_a_real_enemy", 0, 0, 1, 1f)))));
        List<WaveEditor.Problem> p = e.validate(1, 1, ENEMIES);
        assertFalse(p.isEmpty());
        assertTrue(p.get(0).message().contains("未知敌人"));
    }

    @Test
    void 没有出怪组的波次会被拦下() {
        WaveEditor e = new WaveEditor(List.of(new Wave(0f, List.of())));
        List<WaveEditor.Problem> p = e.validate(1, 1, ENEMIES);
        assertFalse(p.isEmpty());
        assertTrue(p.get(0).message().contains("出怪组"));
    }

    @Test
    void Boss整关只能有一个() {
        WaveEditor two = new WaveEditor(java.util.List.of(
                new com.yestercar.common.model.Wave(0f, java.util.List.of(
                        new com.yestercar.common.model.Wave.EnemySpawn("big_bob", 0, 0, 2, 1f)))));
        java.util.List<WaveEditor.Problem> p2 = two.validate(1, 1, ALL);
        assertFalse(p2.isEmpty(), "两个大鲍勃应当被拦下");
        assertTrue(p2.get(0).message().contains("Boss"));

        // 跨波次合计也算
        WaveEditor cross = new WaveEditor(java.util.List.of(
                new com.yestercar.common.model.Wave(0f, java.util.List.of(
                        new com.yestercar.common.model.Wave.EnemySpawn("patriot", 0, 0, 1, 1f))),
                new com.yestercar.common.model.Wave(10f, java.util.List.of(
                        new com.yestercar.common.model.Wave.EnemySpawn("patriot", 0, 0, 1, 1f)))));
        assertFalse(cross.validate(1, 1, ALL).isEmpty(), "两个波次各一个也算超量");

        // 一个就是合法的
        WaveEditor one = new WaveEditor(java.util.List.of(
                new com.yestercar.common.model.Wave(0f, java.util.List.of(
                        new com.yestercar.common.model.Wave.EnemySpawn("patriot", 0, 0, 1, 1f)))));
        assertTrue(one.validate(1, 1, ALL).isEmpty(), "整关一个 Boss 应当合法");
    }

    @Test
    void 越界访问返回空而不是崩() {
        WaveEditor e = withOne();
        assertNull(e.wave(9));
        assertFalse(e.setWaveTime(9, 1f));
        assertNull(e.cycleSpawnEnemy(0, 9, ENEMIES, 1));
    }
}
