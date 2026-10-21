package com.yestercar.gameopening;

import com.yestercar.common.model.Campaign;
import com.yestercar.common.ui.DesignSpace;
import com.yestercar.common.util.UITheme;
import com.yestercar.gameopening.icon.SvgPath;
import processing.core.PApplet;
import processing.core.PConstants;

import java.util.List;

/**
 * 左栏 —— ak-ui 仪表盘 {@code __profile} / {@code __left-menu} 的移植，
 * 并按决定书 D8–D12 做了**删除与替换**。
 *
 * <h2>⚠️ 这一栏与原版差别最大，因为原版一半的东西我们没有</h2>
 * ak-ui 参考稿的左栏是：语音 / 公告 / 好友 / 情报 / 社交栏（ID、签名）/ 等级环。
 * 移植时逐项对照决定书：
 *
 * <table border="1">
 *   <caption>决定书 D8–D12</caption>
 *   <tr><th>原版元素</th><th>决定</th><th>本类怎么处理</th></tr>
 *   <tr><td>助理播报（语音）</td><td>D8 保留结构、标注预留</td>
 *       <td>{@link #drawBroadcast}：框在那、写明"预留"，<b>不可点、无 hover</b></td></tr>
 *   <tr><td>公告卡</td><td>D9 保留位置，放官方战役入口</td>
 *       <td>{@link #drawAnnouncement}：点它 = 进官方关卡页（真实动作）</td></tr>
 *   <tr><td>好友 / 情报 快捷入口</td><td>D10 删除（连位置）</td>
 *       <td>不画</td></tr>
 *   <tr><td>社交栏（ID / 签名）</td><td>D11 删除</td>
 *       <td>不画。假 ID {@code 9304721220} 一并删除</td></tr>
 *   <tr><td>「重播入场」</td><td>D11 是真实动作，移到底部小字</td>
 *       <td>{@link #drawReplay}：重放 {@code Phase.LOADING}</td></tr>
 *   <tr><td>等级环</td><td>D12 固定 120，环以 120 为满</td>
 *       <td>{@link #drawLevelRing}</td></tr>
 * </table>
 *
 * <h2>⚠️ 一条贯穿全栏的判据</h2>
 * 决定书 §5：每个可见元素必须是 ① 绑定真实动作 ② 显示真实数据 ③ 删掉。
 * <b>静态文案不算真实数据</b> —— 所以参考稿那句
 * "博士，小云已就位。今天也一起做些有趣的东西吧。"没有搬过来。
 */
final class LeftColumn {

    /** 点击左栏的结果。 */
    enum Action {
        /** 没点到东西。 */
        NONE,
        /** 点了喇叭，需要调整整体音量。 */
        VOLUME,

        /** 重播入场（重放 LOADING）。 */
        REPLAY,
        /** 官方战役入口。 */
        OFFICIAL
    }

    /** 左栏宽度（设计坐标，cqw）。 */
    private static final float COL_W_PCT = 22f;

    /** 左栏左边缘（设计坐标，cqw）。 */
    private static final float COL_X_PCT = 2.05f;

    /**
     * 等级环直径（占设计宽的百分比）。
     *
     * <p>⚠️ 第一版取 {@code 15}（≈205 设计单位），结果环大到把代号、播报框
     * 全盖住 —— 参考稿的环只占画面宽的约 7%。这里按参考稿的比例取 7。
     */
    private static final float RING_D_PCT = 7f;

    /**
     * 等级环里显示的数字 —— 决定书 D12：**固定 120 的常量**。
     *
     * <p>为什么是常量而不是真实数据：YesterCar 还没有存档/等级系统，
     * 没有真实等级可显示。决定书要求"环形进度固定 120（常量，不绑定数据）"，
     * 所以这是一个**明确标注的占位常量**，不是编造的数据 ——
     * 它不随任何游戏行为变化，也不会让人误以为在追踪进度。
     */
    private static final int RING_FIXED = 120;

    private final PApplet g;
    private final DesignSpace d;
    private final Fonts fonts;
    private final String username;
    private final List<Campaign> official;

    /** 公告卡（官方战役入口）的命中区，每帧更新（窗口坐标）。 */
    private float cardX;
    private float cardY;
    private float cardW;
    private float cardH;

    /** 待应用的音量（点喇叭后由 MainMenu 取走）。 */
    private float pendingVolume = -1f;

    /**
     * @return 刚刚点选的新音量（0–1）；没有则为负
     */
    float takePendingVolume() {
        float v = pendingVolume;
        pendingVolume = -1f;
        return v;
    }

