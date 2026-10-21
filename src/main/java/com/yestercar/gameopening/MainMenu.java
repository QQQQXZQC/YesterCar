package com.yestercar.gameopening;

import com.yestercar.common.io.CampaignLoader;
import com.yestercar.common.model.Campaign;
import com.yestercar.common.model.Operator;
import com.yestercar.common.ui.DesignSpace;
import com.yestercar.common.util.UITheme;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import processing.core.PApplet;
import processing.core.PConstants;

/**
 * 主菜单 —— 手工移植 ak-ui 的 {@code .ak-dashboard}（数值来自
 * {@code UI/ak-ui-1.1.0/src/scss/_ak-dashboard.scss}，本仓不引入它的任何代码）。
 *
 * <p>右侧菜单按 ak-ui 的三轨 grid 排：
 * 「资源 15% + 当前行动 28% + 命令区 57%」（见 {@link #trackHeights}）。
 * 当前行动轨是白纸面板 + 超大「作战」（{@code .ak-command--operation}）。
 *
 * <p>入场：各元素错峰滑入（{@code data-ak-enter}）；开场遮罩
 * （YC 标记 + 光线）已移到 {@link GameOpeningApp} 的启动动画，
 * 软件打开时播放，不再在主菜单重复。
 */
final class MainMenu {

    // ------------------------------------------------------------------
    // 常量（数值都来自 ak-ui 的 scss，注释标注原文）
    // ------------------------------------------------------------------

    /**
     * 右侧菜单宽占**窗口宽**的百分比 —— 回归 ak-ui 原值 {@code 54.375}。
     *
     * <h2>⚠️ 这个常量改过两次，两次的理由必须都记住</h2>
     *
     * <b>第一次</b>：照抄 {@code 54.375cqw}。但当时那段布局把
     * "菜单高 − 15% 资源条"全给了命令区，**漏掉中间 28% 的当前行动轨道**，
     * 于是命令区高了 {@code 85/57 ≈ 1.49} 倍，卡片跟着变高变宽，右端被切。
     * 当时的结论是"16:9 照抄会切出窗口"，于是把宽度改成窗口宽的 46%。
     *
     * <p><b>那个结论是错的</b> —— 它把症状（右端被切）当成了原因（宽度太大）。
     * 真正的成因是漏了一条轨道。补回三轨后重新实测（1280×720）：
     *
     * <pre>
     *   方案          菜单宽    左边缘    右侧溢出   纵向      与左栏间距
     *   46%          588.8px   766.0px   74.8px   111–623px  260.4px
     *   54.375%      696.0px   658.8px   74.8px   111–623px  153.2px
     * </pre>
     *
     * 两次的右侧溢出**都是 74.8px** —— 说明它与菜单宽无关，
     * 只来自 {@link #MENU_RIGHT_PCT} 那个 {@code -5.84cqw}（原文就是负数，
     * 故意让菜单探出右边界形成出血）。所以宽度可以放心回归原值。
     *
     * <p><b>第二次（本轮）</b>：46 → {@code 54.375}，并把上面两个实测数字
     * 记在这里，替代原来那句推测性的"会切出窗口"。
     */
    private static final float MENU_W_WINDOW_PCT = 58f;

    /**
     * 菜单高度占设计高的百分比（ak-ui 原值 {@code 71.111cqh}，本项已上调）。
     *
     * <h2>⚠️ 从 71.111 提到 82.5 —— 这是对 ak-ui 原值的有意偏离</h2>
     * TASKS-ROUND-2 §2 把牌区改成<b>三行等高</b>，§1.1① 又要求
     * <b>牌高 ≥ 26% 窗口高</b>。两条一起把菜单高度锁死了：
     *
     * <pre>
     *   牌高 ≥ 26% × 1080 = 280.8px = 210.6 设计
     *   三行 + 2 个 gap（gap = 1.111cqw = 16 设计）
     *   ⇒ 菜单高 ≥ 3×210.6 + 2×16 = 663.8 设计 = 设计高的 81.95%
     * </pre>
     *
     * <p>也就是说：**在 71.111% 下，三行等高的牌永远到不了 26%**（实测 22.39%）。
     * 82.5% 留了一点余量。若 ① 的门槛下调，这里应当改回 71.111。
     */
    private static final float MENU_H_PCT = 82.5f;

    /**
     * 菜单顶边距（ak-ui 原值 {@code 15.42cqh}，本项已上调）。
     *
     * <p>⚠️ 随菜单加高从 15.42 调到 <b>7.0</b>：菜单变高后若顶边不动，
     * 底边会顶到窗口 97.4% 处、牌区贴着屏幕边。上移后底边落在
     * {@code 7 + 82.5 = 89.5%}，与参考稿的 86.5% 接近。
     */
    private static final float MENU_TOP_PCT = 7f;

    /**
     * {@code --ak-dashboard-menu-right: -5.84%}（负数 = 出血）。
     *
     * <p>它让菜单右端探出窗口 74.8px —— **与 {@link #MENU_W_WINDOW_PCT} 无关**，
     * 46% 与 54.375% 下都是这个数（实测）。这是 ak-ui 有意的画面延续感，
     * 保留。但"卡片被切到点不到"是另一回事，成因是命令区高度算错（见上）。
     */
    private static final float MENU_RIGHT_PCT = -5.84f;

    /**
     * {@code --ak-dashboard-menu-operation-track: 28%}。
     *
     * <p>⚠️ 这条轨道装的是右侧菜单的<b>主视觉</b>：白色纸面上一个超大字号的
     * 「作战」（{@code .ak-command--operation}，标签 {@code font-size: 40cqh}），
     * 旁边配理智式的资源计数（{@code .ak-san-container--terminal}）。
     * 第一版移植把它整块跳过了、高度全部并给命令区 ——
     * 于是三行卡比参考稿高约 1.49 倍（85/57），主视觉也没了。
     */
    private static final float TRACK_OPERATION_PCT = 28f;

    /** {@code __command-row: translateX(-2.4cqw)}。 */
    private static final float DECK_SHIFT_X_PCT = -2.4f;

    /** {@code __command-row: scaleX(.97)}。 */
    private static final float DECK_SCALE_X = 0.97f;

    // ---- 指令牌网格：2×2 铺满（决定书 D2） ----

    /**
     * 牌间距（设计坐标）—— 取 ak-ui {@code --primary} 的 {@code gap: 1.111cqw}。
     *
     * <p>决定书 D3 说"gap 不放大"。⚠️ 那句指的是 {@code .ak-command} <b>内部</b>
     * 的 {@code gap: 0.65rem}（标签与元信息之间，见
     * {@code CommandCard.INNER_GAP}），不是牌与牌之间的距离。
     * 牌距用 ak-ui 的原始 cqw 值即可，它本来就随容器缩放。
     */
    private static final float CARD_GAP_CQW = 1.111f;

    /** 网格两侧留白（设计坐标，cqw）—— 沿用原 {@code --primary} 的 padL。 */
    private static final float GRID_PAD_LEFT_CQW = 2.778f;

    /**
     * 网格右侧留白（cqw）。
     *
     * <p>⚠️ 取 {@code 5.0} 而不是 {@code 2.778}（= 左侧）：因为
     * {@code menu-right:-5.84cqw} 让整块命令区探出右边界，若右留白与左相同，
     * 最右一列卡片的右缘会落在 **1278px**（距窗口只有 2px），
     * 投影直接被裁掉。留 5.0 后右缘约 1240px，投影完整。
     */
    private static final float GRID_PAD_RIGHT_CQW = 5f;

