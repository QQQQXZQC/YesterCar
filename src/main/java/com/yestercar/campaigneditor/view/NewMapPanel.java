package com.yestercar.campaigneditor.view;

import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.common.ui.UiButton;
import com.yestercar.common.util.UITheme;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PFont;

/**
 * 「新建战役」面板：一个自绘的模态面板，收集战役名 / 列 / 行。
 *
 * <h2>为什么不用 {@code JOptionPane}</h2>
 * 原来点「新建战役」会连弹**三个**系统输入框（名字 → 列 → 行），
 * 外观是 Swing 默认主题、跟编辑器的白底界面完全不搭；
 * 而且它的按钮由 look-and-feel 决定，改不了颜色，做不出三态。
 *
 * <h2>只实现"最简可用"的文本输入</h2>
 * 支持：点击定位到某个输入框、退格、输入字符、Tab / 点击切换、光标闪烁。
 * <b>不支持</b>：选中文字、鼠标拖动选择、方向键移动光标、复制粘贴。
 * 这几个名字都不长（"演示地图"、"12"），为此写一套文本编辑控件不划算。
 *
 * <p>不做成通用控件，是因为目前只有这一处需要它。等第二处出现再抽。
 */
public final class NewMapPanel {

    /** 面板宽度。高度由内容推导，见 {@link #panelHeight()}。 */
    private static final float PANEL_W = 380f;
    private static final float ROW_H = 34f;
    /** 每行标签的高度。 */
    private static final float LABEL_H = 18f;
    /** 行与行之间的间距。 */
    private static final float ROW_GAP = 12f;
    /** 面板顶部到第一行标签的距离。 */
    private static final float FIRST_ROW_TOP = 48f;
    /**
     * 按钮上方的留白（错误提示行之后再往下的呼吸空间）。
     *
     * <p>⚠️ <strong>错误提示行不在这 20px 里</strong>，它有自己的一项
     * {@link #ERROR_LINE_H}。曾经它被塞进这里，结果压住了输入框 ——
     * 详见 {@link #errorBox()}。
     */
    private static final float BUTTON_GAP_ABOVE = 20f;
    /**
     * 错误提示行占的高度。
     *
     * <p>取 22 而不是"字号 14 + 一点"：这一行要能容下中文行盒
     * （字号 14 的行盒约 16px）再加上下各 3px 的余量。
     * 以前没有这一项，正是重叠的根因。
     *
     * <p>公开是为了让几何测试能断言"输入框与按钮之间的间隙 ≥ 这一行的高度"——
     * 那条断言正是这个 bug 的回归锁。
     */
    public static final float ERROR_LINE_H = 22f;
    /** 按钮下方的留白（给键盘提示文字）。 */
    private static final float BOTTOM_PAD = 30f;
    /** 输入框里文字距左边的内缩。 */
    private static final float FIELD_PAD = 10f;
    /** 按钮宽高。 */
    private static final float BTN_W = 100f;
    /** 单个输入框的字符上限。三个框共用 —— 名字短、数字更短，没必要分开。 */
    private static final int MAX_FIELD_LEN = 64;

    /** 三个输入框的下标。 */
    private static final int FIELD_NAME = 0;
    private static final int FIELD_COLS = 1;
    private static final int FIELD_ROWS = 2;
    private static final int FIELD_COUNT = 3;

    /**
     * {@link #layoutBoxes()} 返回数组里的下标。
     *
     * <p>用常量而不是裸数字：多一个元素（比如这次的错误行）就会让所有下标平移，
     * 裸数字散在测试和实现里必然有一处忘改。
     */
    public static final int ERROR_BOX_INDEX = FIELD_COUNT;
    public static final int CANCEL_BOX_INDEX = FIELD_COUNT + 1;
    public static final int CONFIRM_BOX_INDEX = FIELD_COUNT + 2;

    /** 输入框里的文字。 */
    private final StringBuilder[] values = {
            new StringBuilder("map_002"),
            new StringBuilder("12"),
            new StringBuilder("8")
    };

    /** 焦点落在哪个输入框。 */
    private int focused = FIELD_NAME;

    /**
     * 选中区间的两个端点：{@code anchor} 是不动的那一头，{@code caret} 是随鼠标走的那一头。
     *
     * <h2>为什么存两个下标而不是一个"选中范围"</h2>
     * 拖动可以往左也可以往右，方向决定了哪一头是起点。
     * 用"锚点 + 插入符"这种记法，方向自然包含在两者的先后关系里，
     * 不必再单独存一个 boolean。
     */
    private int anchor = 0;