    /** 喇叭按钮命中区。 */
    private float speakerX;
    private float speakerY;
    private float speakerW;
    private float speakerH;
    private boolean speakerHovered;

    /** 立绘循环：已加载的图片（按文件名排序）。 */
    // ⚠️ 全部改 static：窗口缩放/全屏会重建 MainMenu（因而重建 LeftColumn），
    //    普通字段会把"当前第几张 + 已加载的图"全丢掉，表现就是轮播从头开始。
    private static final java.util.List<processing.core.PImage> showImgs =
            new java.util.ArrayList<>();

    /** 当前展示的下标。 */
    private static int showIdx;

    /** 上次切换时刻（毫秒）。 */
    private static int showAt;

    /** 是否已经尝试加载过（列出文件）。 */
    private static boolean showLoaded;

    /** 待解码的图片文件队列（每帧解一张）。 */
    private static final java.util.List<java.io.File> pendingShowFiles =
            new java.util.ArrayList<>();

    /** 展示框命中区（窗口坐标）。 */
    private float showX;
    private float showY;
    private float showW;
    private float showH;

    /** 切换阶段：0=停留，1=淡出中，2=淡入中。 */
    private static int showPhase;

    /**
     * 接收开场预加载好的图片（避免重复解码）。
     *
     * @param img 已缩放到 480 宽的立绘
     */
    static void addPreloaded(processing.core.PImage img) {
        showImgs.add(img);
    }

    /** 当前阶段起始时刻。 */
    private static int showPhaseAt;

    /** 淡出/淡入各 150ms。 */
    private static final int SHOW_FADE_MS = 150;

    /** 每张停留时长。 */
    private static final int SHOW_INTERVAL_MS = 5000;   // 每张 5 秒

    /** 重播按钮的命中区，每帧更新（窗口坐标）。 */
    private float replayX;
    private float replayY;
    private float replayW;
    private float replayH;

    private boolean cardHovered;
    private boolean replayHovered;
    private boolean cardPressed;
    private boolean replayPressed;

    LeftColumn(PApplet g, DesignSpace d, Fonts fonts, String username,
               List<Campaign> official) {
        this.g = g;
        this.d = d;
        this.fonts = fonts;
        this.username = username;
        this.official = official;
    }

    // ------------------------------------------------------------------
    // 几何
    // ------------------------------------------------------------------

    private float colX() {
        return DesignSpace.BASE_W * COL_X_PCT / 100f;
    }

    private float colW() {
        return DesignSpace.BASE_W * COL_W_PCT / 100f;
    }

    /**
     * 助理播报的正文。
     *
     * <h2>★ 想改这句话，只改这里 ★</h2>
     * 就是背景人物对玩家说的话。目前是纯文本；将来加语音时，
     * 把音频文件放进 {@code data/music/}，在 {@code GameOpeningApp.enterPhase()}
     * 里进主菜单那一段调用播放即可（背景音乐 {@code BackgroundMusic} 就是现成的范例）。
     *
     * <p>⚠️ 文案会按面板宽度自动折行，改长改短都不用动排版代码；
     * 但太长会被面板高度截断（见 {@link #drawBroadcast}）。
     */
    static final String BROADCAST_TEXT =
            "早上好，博士！我今天有外出任务，所以跟你提前说午安和晚安啦！";

    // ---- ★ 助理播报的字号：改这里 ★ ----
    //
    // ⚠️ 单位是**设计坐标**，不是像素。
    //    设计基准 1440×810；实际像素 = round(设计值 × sx)。
    //    例：窗口 1680×945 时 sx = 1.1667，9.5 设计 ≈ 11 像素。
    //    在 1920×1080 下 sx = 1.3333，9.5 设计 ≈ 13 像素。
    //
    //    为什么要用设计坐标：窗口可缩放，写死像素的话换分辨率就全乱。

    /** 标题「助理播报」的字号（设计坐标）。原 9。 */
    private static final float BROADCAST_TITLE_PT = 16f;

    /** 正文（问候语）的字号（设计坐标）。原 8，为了可读性提到 9.5。 */
    private static final float BROADCAST_BODY_PT = 14f;

    /** 正文行高（设计坐标）。应与正文号一起调：小了会挤，大了会溢出面板。 */
    private static final float BROADCAST_LINE_H = 16f;

    /**
     * 助理播报面板高度（占设计高的比例）。
     *
     * <p>调大字号后如果正文被面板截断，把这里一起调大。
     * 注意 {@link #drawBroadcast} 与 {@link #draw} 里的堆叠都用它，**两处必须一致**。
     */
    private static final float BROADCAST_H_FRAC = 0.082f;

