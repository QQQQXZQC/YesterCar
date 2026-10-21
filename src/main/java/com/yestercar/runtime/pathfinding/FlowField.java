package com.yestercar.runtime.pathfinding;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * 从一组目标格出发的**带权流场**（Dijkstra）。
 *
 * <p>算出来的是一张"到最近目标要多少代价"的距离表。敌人寻路时不需要各自跑一遍搜索，
 * 只要看自己所在格与四邻的距离，**往距离更小的那格走**即可 ——
 * 这正是策划 §2.11 要的「动态路径规划」：路径不存进关卡文件，
 * 运行时按当前地形算，障碍一放一撤立刻重算。
 *
 * <h2>为什么"多目标 + 四方向"是刻意的</h2>
 * <ul>
 *   <li><b>多目标</b>：蓝门可能有 2 道，敌人走向"更近的那道"是自然结果，
 *       不必为每道门各算一张场再比较。</li>
 *   <li><b>只走上下左右</b>：斜着走会让"是否穿过两个不可通行格的夹角"变成一个需要额外规则的问题
 *       （策划没规定）。四方向没有这个歧义，也符合塔防棋盘的手感。</li>
 * </ul>
 *
 * <h2>不可达</h2>
 * 被围死的格子距离是 {@link #UNREACHABLE}，且它**不会被指路**。
 * 敌人在这种格子上会原地不动 —— 这是"漏怪/卡死"的可见症状，比静默乱走好排查。
 *
 * <p>本类是纯数据 + 纯算法，<strong>不含任何绘制或 PApplet 依赖</strong>，可直接单测。
 */
public final class FlowField {

    /** 不可达格的距离值。用 {@code Float.POSITIVE_INFINITY} 便于直接比大小。 */
    public static final float UNREACHABLE = Float.POSITIVE_INFINITY;

    /** 四方向偏移：上、下、左、右。 */
    private static final int[][] DIRS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    private final int cols;
    private final int rows;

    /** 到最近目标的累计代价。{@code dist[row][col]} */
    private final float[][] dist;

    /**
     * 该格是否可走（地形属性，与"是否可达目标"无关）。
     *
     * <p>必须单独存一份：距离表里"不可达"的格子既可能是墙，也可能是被围死的空地 ——
     * 用距离反推可走性会把后者的视线判定搞错。
     */
    private final boolean[][] walkable;

    /** 是否已有确定值（Dijkstra 的 settled 集合）。 */
    private final boolean[][] settled;

    /**
     * 建一张流场。
     *
     * @param grid    地形代价视图
     * @param targets 目标格（通常是蓝门所在格），至少一个；重复或不可走的会被忽略
     * @throws IllegalArgumentException {@code grid} 或 {@code targets} 为 {@code null}
     */
    public FlowField(CostGrid grid, int[]... targets) {
        if (grid == null) {
            throw new IllegalArgumentException("grid 不能为 null");
        }
        if (targets == null) {
            throw new IllegalArgumentException("targets 不能为 null");
        }
        this.cols = grid.cols();
        this.rows = grid.rows();
        this.dist = new float[rows][cols];
        this.settled = new boolean[rows][cols];
        this.walkable = new boolean[rows][cols];
        for (int row = 0; row < rows; row++) {
            Arrays.fill(dist[row], UNREACHABLE);
            for (int col = 0; col < cols; col++) {
                walkable[row][col] = grid.walkable(col, row);
            }
        }
        build(grid, targets);
    }

    /** 多源 Dijkstra。所有目标距离为 0，一起入队向外扩散。 */
    private void build(CostGrid grid, int[][] targets) {
        // 队列元素打包成 long：[float 距离 | 16 位 col | 16 位 row]
        // 这样比较器只需比 long 本身（高 32 位就是 float 的位模式），
        // 比存对象数组省事，也比每次弹队都做一次 float 转换快。
        PriorityQueue<Long> queue = new PriorityQueue<>();

        for (int[] t : targets) {
            if (t == null || t.length < 2) {
                continue;
            }
            int col = t[0];
            int row = t[1];
            if (!grid.walkable(col, row)) {
                // 目标格本身不可走（比如手工改坏的 JSON）：跳过，而不是抛异常。
                // 结果是这张场可能有大量不可达格，调用方从 isReachable 就能发现。
                continue;
            }
            dist[row][col] = 0f;
            queue.add(pack(0f, col, row));
        }

        while (!queue.isEmpty()) {
            long head = queue.poll();
            int col = unpackCol(head);
            int row = unpackRow(head);
            if (settled[row][col]) {
                continue;   // 同一格可能被多次改进，第一次弹出即最优
            }
            settled[row][col] = true;
            float d = dist[row][col];

            for (int[] dir : DIRS) {
                int nc = col + dir[0];
                int nr = row + dir[1];
                if (!grid.walkable(nc, nr) || settled[nr][nc]) {
                    continue;
                }
                float nd = d + grid.cost(nc, nr);
                if (nd < dist[nr][nc]) {
                    dist[nr][nc] = nd;
                    queue.add(pack(nd, nc, nr));
                }
            }
        }
    }

    /**
     * 打包紧 {@code (距离, col, row)}。
     *
     * <p>高 32 位放距离的位模式 —— 正浮点数的位模式与数值同序，所以
     * 直接按 {@code long} 排序就等于按距离排序，不必写比较器。
     * 低 32 位里 col 在上、row 在下，各占 16 位（65535 的容量，对地图尺寸绰绰有余）。
     */
    private static long pack(float distance, int col, int row) {
        long d = Float.floatToIntBits(distance) & 0xFFFFFFFFL;
        return (d << 32) | ((long) (col & 0xFFFF) << 16) | (row & 0xFFFF);
    }

    private static int unpackCol(long packed) {
        return (int) ((packed >>> 16) & 0xFFFF);
    }

    private static int unpackRow(long packed) {
        return (int) (packed & 0xFFFF);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * @param col 列
     * @param row 行
     * @return 该格到最近目标的代价；越界或不可达返回 {@link #UNREACHABLE}
     */
    public float distanceAt(int col, int row) {
        if (!inBounds(col, row)) {
            return UNREACHABLE;
        }
        return dist[row][col];
    }

    /**
     * @param col 列
     * @param row 行
     * @return 该格能否走到某个目标
     */
    public boolean isReachable(int col, int row) {
        return distanceAt(col, row) != UNREACHABLE;
    }

    /**
     * 求该格的**下一步**该往哪走。
     *
     * <p>在可走的四邻里挑距离最小的那个。已经是目标格时返回 {@code null}
     * （没有下一步 —— 调用方应当判定为"到达"）。
     *
     * @param col 列
     * @param row 行
     * @return {@code [col, row]}；无处可走时返回 {@code null}
     */
    public int[] nextStep(int col, int row) {
        if (!isReachable(col, row) || dist[row][col] == 0f) {
            return null;
        }
        int bestCol = -1;
        int bestRow = -1;
        float best = dist[row][col];
        for (int[] dir : DIRS) {
            int nc = col + dir[0];
            int nr = row + dir[1];
            if (!inBounds(nc, nr)) {
                continue;
            }
            float d = dist[nr][nc];
            if (d < best) {
                best = d;
                bestCol = nc;
                bestRow = nr;
            }
        }
        return bestCol < 0 ? null : new int[]{bestCol, bestRow};
    }

    /**
     * 求该格到目标的<strong>平滑 waypoint 链</strong>（含起点与目标）。
     *
     * <h2>为什么不能逐帧问 {@link #nextStep}</h2>
     * ⚠️ 这是踩过的坑，必须说清楚：
     *
     * <p>「每帧用 {@code floor(位置)} 取当前格，再问下一格」看起来能用，实际会**振荡**：
     * 敌人刚跨过格子边界时 {@code floor} 变了，而下一格算出来是**它刚离开的那一格** ——
     * 于是目标在身后，敌人往回走；再跨过边界，又折回去。
     *
     * <p>症状有两个，都很隐蔽：
     * <ul>
     *   <li>敌人**不在格子正中间**，看起来在格边抖动；</li>
     *   <li>它永远进不了"到达"的判定半径，于是**停在蓝门旁却不消失、也不扣血**。</li>
     * </ul>
     *
     * <p>参考项目 Defender 的做法（{@code server/sim/grid.js} 的 {@code waypoints()} +
     * {@code ai.js} 的 {@code planLeg}）是：<strong>一次算好 waypoint 链，然后逐个走完</strong>。
     * 本方法就是那一步。
     *
     * <h2>平滑：能直线看到就跳过中间格</h2>
     * 先取原始四方向链，再用 Bresenham 视线逐段前跳：
     * 若从当前锚点能"直线看到"更远的某个 waypoint，中间的都可以省掉。
     * 于是走廊上的锯齿被拉直，敌人走的是斜线而不是阶梯。
     *
     * <p>视线判定用的是 {@code hasLineOfSight}，它<strong>不允许贴着墙角穿过去</strong>
     * （对角移动时两个相邻格必须都可走）。不这么做的话，敌人会擦着墙角切进去、看起来穿模。
     *
     * @param col 起点列
     * @param row 起点行
     * @return waypoint 链 {@code [[col,row], ...]}；不可达时返回空数组；起点即目标时只含一格
     */
    public int[][] waypoints(int col, int row) {
        if (!isReachable(col, row)) {
            return new int[0][];
        }
        // 1) 原始四方向链
        java.util.ArrayList<int[]> raw = new java.util.ArrayList<>();
        int c = col;
        int r = row;
        raw.add(new int[]{c, r});
        int guard = cols * rows + 2;
        while (dist[r][c] != 0f && guard-- > 0) {
            int[] next = nextStep(c, r);
            if (next == null) {
                break;      // 理论上不会发生（可达就一定有更小的邻居）
            }
            c = next[0];
            r = next[1];
            raw.add(new int[]{c, r});
        }

        // 2) 视线平滑：从锚点尽量往远跳
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        int anchor = 0;
        out.add(raw.get(0));
        while (anchor < raw.size() - 1) {
            int best = anchor + 1;
            for (int j = raw.size() - 1; j > anchor + 1; j--) {
                if (hasLineOfSight(raw.get(anchor)[0], raw.get(anchor)[1],
                        raw.get(j)[0], raw.get(j)[1])) {
                    best = j;
                    break;
                }
            }
            out.add(raw.get(best));
            anchor = best;
        }
        return out.toArray(new int[0][]);
    }

    /**
     * 两格之间是否**直线可达**。
     *
     * <p>Bresenham 逐格走一遍，任何一格不可走就返回 {@code false}。
     * 对角跨越时额外要求两个相邻格也可走 —— 否则敌人会从两个墙的夹角"切"过去。
     *
     * @param c0 起点列
     * @param r0 起点行
     * @param c1 终点列
     * @param r1 终点行
     * @return 全程可走返回 {@code true}
     */
    public boolean hasLineOfSight(int c0, int r0, int c1, int r1) {
        if (!walkableAt(c0, r0) || !walkableAt(c1, r1)) {
            return false;
        }
        int dc = Math.abs(c1 - c0);
        int dr = Math.abs(r1 - r0);
        int sc = c1 > c0 ? 1 : -1;
        int sr = r1 > r0 ? 1 : -1;
        int err = dc - dr;
        int c = c0;
        int r = r0;
        while (c != c1 || r != r1) {
            int e2 = 2 * err;
            int nc = c;
            int nr = r;
            if (e2 > -dr) {
                err -= dr;
                nc += sc;
            }
            if (e2 < dc) {
                err += dc;
                nr += sr;
            }
            // 对角一步：两侧的直角邻居都必须可走，否则就是从墙角穿过去
            if (nc != c && nr != r
                    && !(walkableAt(nc, r) && walkableAt(c, nr))) {
                return false;
            }
            c = nc;
            r = nr;
            if (!walkableAt(c, r)) {
                return false;
            }
        }
        return true;
    }

    /** @return 该格是否可走（越界即不可走） */
    private boolean walkableAt(int col, int row) {
        return inBounds(col, row) && walkable[row][col];
    }

    /**
     * 沿流场走一条完整路径，用于测试与调试可视化。
     *
     * <p>返回的是**未平滑**的逐格链 —— 与 {@link #waypoints} 的区别是它保证每一格都相邻，
     * 便于测试断言"没有瞬移"。
     *
     * @param col    起点列
     * @param row    起点行
     * @param maxStep 最多走多少步（防死循环），传 {@code cols*rows} 足够
     * @return 从起点到目标（含起点）；不可达时返回空数组
     */
    public int[][] tracePath(int col, int row, int maxStep) {
        if (!isReachable(col, row)) {
            return new int[0][];
        }
        java.util.ArrayList<int[]> path = new java.util.ArrayList<>();
        int c = col;
        int r = row;
        path.add(new int[]{c, r});
        int guard = 0;
        while (dist[r][c] != 0f && guard++ < maxStep) {
            int[] next = nextStep(c, r);
            if (next == null) {
                break;      // 卡住：不该发生，但别死循环
            }
            c = next[0];
            r = next[1];
            path.add(new int[]{c, r});
        }
        return path.toArray(new int[0][]);
    }

    /**
     * @return 可达格数量，用于诊断与测试
     */
    public int reachableCount() {
        int n = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                if (dist[row][col] != UNREACHABLE) {
                    n++;
                }
            }
        }
        return n;
    }

    /** @return 列数 */
    public int cols() {
        return cols;
    }

    /** @return 行数 */
    public int rows() {
        return rows;
    }

    private boolean inBounds(int col, int row) {
        return col >= 0 && col < cols && row >= 0 && row < rows;
    }

    /**
     * 把距离表画成等宽文本，供测试失败时定位与命令行调试。
     *
     * @return 每行一行的文本，不可达格显示 {@code "  ."}
     */
    public String toAsciiArt() {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                float d = dist[row][col];
                sb.append(d == UNREACHABLE ? "  ." : String.format("%3.0f", d));
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }
}
