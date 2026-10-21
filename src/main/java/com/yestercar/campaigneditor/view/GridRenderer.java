package com.yestercar.campaigneditor.view;

import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.model.TileType;
import com.yestercar.common.util.UITheme;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PImage;

import java.util.EnumMap;
import java.util.Map;

/**
 * 网格与地形的绘制：**五种地形全部画成有厚度的立体块**。
 *
 * <h2>等距立体块画法</h2>
 * 参考资源包里明日方舟的关卡图（{@code ArknightsGameResource-main/map/*.png}）——
 * 那张图的格子是<strong>正交矩形</strong>，不是菱形；立体感来自画法而非投影。
 * 所以本类<strong>不改坐标系统</strong>，仍然一格一个矩形，只是每格分两块画：
 *
 * <pre>
 *      +--------+
 *      |  顶面  |   ← 贴图（已裁掉美术画的那条侧面）
 *      +--------+
 *      |  侧面  |   ← 纯色，高 rise，这是立体感的来源
 *      +--------+
 * </pre>
 *
 * <p>这样 {@link MapViewport} 的缩放、拖动、点格换算<strong>完全不需要改</strong>。
 *
 * <h2>绘制顺序：先所有侧面，再所有顶面</h2>
 * 这是本类最关键的一点。若按格子逐个「先侧面后顶面」地画，
 * 相邻两格会出现**横向裂缝**：本格的侧面占用了下一格顶面的顶部若干像素。
 * 分两趟画就没有这个问题。
 *
 * <p>「A 在 B 正上方时，B 的侧面会被 A 的顶面盖掉」在俯视视角下是<strong>正确的遮挡</strong>，
 * 不是 bug。因此最下面一行会完整露出侧面，上面的行则只露出一条边 —— 这就是「有厚度」的观感。
 *
 * <h2>每种地形的厚度不同</h2>
 * 参照参考项目 Defender 的 {@code TILE_H}（高台 0.42 / 不可通行 0.30 / 平台 0.26），
 * 侧面高度能直接读出地形类别，比全部同高有层次。
 *
 * <h2>贴图缺失时的兜底</h2>
 * 按策划 §2.12，贴图不存在就用默认色块。<strong>缺图不会让编辑器打不开</strong>。
 */
public class GridRenderer {

    /**
     * 各地形的侧面高度，占一格边长的比例。
     *
     * <p>凸起越高越"重"：高台最高，地面最薄。
     */
    private static final Map<TileType, Float> RISE = new EnumMap<>(TileType.class);

    static {
        RISE.put(TileType.HIGH, 0.42f);
        RISE.put(TileType.IMPASSABLE, 0.32f);
        RISE.put(TileType.RED_DOOR, 0.24f);
        RISE.put(TileType.BLUE_DOOR, 0.22f);
        RISE.put(TileType.GROUND, 0.12f);
        RISE.put(TileType.EMPTY, 0f);
    }

    // ---- 贴图缺失时的兜底色块 ----

    /** 未放置：接近画布底色。 */
    public static final int COLOR_EMPTY_FILL = 0xFFFAFBFC;
    /** 未放置的虚线框。 */
    public static final int COLOR_EMPTY_DASH = 0xFFD5D9DE;
    /** 地面的兜底色。 */
    public static final int COLOR_GROUND_FALLBACK = 0xFF8E909F;
    /** 高台的兜底色。 */
    public static final int COLOR_HIGH_FALLBACK = 0xFF60A8E9;
    /** 不可通行的兜底色。 */
    public static final int COLOR_IMPASSABLE_FALLBACK = 0xFFD20000;
    /** 红门的兜底色。 */
    public static final int COLOR_RED_DOOR_FALLBACK = 0xFFE0524E;
    /** 蓝门的兜底色。 */
    public static final int COLOR_BLUE_DOOR_FALLBACK = 0xFF4A90D9;

    /** 网格线（半透明白，压在贴图上不会太抢眼）。 */
    public static final int COLOR_GRID = 0x33FFFFFF;

    /** 合法悬停高亮。 */
    public static final int COLOR_HIGHLIGHT = UITheme.COLOR_PRIMARY;

    /** 越界悬停高亮。 */
    public static final int COLOR_HIGHLIGHT_BAD = UITheme.COLOR_ACCENT;

    /** 门编号文字色。 */
    public static final int COLOR_DOOR_LABEL = 0xFFFFFFFF;

