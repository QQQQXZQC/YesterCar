package com.yestercar.campaigneditor;

import com.yestercar.common.model.Wave;
import com.yestercar.common.model.Wave.EnemySpawn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 波次表的**可编辑模型**（纯逻辑，不碰 UI，便于单测）。
 *
 * <p>{@link Wave} / {@link EnemySpawn} 都是不可变 record，直接改字段做不到，
 * 所以这里统一"取出 → 复制 → 改 → 放回"，对外只暴露一组动作方法。
 *
 * <h2>为什么单独一个类</h2>
 * 编辑器是自绘 immediate-mode UI，按钮和绘制混在一起，很难测。
 * 把"改一格数值"这件事从 UI 里抽出来，就能用普通 JUnit 把规则钉死。
 */
public final class WaveEditor {

    /** 一条校验问题。 */
    public record Problem(int waveIndex, String message) {
        @Override
        public String toString() {
            return waveIndex < 0 ? message : ("波次 #" + (waveIndex + 1) + "：" + message);
        }
    }

    /**
     * Boss 级敌人：**整关只能出现 1 个**（所有波次合计）。
     *
     * <p>爱国者、大鲍勃。加新 Boss 时在这里补 id 即可。
     */
    public static final java.util.Set<String> BOSS_IDS =
            java.util.Set.of("big_bob", "patriot");

    /** 当前波次表（可变列表，元素不可变）。 */
    private final List<Wave> waves = new ArrayList<>();

    /**
     * 用已有数据初始化。
     *
     * @param source 关卡里读出来的波次，可为 {@code null}
     */
    public WaveEditor(List<Wave> source) {
        if (source != null) {
            for (Wave w : source) {
                if (w != null) {
                    waves.add(w);
                }
            }
        }
    }

    /**
     * @return 波次表（按时刻升序的只读视图）
     */
    public List<Wave> waves() {
        List<Wave> out = new ArrayList<>(waves);
        out.sort(Comparator.comparingDouble(Wave::time));
        return out;
    }

    /**
     * @return 波次数量
     */
    public int size() {
        return waves.size();
    }

    /**
     * @param index 下标（按显示顺序）
     * @return 该波次；越界返回 {@code null}
     */
    public Wave wave(int index) {
        List<Wave> sorted = waves();
        return index >= 0 && index < sorted.size() ? sorted.get(index) : null;
    }

    /**
     * @return 所有波次的敌人总数
     */
    public int totalCount() {
        int n = 0;
        for (Wave w : waves) {
            n += w.totalCount();
        }
        return n;
    }

    /**
     * 新增一个空波次（默认 0 秒、一组 1 只士兵）。
     *
     * @return 新波次在显示顺序里的下标
     */
    public int addWave() {
        waves.add(new Wave(0f, List.of(new EnemySpawn("soldier", 0, 0, 1, 1f))));
        return Math.max(0, waves().size() - 1);
    }

    /**
     * 删除一个波次。
     *
     * @param index 下标（按显示顺序）
     * @return 真的删掉了返回 {@code true}
     */
    public boolean removeWave(int index) {
        Wave w = wave(index);
        return w != null && waves.remove(w);
    }

    /**
     * 改波次时刻（下限 0）。
     *
     * @param index 下标
     * @param time  新时刻（秒）
     * @return 成功返回 {@code true}
     */
    public boolean setWaveTime(int index, float time) {
        Wave w = wave(index);
        if (w == null) {
            return false;
        }
        return replace(index, new Wave(Math.max(0f, time), w.spawns()));
    }

    /**
     * 给波次加一个出怪组（默认 1 只士兵 / 1 秒）。
     *
     * @param index 波次下标
     * @return 成功返回 {@code true}
     */
    public boolean addSpawn(int index) {
        Wave w = wave(index);
        if (w == null) {
            return false;
        }
        List<EnemySpawn> list = new ArrayList<>(w.spawns());
        list.add(new EnemySpawn("soldier", 0, 0, 1, 1f));
        return replace(index, new Wave(w.time(), list));
    }

    /**
     * 删掉某个出怪组。
     *
     * @param index  波次下标
     * @param spawn  组下标
     * @return 成功返回 {@code true}
     */
    public boolean removeSpawn(int index, int spawn) {
        Wave w = wave(index);
        if (w == null || spawn < 0 || spawn >= w.spawns().size()) {
            return false;
        }
        List<EnemySpawn> list = new ArrayList<>(w.spawns());
        list.remove(spawn);
        return replace(index, new Wave(w.time(), list));
    }

    /**
     * 改某个出怪组的敌人种类。
     *
     * @param index  波次下标
     * @param spawn  组下标
     * @param enemy  新敌人 id
     * @param enemyIds 可选的敌人 id 列表（用于校验存在性）
     * @return 成功返回 {@code true}
     */
    public boolean setSpawnEnemy(int index, int spawn, String enemy, List<String> enemyIds) {
        EnemySpawn s = spawn(index, spawn);
        if (s == null || enemy == null || enemy.isBlank()) {
            return false;
        }
        if (enemyIds != null && !enemyIds.isEmpty() && !enemyIds.contains(enemy)) {
            return false;
        }
        return putSpawn(index, spawn, new EnemySpawn(enemy, s.door(),
                s.targetBlueDoor(), s.count(), s.interval()));
    }

