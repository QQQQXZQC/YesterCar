package com.yestercar.gameopening.icon;

import processing.core.PApplet;
import processing.core.PConstants;

import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link AkIcons} 里的 SVG path 画到 Processing 画布上。
 *
 * <h2>⚠️ 为什么不用 Java2D 直接填充</h2>
 * 第一版走的是 {@code ((PGraphicsJava2D) g).g2.fill(path)} —— 代码干净。
 * 但它<b>一个图标都没画出来，而且不报错</b>：本工程的渲染器是
 * <b>P2D（{@code processing.opengl.PGraphics2D}）</b>，不是 JAVA2D，
 * 于是那句 {@code instanceof PGraphicsJava2D} 判断直接 return 了。
 *
 * <p>教训：<b>渲染器不是编译期常量</b>。所以现在的实现不依赖任何具体
 * {@code PGraphics} 子类，只用 {@code beginShape/vertex/endShape} ——
 * Processing 的公共 API，P2D 与 JAVA2D 都支持。
 *
 * <h2>填充规则：为什么必须自己三角化</h2>
 * 需要的是<b>偶奇（even-odd）</b>规则 —— Remix 用同向子路径挖洞，
 * 非零规则下 {@code bank-line} 的屋顶会被填死（上游实测结论）。
 * 而 {@code beginShape()} 的三角化<b>不能指定填充规则</b>。
 *
 * <p>所以这里用<b>扫描线</b>自己算：展平成折线，逐行求交点、排序后按
 * "第 1-2 个、第 3-4 个……"配对 —— 配对法天然就是偶奇规则，
 * 不需要关心子路径绕向。
 */
public final class SvgPath {

    private SvgPath() {
    }

    /** 已解析的 path 缓存 —— 每帧重解析 1.5KB 的字符串是浪费。 */
    private static final Map<String, Path2D> PATHS = new LinkedHashMap<>();

    /** 三角化缓存：key = {@code id + "@" + 尺寸×2}（量化到 0.5px，避免浮点抖动）。 */
    private static final Map<String, float[]> TRIANGLES = new LinkedHashMap<>();

    /**
     * 离屏图像缓存：key 同上。
     *
     * <h2>⚠️ 为什么要先离屏渲染</h2>
     * 直接往画布上画几千个半透明三角形时，<b>alpha 会按三角形数量累积</b>：
     * 相邻扫描带共享边、一个矩形的两个三角形共享对角线，每条边都被抗锯齿
     * 混合一次。实测 0.17 的水印渲染出来接近 <b>0.47</b>（颜色 147 而不是 215）。
     *
     * <p>先在一个离屏缓冲里用<b>不透明</b>颜色画完，再整张 {@code image()} 合成一次 ——
     * 混色只发生一次，alpha 精确，而且顺带消掉了所有接缝。
     */
    private static final Map<String, processing.core.PImage> IMAGES = new LinkedHashMap<>();

    /** 缓存上限，防止极端窗口尺寸下无限增长。 */
    private static final int TRIANGLE_CACHE_MAX = 64;

    /**
     * 取某个图标的 24×24 {@link Path2D}。
     *
     * <p>公开是给测试用的 —— 要验证"边界落在 viewBox 内"就必须拿到几何本身。
     *
     * @param id sprite id
     * @return path；图标不存在时返回 {@code null}
     */
    public static Path2D of(String id) {
        Path2D cached = PATHS.get(id);
        if (cached != null) {
            return cached;
        }
        String d = AkIcons.path(id);
        if (d == null) {
            return null;
        }
        Path2D path = parse(d, AkIcons.evenOdd(id));
        PATHS.put(id, path);
        return path;
    }

