package com.yestercar.runtime.view;

import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.model.TileType;
import com.yestercar.campaigneditor.view.TerrainTextures;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PImage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 战斗界面的地形渲染：**透视 3D 视角**。
 *
 * <h2>和编辑器的渲染器什么关系</h2>
 * 编辑器的 {@code GridRenderer} 用正交投影，格子是<b>正方形</b>；
 * 本类用 {@link Camera} 做透视投影，格子是<b>梯形</b>（近大远小）。
 * 两者<strong>共用同一份地图数据与同一套贴图</strong>，只是投影不同。
 *
 * <h2>「近处平、远处厚」是怎么来的</h2>
 * 不需要额外造假 —— 它是俯角 30° 的<strong>透视自然结果</strong>：
 *
 * <pre>
 *   远处（row 小，离相机远）        近处（row 大，离相机近）
 *      +--------+                      +--------------+
 *      |  顶面  |  ← 小                 |    顶面      |  ← 大
 *      +--------+                      +--------------+
 *      | 侧面高 |  ← 侧面占格宽比例大    | 侧面矮       |  ← 侧面占格宽比例小
 *      +--------+                      +--------------+
 * </pre>
 *
 * 因为俯角固定，侧面的**世界高度**是常数，但投影后近处的格宽变大、侧面高度也变大 ——
 * 关键是**两者变化幅度不同**，于是侧面的视觉占比随距离变化，就出现了你要的效果。
 *
 * <h2>绘制顺序：按深度从远到近</h2>
 * 每格按「侧面 → 顶面」画，格子之间按深度排序（远的先画）。
 * 这样近处的格子会自然遮住远处的，不需要 z-buffer（JAVA2D 也没有）。
 *
 * <h2>JAVA2D 的限制</h2>
 * JAVA2D <strong>没有透视校正的纹理映射</strong>：只能把贴图按仿射变换贴到平行四边形上，
 * 贴到梯形会有"折痕"。所以本类对顶面采用<strong>填色 + 贴图近似</strong>：
 * 用 {@code PGraphicsJava2D.g2} 的仿射变换按三角形贴两次（上三角 + 下三角），
 * 折痕在 30° 俯角下不明显。若将来能用 P2D，应换成真正的透视校正着色器。
 */
public class BattleGridRenderer {

    /** 各地形的世界高度（格），与编辑器保持一致的相对关系。 */
    public static final float HIGH_RISE = 0.42f;
    public static final float IMPASSABLE_RISE = 0.32f;
    public static final float DOOR_RISE = 0.23f;
    public static final float GROUND_RISE = 0.12f;

    /** 格子的世界尺寸（格）：一格就是 1×1。 */
    /** 地形高度与贴图规格见类注释；{@code HALF} 供需要"半格"的地方使用（如贴图内缩）。 */
    private static final float HALF = 0.5f;

    private final PApplet applet;
    private final TerrainTextures textures;

    /** 复用的投影结果缓冲，避免每帧分配。每个点用完即取下一个。 */
    private final float[] pa = new float[4];
    private final float[] pb = new float[4];
    private final float[] pc = new float[4];
    private final float[] pd = new float[4];
    /** 贴图顶点复用缓冲（TextureMapper 要的是独立的 {x,y}）。 */
    private final float[][] tp = {new float[2], new float[2], new float[2]};

    /** 待绘制的格子，按深度排序。 */
    private final List<int[]> order = new ArrayList<>();

    /**
     * @param applet 所属窗口
     */
    public BattleGridRenderer(PApplet applet) {
        this.applet = applet;
        this.textures = new TerrainTextures(applet);
    }

    /**
     * 加载地形贴图。
     *
     * @return 成功张数
     */
    public int loadTextures() {
        return textures.load();
    }

    /** @return 贴图摘要 */
    public String textureSummary() {
        return textures.summary();
    }

    /**
     * 该地形的世界高度（格）。
     *
     * @param type 地形
     * @return 高度；未放置为 0
     */
    public static float riseOf(TileType type) {
        return switch (type) {
            case HIGH -> HIGH_RISE;
            case IMPASSABLE -> IMPASSABLE_RISE;
            case RED_DOOR, BLUE_DOOR -> DOOR_RISE;
            case GROUND -> GROUND_RISE;
            case EMPTY -> 0f;
        };
    }

    /**
     * 画整张地图。
     *
     * @param map    地图数据
     * @param camera 透视相机
     */
    public void draw(TileMap map, Camera camera) {
        sortByDepth(map, camera);

        // 画家算法：远 → 近。每格内部先侧面后顶面
        for (int[] cell : order) {
            drawCell(map, camera, cell[0], cell[1]);
        }
    }

