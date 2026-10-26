package com.yestercar.common.ui;

import processing.core.PApplet;

/**
 * 界面设计坐标系：把「设计稿上量到的数字」换算到实际窗口。
 * <h2>为什么需要它</h2>
 * 主菜单的版式是照着 ak-ui 的仪表盘例子搭的，而那份例子的设计基准是
 * <strong>1366 × 653</strong>（它的容器尺寸）。我们的游戏窗口是
 * <strong>1280 × 720</strong> —— 两个比例不同，直接把数字抄过来会走形。
 * <p>所以约定：<b>所有布局数值都按设计基准写</b>，
 * 由本类换算成窗口坐标。这样"照着抄"这件事才是可行的，
 * 而且将来改窗口尺寸不用回头改几十处坐标。
 * <h2>为什么不是 2D 缩放矩阵</h2>
 * 曾经的设想是"设计坐标系 + 缩放矩阵"，但两个基准的<strong>宽高比不同</strong>
 * （1366:653 ≈ 2.09，1280:720 ≈ 1.78），单一缩放因子必然把圆压成椭圆、
 * 把方块拉成长条。所以这里用的是 {@link #sx} / {@link #sy} 两个独立因子 ——
 * 对"位置"是对的，对"尺寸"要小心：
 * <ul>
 * <li><b>位置</b>用 {@link #x}/{@link #y}（两轴各自换算）；</li>
 * <li><b>正方块、圆角、线宽</b>用 {@link #len}（按较小的那个因子等比，保证不变形）；</li>
 * <li><b>纯横向的宽度</b>用 {@link #w}，<b>纯纵向的高度</b>用 {@link #h}。</li>
 * </ul>
 * <h2>ak-ui 的三个相对量</h2>
 * 那份 CSS 里大量用 {@code cqw}/{@code cqh}（容器宽/高的 1%）。
 * 在设计基准下：{@code 1cqw = 13.66px}、{@code 1cqh = 6.53px}。
 * 本类把它们做成 {@link #cqw}/{@link #cqh}，照着 CSS 抄就更直接。
 */
public final class DesignSpace {

    /** ak-ui 仪表盘例子的设计基准宽（容器宽）。 */
    public static final float BASE_W = 1440f;

    /** ak-ui 仪表盘例子的设计基准高（容器高）。 */
    public static final float BASE_H = 810f;

    /** 统一缩放（取 min），保证**文字不会被非等比拉伸**。 */
    private final float sx;
    private final float sy;

    /** 居中偏移：窗口比例不是 16:9 时，设计区在窗口中居中（左右或上下留边）。 */
    private final float ox;
    private final float oy;

    /**
 * @param windowW 实际窗口宽
 * @param windowH 实际窗口高
 */
    public DesignSpace(int windowW, int windowH) {
 // ⚠️ 以前横纵各算各的（sx≠sy），宽屏下文字会被**横向拉伸**、
 // 竖屏下被压扁。这里改成统一缩放 + 居中留边。
        float s = Math.min(windowW / BASE_W, windowH / BASE_H);
        this.sx = s;
        this.sy = s;
        this.ox = (windowW - BASE_W * s) / 2f;
        this.oy = (windowH - BASE_H * s) / 2f;
    }

    /**
 * @return 横向缩放因子
 */
    public float sx() {
        return sx;
    }

    /**
 * @return 纵向缩放因子
 */
    public float sy() {
        return sy;
    }

    /**
 * 设计 x → 窗口 x。
 * @param designX 设计稿上的横向坐标
 * @return 窗口坐标
 */
    public float x(float designX) {
        return ox + designX * sx;
    }

    /**
 * 设计 y → 窗口 y。
 * @param designY 设计稿上的纵向坐标
 * @return 窗口坐标
 */
    public float y(float designY) {
        return oy + designY * sy;
    }

    /**
 * 纯横向长度（宽度、右边的 x 偏移）。
 * @param designLen 设计稿上的长度
 * @return 窗口长度
 */
    public float w(float designLen) {
        return designLen * sx;
    }

    /**
 * 纯纵向长度（高度）。
 * @param designLen 设计稿上的长度
 * @return 窗口长度
 */
    public float h(float designLen) {
        return designLen * sy;
    }

    /**
 * 各向同性的长度（圆角、线宽、方形图标）。
 * <p>取两个因子里<b>较小</b>的那个：宁可略小也不越界 ——
 * 用大的那个会让方块在窄方向上溢出。
 * @param designLen 设计稿上的长度
 * @return 窗口长度
 */
    public float len(float designLen) {
        return designLen * Math.min(sx, sy);
    }

    /**
 * 容器宽的百分比（对应 ak-ui 的 {@code cqw}）。
 * @param percent 百分比数值，例如 {@code 15} 表示 15%
 * @return 窗口像素
 */
    public float cqw(float percent) {
        return x(BASE_W) * percent / 100f;
    }

    /**
 * 容器高的百分比（对应 ak-ui 的 {@code cqh}）。
 * @param percent 百分比数值，例如 {@code 20} 表示 20%
 * @return 窗口像素
 */
    public float cqh(float percent) {
        return y(BASE_H) * percent / 100f;
    }

    /**
 * 把设计稿上的比例直接换算成窗口像素（用于"某方向占 N%"这类布局）。
 * @param designFraction 设计稿上的比例值（0~1）
 * @return 窗口像素
 */
    public float wFraction(float designFraction) {
        return x(BASE_W) * designFraction;
    }

    /**
 * 按宽度自适应字号。
 * <p>⚠️ 字号只按<b>横向</b>因子缩：中文以方块字为主，行高由字号决定，
 * 两轴各缩一次会让同一段文字在不同窗口尺寸下行距不一致。
 * @param designSize 设计稿上的字号
 * @return 窗口字号
 */
    public float font(float designSize) {
        return designSize * sx;
    }

    /**
 * 画一条"设计坐标下"的矩形。
 * @param g 画笔
 * @param designX 设计 x
 * @param designY 设计 y
 * @param designW 设计宽
 * @param designH 设计高
 */
    public void rect(PApplet g, float designX, float designY,
                     float designW, float designH) {
        g.rect(x(designX), y(designY), w(designW), h(designH));
    }

    /**
 * 在"设计坐标下"画一段文字（左下角为锚点）。
 * @param g 画笔
 * @param text 文字
 * @param designX 设计 x
 * @param designY 设计 y（基线）
 */
    public void text(PApplet g, String text, float designX, float designY) {
        g.text(text, x(designX), y(designY));
    }

    /**
 * 把窗口坐标反查回设计坐标的 x（命中判定用）。
 * @param windowX 窗口 x
 * @return 设计稿上的 x
 */
    public float toDesignX(float windowX) {
        return sx == 0f ? 0f : (windowX - ox) / sx;
    }

    /**
 * 把窗口坐标反查回设计坐标的 y。
 * @param windowY 窗口 y
 * @return 设计稿上的 y
 */
    public float toDesignY(float windowY) {
        return sy == 0f ? 0f : (windowY - oy) / sy;
    }

    @Override
    public String toString() {
        return String.format("DesignSpace[基准 %.0fx%.0f  缩放 %.4f x %.4f  "
                        + "→ 窗口 %.0fx%.0f]",
                BASE_W, BASE_H, sx, sy, x(BASE_W), y(BASE_H));
    }
}
