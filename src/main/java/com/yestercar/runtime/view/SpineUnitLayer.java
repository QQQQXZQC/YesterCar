package com.yestercar.runtime.view;

import com.yestercar.common.io.Workspace;
import com.yestercar.common.model.Operator;
import com.yestercar.runtime.battle.BattleDirector;
import com.yestercar.runtime.battle.BattleEvent;
import com.yestercar.runtime.spine.SpineData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import processing.core.PApplet;
import processing.core.PImage;

/**
 * 干员骨骼动画层：把 {@link BattleDirector.UnitView} 映射成 {@link OperatorAnimator}。
 *
 * <h2>硬约束（Defender 的规矩）</h2>
 * 本类**只读**引擎：{@code UnitView} 快照 + {@code BattleEvent} 事件流。
 * 它不调用任何会改变战斗结果的 API，也不改 {@code runtime/battle/} 一行代码。
 *
 * <h2>素材约定</h2>
 * {@code data/spine/operator/<干员id>/} 下放 {@code *.skel} + {@code *.atlas} + {@code *.png}
 * （见 {@code docs/交接文档-动画与数值.md} 第五节）。
 * 缺素材、解析失败、贴图读不到时**不抛异常**，退化成原来的色块占位
 * —— 这样美术可以随时增量交付，不会挡住别人跑游戏。
 *
 * <h2>死亡怎么表现</h2>
 * 引擎在单位阵亡的当 tick 就把它从 {@code deployed()} 里移走，所以场上快照里
 * "人消失了"这一帧看不到尸体。这里靠 {@code BattleEvent.DIE}（带世界坐标）补上：
 * 事件坐标落在某个干员格子上、且该干员确实已经从快照里消失 → 转为「尸体」继续播 Die。
 * 撤退（{@code RETREAT}）不留尸体，直接从列表里移除。
 */
public final class SpineUnitLayer {

    /** 骨骼素材根目录（相对工程根）。 */
    public static final String SPINE_DIR = "data/spine/operator/";

    /** 中文显示名 → 干员 id。 */
    private static Map<String, String> nameToId;

    /** 该场战斗的模型缓存（id → 已解析模型）。 */
    private final Map<String, SpineData> models = new HashMap<>();

    /** 该场战斗的贴图缓存（id → 图集贴图）。 */
    private final Map<String, PImage> textures = new HashMap<>();

    /** 加载失败过的 id，避免每帧重试。 */
    private final java.util.Set<String> failed = new java.util.HashSet<>();

    /** 场上活着的干员：key = "col,row"。 */
    private final Map<String, OperatorAnimator> live = new HashMap<>();

    /** 正在播死亡动画的尸体。 */
    private final List<OperatorAnimator> corpses = new ArrayList<>();

    private final PApplet applet;

    /** 上一帧的"DIE 事件格子"，用于区分阵亡与撤退。 */
    private final java.util.Set<String> diedCellsThisFrame = new java.util.HashSet<>();

    private boolean enabled = true;
    private int loadFailures;

    /**
     * @param applet 宿主画布（用来 {@code loadImage}）
     */
    public SpineUnitLayer(PApplet applet) {
        this.applet = applet;
        ensureIndex();
    }

    /** 建一次"显示名 → id"索引（只建一次，全进程共用）。 */
    private static void ensureIndex() {
        if (nameToId != null) {
            return;
        }
        Map<String, String> map = new HashMap<>();
        for (Operator op : Operator.loadAll().values()) {
            map.put(op.name(), op.id());
        }
        nameToId = map;
    }

    /** @return 骨骼层是否可用（没有任何素材时整体关闭） */
    public boolean enabled() {
        return enabled;
    }

    /** @return 加载失败的干员数（诊断用） */
    public int loadFailures() {
        return loadFailures;
    }

    /** @return 本次战斗已加载的模型数 */
    public int loadedModels() {
        return models.size();
    }

    /**
     * 取某格上的动画器。
     *
     * @param col 列
     * @param row 行
     * @return 动画器；该格没有或素材缺失返回 {@code null}
     */
    public OperatorAnimator at(int col, int row) {
        return live.get(key(col, row));
    }

    /** @return 正在播死亡动画的尸体（调用方负责投影与绘制） */
    public List<OperatorAnimator> corpses() {
        return corpses;
    }

