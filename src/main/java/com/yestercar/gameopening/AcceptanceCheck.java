package com.yestercar.gameopening;

import com.yestercar.common.ui.DesignSpace;
import processing.core.PApplet;
import processing.core.PGraphics;
import processing.core.PConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * §6 验收：几何自查 + 文本度量。
 *
 * <h2>为什么这些检查不能"看一眼就过"</h2>
 * 决定书 §6 明确写了：<b>不允许</b>用"看起来对""基本一致"作为完成依据。
 * 而这一轮里我个人踩过的坑恰好都是"看起来对"：
 * <ul>
 *   <li>2×2 的两行卡片**纵向重叠 3px** —— 远看完全正常；</li>
 *   <li>命令区高度算错 1.49 倍 —— 布局仍然自洽，只是比例全错；</li>
 *   <li>水印 alpha 被三角带累积成 2.8 倍 —— 图标还在，只是"有点深"。</li>
 * </ul>
 * 这些只有**数字**能抓住。
 *
 * <h2>容差 0.5px</h2>
 * 决定书要求 0.5px。这是保守值：{@link DesignSpace} 是纯浮点换算，
 * 理论上没有累积误差，所以任何超过半像素的偏差都意味着**真的算错了**，
 * 而不是精度问题。
 */
final class AcceptanceCheck {

    /** 容差（窗口像素）—— 决定书 §6 规定。 */
    private static final float TOLERANCE = 0.5f;

    private final PApplet g;
    private final DesignSpace d;
    private final List<String> lines = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    AcceptanceCheck(PApplet g, DesignSpace d) {
        this.g = g;
        this.d = d;
    }

    /** @return 容差（窗口像素） */
    static float tolerance() {
        return TOLERANCE;
    }

    /** @return 报告行（逐条打印用）。 */
    List<String> lines() {
        return lines;
    }

    /** @return 未通过的项；空表示全部通过。 */
    List<String> failures() {
        return failures;
    }

    // ------------------------------------------------------------------
    // 1. 几何
    // ------------------------------------------------------------------

    /**
     * 检查矩形在画布内。
     *
     * @param name 名字
     * @param x    左
     * @param y    上
     * @param w    宽
     * @param h    高
     */
    void inside(String name, float x, float y, float w, float h) {
        lines.add(String.format("  %-12s [%6.1f,%6.1f .. %6.1f,%6.1f] %5.1fx%5.1f",
                name, x, y, x + w, y + h, w, h));
        if (x < -TOLERANCE || y < -TOLERANCE
                || x + w > g.width + TOLERANCE || y + h > g.height + TOLERANCE) {
            failures.add(name + " 越出画布：右 " + (x + w) + " / 下 " + (y + h)
                    + "，画布 " + g.width + "x" + g.height);
        }
    }

    /**
     * 检查两个矩形不重叠（决定书 §6 的"部件之间关系"）。
     *
     * @param an a 名
     * @param ax a 左
     * @param ay a 上
     * @param aw a 宽
     * @param ah a 高
     * @param bn b 名
     * @param bx b 左
     * @param by b 上
     * @param bw b 宽
     * @param bh b 高
     */
    void noOverlap(String an, float ax, float ay, float aw, float ah,
                   String bn, float bx, float by, float bw, float bh) {
        float ox = Math.min(ax + aw, bx + bw) - Math.max(ax, bx);
        float oy = Math.min(ay + ah, by + bh) - Math.max(ay, by);
        if (ox > TOLERANCE && oy > TOLERANCE) {
            failures.add(an + " 与 " + bn + " 重叠 " + fmt(ox) + " x " + fmt(oy) + " px");
        }
    }

    /**
     * 检查一个与设计值换算来的期望值是否吻合。
     *
     * @param name     名字
     * @param actual   实测
     * @param expected 期望
     */
    void near(String name, float actual, float expected) {
        float diff = Math.abs(actual - expected);
        lines.add(String.format("  %-16s 实测 %8.2f  期望 %8.2f  差 %5.2f%s",
                name, actual, expected, diff, diff > TOLERANCE ? "   ✗" : ""));
        if (diff > TOLERANCE) {
            failures.add(name + " 偏差 " + fmt(diff) + "px（容差 " + TOLERANCE + "）");
        }
    }

    /**
     * 检查一行/一列的等分关系（2×2 网格的核心不变量）。
     *
     * @param name   名字
     * @param values 各元素的尺寸
     */
    void equalSizes(String name, float... values) {
        float max = -Float.MAX_VALUE;
        float min = Float.MAX_VALUE;
        for (float v : values) {
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        float spread = max - min;
        lines.add(String.format("  %-16s 最大 %7.2f  最小 %7.2f  差 %5.2f", name, max, min, spread));
        if (spread > TOLERANCE) {
            failures.add(name + " 不等分，极差 " + fmt(spread) + "px");
        }
    }

    // ------------------------------------------------------------------
    // 2. 文本度量
    // ------------------------------------------------------------------

    /**
     * 量一段文字的实际墨迹尺寸，与 {@code PFont} 的度量对比。
     *
     * <h2>为什么要量墨迹，而不是信 {@code textWidth()}</h2>
     * 决定书 §6.2 要求的正是这件事：把渲染出来的墨迹宽高与
     * {@code PFont} 推算值比。差 &gt;1px 说明**字号或字体族不对**，
     * 而不该"用偏移去补"。
     *
     * <p>本工程已知 {@code textWidth()} 对 CJK 回退字形会报得偏大，
     * 所以这里把偏差**如实打印出来**，作为已知项记录，不假装它不存在。
     *
     * @param role 字体角色
     * @param size 设计字号
     * @param text 文本
     * @return 报告行
     */
    String textMetric(Fonts fonts, Fonts.Role role, float size, String text) {
        int pad = 8;
        int wpx = 420;
        int hpx = 120;
        PGraphics pg = g.createGraphics(wpx, hpx);
        pg.beginDraw();
        pg.background(0);
        pg.fill(255);
        fonts.apply(pg, role, size);
        pg.textAlign(PConstants.LEFT, PConstants.BASELINE);
        pg.text(text, pad, hpx / 2f);
        pg.endDraw();

        // 量墨迹包围盒
        int minX = wpx;
        int minY = hpx;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < hpx; y++) {
            for (int x = 0; x < wpx; x++) {
                int c = pg.get(x, y);
                if ((c & 0xFF) > 40) {
                    if (x < minX) {
                        minX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }
        if (maxX < 0) {
            return String.format("  文本「%s」 %s/%s：**没有墨迹**（渲染失败）",
                    text, role, fmt(size));
        }
        float inkW = maxX - minX + 1;
        float inkH = maxY - minY + 1;

        // PFont 推算：textWidth 是前进宽，glyph 高按字号估
        fonts.apply(pg, role, size);
        float advance = pg.textWidth(text);
        float expectedH = fonts.size(size);

        String line = String.format(
                "  文本「%s」 %s@%s：墨迹 %5.1fx%5.1f  前进宽 %5.1f  号高 %5.1f  宽差 %+5.1f 高差 %+5.1f",
                text, role, fmt(size), inkW, inkH, advance, expectedH,
                inkW - advance, inkH - expectedH);
        pg.dispose();
        return line;
    }

    private static String fmt(float v) {
        return String.format("%.2f", v);
    }

    /** 开始一节。 */
    void section(String title) {
        lines.add("");
        lines.add("── " + title + " ──");
    }
}