    /**
     * 三个主要元素的统一放大倍数（用户第 5 轮要求）。
     *
     * <p>作用于<b>经验圈、助理播报框、干员立绘展示框</b>三者的尺寸。
     * 只放大尺寸，不放大它们之间的间距 —— 间距是 `1.2cqw` / `0.6cqw`，
     * 跟着放大会让整栏散开、底边对不齐右列。
     */
    private static final float ELEM_SCALE = 1.25f;

    /** 元素之间的竖向间距（TASKS-ROUND 2.1：{@code 1.2cqw}）。 */
    private float elemGap() {
        return DesignSpace.BASE_W * 1.2f / 100f;
    }

    /**
     * 左列底边 —— 与右列（菜单）底边**同一条基线**（TASKS-ROUND 2.1）。
     *
     * <p>这个值以后备的方式算出来：菜单顶 + 菜单高。左右两列各自独立排版，
     * 但底边必须对齐 —— 否则画面会"一边高一边低"。
     *
     * @param menuBottom 右列底边（设计坐标，由 {@code MainMenu} 传入）
     * @return 左列底边
     */
    private float bottom(float menuBottom) {
        return menuBottom;
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    /**
     * 画整栏。
     *
     * @param mouseX 鼠标 x（窗口坐标）
     * @param mouseY 鼠标 y（窗口坐标）
     * @param enterX 入场位移（窗口坐标，结束后为 0）
     */
    void draw(float mouseX, float mouseY, float enterX, float menuBottom) {
        g.pushMatrix();
        g.translate(enterX, 0f);

        // 左列顺序（用户第 1 条修改意见）：**自上而下**
        //   经验圈 → 玩家代号 → 助理播报 → 干员立绘展示框
        //
        // 所以自下而上排：立绘框在底，然后向上依次是 播报、代号、圈。
        float cqw = DesignSpace.BASE_W / 100f;
        float gapInGroup = 0.6f * cqw;   // 圈与代号之间（TASKS-ROUND-2 4.3）
        float elemGapV = 1.2f * cqw;     // 各组之间（4.3）

        // 三个主要元素统一放大（ELEM_SCALE）
        float frameH = DesignSpace.BASE_H * 0.235f * ELEM_SCALE;
        float castH = DesignSpace.BASE_H * BROADCAST_H_FRAC * ELEM_SCALE;
        float nameH = 20f;               // 代号行高（设计），不参与放大

        // 圈直径：不小于 9% 窗口高（4.1），再乘放大倍数
        float ringD = Math.max(DesignSpace.BASE_H * 0.09f,
                DesignSpace.BASE_W * RING_D_PCT / 100f) * ELEM_SCALE;

        float bottom = bottom(menuBottom);
        float frameTop = bottom - frameH;

        // 立绘框 → 播报 → 代号 → 圈，自下而上
        float castBottom = frameTop - elemGapV - castH;
        float nameBottom = castBottom - elemGapV;
        float ringBottom = nameBottom - nameH - gapInGroup;

        // 重播入场在左上角，不参与这个堆叠
        drawSpeaker(mouseX, mouseY);

        drawGroup(ringBottom, nameBottom, ringD);
        drawBroadcast(castBottom);
        drawCharacterFrame(frameTop);

        g.popMatrix();
    }

    /**
     * 等级环 —— {@code .ak-dashboard__level-ring}。
     *
     * <p>粗描边圆 + 环内数字 + 右下小字 "LV"。
     * 数字是 {@link #RING_FIXED}（明确定义的常量占位，见字段注释）。
     */
    /**
     * 经验圈 + 代号，作为**一组**水平居中于立绘框（TASKS-ROUND-2 §4.2）。
     *
     * <p>两者共用同一个水平中心 = 左栏中心，所以"圈中心 / 代号中心 / 立绘框中心"
     * 三者对齐。
     *
     * @param ringBottom 圈的下边缘
     * @param nameBottom 代号的基线
     * @param ringD      圈直径
     */
    private void drawGroup(float ringBottom, float nameBottom, float ringD) {
        float cx = colX() + colW() / 2f;

        // 圈内白色底（50% 不透明）—— 否则数字直接压在立绘上读不清
        g.noStroke();
        g.fill(255, 255, 255, 0.5f * 255f);
        g.ellipse(d.x(cx), d.y(ringBottom - ringD / 2f), d.w(ringD), d.w(ringD));

        // 圈
        g.noFill();
        g.stroke(255, 255, 255, 0.16f * 255f);
        g.strokeWeight(d.len(2.2f));
        g.ellipse(d.x(cx), d.y(ringBottom - ringD / 2f), d.w(ringD), d.w(ringD));
        g.stroke(UITheme.SIGNAL_ACTION);
        g.strokeWeight(d.len(3.4f));
        g.arc(d.x(cx), d.y(ringBottom - ringD / 2f), d.w(ringD), d.w(ringD),
                -PConstants.HALF_PI, -PConstants.HALF_PI + PConstants.TWO_PI);
        g.noStroke();

        // 文字阴影（不是整条压暗遮罩）
        //
        // ⚠️ 上一版为了让左栏在立绘上读得清，在角色之上盖了一条 32 阶的渐隐矩形 ——
        //    结果立绘被切成**一列一列明暗交替**（用户报告的 bug）。
        //    现在改成只给文字本身描一层暗影：局部、不影响画面其他部分。
        fonts.apply(g, Fonts.Role.MONO, ringD * 0.30f);
        g.textAlign(PConstants.CENTER, PConstants.CENTER);
        g.fill(0f, 0f, 0f, 0.72f * 255f);
        fonts.text(g, String.valueOf(RING_FIXED),
                d.x(cx) + 2f, d.y(ringBottom - ringD / 2f - ringD * 0.04f) + 2f);
        g.fill(UITheme.MENU_TEXT);
        fonts.text(g, String.valueOf(RING_FIXED),
                d.x(cx), d.y(ringBottom - ringD / 2f - ringD * 0.04f));

        fonts.apply(g, Fonts.Role.MONO, ringD * 0.135f);
        // 和 "120" 同一套做法：先描一层暗影，再压白字
        g.fill(0f, 0f, 0f, 0.72f * 255f);
        fonts.text(g, "LV", d.x(cx) + 2f, d.y(ringBottom - ringD / 2f + ringD * 0.27f) + 2f);
        g.fill(UITheme.MENU_TEXT);
        fonts.text(g, "LV", d.x(cx), d.y(ringBottom - ringD / 2f + ringD * 0.27f));

        // 代号：衬线 + 逐字字距，居中
        fonts.apply(g, Fonts.Role.COMMAND, 26f);
        g.fill(UITheme.MENU_TEXT);
        g.textAlign(PConstants.LEFT, PConstants.BASELINE);
        String shown = Username.titled(username);   // 显示加 "Dr." 前缀（存的原样不变）
        float nameX = d.x(cx) - measureSpaced(shown, d.w(1.2f)) / 2f;

        // 代号底板：25% 不透明度的灰
        g.noStroke();
        g.fill(128f, 128f, 128f, 0.50f * 255f);
        g.rect(nameX - d.w(10f), d.y(nameBottom) - d.h(30f),
                measureSpaced(shown, d.w(1.2f)) + d.w(20f), d.h(40f), 3f);
        g.fill(0f, 0f, 0f, 0.72f * 255f);           // 先描一层暗影
        fonts.spacedText(g, shown, nameX + 2f, d.y(nameBottom) + 2f, d.w(1.2f), PConstants.LEFT);
        g.fill(UITheme.MENU_TEXT);
        fonts.spacedText(g, shown, nameX, d.y(nameBottom), d.w(1.2f), PConstants.LEFT);
    }

    /**
     * 按可用宽度把一段中文折行。
     *
     * <p>中文没有空格可断，只能逐字累加量宽度 —— {@code textWidth(整句)} 只给总宽，
     * 用它除不出安全的断点（CJK 与标点宽度不一样）。
     *
     * @param text   原文
     * @param availW 可用宽度（**窗口像素**）
     * @return 折好的行
     */
    private java.util.List<String> wrap(String text, float availW) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String next = cur.toString() + c;
            if (g.textWidth(next) > availW && cur.length() > 0) {
                lines.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            lines.add(cur.toString());
        }
        return lines;
    }

