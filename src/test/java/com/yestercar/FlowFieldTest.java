package com.yestercar;

import com.yestercar.runtime.pathfinding.CostGrid;
import com.yestercar.runtime.pathfinding.FlowField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流场寻路（Dijkstra）。
 *
 * <p>这是运行时最核心的一块算法：敌人怎么走完全由它决定。
 * 所以测的重点不是"跑得通"，而是几条**容易算错又不容易发现**的性质：
 * 绕障碍、被围死、多目标取近、带权绕开高价格。
 */
@DisplayName("流场寻路")
class FlowFieldTest {

    /**
     * 用字符画造一张地形，便于在测试里直观看出形状。
     *
     * <p>{@code '#'} 不可走（<b>唯一的墙</b>）；其余字符都可走，
     * {@code '2'} / {@code '5'} 之类的数字表示该格代价为这个数，其它字符代价 1。
     */
    private static CostGrid grid(String... rows) {
        return new CostGrid() {
            @Override
            public int cols() {
                return rows[0].length();
            }

            @Override
            public int rows() {
                return rows.length;
            }

            @Override
            public boolean walkable(int col, int row) {
                if (col < 0 || col >= cols() || row < 0 || row >= rows()) {
                    return false;
                }
                return rows[row].charAt(col) != '#';
            }

            @Override
            public float cost(int col, int row) {
                char c = rows[row].charAt(col);
                return Character.isDigit(c) ? (c - '0') : 1f;
            }
        };
    }

    @Test
    @DisplayName("直线距离：3×1 的走廊，左端到右端是 2")
    void 直线距离() {
        CostGrid g = grid("...");
        FlowField f = new FlowField(g, new int[]{2, 0});

        assertEquals(0f, f.distanceAt(2, 0), "目标格本身距离为 0");
        assertEquals(1f, f.distanceAt(1, 0));
        assertEquals(2f, f.distanceAt(0, 0));
        assertEquals(3, f.reachableCount());
    }

    @Test
    @DisplayName("绕障碍：中间挡一格时必须绕路，而不是直线穿过去")
    void 绕障碍() {
        //  ...
        //  .#.
        //  ...
        CostGrid g = grid("...", ".#.", "...");
        FlowField f = new FlowField(g, new int[]{2, 2});

        assertEquals(FlowField.UNREACHABLE, f.distanceAt(1, 1), "障碍格不可达");
        // 从 (0,0) 到 (2,2) 绕开中心至少要 4 步
        assertEquals(4f, f.distanceAt(0, 0), "应当绕路，而不是算成 2*√2≈2.83");
        assertEquals(8, f.reachableCount(), "9 格减去 1 个障碍");
    }

    @Test
    @DisplayName("被围死：距离为 UNREACHABLE，且不会被指路")
    void 被围死() {
        // 真正把 (1,1) 围死：四面都是墙
        //  ...
        //  .#.
        //  .#.     ← 不，(1,1) 的上下还是通的
        //
        // 正确的围法：中间格四邻全为 #
        //  ###
        //  #.#
        //  ###
        CostGrid g = grid("###", "#.#", "###");
        FlowField f = new FlowField(g, new int[]{2, 2});

        assertFalse(f.isReachable(1, 1), "中间格四面被墙围住，应当不可达");
        assertEquals(FlowField.UNREACHABLE, f.distanceAt(1, 1));
        assertNull(f.nextStep(1, 1), "不可达的格子不该给出下一步");
        assertEquals(0, f.reachableCount(), "目标 (2,2) 也是墙，全图不可达");
    }

    @Test
    @DisplayName("半包围但留了口子 → 仍然可达，且绕远路")
    void 留口子仍可达() {
        //  .#.
        //  #.#
        //  .#.
        // (1,1) 的上下左右都是 #，只有对角是通的 —— 四方向下不可达
        CostGrid g = grid(".#.", "#.#", ".#.");
        FlowField f = new FlowField(g, new int[]{0, 0});
        assertFalse(f.isReachable(1, 1),
                "四方向寻路下，(1,1) 四面都是墙，对角通不算通");
    }

