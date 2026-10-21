package com.yestercar.gameopening;

import com.yestercar.common.ui.DesignSpace;
import com.yestercar.common.util.UITheme;
import processing.core.PApplet;
import processing.core.PConstants;

/**
 * 子页面左上角的**返回箭头**。
 *
 * <h2>为什么它出现在子页面而不是主菜单</h2>
 * 主菜单那个位置是**档案面板**；进入子页面后档案收起来、换成返回箭头。
 * 这样左上角这个位置有明确归属：**主菜单是身份，子页面是返回**。
 * 子页面本来就该比主菜单更简洁 —— 这是常规做法，不是我们发明的。
 *
 * <h2>⚠️ 命中区比看得见的形状大</h2>
 * ak-ui 的 {@code design-language.md} 有一条硬要求：
 * "即使可见形状被剪裁，命中区域也要保持矩形且足够大"，
 * 并且触控目标至少 44×44。
 * 所以这里画的是一个 26pt 的箭头，但命中区是 **48×48**。
 */
final class BackArrow {

    /** 可见尺寸（设计坐标）。 */
    private static final float VISUAL = 26f;

    /** 命中区尺寸（设计坐标）—— 满足 44 的最小触控目标。 */
    private static final float HIT = 48f;

    private static final float MARGIN_X = 28f;
    private static final float MARGIN_Y = 30f;

    private final PApplet g;
    private final DesignSpace d;

    /** 鼠标是否停在命中区上（hover 时整块底会亮一点）。 */
    private boolean hovered;
    private boolean pressed;

    BackArrow(PApplet g, DesignSpace d) {
        this.g = g;
        this.d = d;
    }

    /** @return 命中区的矩形 {@code {x, y, w, h}}（设计坐标）。 */
    private float[] box() {
        return new float[]{MARGIN_X, MARGIN_Y, HIT, HIT};
    }

    /**
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 是否命中
     */
    boolean hit(float mouseX, float mouseY) {
        float[] b = box();
        return mouseX >= d.x(b[0]) && mouseX <= d.x(b[0] + b[2])
                && mouseY >= d.y(b[1]) && mouseY <= d.y(b[1] + b[3]);
    }

    void hover(float mouseX, float mouseY) {
        hovered = hit(mouseX, mouseY);
    }

    void press() {
        pressed = true;
    }

    void release() {
        pressed = false;
    }

    boolean isPressed() {
        return pressed;
    }

    void draw() {
        float[] b = box();
        float bx = d.x(b[0]);
        float by = d.y(b[1]);
        float bw = d.w(b[2]);
        float bh = d.h(b[3]);

        // 底：悬浮变亮、按下变暗 —— 与 ak-ui 的按钮三态同一套做法
        if (hovered || pressed) {
            g.noStroke();
            g.fill(255, 255, 255, pressed ? 0.10f * 255 : (hovered ? 0.07f * 255 : 0f));
            CutShape.square(g, bx, by, bw, bh);
        }

        // 箭头本身：两根线画出来，不依赖字体里有 ← 这个字形
        float cx = bx + bw / 2f;
        float cy = by + bh / 2f;
        float r = d.w(VISUAL) / 2f;

        g.stroke(pressed ? UITheme.MENU_ACCENT : UITheme.MENU_TEXT);
        g.strokeWeight(d.len(2.4f));
        g.line(cx + r * 0.55f, cy - r * 0.6f, cx - r * 0.45f, cy);
        g.line(cx - r * 0.45f, cy, cx + r * 0.55f, cy + r * 0.6f);
        g.noStroke();

        // 一条左侧竖条 —— 与 ak-ui 的 .ak-tag / .ak-panel 同一种"左边缘标线"手势
        g.fill(pressed ? UITheme.MENU_ACCENT : UITheme.SIGNAL_ACTION);
        g.rect(bx, by + bh * 0.22f, d.len(2.5f), bh * 0.56f);
    }

    /** @return 主菜单里显示档案、子页面显示返回 —— 供绘制层判断。 */
    static boolean isSubpagePosition(float designX, float designY) {
        return designX < MARGIN_X + HIT && designY < MARGIN_Y + HIT;
    }

    /**
     * 画一个"这是子页面"的标题，紧挨在返回箭头右侧。
     *
     * @param text 标题
     */
    void drawTitle(String text) {
        fontsTitle();
        g.textAlign(PConstants.LEFT, PConstants.CENTER);
        g.fill(UITheme.MENU_TEXT_DIM);
        float[] b = box();
        g.text(text, d.x(b[0] + b[2] + 10f), d.y(b[1] + b[3] / 2f));
    }

    private void fontsTitle() {
        // 标题用等宽的宽字距 —— ak-ui 的 __eyebrow 就是这种做法
        g.textSize(d.font(11f));
    }
}
