package com.yestercar.runtime.battle;

import com.yestercar.campaigneditor.model.Door;
import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.runtime.pathfinding.FlowField;
import com.yestercar.runtime.pathfinding.TileMapGrid;

import java.util.ArrayList;
import java.util.List;

/**
 * 一场战斗的**纯逻辑**状态机：出怪、沿流场移动、到蓝门扣血。
 *
 * <h2>为什么不做单位碰撞、不做阻挡</h2>
 * 策划 §2.1 只要求「怪物与蓝门中心距离 < 0.5 格 → 怪物消失，蓝门扣 1 点生命」。
 * 单位之间互相阻挡属于 P4.4，这里**故意不做** ——
 * 先把「出怪 → 沿路走 → 到门扣血」这条最简链路跑通，
 * 才看得出流场本身对不对。加了碰撞就分不清"走得不对"是流场错还是碰撞错。
 *
 * <h2>每道蓝门一张流场</h2>
 * 出怪组指定 {@code targetBlueDoor}，所以按蓝门 ID 各建一张场。
 * 这样"不同波次打不同门"是自然支持的，也不需要运行时比较哪张场更近。
 *
 * <p>本类<strong>不依赖 Processing</strong>，可脱离窗口单测 ——
 * 这是它最重要的性质：整条玩法链路能在毫秒级的单元测试里验证，
 * 不必开窗口、不必等渲染。
 */
public final class BattleScene {

    /**
     * 判定"已经进入蓝门"的距离阈值（格）。
     *
     * <p>⚠️ **必须很小**：这里只是给"刚好踩到门格中心"留的浮点容差。
     * 早先写成 {@code 0.5}（半个格子），于是敌人**离门还有半格**就被判定成漏怪、
     * 门当场扣血、敌人凭空消失 —— 画面上就是"还没走到蓝门，生命值先掉了"。
     *
     * <p>真正保证"到门才扣血"的是移动本身：waypoint 链的最后一格就是蓝门格，
     * 走完最后一段时敌人会被吸附到门格中心（距离 ≈ 0），所以 0.05 足够；
     * 取 0.2 是给块状遮挡/浮点误差留一点余量，视觉上门格半径约 0.5 格，
     * 0.2 时敌人已经压在门上了。
     */
    public static final float ARRIVE_DISTANCE = 0.2f;

    private final TileMap map;

    /** 每道蓝门一张流场，key = 蓝门 id。 */
    private final List<FlowField> fieldsByDoor = new ArrayList<>();

    /** 蓝门 id → 它在 {@link #fieldsByDoor} 里的下标。 */
    private final List<Integer> doorIds = new ArrayList<>();

    private final List<Enemy> enemies = new ArrayList<>();

    /** 已经跑过的逻辑时间（秒）。 */
    private float time;

    /** 漏怪总数（到达蓝门而消失的敌人个数）。 */
    private int leaked;

    /** 全局共享的蓝门生命（所有蓝门共用一条命，不再是每门独立）。 */
    /** 已消灭的敌人数（漏怪不算）。 */
    private int killedCount;

    /**
     * 本帧刚刚"漏出蓝门"的敌人（按对象身份）。
     *
     * <p>漏怪与阵亡在引擎里都是 {@code kill()}，统一清理时无法区分，
     * 靠这张表把漏怪从击杀数里排除。
     */
    private final java.util.Set<Enemy> leakedThisFrame =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * @return 已消灭的敌人数
     */
    public int killedCount() {
        return killedCount;
    }

    private int blueHp = -1;

    /** 全局蓝门生命上限。 */
    private int blueMaxHp = -1;

    /** 本关生命值（来自关卡参数，默认 3）。 */
    private int life = 3;

    /**
     * @param map 地图
     * @throws IllegalArgumentException {@code map} 为 {@code null}
     */
    public BattleScene(TileMap map) {
        if (map == null) {
            throw new IllegalArgumentException("map 不能为 null");
        }
        this.map = map;
        rebuildFields();
    }

    /**
     * 重建所有流场，并让场上敌人重新规划路径。
     *
     * <p>地形或门变动后必须调用 —— 这正是策划 §2.11「动态路径规划」的要求：
     * 路径不存进关卡文件，每次地形变了重算。
     * P2.4 放/撤障碍时就会调它。
     *
     * <p>⚠️ 只重建流场是不够的：敌人手里还攥着**按旧地形算出来的 waypoint 链**，
     * 那条链可能正好穿过新放下的障碍。所以要一并作废。
     */
    public void rebuildFields() {
        fieldsByDoor.clear();
        doorIds.clear();
        TileMapGrid grid = new TileMapGrid(map);
        for (Door door : map.blueDoors()) {
            // 只以**蓝门所在格**为目标。敌人走到门心附近就算到达。
            fieldsByDoor.add(new FlowField(grid, new int[]{door.col(), door.row()}));
            doorIds.add(door.id());
        }
        for (Enemy e : enemies) {
            e.invalidatePath();
        }
    }