    private final PApplet applet;
    private final TerrainTextures textures;

    /** 每格的屏幕矩形缓存：{@code [row * cols + col] -> {x, y, w, h}}。 */
    private float[][] cellCache;
    private int cachedCols;
    private int cachedRows;
    private float cachedCellSize;
    private float cachedOriginX;
    private float cachedOriginY;

    /**
     * @param applet 所属窗口
     */
    public GridRenderer(PApplet applet) {
        this.applet = applet;
        this.textures = new TerrainTextures(applet);
    }

    /**
     * 加载地形贴图。在 {@code setup()} 里调用一次。
     *
     * @return 成功加载的张数
     */
    public int loadTextures() {
        return textures.load();
    }

    /**
     * @return 贴图加载摘要，如 {@code "地形贴图 5/5"}
     */
    public String textureSummary() {
        return textures.summary();
    }

    /**
     * @return 贴图加载失败的地形与原因
     */
    public Map<TileType, String> textureFailures() {
        return textures.failures();
    }

    /**
     * 画整张地图。调用方负责先裁剪到地图区，本类不做裁剪。
     *
     * @param map      地图数据
     * @param viewport 视口
     */
    public void draw(TileMap map, MapViewport viewport) {
        refreshCache(map, viewport);

        // 第一趟：所有侧面（从远到近）。只有比南边邻格高的格子才画 —— 见 shouldDrawSouthSide
        for (int row = 0; row < map.rows(); row++) {
            for (int col = 0; col < map.cols(); col++) {
                drawSide(map, col, row, index(map, col, row));
            }
        }

        // 第二趟：未放置格（它们是"空"，既无侧面也无顶面）
        for (int row = 0; row < map.rows(); row++) {
            for (int col = 0; col < map.cols(); col++) {
                if (map.tileAt(col, row) == TileType.EMPTY) {
                    drawEmpty(index(map, col, row));
                }
            }
        }

        // 第三趟：所有顶面
        for (int row = 0; row < map.rows(); row++) {
            for (int col = 0; col < map.cols(); col++) {
                drawTop(map.tileAt(col, row), index(map, col, row));
            }
        }

        drawGridLines(map, viewport);
        drawDoorLabels(map, viewport);
    }

    /**
     * 这一格要不要画朝观察者（南）的侧面。
     *
     * <h2>为什么必须判断邻居</h2>
     * 侧面画在格子底部、高度 rise。如果无脑给每一格都画，上一行的侧面就会
     * 压在下一行顶面的上沿 —— 于是「隔壁那格的侧面」从别人的格子里透出来，
     * 看起来像是格子被贴错了图（实测踩过：地面格底部露出不可通行格的警戒条纹）。
     *
     * <h2>判据</h2>
     * 只有<strong>比南边邻格高</strong>时才画：
     * <ul>
     *   <li>南边空 / 出界 → 画（这一面朝向观察者，看得见）；</li>
     *   <li>南边更高或一样高 → 不画（会被南边那格完全挡住）；</li>
     *   <li>南边更矮 → 画（高出来的那一截露得出来）。</li>
     * </ul>
     * 这正是参考项目 Defender 的 {@code buildTileQuads} 里
     * {@code if (H > nh)} 那条判断，思路一致。
     *
     * @param map 地图
     * @param col 列
     * @param row 行
     * @param cell 格子屏幕边长
     * @return 该格朝南的侧面是否需要绘制
     */
    private boolean shouldDrawSouthSide(TileMap map, int col, int row, float cell) {
        TileType self = map.tileAt(col, row);
        if (self == TileType.EMPTY) {
            return false;
        }
        int southRow = row + 1;
        if (!map.inBounds(col, southRow)) {
            return true;                       // 地图最下面一行：完整露出厚度
        }
        TileType south = map.tileAt(col, southRow);
        if (south == TileType.EMPTY) {
            return true;                       // 南边空着，这一面看得见
        }
        // 南边存在：只有自己更高才露得出来
        return riseOf(self, cell) > riseOf(south, cell) + 0.5f;
    }

    // ------------------------------------------------------------------
    // 几何缓存
    // ------------------------------------------------------------------

    private int index(TileMap map, int col, int row) {
        return row * map.cols() + col;
    }