    /** 把格子按深度从远到近排序（深度大 = 远）。 */
    private void sortByDepth(TileMap map, Camera camera) {
        order.clear();
        for (int row = 0; row < map.rows(); row++) {
            for (int col = 0; col < map.cols(); col++) {
                if (map.tileAt(col, row) != TileType.EMPTY) {
                    order.add(new int[]{col, row});
                }
            }
        }
        // 按格子中心深度降序（远先画）。中心是 (col+0.5, row+0.5)
        order.sort(Comparator.comparingDouble(
                c -> -camera.depthOf(c[0] + 0.5f, c[1] + 0.5f, 0f)));
    }

    /** 画一格：侧面 + 顶面。 */
    private void drawCell(TileMap map, Camera camera, int col, int row) {
        TileType type = map.tileAt(col, row);
        float rise = riseOf(type);

        // ⚠️ 世界坐标约定：格 (col,row) 占 [col,col+1] × [row,row+1]，
        // **中心是 (col+0.5, row+0.5)** —— 与数据层一致
        // （编辑器 GridRenderer 把格画在 col*tileSize .. (col+1)*tileSize）。
        //
        // 这里曾经写成 [col-0.5, col+0.5]（把整数坐标当成格心），
        // 于是整张地形相对敌人与门的高亮**偏了半个格**，
        // 表现出来就是"敌人走在格子的右下角"（实测踩过）。
        float x0 = col;
        float x1 = col + 1f;
        float y0 = row;
        float y1 = row + 1f;

        // ---- 侧面 ----
        // 只画朝观察者的南面（y = y1 那一侧）：相机永远在南边，其它面看不到。
        // 这样也顺带避免了"给每格都画侧面导致渗色"的问题。
        if (rise > 0f) {
            int side = sideColorOf(type);
            fillQuadFlat(camera,
                    x0, y1, rise, x1, y1, rise, x1, y1, 0f, x0, y1, 0f,
                    side);
        }

        // ---- 顶面 ----
        PImage img = textures.top(type);
        int flat = topColorOf(type);
        if (img != null) {
            // JAVA2D 无透视校正纹理映射：按仿射贴图，梯形会有轻微折痕。
            // 拆成两个三角形贴，比整块贴的折痕小。
            texturedQuad(camera, img,
                    x0, y0, rise, x1, y0, rise, x1, y1, rise, x0, y1, rise, flat);
        } else {
            fillQuadFlat(camera,
                    x0, y0, rise, x1, y0, rise, x1, y1, rise, x0, y1, rise, flat);
        }
    }

    /**
     * 用 P2D 原生纹理映射画一个四边形。
     *
     * <p>只有 OpenGL 后端（{@code PGraphics2D}）支持；其余后端返回
     * {@code false}，由调用方退回仿射贴图或纯色。
     *
     * @param img 贴图
     * @param a   左上（屏幕坐标）
     * @param b   右上
     * @param c   右下
     * @param d   左下
     * @return 是否画成功
     */
    private boolean nativeTexturedQuad(PImage img,
                                       float[] a, float[] b, float[] c, float[] d) {
        if (img == null || !(applet.getGraphics() instanceof processing.opengl.PGraphics2D)) {
            return false;
        }
        applet.pushStyle();
        applet.textureMode(PConstants.NORMAL);
        applet.noStroke();
        applet.noTint();
        applet.beginShape();
        applet.texture(img);
        applet.vertex(a[0], a[1], 0f, 0f);
        applet.vertex(b[0], b[1], 1f, 0f);
        applet.vertex(c[0], c[1], 1f, 1f);
        applet.vertex(d[0], d[1], 0f, 1f);
        applet.endShape(PConstants.CLOSE);
        applet.popStyle();
        return true;
    }

    // ------------------------------------------------------------------
    // 绘制原语
    // ------------------------------------------------------------------

    /**
     * 用纯色填充一个世界空间四边形。
     *
     * <p>带深度明暗：越远越暗（模拟雾/距离衰减），让纵深一眼可见。
     */
    private void fillQuadFlat(Camera camera,
                              float ax, float ay, float az,
                              float bx, float by, float bz,
                              float cx2, float cy2, float cz,
                              float dx, float dy, float dz,
                              int argb) {
        camera.project(ax, ay, az, pa);
        camera.project(bx, by, bz, pb);
        camera.project(cx2, cy2, cz, pc);
        camera.project(dx, dy, dz, pd);

        applet.noStroke();
        applet.fill(shadeByDepth(argb, camera, ax, ay, az));
        applet.beginShape();
        applet.vertex(pa[0], pa[1]);
        applet.vertex(pb[0], pb[1]);
        applet.vertex(pc[0], pc[1]);
        applet.vertex(pd[0], pd[1]);
        applet.endShape(PConstants.CLOSE);
    }

