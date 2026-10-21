package com.yestercar.runtime.view;

import processing.awt.PGraphicsJava2D;
import processing.core.PApplet;
import processing.core.PImage;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;

/**
 * 把贴图贴到一个三角形上。
 *
 * <h2>为什么需要这个类</h2>
 * <strong>Processing 的 JAVA2D 渲染器没有纹理映射</strong>：
 * {@code texture()} / {@code vertex(x, y, u, v)} 在 JAVA2D 下会直接报
 * {@code texture() is not available with this renderer}。
 * 而 P2D 在目标机器上初始化会挂（显卡不支持 OpenGL 2.0），所以也换不了。
 *
 * <p>唯一可行的路是绕到 {@link PGraphicsJava2D#g2}（它是 public 字段）用
 * {@link Graphics2D#drawImage} 的仿射变换。这样能得到**仿射**（而非透视校正）的贴图 ——
 * 贴到梯形会有轻微"折痕"，所以调用方把四边形<strong>拆成两个三角形分别贴</strong>，
 * 折痕能小很多。30° 俯角下肉眼基本看不出。
 *
 * <h2>变换是怎么算的</h2>
 * 设贴图坐标 {@code (u,v)} 到屏幕坐标 {@code (x,y)} 是仿射映射
 * {@code x = a·u + b·v + c}，{@code y = d·u + e·v + f}，
 * 三点对三点可解出 6 个系数。算出后交给 {@link AffineTransform} 即可。
 *
 * <h2>⚠️ y 轴方向</h2>
 * Processing 的画布 y 向下，Java2D 的画布 y 也向下，所以这里是同一套方向、
 * <b>不需要翻转</b>。{@code g2.drawImage} 的 {@code (0,0)} 就是画布左上角。
 */
public final class TextureMapper {

    private TextureMapper() {
    }

    /**
     * 把 {@code img} 贴到屏幕三角形 {@code (x0,y0)-(x1,y1)-(x2,y2)} 上。
     *
     * @param applet 窗口（用来拿 {@code PGraphicsJava2D.g2}）
     * @param img    贴图
     * @param p0     三角形第 1 个顶点，长度 ≥ 2：{@code [0]=屏幕x, [1]=屏幕y}
     * @param p1     第 2 个顶点
     * @param p2     第 3 个顶点
     * @param u0     {@code p0} 对应的贴图 u（0~1）
     * @param v0     {@code p0} 对应的贴图 v（0~1）
     * @param u1     {@code p1} 对应的 u
     * @param v1     {@code p1} 对应的 v
     * @param u2     {@code p2} 对应的 u
     * @param v2     {@code p2} 对应的 v
     * @return 成功贴出返回 {@code true}；拿不到 Graphics2D、图片为空、
     *         或三点退化（面积为 0）时返回 {@code false}，调用方应退回纯色填充
     */
    public static boolean mapTriangle(PApplet applet, PImage img,
                                      float[] p0, float[] p1, float[] p2,
                                      float u0, float v0,
                                      float u1, float v1,
                                      float u2, float v2) {
        if (applet == null || img == null || img.width <= 0 || img.height <= 0) {
            return false;
        }
        Graphics2D g2 = graphicsOf(applet);
        if (g2 == null) {
            return false;
        }

        float w = img.width;
        float h = img.height;
        // 贴图坐标 → 像素坐标
        float su0 = u0 * w, sv0 = v0 * h;
        float su1 = u1 * w, sv1 = v1 * h;
        float su2 = u2 * w, sv2 = v2 * h;

        // 解仿射系数
        float det = (su1 - su0) * (sv2 - sv0) - (su2 - su0) * (sv1 - sv0);
        if (Math.abs(det) < 1e-6f) {
            return false; // 三点共线
        }
        float a = ((p1[0] - p0[0]) * (sv2 - sv0) - (p2[0] - p0[0]) * (sv1 - sv0)) / det;
        float b = ((p2[0] - p0[0]) * (su1 - su0) - (p1[0] - p0[0]) * (su2 - su0)) / det;
        float c = p0[0] - a * su0 - b * sv0;

        float d = ((p1[1] - p0[1]) * (sv2 - sv0) - (p2[1] - p0[1]) * (sv1 - sv0)) / det;
        float e = ((p2[1] - p0[1]) * (su1 - su0) - (p1[1] - p0[1]) * (su2 - su0)) / det;
        float f = p0[1] - d * su0 - e * sv0;

        // 屏幕三角形的包围盒 —— 用它把绘制范围限制住，
        // 否则 Graphics2D 会对整张图做变换，大图 + 多格会明显掉帧
        int minX = (int) Math.floor(Math.min(p0[0], Math.min(p1[0], p2[0])));
        int maxX = (int) Math.ceil(Math.max(p0[0], Math.max(p1[0], p2[0])));
        int minY = (int) Math.floor(Math.min(p0[1], Math.min(p1[1], p2[1])));
        int maxY = (int) Math.ceil(Math.max(p0[1], Math.max(p1[1], p2[1])));
        int bw = maxX - minX;
        int bh = maxY - minY;
        if (bw <= 0 || bh <= 0) {
            return false;
        }

        AffineTransform saved = g2.getTransform();
        java.awt.Shape savedClip = g2.getClip();
        try {
            g2.setClip(minX, minY, bw, bh);
            g2.translate(minX, minY);
            g2.transform(new AffineTransform(a, d, b, e, c - minX, f - minY));
            g2.drawImage(toAwt(applet, img), 0, 0, null);
            return true;
        } catch (RuntimeException ex) {
            return false;
        } finally {
            g2.setTransform(saved);
            g2.setClip(savedClip);
        }
    }

    /**
     * 取窗口的 Java2D 画布。
     *
     * @param applet 窗口
     * @return Graphics2D；渲染器不是 JAVA2D 时返回 {@code null}
     */
    public static Graphics2D graphicsOf(PApplet applet) {
        if (applet.g instanceof PGraphicsJava2D java2d) {
            return java2d.g2;
        }
        return null;
    }

    /**
     * 把 {@link PImage} 转成 AWT 图片。
     *
     * <p>Processing 的 {@code PImage} 内部就存着 {@code BufferedImage}，
     * 但字段是包内可见的，外部拿不到，只能通过 {@code getImage()}。
     * 这里用 {@link PImage#getImage()}；若返回空则自行转换一次并缓存到
     * {@code PImage} 的 {@code image} 字段（Processing 会自己懒加载）。
     *
     * @param applet 窗口
     * @param img    贴图
     * @return AWT 图片；转换失败返回 {@code null}
     */
    private static java.awt.Image toAwt(PApplet applet, PImage img) {
        java.awt.Image awt = img.getImage();
        if (awt != null) {
            return awt;
        }
        // 还没有底层 AWT 图：让 Processing 建一张（内部会调用 loadPixels 之类）
        img.loadPixels();
        return img.getImage();
    }

    /**
     * 当前渲染器是否支持本类的贴图方式。
     *
     * <p>窗口若用 P2D/P3D，请改用 Processing 自带的 {@code texture()}；
     * 本类只服务 JAVA2D。
     *
     * @param applet 窗口
     * @return 支持返回 {@code true}
     */
    public static boolean supported(PApplet applet) {
        return graphicsOf(applet) != null;
    }
}
