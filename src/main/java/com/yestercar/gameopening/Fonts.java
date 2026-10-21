package com.yestercar.gameopening;

import com.yestercar.common.util.UITheme;
import processing.core.PApplet;
import processing.core.PFont;

/**
 * ak-ui 的三个**字体角色**。
 *
 * <h2>为什么不是"一个字体走天下"</h2>
 * ak-ui 把字体当**结构**用，不是当装饰：
 *
 * <table border="1">
 *   <caption>三个角色</caption>
 *   <tr><th>角色</th><th>ak-ui 的用法</th><th>我们对应到什么</th></tr>
 *   <tr><td>command（衬线）</td>
 *       <td>命令与标题。{@code font-weight:900} + {@code letter-spacing:-0.08em}
 *           —— 又重又紧，是画面上的视觉锚点</td>
 *       <td>{@code Serif} 逻辑字体</td></tr>
 *   <tr><td>sans（无衬线）</td>
 *       <td>正文与控件标签</td>
 *       <td>项目已有的中文字体解析（{@code UITheme}）</td></tr>
 *   <tr><td>mono（等宽）</td>
 *       <td>标识符、遥测、对齐的数值。{@code letter-spacing:0.06em~0.12em}
 *           —— 宽字距让它读起来像仪表盘</td>
 *       <td>{@code Monospaced} 逻辑字体</td></tr>
 * </table>
 *
 * <p>⚠️ <strong>不引入新字体文件</strong>：用 Processing 自带的逻辑字体
 * （{@code Serif} / {@code SansSerif} / {@code Monospaced}），
 * 平台会解析到真实字体，中文由系统回退覆盖。
 * 引新字体文件意味着许可、体积、以及"没装就退化"三件事，
 * 而我们现在只需要"衬线 / 无衬线 / 等宽"这个**对比关系**成立。
 *
 * <h2>字号机制（TASKS-ROUND §1）</h2>
 * 汉字由 Processing 在 {@code createFont} 时**按某个像素尺寸栅格化一次**；
 * 之后用 {@code textSize()} 改大小，只是把那张位图拉伸 —— 放大变糊、缩小发虚。
 *
 * <p>所以本类改为：**每个用到的像素字号都单独建一个 PFont 并缓存**。
 * {@link #apply} 拿到设计字号后先换算成最终像素、取整、查缓存，
 * 命不中才 {@code createFont(family, px, true)}。**全程不调 textSize** ——
 * 字体本身就是那个尺寸，绘制时是 1:1 出图。
 *
 * <p>缓存规模是有界的：主菜单用到的不同字号约 20 个 × 3 个角色 = 60 个 PFont，
 * 建一次就一直在。相比"每帧 textSize 拉伸位图"，这是用一点内存换清晰度。
 */
final class Fonts {

    private final PApplet applet;
    private final float sx;

    /** 字体族名（按角色）。 */
    private final String commandFamily;
    private final String sansFamily;
    private final String monoFamily;

    /**
     * 已创建的字体：key = {@code 角色@像素字号}。
     *
     * <p>为什么要缓存：{@code createFont} 会现场栅格化整套字形，很贵；
     * 而同一帧里很多文字用同一个字号。
     */
    private final java.util.Map<String, PFont> cache = new java.util.HashMap<>();

    /**
     * @param g      画布
     * @param design 设计坐标系（取横向因子换算字号）
     */
    Fonts(PApplet g, com.yestercar.common.ui.DesignSpace design) {
        this.applet = g;
        this.sx = design.sx();
        this.commandFamily = "Serif";
        this.sansFamily = UITheme.resolvedFontFamily();
        this.monoFamily = "Monospaced";
    }

    /**
     * 取某个角色在某个**像素字号**下的字体（按需创建 + 缓存）。
     *
     * @param role 角色
     * @param px   像素字号（已取整）
     * @return 该尺寸的 PFont
     */
    private PFont get(Role role, int px) {
        String key = role + "@" + px;
        PFont f = cache.get(key);
        if (f != null) {
            return f;
        }
        String family = switch (role) {
            case COMMAND -> commandFamily;
            case SANS -> sansFamily;
            case MONO -> monoFamily;
        };
        f = applet.createFont(family, px, true);
        cache.put(key, f);
        return f;
    }

    /**
     * 切换角色**并**按设计字号设字体（TASKS-ROUND §1）。
     *
     * <p>换算链：设计字号 × {@code sx} → 取整 → 取一个**该像素尺寸**的 PFont。
     * 不调用 {@code textSize()}：字体已经在最终尺寸上栅格化过，
     * 再用 textSize 就变成拉伸位图了。
     *
     * @param g          画布
     * @param role       角色
     * @param designSize 设计稿上的字号
     */
    void apply(PApplet g, Role role, float designSize) {
        g.textFont(get(role, pixelSize(designSize)));
    }