    /**
     * 解析 SVG path 的 {@code d} 字符串。
     *
     * <p>只实现 AkIcons 实际用到的命令：{@code M L H V C Z} 及小写相对形式。
     * 没实现弧线（{@code A}）与二次曲线（{@code Q/T}）—— 我核过这 14 个 path
     * 的命令集合就只有 {@code CHLMVZhlvz}。将来若加入含其它命令的图标，
     * 这里会<b>显式抛异常</b>而不是画错。
     *
     * @param d       路径数据
     * @param evenOdd 是否用偶奇填充
     * @return 解析结果
     */
    public static Path2D parse(String d, boolean evenOdd) {
        Path2D p = new Path2D.Double(evenOdd ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
        double cx = 0;
        double cy = 0;
        double sx = 0;
        double sy = 0;

        int i = 0;
        int n = d.length();
        char cmd = 0;
        while (i < n) {
            char c = d.charAt(i);
            if (c == ' ' || c == ',' || c == '\n' || c == '\r' || c == '\t') {
                i++;
                continue;
            }
            if (Character.isLetter(c)) {
                cmd = c;
                i++;
            } else if (cmd == 0) {
                throw new IllegalArgumentException("path 以数字开头，没有命令");
            }

            boolean rel = Character.isLowerCase(cmd);
            switch (Character.toUpperCase(cmd)) {
                case 'M' -> {
                    double[] v = nums(d, i, 2);
                    i = (int) v[2];
                    cx = rel ? cx + v[0] : v[0];
                    cy = rel ? cy + v[1] : v[1];
                    sx = cx;
                    sy = cy;
                    p.moveTo(cx, cy);
                    cmd = rel ? 'l' : 'L';
                }
                case 'L' -> {
                    double[] v = nums(d, i, 2);
                    i = (int) v[2];
                    cx = rel ? cx + v[0] : v[0];
                    cy = rel ? cy + v[1] : v[1];
                    p.lineTo(cx, cy);
                }
                case 'H' -> {
                    double[] v = nums(d, i, 1);
                    i = (int) v[1];
                    cx = rel ? cx + v[0] : v[0];
                    p.lineTo(cx, cy);
                }
                case 'V' -> {
                    double[] v = nums(d, i, 1);
                    i = (int) v[1];
                    cy = rel ? cy + v[0] : v[0];
                    p.lineTo(cx, cy);
                }
                case 'C' -> {
                    double[] v = nums(d, i, 6);
                    i = (int) v[6];
                    double x1 = rel ? cx + v[0] : v[0];
                    double y1 = rel ? cy + v[1] : v[1];
                    double x2 = rel ? cx + v[2] : v[2];
                    double y2 = rel ? cy + v[3] : v[3];
                    double x = rel ? cx + v[4] : v[4];
                    double y = rel ? cy + v[5] : v[5];
                    p.curveTo(x1, y1, x2, y2, x, y);
                    cx = x;
                    cy = y;
                }
                case 'Z' -> {
                    p.closePath();
                    cx = sx;
                    cy = sy;
                }
                default -> throw new IllegalArgumentException(
                        "不支持的 SVG 命令 '" + cmd + "'（本解析器只实现 M L H V C Z）");
            }
        }
        return p;
    }

    /**
     * 从 {@code i} 开始读 {@code count} 个数字。
     *
     * @param d     路径数据
     * @param i     起点
     * @param count 要读几个
     * @return 长度 {@code count + 1}，最后一位是读完后的下标
     */
    private static double[] nums(String d, int i, int count) {
        double[] out = new double[count + 1];
        int n = d.length();
        for (int k = 0; k < count; k++) {
            while (i < n && (d.charAt(i) == ' ' || d.charAt(i) == ',' || d.charAt(i) == '\n'
                    || d.charAt(i) == '\r' || d.charAt(i) == '\t')) {
                i++;
            }
            int start = i;
            if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) {
                i++;
            }
            while (i < n && (Character.isDigit(d.charAt(i)) || d.charAt(i) == '.')) {
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException("位置 " + i + " 处缺少数字");
            }
            out[k] = Double.parseDouble(d.substring(start, i));
        }
        out[count] = i;
        return out;
    }