    /**
     * 三行各自的标签字号（设计坐标）。
     *
     * <p>ak-ui 的 {@code .ak-command__label} 是
     * {@code clamp(1.1rem, 2.7cqw, 2.85rem)} —— 字号**跟着卡宽走**。
     * 我们的三行宽度差得很远（{@code 1fr} 格 ≈ 190 设计宽，
     * {@code 9fr} 窄格 ≈ 110），所以按行给字号，而不是一个全局值。
     */
    /**
     * 牌面主标签的设计字号。
     *
     * <h2>这个数是怎么定的（TASKS-ROUND 3.5）</h2>
     * 3.5 给出三个停止条件，其中一条是
     * <b>「牌面文字 / 牌高」落在 0.14–0.18</b>。牌高由 3.4 的三个杠杆决定
     * （菜单宽 58%、资源条空间并入牌区、inset/trim），所以标签字号要**反推**：
     *
     * <pre>
     *   牌高（设计）= 菜单高 576 × 72% ≈ 414.7
     *   CommandCard 里标签再乘 enlarge（上限 1.5）→ 有效设计字号 = 44 × 1.5 = 66
     *   像素比 = 66 / 414.7 = 0.159   ← 落在 0.14–0.18 内
     * </pre>
     *
     * <p>改牌高就要重算这个数 —— {@link #runAcceptance()} 会把它算出来并判定。
     */
    private static final float LABEL_MAIN = 44f;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ---- 入场时序（ak-ui 的 data-ak-enter 错峰进场） ----
    //
    // 遮罩 + YC 标记 + 光线那套开场层原在这里（2026-10 实现），
    // 应需求移到了 GameOpeningApp 的启动动画（软件打开时播放）。
    // 错峰延迟原本取自 showcase 的 data-delay（850~1550ms）——那套数值
    // 是给「遮罩盖着底下进场」设计的；遮罩移走后，提交代号的前 1.25s
    // 菜单几乎全透明，观感成了「卡 1~2s 才出主界面」（2026-10 修）。
    // 现改为无遮罩直接进场：保留层次感的短错峰，0.4s 内全部启动、
    // 约 1.1s 内就位。单元素动画仍是 createEntrance({duration:700, distance:40})。

    /** 单个元素的进场时长（{@code createEntrance duration: 700}）。 */
    private static final int ENTER_MS = 700;

    /** 进场位移（{@code createEntrance distance: 40}，设计坐标）。 */
    private static final float ENTER_DIST = 40f;

    /** 各元素错峰延迟（毫秒）：自上而下 80ms 一档。 */
    private static final int ENTER_PROFILE_MS = 0;
    private static final int ENTER_RESOURCE_MS = 80;
    private static final int ENTER_OPERATION_MS = 160;
    private static final int ENTER_ROW1_MS = 240;
    private static final int ENTER_ROW2_MS = 320;
    private static final int ENTER_ROW3_MS = 400;

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    private final PApplet g;
    private final DesignSpace d;
    private final Fonts fonts;
    private final String username;

    private final List<Campaign> official = new ArrayList<>();
    private final List<Campaign> custom = new ArrayList<>();

    private Screen screen = Screen.MENU;

    /** 干员卡矩形是否已打印。 */
    private boolean cardRectLogged;

    /** 干员总览页。 */
    private OperatorScreen operatorScreen;

    /** 编队界面（P3.2 闭环的关键一环）。 */
    private SquadScreen squadScreen;

    /** 从哪一屏进编队的 —— 返回箭头要回到这里，而不是直接回主菜单。 */
    private Screen squadReturn = Screen.OFFICIAL;

    /** 「准备作战」选中的战役；进编队时记下，开战时用它。 */
    // ⚠️ static：窗口缩放会重建 MainMenu，"开始作战"会因它为空而报错
    private static Campaign pendingCampaign;

    /** 诊断用的"直达编队页"是否已执行。 */
    private boolean squadJumped;

    /** 诊断：是否已经跳到干员页。 */
    private boolean opsJumped;

    /** 诊断用的"自动开战"是否已执行。 */
    private boolean autoStarted;
    private final BackArrow back;
    /** 左栏（档案 / 播报 / 公告卡 / 重播入场）—— 决定书 D8–D12。 */
    private final LeftColumn left;
    private final OfficialScreen officialScreen;
    private final LevelListScreen customScreen;

    /** 三行命令卡（主菜单本体）。 */
    private final List<CommandCard> row1 = new ArrayList<>();
    private final List<CommandCard> row2 = new ArrayList<>();
    private final List<CommandCard> row3 = new ArrayList<>();

    /** 正在被按住的卡；null = 没有按住。 */
    private CommandCard pressedCard;
    private String hint = "";
    private int hintAt;

    // ---- 「当前行动」纸面面板（28% 轨道） ----

    /** 作战按钮的命中区（窗口坐标，每帧在 {@link #drawOperationPanel} 里更新）。 */
    private float opBtnX, opBtnY, opBtnW, opBtnH;

    private boolean opHovered;
    private boolean opPressed;

    /** hover 位移的插值（0=常态 1=悬起，逐帧趋近 —— {@code transition 280ms} 的简化）。 */
    private float opHoverT;

    /** 入场动画时钟：主菜单第一次绘制时启动；-1 = 还没开始。 */
    private int menuShownAt = -1;

    // ------------------------------------------------------------------
    // 几何
    // ------------------------------------------------------------------

    /**
     * 右侧菜单的竖向切分（设计坐标）—— 纯函数，布局与测试共用。
     *
     * <h2>⚠️ 这条契约改过三次，每次的理由都留着</h2>
     * <ol>
     *   <li><b>ak-ui 原文</b>：{@code resource 15% / operation 28% / 1fr}。
     *       当时漏掉 operation 轨道、命令区吃掉 85%，命令区高 1.49 倍。</li>
     *   <li><b>TASKS-ROUND 3.4</b>：资源条已删，它的 15% 并入牌区 →
     *       {@code 28% / 72%}。</li>
     *   <li><b>TASKS-ROUND-2 §2（本轮）</b>：牌区恢复<b>行结构</b>，
     *       而且 **「作战」card 成为第 1 行** —— 它不再是独立的一条轨道。
     *       所以菜单里只剩一块「牌区」，占满整个菜单高。</li>
     * </ol>
     *
     * <p>注意这次**没有**"当前行动轨"了：作战卡进了牌区。
     * 原来那 28% 的语义（右侧菜单的主视觉）由"第 1 行整行牌"承担。
     *
     * @param menuH 右侧菜单总高（设计坐标）
     * @return {@code {牌区高}}（单元素，保留数组形式以免调用点再改一次）
     */
    static float[] trackHeights(float menuH) {
        return new float[]{menuH};
    }

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------

    MainMenu(PApplet g, DesignSpace d, Fonts fonts, String username) {
        this.g = g;
        this.d = d;
        this.fonts = fonts;
        this.username = username;

        official.addAll(CampaignLoader.loadOfficial());
        custom.addAll(CampaignLoader.loadCustom());
        System.out.println("[MainMenu] 官方 " + official.size() + " 个，自定义 " + custom.size() + " 个");

        back = new BackArrow(g, d);
        left = new LeftColumn(g, d, fonts, username, official);
        officialScreen = new OfficialScreen(g, d, fonts);
        customScreen = new LevelListScreen(g, d, fonts);
        officialScreen.setItems(official);
        customScreen.setItems(custom);

        // 编队界面：干员表来自 data/operators.json（与战斗读同一份定义）
        squadScreen = new SquadScreen(g, d, fonts);
        squadScreen.setOperators(new ArrayList<>(Operator.loadAll().values()));

        operatorScreen = new OperatorScreen(g, d, fonts);
        operatorScreen.setItems(new ArrayList<>(Operator.loadAll().values()));
        buildCards();

    }

