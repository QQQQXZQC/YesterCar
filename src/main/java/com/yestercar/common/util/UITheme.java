package com.yestercar.common.util;

import g4p_controls.G4P;
import g4p_controls.GAlign;
import g4p_controls.GCScheme;
import g4p_controls.GLabel;
import processing.core.PApplet;

/**
 * 四个模块窗口的统一外观入口：配色、中文字体、像素密度、控件工厂。
 *
 * <p>每个 {@code PApplet} 的 {@code setup()} 里调用一次 {@link #apply(PApplet)} 即可。
 *
 * <h2>配色</h2>
 * G4P 内置 8 套配色方案（{@code GConstants} 中的 {@code *_SCHEME} 常量）。
 * 本主题默认选用 {@link #DEFAULT_SCHEME}，其第 12 号索引即窗口底色
 * {@code #202020}——配合第 8~11 号索引的近白色文字，在深色底上对比度是够的。
 *
 * <p>下表是从 {@code third_party/G4P/src/data/default_gui_palette.png} 实测提取的
 * 默认调色板数值，供后续精调配色时参考（16 个索引从左到右）：
 *
 * <pre>
 * idx :   0        1        2        3        4        5        6        7
 * RED : D60000   9C2929   8C0000   EF3939   EF6B6B   F7ABAB   FBD7D7   FFFFFF
 * BLUE: 1A1EB2   303285   080B74   4E51D8   7375D8   C9CEFF   E3E6FF   FFFFFF
 *
 * idx :   8        9        10       11       12       13       14       15
 * BLUE: FFFFFF   FFFFFF   FFFFFF   FFFFFF   202020   FFFFFF   FFCE40   A67B00
 * </pre>
 *
 * <p>第 8~11 号索引在所有内置方案里都是白色，第 12 号都是 {@code #202020}，
 * 因此「深色底 + 浅色文字」这一组合无需自定义调色板即可成立。
 *
 * <h2>G4P 的三个已知坑（本类负责统一规避）</h2>
 * <ol>
 *   <li>{@code pixelDensity} 默认为 2，在 2K/4K 屏上画布会翻倍——用
 *       {@link #disablePixelDensityScaling(PApplet)} 压成 1（<strong>只能在
 *       {@code settings()} 内调用</strong>）；</li>
 *   <li>{@code GLabel} 默认色在深色背景下对比度不足——用 {@link #label} 创建；</li>
 *   <li>中文字体必须显式注入，否则控件显示方块——见 {@link #apply(PApplet)}。</li>
 * </ol>
 */
public final class UITheme {

    /** 默认配色方案编号，{@code GConstants.BLUE_SCHEME}。 */
    public static final int DEFAULT_SCHEME = GCScheme.BLUE_SCHEME;

    /**
     * 中文字体族名。
     *
     * <p>本机实测可用的中文字体：{@code Microsoft YaHei}、{@code Microsoft YaHei UI}、
     * {@code SimHei}、{@code SimSun}、{@code DengXian}、{@code KaiTi}、{@code FangSong}。
     * G4P 找不到指定字体会静默回退到 Arial，中文就会变方块。
     */
    public static final String FONT_FAMILY = "Microsoft YaHei";

    /** 正文字号。 */
    public static final int FONT_SIZE = 14;

    /** 标题字号。 */
    public static final int TITLE_SIZE = 22;

    /** 窗口底色，取自调色板第 12 号索引。 */
    public static final int COLOR_WINDOW_BG = 0xFF202020;

    /** 主文字色。 */
    public static final int COLOR_TEXT = 0xFFF0F0F0;

    /** 次要文字色（状态栏、提示）。 */
    public static final int COLOR_TEXT_DIM = 0xFFA0A0A0;

    /** 强调色，取自 BLUE 方案第 14 号索引。 */
    public static final int COLOR_ACCENT = 0xFFFFCE40;

    /** 网格线颜色。 */
    public static final int COLOR_GRID_LINE = 0xFF505050;

