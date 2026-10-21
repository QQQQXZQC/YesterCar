package com.yestercar.runtime.battle;

import java.util.List;

/**
 * 阻挡系统（**以 Defender 为准**，第 17 轮决定）。
 *
 * <h2>判定用平方半径，不是格子</h2>
 * {@code Defender/server/sim/constants.js:36-37}：
 * <pre>
 *   BLOCK_RADIUS_SQ = { ground: 0.49999037, fly: 0.79995137, device: 0.19998784 }
 *   // 即 ground 半径 0.70709997 格、fly 0.8944、device 0.4472
 * </pre>
 * 敌人只要**中心距**进入这个半径就被抓住 —— 视觉上停在格子边缘，
 * 而不是走到格中心才停（我们上一版按策划 §2.7 用格子判定，本轮推翻）。
 *
 * <h2>扫描范围</h2>
 * {@code Battle.js:_checkBlock} 对**地面敌人只扫正交邻居**（自身 + 上下左右），
 * 因为对角线中心距 ≥ √0.5，已经超出地面半径；只有飞行敌人才扫 3×3。
 * 我们目前没有飞行敌人，所以固定按正交扫描。
 *
 * <h2>多候选：最近者胜</h2>
 * 用严格小于比较平方距离；平手保持扫描顺序（与 Defender 的 [ASSUMED] 注释一致）。
 *
 * <h2>阻挡位按 blockWeight 求和</h2>
 * {@code _blockerFor} 里 {@code used + w <= cap}，其中 {@code used = Σ e.blockWeight}。
 * 上一版写死"每只占 1"，于是权重 3 的敌人也只算 1 —— {@code EnemyType.blockWeight}
 * 解析了却没用上。
 */
final class BlockSystem {

    /** 地面敌人（没有飞行单位，所以只用这一档）。 */
    static final double BLOCK_RADIUS_SQ_GROUND = 0.49999037;

    private BlockSystem() {
    }

    /**
     * 尝试让某个干员接住一个敌人。
     *
     * @param units 场上干员
     * @param ex    敌人世界 x（格，格中心 = col + 0.5）
     * @param ey    敌人世界 y
     * @param col   敌人所在格列（用于限定扫描范围）
     * @param row   敌人所在格行
     * @param weight 该敌人的阻挡权重（{@code EnemyType.blockWeight}）
     * @return 接住它的干员；没人能接返回 {@code null}（敌人穿过）
     */
    static OperatorUnit tryBlock(List<OperatorUnit> units, float ex, float ey,
                                 int col, int row, int weight) {
        if (units == null || units.isEmpty()) {
            return null;
        }
        if (weight <= 0) {
            return null;      // 阻挡权重 0 = 无法被阻挡（如幽灵）
        }
        int w = Math.max(1, weight);
        OperatorUnit best = null;
        double bestD2 = Double.MAX_VALUE;

        // 自身 + 正交四邻
        int[][] cells = {{col, row}, {col - 1, row}, {col + 1, row}, {col, row - 1}, {col, row + 1}};
        for (int[] cell : cells) {
            OperatorUnit u = occupantAt(units, cell[0], cell[1]);
            if (u == null || !u.hasBlockRoom(w)) {
                continue;
            }
            double dx = ex - (cell[0] + 0.5f);
            double dy = ey - (cell[1] + 0.5f);
            double d2 = dx * dx + dy * dy;
            if (d2 < BLOCK_RADIUS_SQ_GROUND && d2 < bestD2) {
                best = u;
                bestD2 = d2;
            }
        }
        if (best != null) {
            best.addBlocking(w);
        }
        return best;
    }

    /**
     * @param units 场上干员
     * @param col   列
     * @param row   行
     * @return 该格上是否**有**干员（不管阻挡位满没满）
     */
    static OperatorUnit occupantAt(List<OperatorUnit> units, int col, int row) {
        if (units == null) {
            return null;
        }
        for (OperatorUnit u : units) {
            if (u.alive() && u.col() == col && u.row() == row) {
                return u;
            }
        }
        return null;
    }
}