    /**
     * 把一个图标画进一个正方形盒子（填充）。
     *
     * @param applet 应用
     * @param id     sprite id
     * @param x      盒子左边（窗口坐标）
     * @param y      盒子上边（窗口坐标）
     * @param size   盒子边长（窗口像素）
     * @param argb   颜色（含 alpha）
     */
    public static void fill(PApplet applet, String id, float x, float y, float size, int argb) {
        processing.core.PImage img = image(applet, id, size);
        if (img == null) {
            return;
        }
        // 图标本身渲染成**白色不透明**，这里用 tint 一次染成目标色 + alpha。
        // 只合成一次，所以 alpha 不会被叠加到比设定值更浓。
        applet.pushStyle();
        applet.tint(argb);
        applet.image(img, x, y);
        applet.noTint();
        applet.popStyle();
    }

    /**
     * 取（或生成）某个图标在给定尺寸下的离屏图像。
     *
     * @param applet 应用（用来 createGraphics）
     * @param id     sprite id
     * @param size   边长（像素）
     * @return 白色不透明的图标图像；无法生成时 {@code null}
     */
    private static processing.core.PImage image(PApplet applet, String id, float size) {
        int px = Math.max(1, Math.round(size));
        String key = id + "@" + px;
        processing.core.PImage cached = IMAGES.get(key);
        if (cached != null) {
            return cached;
        }
        Path2D path = of(id);
        if (path == null) {
            return null;
        }
        float[] tris = tessellate(id, path, px);
        if (tris.length == 0) {
            return null;
        }

        // 2× 超采样：图标里有细笔画，1× 会糊
        int ss = 2;
        int canvas = px * ss;
        processing.core.PGraphics pg = applet.createGraphics(canvas, canvas);
        pg.beginDraw();
        pg.clear();
        pg.noStroke();
        pg.fill(255);
        // 三角形坐标是 px 尺度的，这里整体放大 ss 倍
        pg.scale(ss);
        pg.beginShape(PConstants.TRIANGLES);
        for (int i = 0; i < tris.length; i += 2) {
            pg.vertex(tris[i], tris[i + 1]);
        }
        pg.endShape();
        pg.endDraw();

        processing.core.PImage out = pg.get();
        out.resize(px, px);
        if (IMAGES.size() > TRIANGLE_CACHE_MAX) {
            IMAGES.clear();
        }
        IMAGES.put(key, out);
        return out;
    }

    /**
     * 扫描线三角化（偶奇规则）。
     *
     * @param id   sprite id（缓存键）
     * @param path 24×24 的 path
     * @param size 目标边长（像素）
     * @return 顶点数组 {@code [x0,y0, x1,y1, ...]}，每 3 个点一组三角形
     */
    static float[] tessellate(String id, Path2D path, float size) {
        String key = id + "@" + Math.round(size * 2f);
        float[] cached = TRIANGLES.get(key);
        if (cached != null) {
            return cached;
        }
        double scale = size / 24.0;
        List<double[]> edges = flatten(path, scale);
        float[] result = scanline(edges);
        if (TRIANGLES.size() > TRIANGLE_CACHE_MAX) {
            TRIANGLES.clear();
        }
        TRIANGLES.put(key, result);
        return result;
    }

    /**
     * 把 path 展平成边表。
     *
     * <p>展平容差 0.02（viewBox 单位）—— 24px 的图标放大到 120px 时
     * 折线误差约 0.1px，肉眼不可见。
     *
     * @param path  path
     * @param scale 24 → size 的缩放
     * @return 每条边 {@code {x0, y0, x1, y1}}
     */
    private static List<double[]> flatten(Path2D path, double scale) {
        List<double[]> edges = new ArrayList<>();
        FlatteningPathIterator it = new FlatteningPathIterator(
                path.getPathIterator(null), 0.02);
        double[] seg = new double[6];
        double startX = 0;
        double startY = 0;
        double prevX = 0;
        double prevY = 0;
        while (!it.isDone()) {
            int type = it.currentSegment(seg);
            switch (type) {
                case PathIterator.SEG_MOVETO -> {
                    startX = seg[0] * scale;
                    startY = seg[1] * scale;
                    prevX = startX;
                    prevY = startY;
                }
                case PathIterator.SEG_LINETO -> {
                    double nx = seg[0] * scale;
                    double ny = seg[1] * scale;
                    if (prevY != ny) {
                        edges.add(new double[]{prevX, prevY, nx, ny});
                    }
                    prevX = nx;
                    prevY = ny;
                }
                case PathIterator.SEG_CLOSE -> {
                    if (prevY != startY) {
                        edges.add(new double[]{prevX, prevY, startX, startY});
                    }
                    prevX = startX;
                    prevY = startY;
                }
                default -> { }
            }
            it.next();
        }
        return edges;
    }