    /** 视口没变就复用上一帧的换算结果 —— 拖大图时能省下大量重复计算。 */
    private void refreshCache(TileMap map, MapViewport viewport) {
        float cell = viewport.cellScreenSize();
        float[] origin = viewport.mapToScreen(0, 0);
        boolean same = cellCache != null
                && cachedCols == map.cols() && cachedRows == map.rows()
                && cachedCellSize == cell
                && cachedOriginX == origin[0] && cachedOriginY == origin[1];
        if (same) {
            return;
        }

        cachedCols = map.cols();
        cachedRows = map.rows();
        cachedCellSize = cell;
        cachedOriginX = origin[0];
        cachedOriginY = origin[1];

        cellCache = new float[map.cols() * map.rows()][];
        for (int row = 0; row < map.rows(); row++) {
            for (int col = 0; col < map.cols(); col++) {
                cellCache[index(map, col, row)] = new float[]{
                        origin[0] + col * cell,
                        origin[1] + row * cell,
                        cell, cell
                };
            }
        }
    }

    /** @return 该格在屏幕上抬升的高度（像素） */
    private float riseOf(TileType type, float cell) {
        Float ratio = RISE.get(type);
        return (ratio == null ? 0f : ratio) * cell;
    }

    // ------------------------------------------------------------------
    // 三趟绘制
    // ------------------------------------------------------------------

    /**
     * 画一格朝观察者（南）的侧面：格子底部的一条 band，高度 rise。
     *
     * <p>颜色优先取贴图底部量出来的侧面底色，贴图缺失时退回"顶面兜底色压暗"。
     *
     * @param map  地图（用来判断邻居）
     * @param col  列
     * @param row  行
     * @param idx  缓存下标
     */
    private void drawSide(TileMap map, int col, int row, int idx) {
        float[] c = cellCache[idx];
        float cell = c[2];
        if (!shouldDrawSouthSide(map, col, row, cell)) {
            return;
        }
        TileType type = map.tileAt(col, row);
        float rise = riseOf(type, cell);
        if (rise < 0.5f) {
            return;
        }

        int fallback = fallbackTop(type);
        int side = textures.sideColor(type, darker(fallback, 0.45f));

        applet.noStroke();
        applet.fill(side);
        applet.rect(c[0], c[1] + cell - rise, cell, rise);

        // 侧面顶端压一条稍亮的线：不加的话顶面与侧面会糊成一片
        applet.fill(lighten(side, 1.3f));
        applet.rect(c[0], c[1] + cell - rise, cell, Math.max(1f, cell * 0.014f));
    }

    /**
     * 画一格的顶面（贴图或兜底色块）。
     *
     * <p>顶面就是完整一格大小 —— 贴图是正方形，<strong>不能压扁</strong>，
     * 否则图案会变形。厚度由下面的侧面 band 表现，且会被下一行的顶面盖掉一部分，
     * 净效果正是「方块朝观察者一面有厚度」。
     */
    private void drawTop(TileType type, int idx) {
        if (type == TileType.EMPTY) {
            return;
        }
        float[] c = cellCache[idx];
        float cell = c[2];

        PImage img = textures.top(type);
        if (img != null) {
            applet.image(img, c[0], c[1], cell, cell);
        } else {
            applet.noStroke();
            applet.fill(fallbackTop(type));
            applet.rect(c[0], c[1], cell, cell);
        }

        // 顶面描边：不加的话相邻同色格会糊成一片，数不出格子
        applet.noFill();
        applet.stroke(darker(fallbackTop(type), 0.72f));
        applet.strokeWeight(1f);
        applet.rect(c[0], c[1], cell, cell);
        applet.noStroke();
    }

    /** 未放置格：空白 + 虚线框。 */
    private void drawEmpty(int idx) {
        float[] c = cellCache[idx];
        applet.noStroke();
        applet.fill(COLOR_EMPTY_FILL);
        applet.rect(c[0], c[1], c[2], c[3]);
        drawDashedBorder(c[0], c[1], c[2]);
    }

    /** 贴图缺失时该地形用的颜色。 */
    private static int fallbackTop(TileType type) {
        return switch (type) {
            case GROUND -> COLOR_GROUND_FALLBACK;
            case HIGH -> COLOR_HIGH_FALLBACK;
            case IMPASSABLE -> COLOR_IMPASSABLE_FALLBACK;
            case RED_DOOR -> COLOR_RED_DOOR_FALLBACK;
            case BLUE_DOOR -> COLOR_BLUE_DOOR_FALLBACK;
            case EMPTY -> COLOR_EMPTY_FILL;
        };
    }