    /**
     * 把一个正方形贴图贴到世界空间四边形上。
     *
     * <p>拆成两个三角形分别贴，减少梯形贴图的"折痕"。
     * 若贴图无法使用（拿不到 Graphics2D），退回纯色。
     */
    private void texturedQuad(Camera camera, PImage img,
                              float ax, float ay, float az,
                              float bx, float by, float bz,
                              float cx2, float cy2, float cz,
                              float dx, float dy, float dz,
                              int fallback) {
        camera.project(ax, ay, az, pa);
        camera.project(bx, by, bz, pb);
        camera.project(cx2, cy2, cz, pc);
        camera.project(dx, dy, dz, pd);

        // ① P2D（OpenGL）有原生纹理映射：直接用 beginShape/texture/vertex。
        //    这条路径是**必须**的 —— 搬进主窗口后画布是 P2D，没有 Graphics2D，
        //    TextureMapper 只能返回 false，于是一路兜底成纯色块（贴图全丢）。
        if (nativeTexturedQuad(img, pa, pb, pc, pd)) {
            return;
        }

        // ② JAVA2D：没有原生纹理映射，用仿射贴图把四边形拆成两个三角形。
        // TextureMapper 需要 {x,y} 形式的点，转一下（复用缓冲）
        copy2(pa, tp[0]);
        copy2(pb, tp[1]);
        copy2(pc, tp[2]);
        // 三角形 1：a-b-c，对应的贴图 UV 是 (0,0)-(1,0)-(1,1)
        boolean ok1 = TextureMapper.mapTriangle(applet, img,
                tp[0], tp[1], tp[2], 0f, 0f, 1f, 0f, 1f, 1f);

        copy2(pa, tp[0]);
        copy2(pc, tp[1]);
        copy2(pd, tp[2]);
        // 三角形 2：a-c-d，UV 是 (0,0)-(1,1)-(0,1)
        // 与上一块共享对角线 a-c，所以接缝能对上
        boolean ok2 = TextureMapper.mapTriangle(applet, img,
                tp[0], tp[1], tp[2], 0f, 0f, 1f, 1f, 0f, 1f);

        if (!ok1 && !ok2) {
            fillQuadFlat(camera, ax, ay, az, bx, by, bz, cx2, cy2, cz, dx, dy, dz, fallback);
        }
    }

    private static void copy2(float[] src, float[] dst) {
        dst[0] = src[0];
        dst[1] = src[1];
    }

    /**
     * 按深度把颜色压暗。
     *
     * <p>这是没有光照系统时的廉价替代：远处偏暗偏冷，纵深立刻看得出来。
     *
     * @param argb   原色
     * @param camera 相机
     * @param x      世界 x
     * @param y      世界 y
     * @param z      世界 z
     * @return 调整后的颜色
     */
    private int shadeByDepth(int argb, Camera camera, float x, float y, float z) {
        float d = camera.depthOf(x, y, z);
        // 以相机距离为基准做归一化，大约 0.72 ~ 1.0 的亮度
        float k = camera.dist();
        float t = Math.min(1f, Math.max(0f, (d - k * 0.6f) / (k * 1.6f)));
        float lum = 1.0f - 0.30f * t;
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * lum));
        int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * lum));
        int b = Math.min(255, Math.round((argb & 0xFF) * lum));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------
    // 颜色兜底（贴图缺失时）
    // ------------------------------------------------------------------

    private static int topColorOf(TileType type) {
        return switch (type) {
            case GROUND -> 0xFF8E909F;
            case HIGH -> 0xFF60A8E9;
            case IMPASSABLE -> 0xFFD20000;
            case RED_DOOR -> 0xFFE0524E;
            case BLUE_DOOR -> 0xFF4A90D9;
            case EMPTY -> 0x00000000;
        };
    }

    private static int sideColorOf(TileType type) {
        return switch (type) {
            case GROUND -> 0xFF333944;
            case HIGH -> 0xFF191D24;
            case IMPASSABLE -> 0xFF3C3C45;
            case RED_DOOR -> 0xFF7A6E6E;
            case BLUE_DOOR -> 0xFF6E7A7C;
            case EMPTY -> 0x00000000;
        };
    }
}
