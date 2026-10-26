package com.yestercar.common.ui;

import com.yestercar.common.util.UITheme;
import processing.core.PApplet;
import processing.core.PConstants;

/**
 * 一个按钮的几何 + 状态 + 绘制 + 命中判定。
 * <h2>为什么要有这个类</h2>
 * 原来每个按钮都是"就地画一个矩形"，于是：
 * 画的地方和判定命中的地方各算一套坐标（容易对不上）；
 * 没有悬浮/按下反馈；鼠标移上去不变手形。
 * 收成一个类之后，<strong>绘制与判定用的是同一个 {@link #box}</strong>，不可能对不上。
 * <h2>三态</h2>
 * <table border="1">
 * <caption>按钮配色</caption>
 * <tr><th>种类</th><th>常态</th><th>悬浮</th><th>按下</th></tr>
 * <tr><td>普通</td><td>白</td><td>{@code #C3C3C3}</td><td>{@code #7F7F7F}</td></tr>
 * <tr><td>主按钮（{@code primary}）</td><td>{@code #FFF200}</td><td>{@code #FFC90E}</td><td>{@code #F1C644}</td></tr>
 * <tr><td>选中态（{@code active}）</td><td colspan="3">蓝色实底，不随鼠标变化</td></tr>
 * </table>
 * <p>主按钮不转灰：它承担"这是我该点的那个"的视觉引导，转成灰阶就和其他按钮一样了。
 * 选中态也不变：灰阶表达不了"选中"，而"哪个地形被选中"是必须一眼看出的信息。
 * <h2>按下状态是怎么来的</h2>
 * Processing 不提供"鼠标在按钮上按住"这个事件，所以由调用方在
 * {@code mousePressed} 里调 {@link #press}、在 {@code mouseReleased} 里调 {@link #releaseAll}。
 * 见 {@code EditorApp}。
 */
public final class UiButton {

    private final PApplet applet;

    /** 矩形：{@code x, y, w, h}。 */
    private float x;
    private float y;
    private float w;
    private float h;

    /** 按钮文字。 */
    private String label = "";

    /** 是否当前被鼠标按住。 */
    private boolean pressed;

    /**
 * @param applet 所属窗口
 */
    public UiButton(PApplet applet) {
        this.applet = applet;
    }

    /**
 * 设置位置与尺寸。
 * @param x 左边界
 * @param y 上边界
 * @param w 宽
 * @param h 高
 * @return 自身，便于链式调用
 */
    public UiButton at(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        return this;
    }

    /**
 * 设置文字。
 * @param text 按钮文字
 * @return 自身，便于链式调用
 */
    public UiButton text(String text) {
        this.label = text == null ? "" : text;
        return this;
    }

    /**
 * 标记为「被按下」。由调用方在 {@code mousePressed} 里命中时调用。
 */
    public void press() {
        pressed = true;
    }

    /**
 * 清除按下状态。由调用方在 {@code mouseReleased} 里调用。
 */
    public void release() {
        pressed = false;
    }

    /**
 * @return 是否处于按下状态
 */
    public boolean isPressed() {
        return pressed;
    }

    /**
 * 鼠标是否落在按钮上。
 * <p>⚠️ <strong>尺寸为 0 的按钮谁也命中不了</strong>：按钮是先建出来、每帧才摆位的，
 * 若某帧忘了摆位它的矩形就是 {@code (0,0,0,0)}，而
 * {@code 0 >= 0 && 0 <= 0 + 0} 恰好成立 —— 那样它会**在屏幕原点误吃点击**。
 * 这个边界是单元测试抓出来的，不是构造出来的假想。
 * @param mouseX 鼠标 x
 * @param mouseY 鼠标 y
 * @return 命中返回 {@code true}
 */
    public boolean isHovered(float mouseX, float mouseY) {
        if (w <= 0f || h <= 0f) {
            return false;
        }
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    /**
 * 绘制按钮。
 * @param mouseX 鼠标 x（判悬浮）
 * @param mouseY 鼠标 y
 * @param active 是否选中态（蓝底，不随鼠标变）
 * @param primary 是否主按钮（黄底三态）
 * @param enabled 是否可用；{@code false} 时画成灰字，且不响应鼠标
 */
    public void draw(float mouseX, float mouseY, boolean active, boolean primary,
                     boolean enabled) {
        boolean hovered = enabled && isHovered(mouseX, mouseY);
        boolean down = enabled && pressed && hovered;

        int face;
        if (active) {
            face = UITheme.COLOR_PRIMARY;
        } else if (primary) {
            face = down ? UITheme.BTN_PRIMARY_FACE_PRESSED
                    : hovered ? UITheme.BTN_PRIMARY_FACE_HOVER
                    : UITheme.BTN_PRIMARY_FACE;
        } else {
            face = down ? UITheme.BTN_FACE_PRESSED
                    : hovered ? UITheme.BTN_FACE_HOVER
                    : UITheme.BTN_FACE;
        }

 // 按下时整体下移 1px：比只换颜色更像"真的按下去了"
        float dy = down ? 1f : 0f;

        applet.noStroke();
        applet.fill(face);
        applet.rect(x, y + dy, w, h, UITheme.RADIUS_SUBTLE);

 // 描边：选中/主按钮用主色，其余用分隔线色
        applet.noFill();
        applet.stroke(active || primary ? UITheme.COLOR_PRIMARY : UITheme.LINE_ON_LIGHT);
        applet.strokeWeight(1f);
        applet.rect(x, y + dy, w, h, UITheme.RADIUS_SUBTLE);
        applet.noStroke();

        int textColor;
        if (!enabled) {
            textColor = UITheme.COLOR_LOW;
        } else if (active) {
            textColor = 0xFFFFFFFF;
        } else {
            textColor = UITheme.TEXT_ON_LIGHT;
        }
        applet.fill(textColor);
        applet.textAlign(PConstants.CENTER, PConstants.CENTER);
        applet.text(label, x + w / 2f, y + dy + h / 2f);
        applet.textAlign(PConstants.LEFT, PConstants.BASELINE);
    }

 // ------------------------------------------------------------------
 // 几何读取
 // ------------------------------------------------------------------

    /**
 * @return 按钮文字
 */
    public String text() {
        return label;
    }

    /** @return 左边界 */
    public float x() {
        return x;
    }

    /** @return 上边界 */
    public float y() {
        return y;
    }

    /** @return 宽 */
    public float width() {
        return w;
    }

    /** @return 高 */
    public float height() {
        return h;
    }

    /** @return 下边界 */
    public float bottom() {
        return y + h;
    }

    /**
 * @return 是否为「可以变手形光标」的按钮。
 * <p>只要它是绘出来给用户点的就算 —— 是否可用（{@code enabled}）由调用方自己判断。
 * 光标是"这里能点"的信号，不该因为"这次点不了"就消失。
 */
    public boolean isClickableShape() {
        return w > 0 && h > 0;
    }
}
