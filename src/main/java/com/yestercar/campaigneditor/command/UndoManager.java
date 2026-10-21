package com.yestercar.campaigneditor.command;

import com.yestercar.campaigneditor.model.TileMap;

import java.util.ArrayList;
import java.util.List;

/**
 * 撤销 / 重做的历史栈。
 *
 * <h2>一笔 = 一次「按下 → 抬起」</h2>
 * 调用方在<strong>改动之前</strong>调 {@link #beginStroke()}、改动完成后调 {@link #commit(String)}。
 * 一次拖拽画 20 格算<strong>一笔</strong>，按一次 Ctrl+Z 全部撤销 ——
 * 若按格记历史，用户要按 20 次才能撤销，撤销基本就废了。
 *
 * <pre>
 *   mousePressed  → beginStroke()            记下改动前的状态
 *   mouseDragged  → noteChange(...) × N      随便改多少次
 *   mouseReleased → commit("放置 地面")       推入一条历史
 * </pre>
 *
 * <h2>为什么是「列表 + 指针」而不是两个栈</h2>
 * 两个栈（undo 栈 + redo 栈）的经典写法有个隐蔽的坑：<strong>撤销时必须先拍一张
 * "当前状态"塞进重做栈</strong>，因为撤销栈里存的是"改动之前"，不是"改动之后"。
 * 忘了这一步，重做就会跳回一个更早的、错误的状态。
 *
 * <p>改成一条列表 + 一个指针就没有这个问题：
 *
 * <pre>
 *   states[0]  states[1]  states[2]  states[3]
 *   初始        编辑1后    编辑2后    编辑3后
 *                  ↑ cursor = 2 表示"当前处于编辑2之后"
 *
 *   撤销 = cursor-- 并 restore(states[cursor])
 *   重做 = cursor++ 并 restore(states[cursor])
 *   新编辑 = 砍掉 cursor 之后的全部，再 append
 * </pre>
 *
 * 状态只存一份，不存在"两份状态对不上"的可能。
 *
 * <h2>存整图快照，不存逐格补丁</h2>
 * 每笔存一份 {@link TileMap.Snapshot}。15×15 上限 = 225 格 ≈ 几 KB 一份，
 * 200 笔也就几百 KB，内存不是问题。
 *
 * <p>换来的是<strong>绝不可能漏记字段</strong> —— 逐格补丁方案要同时正确还原
 * 「地形表」「红门列表」「蓝门列表」「id 计数器」四样东西，漏一样就是几周后才暴露的 bug。
 */
public final class UndoManager {

    /** 历史深度上限。见 {@code 具体设计.md} §4.1.0.5 的取舍说明。 */
    public static final int DEFAULT_LIMIT = 200;

    private final TileMap map;
    private final int limit;

    /**
     * 状态序列。{@code states[0]} 是初始状态，{@code states[i]} 是第 i 笔<strong>之后</strong>的状态。
     */
    private final List<State> states = new ArrayList<>();

    /** 当前所处的状态下标；{@code states.get(cursor)} 就是当前状态。 */
    private int cursor;

    /** 正在累积的这一笔的起始状态；不在笔中时为 {@code null}。 */
    private TileMap.Snapshot pending;

    /** 这一笔改了多少格，用来判断"有没有真的改动"。 */
    private int pendingChanges;

    /**
     * 一个历史状态。
     *
     * @param snap  该状态的地图快照
     * @param label 到达该状态所做的事，如 {@code "放置 地面"}；初始状态为 {@code "初始"}
     */
    /**
     * 波次表的快照 / 还原（由编辑器实现）。
     *
     * <p>撤销栈本来只管地图；波次是另一份数据，用这个接口挂上去，
     * 这样"改波次"和"改地形"进的是同一条历史。
     */
    public interface WaveSnapshotter {
        /**
         * @return 当前波次表
         */
        java.util.List<com.yestercar.common.model.Wave> snapshot();

        /**
         * 还原到某一版波次表。
         *
         * @param waves 目标波次表
         */
        void restore(java.util.List<com.yestercar.common.model.Wave> waves);
    }

    /** 波次快照器；{@code null} 表示这个编辑器不管波次。 */
    private WaveSnapshotter waveSnapshotter;

    /**
     * 挂上波次快照器。
     *
     * @param snapshotter 快照器，可为 {@code null}
     */
    public void bindWaves(WaveSnapshotter snapshotter) {
        this.waveSnapshotter = snapshotter;
    }

    /** @return 当前波次的快照；没挂快照器时返回 {@code null} */
    private java.util.List<com.yestercar.common.model.Wave> snapWaves() {
        return waveSnapshotter == null ? null
                : java.util.List.copyOf(waveSnapshotter.snapshot());
    }

    /**
     * 还原波次。
     *
     * @param waves 目标波次；{@code null} 表示这一步没有波次信息
     */
    private void restoreWaves(java.util.List<com.yestercar.common.model.Wave> waves) {
        if (waveSnapshotter != null && waves != null) {
            waveSnapshotter.restore(waves);
        }
    }

    private record State(TileMap.Snapshot snap,
                         java.util.List<com.yestercar.common.model.Wave> waves,
                         String label) {
    }

