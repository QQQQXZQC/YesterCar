package com.yestercar.gameopening;

import processing.core.PApplet;
import processing.core.PConstants;

/**
 * ak-ui 的**几何手势**工具箱。
 *
 * <h2>ak-ui 用哪几种剪裁</h2>
 * 全仓 {@code clip-path} 归纳下来是三组，本类各给一个方法：
 *
 * <table border="1">
 *   <caption>几何手势</caption>
 *   <tr><th>手势</th><th>原文</th><th>用在哪</th></tr>
 *   <tr><td>切一个角</td>
 *       <td>{@code polygon(0 0, calc(100% - cut) 0, 100% cut, 100% 100%, 0 100%)}</td>
 *       <td>面板、卡片 —— <b>右上角</b>斜切，是最常见的那个</td></tr>
 *   <tr><td>斜底标签</td>
 *       <td>{@code polygon(0 0, 100% 0, 100% 80%, 0 100%)}</td>
 *       <td>小小的眉标（eyebrow）—— 下缘斜切，像贴上去的纸条</td></tr>
 *   <tr><td>直角</td>
 *       <td>{@code border-radius: 0}</td>
 *       <td>命令卡<b>刻意不用圆角</b>。这是它的性格，不是遗漏</td></tr>
 * </table>
 *
 * <p>⚠️ 一条设计原则：<strong>不要每个边都切</strong>。ak-ui 的
 * {@code design-language.md} 把"每个元素都剪裁、倾斜、编号"列为典型失败模式。
 * 所以本类只提供这三种，用到哪几个由调用方克制地决定。
 *
 * <p>另外注意 {@code design-language.md} 的硬要求：
 * <strong>即使可见形状是斜切的，命中区域也要保持矩形且够大</strong>。
 * 所以本类的形状方法只管画，命中判定一律用未切过的矩形 ——
 * 那条规则在 {@code CommandCard.hit()} 里落实。
 */
final class CutShape {

    private CutShape() {
    }

    /**
     * 右上角切一刀的矩形（ak-ui 最常见的那个手势）。
     *
     * @param g     画布
     * @param x     左上 x
     * @param y     左上 y
     * @param w     宽
     * @param h     高
     * @param cut   切角边长
     */
    static void cutTopRight(PApplet g, float x, float y, float w, float h, float cut) {
        float c = Math.min(cut, Math.min(w, h) * 0.5f);
        g.beginShape();
        g.vertex(x, y);
        g.vertex(x + w - c, y);
        g.vertex(x + w, y + c);
        g.vertex(x + w, y + h);
        g.vertex(x, y + h);
        g.endShape(PConstants.CLOSE);
    }

    /**
     * 下缘斜切的标签（眉标）。
     *
     * @param g     画布
     * @param x     左上 x
     * @param y     左上 y
     * @param w     宽
     * @param h     高
     * @param drop  右下角比左下角低多少；负值则相反
     */
    static void slantedLabel(PApplet g, float x, float y, float w, float h, float drop) {
        g.beginShape();
        g.vertex(x, y);
        g.vertex(x + w, y);
        g.vertex(x + w, y + h - drop);
        g.vertex(x, y + h);
        g.endShape(PConstants.CLOSE);
    }

    /**
     * 四角直角矩形。
     *
     * <p>单独给一个方法而不是直接用 {@code rect()}，是为了让"这里刻意不圆角"
     * 在代码里看得见 —— 否则下一个人会顺手加上圆角。
     *
     * @param g 画布
     * @param x 左上 x
     * @param y 左上 y
     * @param w 宽
     * @param h 高
     */
    static void square(PApplet g, float x, float y, float w, float h) {
        g.rect(x, y, w, h);
    }

    /**
     * 一根水平的信号条（ak-ui 的 {@code ::before}）。
     *
     * <p>{@code scaleX(0) → scaleX(1)}，{@code transform-origin: left} ——
     * 所以是从左往右长出来的，不是从中间展开。这个方向感是刻意的。
     *
     * @param g      画布
     * @param x      左端 x
     * @param y      上边 y
     * @param w      满宽
     * @param h      高
     * @param scaleX 0~1 的伸展比例
     */
    static void signalBar(PApplet g, float x, float y, float w, float h, float scaleX) {
        float s = Math.max(0f, Math.min(1f, scaleX));
        if (s <= 0f || w <= 0f || h <= 0f) {
            return;
        }
        g.rect(x, y, w * s, h);
    }

    /**
     * 内缩一圈的细边框（ak-ui 的 {@code ::after}）。
     *
     * <p>这些组件都用 {@code inset: N} 画一圈**内**边框而不是 {@code border} ——
     * 因为 {@code clip-path} 会把边框一起切掉，内缩的描边则不受影响。
     *
     * @param g      画布
     * @param x      外框左
     * @param y      外框上
     * @param w      外框宽
     * @param h      外框高
     * @param inset  内缩量
     */
    static void insetStroke(PApplet g, float x, float y, float w, float h, float inset) {
        float iw = w - inset * 2f;
        float ih = h - inset * 2f;
        if (iw <= 0f || ih <= 0f) {
            return;
        }
        g.rect(x + inset, y + inset, iw, ih);
    }
}
