package com.yestercar.campaigneditor.view;

/**
 * 地图视口：负责「缩放 + 拖动」的坐标换算。
 *
 * <p>把这块单独成类，是为了让界面绘制只关心「画什么」，而换算逻辑可以被单独验证 ——
 * 缩放拖动的 bug（点不准格子、漂移、边界露白）都出在这里，靠肉眼看很难定位。
 *
 * <h2>三套坐标</h2>
 * <table border="1">
 *   <caption>坐标系</caption>
 *   <tr><th>名字</th><th>含义</th></tr>
 *   <tr><td>格子坐标</td><td>{@code col, row}，整数，地图契约用的坐标</td></tr>
 *   <tr><td>地图坐标</td><td>{@code col * tileSize, row * tileSize}，不受缩放影响的地图内部像素</td></tr>
 *   <tr><td>屏幕坐标</td><td>窗口像素，受缩放与拖动影响</td></tr>
 * </table>
 *
 * <p>换算关系：{@code 屏幕 = 地图区左上角 + 平移 + 地图坐标 × 缩放}。
 *
 * <h2>缩放围绕鼠标</h2>
 * 滚轮缩放时保持「鼠标下的那一点不动」—— 否则缩放会朝地图区中心漂移，用起来很别扭。
 * 实现方式是缩放后反算出需要的平移量（见 {@link #zoomAt}）。
 */
public final class MapViewport {

    /** 最小缩放。再小地图就只剩几个像素，没有意义。 */
    public static final float MIN_SCALE = 0.2f;

    /** 最大缩放。 */
    public static final float MAX_SCALE = 4.0f;

    /** 每格滚轮改变的比例（乘性，手感比加性均匀）。 */
    public static final float ZOOM_STEP = 1.12f;

    /** 未缩放时的一格边长（像素）。 */
    public static final int TILE_SIZE = 64;

    /** 地图列数、行数。 */
    private int cols;
    private int rows;

    /** 当前缩放。 */
    private float scale = 1f;

    /**
     * 平移量：地图左上角相对地图区左上角的偏移（屏幕像素）。
     *
     * <p>注意它已经含了缩放 —— 即「地图 (0,0) 的屏幕位置 = 地图区左上角 + pan」。
     */
    private float panX;
    private float panY;

    /** 承载这块地图的屏幕区域。 */
    private LayoutRegions.Region area;

    /**
     * @param cols 地图列数
     * @param rows 地图行数
     * @param area 地图区在屏幕上的矩形
     */
    public MapViewport(int cols, int rows, LayoutRegions.Region area) {
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        this.area = area;
        resetView();
    }

    // ------------------------------------------------------------------
    // 基本属性
    // ------------------------------------------------------------------

    /**
     * 改地图尺寸（编辑器里新建/改地图大小时调用）。
     *
     * <p>会保持当前缩放与平移不变 —— 用户改行列数时，视野跳走是很烦的。
     *
     * @param newCols 新列数
     * @param newRows 新行数
     */
    public void setMapSize(int newCols, int newRows) {
        this.cols = Math.max(1, newCols);
        this.rows = Math.max(1, newRows);
        clampPan();
    }

    /**
     * 换一个地图区矩形（窗口尺寸变化时调用）。
     *
     * @param newArea 新的地图区
     */
    public void setArea(LayoutRegions.Region newArea) {
        this.area = newArea;
        resetView();
    }

    /** @return 地图列数 */
    public int cols() {
        return cols;
    }

    /** @return 地图行数 */
    public int rows() {
        return rows;
    }

    /** @return 当前缩放 */
    public float scale() {
        return scale;
    }

    /** @return 平移 x（屏幕像素） */
    public float panX() {
        return panX;
    }

    /** @return 平移 y（屏幕像素） */
    public float panY() {
        return panY;
    }

    /** @return 地图区矩形 */
    public LayoutRegions.Region area() {
        return area;
    }

    /** @return 地图缩放后的总宽（屏幕像素） */
    public float scaledMapWidth() {
        return cols * (float) TILE_SIZE * scale;
    }

    /** @return 地图缩放后的总高（屏幕像素） */
    public float scaledMapHeight() {
        return rows * (float) TILE_SIZE * scale;
    }

    // ------------------------------------------------------------------
    // 视图控制
    // ------------------------------------------------------------------

    /**
     * 复位：按需自适应缩放并居中。
     *
     * <p>地图小到能放下时不放大（保持 1:1，格子看得清）；
     * 大到放不下时才缩小到刚好放得下。
     */
    public void resetView() {
        if (area == null) {
            return;
        }
        float fitX = area.width() / (cols * (float) TILE_SIZE);
        float fitY = area.height() / (rows * (float) TILE_SIZE);
        float fit = Math.min(fitX, fitY);
        scale = Math.min(1f, fit);          // 能放下就不放大
        scale = clampScale(scale);

        // 居中
        panX = (area.width() - scaledMapWidth()) / 2f;
        panY = (area.height() - scaledMapHeight()) / 2f;
        clampPan();
    }

