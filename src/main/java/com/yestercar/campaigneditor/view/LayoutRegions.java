package com.yestercar.campaigneditor.view;

/**
 * 编辑器各功能区的矩形范围。
 *
 * <p>把布局几何集中在这里，而不是散落在各个 View 的绘制代码里 ——
 * 好处是「改一处，全对齐」，也让「区域有没有重叠/空隙」变成可断言的事
 * （见 {@code LayoutCheck} 之类的验证）。
 *
 * <h2>1600 × 900 的分区</h2>
 * <pre>
 * +------------------------------------------------------------------+  y=0
 * | 顶部工具栏 (56)                                                   |
 * +---------------------------------------------+--------------------+  y=72
 * |                                             |                    |
 * | 地图区 (1192 × 608)                          | 属性栏 (360 × 608) |
 * |                                             |                    |
 * +---------------------------------------------+--------------------+  y=696
 * | 波次列表 (1600 - 32, 高 176)                                      |
 * +------------------------------------------------------------------+  y=888
 * | 状态栏 (28)                                                       |
 * +------------------------------------------------------------------+  y=900
 * </pre>
 *
 * <p>所有矩形都按「外框含边距」给出：{@code x/y} 是左/上边界，
 * {@code width/height} 是尺寸，不含线宽补偿。
 */
public final class LayoutRegions {

    // ---- 窗口 ----
    public static final int WINDOW_W = 1600;
    public static final int WINDOW_H = 960;

    /** 窗口四周边距。 */
    public static final int MARGIN = 16;

    /** 行与行之间的间距。 */
    public static final int GAP = 8;

    // ---- 各行高度 ----
    public static final int HEADER_H = 56;
    public static final int WAVE_H = 230;
    public static final int STATUS_H = 28;

    /** 右侧属性栏宽度。 */
    public static final int SIDEBAR_W = 360;

    // ---- 派生几何（全部由上面推导，不要另写死数字） ----

    /**
     * 纵向合计的间距个数。
     *
     * <p>排布是：上边距 · 工具栏 · 间距 · 内容行 · 间距 · 波次区 · 间距 · 状态栏 · 下边距，
     * 其中<strong>「边距」也占一个间距的宽度</strong>，所以一共是
     * <strong>5</strong> 个（3 个区内间距 + 上下边距各 1）。
     *
     * <p>写成常量而不是直接乘 4 —— 这个数算错一次就会让最后一行溢出窗口，
     * 而且从画面上看只是"状态栏贴边了"，很难注意到。
     */
    public static final int GAP_COUNT = 5;

    /** 内容行（地图区 + 属性栏）的顶部 y。 */
    public static final int CONTENT_Y = MARGIN + HEADER_H + GAP;

    /** 状态栏顶部 y。 */
    public static final int STATUS_Y = WINDOW_H - MARGIN - STATUS_H;

    /**
     * 波次区顶部 y，由状态栏往上推。
     *
     * <p>之所以从**底部往顶部**推导（而不是从内容行往下加），是为了让
     * 「状态栏贴住下边距」这个约束绝对成立 —— 它是最后一行，最容易被挤出去。
     */
    public static final int WAVE_Y = STATUS_Y - GAP - WAVE_H;

    /**
     * 内容行的高度 = 剩余空间 = 窗口高 - 三个固定行高 - 5 个间距。
     *
     * <p>由「剩余空间」反推，而不是另写一个算式 —— 这样只要窗口尺寸或某个行高改了，
     * 内容行会自动吸收差额，不会出现某一行被挤出窗口。
     */
    public static final int CONTENT_H = WAVE_Y - GAP - CONTENT_Y;

    /** 地图区宽度 = 窗口宽 - 左右边距 - 属性栏 - 间距。 */
    public static final int MAP_W = WINDOW_W - MARGIN * 2 - SIDEBAR_W - GAP;

    private LayoutRegions() {
    }

    /**
     * 一个矩形区域。
     *
     * @param x      左边界
     * @param y      上边界
     * @param width  宽度
     * @param height 高度
     */
    public record Region(float x, float y, float width, float height) {
        /** @return 右边界 */
        public float right() {
            return x + width;
        }

        /** @return 下边界 */
        public float bottom() {
            return y + height;
        }

        /** @return 中心 x */
        public float centerX() {
            return x + width / 2f;
        }

        /** @return 中心 y */
        public float centerY() {
            return y + height / 2f;
        }

        /**
         * @param px 点的 x
         * @param py 点的 y
         * @return 点是否落在矩形内（含边界）
         */
        public boolean contains(float px, float py) {
            return px >= x && px <= right() && py >= y && py <= bottom();
        }
    }

    /**
     * @return 顶部工具栏区域
     */
    public static Region header() {
        return new Region(MARGIN, MARGIN, WINDOW_W - MARGIN * 2f, HEADER_H);
    }

    /**
     * @return 地图区（可缩放、可拖动）
     */
    public static Region mapArea() {
        return new Region(MARGIN, CONTENT_Y, MAP_W, CONTENT_H);
    }

    /**
     * @return 右侧属性栏
     */
    public static Region sidebar() {
        return new Region(WINDOW_W - MARGIN - SIDEBAR_W, CONTENT_Y, SIDEBAR_W, CONTENT_H);
    }

    /**
     * @return 底部波次列表
     */
    public static Region wavePanel() {
        return new Region(MARGIN, WAVE_Y, WINDOW_W - MARGIN * 2f, WAVE_H);
    }

    /**
     * @return 最底状态栏
     */
    public static Region statusBar() {
        return new Region(MARGIN, STATUS_Y, WINDOW_W - MARGIN * 2f, STATUS_H);
    }

    /**
     * 按 1600×900 的设计尺寸画一行布局自检，输出各区矩形的实际数值。
     *
     * <p>放在这里而不是测试里，是因为它用的是同一批常量 ——
     * 文档里的 ASCII 图容易和代码走偏，这个输出不会。
     *
     * @return 描述各行几何的多行文本
     */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("窗口 %d × %d，边距 %d，行间距 %d%n",
                WINDOW_W, WINDOW_H, MARGIN, GAP));
        append(sb, "工具栏", header());
        append(sb, "地图区", mapArea());
        append(sb, "属性栏", sidebar());
        append(sb, "波次区", wavePanel());
        append(sb, "状态栏", statusBar());
        return sb.toString();
    }

    private static void append(StringBuilder sb, String name, Region r) {
        sb.append(String.format("  %-6s x=%-6.0f y=%-6.0f 宽=%-6.0f 高=%-6.0f  (右=%.0f 下=%.0f)%n",
                name, r.x(), r.y(), r.width(), r.height(), r.right(), r.bottom()));
    }

    /**
     * 计算地图区在 {@code tileSize} 下能完整显示多少格（不缩放时）。
     *
     * <p>用于量化「多大的地图还得缩放」。<strong>读常量、不写死数值</strong> ——
     * 上限已经从 25 调到 15 一次，写死的注释下次就会变成假的。
     *
     * @param tileSize 格子边长（像素）
     * @return 长度为 2 的数组：{@code [0]} 列数、{@code [1]} 行数
     */
    public static int[] fitsAt(int tileSize) {
        Region map = mapArea();
        return new int[]{
                (int) Math.floor(map.width() / tileSize),
                (int) Math.floor(map.height() / tileSize)
        };
    }
}