    /**
     * 把某个出怪组的敌人**切到下一个**（下拉框的省事替代）。
     *
     * @param index    波次下标
     * @param spawn    组下标
     * @param enemyIds 可选 id（按显示顺序）
     * @param step     +1 下一个 / -1 上一个
     * @return 切换后的 id；失败返回 {@code null}
     */
    public String cycleSpawnEnemy(int index, int spawn, List<String> enemyIds, int step) {
        EnemySpawn s = spawn(index, spawn);
        if (s == null || enemyIds == null || enemyIds.isEmpty()) {
            return null;
        }
        int cur = enemyIds.indexOf(s.enemy());
        int next = Math.floorMod(cur + step, enemyIds.size());
        String id = enemyIds.get(next);
        return putSpawn(index, spawn, new EnemySpawn(id, s.door(),
                s.targetBlueDoor(), s.count(), s.interval())) ? id : null;
    }

    /**
     * 改红门 / 蓝门下标。
     *
     * @param index  波次下标
     * @param spawn  组下标
     * @param red    红门下标
     * @param blue   蓝门下标
     * @return 成功返回 {@code true}
     */
    public boolean setSpawnDoors(int index, int spawn, int red, int blue) {
        EnemySpawn s = spawn(index, spawn);
        if (s == null) {
            return false;
        }
        return putSpawn(index, spawn, new EnemySpawn(s.enemy(), Math.max(0, red),
                Math.max(0, blue), s.count(), s.interval()));
    }

    /**
     * 调数量（下限 1）。
     *
     * @param index 波次下标
     * @param spawn 组下标
     * @param delta 增减
     * @return 成功返回 {@code true}
     */
    public boolean bumpCount(int index, int spawn, int delta) {
        EnemySpawn s = spawn(index, spawn);
        if (s == null) {
            return false;
        }
        int n = Math.max(1, Math.min(99, s.count() + delta));
        return putSpawn(index, spawn, new EnemySpawn(s.enemy(), s.door(),
                s.targetBlueDoor(), n, s.interval()));
    }

    /**
     * 调出怪间隔（下限 0.1 秒）。
     *
     * @param index 波次下标
     * @param spawn 组下标
     * @param delta 增减（秒）
     * @return 成功返回 {@code true}
     */
    public boolean bumpInterval(int index, int spawn, float delta) {
        EnemySpawn s = spawn(index, spawn);
        if (s == null) {
            return false;
        }
        float v = Math.max(0.1f, Math.min(30f, Math.round((s.interval() + delta) * 10f) / 10f));
        return putSpawn(index, spawn, new EnemySpawn(s.enemy(), s.door(),
                s.targetBlueDoor(), s.count(), v));
    }

    /**
     * 校验整张表。
     *
     * @param redDoors  地图上的红门数量
     * @param blueDoors 地图上的蓝门数量
     * @param enemyIds  可用的敌人 id
     * @return 问题列表；空表示通过
     */
    public List<Problem> validate(int redDoors, int blueDoors, List<String> enemyIds) {
        List<Problem> out = new ArrayList<>();
        List<Wave> sorted = waves();
        if (sorted.isEmpty()) {
            out.add(new Problem(-1, "至少要有一个波次"));
            return out;
        }
        // Boss 整关唯一
        java.util.Map<String, Integer> bossTotals = new java.util.HashMap<>();
        for (Wave w : sorted) {
            for (EnemySpawn s : w.spawns()) {
                if (BOSS_IDS.contains(s.enemy())) {
                    bossTotals.merge(s.enemy(), s.count(), Integer::sum);
                }
            }
        }
        for (java.util.Map.Entry<String, Integer> e : bossTotals.entrySet()) {
            if (e.getValue() > 1) {
                out.add(new Problem(-1, "Boss「" + e.getKey()
                        + "」整关最多 1 个（现在是 " + e.getValue() + " 个）"));
            }
        }

        for (int i = 0; i < sorted.size(); i++) {
            Wave w = sorted.get(i);
            if (w.time() < 0f) {
                out.add(new Problem(i, "时刻不能为负"));
            }
            if (w.spawns().isEmpty()) {
                out.add(new Problem(i, "没有出怪组"));
                continue;
            }
            for (EnemySpawn s : w.spawns()) {
                if (s.count() < 1) {
                    out.add(new Problem(i, "出怪数量必须大于 0"));
                }
                if (s.interval() <= 0f) {
                    out.add(new Problem(i, "出怪间隔必须大于 0"));
                }
                if (redDoors > 0 && (s.door() < 0 || s.door() >= redDoors)) {
                    out.add(new Problem(i, "引用了不存在的红门 #" + s.door()));
                }
                if (blueDoors > 0 && (s.targetBlueDoor() < 0 || s.targetBlueDoor() >= blueDoors)) {
                    out.add(new Problem(i, "引用了不存在的蓝门 #" + s.targetBlueDoor()));
                }
                if (enemyIds != null && !enemyIds.isEmpty() && !enemyIds.contains(s.enemy())) {
                    out.add(new Problem(i, "未知敌人：" + s.enemy()));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 内部：定位与替换
    // ------------------------------------------------------------------

    /** 按显示顺序取组。 */
    private EnemySpawn spawn(int index, int spawnIndex) {
        Wave w = wave(index);
        if (w == null || spawnIndex < 0 || spawnIndex >= w.spawns().size()) {
            return null;
        }
        return w.spawns().get(spawnIndex);
    }

    /** 按显示顺序替换整条波次。 */
    private boolean replace(int index, Wave updated) {
        Wave old = wave(index);
        if (old == null) {
            return false;
        }
        int at = waves.indexOf(old);
        if (at < 0) {
            return false;
        }
        waves.set(at, updated);
        return true;
    }

    /** 替换某一组。 */
    private boolean putSpawn(int index, int spawnIndex, EnemySpawn updated) {
        Wave w = wave(index);
        if (w == null || spawnIndex < 0 || spawnIndex >= w.spawns().size()) {
            return false;
        }
        List<EnemySpawn> list = new ArrayList<>(w.spawns());
        list.set(spawnIndex, updated);
        return replace(index, new Wave(w.time(), list));
    }
}