    /** 插入符位置（0 ~ 文本长度）。 */
    private int caret = 0;

    /**
     * 拖动是否正在进行（从输入框内按下开始）。
     *
     * <p>点在面板空白处不会开始拖动 —— 否则"按住面板边缘拖一下"
     * 也会莫名其妙地改掉选中范围。
     */
    private boolean dragging = false;

    /** 结果：用户点了确认时非 {@code null}。 */
    private Result result;

    /** 面板是否已关闭（确认或取消）。 */
    private boolean closed;

    /** 校验失败的原因，非空时显示在面板底部。 */
    private String error = "";

    private final PApplet applet;
    private final PFont font;
    private final UiButton confirm;
    private final UiButton cancel;

    /** 面板左上角与高度（每帧按视口算，所以在构造时不定）。 */
    private float panelX;
    private float panelY;
    private float panelH;

    /**
     * 新建战役的参数。
     *
     * @param name 地图名（= 文件名）
     * @param cols 列数
     * @param rows 行数
     */
    public record Result(String name, int cols, int rows) {
    }

    /**
     * @param applet 所属窗口
     * @param font   字体
     */
    public NewMapPanel(PApplet applet, PFont font) {
        this.applet = applet;
        this.font = font;
        this.confirm = new UiButton(applet).text("确认");
        this.cancel = new UiButton(applet).text("取消");
    }

    /**
     * @return 面板是否已经关闭（用户点了确认或取消）
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * @return 确认后的结果；取消或还没确认时返回 {@code null}
     */
    public Result result() {
        return result;
    }

    // ------------------------------------------------------------------
    // 几何
    // ------------------------------------------------------------------

    /**
     * 面板高度 —— <strong>由内容推导，不写死</strong>。
     *
     * <p>⚠️ 这里踩过坑：原来固定 {@code 250}，而三行输入框实际占到 y=553（面板顶 325 起算），
     * 按钮却摆在 y=499 —— **与「行数」输入框重叠 54px**。
     * 固定高度只要内容一变（加一行、改字号）就会再次重叠，而"重叠"在代码里看不出来。
     *
     * <p>现在高度与按钮位置都从同一个推导链算出来，结构上不可能重叠。
     *
     * @return 面板高度
     */
    public static float panelHeight() {
        float rows = FIRST_ROW_TOP + FIELD_COUNT * (LABEL_H + ROW_H + ROW_GAP);
        return rows + ERROR_LINE_H + BUTTON_GAP_ABOVE + ROW_H + BOTTOM_PAD;
    }

    /**
     * 输入框区域的底边（相对面板顶）。
     *
     * @return 最后一个输入框的下边
     */
    public static float rowsBottom() {
        return FIRST_ROW_TOP + (FIELD_COUNT - 1) * (LABEL_H + ROW_H + ROW_GAP)
                + LABEL_H + ROW_H;
    }

    /**
     * 错误提示行占的矩形（相对屏幕）。
     *
     * <h2>⚠️ 这一行必须有**自己的**预算，不能挤在别处的空隙里</h2>
     * 它曾经被画进 {@link #BUTTON_GAP_ABOVE} 那 20px 里，锚点是
     * {@code rowsBottom() + 2f} —— 读起来像"输入框下方 2px"，
     * 但用的是 {@code CENTER} 对齐，含义变成"行盒**中心**在输入框下方 2px"，
     * 于是行盒上半部分直接侵入「行数」输入框。
     *
     * <p>重叠量是 {@code H14/2 - 2}：锚点只比框底低 2px，行盒半高约 8px，
     * 所以**必然重叠约 6px**，与面板高度无关。
     * 当时误以为"加重面板就能修好"，实际上错误文字和按钮都锚在
     * {@code rowsBottom()} 上，{@code panelY} 一平移两者同步移动，重叠量分毫不变。
     *
     * <p>现在给它独立高度 {@link #ERROR_LINE_H}，并让 {@link #panelHeight()}
     * 把它算进去 —— 与其他元素同一条推导链，结构上不可能再重叠。
     *
     * @return 错误提示行的矩形
     */
    public static Box errorBox() {
        float panelH = panelHeight();
        float panelX = (LayoutRegions.WINDOW_W - PANEL_W) / 2f;
        float panelY = (LayoutRegions.WINDOW_H - panelH) / 2f;
        return new Box(panelX + UITheme.SPACE_5, panelY + rowsBottom(),
                PANEL_W - UITheme.SPACE_5 * 2, ERROR_LINE_H);
    }