    // ------------------------------------------------------------------
    // 网格线与标注
    // ------------------------------------------------------------------

    private void drawGridLines(TileMap map, MapViewport viewport) {
        float cell = viewport.cellScreenSize();
        float[] origin = viewport.mapToScreen(0, 0);

        applet.stroke(COLOR_GRID);
        applet.strokeWeight(1f);
        applet.noFill();

        float w = map.cols() * cell;
        float h = map.rows() * cell;
        for (int col = 0; col <= map.cols(); col++) {
            float x = origin[0] + col * cell;
            applet.line(x, origin[1], x, origin[1] + h);
        }
        for (int row = 0; row <= map.rows(); row++) {
            float y = origin[1] + row * cell;
            applet.line(origin[0], y, origin[0] + w, y);
        }
        applet.noStroke();
    }

    /** 在门上标编号，便于波次里按 id 引用。 */
    private void drawDoorLabels(TileMap map, MapViewport viewport) {
        float cell = viewport.cellScreenSize();
        if (cell < 28f) {
            return;
        }
        applet.textAlign(PConstants.CENTER, PConstants.CENTER);
        applet.textSize(Math.max(10f, cell * 0.32f));
        applet.fill(COLOR_DOOR_LABEL);

        float[] origin = viewport.mapToScreen(0, 0);
        map.redDoors().forEach(d ->
                label("R" + d.id(), d.col(), d.row(), origin, cell, TileType.RED_DOOR));
        map.blueDoors().forEach(d ->
                label("B" + d.id(), d.col(), d.row(), origin, cell, TileType.BLUE_DOOR));

        applet.textAlign(PConstants.LEFT, PConstants.BASELINE);
    }

    private void label(String text, int col, int row, float[] origin, float cell, TileType type) {
        float x = origin[0] + col * cell + cell / 2f;
        // 编号画在顶面中心。顶面是整格，所以中心就是格中心；
        // 但侧面占了底部 rise 高，稍微上移一点视觉上更居中。
        float y = origin[1] + row * cell + cell / 2f - riseOf(type, cell) * 0.4f;
        applet.text(text, x, y);
    }

    // ------------------------------------------------------------------
    // 鼠标悬停
    // ------------------------------------------------------------------

    /**
     * 画鼠标悬停高亮。
     *
     * @param viewport 视口
     * @param col      悬停的列
     * @param row      悬停的行
     * @param inside   是否在地图范围内
     */
    public void drawHover(MapViewport viewport, int col, int row, boolean inside) {
        float cell = viewport.cellScreenSize();
        float[] p = viewport.mapToScreen(col * (float) MapViewport.TILE_SIZE,
                row * (float) MapViewport.TILE_SIZE);

        applet.noFill();
        applet.stroke(inside ? COLOR_HIGHLIGHT : COLOR_HIGHLIGHT_BAD);
        applet.strokeWeight(Math.max(2f, cell * 0.045f));
        applet.rect(p[0] + 1f, p[1] + 1f, cell - 2f, cell - 2f);
        applet.noStroke();
    }

    // ------------------------------------------------------------------
    // 颜色工具
    // ------------------------------------------------------------------

    /** 画一条虚线边框（用于未放置格）。 */
    private void drawDashedBorder(float x, float y, float cell) {
        applet.stroke(COLOR_EMPTY_DASH);
        applet.strokeWeight(1f);
        float dash = Math.max(3f, cell * 0.12f);
        for (float d = 0; d < cell; d += dash * 2) {
            float len = Math.min(dash, cell - d);
            applet.line(x + d, y, x + d + len, y);
            applet.line(x + d, y + cell, x + d + len, y + cell);
            applet.line(x, y + d, x, y + d + len);
            applet.line(x + cell, y + d, x + cell, y + d + len);
        }
        applet.noStroke();
    }

    /** 把颜色整体压暗。 */
    static int darker(int argb, float factor) {
        return scale(argb, factor);
    }

    /** 把颜色整体提亮。 */
    static int lighten(int argb, float factor) {
        return scale(argb, factor);
    }

    private static int scale(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = clamp255(Math.round(((argb >> 16) & 0xFF) * factor));
        int g = clamp255(Math.round(((argb >> 8) & 0xFF) * factor));
        int b = clamp255(Math.round((argb & 0xFF) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