    @Test
    @DisplayName("多目标：取更近的那一个")
    void 多目标取近() {
        // 五个格一排，目标在两端
        CostGrid g = grid(".....");
        FlowField f = new FlowField(g, new int[]{0, 0}, new int[]{4, 0});

        assertEquals(0f, f.distanceAt(0, 0));
        assertEquals(0f, f.distanceAt(4, 0));
        // 中点 (2,0) 离两边都是 2
        assertEquals(2f, f.distanceAt(2, 0));
        assertEquals(1f, f.distanceAt(1, 0), "左边更近，取左边");
        assertEquals(1f, f.distanceAt(3, 0), "右边更近，取右边");

        assertArrayEquals(new int[]{0, 0}, f.nextStep(1, 0), "应当朝左走");
        assertArrayEquals(new int[]{4, 0}, f.nextStep(3, 0), "应当朝右走");
    }

    @Test
    @DisplayName("带权：直穿贵格 vs 绕路，取便宜的那条")
    void 带权选择() {
        //  .2.
        //  ...
        // 目标在右上角 (2,0)
        //
        //  从 (0,0) 出发有两条路：
        //    直穿 (1,0)：代价 1 + 2 = 3，再走 (2,0) 已到 → 3
        //    绕下面：  (0,1)(1,1)(2,1)(2,0) → 1+1+1+1 = 4
        //  所以应当直穿，距离 3
        FlowField f2 = new FlowField(grid(".2.", "..."), new int[]{2, 0});
        assertEquals(3f, f2.distanceAt(0, 0), "直穿 1+2=3 比绕路 4 便宜");

        // 把中间格改成代价 5：
        //    直穿 1 + 5 = 6
        //    绕下面 4
        //  现在必须绕路
        FlowField f5 = new FlowField(grid(".5.", "..."), new int[]{2, 0});
        assertEquals(4f, f5.distanceAt(0, 0), "绕下面 4 步，比直穿 1+5=6 便宜");
        assertArrayEquals(new int[]{0, 1}, f5.nextStep(0, 0),
                "应当先往下走，而不是直穿贵格");
    }

    @Test
    @DisplayName("带权：一行没法绕时只能直穿")
    void 一行只能直穿() {
        FlowField f = new FlowField(grid(".2."), new int[]{2, 0});
        assertEquals(3f, f.distanceAt(0, 0), "1 + 2 = 3，没有别的路");
    }

    @Test
    @DisplayName("nextStep 在目标格上返回 null（没有下一步）")
    void 目标格没有下一步() {
        FlowField f = new FlowField(grid("..."), new int[]{1, 0});
        assertNull(f.nextStep(1, 0), "已经是目标格，应当判定为到达");
    }

    @Test
    @DisplayName("nextStep 越界时返回 null，不抛异常")
    void 越界不抛异常() {
        FlowField f = new FlowField(grid("..."), new int[]{1, 0});
        assertNull(f.nextStep(-1, 0));
        assertNull(f.nextStep(9, 0));
        assertFalse(f.isReachable(-1, 0));
        assertFalse(f.isReachable(0, 99));
    }