    /** 量一段带字距文本的总宽（窗口像素）。 */
    private float measureSpaced(String text, float spacing) {
        float total = 0f;
        for (int i = 0; i < text.length(); i++) {
            total += g.textWidth(text.charAt(i));
            if (i < text.length() - 1) {
                total += spacing;
            }
        }
        return total;
    }

    private void drawLevelRing(float top) {
        float ring = DesignSpace.BASE_W * RING_D_PCT / 100f;
        float cx = colX() + ring / 2f;
        float cy = top + ring / 2f;

        // 底环：细、暗（未填充部分）
        g.noFill();
        g.stroke(255, 255, 255, 0.16f * 255f);
        g.strokeWeight(d.len(2.2f));
        g.ellipse(d.x(cx), d.y(cy), d.w(ring), d.w(ring));

        // 进度环：信号黄，满圈（120/120）
        g.stroke(UITheme.SIGNAL_ACTION);
        g.strokeWeight(d.len(3.4f));
        g.arc(d.x(cx), d.y(cy), d.w(ring), d.w(ring), -PConstants.HALF_PI, -PConstants.HALF_PI
                + PConstants.TWO_PI);
        g.noStroke();

        // 环内数字：等宽、大
        fonts.apply(g, Fonts.Role.MONO, ring * 0.30f);
        g.textAlign(PConstants.CENTER, PConstants.CENTER);
        g.fill(UITheme.MENU_TEXT);
        fonts.text(g, String.valueOf(RING_FIXED), d.x(cx), d.y(cy - ring * 0.04f));

        // "LV" —— 环内右下小字
        fonts.apply(g, Fonts.Role.MONO, ring * 0.135f);
        g.fill(0f, 0f, 0f, 0.72f * 255f);
        fonts.text(g, "LV", d.x(cx) + 2f, d.y(cy + ring * 0.27f) + 2f);
        g.fill(UITheme.MENU_TEXT);
        fonts.text(g, "LV", d.x(cx), d.y(cy + ring * 0.27f));
    }

