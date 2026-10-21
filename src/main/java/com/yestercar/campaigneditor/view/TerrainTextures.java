package com.yestercar.campaigneditor.view;

import com.yestercar.campaigneditor.model.TileType;
import com.yestercar.common.io.Workspace;
import com.yestercar.common.util.AssetPaths;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PImage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * 地形贴图的加载与切分。
 *
 * <p>每张地形图都是 <strong>256 × 256 的正方形</strong>，但内部其实分成两段：
 * <strong>上面是顶面，最底下一条是美术画好的侧面</strong>。渲染时要把它切开分别使用：
 * 顶面铺在地块上面，侧面颜色用来画立体块的侧面。
 *
 * <h2>每张图的切分比例不一样</h2>
 * 量出来的分界线（见 {@code TextureSplitProbe}）：
 *
 * <table border="1">
 *   <caption>顶面占比</caption>
 *   <tr><th>地形</th><th>顶面占比</th><th>侧面底色</th></tr>
 *   <tr><td>GROUND</td><td>0.949</td><td>{@code #333944}</td></tr>
 *   <tr><td>HIGH</td><td>0.871</td><td>{@code #191D24}</td></tr>
 *   <tr><td>IMPASSABLE</td><td>0.695</td><td>{@code #5E524D}</td></tr>
 *   <tr><td>RED_DOOR</td><td>0.777</td><td>{@code #B7A8A8}</td></tr>
 *   <tr><td>BLUE_DOOR</td><td>0.781</td><td>{@code #ACB6B8}</td></tr>
 * </table>
 *
 * <p><strong>为什么不统一用 1.0（整张图当顶面）</strong>：那样会把美术画的侧面条带
 * 也当成顶面铺开，方块看起来会多出一条深色下边，而且相邻格子会出现横向接缝。
 *
 * <h2>贴图缺了也能跑</h2>
 * 按策划 §2.12：贴图不存在就退回 {@link GridRenderer} 的默认色块。
 * 所以本类所有查询都允许返回 {@code null}，调用方负责兜底。
 */
public final class TerrainTextures {

    /** 贴图边长（像素）。所有地形图都必须是这个尺寸。 */
    public static final int TEXTURE_SIZE = 256;

    /**
     * 每种地形的「顶面占比」与「侧面颜色」。
     *
     * <p><strong>{@code topRatio = 1.0} 表示这张图是完整俯视图、整张都当顶面用</strong>，
     * 侧面完全靠程序画。
     *
     * <p>这些数值是逐行扫描贴图得出的（每行平均色 + 找跳变），不是估的。
     * ⚠️ <strong>换美术素材后要重新量</strong>，否则顶面会多出一条深色下边，
     * 或相邻格出现横向接缝。
     *
     * <p>⚠️ <strong>不能只靠「找亮度跳变」自动判断</strong>：
     * {@code IMPASSABLE} 的警戒边框本身就是明暗交替的，自动算法会把
     * 「红色禁区与灰色边框的交界」误判成顶面/侧面分界线，从而**切掉边框的下半边**。
     * 判断某张图有没有侧面，要看**最底部若干行是不是一致地暗**，并人工核对一次。
     */
    private static final Map<TileType, Spec> SPECS = new EnumMap<>(TileType.class);

    static {
        // 顶面 0~243，244~246 是深色描边（#1A1A1B），247 起才是侧面带 #333944
        SPECS.put(TileType.GROUND,     new Spec(247f / 256f, 0xFF333944));
        // 顶面 0~234，235 起渐变到侧面带 #191D24
        SPECS.put(TileType.HIGH,       new Spec(235f / 256f, 0xFF191D24));
        // **完整俯视图**：外圈警戒边框 + 中间红色禁区，底部只有深色描边、没有侧面带
        // → 整张都当顶面用，侧面完全靠程序画
        SPECS.put(TileType.IMPASSABLE, new Spec(1f, 0xFF3C3C45));
        // 顶面 0~199，200 起是侧面带 #B7A8A8（美术已画好）
        SPECS.put(TileType.RED_DOOR,   new Spec(200f / 256f, 0xFFB7A8A8));
        // 顶面 0~200，201 起是侧面带 #ACB6B8
        SPECS.put(TileType.BLUE_DOOR,  new Spec(201f / 256f, 0xFFACB6B8));
    }

    /**
     * 一种地形的贴图规格。
     *
     * @param topRatio  顶面占整张图高度的比例
     * @param sideColor 侧面底色（从贴图底部量出来的平均色）
     */
    private record Spec(float topRatio, int sideColor) {
    }

    /** 已加载的「顶面部分」贴图。 */
    private final Map<TileType, PImage> tops = new EnumMap<>(TileType.class);

    /** 加载失败的原因，供状态栏 / 日志显示。 */
    private final Map<TileType, String> failures = new EnumMap<>(TileType.class);

    private final PApplet applet;
    private boolean loaded;

    /**
     * @param applet 所属窗口（用来读图与建子图）
     */
    public TerrainTextures(PApplet applet) {
        this.applet = applet;
    }

    /**
     * 加载所有地形贴图。重复调用只生效一次。
     *
     * @return 成功加载的张数
     */
    public int load() {
        if (loaded) {
            return tops.size();
        }
        loaded = true;

        Path dir = Workspace.imageFile(AssetPaths.TERRAIN_DIR);
        System.out.println("[TerrainTextures] 贴图目录: " + dir);

        for (Map.Entry<TileType, Spec> e : SPECS.entrySet()) {
            TileType type = e.getKey();
            Spec spec = e.getValue();
            Path file = dir.resolve(AssetPaths.terrainFile(type.name()));

            if (!Files.isRegularFile(file)) {
                failures.put(type, "文件不存在");
                System.out.printf("  [缺图] %-12s 用默认色块（%s）%n",
                        type.name(), file.getFileName());
                continue;
            }
            try {
                PImage full = applet.loadImage(file.toString());
                if (full == null || full.width <= 0) {
                    failures.put(type, "loadImage 返回空");
                    System.out.printf("  [失败] %-12s 读不出来，用默认色块%n", type.name());
                    continue;
                }
                if (full.width != TEXTURE_SIZE || full.height != TEXTURE_SIZE) {
                    System.out.printf("  [警告] %-12s 尺寸 %dx%d，约定是 %d x %d%n",
                            type.name(), full.width, full.height, TEXTURE_SIZE, TEXTURE_SIZE);
                }
                PImage top = cropTop(full, spec.topRatio());
                tops.put(type, top);
                System.out.printf("  [载入] %-12s %dx%d  顶面 %dx%d（占比 %.3f）%n",
                        type.name(), full.width, full.height,
                        top.width, top.height, spec.topRatio());
            } catch (RuntimeException ex) {
                failures.put(type, ex.getMessage());
                System.out.printf("  [失败] %-12s %s%n", type.name(), ex.getMessage());
            }
        }
        System.out.printf("[TerrainTextures] 共载入 %d / %d 张%n", tops.size(), SPECS.size());
        return tops.size();
    }

    /**
     * 裁出顶面部分。
     *
     * <p>用 {@code get()} 取像素再 {@code updatePixels()}，而不是 {@code copy()}/四参数
     * {@code image()} —— 后两者在需要跨帧复用时不如自己建一张 PImage 干净。
     *
     * @param full     整张贴图
     * @param topRatio 顶面占比
     * @return 只有顶面的新图
     */
    private PImage cropTop(PImage full, float topRatio) {
        int topH = Math.max(1, Math.round(full.height * topRatio));
        PImage out = applet.createImage(full.width, topH, PConstants.ARGB);
        full.loadPixels();
        out.loadPixels();
        for (int y = 0; y < topH; y++) {
            System.arraycopy(full.pixels, y * full.width,
                    out.pixels, y * out.width, full.width);
        }
        out.updatePixels();
        return out;
    }

    /**
     * 取某种地形的顶面贴图。
     *
     * @param type 地形
     * @return 贴图；没有则返回 {@code null}，调用方应退回默认色块
     */
    public PImage top(TileType type) {
        return tops.get(type);
    }

    /**
     * 取某种地形的侧面底色。
     *
     * @param type         地形
     * @param fallbackArgb 贴图缺失时的兜底色
     * @return ARGB 颜色
     */
    public int sideColor(TileType type, int fallbackArgb) {
        Spec spec = SPECS.get(type);
        PImage top = tops.get(type);
        return (top != null && spec != null) ? spec.sideColor() : fallbackArgb;
    }

    /**
     * @param type 地形
     * @return 该地形是否已成功加载贴图
     */
    public boolean has(TileType type) {
        return tops.containsKey(type);
    }

    /**
     * @return 已加载张数
     */
    public int loadedCount() {
        return tops.size();
    }

    /**
     * @return 加载失败的地形与原因，供状态栏提示
     */
    public Map<TileType, String> failures() {
        return Map.copyOf(failures);
    }

    /**
     * @return 一行摘要，如 {@code "地形贴图 5/5"}
     */
    public String summary() {
        return "地形贴图 " + tops.size() + "/" + SPECS.size();
    }
}
