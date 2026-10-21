package com.yestercar.runtime.pathfinding;

import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.model.TileType;

/**
 * 把编辑器的 {@link TileMap} 适配成流场要的 {@link CostGrid}。
 *
 * <p>这一层是**唯一**把「编辑器数据」和「运行时寻路」连起来的地方。
 * {@link FlowField} 因此不必认识 {@code TileMap}，
 * 而运行时也不必知道地形是怎么画出来的。
 *
 * <h2>代价约定</h2>
 * <table border="1">
 *   <caption>地形与敌人寻路代价</caption>
 *   <tr><th>地形</th><th>可走</th><th>代价</th><th>理由</th></tr>
 *   <tr><td>地面 GROUND</td><td>✅</td><td>1</td><td>基准</td></tr>
 *   <tr><td>红门 RED_DOOR</td><td>✅</td><td>1</td><td>敌人从这里出生，出不来就没法玩</td></tr>
 *   <tr><td>蓝门 BLUE_DOOR</td><td>✅</td><td>1</td><td>这里是目标，进不去就到不了</td></tr>
 *   <tr><td>高台 HIGH</td><td>❌</td><td>—</td><td>只能放远程干员，敌人上不去</td></tr>
 *   <tr><td>不可通行 IMPASSABLE</td><td>❌</td><td>—</td><td>墙</td></tr>
 *   <tr><td>未放置 EMPTY</td><td>❌</td><td>—</td><td>没画的地方不该让敌人通过</td></tr>
 * </table>
 *
 * <p>可走性直接复用 {@link TileType#isWalkable()} —— 那是数据层已经定义好的规则，
 * 在这里重写一遍就等于有两个真相，改一处忘另一处。
 *
 * <p>⚠️ 目前所有可走地形代价都是 1，所以流场算出来的其实是**步数**。
 * 留着代价这个维度是为了将来加「减速地形」时不用改算法。
 */
public final class TileMapGrid implements CostGrid {

    private final TileMap map;

    /**
     * @param map 地图
     * @throws IllegalArgumentException {@code map} 为 {@code null}
     */
    public TileMapGrid(TileMap map) {
        if (map == null) {
            throw new IllegalArgumentException("map 不能为 null");
        }
        this.map = map;
    }

    @Override
    public int cols() {
        return map.cols();
    }

    @Override
    public int rows() {
        return map.rows();
    }

    @Override
    public boolean walkable(int col, int row) {
        if (col < 0 || col >= map.cols() || row < 0 || row >= map.rows()) {
            return false;
        }
        return map.tileAt(col, row).isWalkable();
    }

    @Override
    public float cost(int col, int row) {
        // 目前全部为 1（见类注释）。将来加慢地形时改这里，算法不用动。
        return 1f;
    }
}