    /**
     * 面板里一块矩形。
     *
     * @param x      左边界
     * @param y      上边界
     * @param width  宽
     * @param height 高
     */
    public record Box(float x, float y, float width, float height) {
        /** @return 下边界 */
        public float bottom() {
            return y + height;
        }

        /** @return 右边界 */
        public float right() {
            return x + width;
        }
    }

    /**
     * 算出面板里所有元素的矩形（相对屏幕）。
     *
     * <p><strong>做成静态且公开，是为了能被单元测试直接验算</strong>：
     * 「按钮与输入框是否重叠」这种错误在代码里看不出来，只有算一遍才知道。
     * 这里踩过坑 —— 面板高度写死 250，而三行输入框占到 553，
     * 按钮摆在 499，**与「行数」输入框重叠 54px**。
     *
     * @return 顺序见 {@link #ERROR_BOX_INDEX} 等下标常量：
     *         {@code [0..FIELD_COUNT-1]} 三个输入框，然后是错误提示行、取消、确认
     */
    public static Box[] layoutBoxes() {
        float panelH = panelHeight();
        float panelX = (LayoutRegions.WINDOW_W - PANEL_W) / 2f;
        float panelY = (LayoutRegions.WINDOW_H - panelH) / 2f;

        Box[] out = new Box[FIELD_COUNT + 3];
        float fieldX = panelX + UITheme.SPACE_5;
        float fieldW = PANEL_W - UITheme.SPACE_5 * 2;
        for (int i = 0; i < FIELD_COUNT; i++) {
            float y = panelY + FIRST_ROW_TOP + i * (LABEL_H + ROW_H + ROW_GAP) + LABEL_H;
            out[i] = new Box(fieldX, y, fieldW, ROW_H);
        }

        // 错误提示行：排在输入框与按钮之间，且有自己的高度。
        // 把它放进这个数组，测试才能验算"它不压输入框、也不压按钮"。
        out[ERROR_BOX_INDEX] = errorBox();

        float btnY = panelY + rowsBottom() + ERROR_LINE_H + BUTTON_GAP_ABOVE;
        out[CANCEL_BOX_INDEX] = new Box(
                panelX + PANEL_W - UITheme.SPACE_5 - BTN_W * 2 - UITheme.SPACE_3,
                btnY, BTN_W, ROW_H);
        out[CONFIRM_BOX_INDEX] = new Box(
                panelX + PANEL_W - UITheme.SPACE_5 - BTN_W, btnY, BTN_W, ROW_H);
        return out;
    }

    /** 把面板摆在视口正中；每帧调一次，窗口尺寸变化也不用管。 */
    private void layout() {
        panelH = panelHeight();
        panelX = (LayoutRegions.WINDOW_W - PANEL_W) / 2f;
        panelY = (LayoutRegions.WINDOW_H - panelH) / 2f;

        Box[] boxes = layoutBoxes();
        Box cancelBox = boxes[CANCEL_BOX_INDEX];
        cancel.at(cancelBox.x(), cancelBox.y(), cancelBox.width(), cancelBox.height());
        Box ok = boxes[CONFIRM_BOX_INDEX];
        confirm.at(ok.x(), ok.y(), ok.width(), ok.height());
    }

    /**
     * 第 {@code index} 个输入框的矩形。
     *
     * <p>绘制与命中判定都用它，避免两边各算一套。
     *
     * @param index 输入框下标
     * @return {@code [x, y, w, h]}
     */
    private float[] fieldBox(int index) {
        float x = panelX + UITheme.SPACE_5;
        float w = PANEL_W - UITheme.SPACE_5 * 2;
        return new float[]{x, rowTop(index) + LABEL_H, w, ROW_H};
    }

