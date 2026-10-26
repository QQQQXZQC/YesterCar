package com.yestercar.gameopening;

import com.yestercar.common.model.Campaign;
import com.yestercar.common.ui.DesignSpace;
import com.yestercar.common.util.UITheme;
import processing.core.PApplet;
import processing.core.PConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * 官方关卡页 —— **与自定义页刻意不同的一种版式**。
 *
 * <h2>为什么两页长得不一样</h2>
 * 官方关卡固定 2 个、不会增减，适合做"**展示**"；
 * 自定义关卡会随玩家增删一直变（现在 7 个，以后可能 30 个），
 * 只适合做"**清单**"。硬把两种需求塞进同一个版式，必然有一边别扭。
 *
 * <h2>版式：中心横轴 + 节点</h2>
 * <pre>
 *   ┌──────────────────────────────────────────────────┐
 *   │  ←  官方关卡                                      │
 *   │                                                  │
 *   │        ●───────────────●                         │
 *   │        │               │        ← 轴线贯穿画面中部  │
 *   │   ┌─────────┐    ┌─────────┐                     │
 *   │   │第一关…  │    │第二关…  │   ← 按钮挂在节点下方   │
 *   │   └─────────┘    └─────────┘                     │
 *   │   01 · 5敌       02 · 8敌      ← 等宽元信息        │
 *   └──────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>背景是程序生成的</h2>
 * 与 {@link Background} 同一思路：没有可用素材，就自己画。
 * 好处是<strong>天然跟着窗口尺寸走</strong>，任何分辨率都不拉伸。
 * 等有真图时只需换掉 {@link #drawBackground} 一处。
 */
final class OfficialScreen {

    /** 节点间距（设计坐标）。 */
    private static final float NODE_GAP = 300f;

    /** 节点圆点半径（设计坐标）。 */
    private static final float DOT_R = 9f;

    /** 按钮尺寸（设计坐标）。ak-ui 的 {@code .ak-button} 是 150×50。 */
    private static final float BTN_W = 150f;
    private static final float BTN_H = 50f;

    private final PApplet g;
    private final DesignSpace d;
    private final Fonts fonts;

    private final List<Campaign> items = new ArrayList<>();
    private final List<CommandCard> buttons = new ArrayList<>();

    private Campaign selected;
    private final CommandCard readyButton;
    private String hint = "";
    private int hintAt;
    private CommandCard pressedCard;

    OfficialScreen(PApplet g, DesignSpace d, Fonts fonts) {
        this.g = g;
        this.d = d;
        this.fonts = fonts;
        // 按 M 布局的节点数建满（原来是 4 个，导致第 5 关画不出来）
        for (int i = 0; i < M_NODES.length; i++) {
            buttons.add(new CommandCard(g, d, fonts));
        }
        readyButton = new CommandCard(g, d, fonts);
        layout();
    }

    /** 交给本页显示的官方关卡。 */
    void setItems(List<Campaign> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        selected = null;
        pressedCard = null;
        System.out.print("[OfficialScreen] 载入官方关卡 " + items.size() + " 个:");
        for (Campaign c : items) {
            System.out.print(" " + c.id() + "(" + c.name() + ")");
        }
        System.out.println();
        while (buttons.size() < items.size()) {
            buttons.add(new CommandCard(g, d, fonts));
        }
        layout();
    }

    /**
     * @return 当前选中的关卡；没选返回 {@code null}
     */
    Campaign selected() {
        return selected;
    }

    /**
     * 清掉选中，回到"一个都没选"的初始状态。
     *
     * <p>每次进入本页都要调 —— 见 {@code MainMenu.goScreen} 的说明。
     */
    void clearSelection() {
        selected = null;
        hint = "";
    }

    // ------------------------------------------------------------------
    // 版式
    // ------------------------------------------------------------------

    /** @return 轴线的 y（设计坐标）—— 画面中部偏上，给按钮留出下方空间。 */
    private float axisY() {
        return DesignSpace.BASE_H * 0.40f;
    }

    /** @return 第 {@code i} 个节点圆点的 x（设计坐标）。 */
    private float nodeX(int i) {
        int n = Math.max(1, items.size());
        float span = NODE_GAP * (n - 1);
        float first = DesignSpace.BASE_W / 2f - span / 2f;
        return first + NODE_GAP * i;
    }

    /** 五个关卡在 M 上的位置（设计坐标）。顺序即 MS-1..MS-5。 */
    // 左右对称：以设计宽度的一半（720）为轴，
    // MS-1↔MS-8、MS-2↔MS-7、MS-3↔MS-6、MS-4↔MS-5 的 x 互为镜像（x' = 1440 - x）。
    private static final float[][] M_NODES = {
            {300f, 640f},    // MS-1 左下
            {220f, 420f},    // MS-2 左中
            {400f, 200f},    // MS-3 左上
            {620f, 470f},    // MS-4 中偏左下
            {820f, 470f},    // MS-5 中偏右下（Boss）
            {1040f, 200f},   // MS-6 右上
            {1220f, 420f},   // MS-7 右中
            {1140f, 640f},   // MS-8 右下（Boss）
    };

    /** 关卡方框尺寸（设计坐标，2:1 长方形）。 */
    private static final float NODE_W = 132f;
    private static final float NODE_H = 66f;

    /** 有 Boss 的关卡下标（纯 UI 标记，不读数据）。 */
    /** Boss 关按 **id** 判定（ms-5 / ms-8），不再依赖下标。 */
    private static final java.util.Set<String> BOSS_IDS =
            java.util.Set.of("ms-5", "ms-8");

    /** 画 M 形连线（相邻关卡之间）。 */
    /**
     * 从 id 里取关卡编号：{@code ms-5} → {@code 5}。
     *
     * @param id 战役 id
     * @return 编号；取不到时返回 id 本身
     */
    private static String numberFromId(String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+)").matcher(id);
        return m.find() ? m.group(1) : id;
    }

    private void drawMLines() {
        g.stroke(255, 255, 255, 0.55f * 255f);
        g.strokeWeight(d.len(2f));
        for (int i = 0; i + 1 < M_NODES.length; i++) {   // 1-2-…-8 依次相连
            g.line(d.x(M_NODES[i][0]), d.y(M_NODES[i][1]),
                    d.x(M_NODES[i + 1][0]), d.y(M_NODES[i + 1][1]));
        }
        g.noStroke();
    }

    /**
     * 右侧详情：关卡名 + 各类型敌人数量。
     *
     * <p>统计口径是**整关合计**（与选关信息面板一致）。
     */


    /**
     * 右侧半透明灰底详情：关卡代号 + 关卡名（白字）。
     *
     * <p>未选中时不画；「准备作战」按钮也放在这块灰底里。
     */
    private void drawDetailPanel() {
        if (selected == null) {
            return;
        }
        int idx = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(selected.id())) {
                idx = i;
                break;
            }
        }
        float px = 1160f;
        float py = 30f;    // 两次上移共 220（250 → 30）
        float pw = 260f;   // 左右各留 16
        float ph = 300f;   // 面板高度（固定）

        g.noStroke();
        g.fill(60f, 60f, 60f, 190f);            // 半透明灰底
        g.rect(d.x(px), d.y(py), d.w(pw), d.h(ph), 4f);
        g.noFill();
        g.stroke(255, 255, 255, 70);
        g.strokeWeight(1f);
        g.rect(d.x(px), d.y(py), d.w(pw), d.h(ph), 4f);
        g.noStroke();

        fonts.apply(g, Fonts.Role.MONO, 19f);
        g.textAlign(PConstants.CENTER, PConstants.TOP);
        g.fill(255, 255, 255);
        fonts.text(g, "MS-" + numberFromId(selected.id()) + "  " + selected.name(),
                d.x(px + pw / 2f), d.y(py + 24f));

        // 关卡简介（标题下方）：按面板宽度自动折行，最多 4 行，超出加省略号
        String desc = selected.meta().description();
        if (desc != null && !desc.isBlank()) {
            fonts.apply(g, Fonts.Role.MONO, 12f);
            g.fill(255, 255, 255, 210f);
            g.textAlign(PConstants.CENTER, PConstants.TOP);
            // 320 高：标题 42 + 描述 + 按钮区 56，行高 20 → 最多 11 行
            java.util.List<String> lines = wrapText(desc, pw - 32f, 11);
            float ly = py + 46f;
            for (String line : lines) {
                fonts.text(g, line, d.x(px + pw / 2f), d.y(ly));
                ly += 20f;
            }
        }
        fonts.apply(g, Fonts.Role.MONO, 13f);

        // 「准备作战」放在面板内部底部，水平居中
        float btnW = BTN_W * 0.88f;
        readyButton.at(d.x(px + pw / 2f - btnW / 2f), d.y(py + ph - 52f),
                d.w(btnW), d.h(44f));
    }

    /**
     * 按可用宽度把一段中文折行。
     *
     * @param text     原文
     * @param maxWidth 可用宽度（设计像素）
     * @param maxLines 最多几行（超出时最后一行加省略号）
     * @return 折好的行
     */
    private java.util.List<String> wrapText(String text, float maxWidth, int maxLines) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            if (g.textWidth(cur.toString()) > maxWidth) {
                cur.deleteCharAt(cur.length() - 1);
                out.add(cur.toString());
                cur.setLength(0);
                cur.append(c);
                if (out.size() == maxLines) {
                    break;
                }
            }
        }
        if (out.size() < maxLines && cur.length() > 0) {
            out.add(cur.toString());
        } else if (cur.length() > 0 && out.size() == maxLines) {
            String last = out.get(maxLines - 1);
            out.set(maxLines - 1, last.substring(0, Math.max(0, last.length() - 1)) + "…");
        }
        return out;
    }

    private void layout() {
        for (int i = 0; i < buttons.size(); i++) {
            if (i >= items.size()) {
                buttons.get(i).at(0f, 0f, 0f, 0f);
                continue;
            }
            float[] pos = M_NODES[Math.min(i, M_NODES.length - 1)];
            float bx = pos[0] - NODE_W / 2f;
            float by = pos[1] - NODE_H / 2f;
            buttons.get(i).at(d.x(bx), d.y(by), d.w(NODE_W), d.h(NODE_H));
        }
        // 「准备作战」在右下角 —— 与自定义页同一位置，两页手感一致
        // 放进右侧灰底面板里（面板 1160..1400 × 250..550）
        // 水平居中于右侧灰底面板（面板 1160..1400，中心 1280）
        // 按钮位置由 drawDetailPanel() 每帧跟随面板底部设定
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    void draw(float mouseX, float mouseY) {
        // ⚠️ 清屏必须在这里、不能放在 drawBackground() 里 ——
        // 那个方法在返回箭头之后才被调用，清屏会把箭头抹掉（实测踩过）。
        g.background(UITheme.WINDOW_BG_DARK);
        drawBackground();
        drawMLines();
        drawDetailPanel();

        int now = g.millis();
        for (int i = 0; i < items.size() && i < buttons.size(); i++) {
            drawNode(i, mouseX, mouseY);
            CommandCard c = buttons.get(i);
            Campaign camp = items.get(i);
            boolean sel = selected != null && selected.id().equals(camp.id());
            c.label("MS-" + numberFromId(camp.id()))
                    .meta(camp.meta().waves().size() + "波次 · "
                            + camp.meta().totalEnemies() + "敌")
                    .badge(String.format("%02d", i + 1))
                    // 选中 = 灰；Boss 关（MS-3/MS-5）= 红；其余 = 深色
                    .tone(sel ? CommandCard.Tone.MUTED
                            : (BOSS_IDS.contains(camp.id()) ? CommandCard.Tone.DANGER
                                    : CommandCard.Tone.DARK))
                    .disabled(false)
                    .selected(sel);
            c.update(mouseX, mouseY, now);
            c.draw();
        }

        if (items.isEmpty()) {
            fonts.apply(g, Fonts.Role.MONO, 13f);
            g.textAlign(PConstants.CENTER, PConstants.CENTER);
            g.fill(UITheme.MENU_TEXT_DIM);
            fonts.text(g, "没有找到官方关卡", d.x(DesignSpace.BASE_W / 2f), d.y(axisY()));
        }


        if (selected != null) {
            readyButton.label("准备作战").meta("").centerLabel(true)
                    .tone(CommandCard.Tone.DANGER).disabled(false).selected(false);
            readyButton.update(mouseX, mouseY, now);
            readyButton.draw();
        }

        drawHint(now);
    }

    /**
     * 背景：深色底 + 网格 + 一层极淡的中心光晕。
     *
     * <p>换真图时只改这个方法。
     */
    /** 关卡列表背景图（战役选择页）。 */
    private static final String BG_PATH = "data/imgs/fighting/Mainbackground.png";

    /** 背景图（懒加载）。 */
    private processing.core.PImage bgImage;

    /** 是否已尝试加载。 */
    private boolean bgTried;

    private void drawBackground() {
        if (!bgTried) {
            bgTried = true;
            bgImage = g.loadImage(BG_PATH);
            if (bgImage == null) {
                System.out.println("[OfficialScreen] 背景图读取失败: " + BG_PATH);
            }
        }
        if (bgImage != null) {
            g.imageMode(processing.core.PConstants.CORNER);
            float sc = Math.max(g.width / (float) bgImage.width,
                    g.height / (float) bgImage.height);
            float w = bgImage.width * sc;
            float h = bgImage.height * sc;
            g.image(bgImage, (g.width - w) / 2f, (g.height - h) / 2f, w, h);
            // 压暗一点，保证文字与轴线看得清
            g.noStroke();
            g.fill(0f, 0f, 0f, 150f);
            g.rect(0, 0, g.width, g.height);
        }

        float step = DesignSpace.BASE_W / 22f;
        g.stroke(0xFFFFFFFF, 6);
        g.strokeWeight(1f);
        for (float x = 0; x < DesignSpace.BASE_W; x += step) {
            g.line(d.x(x), 0, d.x(x), g.height);
        }
        for (float y = 0; y < DesignSpace.BASE_H; y += step) {
            g.line(0, d.y(y), g.width, d.y(y));
        }

        // 中心光晕：让视线落到轴上。
        //
        // ⚠️ 这里修过两个 bug：
        // ① alpha 原本是 `1 - t`，于是**顶部最亮、越往下越透明** ——
        //    方向完全反了，画面顶部糊了一片暖灰色；
        // ② 光晕画在返回箭头**之后**（且 background() 会清屏），
        //    把左上角的返回箭头一并盖掉，看起来像"官方页没有返回按钮"。
        //
        // 现在：强度在轴线上最高、向上下两侧对称衰减；且这个方法
        // **不负责清屏**（清屏交给 draw() 的第一句），
        // 这样绘制顺序才是"先背景、再控件"。
        g.noStroke();
        int bands = 30;
        float span = DesignSpace.BASE_H * 0.30f;
        float bandH = span * 2f / bands;
        for (int i = 0; i < bands; i++) {
            // t=0 在光晕顶边、t=1 在底边；到轴线的距离决定强度
            float t = i / (float) (bands - 1);
            float distToAxis = Math.abs(t - 0.5f) * 2f;      // 0 在轴线上，1 在边缘
            float a = 9f * (1f - distToAxis);
            if (a <= 0.15f) {
                continue;
            }
            g.fill(UITheme.MENU_ACCENT, a);
            g.rect(0, d.y(axisY() - span + bandH * i), g.width, d.h(bandH) + 1f);
        }
    }

    /** 轴线：一条贯穿的横线 + 两端渐隐。 */
    private void drawAxis() {
        if (true) {
            return;      // 旧横轴设计已废弃，M 布局取代
        }
        float y = d.y(axisY());
        float x0 = d.x(90f);
        float x1 = d.x(DesignSpace.BASE_W - 90f);

        // 主线
        g.stroke(255, 255, 255, 0.22f * 255);
        g.strokeWeight(d.len(1.2f));
        g.line(x0, y, x1, y);

        // 两端各一小段加粗 —— ak-ui 的 __line / __line--grow 就是这个手法：
        // 用"长短不一的横线"做装饰，而不是画满
        g.stroke(UITheme.SIGNAL_ACTION);
        g.strokeWeight(d.len(3f));
        g.line(x0, y, x0 + d.w(46f), y);
        g.line(x1 - d.w(46f), y, x1, y);
        g.noStroke();
    }

    /** 一个节点：圆点 + 连到按钮的竖线 + 序号。 */
    private void drawNode(int i, float mouseX, float mouseY) {
        if (true) {
            return;      // 旧圆点节点已废弃，M 布局只用方框
        }
        float nx = d.x(nodeX(i));
        float y = d.y(axisY());
        Campaign camp = items.get(i);
        boolean sel = selected != null && selected.id().equals(camp.id());
        boolean over = buttons.get(i).hit(mouseX, mouseY);

        // 竖线：从轴连到按钮
        g.stroke(255, 255, 255, sel || over ? 0.5f * 255 : 0.22f * 255);
        g.strokeWeight(d.len(1.4f));
        g.line(nx, y, nx, d.y(axisY() + 34f));
        g.noStroke();

        // 圆点：选中的是实心强调色，未选是描边
        float r = d.len(DOT_R);
        if (sel) {
            g.fill(UITheme.MENU_ACCENT);
            g.ellipse(nx, y, r * 2f, r * 2f);
        } else {
            g.noFill();
            g.stroke(UITheme.SIGNAL_ACTION);
            g.strokeWeight(d.len(2.4f));
            g.ellipse(nx, y, r * 1.7f, r * 1.7f);
            g.noStroke();
            g.fill(UITheme.WINDOW_BG_DARK);
            g.ellipse(nx, y, r * 1.1f, r * 1.1f);
        }

        // 轴下方的序号与敌人数 —— 等宽、宽字距（ak-ui 的 __eyebrow）
        fonts.apply(g, Fonts.Role.MONO, 9f);
        g.textAlign(PConstants.CENTER, PConstants.TOP);
        g.fill(sel ? UITheme.MENU_ACCENT : UITheme.MENU_TEXT_DIM, (sel ? 1f : 0.6f) * 255);
        fonts.text(g, String.format("0%d", i + 1), nx, y + d.h(14f));
        fonts.text(g, camp.meta().waves().size() + "波 · " + camp.meta().totalEnemies() + "敌", nx, y + d.h(26f));
    }

    private void drawHint(int now) {
        if (hint.isEmpty()) {
            return;
        }
        float age = now - hintAt;
        float a = age > 4000f ? 0f : (age > 3000f ? 1f - (age - 3000f) / 1000f : 1f);
        if (a <= 0f) {
            hint = "";
            return;
        }
        fonts.apply(g, Fonts.Role.SANS, 12f);
        g.textAlign(PConstants.LEFT, PConstants.BOTTOM);
        g.fill(UITheme.SIGNAL_ACTION, a * 255);
        fonts.text(g, hint, d.x(90f), d.y(DesignSpace.BASE_H - 14f));
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    void mousePressed(float mouseX, float mouseY) {
        pressedCard = null;
        for (CommandCard c : clickable()) {
            if (c.hit(mouseX, mouseY)) {
                c.press();
                pressedCard = c;
                break;
            }
        }
    }

    /**
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 点了「准备作战」返回 {@code true}
     */
    boolean mouseReleased(float mouseX, float mouseY) {
        boolean onReady = false;
        CommandCard target = pressedCard;

        if (target != null && target.hit(mouseX, mouseY)) {
            if (target == readyButton) {
                onReady = true;
            } else {
                int i = buttons.indexOf(target);
                if (i >= 0 && i < items.size()) {
                    // 官方页只有 2 个节点、按钮本身就是"选它"，
                    // 所以这里一次点击就够 —— 不像列表页要先看详情。
                    // 选中不给提示语 —— 界面上已经有两重反馈：
                    // 圆点变实心橙 + 右下角出现「准备作战」。
                    // 再加一行字只是噪声。
                    selected = items.get(i);
                }
            }
        }

        for (CommandCard c : clickable()) {
            c.release();
        }
        pressedCard = null;
        return onReady;
    }

    private List<CommandCard> clickable() {
        List<CommandCard> all = new ArrayList<>(buttons);
        all.add(readyButton);
        return all;
    }
}
