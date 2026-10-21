package com.yestercar.runtime.pathfinding;

/**
 * 流场需要的**代价视图**：一个格子能不能走、走进去要花多少代价。
 *
 * <h2>为什么要有这个接口</h2>
 * {@link FlowField} 是纯算法，不该认识 {@code TileMap}、更不该认识运行时的单位。
 * 中间隔一层接口，好处有两件：
 * <ul>
 *   <li><b>可独立测试</b>：单元测试用一个小数组就能验算 Dijkstra，
 *       不必构造一张合法地图。</li>
 *   <li><b>将来能加动态代价</b>：干员阻挡、地形减速都只是换一个实现，
 *       算法本身一个字不改。</li>
 * </ul>
 *
 * <p>坐标一律 {@code col,row}，左上角 {@code (0,0)}。
 */
public interface CostGrid {

    /**
     * @return 列数
     */
    int cols();

    /**
     * @return 行数
     */
    int rows();

    /**
     * 这一格能不能被敌人走。
     *
     * <p>越界一律视为<strong>不可走</strong> —— 这样算法里就不用到处判边界。
     *
     * @param col 列
     * @param row 行
     * @return 可走返回 {@code true}
     */
    boolean walkable(int col, int row);

    /**
     * 走进这一格的代价（必须为正）。
     *
     * <p>约定：地面 {@code 1.0}；红门/蓝门 {@code 1.0}
     * （门所在格本身要能走，否则敌人出不来、也进不去）。
     * 将来的减速地形返回 {@code >1}，就自动被绕开。
     *
     * <p>不可走的格子返回值无意义，调用方应先问 {@link #walkable}。
     *
     * @param col 列
     * @param row 行
     * @return 代价，恒 {@code > 0}
     */
    float cost(int col, int row);
}