    /**
     * {@code PGraphics} 版本 —— 给离屏绘制（验收量文本、生成图标）用。
     *
     * @param g          离屏画布
     * @param role       角色
     * @param designSize 设计字号
     */
    void apply(processing.core.PGraphics g, Role role, float designSize) {
        g.textFont(get(role, pixelSize(designSize)));
    }

    /**
     * 设计字号 → 最终像素字号（取整，且不小于 6）。
     *
     * @param designSize 设计字号
     * @return 像素字号
     */
    int pixelSize(float designSize) {
        return Math.max(6, Math.round(designSize * sx));
    }

    /**
     * 把设计字号换算成窗口像素。
     *
     * @param designSize 设计稿上的字号
     * @return 窗口字号
     */
    float size(float designSize) {
        return pixelSize(designSize);
    }

    /**
     * 画一行**带字距**的文字（决定书 D6）。
     *
     * <h2>为什么只能逐字画</h2>
     * CSS 的 {@code letter-spacing} 由排版引擎处理；Processing 的 {@code PFont}
     * **不提供字距控制**。唯一的办法是自己逐字推进：
     * 画一个字 → 用 {@code textWidth} 量它的宽 → 加上字距 → 定下一个字的位置。
     *
     * <h2>⚠️ 只用于标题类短文本</h2>
     * 逐字画的代价：
     * <ul>
     *   <li>每个字独立定位，{@code textWidth} 对 CJK 回退字形**报得偏大**，
     *       累加误差会让长句越画越散；</li>
     *   <li>无法做跨字的连字/标点挤压；</li>
     *   <li>字多了性能也差（每字一次 text 调用）。</li>
     * </ul>
     * 所以只给代号、栏目名这类**几个字**的标题用。正文一律普通 {@code text()}。
     *
     * @param g       画布
     * @param text    文本
     * @param x       起始左边缘（窗口坐标）
     * @param baseline 基线 y（窗口坐标）
     * @param spacing 字距（窗口像素）
     * @param align   水平对齐：{@code PConstants.LEFT} 或 {@code CENTER}
     * @return 画完之后的总宽度（窗口像素），便于在右侧接别的东西
     */
    float spacedText(PApplet applet, String text, float x, float baseline,
                     float spacing, int align) {
        return spacedText(applet.g, text, x, baseline, spacing, align);
    }

    /**
     * {@code PGraphics} 版本 —— 给需要离屏绘制的调用方。
     *
     * @param g        画布
     * @param text     文本
     * @param x        起始左边缘
     * @param baseline 基线 y
     * @param spacing  字距
     * @param align    水平对齐
     * @return 总宽度
     */
    float spacedText(processing.core.PGraphics g, String text, float x, float baseline,
                     float spacing, int align) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        float total = 0f;
        for (int i = 0; i < text.length(); i++) {
            total += g.textWidth(text.charAt(i));
            if (i < text.length() - 1) {
                total += spacing;
            }
        }
        float cx = align == processing.core.PConstants.CENTER ? x - total / 2f : x;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            g.text(ch, cx, baseline);
            cx += g.textWidth(ch) + spacing;
        }
        return total;
    }

    /**
     * 画一段文字，**坐标取整**（决定书 L2）。
     *
     * <h2>为什么坐标也要取整</h2>
     * 字号取整解决了"字被拉伸"，但分数坐标会让整个字形在像素网格上半格处落笔，
     * P2D 的抗锯齿于是把笔画摊到两列像素上 —— 放大 4 倍看就是**灰阶重影**。
     * 字号与坐标都落在整数上，字才锐。
     *
     * <p>⚠️ 调用本方法前**不要**对画布做 {@code scale()}：
     * 那会再引入一次非整数变换，取整就白做了。
     *
     * @param g  画布
     * @param s  文本
     * @param x  左/中/右基准 x（按当前 textAlign）
     * @param y  基线/中心 y（按当前 textAlign）
     */
    void text(PApplet g, String s, float x, float y) {
        g.text(s, Math.round(x), Math.round(y));
    }

    /** 三个角色。 */
    enum Role {
        /** 命令 / 标题：衬线、重。 */
        COMMAND,
        /** 正文与控件标签：无衬线。 */
        SANS,
        /** 数值与标识符：等宽。 */
        MONO
    }
}