    /**
     * 代号 + 标签 —— 决定书 D12：显示 {@code Username} 阶段真实输入的代号。
     *
     * <p>假 ID（{@code 9304721220}）与其它占位数值已删除。
     */
    private void drawIdentity(float bottom) {
        float x = colX();
        float nameY = bottom;

        // 代号：衬线大字 + 逐字字距（决定书 D6：只用于标题类文本）
        fonts.apply(g, Fonts.Role.COMMAND, 26f);
        g.fill(UITheme.MENU_TEXT);
        float w = fonts.spacedText(g, username, d.x(x), d.y(nameY), d.w(1.2f), PConstants.LEFT);

        // PLAYER 小标签，跟在代号右侧
        String tag = "PLAYER";
        fonts.apply(g, Fonts.Role.MONO, 8f);
        float tw = g.textWidth(tag);
        float bh = d.h(13f);
        float bx = d.x(x) + w + d.w(8f);
        float by = d.y(nameY) - bh * 0.85f;
        g.noStroke();
        g.fill(0xA6121416);
        CutShape.square(g, bx, by, tw + d.w(9f), bh);
        g.fill(UITheme.MENU_TEXT, 0.78f * 255f);
        g.textAlign(PConstants.CENTER, PConstants.CENTER);
        fonts.text(g, tag, bx + (tw + d.w(9f)) / 2f, by + bh / 2f);
    }

    /**
     * 助理播报 —— 决定书 D8：**保留结构、标注预留**，
     * <b>不可点、无 hover 态</b>。
     *
     * <p>为什么保留：它在参考稿里是一个明确的版式块（左下角的长条），
     * 删掉会让左栏下半部空掉。但它没有真实数据源，所以：
     * <ul>
     *   <li>画成"未激活"的暗框，不与可点元素抢注意力；</li>
     *   <li>文字直接写"预留"，让看代码和看界面的人都不会误会；</li>
     *   <li>{@code hit()} 不包含它 —— 它从交互层面就不存在。</li>
     * </ul>
     */
    private void drawBroadcast(float y) {
        float x = colX();
        float w = colW();
        float h = DesignSpace.BASE_H * BROADCAST_H_FRAC * ELEM_SCALE;

        // 深色面板而不是浅色半透明：浅色叠在摄影图上会糊成一片灰，
        // 文字读不出来。这里用 ak-ui {@code .ak-status} 的底（{@code rgba(17,19,21,.82)}）。
        g.noStroke();
        g.fill(17, 19, 21, 0.86f * 255f);
        CutShape.square(g, d.x(x), d.y(y), d.w(w), d.h(h));

        // 左侧一条细信号线（与 ak-ui 的 __line 同语言）
        g.fill(UITheme.MENU_TEXT_DIM, 0.5f * 255f);
        g.rect(d.x(x), d.y(y + h * 0.28f), d.len(2f), d.h(h * 0.44f));

        fonts.apply(g, Fonts.Role.MONO, BROADCAST_TITLE_PT);
        g.textAlign(PConstants.LEFT, PConstants.TOP);
        g.fill(UITheme.MENU_TEXT, 0.95f * 255f);
        fonts.text(g, "助理播报", d.x(x + 10f), d.y(y + h * 0.12f));

        // 正文按面板宽度折行（中文按字符逐个量，不能靠 textWidth 一次算完）
        fonts.apply(g, Fonts.Role.MONO, BROADCAST_BODY_PT);
        g.fill(UITheme.MENU_TEXT_DIM, 1f * 255f);
        float availW = d.w(w - 20f);
        java.util.List<String> lines = wrap(BROADCAST_TEXT, availW);
        float lineH = d.h(BROADCAST_LINE_H);
        float textTop = y + h * 0.44f;
        for (int i = 0; i < lines.size() && i < 2; i++) {
            fonts.text(g, lines.get(i), d.x(x + 10f), d.y(textTop) + lineH * i);
        }
    }

