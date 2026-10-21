package com.yestercar.runtime.battle;

import com.yestercar.common.model.Wave;
import com.yestercar.common.model.Wave.EnemySpawn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 出怪调度：把 {@code campaign.json} 里的 {@code waves} 变成"这一 tick 该放谁"。
 *
 * <h2>为什么需要它</h2>
 * 第 20 轮之前战斗里是写死的"每 2 秒放一只 soldier"，`waves` 数据编辑器存了却没人读。
 * 有了这个类，出怪的时刻、种类、数量、间隔、走哪道门全部来自关卡数据。
 *
 * <h2>时间模型</h2>
 * 只吃 {@code dt}，内部累计 {@link #time()}。它**不依赖帧率** ——
 * {@link BattleDirector} 用固定 1/30 步长调它，所以同一份数据在任何机器上出怪序列一致。
 *
 * <h2>一小段缓冲</h2>
 * 每个出怪组激活后放进 {@code active}，按自己的 {@code interval} 逐只吐出。
 * 一组没放完就一直是 {@code active} 状态，所以 {@link #allSpawned()} 可以
 * 准确回答"这一关的敌人是不是都出来了"。
 */
public final class SpawnManager {

    /** 一个正在放出的出怪组。 */
    private static final class Active {
        final EnemySpawn spawn;
        int remaining;
        float nextAt;

        Active(EnemySpawn spawn, int remaining, float nextAt) {
            this.spawn = spawn;
            this.remaining = remaining;
            this.nextAt = nextAt;
        }
    }

    /** 本关的波次（按时刻升序）。 */
    private final List<Wave> waves = new ArrayList<>();

    /** 还没触发的下一波下标。 */
    private int nextWave;

    /** 正在放出的组。 */
    private final List<Active> active = new ArrayList<>();

    /** 本 tick 吐出的敌人（调用方取走后清空）。 */
    private final List<EnemySpawn> due = new ArrayList<>();

    private float time;
    private int totalCount;
    private int spawnedCount;

    /**
     * @param source 关卡波次；{@code null} 或空时退化成"一波 5 只士兵"
     */
    public SpawnManager(List<Wave> source) {
        List<Wave> list = new ArrayList<>();
        if (source != null) {
            for (Wave w : source) {
                if (w != null) {
                    list.add(w);
                }
            }
        }
        if (list.isEmpty()) {
            // 老关卡没有 waves：给一个默认波次，保持"能玩"而不是"一进来就判定胜利"
            list.add(new Wave(0f, List.of(new EnemySpawn("soldier", 0, 0, 5, 2f))));
            System.out.println("[SpawnManager] waves 为空，使用默认波次（5 只士兵 / 2 秒）");
        }
        list.sort(Comparator.comparingDouble(Wave::time));
        waves.addAll(list);
        for (Wave w : waves) {
            totalCount += w.totalCount();
        }
    }

    /**
     * 推进时间。
     *
     * @param dt 步长（秒）
     */
    public void update(float dt) {
        if (dt <= 0f) {
            return;
        }
        time += dt;

        // ① 触发到点的波次
        while (nextWave < waves.size() && waves.get(nextWave).time() <= time + 1e-6f) {
            Wave w = waves.get(nextWave++);
            for (EnemySpawn sp : w.spawns()) {
                if (sp.count() > 0) {
                    // 第一只立刻出，之后按 interval 出
                    active.add(new Active(sp, sp.count(), time));
                }
            }
        }

        // ② 各组装自己节奏吐
        for (int i = active.size() - 1; i >= 0; i--) {
            Active a = active.get(i);
            while (a.remaining > 0 && a.nextAt <= time + 1e-6f) {
                due.add(a.spawn);
                a.remaining--;
                spawnedCount++;
                a.nextAt += Math.max(0.01f, a.spawn.interval());
            }
            if (a.remaining <= 0) {
                active.remove(i);
            }
        }
    }

    /**
     * 取走本 tick 该放的敌人（取完清空）。
     *
     * @return 每个元素代表"放一只"，含门下标与敌人 id
     */
    public List<EnemySpawn> drainDue() {
        if (due.isEmpty()) {
            return List.of();
        }
        List<EnemySpawn> out = List.copyOf(due);
        due.clear();
        return out;
    }

    /**
     * @return 本关计划放出的敌人总数
     */
    public int totalCount() {
        return totalCount;
    }

    /**
     * @return 已经放出的数量
     */
    public int spawnedCount() {
        return spawnedCount;
    }

    /**
     * @return 是不是"全部波次都已触发且没有组还在吐"
     */
    public boolean allSpawned() {
        return nextWave >= waves.size() && active.isEmpty();
    }

    /**
     * @return 已经过去的关卡时间（秒）
     */
    public float time() {
        return time;
    }

    /**
     * @return 波次数量（HUD/调试用）
     */
    public int waveCount() {
        return waves.size();
    }
}