    /**
     * @param map   要记录历史的地图
     * @param limit 深度上限；{@code <= 0} 时用 {@link #DEFAULT_LIMIT}
     */
    public UndoManager(TileMap map, int limit) {
        this.map = map;
        this.limit = limit > 0 ? limit : DEFAULT_LIMIT;
        this.states.add(new State(map.snapshot(), null, "初始"));
        this.cursor = 0;
    }

    /**
     * @param map 要记录历史的地图
     */
    public UndoManager(TileMap map) {
        this(map, DEFAULT_LIMIT);
    }

    // ------------------------------------------------------------------
    // 记录
    // ------------------------------------------------------------------

    /**
     * 开始一笔。<strong>必须在改动地图之前调用。</strong>
     *
     * <p>重复调用只生效一次（不会把已开始的这一笔冲掉）——
     * 这样调用方可以放心地在每个 {@code mousePressed} 里都调。
     */
    public void beginStroke() {
        if (pending == null) {
            pending = map.snapshot();
            pendingChanges = 0;
        }
    }

    /**
     * 告诉历史栈「这一笔改动了一格」。
     *
     * <p>只用来判断这一笔到底有没有产生变化 —— 没变化就不该留下一条空历史，
     * 否则用户按 Ctrl+Z 会觉得"按了没反应"。
     *
     * @param changed 这一格确实被改了时传 {@code true}
     */
    public void noteChange(boolean changed) {
        if (changed) {
            pendingChanges++;
        }
    }

    /**
     * 提交这一笔。
     *
     * <p>若这一笔没有任何实际改动，<strong>不留历史</strong>，直接返回 {@code false}。
     *
     * @param label 描述，如 {@code "放置 地面"}；用于状态栏提示
     * @return 真的推入了一条历史时返回 {@code true}
     */
    public boolean commit(String label) {
        if (pending == null) {
            return false;
        }
        pending = null;
        int changes = pendingChanges;
        pendingChanges = 0;

        if (changes == 0) {
            return false;   // 空笔：没改动就不进历史
        }

        // 新编辑作废"未来"：砍掉 cursor 之后的状态
        while (states.size() > cursor + 1) {
            states.remove(states.size() - 1);
        }
        states.add(new State(map.snapshot(), snapWaves(), label));
        cursor = states.size() - 1;

        // 超上限：丢掉最旧的状态（保留 states[0] 作为"撤销到头"的落点）
        while (states.size() > limit + 1) {
            states.remove(0);
            cursor--;
        }
        return true;
    }

    /**
     * 丢弃正在累积的这一笔（例如操作被中断）。
     */
    public void abortStroke() {
        pending = null;
        pendingChanges = 0;
    }

    // ------------------------------------------------------------------
    // 撤销 / 重做
    // ------------------------------------------------------------------

    /**
     * @return 还能撤销几步
     */
    public int undoDepth() {
        return cursor;
    }

    /**
     * @return 还能重做几步
     */
    public int redoDepth() {
        return states.size() - 1 - cursor;
    }

    /**
     * @return 是否可以撤销
     */
    public boolean canUndo() {
        return cursor > 0;
    }

    /**
     * @return 是否可以重做
     */
    public boolean canRedo() {
        return cursor < states.size() - 1;
    }

    /**
     * 撤销一步。
     *
     * @return 被撤销掉的那一步的描述；没得撤销时返回 {@code null}
     */
    public String undo() {
        if (!canUndo()) {
            return null;
        }
        String undone = states.get(cursor).label();
        cursor--;
        map.restore(states.get(cursor).snap());
        restoreWaves(states.get(cursor).waves());
        return undone;
    }

    /**
     * 重做一步。
     *
     * @return 被重做的那一步的描述；没得重做时返回 {@code null}
     */
    public String redo() {
        if (!canRedo()) {
            return null;
        }
        cursor++;
        State s = states.get(cursor);
        map.restore(s.snap());
        restoreWaves(s.waves());
        return s.label();
    }

    /**
     * 清空全部历史，把当前地图状态当作新的起点。
     *
     * <p>用于「新建地图」「打开地图」之后 —— 那时候不该还能撤销到上一张图。
     */
    public void clear() {
        states.clear();
        states.add(new State(map.snapshot(), snapWaves(), "初始"));
        cursor = 0;
        pending = null;
        pendingChanges = 0;
    }

    /**
     * @return 一步撤销会撤掉什么；没得撤销时返回 {@code null}
     */
    public String peekUndoLabel() {
        return canUndo() ? states.get(cursor).label() : null;
    }

    /**
     * @return 一步重做会重做什么；没得重做时返回 {@code null}
     */
    public String peekRedoLabel() {
        return canRedo() ? states.get(cursor + 1).label() : null;
    }

    /**
     * @return 一行摘要，如 {@code "可撤销 3 步 / 可重做 1 步"}
     */
    public String summary() {
        return "可撤销 " + undoDepth() + " 步 / 可重做 " + redoDepth() + " 步";
    }

    @Override
    public String toString() {
        return "UndoManager[" + summary() + " 上限=" + limit + "]";
    }
}