    private static boolean applied = false;

    private UITheme() {
        // 工具类，不允许实例化
    }

    /**
     * 把一个 {@code PApplet} 窗口纳入统一主题。
     *
     * <p>在 {@code setup()} 的最前面调用，必须在创建任何 G4P 控件之前——
     * G4P 的字体设置只影响「调用之后创建」的控件。
     *
     * <p><strong>注意</strong>：本方法不设置 {@code pixelDensity}。
     * 实测在 Maven 工程（非 PDE）下，{@code pixelDensity()} 在 {@code setup()} 里调用会抛
     * {@code IllegalStateException: pixelDensity() cannot be used here} 并直接打断渲染循环。
     * 它只能在 {@code settings()} 里调用，因此每个窗口类必须自己写：
     * <pre>
     * &#64;Override
     * public void settings() {
     *     size(W, H, JAVA2D);
     *     UITheme.disablePixelDensityScaling(this);   // 必须在 settings() 内
     * }
     * </pre>
     *
     * @param applet 目标窗口
     */
    public static void apply(PApplet applet) {
        // 全局配色
        G4P.setGlobalColorScheme(DEFAULT_SCHEME);

        // 中文字体注入（displayFont 影响按钮/标签/下拉框，inputFont 影响输入框）
        G4P.setDisplayFont(FONT_FAMILY, G4P.PLAIN, FONT_SIZE);
        G4P.setInputFont(FONT_FAMILY, G4P.PLAIN, FONT_SIZE);
        G4P.setSliderFont(FONT_FAMILY, G4P.BOLD, FONT_SIZE);

        applied = true;
    }

    /**
     * 把像素密度压到 1，规避 Processing 4 默认 {@code pixelDensity(2)} 造成的画布翻倍。
     *
     * <p>实测：2K/4K 屏上 660×440 的窗口实际画布是 1320×880，截屏、坐标换算、
     * 字号都会出现 2 倍偏差。
     *
     * <p><strong>必须在 {@code settings()} 内调用</strong>，在 {@code setup()} 里调用会抛
     * {@code IllegalStateException}。见 {@link #apply(PApplet)} 的说明。
     *
     * @param applet 目标窗口
     */
    public static void disablePixelDensityScaling(PApplet applet) {
        applet.pixelDensity(1);
    }

    /**
     * @return 是否已经调用过 {@link #apply(PApplet)}
     */
    public static boolean isApplied() {
        return applied;
    }

    /**
     * 创建一个对比度达标的中文标签。
     *
     * <p>G4P 的 {@code GLabel} 在深色背景下默认色偏暗，因此本方法显式设置局部配色方案，
     * 保证文字可读。项目内一律用它创建标签，不要直接 {@code new GLabel(...)}。
     *
     * @param applet 所属窗口
     * @param x      x 坐标
     * @param y      y 坐标
     * @param width  宽度
     * @param height 高度
     * @param text   显示文字
     * @return 已设色的标签控件
     */
    public static GLabel label(PApplet applet, float x, float y, float width, float height, String text) {
        GLabel label = new GLabel(applet, x, y, width, height, text);
        // 显式指定配色方案，规避深色背景下标签对比度不足的问题
        label.setLocalColorScheme(DEFAULT_SCHEME);
        label.setTextAlign(GAlign.LEFT, GAlign.MIDDLE);
        return label;
    }

    /**
     * 画一个标准的窗口底。
     *
     * @param applet 目标窗口
     */
    public static void background(PApplet applet) {
        applet.background(COLOR_WINDOW_BG);
    }

    /**
     * @return 面向用户展示的主题摘要，供启动日志使用
     */
    public static String describe() {
        return "主题=" + (applied ? "已应用" : "未应用")
                + " | 配色方案=" + DEFAULT_SCHEME
                + " | 字体=" + FONT_FAMILY + " " + FONT_SIZE + "pt";
    }
}