    /**
     * 放一个敌人到某道红门上。
     *
     * @param type        敌人类型
     * @param redDoorId   出发的红门 id
     * @param blueDoorId  目标蓝门 id
     * @return 放下的敌人；红门或蓝门不存在时返回 {@code null}
     */
    public Enemy spawn(EnemyType type, int redDoorId, int blueDoorId) {
        Door red = map.redDoorById(redDoorId);
        Door blue = map.blueDoorById(blueDoorId);
        if (red == null || blue == null || type == null) {
            return null;
        }
        Enemy e = new Enemy(type, red.col() + 0.5f, red.row() + 0.5f, blueDoorId);
        // 固定路线（目前只有爱国者）：出生后停顿 wait 秒，再沿手写航点走
        EnemyPaths.Path fixed = EnemyPaths.of(type.id());
        if (fixed != null && !fixed.cells().isEmpty()) {
            int[][] wps = new int[fixed.cells().size()][2];
            for (int i = 0; i < wps.length; i++) {
                wps[i][0] = fixed.cells().get(i)[0];
                wps[i][1] = fixed.cells().get(i)[1];
            }
            e.setWaypoints(wps);
            if (fixed.stopAt() != null && fixed.delay() > 0f) {
                e.setStopAt(fixed.stopAt()[0], fixed.stopAt()[1], fixed.delay());
            }
        }
        enemies.add(e);
        return e;
    }

    /**
     * 推进 {@code dt} 秒。
     *
     * <p>顺序：先移动，再判定到达。反过来会让"这一步正好走到门里"的敌人多活一帧。
     *
     * @param dt 时间步长（秒）
     */
    public void update(float dt) {
        if (dt <= 0f) {
            return;
        }
        time += dt;
        for (Enemy e : enemies) {
            if (!e.alive()) {
                continue;
            }
            // 被干员挡住时**不移动**，原地攻击（策划 §2.7 / §2.9）
            if (e.blocked()) {
                continue;
            }
            // 远程敌人出手后先原地停住，等冷却结束再继续走
            if (e.holding(time)) {
                continue;
            }
            moveEnemy(e, dt);
            e.maybeStop(e.col(), e.row(), time);   // 固定路线：到指定格后停顿
            checkArrival(e);
        }
        // 移除已经消失的（到达或死亡）。
        // ⚠️ 只有**被打死**的才算击杀：漏怪走的也是 kill()（alive=false），
        //    所以要用"这一帧刚漏掉的那批"排除掉 —— 否则击杀数会把漏怪也加进去
        //    （实测：生命扣光时"击杀 4/5"里混着 3 只漏怪，数字对不上）。
        for (int i = enemies.size() - 1; i >= 0; i--) {
            Enemy e = enemies.get(i);
            if (!e.alive()) {
                if (!leakedThisFrame.remove(e)) {
                    killedCount++;
                }
                enemies.remove(i);
            }
        }
        leakedThisFrame.clear();
    }