    /** 第 {@code index} 行的起点 y（标签文字的顶部）。 */
    private float rowTop(int index) {
        return panelY + FIRST_ROW_TOP + index * (LABEL_H + ROW_H + ROW_GAP);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    /** 画整个面板。调用方应在画完编辑器之后调用它，让面板盖在最上层。 */
    public void draw(float mouseX, float mouseY) {
        layout();

        // 后面的地图区压暗一点，表明这是模态
        applet.noStroke();
        applet.fill(0, 0, 0, 90);
        applet.rect(0, 0, LayoutRegions.WINDOW_W, LayoutRegions.WINDOW_H);

        // 面板本体
        applet.fill(UITheme.PANEL_BG_LIGHT);
        applet.rect(panelX, panelY, PANEL_W, panelH, UITheme.RADIUS_SUBTLE);
        applet.noFill();
        applet.stroke(UITheme.LINE_ON_LIGHT);
        applet.strokeWeight(1f);
        applet.rect(panelX, panelY, PANEL_W, panelH, UITheme.RADIUS_SUBTLE);
        applet.noStroke();

        applet.textFont(font);

        // 标题
        applet.fill(UITheme.TEXT_ON_LIGHT);
        applet.textAlign(PConstants.LEFT, PConstants.TOP);
        applet.textSize(UITheme.TITLE_SIZE - 4);
        applet.text("新建战役", panelX + UITheme.SPACE_5, panelY + UITheme.SPACE_4);
        applet.textSize(UITheme.FONT_SIZE);

        String[] labels = {"战役名（文件夹名，也是主菜单里显示的名字）",
                "列数（1 ~ " + TileMap.MAX_SIZE + "）",
                "行数（1 ~ " + TileMap.MAX_SIZE + "）"};

        for (int i = 0; i < FIELD_COUNT; i++) {
            float[] b = fieldBox(i);

            // 标签
            applet.fill(UITheme.TEXT_DIM_ON_LIGHT);
            applet.textAlign(PConstants.LEFT, PConstants.BOTTOM);
            applet.text(labels[i], b[0], b[1] - 3f);

            // 输入框
            applet.noStroke();
            applet.fill(255);
            applet.rect(b[0], b[1], b[2], b[3], UITheme.RADIUS_SUBTLE);
            applet.noFill();
            applet.stroke(i == focused ? UITheme.COLOR_PRIMARY : UITheme.LINE_ON_LIGHT);
            applet.strokeWeight(i == focused ? 2f : 1f);
            applet.rect(b[0], b[1], b[2], b[3], UITheme.RADIUS_SUBTLE);
            applet.noStroke();

            // 文字 + 选中高亮 + 光标
            String shown = values[i].toString();
            float textX = b[0] + FIELD_PAD;
            float midY = b[1] + b[3] / 2f;

            // 选中高亮画在文字**下面** —— 所以先画底、再画字。
            // 只有焦点框才可能有选中区（切走时选中自然失效，不必额外清）。
            if (i == focused && hasSelection()) {
                float selX = textX + textWidthUpTo(shown, selectionStart());
                float selW = textWidthUpTo(shown, selectionEnd())
                        - textWidthUpTo(shown, selectionStart());
                applet.noStroke();
                applet.fill(UITheme.SELECTION_BG);
                applet.rect(selX, b[1] + 5f, selW, b[3] - 10f);
            }

            applet.fill(UITheme.TEXT_ON_LIGHT);
            applet.textAlign(PConstants.LEFT, PConstants.CENTER);
            applet.text(shown, textX, midY);

            if (i == focused && isCaretVisible()) {
                // ⚠️ 光标位置必须用 caret（插入符），不能用"整串宽度"。
                // 以前写的是 textWidth(整串) + 1f，等于把光标永远钉在末尾 ——
                // 那正是"拖了也没反应"的观感来源之一。
                float caretX = textX + textWidthUpTo(shown, caret) + 1f;
                applet.stroke(UITheme.TEXT_ON_LIGHT);
                applet.strokeWeight(1.5f);
                applet.line(caretX, b[1] + 6f, caretX, b[1] + b[3] - 6f);
                applet.noStroke();
            }
        }

        // 错误提示。
        // ⚠️ 位置一律取自 errorBox()，不再自己算 —— 自己算就会和布局模型脱节，
        // 而"脱节"在代码里看不出来，只会在界面上以重叠的形式出现（踩过）。
        if (!error.isEmpty()) {
            Box eb = errorBox();
            applet.fill(UITheme.COLOR_ACCENT);
            applet.textAlign(PConstants.LEFT, PConstants.CENTER);
            applet.text(error, eb.x(), eb.y() + eb.height() / 2f);
        }

        // 两个按钮都是白底普通按钮 —— 由最终确认，但不必抢眼
        confirm.draw(mouseX, mouseY, false, false, true);
        cancel.draw(mouseX, mouseY, false, false, true);

        applet.textAlign(PConstants.LEFT, PConstants.BASELINE);

        // 提示：键盘怎么用。面板上没有可见的输入焦点机制，明说一句省得用户乱点
        applet.fill(UITheme.TEXT_DIM_ON_LIGHT);
        applet.textAlign(PConstants.LEFT, PConstants.CENTER);
        applet.textSize(UITheme.FONT_SIZE - 2);
        applet.text("↑↓ 切换输入框    Enter 确认    Esc 取消",
                panelX + UITheme.SPACE_5, panelY + panelH - 14f);
        applet.textSize(UITheme.FONT_SIZE);
        applet.textAlign(PConstants.LEFT, PConstants.BASELINE);
    }

    /**
     * 一串文字前 {@code count} 个字符的总宽度。
     *
     * <p>⚠️ 逐字符量，不用 {@code 总宽 * count / 长度} 去估 ——
     * 中文与 ASCII 宽度差一倍，估出来的位置在混排文本里会明显偏离。
     *
     * @param text  完整文字
     * @param count 字符数（会被夹到 {@code 0..text.length()}）
     * @return 像素宽度
     */
    private float textWidthUpTo(String text, int count) {
        int n = Math.max(0, Math.min(count, text.length()));
        if (n == 0) {
            return 0f;
        }
        return applet.textWidth(text.substring(0, n));
    }

    /**
     * 光标闪烁：以 1 秒为周期，前 0.6 秒显示。
     *
     * @return 这一帧是否该画光标
     */
    private boolean isCaretVisible() {
        return (System.currentTimeMillis() % 1000) < 600;
    }

    /**
     * 鼠标是否落在面板里"能点"的东西上（按钮或输入框）。
     *
     * <p>供 <code>EditorApp</code> 决定要不要给手形光标。面板是模态的，
     * 所以面板开着时只看面板内部，不管底下的编辑器。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 落在按钮或任一输入框上返回 {@code true}
     */
    public boolean isOverClickable(float mouseX, float mouseY) {
        layout();
        if (confirm.isHovered(mouseX, mouseY) || cancel.isHovered(mouseX, mouseY)) {
            return true;
        }
        for (int i = 0; i < FIELD_COUNT; i++) {
            float[] b = fieldBox(i);
            if (mouseX >= b[0] && mouseX <= b[0] + b[2]
                    && mouseY >= b[1] && mouseY <= b[1] + b[3]) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /**
     * 鼠标按下：给按钮标按下、给输入框换焦点、并把插入符挪到点击位置。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     */
    public void mousePressed(float mouseX, float mouseY) {
        layout();

        if (confirm.isHovered(mouseX, mouseY)) {
            confirm.press();
            return;
        }
        if (cancel.isHovered(mouseX, mouseY)) {
            cancel.press();
            return;
        }
        // 点某个输入框 → 焦点过去，插入符落到点击处，并开始一次可能的拖动选中
        for (int i = 0; i < FIELD_COUNT; i++) {
            float[] b = fieldBox(i);
            if (mouseX >= b[0] && mouseX <= b[0] + b[2]
                    && mouseY >= b[1] && mouseY <= b[1] + b[3]) {
                focused = i;
                int at = indexAt(mouseX, b);
                // 单击不选中任何东西：锚点与插入符重合
                anchor = at;
                caret = at;
                dragging = true;
                return;
            }
        }
        // 点在面板别处：结束拖动，但不动插入符（与常见输入框一致 ——
        // 点空白不该把光标弹回开头）
        dragging = false;
    }

    /**
     * 鼠标拖动：按住左键划过文字时**从按下处选到当前处**。
     *
     * <h2>这是"像别的文本框一样拖动选中"</h2>
     * 面板里原来的输入框完全没有选中概念（没有 selection 状态、插入符恒在串尾），
     * 所以拖了也没有任何反应。现在有了"锚点 + 插入符"这对状态：
     * 按下时两者落在同一点，拖动只移动插入符，两者之间的区间就是选中区。
     *
     * <p>往左拖和往右拖都成立 —— 由 {@link #selectionStart()} /
     * {@link #selectionEnd()} 在读取时排序，不必在拖动时判断方向。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     */
    public void mouseDragged(float mouseX, float mouseY) {
        if (!dragging || closed) {
            return;
        }
        layout();
        float[] b = fieldBox(focused);
        caret = indexAt(mouseX, b);
        error = "";
    }

    /**
     * 鼠标位置对应的字符下标。
     *
     * <p>取"文字宽度最接近的那一处"：从左往右累加每个字符的宽度，
     * 一旦累加宽度超过鼠标相对左边距的距离，就落在那个字符的边界上。
     *
     * <p>⚠️ 中文与 ASCII 宽度不同，所以必须逐字符量，不能拿
     * {@code 总宽 / 字符数} 去估 —— 那样子啊在混排文本里会明显偏。
     *
     * @param mouseX 鼠标 x（屏幕坐标）
     * @param box    {@code {x, y, w, h}}
     * @return 字符下标，范围 {@code 0..长度}
     */
    private int indexAt(float mouseX, float[] box) {
        String text = values[focused].toString();
        float rel = mouseX - (box[0] + FIELD_PAD);
        if (rel <= 0f) {
            return 0;
        }
        float acc = 0f;
        for (int i = 0; i < text.length(); i++) {
            float w = applet.textWidth(text.charAt(i));
            // 落在这个字符的前半 → 插在它前面；后半 → 插在它后面
            if (rel < acc + w / 2f) {
                return i;
            }
            acc += w;
        }
        return text.length();
    }

    /**
     * @return 选中区的起点（两个端点里靠前的那个）
     */
    public int selectionStart() {
        return Math.min(anchor, caret);
    }

    /**
     * @return 选中区的终点（两个端点里靠后的那个）
     */
    public int selectionEnd() {
        return Math.max(anchor, caret);
    }

    /**
     * @return 是否有选中的文字
     */
    public boolean hasSelection() {
        return selectionStart() != selectionEnd();
    }

    /**
     * 删掉选中的文字，并把插入符收到选区起点。
     *
     * @return 真的删了东西返回 {@code true}
     */
    private boolean deleteSelection() {
        if (!hasSelection()) {
            return false;
        }
        int from = selectionStart();
        int to = selectionEnd();
        values[focused].delete(from, to);
        anchor = from;
        caret = from;
        return true;
    }

    /**
     * 鼠标松开。确认/取消在这里生效 —— 与"按下高亮、松开触发"的常规按钮行为一致。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     */
    public void mouseReleased(float mouseX, float mouseY) {
        layout();
        boolean onConfirm = confirm.isPressed() && confirm.isHovered(mouseX, mouseY);
        boolean onCancel = cancel.isPressed() && cancel.isHovered(mouseX, mouseY);
        confirm.release();
        cancel.release();

        if (onCancel) {
            closed = true;
            result = null;
            return;
        }
        if (onConfirm) {
            submit();
        }
    }

    /**
     * 处理**按键**（方向键、回车、退格、Esc）。
     *
     * <h2>⚠️ 方法名刻意不叫 {@code keyPressed}</h2>
     * 它曾经叫 {@code keyPressed(char, int)} —— 与 {@link PApplet#keyPressed()} <strong>同名</strong>，
     * 于是很自然地被误读成"覆写了 Processing 的入口"。实际上两者毫无关系：
     * {@code PApplet} 没有 {@code keyPressed(char, int)} 这个重载，
     * 这是本类自己的方法，由 {@code EditorApp} 手动转发调用。
     * 改名为 {@link #handleKey} 就是为了让这个区别一眼可见。
     *
     * <h2>⚠️ 这里<strong>不</strong>负责输入字符</h2>
     * 字符一律走 {@link #handleTyped}。原因是 ASCII 字符会**同时**产生
     * {@code KEY_PRESSED} 与 {@code KEY_TYPED} 两个事件 —— 如果两边都追加字符，
     * 打一个 {@code a} 会进两个 {@code aa}。所以职责必须分开：
     * <b>这里管控制键，{@code handleTyped} 管字符</b>。
     *
     * @param keyCode 键码
     */
    public void handleKey(int keyCode) {
        if (keyCode == PConstants.ESC) {
            closed = true;
            result = null;
            return;
        }
        // 用 ↑/↓ 切换输入框，不用 Tab。
        //
        // Tab 在 AWT/Swing 里天生带"焦点遍历"语义（Processing 是靠
        // `canvas.setFocusTraversalKeysEnabled(false)` 才把它让给画布的），
        // 与其依赖那个设置，不如换成没有歧义的上下键。
        if (keyCode == PConstants.UP) {
            focused = (focused - 1 + FIELD_COUNT) % FIELD_COUNT;
            error = "";
            return;
        }
        if (keyCode == PConstants.DOWN) {
            focused = (focused + 1) % FIELD_COUNT;
            error = "";
            return;
        }
        if (keyCode == PConstants.ENTER || keyCode == PConstants.RETURN) {
            submit();
            return;
        }
        if (keyCode == PConstants.BACKSPACE) {
            // 有选中就删选中，没选中才删最后一个字符 —— 与常见输入框一致
            if (!deleteSelection()) {
                StringBuilder sb = values[focused];
                if (caret > 0 && caret <= sb.length()) {
                    sb.deleteCharAt(caret - 1);
                    caret--;
                }
                anchor = caret;
            }
            error = "";
            return;
        }
        if (keyCode == PConstants.DELETE) {
            // Delete 删的是插入符**后面**那个字符（与退格相反）
            if (!deleteSelection()) {
                StringBuilder sb = values[focused];
                if (caret >= 0 && caret < sb.length()) {
                    sb.deleteCharAt(caret);
                }
                anchor = caret;
            }
            error = "";
        }
    }

    /**
     * 处理**已上屏的字符**（Processing 的 {@code keyTyped}）。
     *
     * <h2>⚠️ 中文输入法只能走这条路</h2>
     * 按 Java IMF 规范，画布是被动客户：输入法组合期间用的按键会被输入法
     * 标记为 consumed，不产生 {@code keyPressed()}；上屏时只送 {@code KEY_TYPED}。
     * 所以**没有这个入口，中文一个字都进不来** —— 这就是"文本框无法输入中文"的根因，
     * 不是过滤太严，而是通道没接上。
     *
     * <p>这里收**任何可打印字符**（含中文、日文、空格）。真正非法的字符
     * （文件名不允许的 {@code \ / : * ? " < > |}）由 {@code MapNaming.sanitizeFileName}
     * 在提交时挡下并给出明确提示 —— 在**输入时**静默丢弃，用户会以为程序坏了；
     * 在**提交时**报错，他知道该改哪里。
     *
     * @param c 已上屏的字符
     */
    public void handleTyped(char c) {
        if (closed) {
            return;                 // 面板已关（比如回车提交了），别再吃字符
        }
        if (!isPrintable(c)) {
            return;
        }
        StringBuilder sb = values[focused];
        if (sb.length() >= MAX_FIELD_LEN) {
            return;
        }
        // 有选中时先删掉再插入 —— 否则"选中一段再打字"会变成插入而不是替换
        deleteSelection();
        int at = Math.max(0, Math.min(caret, sb.length()));
        sb.insert(at, c);
        caret = at + 1;
        anchor = caret;
        error = "";
    }

    /**
     * @param c 字符
     * @return 是不是可打印字符（不是控制字符）
     */
    private static boolean isPrintable(char c) {
        // Character.isISOControl 覆盖 0x00-0x1F 与 0x7F-0x9F，
        // 剩下的就是可见字符 —— 中文、日文、空格都算
        return !Character.isISOControl(c);
    }

    /** 校验并提交。不合规就把原因写到面板底部，不关闭。 */
    private void submit() {
        String name = com.yestercar.campaigneditor.util.MapNaming
                .sanitizeFileName(values[FIELD_NAME].toString());
        if (name.isEmpty()) {
            error = "地图名不能为空，也不能只由非法字符组成";
            focused = FIELD_NAME;
            return;
        }
        int cols = parseInt(values[FIELD_COLS].toString());
        int rows = parseInt(values[FIELD_ROWS].toString());
        if (cols < 1 || cols > TileMap.MAX_SIZE || rows < 1 || rows > TileMap.MAX_SIZE) {
            error = "列/行必须是 1 ~ " + TileMap.MAX_SIZE + " 的整数";
            focused = cols < 1 || cols > TileMap.MAX_SIZE ? FIELD_COLS : FIELD_ROWS;
            return;
        }
        error = "";
        result = new Result(name, cols, rows);
        closed = true;
    }

    /** 解析整数，失败返回 -1（必然越界，由调用方统一报错）。 */
    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