    /**
     * 立绘展示框 —— 决定书 **L4 覆盖 D9** 之后的位置。
     *
     * <h2>⚠️ 这条决定被改过一次，两版的理由都该记住</h2>
     * <b>D9 原定</b>：这个位置放**官方战役入口**（因为"空着不如放个真动作"）。
     *
     * <p><b>L4 现定</b>：这里应该是**干员立绘循环展示框**，而战役入口属于"作战"卡
     * —— 两个入口通向同一批战役才是重复。所以：
     * <ul>
     *   <li>官方战役入口从这里<b>移除</b>（现在只有「作战」卡一个入口）；</li>
     *   <li>本框固定为立绘展示位，<b>预留、不可点</b>。</li>
     * </ul>
     *
     * <p>⚠️ **不放占位图**（决定书 §7）：素材没到之前就画一个空框 + 说明。
     * 画一张灰图会让人以为"图就是这样的"，比空着更误导。
     */
    private void drawCharacterFrame(float y) {
        float x = colX();
        float w = colW();
        float h = w;   // 展示框改成正方形（长 = 宽）

        // 框体：**不透明**纸面 + 细边框。
        //
        // ⚠️ 第一版用了 0.15 的浅色半透明，结果角色立绘整个透上来 ——
        //    框里的"干员立绘/预留"几个字被压在人物身上，等于没有框。
        //    展示框本来就是要"占住这块地方"，必须不透明。
        g.noStroke();
        g.fill(253, 253, 251, 0.97f * 255f);
        CutShape.square(g, d.x(x), d.y(y), d.w(w), d.h(h));
        g.noFill();
        g.stroke(255, 255, 255, 0.30f * 255f);
        g.strokeWeight(d.len(1f));
        CutShape.insetStroke(g, d.x(x), d.y(y), d.w(w), d.h(h), d.len(2f));
        g.noStroke();

        // 顶部橙色标签条（对应参考稿公告卡的 EXECUTIVE ORDER）
        float tagH = h * 0.155f;
        g.fill(UITheme.MENU_ACCENT, 255f);
        CutShape.square(g, d.x(x), d.y(y), d.w(w * 0.46f), d.h(tagH));
        fonts.apply(g, Fonts.Role.MONO, 13f);   // 原 8，加大
        g.textAlign(PConstants.LEFT, PConstants.CENTER);
        g.fill(255);
        fonts.text(g, "OPERATOR", d.x(x + 8f), d.y(y + tagH / 2f));

        // 记录命中区（点击切下一张）
        showX = d.x(x);
        showY = d.y(y);
        showW = d.w(w);
        showH = d.h(h);

        drawShowImages(x, y, w, h, tagH);

        // 不可点：不写 hit()，也不更新任何 hover 状态
    }