    /**
     * 沿 waypoint 链走一步。
     *
     * <p>⚠️ <strong>必须沿一条算好的链走，不能每帧问"下一格是哪"</strong> ——
     * 后者会在格子边界振荡，症状是敌人停在蓝门旁不消失、也不扣血。
     * 完整的成因与解法见 {@link FlowField#waypoints}。
     *
     * <p>移动时把 {@code dist} 当作"这一帧还能走多远"一路扣下去，
     * 所以一帧跨过多个 waypoint 也不会丢距离、不会在拐角处卡顿。
     */
    private void moveEnemy(Enemy e, float dt) {
        FlowField field = fieldFor(e.targetBlueDoorId());
        if (field == null) {
            return;     // 目标蓝门被删了：原地不动，不崩
        }
        if (e.waypoints() == null) {
            e.setWaypoints(field.waypoints(e.col(), e.row()));
        }
        int[][] wps = e.waypoints();
        if (wps.length == 0) {
            return;     // 不可达：原地不动
        }

        // 减速：辅助命中后移速打折（只影响移动，不影响攻击）
        float budget = e.type().speed() * e.slowFactor(time) * dt;
        int index = e.waypointIndex();
        while (budget > 1e-6f && index < wps.length) {
            // waypoint 存的是格子下标，世界坐标要取格子中心 (+0.5)
            float tx = wps[index][0] + 0.5f;
            float ty = wps[index][1] + 0.5f;
            float dx = tx - e.x();
            float dy = ty - e.y();
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d <= budget) {
                // 刚好走到（或越过）这个 waypoint：吸附到它的中心，余量留给下一段
                e.setPosition(tx, ty);
                budget -= d;
                index++;
            } else {
                // 朝向跟着移动方向走（远程敌人按朝向镜像攻击范围）
                if (Math.abs(dx) > 1e-4f || Math.abs(dy) > 1e-4f) {
                    e.setFacing(Math.abs(dx) >= Math.abs(dy)
                            ? (dx > 0f ? com.yestercar.common.util.Director.RIGHT
                                       : com.yestercar.common.util.Director.LEFT)
                            : (dy > 0f ? com.yestercar.common.util.Director.DOWN
                                       : com.yestercar.common.util.Director.UP));
                }
                e.setPosition(e.x() + dx / d * budget, e.y() + dy / d * budget);
                budget = 0f;
            }
        }
        e.setWaypointIndex(index);
    }

    /** 到达蓝门 → 敌人消失，门扣 1 血。 */
    private void checkArrival(Enemy e) {
        Door blue = map.blueDoorById(e.targetBlueDoorId());
        if (blue == null) {
            return;
        }
        float cx = blue.col() + 0.5f;
        float cy = blue.row() + 0.5f;
        float dx = e.x() - cx;
        float dy = e.y() - cy;
        if ((float) Math.sqrt(dx * dx + dy * dy) < ARRIVE_DISTANCE) {
            e.kill();
            leakedThisFrame.add(e);      // 让统一清理那一步知道"这只是漏出去的，不算击杀"
            if (blueHp < 0) {
                initBlueHp();
            }
            blueHp = Math.max(0, blueHp - 1);
            leaked++;
        }
    }

    /** 初始化全局蓝门生命（取地图上各蓝门血量之和，保留原关卡的容错预算）。 */
    private void initBlueHp() {
        // 全局生命就是"一条命"，每关默认 3 点（关卡参数里可改）
        blueMaxHp = life;
        blueHp = life;
    }

    /**
     * 设置本关生命值。
     *
     * @param newLife 生命（≥1）
     */
    public void setLife(int newLife) {
        this.life = Math.max(1, newLife);
        this.blueHp = -1;
        this.blueMaxHp = -1;
    }

    /**
     * @return 全局剩余生命
     */
    public int blueHp() {
        if (blueHp < 0) {
            initBlueHp();
        }
        return blueHp;
    }

    /**
     * @return 全局生命上限
     */
    public int blueMaxHp() {
        if (blueMaxHp < 0) {
            initBlueHp();
        }
        return blueMaxHp;
    }

    /**
     * 找出某道蓝门的流场。
     *
     * @param blueDoorId 蓝门 id
     * @return 流场；该门不存在时返回 {@code null}
     */
    public FlowField fieldFor(int blueDoorId) {
        int idx = doorIds.indexOf(blueDoorId);
        return idx < 0 ? null : fieldsByDoor.get(idx);
    }

    // ------------------------------------------------------------------
    // 只读视图
    // ------------------------------------------------------------------

    /** @return 场上还活着的敌人（不可变副本） */
    public List<Enemy> enemies() {
        return List.copyOf(enemies);
    }

    /** @return 地图 */
    public TileMap map() {
        return map;
    }

    /** @return 已经跑过的逻辑时间（秒） */
    public float time() {
        return time;
    }

    /** @return 漏怪总数 */
    public int leaked() {
        return leaked;
    }

    /**
     * @return 所有蓝门是否都已失守（生命归零）
     */
    public boolean allDoorsLost() {
        if (map.blueDoors().isEmpty()) {
            return true;
        }
        return blueHp() <= 0;
    }

    /**
     * 状态行，供运行时窗口显示与测试断言。
     *
     * @return 一行摘要
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("t=%.1fs  敌人 %d  漏怪 %d  蓝门 ", time, enemies.size(), leaked));
        for (Door d : map.blueDoors()) {
            sb.append('#').append(d.id()).append(':').append(d.hp()).append(' ');
        }
        return sb.toString().trim();
    }
}