    /**
     * 逐条扫描带求交点、配对、出三角形。
     *
     * @param edges 边表
     * @return 顶点数组
     */
    private static float[] scanline(List<double[]> edges) {
        if (edges.isEmpty()) {
            return new float[0];
        }
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] e : edges) {
            minY = Math.min(minY, Math.min(e[1], e[3]));
            maxY = Math.max(maxY, Math.max(e[1], e[3]));
        }
        // 每 0.5px 一条扫描带 —— 够细，三角形数又不至于爆掉
        int bands = Math.max(1, (int) Math.ceil((maxY - minY) / 0.5));
        double bandH = (maxY - minY) / bands;

        List<Double> xs = new ArrayList<>();
        List<Float> out = new ArrayList<>();

        for (int b = 0; b < bands; b++) {
            double y0 = minY + bandH * b;
            double y1 = y0 + bandH;
            double yMid = (y0 + y1) / 2.0;
            xs.clear();
            for (double[] e : edges) {
                double ya = e[1];
                double yb = e[3];
                double lo = Math.min(ya, yb);
                double hi = Math.max(ya, yb);
                if (yMid < lo || yMid >= hi) {
                    continue;
                }
                double t = (yMid - ya) / (yb - ya);
                xs.add(e[0] + t * (e[2] - e[0]));
            }
            if (xs.size() < 2) {
                continue;
            }
            xs.sort(Double::compare);
            // 配对：第 1-2、第 3-4……个交点之间填充 —— 这就是偶奇规则
            for (int k = 0; k + 1 < xs.size(); k += 2) {
                double xa = xs.get(k);
                double xb = xs.get(k + 1);
                if (xb - xa < 1e-6) {
                    continue;
                }
                // 一条扫描带 = 一个矩形，拆成两个三角形
                out.add((float) xa);
                out.add((float) y0);
                out.add((float) xb);
                out.add((float) y0);
                out.add((float) xb);
                out.add((float) y1);

                out.add((float) xa);
                out.add((float) y0);
                out.add((float) xb);
                out.add((float) y1);
                out.add((float) xa);
                out.add((float) y1);
            }
        }

        float[] arr = new float[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    /**
     * 打印某一行扫描的交点与配对结果（诊断用）。
     *
     * @param id    sprite id
     * @param size  目标边长
     * @param viewY viewBox 坐标下的 y（0~24）
     * @return 可读的一行
     */
    public static String debugSpans(String id, float size, double viewY) {
        Path2D path = of(id);
        if (path == null) {
            return "no path";
        }
        double scale = size / 24.0;
        List<double[]> edges = flatten(path, scale);
        double y = viewY * scale;
        List<Double> xs = new ArrayList<>();
        for (double[] e : edges) {
            double ya = e[1];
            double yb = e[3];
            double lo = Math.min(ya, yb);
            double hi = Math.max(ya, yb);
            if (y < lo || y >= hi) {
                continue;
            }
            double t = (y - ya) / (yb - ya);
            xs.add(e[0] + t * (e[2] - e[0]));
        }
        xs.sort(Double::compare);
        StringBuilder sb = new StringBuilder();
        sb.append("y=").append(viewY).append(" 交点=").append(xs.size()).append(" :");
        for (double x : xs) {
            sb.append(String.format(" %.2f", x / scale));
        }
        sb.append("  → 配对填充:");
        for (int k = 0; k + 1 < xs.size(); k += 2) {
            sb.append(String.format(" [%.1f..%.1f]", xs.get(k) / scale, xs.get(k + 1) / scale));
        }
        return sb.toString();
    }

    /**
     * 只查这个 id 能不能画（测试用）。
     *
     * @param id sprite id
     * @return 有路径返回 {@code true}
     */
    public static boolean has(String id) {
        return of(id) != null;
    }
}