    /**
     * 读取 {@code data/imgs/Mainshow} 下所有立绘并**缩到框内尺寸后缓存**。
     *
     * <p>原图多是 2048×2048，全量解码约 480MB —— 必须缩放后再留。
     */
    private void ensureShowImages() {
        if (showLoaded) {
            return;
        }
        showLoaded = true;
        if (!showImgs.isEmpty()) {
            return;      // 开场已预加载过，直接复用
        }
        java.io.File dir = new java.io.File(g.sketchPath("data/imgs/Mainshow"));
        java.io.File[] files = dir.listFiles((dd, name) -> {
            String n = name.toLowerCase();
            return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".webp");
        });
        if (files == null) {
            return;
        }
        java.util.Arrays.sort(files, java.util.Comparator.comparing(java.io.File::getName));
        pendingShowFiles.addAll(java.util.Arrays.asList(files));
        showAt = g.millis();
    }

    /**
     * 每帧最多解码**一张**入列图片。
     *
     * <p>29 张 2048² 一次性解码会让进入主菜单后卡顿好几秒 —— 分摊到各帧后
     * 单帧开销只有几十毫秒，体感上就不明显了。
     */
    void pumpShowLoading() {
        ensureShowImages();
        if (pendingShowFiles.isEmpty()) {
            return;
        }
        java.io.File f = pendingShowFiles.remove(0);
        processing.core.PImage raw = g.loadImage(f.getPath());
        if (raw != null) {
            raw.resize(480, 0);
            showImgs.add(raw);
        }
    }

    /**
     * 画循环立绘：等比 contain 到框内，切换时交叉淡入。
     *
     * @param x    框左（设计）
     * @param y    框上（设计）
     * @param w    框宽（设计）
     * @param h    框高（设计）
     * @param tagH 顶部标签条高度（设计，立绘从它下面开始）
     */
    private void drawShowImages(float x, float y, float w, float h, float tagH) {
        ensureShowImages();
        if (showImgs.isEmpty()) {
            return;
        }
        int now = g.millis();
        float boxX = d.x(x + 6f);
        float boxY = d.y(y + tagH + 4f);
        float boxW = d.w(w - 12f);
        float boxH = d.h(h - tagH - 10f);

        // 直接切换（不做过渡：淡入淡出会让人物重影或闪白）
        if (now - showAt >= SHOW_INTERVAL_MS) {
            showIdx = (showIdx + 1) % showImgs.size();
            showAt = now;
        }
        g.noStroke();
        g.fill(253, 253, 251);
        g.rect(boxX, boxY, boxW, boxH);
        drawContained(showImgs.get(showIdx), boxX, boxY, boxW, boxH, 255f);
    }

    /**
     * 等比 contain 绘制。
     *
     * @param img   图片
     * @param bx    目标左
     * @param by    目标上
     * @param bw    目标宽
     * @param bh    目标高
     * @param alpha 透明度（0–255）
     */
    private void drawContained(processing.core.PImage img,
            float bx, float by, float bw, float bh, float alpha) {
        float sc = Math.min(bw / img.width, bh / img.height);
        float iw = img.width * sc;
        float ih = img.height * sc;
        g.tint(255f, alpha);
        g.imageMode(PConstants.CORNER);
        g.image(img, bx + (bw - iw) / 2f, by + (bh - ih) / 2f, iw, ih);
        g.noTint();
    }

    /**
     * 重播入场 —— 决定书 D11：真实动作（重放 {@code Phase.LOADING}），
     * 放在左栏**底部**做一行小字按钮。
     *
     * <p>原版把它放在左上角，与文档站自己的按钮挨着；
     * 我们不需要那个位置，但动作是真的，所以留着、挪到底部。
     */
    /**
     * 左上角喇叭按钮：点击循环切换整体音量（100 → 75 → 50 → 25 → 0 → 100）。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     */
    private void drawSpeaker(float mouseX, float mouseY) {
        float inset = DesignSpace.BASE_W * 1.1f / 100f;
        float size = 33f;   // 原 22，放大 1.5 倍
        float x = d.x(inset);
        float y = d.y(inset);
        float w = d.w(size);
        float h = d.h(size);
        speakerX = x;
        speakerY = y;
        speakerW = w;
        speakerH = h;
        speakerHovered = hit(mouseX, mouseY, x, y, w, h);

        g.noStroke();
        g.fill(255, 255, 255, speakerHovered ? 0.18f * 255f : 0f);
        g.rect(x - 4f, y - 4f, w + 8f, h + 8f, 3f);

        // 喇叭本体：方块 + 三角
        g.fill(235f, 235f, 238f, 230f);
        g.rect(x, y + h * 0.34f, w * 0.3f, h * 0.32f);
        g.beginShape();
        g.vertex(x + w * 0.3f, y + h * 0.34f);
        g.vertex(x + w * 0.62f, y + h * 0.12f);
        g.vertex(x + w * 0.62f, y + h * 0.88f);
        g.vertex(x + w * 0.3f, y + h * 0.66f);
        g.endShape(PConstants.CLOSE);

        // 当前音量百分比（常驻，点击后立刻能看到数值变化）
        float vol = com.yestercar.gameopening.AudioTrack.masterGain();
        fonts.apply(g, Fonts.Role.MONO, 11f);
        g.textAlign(PConstants.LEFT, PConstants.CENTER);
        g.fill(235f, 235f, 238f, 230f);
        fonts.text(g, Math.round(vol * 100) + "%",
                x + w + 8f, y + h / 2f);

        if (vol <= 0.01f) {
            g.stroke(255f, 90f, 90f, 240f);
            g.strokeWeight(2f);
            g.line(x + w * 0.66f, y + h * 0.2f, x + w * 0.95f, y + h * 0.8f);
            g.noStroke();
        } else {
            g.noFill();
            g.stroke(235f, 235f, 238f, 220f);
            g.strokeWeight(1.6f);
            for (int i = 1; i <= 2; i++) {
                float r = w * (0.18f + 0.16f * i) * (0.7f + 0.3f * vol);
                g.arc(x + w * 0.62f, y + h * 0.5f, r * 2f, r * 2f,
                        -PConstants.HALF_PI + 0.5f, PConstants.HALF_PI - 0.5f);
            }
            g.noStroke();
        }
    }

    private void drawReplay(float mouseX, float mouseY) {
        // 已按需求移除「重播入场」按钮：不绘制、不响应，命中区清零
        replayX = 0;
        replayY = 0;
        replayW = 0;
        replayH = 0;
        replayHovered = false;
        if (true) {
            return;
        }
        // TASKS-ROUND 2.3：移到**左上角**，距左、距上各 1.1cqw，位于「博士」上方
        float inset = DesignSpace.BASE_W * 1.1f / 100f;
        float x = inset;
        float y = inset;
        float w = DesignSpace.BASE_W * 6.6f / 100f;
        float h = DesignSpace.BASE_H * 0.045f;

        replayX = d.x(x);
        replayY = d.y(y);
        replayW = d.w(w);
        replayH = d.h(h);
        replayHovered = hit(mouseX, mouseY, replayX, replayY, replayW, replayH);

        // hover 才显底 —— 常态只是"一行小字"，不抢注意力
        if (replayHovered || replayPressed) {
            g.noStroke();
            g.fill(255, 255, 255, (replayPressed ? 0.06f : 0.11f) * 255f);
            CutShape.square(g, replayX, replayY, replayW, replayH);
        }

        fonts.apply(g, Fonts.Role.MONO, 10f);
        g.textAlign(PConstants.LEFT, PConstants.CENTER);
        g.fill(UITheme.MENU_TEXT_DIM,
                (replayHovered ? 1f : 0.92f) * 255f);
        fonts.text(g, "▷ 重播入场", replayX + d.w(6f), replayY + replayH / 2f);
    }

    /** 截断到给定宽度（窗口像素）。 */
    private String fit(String text, float maxWidthPx) {
        if (g.textWidth(text) <= maxWidthPx) {
            return text;
        }
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (g.textWidth(text.substring(0, mid) + "…") <= maxWidthPx) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + "…";
    }

    private static boolean hit(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /**
     * 鼠标按下。
     *
     * @param mx 鼠标 x
     * @param my 鼠标 y
     */
    void mousePressed(float mx, float my) {
        // 公告卡已改为「立绘展示框」（L4）：不可点，所以这里只剩重播
        replayPressed = hit(mx, my, replayX, replayY, replayW, replayH);
    }

    /**
     * 鼠标松开 —— 只有按下与松开落在同一块上才算点中。
     *
     * @param mx 鼠标 x
     * @param my 鼠标 y
     * @return 动作
     */
    Action mouseReleased(float mx, float my) {
        Action action = Action.NONE;
        // 点喇叭 → 循环整体音量
        if (hit(mx, my, speakerX, speakerY, speakerW, speakerH)) {
            float v = com.yestercar.gameopening.AudioTrack.masterGain();
            float next = v > 0.8f ? 0.75f : v > 0.55f ? 0.5f
                    : v > 0.3f ? 0.25f : v > 0.05f ? 0f : 1f;
            pendingVolume = next;
            return Action.VOLUME;
        }
        // 点展示框 → 立即切下一张（并重置计时）
        if (!showImgs.isEmpty() && hit(mx, my, showX, showY, showW, showH)
                && showPhase == 0) {
            showPhase = 1;
            showPhaseAt = g.millis();
        }
        // 重播功能已移除，这里不再产出 REPLAY 动作
        replayPressed = false;
        return action;
    }

    /** @return 鼠标是否停在任何可点元素上（给光标用）。 */
    boolean isHovering() {
        return replayHovered;
    }

    /**
     * @param mx 鼠标 x
     * @param my 鼠标 y
     * @return 是否停在立绘展示框上
     */
    boolean hoveringShow(float mx, float my) {
        return hit(mx, my, showX, showY, showW, showH);
    }
}