    /**
     * §6 验收（决定书要求，可复现）：几何自查 + 文本度量。
     *
     * <p>用 {@code -Dyestercar.ui.verify=true} 打开。
     *
     * <h2>为什么这些检查不是同义反复</h2>
     * 单看"实际 == 设计值换算"确实是同义反复 —— 位置本来就是那么算的。
     * 所以这里检查的是**不变量**，它们能独立抓住错误：
     * <ul>
     *   <li>三轨之和 == 菜单高（漏掉一条轨道时立刻炸）；</li>
     *   <li>2×2 四格等宽等高（换算前后不一致时炸 —— 我确实因此重叠过 3px）；</li>
     *   <li>每张卡都在画布内（越界时炸）；</li>
     *   <li>卡片之间、左右两栏之间不重叠。</li>
     * </ul>
     *
     * @return 报告行
     */
    List<String> runAcceptance() {
        AcceptanceCheck chk = new AcceptanceCheck(g, d);
        // 牌区的牌与名字，多处要用
        String[] cardNames = {"自定义关卡", "干员", "退出"};
        List<CommandCard> deckCards = allCards();

        chk.section("几何：牌区占满菜单（TASKS-ROUND-2 §2：作战成为第 1 行）");
        float[] m = menuBox();
        float[] t = trackHeights(m[3]);
        chk.near("牌区 = 菜单高", t[0], m[3]);
        chk.near("菜单宽 = 58%", m[2], DesignSpace.BASE_W * 0.58f);

        chk.section("几何：牌区（三行，TASKS-ROUND-2 §2）");
        float[] row0 = gridCell(0, 0);
        float[] row1 = gridCell(1, 0);
        float[] row2a = gridCell(2, 0);
        float[] row2b = gridCell(2, 1);
        chk.equalSizes("三行等高", row0[3], row1[3], row2a[3]);
        chk.near("行间隙 0-1", row1[1] - (row0[1] + row0[3]),
                DesignSpace.BASE_W * CARD_GAP_CQW / 100f);
        chk.near("行间隙 1-2", row2a[1] - (row1[1] + row1[3]),
                DesignSpace.BASE_W * CARD_GAP_CQW / 100f);
        chk.equalSizes("第 3 行两格等宽", row2a[2], row2b[2]);
        chk.near("第 3 行列间隙", row2b[0] - (row2a[0] + row2a[2]),
                DesignSpace.BASE_W * CARD_GAP_CQW / 100f);
        chk.equalSizes("第 1、2 行整行同宽", row0[2], row1[2]);
        chk.near("三行 + 两间隙 = 牌区高",
                row0[3] * 3f + (row1[1] - (row0[1] + row0[3])) * 2f, t[0]);
        chk.lines().add(String.format("  %-14s %.2f:1（只报告，不判定）",
                "长宽比", row2a[2] / row2a[3]));

        // ---- TASKS-ROUND-2 §1.1：三个指标（③ 改为宽度基准） ----
        //
        // 上一轮 ③ 是「标签字号 / 牌高 ∈ 0.14–0.18」，那是从**横牌**（约 2.9:1）
        // 推的；牌变竖之后必然不成立（实测 0.085）。
        // 本轮改为**宽度基准 + 禁止截断**，并明确要求贴**实际渲染字号**。
        chk.section("尺寸指标（§1.1：① 牌高 ② 字号 ③ 不截断）");
        float cardHpx = d.h(row2a[3]);
        float cardWpx = d.w(row2a[2]);
        float winH = g.height;
        chk.lines().add(String.format("  牌面        %.1f x %.1f px  长宽比 %.2f:1（只报告）",
                cardWpx, cardHpx, cardWpx / cardHpx));

        // ① 牌高 ≥ 26% 窗口高
        float r1 = cardHpx / winH * 100f;
        chk.lines().add(String.format("  ① 牌高/窗口高     %.2f%%   要求 ≥26%%%s",
                r1, r1 >= 26f ? "  ✓" : "  ✗"));
        if (r1 < 26f) {
            chk.failures().add(String.format("牌高只有窗口高的 %.2f%%，要求 ≥26%%", r1));
        }

        // ② 主标签字号 ≥ 3.2% 窗口高（用实际渲染字号，不用名义值）
        float labelPx = deckCards.get(0).lastLabelPx();
        float r2 = labelPx / winH * 100f;
        chk.lines().add(String.format("  ② 标签字号/窗口高   %.2f%%   要求 ≥3.2%%%s",
                r2, r2 >= 3.2f ? "  ✓" : "  ✗"));
        if (r2 < 3.2f) {
            chk.failures().add(String.format("标签字号只有窗口高的 %.2f%%，要求 ≥3.2%%", r2));
        }

        // ③ 不截断：实际渲染字号 × 字符数 ≤ 牌内可用宽
        //    —— 必须贴实际值。用名义字号推算会漏掉"被 fit() 缩过"的情况。
        chk.lines().add("  ③ 不截断（实际渲染字号 × 字符数 ≤ 可用宽）：");
        for (int i = 0; i < deckCards.size() && i < cardNames.length; i++) {
            CommandCard c = deckCards.get(i);
            float eff = c.lastLabelPx();
            int chars = cardNames[i].length();
            float need = eff * chars;
            float avail = c.width() - d.w(CommandCard.padXDesign()) * 2f
                    * Math.min(c.enlargeFactor(), 1.5f);
            boolean ok = need <= avail;
            chk.lines().add(String.format(
                    "       %-6s %5.1fpx × %d = %6.1f  可用 %6.1f  %s",
                    cardNames[i], eff, chars, need, avail, ok ? "✓" : "✗ 截断"));
            if (!ok) {
                chk.failures().add(String.format(
                        "「%s」被截断：%.1fpx × %d 字 = %.1f > 可用 %.1f",
                        cardNames[i], eff, chars, need, avail));
            }
        }

        chk.section("几何：部件矩形（窗口坐标，逐项与换算值比）");
        // 作战卡 = 牌区第 1 行整行
        float[] opCell = gridCell(0, 0);
        float[] op = operationPanelBox();
        chk.inside("作战卡", d.x(op[0]), d.y(op[1]), d.w(op[2]), d.h(op[3]));
        chk.near("作战卡左缘", d.x(op[0]), d.x(opCell[0]));
        chk.near("作战卡宽 = 整行", d.w(op[2]), d.w(opCell[2]));

        String[] names = {"自定义关卡", "干员", "退出"};
        float[][] cells = {gridCell(1, 0), gridCell(2, 0), gridCell(2, 1)};
        List<CommandCard> all = allCards();
        for (int i = 0; i < all.size() && i < names.length; i++) {
            CommandCard c = all.get(i);
            chk.inside(names[i], c.x(), c.y(), c.width(), c.height());
            chk.near(names[i] + " 左缘", c.x(), d.x(cells[i][0]));
            chk.near(names[i] + " 上缘", c.y(), d.y(cells[i][1]));
            chk.near(names[i] + " 宽", c.width(), d.w(cells[i][2]));
        }

        chk.section("几何：不重叠");
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                CommandCard a = all.get(i);
                CommandCard b = all.get(j);
                chk.noOverlap(names[i], a.x(), a.y(), a.width(), a.height(),
                        names[j], b.x(), b.y(), b.width(), b.height());
            }
        }
        float leftRight = d.x(DesignSpace.BASE_W * (2.05f + 22f) / 100f);
        float menuLeft = d.x(m[0]);
        if (menuLeft - leftRight <= 0f) {
            chk.failures().add(String.format(
                    "左栏与右菜单重叠：左栏右缘 %.1f，菜单左缘 %.1f", leftRight, menuLeft));
        } else {
            chk.lines().add(String.format(
                    "  左栏右缘 %.1f  <  菜单左缘 %.1f  → 中间区 %.1f px",
                    leftRight, menuLeft, menuLeft - leftRight));
        }

        chk.section("文本度量（墨迹 vs PFont 推算）");
        for (String tx : new String[]{"自定义关卡", "干员", "退出"}) {
            chk.lines().add(chk.textMetric(fonts, Fonts.Role.COMMAND, LABEL_MAIN, tx));
        }
        chk.lines().add(chk.textMetric(fonts, Fonts.Role.MONO, 10f, "2 个官方关卡"));

        chk.section("结论");
        if (chk.failures().isEmpty()) {
            chk.lines().add("  ✓ 全部通过（几何容差 " + AcceptanceCheck.tolerance() + "px）");
        } else {
            chk.lines().add("  ✗ 未通过 " + chk.failures().size() + " 项：");
            for (String f : chk.failures()) {
                chk.lines().add("    - " + f);
            }
        }
        return chk.lines();
    }

    /**
     * @return 右侧菜单的底边（设计坐标）—— 左列要与之对齐（TASKS-ROUND 2.1）
     */
    float menuBottom() {
        float[] m = menuBox();
        return m[1] + m[3];
    }

    /**
     * 右侧菜单的矩形（设计坐标）。
     *
     * @return {@code {x, y, w, h}}
     */
    private float[] menuBox() {
        // 宽按窗口比例算（见 MENU_W_WINDOW_PCT 的注释），先换回设计单位
        float mw = d.x(DesignSpace.BASE_W) / d.sx() * MENU_W_WINDOW_PCT / 100f;
        float mh = DesignSpace.BASE_H * MENU_H_PCT / 100f;
        float right = DesignSpace.BASE_W * MENU_RIGHT_PCT / 100f;
        return new float[]{DesignSpace.BASE_W - right - mw,
                DesignSpace.BASE_H * MENU_TOP_PCT / 100f, mw, mh};
    }

    /**
     * 命令区整块区域的矩形（设计坐标）—— 2×2 网格的容器。
     *
     * <p>它占菜单总高的 <b>57%</b>（资源 15% + 当前行动 28% 之外的剩余）。
     * 上一版漏掉当前行动轨道时这里是 85%，卡片因此高了 1.49 倍 ——
     * 现在两处共用 {@link #trackHeights} 的同一份推导。
     *
     * @return {@code {x, y, w, h}}
     */
    private float[] deckTrack() {
        float[] m = menuBox();
        return new float[]{m[0], m[1], m[2], m[3]};
    }

    /**
     * 「作战」卡的矩形 —— 牌区**第 1 行整行**（TASKS-ROUND-2 §2）。
     *
     * <p>它原来是一条独立的 28% 轨道，现在成了牌区的第一行。
     * 内距沿用 {@code __right-menu > .ak-san-container} 的
     * {@code margin: 0.556cqw 0 0 2.778cqw}，高度撑满该行。
     *
     * @return {@code {x, y, w, h}}
     */
    private float[] operationPanelBox() {
        float[] row = gridCell(0, 0);
        // 作战占满整行（三列布局下要跨 3 格 + 2 个 gap）
        float[] last = gridCell(0, 2);
        float right = last[0] + last[2];
        return new float[]{row[0], row[1], right - row[0], row[3]};
    }

    /**
     * 牌区某格的矩形（设计坐标）—— **行结构**（TASKS-ROUND-2 §2）。
     *
     * <pre>
     *   第 0 行：作战（整行，含时间块）
     *   第 1 行：自定义关卡（整行）
     *   第 2 行：干员 | 退出（两等分）
     * </pre>
     *
     * <p>行高 = {@code (牌区高 − 2 × gap) / 3}；第 3 行两格宽 =
     * {@code (牌区宽 − gap) / 2}。gap 用 ak-ui 的 {@code 0.65rem} 换算值，
     * <b>不随放大而变大</b>。
     *
     * <h2>⚠️ 不套 {@code __command-deck} 的 scaleY(1.085)</h2>
     * 牌区占满整个菜单高，再乘 1.085 会溢出菜单底边、压到画面外。
     *
     * <h2>⚠️ 右留白大于左留白</h2>
     * {@code menu-right:-5.84cqw} 让牌区探出右边界，左右等留白时
     * 最右列的右缘会距窗口只有几像素，投影被裁。
     *
     * @param row 行 0/1/2
     * @param col 列；第 0、1 行恒为 0（整行），第 2 行取 0/1
     * @return {@code {x, y, w, h}}
     */
    private float[] gridCell(int row, int col) {
        float[] deck = deckTrack();
        float gap = DesignSpace.BASE_W * CARD_GAP_CQW / 100f;

        // 横向：translateX(-2.4cqw) + scaleX(0.97)，原点在左中
        float shift = -DesignSpace.BASE_W * 2.4f / 100f;
        float scaleX = 0.97f;
        float innerX = deck[0] + shift + deck[2] * (1f - scaleX) * 0.5f
                + DesignSpace.BASE_W * GRID_PAD_LEFT_CQW / 100f;
        float innerW = deck[2] * scaleX
                - DesignSpace.BASE_W * (GRID_PAD_LEFT_CQW + GRID_PAD_RIGHT_CQW) / 100f;

        // 纵向：3 行，2 个 gap
        float cellH = (deck[3] - gap * 2f) / 3f;
        float y = deck[1] + (cellH + gap) * row;

        if (row < 2) {
            return new float[]{innerX, y, innerW, cellH};
        }
        float cellW = (innerW - gap) / 2f;
        return new float[]{innerX + (cellW + gap) * col, y, cellW, cellH};
    }

    /**
     * 由卡片高度反推放大倍数（决定书 D3）。
     *
     * <p>基准是 ak-ui 三行布局时的一张牌高（约 88 设计高），2×2 的格子更高，
     * 所以倍数 = 当前高 / 基准高。四张牌同高，倍数自然也一致。
     *
     * @param cardH 卡片高度（设计坐标）
     * @return 放大倍数，夹在 1.0~1.5 之间
     */
    /**
     * 建 3 张牌（作战卡是手绘的，不在这里）。
     *
     * <pre>
     *   第 0 行：作战（整行）        ← drawOperationPanel 直接画在 gridCell(0,0)
     *   第 1 行：自定义关卡（整行）   ← row1[0]
     *   第 2 行：干员 | 退出          ← row2[0], row2[1]
     * </pre>
     */
    private void buildCards() {
        row1.clear();
        row2.clear();
        row3.clear();

        addGridCard(row1, 1, 0);   // 自定义关卡（整行）
        addGridCard(row2, 2, 0);   // 干员
        addGridCard(row2, 2, 1);   // 退出
    }

    /**
     * 往牌区放一张卡。
     *
     * @param list 目标列表
     * @param row  行 0~2
     * @param col  列（整行行恒 0）
     */
    private void addGridCard(List<CommandCard> list, int row, int col) {
        CommandCard c = new CommandCard(g, d, fonts);
        list.add(c);
        float[] b = gridCell(row, col);
        c.at(d.x(b[0]), d.y(b[1]), d.w(b[2]), d.h(b[3]));
        c.enlarge(fitEnlarge(b[3]));
    }

    private static float fitEnlarge(float cardH) {
        // 88 = 三行布局下的单元格高（264.7 / 3），作为"未放大"的参照
        return Math.max(1f, Math.min(1.5f, cardH / 88f));
    }

    /**
     * 每帧刷新卡面文案（关卡数是活的，不能只在构造时写死）。
     *
     * <h2>面色：以**纸面**为主（用户定的 Q2）</h2>
     * 参考稿的主导航几乎全是近白纸面，深色只留给最右那张窄牌「仓库」。
     * 上一版把「选择关卡」做成深石墨当视觉锚点 —— 那是我们自己加的语义，
     * 没有 ak-ui 依据，且与参考稿的整体明度完全相反。
     *
     * <p>现在的分布照参考稿的语言：
     * <pre>
     *   第 1 行  选择关卡(纸) │ 自定义关卡(纸)
     *   第 2 行  干员(柔和灰，禁用)
     *   第 3 行  退出(深，窄格)      ← 对应参考稿的「仓库」
     * </pre>
     */
    private void fillContent() {
        // 水印用 Remix 图标（D5）。
        row1.get(0).label("自定义关卡")
                .meta(custom.isEmpty() ? "还没有 —— 用战役编辑器做一张"
                        : custom.size() + " 个 · 可写")
                .watermarkIcon("file-list-3-line")
                .labelSize(LABEL_MAIN)
                .tone(CommandCard.Tone.PAPER);

        row2.get(0).label("干员").meta("29 名 · 7 职业")
                .watermarkIcon("profile-line")
                .labelSize(LABEL_MAIN)
                .tone(CommandCard.Tone.MUTED).disabled(false);   // 干员页已实现

        // 退出不放水印：14 个图标里没有表示"退出"的，硬凑比空着更糟。
        row2.get(1).label("退出").meta("关闭游戏")
                .watermarkIcon("")
                .labelSize(LABEL_MAIN)
                .tone(CommandCard.Tone.DARK);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    void draw(float mouseX, float mouseY) {
        int now = g.millis();

        if (screen == Screen.MENU) {
            // 入场时钟：第一次落在本屏时启动（从子页返回不重播）
            if (menuShownAt < 0) {
                menuShownAt = now;
            }
            float ent = now - menuShownAt;

            fillContent();

            // 各元素错峰进场：位移量按 TERMINAL 曲线随时间收敛到 0（design 坐标）
            // 左栏（D8–D12）：档案 / 播报 / 公告卡 / 重播入场
            float pLeft = Easing.TERMINAL.progressFromStart(ent, ENTER_PROFILE_MS, ENTER_MS);
            left.draw(mouseX, mouseY, -d.w(ENTER_DIST) * (1f - pLeft), menuBottom());

            // D4：资源条已删除（没有真实资源数据）。只保留右侧时钟 —— 见 drawClock()。
            drawClock();

            drawOperationPanel(ent, mouseX, mouseY);

            enterRow(row1, Easing.TERMINAL.progressFromStart(ent, ENTER_ROW1_MS, ENTER_MS), d.w(ENTER_DIST), 0f);
            enterRow(row2, Easing.TERMINAL.progressFromStart(ent, ENTER_ROW2_MS, ENTER_MS), d.w(ENTER_DIST), 0f);
            enterRow(row3, Easing.TERMINAL.progressFromStart(ent, ENTER_ROW3_MS, ENTER_MS), 0f, d.h(ENTER_DIST));
            for (CommandCard c : allCards()) {
                c.update(mouseX, mouseY, now);
                c.draw();
            }
            if (!cardRectLogged) {
                cardRectLogged = true;
                CommandCard op = row2.get(0);
                System.out.printf("[诊断] 干员卡 窗口矩形(%.0f,%.0f,%.0f,%.0f) 窗口 %dx%d%n",
                        op.x(), op.y(), op.width(), op.height(), g.width, g.height);
            }
        } else {
            // ⚠️ 返回箭头必须画在**屏幕内容之后**。
            // 两个子页面都从 g.background() 开始（那是清屏），
            // 先画箭头会被清掉 —— 官方页曾经因此看起来"没有返回按钮"（实测踩过）。
            if (screen == Screen.SQUAD) {
                squadScreen.draw(mouseX, mouseY);
            } else if (screen == Screen.OFFICIAL) {
                officialScreen.draw(mouseX, mouseY);
            } else {
                customScreen.draw(mouseX, mouseY);
            }
            if (screen == Screen.OPERATORS) {
                operatorScreen.draw(mouseX, mouseY);
            }
            back.hover(mouseX, mouseY);
            back.draw();
            // 干员详情打开时，返回按钮是**不可用**的：压暗并盖一层遮罩，
            // 明确告诉用户"现在点它是没用的"，而不是让人以为程序坏了。
            boolean backDisabled = screen == Screen.OPERATORS && operatorScreen.hasDetail();
            if (backDisabled) {
                g.noStroke();
                g.fill(0f, 0f, 0f, 150f);
                g.rect(0, 0, d.w(108f), d.h(96f));

            }
            back.drawTitle(switch (screen) {
                case SQUAD -> "编队";
                case OFFICIAL -> "官方关卡";
                case OPERATORS -> "干员";
                default -> "自定义关卡";
            });
        }

        drawHint(now);

        // 诊断：-Dyestercar.ui.gotoops=1 直接进干员总览页（截图用）
        if (screen == Screen.MENU && !opsJumped
                && "1".equals(System.getProperty("yestercar.ui.gotoops"))) {
            opsJumped = true;
            goScreen(Screen.OPERATORS);
        }
        // 主界面每帧解一张立绘图（分摊开销，避免进菜单后卡顿）
        left.pumpShowLoading();
        maybeJumpToSquad();
        maybeAutoStart();
    }

    /**
     * 诊断：{@code -Dyestercar.ui.squadauto=1} 自动勾选两名干员并开战。
     *
     * <p>我点不了鼠标，所以用这个开关把"选人 → 开始作战 → 打开战斗窗口"
     * 整条链路跑通，日志就是证据。
     */
    private void maybeAutoStart() {
        if (autoStarted || screen != Screen.SQUAD) {
            return;
        }
        // 必须等于 "1"：传空值（-Dkey=）也会让 getProperty 返回非 null，
        // 于是"只想截图"也会被自动开战打断（实测踩过）。
        if (!"1".equals(System.getProperty("yestercar.ui.squadauto"))) {
            return;
        }
        autoStarted = true;
        // 可用 -Dyestercar.ui.squadids=a,b,c 指定，便于截图不同编队
        String idsProp = System.getProperty("yestercar.ui.squadids");
        String[] ids = idsProp == null || idsProp.isBlank()
                ? new String[]{"guard_01", "sniper_01"}
                : idsProp.split(",");
        squadScreen.selectForTest(ids);
        String pickProp = System.getProperty("yestercar.ui.squadpick");
        if (pickProp != null && !pickProp.isBlank()) {
            int slot = Integer.parseInt(pickProp.trim());
            squadScreen.openPickForTest(slot);
            System.out.println("[诊断] 打开选择页: 第 " + (slot + 1) + " 格");
        }
        System.out.println("[诊断] 自动编队: " + squadScreen.selectedIds());
        startBattle();
    }

    /** 诊断：{@code -Dyestercar.ui.gotosquad=official|custom} 直接进编队页（截图/联调用）。 */
    private void maybeJumpToSquad() {
        if (squadJumped) {
            return;
        }
        String mode = System.getProperty("yestercar.ui.gotosquad");
        if (mode == null || screen != Screen.MENU) {
            return;
        }
        squadJumped = true;
        Campaign c = "official".equalsIgnoreCase(mode)
                ? official.get(0) : custom.get(0);
        pendingCampaign = c;
        squadReturn = "official".equalsIgnoreCase(mode) ? Screen.OFFICIAL : Screen.CUSTOM;
        squadScreen.setCampaignName(c.name());
        squadScreen.setCampaign(c.meta());
        squadScreen.clearSelection();
        goScreen(Screen.SQUAD);
        System.out.println("[诊断] 直接进入编队页: " + c.name());
    }

    /** 把一整行的入场进度写到各卡（位移 = 距离 × 剩余量）。 */
    private void enterRow(List<CommandCard> row, float p, float dx, float dy) {
        float k = 1f - p;
        for (CommandCard c : row) {
            c.enter(dx * k, dy * k, p);
        }
    }

    /**
     * 右侧时钟 —— 决定书 D4 唯一保留的遥测。
     *
     * <h2>为什么资源条被删掉，时钟留下</h2>
     * 决定书 §5 的原则是"每个可见元素必须绑定真实动作 / 显示真实数据 / 删掉"。
     * 参考稿的资源条是「龙门币 / 合成玉 / 源石」三个游戏货币 ——
     * YesterCar 没有货币系统，那几个数字只能是**编的**，所以整条删除。
     *
     * <p>时钟显示的是**真实时间**，符合"显示真实数据"，因此保留。
     * 之前那条 `官方 2   自定义 7` 也是真实数据，但它已经在左栏档案的
     * 圆环里有了一个更强的表达（关卡总数），重复展示只会削弱圆环。
     *
     * <p>位置：右侧菜单右上角，与 {@code __system-line} 的基线一致。
     */
    private void drawClock() {
        float[] m = menuBox();

        // ⚠️ 这里必须**钳到窗口内**，不能按 menuBox 的右缘算。
        //
        // 起因：`menu-right:-5.84cqw` 让菜单整体探出右边界，于是
        // `m[0] + 2.778cqw + 48.82cqw` 落到了设计宽 1484（窗口 1649），
        // 而窗口只有 1600 —— 时钟被画到画布外，看起来"消失了"（实测）。
        // 时钟是真实数据，不能因为布局出血就丢掉。
        float rightEdge = Math.min(
                m[0] + DesignSpace.BASE_W * (2.778f + 48.82f) / 100f,
                DesignSpace.BASE_W - 3.4f);
        float midY = m[1] + DesignSpace.BASE_W * 1f / 100f;

        // 可读性：**文字阴影**，不是底衬板。
        //
        // 上一版试过画一条渐隐暗色带，结果 12 层矩形叠成一块明显的灰板 ——
        // 比"看不清"还糟。改成先画一遍深色偏移、再画白字，
        // 这是 `text-shadow` 的标准做法，不引入任何色块。
        String clock = LocalTime.now().format(CLOCK);
        fonts.apply(g, Fonts.Role.MONO, 11f);
        g.textAlign(PConstants.RIGHT, PConstants.CENTER);
        g.fill(0f, 0f, 0f, 0.75f * 255f);
        fonts.text(g, clock, d.x(rightEdge) + 1f, d.y(midY) + 1f);

        g.fill(255, 255, 255, 0.90f * 255f);
        fonts.text(g, clock, d.x(rightEdge), d.y(midY));

        // 时钟左边一小段细线（__line--grow：把视线引向时间）
        float clockW = g.textWidth(clock) / d.sx();
        float lineW = DesignSpace.BASE_W * 3f / 100f;
        float lineRight = rightEdge - clockW - DesignSpace.BASE_W * 1.2f / 100f;
        g.stroke(255f, 255f, 255f, 0.42f * 255f);
        g.strokeWeight(1f);
        g.line(d.x(lineRight - lineW), d.y(midY), d.x(lineRight), d.y(midY));
        g.noStroke();
    }

    /**
     * 「当前行动」纸面面板的矩形（设计坐标）——
     * 照 {@code __right-menu > .ak-san-container--terminal} 的定位：
     * {@code margin: 0.556cqw 0 0 2.778cqw; height: calc(100% - 1.389cqw)}，
     * 右缘与命令卡的右缘列对齐（比卡片区右缘宽出约 0.4cqw，与原文一致）。
     *
     * @return {@code {x, y, w, h}}
     */
    /**
     * 「当前行动」纸面面板 —— 28% 轨道的主视觉，对应 ak-ui 的
     * {@code .ak-command--operation}（超大「作战」按钮）+
     * {@code .ak-san-container--terminal}（左列资源计数）。
     *
     * <p>纸面 = 命令卡的 {@code --paper} 底。左列装"理智样式"的关卡计数：
     * 原版图本是{{理智}}素材，但 ak-ui 的 40 个美术素材已全部移出仓库、
     * 改为 CDN 外链且许可不明（{@code assets/README.md}）——
     * 不能抓取，这里以几何占位近似（见 {@link #drawSanColumn}）。
     * 点击作战区 = 进入官方关卡页（与「选择关卡」同一动作）。
     *
     * @param entMs   入场经过的毫秒
     * @param mouseX  鼠标 x（窗口坐标）
     * @param mouseY  鼠标 y（窗口坐标）
     */
    private void drawOperationPanel(float entMs, float mouseX, float mouseY) {
        float p = Easing.TERMINAL.progressFromStart(entMs, ENTER_OPERATION_MS, ENTER_MS);
        if (p <= 0f) {
            return;
        }
        float a = p;
        float[] b = operationPanelBox();
        float px = b[0], py = b[1], pw = b[2], ph = b[3];

        // 作战按钮区（右列 72.5%）—— 命中按最终位置算，与命令卡一致
        float btnX = px + pw * 0.275f;
        opBtnX = d.x(btnX);
        opBtnY = d.y(py);
        opBtnW = d.w(px + pw - btnX);
        opBtnH = d.h(ph);
        opHovered = opHit(mouseX, mouseY);

        // hover 位移过渡（原文 transition 280ms 的逐帧简化）
        opHoverT += ((opHovered ? 1f : 0f) - opHoverT) * 0.18f;

        g.pushMatrix();
        g.translate(d.w(ENTER_DIST) * (1f - p), 0f);
        g.noStroke();

        // 纸面底（--paper 与顶部高光渐层的合成，近似不透明白纸）
        // ⚠️ 必须用 4 参形式：fill(int, float) 会把 alpha 乘上 rgb 的 alpha 字节，
        // 24 位色 0xFDFDFB 的该字节是 0 → 画出来完全透明（实测踩过）。
        g.fill(253, 253, 251, 0.97f * 255f * a);
        CutShape.square(g, d.x(px), d.y(py), d.w(pw), d.h(ph));

        // ::after —— 橙色小舌头（出血到面板底边之下一点）
        float tabW = pw * 0.3875f;
        float tabH = ph * 0.0335f;
        float tabX = px + pw * 0.2875f;
        float tabY = py + ph * 1.017f - tabH;
        g.fill(UITheme.MENU_ACCENT, 255f * a);
        CutShape.square(g, d.x(tabX), d.y(tabY), d.w(tabW), d.h(tabH));

        drawSanColumn(px, py, pw, ph, a);
        drawOperationButton(btnX, py, px + pw - btnX, ph, a);

        g.popMatrix();
    }

    /**
     * 左列时间块 —— 决定书 L8 / D15。
     *
     * <h2>为什么把"可选关卡数"换成时间</h2>
     * L8 的判断是：这块在参考稿里是**战斗相关的状态读数**（理智 132/135），
     * 而"可选关卡数"是一个静态的资产管理数字，放在"作战"卡里语义不对。
     * 时间更贴近仪表盘的气质，而且它**每秒都在变** —— 一个真正"活着"的读数。
     *
     * <p>结构上照 {@code .ak-san} 的比例：上方大字 + 下方深色条。
     * 大字是 {@code HH:mm}（24 小时制），深色条里是 {@code YYYY/MM/DD}。
     */
    private void drawSanColumn(float px, float py, float pw, float ph, float a) {
        float colW = pw * 0.275f;

        // 灰底方块（参考稿的 figure 区）
        float figX = px + colW * 0.055f;
        float figY = py + ph * 0.055f;
        float figW = colW * 0.89f;
        float figH = ph * 0.745f;
        g.fill(235, 236, 235, 0.92f * 255f * a);
        CutShape.square(g, d.x(figX), d.y(figY), d.w(figW), d.h(figH));

        // ---- TASKS-ROUND 2.4：两行时间，间距与贴底距离都有明确要求 ----
        //
        //   ① HH:mm 大字在上；
        //   ② YYYY/MM/DD 小字**贴块底边**，距底 0.35cqw；
        //   ③ 两行之间 ≥ 0.9cqw。
        //
        // 两个都是 cqw 量，所以要换算成设计坐标再乘——不能直接用像素。
        float cqw = DesignSpace.BASE_W / 100f;
        float bottomGap = 0.35f * cqw;
        float lineGap = 0.9f * cqw;

        float figBottom = figY + figH;
        float smallSize = figH * 0.20f;

        // ⚠️ 大字字号必须**量过再定**，不能按 figH 的百分比硬给。
        //    上一版 `figH * 0.44` 在 282px 高的行上算到 92px，
        //    而 "00:47" 在那个字号下比灰块还宽 —— 直接溢出到卡片外（实测左溢 100px）。
        //    这里先按目标字号试排，放不下就等比缩到能用为止。
        String hhmmPreview = "00:47";
        float bigSize = figH * 0.44f;
        // ⚠️ 单位必须统一：figW 是**设计坐标**，而 g.textWidth 返回的是**像素**。
        //    上一版直接拿设计值当像素比，于是窗口越大（sx 越大）、
        //    textWidth 越大而 figW*0.92 不变 → 字号被越缩越小，
        //    表现为"全屏后时间不放大反而更小"（用户报告的 §5）。
        float availW = d.w(figW) * 0.92f;
        fonts.apply(g, Fonts.Role.MONO, bigSize);
        float measured = g.textWidth(hhmmPreview);
        if (measured > availW && measured > 0f) {
            bigSize = Math.max(10f, bigSize * (availW / measured));
        }

        // 小字：底边贴块底（距底 bottomGap）
        float smallBottom = figBottom - bottomGap;
        java.time.LocalDate day = java.time.LocalDate.now();
        String ymd = String.format("%04d/%02d/%02d",
                day.getYear(), day.getMonthValue(), day.getDayOfMonth());
        // 小字同样要量宽："2026/10/18" 10 个字符，比 "00:47" 长得多。
        // 只按 figH 的百分比给字号，日期会从灰块左侧溢出去（实测溢出 20px）。
        {
            float smallTry = smallSize;
            fonts.apply(g, Fonts.Role.MONO, smallTry);
            float m = g.textWidth(ymd);
            if (m > availW && m > 0f) {
                smallSize = Math.max(7f, smallTry * (availW / m));
            }
        }
        fonts.apply(g, Fonts.Role.MONO, smallSize);
        g.textAlign(PConstants.CENTER, PConstants.BOTTOM);
        g.fill(23, 25, 27, 0.70f * a * 255f);
        fonts.text(g, ymd, d.x(figX + figW / 2f), d.y(smallBottom));

        // 大字：底边 = 小字顶 − lineGap，于是间距恰为 lineGap（≥0.9cqw）
        float smallTop = smallBottom - smallSize * 1.15f;
        float bigBottom = smallTop - lineGap;
        java.time.LocalTime now = java.time.LocalTime.now();
        String hhmm = String.format("%02d:%02d", now.getHour(), now.getMinute());
        if (System.getProperty("yestercar.ui.timelog") != null) {
            System.out.printf("[时间块] 窗口 %dx%d  卡片 %.1fpx  灰块 %.1fpx  "
                            + "大字 %.1f设计 = %.0fpx  可用 %.1fpx  实测宽 %.1fpx%n",
                    g.width, g.height, d.h(ph), d.h(figH),
                    bigSize, bigSize * d.sx(), availW, g.textWidth(hhmmPreview));
        }
        fonts.apply(g, Fonts.Role.MONO, bigSize);
        g.textAlign(PConstants.CENTER, PConstants.BOTTOM);
        g.fill(23, 25, 27, 255f * a);
        fonts.text(g, hhmm, d.x(figX + figW / 2f), d.y(bigBottom));

        // 实时指示点（右上角）—— 说明这块在走，不是静态图
        float dotD = figH * 0.075f;
        g.fill(UITheme.MENU_ACCENT, 255f * a);
        g.ellipse(d.x(figX + figW * 0.90f), d.y(figY + figH * 0.12f), d.w(dotD), d.w(dotD));
    }

    /**
     * 「作战」按钮 —— {@code .ak-command--operation} 的内容三件套：
     * 斜置水印 / 大标签 / 「当前」徽标 + 一行 meta。
     *
     * <h2>⚠️ 上一版有一个裁切 bug</h2>
     * 标签用 {@code textAlign(TOP)} 且 {@code labelY = py + ph*0.05}，
     * 但字号是 {@code ph*0.40} —— Processing 的 TOP 对齐仍会在字形上方留出
     * 一部分行高（ascent），于是**字顶越出卡片上沿**被裁掉（实测：卡片从 y=196 起，
     * 字顶落在 y=195）。现在改成**按基线定位**并把基线算进卡片内，
     * 这样字顶永远在卡内。
     */
    private void drawOperationButton(float btnX, float py, float btnW, float ph, float a) {
        // L5：水印用 ak-ui 的 `#icon-sword-line`（交叉双剑），不再自绘。
        // 路径取自 AkIcons.SWORD_LINE（与上游 icons.svg 逐字核对一致）。
        //   `.ak-command--operation .ak-command__watermark`：
        //   width min(9cqw, 75%)、rotate(-4deg)、opacity .12
        drawOperationWatermark(btnX + btnW * 0.48f, py + ph / 2f, ph * 0.86f, a);

        // :hover translateY(-0.18cqw)
        g.pushMatrix();
        g.translate(0f, -d.h(2.46f) * opHoverT);

        float labelX = btnX + DesignSpace.BASE_W * 1.875f / 100f;
        float labelSize = ph * 0.40f;

        // 基线 = 卡片顶 + 上留白 + 一个字号的高（保证字顶不越界）
        float labelBaseline = py + ph * 0.06f + labelSize * 0.82f;

        // 标签：衬线、墨色（纸面上的大标签）
        //
        // ⚠️ 这里**不做** ak-ui 的 `scaleX(0.92)`。
        // 决定书 L2 明确要求"不对文字调用 scale()"：非整数变换会让
        // P2D 把笔画摊到两列像素上，放大看就是灰阶重影。
        // 0.92 的水平压缩是风格细节，清晰度是硬要求 —— 前者让位。
        fonts.apply(g, Fonts.Role.COMMAND, labelSize);
        g.textAlign(PConstants.LEFT, PConstants.BASELINE);
        g.fill(23, 25, 27, 255f * a);
        fonts.text(g, "作战", d.x(labelX), d.y(labelBaseline));

        // 「当前」徽标：紧跟标签下方，深底白字
        float badgeSize = ph * 0.135f;
        float padX = d.w(ph * 0.024f);
        fonts.apply(g, Fonts.Role.SANS, badgeSize);
        float textW = g.textWidth("当前");
        float badgeW = padX * 2f + textW;
        float badgeH = fonts.size(badgeSize) * 1.30f;
        float badgeY = labelBaseline + d.h(ph * 0.075f);

        g.pushMatrix();
        g.translate(d.x(labelX), d.y(badgeY));
        g.fill(23, 25, 27, 255f * a);
        g.rect(0f, 0f, badgeW, badgeH);
        g.fill(255, 255, 255, 255f * a);
        g.textAlign(PConstants.LEFT, PConstants.CENTER);
        fonts.text(g, "当前", padX, badgeH / 2f);
        g.popMatrix();

        // meta：当前关卡名（真实数据）；没有就写提示
        fonts.apply(g, Fonts.Role.SANS, ph * 0.135f);
        // 主界面「作战」卡固定显示当前主力章节名（用户指定），不跟着关卡数据变
        String meta = "怒号光明";
        g.textAlign(PConstants.LEFT, PConstants.TOP);
        g.fill(23, 25, 27, 0.78f * 255f * a);
        fonts.text(g, meta, d.x(labelX), d.y(badgeY) + badgeH + d.h(ph * 0.04f));

        g.popMatrix();
    }

    /**
     * 作战卡的水印 —— {@code #icon-sword-line}（交叉双剑），决定书 L5。
     *
     * <p>上一版是**自绘**的一把剑，与 ak-ui 的 SVG 形状不一致 —— L5 抱怨的就是这个。
     * 现在直接用图标库里的路径：`AkIcons.SWORD_LINE` 与
     * `ak-ui-1.1.0/ak-terminal-java/reference/icons.svg` 的 `#icon-sword-line` 逐字一致。
     *
     * <p>参数照 {@code .ak-command--operation .ak-command__watermark}：
     * 尺寸取卡片高的 86%，{@code rotate(-4deg)}，{@code opacity: .12}。
     *
     * @param cx    中心 x（设计坐标）
     * @param cy    中心 y（设计坐标）
     * @param size  边长（设计坐标）
     * @param alpha 整体透明度
     */
    private void drawOperationWatermark(float cx, float cy, float size, float a) {
        float side = d.w(size);
        g.pushMatrix();
        g.translate(d.x(cx), d.y(cy));
        g.rotate(PApplet.radians(-4f));
        com.yestercar.gameopening.icon.SvgPath.fill(g, "sword-line",
                -side / 2f, -side / 2f, side,
                ((int) Math.round(0.12f * 255f * a) << 24) | 0x3C3C3C);
        g.popMatrix();
    }

    /** 作战按钮命中（窗口坐标；区域每帧由 {@link #drawOperationPanel} 更新）。 */
    private boolean opHit(float mx, float my) {
        return opBtnW > 0f
                && mx >= opBtnX && mx <= opBtnX + opBtnW
                && my >= opBtnY && my <= opBtnY + opBtnH;
    }

    // ------------------------------------------------------------------
    // 提示条
    // ------------------------------------------------------------------

    private void drawHint(int now) {
        if (hint.isEmpty()) {
            return;
        }
        float age = now - hintAt;
        float a;
        if (age > 4000f) {
            a = 0f;
        } else if (age > 3000f) {
            a = 1f - (age - 3000f) / 1000f;
        } else {
            a = 1f;
        }
        if (a <= 0f) {
            hint = "";
            return;
        }
        fonts.apply(g, Fonts.Role.SANS, 12f);
        g.textAlign(PConstants.LEFT, PConstants.BOTTOM);
        g.fill(UITheme.MENU_TEXT_DIM, a * 255f);
        fonts.text(g, hint, d.x(28.412798f), d.y(639f));
    }

    // ------------------------------------------------------------------
    // 鼠标
    // ------------------------------------------------------------------

    void mousePressed(float mouseX, float mouseY) {
        if (screen == Screen.MENU) {
            pressedCard = null;
            opPressed = false;

            // 左栏（公告卡 / 重播入场）
            left.mousePressed(mouseX, mouseY);

            // 「作战」纸面按钮（与「选择关卡」同动作，但区域更大、更好点）
            if (opHit(mouseX, mouseY)) {
                opPressed = true;
                return;
            }
            for (CommandCard c : allCards()) {
                if (c.hit(mouseX, mouseY)) {
                    c.press();
                    pressedCard = c;
                    return;
                }
            }
            return;
        }

        if (back.hit(mouseX, mouseY)) {
            back.press();
            return;
        }
        switch (screen) {
            case SQUAD -> squadScreen.mousePressed(mouseX, mouseY);
            case OFFICIAL -> officialScreen.mousePressed(mouseX, mouseY);
            case OPERATORS -> operatorScreen.mousePressed(mouseX, mouseY);
            default -> customScreen.mousePressed(mouseX, mouseY);
        }
    }

    void mouseReleased(float mouseX, float mouseY) {
        if (screen != Screen.MENU) {
            // 干员详情打开时，左上角返回不该生效（否则很容易误触回主菜单）
            if (screen == Screen.OPERATORS && operatorScreen.hasDetail()) {
                // ⚠️ 这里只能吞掉松开，不能再转发一次按下 ——
                //    转发会把刚打开的详情当成"点了浮层外面"立刻关掉，
                //    表现就是第一次点击详情一闪而过。
                back.release();
                return;
            }
            // 返回箭头优先
            if (back.isPressed()) {
                boolean onArrow = back.hit(mouseX, mouseY);
                back.release();
                if (onArrow) {
                    if (screen == Screen.SQUAD) {
                        goScreen(squadReturn);      // 退回进来时的列表页
                    } else {
                        goMenu();
                    }
                }
                return;
            }
            // 编队页：确认开战 → 打开战斗窗口
            if (screen == Screen.SQUAD) {
                if (squadScreen.mouseReleased(mouseX, mouseY)) {
                    startBattle();
                }
                return;
            }
            boolean ready = screen == Screen.OFFICIAL
                    ? officialScreen.mouseReleased(mouseX, mouseY)
                    : customScreen.mouseReleased(mouseX, mouseY);
            if (ready) {
                Campaign c = screen == Screen.OFFICIAL
                        ? officialScreen.selected() : customScreen.selected();
                if (c == null) {
                    showHint("请先选择一个关卡");
                    return;
                }
                // 选关 → 编队（P3.2：把「准备作战」真正接上）
                pendingCampaign = c;
                squadReturn = screen;
                squadScreen.setCampaignName(c.name());
                squadScreen.setCampaign(c.meta());
                squadScreen.clearSelection();
                goScreen(Screen.SQUAD);
            }
            return;
        }

        CommandCard target = pressedCard;
        boolean opTarget = opPressed;
        for (CommandCard c : allCards()) {
            c.release();
        }
        pressedCard = null;
        opPressed = false;

        // 左栏的动作先处理（公告卡 = 官方入口，重播 = 重放加载）
        LeftColumn.Action la = left.mouseReleased(mouseX, mouseY);
        if (la == LeftColumn.Action.OFFICIAL) {
            goScreen(Screen.OFFICIAL);
            return;
        }
        if (la == LeftColumn.Action.VOLUME && g instanceof GameOpeningApp hostApp) {
            hostApp.setMasterVolume(left.takePendingVolume());
            return;
        }
        // 「重播入场」已移除，不再处理 REPLAY 动作

        if (opTarget) {
            if (opHit(mouseX, mouseY)) {
                goScreen(Screen.OFFICIAL);
            }
            return;
        }
        if (target == null || !target.hit(mouseX, mouseY)) {
            return;
        }

        // 四张牌 → 四个动作。行号/下标与 buildCards() 的映射一致：
        //   row1 = 选择关卡(0) / 自定义关卡(1)
        //   row2 = 干员(0)
        //   row3 = 退出(0)
        // L7 之后是 3 张：
        //   row1 = 自定义关卡
        //   row2 = 干员 / 退出
        // 官方关卡入口收敛到「作战」卡（operationPanel 的命中区）。
        if (target == row1.get(0)) {
            goScreen(Screen.CUSTOM);
        } else if (target == row2.get(0)) {
            goScreen(Screen.OPERATORS);
        } else if (target == row2.get(1)) {
            g.exit();
        }
    }

    // ------------------------------------------------------------------
    // 屏幕切换
    // ------------------------------------------------------------------

    /**
     * 确认编队后开战。
     *
     * <p>战斗跑在**另一个窗口**（{@link GameOpeningApp#launchBattle}），
     * 所以这里调用后主菜单仍在原处，玩家关掉战斗窗口就能回来。
     */
    private void startBattle() {
        if (pendingCampaign == null) {
            showHint("没有选中的战役");
            return;
        }
        List<String> squad = squadScreen.selectedIds();
        if (squad.isEmpty()) {
            showHint("请至少选择一名干员");
            return;
        }
        if (g instanceof GameOpeningApp host) {
            host.launchBattle(pendingCampaign.id(), squad,
                    squadReturn == Screen.OFFICIAL);
        } else {
            System.out.println("[MainMenu] 当前宿主不是 GameOpeningApp，无法打开战斗窗口");
        }
    }

    /**
     * 从战斗返回时，直接回到对应的战役列表。
     *
     * @param official {@code true} 回官方列表，{@code false} 回自定义列表
     */
    void openList(boolean official) {
        goScreen(official ? Screen.OFFICIAL : Screen.CUSTOM);
    }

    /**
     * @return 当前屏幕名（窗口重建时用来恢复）
     */
    String screenName() {
        return screen.name();
    }

    /**
     * 恢复到一个屏幕（窗口尺寸变化会重建 MainMenu，屏幕状态会丢）。
     *
     * @param name {@link #screenName()} 的返回值
     */
    void restoreScreen(String name) {
        try {
            screen = Screen.valueOf(name);
        } catch (RuntimeException e) {
            screen = Screen.MENU;
        }
    }

    /**
     * 窗口缩放会重建 MainMenu，这里把编队状态迁移给新实例。
     *
     * @param fresh 新建的菜单
     */
    void transferStateTo(MainMenu fresh) {
        if (fresh == null || fresh == this) {
            return;
        }
        fresh.squadScreen.copyStateFrom(squadScreen);
        fresh.squadReturn = squadReturn;
    }

    private void goScreen(Screen next) {
        boolean fromMenu = screen == Screen.MENU;
        // 战役界面（编队/关卡列表）用专属配乐，主菜单用主菜单曲
        // 战役相关界面（官方/自定义列表、编队）统一放 Broken_Sun；
        // 只有回到主菜单才换回 fudahuang。
        // 音乐规则：
        //   · 编队界面 = Broken_Sun（官方/自定义都一样）
        //   · 官方列表 = Broken_Sun；自定义列表与主菜单 = fudahuang
        //   · 进「官方列表」时从头放，其余列表间切换/回主菜单都**接着放**
        if (g instanceof GameOpeningApp host) {
            if (next == Screen.SQUAD) {
                // 自定义 → 编队：Broken_Sun 从头；官方 → 编队：接着放
                host.switchCampaignMusic(true, squadReturn == Screen.CUSTOM);
            } else if (next == Screen.OFFICIAL) {
                host.switchCampaignMusic(true, fromMenu);
            } else {
                // MENU / CUSTOM：fudahuang。
                // 从编队返回列表时要**从头**放 —— 否则会接着离开时的那一段继续，
                // 听起来像"上次没放完"，日志里表现为直接 play 而不是重新开始。
                boolean fromSquad = screen == Screen.SQUAD;
                host.switchCampaignMusic(false, fromMenu || fromSquad);
            }
        }
        screen = next;
        officialScreen.clearSelection();
        customScreen.clearSelection();
        System.out.println("[MainMenu] 进入屏幕 " + next);
    }

    /**
     * 重播入场 —— 决定书 D11：真实动作，重放 {@code Phase.LOADING}。
     *
     * <p>做法是回到加载阶段并把入场时钟清零，让主菜单的错峰动画重跑一遍。
     * 不重建窗口 —— 那是"重启程序"，不是"重播动画"。
     */
    private void replayEntrance() {
        menuShownAt = -1;
        if (g instanceof GameOpeningApp app) {
            app.replayLoadingPhase();
        }
    }

    private void goMenu() {
        // 回主菜单 → 换回主菜单曲（goScreen 统一处理）
        if (g instanceof GameOpeningApp host) {
            host.leaveCampaignScreen();
        }
        screen = Screen.MENU;
        System.out.println("[MainMenu] 返回主菜单");
    }

    private List<CommandCard> allCards() {
        List<CommandCard> all = new ArrayList<>();
        all.addAll(row1);
        all.addAll(row2);
        all.addAll(row3);
        return all;
    }

    private void showHint(String s) {
        hint = s;
        hintAt = g.millis();
    }

    /** 内层屏幕路由。 */
    enum Screen {
        /** 主菜单根屏。 */
        MENU,
        /** 官方关卡列表。 */
        OFFICIAL,
        /** 自定义关卡列表。 */
        CUSTOM,
        /** 编队（选关之后、开战之前）。 */
        SQUAD,
        /** 干员总览。 */
        OPERATORS
    }
}