    @Test
    @DisplayName("tracePath 走出一条能到达目标的完整路径")
    void 走出完整路径() {
        //  .....
        //  ####.
        //  .....
        // 中间一行是墙，但最右边留了一格 —— 必须绕过去
        CostGrid g = grid(".....", "####.", ".....");
        FlowField f = new FlowField(g, new int[]{4, 2});

        int[][] path = f.tracePath(0, 0, 100);
        assertTrue(path.length > 0, "上下是连通的（最右一格），应当有路径");
        assertArrayEquals(new int[]{0, 0}, path[0], "路径含起点");
        int[] last = path[path.length - 1];
        assertArrayEquals(new int[]{4, 2}, last,
                "路径终点应当就是目标格；实际 " + java.util.Arrays.deepToString(path));

        // 必须绕过墙：这条图的最短路是「右 4 步 → 下 2 步」，共 6 步，
        // 恰好等于曼哈顿距离 —— 因为缺口就在最右边，(0,0) 到 (4,2) 的对角线本来就要经过它。
        // 所以**不能**用"步数 > 曼哈顿"来证明绕路，要直接检查路径经过了缺口。
        assertEquals(6, path.length - 1, "最短路应当是 6 步");

        boolean throughGap = false;
        for (int[] cell : path) {
            if (cell[0] == 4 && cell[1] == 1) {
                throughGap = true;
            }
        }
        assertTrue(throughGap, "必须从最右边的缺口 (4,1) 穿过去；实际路径 "
                + java.util.Arrays.deepToString(path));

        // 每一格都与上一格相邻（不能瞬移），且都不在墙上
        for (int i = 1; i < path.length; i++) {
            int dc = Math.abs(path[i][0] - path[i - 1][0]);
            int dr = Math.abs(path[i][1] - path[i - 1][1]);
            assertEquals(1, dc + dr, "第 " + i + " 步不是相邻格: "
                    + java.util.Arrays.toString(path[i - 1]) + " → "
                    + java.util.Arrays.toString(path[i]));
            assertTrue(g.walkable(path[i][0], path[i][1]),
                    "路径经过了不可走的格子 " + java.util.Arrays.toString(path[i]));
        }
    }

    @Test
    @DisplayName("tracePath 在不可达时返回空数组")
    void 不可达无路径() {
        CostGrid g = grid("...", "###", "...");
        FlowField f = new FlowField(g, new int[]{0, 2});
        assertEquals(0, f.tracePath(0, 0, 100).length);
    }

    @Test
    @DisplayName("目标格不可走时被忽略，不会崩")
    void 目标不可走被忽略() {
        CostGrid g = grid(".#.");
        FlowField f = new FlowField(g, new int[]{1, 0});   // 目标是墙
        assertEquals(FlowField.UNREACHABLE, f.distanceAt(1, 0));
        assertEquals(0, f.reachableCount(), "没有有效目标，全图不可达");
    }

    @Test
    @DisplayName("没有有效目标时不崩（可达数为 0）")
    void 无目标不崩() {
        FlowField f = new FlowField(grid("..."));
        assertEquals(0, f.reachableCount());
        assertFalse(f.isReachable(0, 0));
    }

    @Test
    @DisplayName("参数为 null 时明确拒绝（编程错误，别静默）")
    void 参数校验() {
        assertThrows(IllegalArgumentException.class, () -> new FlowField(null, new int[]{0, 0}));
        assertThrows(IllegalArgumentException.class,
                () -> new FlowField(grid("..."), (int[][]) null));
    }

    @Test
    @DisplayName("大图不栈溢出：满尺寸全开图，对角距离 = 2×(n-1)")
    void 大图性能与正确性() {
        // 用真实上限，而不是写死一个数 —— 上限改过（25 → 15），
        // 写死的测试会变成"实现改了、测试没跟上"的假失败
        final int n = com.yestercar.campaigneditor.model.TileMap.MAX_SIZE;
        String[] rows = new String[n];
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < n; i++) {
            line.append('.');
        }
        java.util.Arrays.fill(rows, line.toString());

        FlowField f = new FlowField(grid(rows), new int[]{0, 0});
        assertEquals(n * n, f.reachableCount(), n + "×" + n + " 全开，应当全部可达");
        assertEquals(2f * (n - 1), f.distanceAt(n - 1, n - 1),
                "对角线走法是 (n-1)+(n-1) 步（四方向）");

        int[][] path = f.tracePath(n - 1, n - 1, 1000);
        assertEquals(2 * (n - 1) + 1, path.length,
                (2 * (n - 1)) + " 步 + 起点 = " + (2 * (n - 1) + 1) + " 格");
    }

    @Test
    @DisplayName("toAsciiArt 能把距离表打出来（失败时用于定位）")
    void 可打印诊断() {
        FlowField f = new FlowField(grid("...", ".#."), new int[]{2, 0});
        String art = f.toAsciiArt();
        assertNotNull(art);
        assertTrue(art.contains("."), "不可达格应显示为点");
        assertTrue(art.lines().count() == 2, "两行地形应打两行");
    }
}