    /**
     * 以某个屏幕点为锚缩放。
     *
     * <p>锚点保持不动：先记下锚点对应的地图坐标，缩放后再反算平移使该点回到原位。
     * 这就是「鼠标下的格子不跑」的实现。
     *
     * @param screenX 锚点屏幕 x（通常是鼠标）
     * @param screenY 锚点屏幕 y
     * @param steps   滚轮步数，正数放大
     * @return 缩放是否真的变了（到上下限时返回 false）
     */
    public boolean zoomAt(float screenX, float screenY, int steps) {
        if (steps == 0 || area == null) {
            return false;
        }
        float newScale = clampScale(scale * (float) Math.pow(ZOOM_STEP, steps));
        if (Math.abs(newScale - scale) < 1e-6f) {
            return false;
        }

        // 锚点在地图坐标里的位置（缩放前）
        float mapX = (screenX - area.x() - panX) / scale;
        float mapY = (screenY - area.y() - panY) / scale;

        scale = newScale;

        // 反算平移，让同一点仍落在锚点上
        panX = screenX - area.x() - mapX * scale;
        panY = screenY - area.y() - mapY * scale;

        clampPan();
        return true;
    }

    /**
     * 按屏幕像素平移（拖动地图）。
     *
     * @param dx 屏幕 x 位移
     * @param dy 屏幕 y 位移
     */
    public void panBy(float dx, float dy) {
        panX += dx;
        panY += dy;
        clampPan();
    }

    // ------------------------------------------------------------------
    // 坐标换算
    // ------------------------------------------------------------------

    /**
     * 地图坐标 → 屏幕坐标。
     *
     * @param mapX 地图内 x（像素，未缩放）
     * @param mapY 地图内 y
     * @return 长度为 2 的数组：{@code [0]} 屏幕 x、{@code [1]} 屏幕 y
     */
    public float[] mapToScreen(float mapX, float mapY) {
        return new float[]{
                area.x() + panX + mapX * scale,
                area.y() + panY + mapY * scale
        };
    }

    /**
     * 屏幕坐标 → 地图坐标。
     *
     * @param screenX 屏幕 x
     * @param screenY 屏幕 y
     * @return 长度为 2 的数组：{@code [0]} 地图 x、{@code [1]} 地图 y
     */
    public float[] screenToMap(float screenX, float screenY) {
        return new float[]{
                (screenX - area.x() - panX) / scale,
                (screenY - area.y() - panY) / scale
        };
    }

    /**
     * 屏幕坐标 → 格子坐标。
     *
     * @param screenX 屏幕 x
     * @param screenY 屏幕 y
     * @return 长度为 2 的数组：{@code [0]} 列、{@code [1]} 行（可能越界，调用方需自行判断）
     */
    public int[] screenToCell(float screenX, float screenY) {
        float[] map = screenToMap(screenX, screenY);
        return new int[]{
                (int) Math.floor(map[0] / TILE_SIZE),
                (int) Math.floor(map[1] / TILE_SIZE)
        };
    }

    /**
     * 光标是否落在地图区里（用于决定要不要响应拖动/滚轮）。
     *
     * @param screenX 屏幕 x
     * @param screenY 屏幕 y
     * @return 在地图区内返回 {@code true}
     */
    public boolean containsPointer(float screenX, float screenY) {
        return area != null && area.contains(screenX, screenY);
    }

    /**
     * 格子坐标是否在地图范围内。
     *
     * @param col 列
     * @param row 行
     * @return 在范围内返回 {@code true}
     */
    public boolean isInsideMap(int col, int row) {
        return col >= 0 && col < cols && row >= 0 && row < rows;
    }

    /**
     * 单格在屏幕上的边长。
     *
     * @return 边长（像素）
     */
    public float cellScreenSize() {
        return TILE_SIZE * scale;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static float clampScale(float value) {
        if (value < MIN_SCALE) {
            return MIN_SCALE;
        }
        return Math.min(value, MAX_SCALE);
    }

    /**
     * 约束平移，避免地图被拖出视野之外。
     *
     * <p>规则：地图比区域小时，居中锁死（不允许拖动，因为拖了会露白且没意义）；
     * 比区域大时，允许在「贴住任一边」的范围内移动，但不留空。
     */
    private void clampPan() {
        if (area == null) {
            return;
        }
        float mapW = scaledMapWidth();
        float mapH = scaledMapHeight();

        if (mapW <= area.width()) {
            panX = (area.width() - mapW) / 2f;
        } else {
            float minPan = area.width() - mapW;   // 右边贴住
            panX = Math.max(minPan, Math.min(0f, panX));
        }

        if (mapH <= area.height()) {
            panY = (area.height() - mapH) / 2f;
        } else {
            float minPan = area.height() - mapH;
            panY = Math.max(minPan, Math.min(0f, panY));
        }
    }

    @Override
    public String toString() {
        return String.format("MapViewport[%dx%d scale=%.3f pan=(%.1f, %.1f) area=%s]",
                cols, rows, scale, panX, panY, area);
    }
}