    /**
     * 推进一帧：同步场上单位、消费死亡事件。
     *
     * @param dt    本帧真实间隔（秒）
     * @param units 本帧场上干员快照
     * @param events 本帧事件（**会被清空的事件队列**，调用方传 {@code drainEvents()} 的结果）
     */
    public void update(float dt, List<BattleDirector.UnitView> units,
                       List<BattleEvent> events) {
        if (!enabled) {
            return;
        }
        // ① 先记下本帧 DIE 落在哪些格子（阵亡 vs 撤退 的区别就在这里）
        diedCellsThisFrame.clear();
        if (events != null) {
            for (BattleEvent e : events) {
                if (e.kind() == BattleEvent.Kind.DIE) {
                    diedCellsThisFrame.add(key((int) Math.floor(e.x()),
                            (int) Math.floor(e.y())));
                }
            }
        }

        // ② 同步活着的单位
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (BattleDirector.UnitView u : units) {
            String cell = key(u.col(), u.row());
            seen.add(cell);
            OperatorAnimator anim = live.get(cell);
            if (anim == null) {
                anim = create(u);
                if (anim == null) {
                    continue;                  // 素材缺失 → 调用方画色块
                }
                live.put(cell, anim);
            }
            anim.setCell(u.col(), u.row(), u.facing());
            anim.update(dt, u.attackAge(), true);
        }

        // ③ 消失的单位：DIE 事件对得上 → 尸体；否则（撤退/数据抖动）直接移除
        java.util.Iterator<Map.Entry<String, OperatorAnimator>> it = live.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, OperatorAnimator> entry = it.next();
            if (seen.contains(entry.getKey())) {
                continue;
            }
            OperatorAnimator anim = entry.getValue();
            if (diedCellsThisFrame.contains(entry.getKey())) {
                anim.update(0f, -1f, false);   // 切到 DIE 状态
                corpses.add(anim);
            }
            it.remove();
        }

        // ④ 尸体推进 + 清理
        for (int i = corpses.size() - 1; i >= 0; i--) {
            OperatorAnimator c = corpses.get(i);
            c.update(dt, -1f, false);
            if (c.expired()) {
                corpses.remove(i);
            }
        }
    }

    /**
     * 给一个场上单位建动画器（首次见到时才加载素材）。
     *
     * @param u 单位快照
     * @return 动画器；素材缺失返回 {@code null}
     */
    private OperatorAnimator create(BattleDirector.UnitView u) {
        String id = nameToId.get(u.name());
        if (id == null) {
            return null;
        }
        if (failed.contains(id)) {
            return null;
        }
        SpineData data = models.get(id);
        PImage tex = textures.get(id);
        if (data == null || tex == null) {
            Path dir = Workspace.projectRoot().resolve(SPINE_DIR + id);
            if (!Files.isDirectory(dir)) {
                fail(id, "目录不存在: " + dir);
                return null;
            }
            try {
                data = SpineData.load(dir);
                tex = applet.loadImage(dir.resolve(data.atlas().page()).toString());
            } catch (IOException | RuntimeException ex) {
                fail(id, "解析失败: " + ex);
                return null;
            }
            if (tex == null) {
                fail(id, "贴图读不到: " + data.atlas().page());
                return null;
            }
            models.put(id, data);
            textures.put(id, tex);
        }
        return new OperatorAnimator(id, data, tex, attackIntervalOf(id));
    }

    /**
     * @param id 干员 id
     * @return 该干员的攻击间隔（秒）；查不到返回 0（动画器会退回"只看动画时长"）
     */
    private static float attackIntervalOf(String id) {
        for (Operator op : Operator.loadAll().values()) {
            if (op.id().equals(id)) {
                return op.attackInterval();
            }
        }
        return 0f;
    }

    /**
     * 记一次加载失败（每个 id 只打印一次，避免刷屏）。
     *
     * @param id  干员 id
     * @param why 原因
     */
    private void fail(String id, String why) {
        if (failed.add(id)) {
            loadFailures++;
            System.out.println("[骨骼] 跳过 " + id + " → " + why);
        }
    }

    /**
     * @param col 列
     * @param row 行
     * @return 缓存键
     */
    private static String key(int col, int row) {
        return col + "," + row;
    }
}
