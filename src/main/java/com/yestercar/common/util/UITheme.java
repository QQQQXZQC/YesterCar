package com.yestercar.common.util;

import g4p_controls.G4P;
import g4p_controls.GAlign;
import g4p_controls.GCScheme;
import g4p_controls.GLabel;
import processing.core.PApplet;
import processing.core.PFont;
import processing.core.PGraphics;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.List;

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
 * <h2>⚠️ 中文显示：有两条独立的渲染路径，必须分别处理</h2>
 * 这是本项目踩过的坑，两条路径互不影响，只修一条仍会看到方框：
 *
 * <table border="1">
 *   <caption>两条中文渲染路径</caption>
 *   <tr><th>路径</th><th>谁在用</th><th>怎么设字体</th><th>不设的后果</th></tr>
 *   <tr>
 *     <td>AWT 路径</td>
 *     <td>G4P 控件（按钮/标签/下拉框）</td>
 *     <td>{@code G4P.setDisplayFont(...)}</td>
 *     <td>回退到 Dialog，中文可能变方块</td>
 *   </tr>
 *   <tr>
 *     <td>Processing 路径</td>
 *     <td>自己写的 {@code text()} 调用</td>
 *     <td>{@code textFont(createFont(...))}</td>
 *     <td><strong>一定</strong>画方框（实测：默认字体画中文只有 600 个像素，全是方框轮廓）</td>
 *   </tr>
 * </table>
 *
 * {@link #apply(PApplet)} 两条一起处理，所以每个窗口只要调它一次即可。
 */
public final class UITheme {

    /** 默认配色方案编号，{@code GConstants.BLUE_SCHEME}。 */
    public static final int DEFAULT_SCHEME = GCScheme.BLUE_SCHEME;

    /**
     * 中文字体族名候选，按优先级排列。
     *
     * <p>同时放<strong>英文名与本地化中文名</strong>，因为 Java 的字体家族名在不同系统上不同：
     * 本机 AWT 列表里是「微软雅黑」而不是「Microsoft YaHei」。
     * {@link #resolveChineseFamily()} 不靠猜名字，而是逐个试
     * {@link Font#canDisplay(char)}，找出真能画出中文字形的那一个。
     */
    public static final List<String> FONT_CANDIDATES = List.of(
            "Microsoft YaHei", "微软雅黑",
            "Microsoft YaHei UI",
            "SimHei", "黑体",
            "SimSun", "宋体",
            "DengXian", "等线",
            "Noto Sans CJK SC", "Source Han Sans SC");

    /** 兼容旧引用：首选字体名。实际用哪个由 {@link #resolveChineseFamily()} 决定。 */
    public static final String FONT_FAMILY = "Microsoft YaHei";

    /** 正文字号。 */
    public static final int FONT_SIZE = 14;

    /** 标题字号。 */
    public static final int TITLE_SIZE = 22;

    // ------------------------------------------------------------------
    // 配色：采用 ak-ui（明日方舟风格 UI 库）的官方色板
    //
    // 来源：UI/ak-ui-1.1.0/src/scss/_variables.scss 与 _tokens.scss
    // 之所以直接用它而不是自己调色：这是**现成的、成体系的**明日方舟配色，
    // 自己配很难配得比它更像。
    //
    // 命名保留了 ak-ui 的 tier 语义（primary / advanced / basic / low），
    // 因为这几个词在明日方舟里是有明确等级含义的，改成「红黄绿」反而丢信息。
    //
    // ⚠️ 底色分两套：编辑器用白底（WINDOW_BG_EDITOR），其余用深底（WINDOW_BG_DARK）。
    // 白底不是简单地改一个常量 —— 文字色、网格线、分区线都要跟着换，
    // 所以这里成对给出，避免某个界面用白底却配了浅色文字（看不见）。
    // ------------------------------------------------------------------

    /** 主色（蓝 {@code #4aabea}）—— 主要动作、信息。 */
    /** 主色（蓝 {@code #4aabea}）—— 选中态、信息。 */
    public static final int COLOR_PRIMARY = 0xFF4AABEA;

    /** 强调色（黄 {@code #f1c644}）。 */
    public static final int COLOR_ADVANCED = 0xFFF1C644;

    // ------------------------------------------------------------------
    // 按钮三态（P2.2 追加）
    //
    // 普通按钮走灰阶：白 → 悬浮灰 → 按下深灰。
    // 主按钮（黄）**不转灰**：它承担"这是我该点的那个"的视觉引导，
    // 转成灰阶就和其他按钮一样了，反而失去意义。
    // ------------------------------------------------------------------

    /** 普通按钮：常态底色。 */
    public static final int BTN_FACE = 0xFFFFFFFF;

    /** 普通按钮：鼠标悬浮。 */
    public static final int BTN_FACE_HOVER = 0xFFC3C3C3;

    /** 普通按钮：按下。 */
    public static final int BTN_FACE_PRESSED = 0xFF7F7F7F;

    /** 主按钮：常态底色。 */
    public static final int BTN_PRIMARY_FACE = 0xFFFFF200;

    /** 主按钮：鼠标悬浮。 */
    public static final int BTN_PRIMARY_FACE_HOVER = 0xFFFFC90E;

    /** 主按钮：按下。 */
    public static final int BTN_PRIMARY_FACE_PRESSED = 0xFFF1C644;

    /** 警示色（橙 {@code #f6540e}）—— 错误、危险操作。 */
    public static final int COLOR_ACCENT = 0xFFF6540E;

    /** 基础色（黄绿 {@code #d8dd5a}）—— 次要标记。 */
    public static final int COLOR_BASIC = 0xFFD8DD5A;

    /** 禁用/次要色（灰 {@code #9c9c9c}）。 */
    public static final int COLOR_LOW = 0xFF9C9C9C;

    /** 次色（紫灰 {@code #cfc2d1}）。 */
    public static final int COLOR_SECONDARY = 0xFFCFC2D1;

    // ---- 主菜单专属色（ak-ui 仪表盘那一套） ----

    /**
     * 主菜单强调色（ak-ui 的 {@code --ak-dashboard-accent}，橙 {@code #ff5e19}）。
     *
     * <p>⚠️ 与全局 {@link #COLOR_ACCENT}（{@code #f6540e}）<strong>不是同一个色</strong>：
     * ak-ui 的仪表盘组件用的是自己那一份。两个都保留是有意的 ——
     * 主菜单要贴着仪表盘的样子，而编辑器/错误提示继续用全局那个。
     */
    public static final int MENU_ACCENT = 0xFFFF5E19;

    /**
     * 主菜单的"纸面"色（ak-ui 的 {@code --ak-dashboard-paper}，近白）。
     *
     * <p>ak-ui 的层级靠**纸面与石墨面并存**建立，不是靠深浅渐变。
     * 所以命令卡有"纸面"变体：白底 + 深字，在一堆深色卡里当视觉锚点。
     */
    public static final int MENU_PAPER = 0xF2FDFDFB;

    /** 主菜单的"柔和纸面"（{@code --ak-dashboard-muted}），比纸面灰一档。 */
    public static final int MENU_MUTED = 0xF2EBECEB;

    /**
     * 主菜单的"抬升面"（{@code --ak-dashboard-dark}，石墨灰 {@code rgba(66,66,66,.92)}）。
     *
     * <p>注意是<b>半透明</b>而不是纯色 —— 它会透出底下的网格背景，
     * 这是 ak-ui 层叠感的来源之一。
     */
    public static final int MENU_RAISED = 0xEB424242;

    /** 主菜单的青色面（{@code --ak-dashboard-cyan}）—— 信息/招募类面板。 */
    public static final int MENU_CYAN = 0xEB05A7DC;

    /** 信息信号色（{@code --ak-dashboard-info} → {@code --ak-color-primary}，{@code #4aabea}）。 */
    public static final int SIGNAL_INFO = 0xFF4AABEA;

    /** 行动/警示信号色（{@code --ak-dashboard-signal} → {@code --ak-color-advanced}）。 */
    public static final int SIGNAL_ACTION = 0xFFF1C644;

    /** 成功信号色（ak-ui 的 {@code --ak-signal-success}）。 */
    public static final int SIGNAL_SUCCESS = 0xFF46C47C;

    /** 危险信号色（ak-ui 的 {@code --ak-signal-danger}）。 */
    public static final int SIGNAL_DANGER = 0xFFE33B3B;

    /** 深色面上的主文字色（ak-ui 的 {@code --ak-text-primary}）。 */
    public static final int MENU_TEXT = 0xFFF8F8F5;

    /** 深色面上的次文字色（{@code --ak-text-secondary}，透明 68%）。 */
    public static final int MENU_TEXT_DIM = 0xADF8F8F5;

    /**
     * ak-ui 原始色板里的亮青蓝（{@code $blue: #2bf}）。
     *
     * <p>⚠️ 与 {@link #SIGNAL_INFO} <strong>不是同一个色</strong>：仪表盘组件把
     * {@code --ak-dashboard-info} 指到了 {@code --ak-color-primary}（{@code #4aabea}），
     * 而 {@code #2bf} 只出现在部分组件里。两个都留着，是因为确实存在两个来源。
     */
    public static final int PALETTE_BLUE = 0xFF22BBFF;

    /** ak-ui 原始色板里的黄（{@code $yellow: #ffd802}）。 */
    public static final int PALETTE_YELLOW = 0xFFFFD802;

    /** ak-ui 原始色板里的深蓝（{@code #0075a8}）。 */
    public static final int PALETTE_DARK_BLUE = 0xFF0075A8;

    /** ak-ui 原始色板里的浅蓝（{@code #3ff7ff}）。 */
    public static final int PALETTE_LIGHT_BLUE = 0xFF3FF7FF;

    // ---- 兼容旧引用（新代码请用上面带语义的名字） ----

    /** @deprecated 用 {@link #PALETTE_BLUE}。 */
    @Deprecated
    public static final int COLOR_BLUE = PALETTE_BLUE;
    /** @deprecated 用 {@link #PALETTE_YELLOW}。 */
    @Deprecated
    public static final int COLOR_YELLOW = PALETTE_YELLOW;
    /** @deprecated 用 {@link #PALETTE_DARK_BLUE}。 */
    @Deprecated
    public static final int COLOR_DARK_BLUE = PALETTE_DARK_BLUE;
    /** @deprecated 用 {@link #PALETTE_LIGHT_BLUE}。 */
    @Deprecated
    public static final int COLOR_LIGHT_BLUE = PALETTE_LIGHT_BLUE;

    /** 深色面（{@code #222222}）。 */
    public static final int COLOR_DARK = 0xFF222222;

    /** 灰色面（{@code #313131}）。 */
    public static final int COLOR_GRAY = 0xFF313131;

    // ---- 深色底那一套（加载界面、主菜单、非编辑器窗口） ----

    /** 深色画面底（ak-ui 的 {@code surface-canvas}）。 */
    public static final int WINDOW_BG_DARK = 0xFF111315;

    /** 深底上的主文字色（ak-ui 的 {@code text-primary}）。 */
    public static final int TEXT_ON_DARK = 0xFFF8F8F5;

    /** 深底上的次要文字色（ak-ui 的 {@code text-secondary}）。 */
    public static final int TEXT_DIM_ON_DARK = 0xADF8F8F5;

    /** 深底上的分区线/网格线。 */
    public static final int LINE_ON_DARK = 0xFF505050;

    // ---- 白底那一套（**战役编辑器**专用） ----

    /**
     * 编辑器画面底：浅色（{@code surface-panel} 的意思）。
     *
     * <p>用略暖的灰白而不是纯白 —— 纯白配深色文字会太刺眼，
     * 而且和地图里的浅色地砖分不开。
     */
    public static final int WINDOW_BG_EDITOR = 0xFFF2F3F5;

    /** 白底上的主文字色（ak-ui 的 {@code text-inverse}）。 */
    public static final int TEXT_ON_LIGHT = 0xFF1B1D1F;

    /** 白底上的次要文字色。 */
    public static final int TEXT_DIM_ON_LIGHT = 0x9C1B1D1F;

    /** 白底上的分区线。 */
    public static final int LINE_ON_LIGHT = 0xFFB8BCC2;

    /**
     * 白底输入框里**选中文字**的底色。
     *
     * <p>用 {@link #COLOR_PRIMARY} 的浅色调而不是系统默认的那种深蓝：
     * 我们的输入框本身是白底、焦点框也是主色，选中底色跟着主色走
     * 才像一套东西。透明度约 30%，保证上面的深色文字仍然清楚。
     */
    public static final int SELECTION_BG = 0x4D4AABEA;

    /** 白底上的面板底（比画面底略深一点，用来分区）。 */
    public static final int PANEL_BG_LIGHT = 0xFFE4E6EA;

    /** 深色底上的面板底。 */
    public static final int PANEL_BG_DARK = 0xFF1B1D1F;

    // ---- 兼容旧引用：默认指向深色那一套 ----

    /** @deprecated 请改用 {@link #WINDOW_BG_DARK} 或 {@link #WINDOW_BG_EDITOR}。 */
    @Deprecated
    public static final int COLOR_WINDOW_BG = WINDOW_BG_DARK;

    /** @deprecated 请改用 {@link #TEXT_ON_DARK} 或 {@link #TEXT_ON_LIGHT}。 */
    @Deprecated
    public static final int COLOR_TEXT = TEXT_ON_DARK;

    /** @deprecated 请改用 {@link #TEXT_DIM_ON_DARK} 或 {@link #TEXT_DIM_ON_LIGHT}。 */
    @Deprecated
    public static final int COLOR_TEXT_DIM = TEXT_DIM_ON_DARK;

    /** @deprecated 请改用 {@link #LINE_ON_DARK} 或 {@link #LINE_ON_LIGHT}。 */
    @Deprecated
    public static final int COLOR_GRID_LINE = LINE_ON_DARK;

    /** 主色文字（深色底上可读的蓝），用于可在浅色底上也能用的场合。 */
    public static final int COLOR_TEXT_BLUE = 0xFF0098DC;

    // ------------------------------------------------------------------
    // 间距与几何（ak-ui 的 4px 基准体系：space-1~6）
    // ------------------------------------------------------------------

    /** 间距 4px。 */
    public static final int SPACE_1 = 4;

    /** 间距 8px。 */
    public static final int SPACE_2 = 8;

    /** 间距 12px。 */
    public static final int SPACE_3 = 12;

    /** 间距 16px。 */
    public static final int SPACE_4 = 16;

    /** 间距 24px。 */
    public static final int SPACE_5 = 24;

    /** 间距 32px。 */
    public static final int SPACE_6 = 32;

    /** 控件高度（ak-ui 的 {@code density-control-height}）。 */
    public static final int CONTROL_HEIGHT = 44;

    /** 细线宽度（{@code line-hairline}）。 */
    public static final float LINE_HAIRLINE = 1f;

    /** 粗线/强调描边宽度（{@code line-strong}）。 */
    public static final float LINE_STRONG = 4f;

    /** 小切角（{@code cut-sm}）。 */
    public static final float CUT_SM = 6f;

    /** 中切角（{@code cut-md}）。 */
    public static final float CUT_MD = 12f;

    /** 大切角（{@code cut-lg}）。 */
    public static final float CUT_LG = 24f;

    /** 细圆角（{@code radius-subtle}）。 */
    public static final float RADIUS_SUBTLE = 2f;

    private static boolean applied = false;
    private static String resolvedFamily;

    private UITheme() {
        // 工具类，不允许实例化
    }

    /**
     * 把一个 {@code PApplet} 窗口纳入统一主题。
     *
     * <p>在 {@code setup()} 的最前面调用，必须在创建任何 G4P 控件之前——
     * G4P 的字体设置只影响「调用之后创建」的控件。两条字体路径一次搞定：
     * G4P 控件（AWT）与自己的 {@code text()}（Processing 字体）。
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

        // 解析一个真能画中文的字体族（不靠猜名字，靠 canDisplay 实测）
        String family = resolveChineseFamily();

        // 路径一：G4P 控件走 AWT（displayFont 影响按钮/标签/下拉框，inputFont 影响输入框）
        G4P.setDisplayFont(family, G4P.PLAIN, FONT_SIZE);
        G4P.setInputFont(family, G4P.PLAIN, FONT_SIZE);
        G4P.setSliderFont(family, G4P.BOLD, FONT_SIZE);

        // 路径二：自己写的 text() 走 Processing 字体，不设就是方框。
        // 实测在 setup() 里调用即可成功，不需要推迟到 draw()。
        applyFont(applet, family, FONT_SIZE);

        applied = true;
    }

    /**
     * 给主画布设置 Processing 字体。
     *
     * <p>在 {@code setup()} 里调用即可。
     *
     * @param applet 目标窗口
     * @param family 字体族名
     * @param size   字号
     */
    public static void applyFont(PApplet applet, String family, int size) {
        try {
            applet.textFont(applet.createFont(family, size, true));
        } catch (RuntimeException | LinkageError e) {
            warnFontFailure(family, e);
        }
    }

    /**
     * 用已解析的中文字体给主画布设字体。
     *
     * @param applet 目标窗口
     */
    public static void applyFont(PApplet applet) {
        applyFont(applet, resolveChineseFamily(), FONT_SIZE);
    }

    /**
     * 给离屏画布设置字体。
     *
     * <p>离屏缓冲（{@code createGraphics}）<strong>不继承</strong>主画布的字体，必须单独设一次。
     *
     * <p>为什么要传 {@code owner}：{@code PFont} 只能由 {@link PApplet#createFont} 创建
     * （{@code PGraphics.createFont} 是 protected，外部调不到），
     * 而 {@code PApplet} <strong>不是</strong> {@code PGraphics}（它的父类是 {@code Object}，
     * 只实现 {@code PConstants}），所以两者必须各有一个重载。
     *
     * @param owner  负责创建字体的 PApplet
     * @param target 接收字体的离屏画布
     * @param family 字体族名
     * @param size   字号
     */
    public static void applyFont(PApplet owner, PGraphics target, String family, int size) {
        try {
            target.textFont(owner.createFont(family, size, true));
        } catch (RuntimeException | LinkageError e) {
            warnFontFailure(family, e);
        }
    }

    /**
     * 用已解析的中文字体给离屏画布设字体。
     *
     * @param owner  负责创建字体的 PApplet
     * @param target 接收字体的离屏画布
     */
    public static void applyFont(PApplet owner, PGraphics target) {
        applyFont(owner, target, resolveChineseFamily(), FONT_SIZE);
    }

    private static void warnFontFailure(String family, Throwable e) {
        // 失败就退回默认字体并明确告知，而不是让用户对着一屏方框猜原因
        System.err.println("[UITheme] 无法加载中文字体 '" + family
                + "'，中文将显示为方框: " + e.getMessage());
    }

    /**
     * 找出本机真正能渲染中文的字体族名。
     *
     * <p>判定方式是不信名字信字形：逐个候选建 {@link Font} 后问
     * {@link Font#canDisplay(char)}。找不到任何候选时退回到
     * {@link #FONT_FAMILY}（宁可显示方框，也不要抛异常起不来）。
     *
     * @return 可直接用于 AWT 与 {@code createFont} 的字体族名
     */
    public static String resolveChineseFamily() {
        if (resolvedFamily != null) {
            return resolvedFamily;
        }
        String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();

        for (String candidate : FONT_CANDIDATES) {
            for (String family : available) {
                if (family.equalsIgnoreCase(candidate)) {
                    Font font = new Font(family, Font.PLAIN, FONT_SIZE);
                    if (font.canDisplay('中') && font.canDisplay('文')) {
                        resolvedFamily = family;
                        return resolvedFamily;
                    }
                }
            }
        }
        System.err.println("[UITheme] 未找到可显示中文的字体，回退到 " + FONT_FAMILY);
        resolvedFamily = FONT_FAMILY;
        return resolvedFamily;
    }

    /**
     * @return {@link #apply(PApplet)} 解析出的实际字体族名；未解析时为 {@code null}
     */
    public static String resolvedFontFamily() {
        return resolvedFamily;
    }

    /**
     * 清空字体解析缓存，仅供测试使用。
     */
    static void resetFontCache() {
        resolvedFamily = null;
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
                + " | 字体=" + (resolvedFamily != null ? resolvedFamily : FONT_FAMILY)
                + " " + FONT_SIZE + "pt";
    }
}
