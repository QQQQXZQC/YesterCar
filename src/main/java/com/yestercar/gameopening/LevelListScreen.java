package com.yestercar.gameopening;

import com.yestercar.common.model.Campaign;
import com.yestercar.common.ui.DesignSpace;
import com.yestercar.common.util.UITheme;
import processing.core.PApplet;
import processing.core.PConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * 关卡列表页 —— 自定义关卡走的版式。
 *
 * <h2>版式（Q4 定的 60 / 40）</h2>
 * <pre>
 *   ┌────────────────────────────┬──────────────────────┐
 *   │  6 行关卡，每行一张命令卡     │  信息面板（50% 底）    │
 *   │  行高 = 列表区高度 / 6       │    关卡名（等宽、大）   │
 *   │                            │    波次 / 敌人         │
 *   │                            │    尺寸                │
 *   │  [ ‹ ]  [ › ]              │                      │
 *   │                            │      [ 准备作战 ]（红）  │
 *   └────────────────────────────┴──────────────────────┘
 * </pre>
 *
 * <h2>⚠️ 「准备作战」在信息面板上</h2>
 * 它**只在选中了关卡之后**出现 —— 没选就没有可"准备"的东西。
 * 这与"没实现的东西不许画成能用的样子"是同一条原则的反面：
 * **已实现的东西，在它能用的那一刻才出现**。
 *
 * <h2>⚠️ 单击 = 选中、不前进</h2>
 * 点一行只在右侧显示它的信息。前进要靠「准备作战」——
 * 两次点击各有明确含义：**第一次看，第二次走**。
 */
final class LevelListScreen {

    /** 一页几行（Q4 定 6）。 */
    static final int ROWS_PER_PAGE = 6;

    /** 列表区占内容宽的比例（Q4 定 60%）。 */
    private static final float LIST_W_FRACTION = 0.60f;

    /** 面板与列表之间的间隙（设计坐标）。 */
    private static final float GAP = 24f;

    /** 翻页卡的高度（设计坐标）。 */
    private static final float NAV_H = 30f;

    /** 翻页卡宽度（设计坐标）。 */
    private static final float NAV_W = 54f;

    private final PApplet g;
    private final DesignSpace d;
    private final Fonts fonts;

    private final List<CommandCard> rows = new ArrayList<>();
    private final CommandCard prevPage;
    private final CommandCard nextPage;
    private final CommandCard readyButton;

    /** 当前页的关卡（由调用方 setItems 填）。 */
    private final List<Campaign> items = new ArrayList<>();

    private int page;
    private int hoveredRow = -1;
    private Campaign pressedItem;
    private String hint = "";
    private int hintAt;

    /** 当前被按下的卡 —— 按下只标记，松开才生效。 */
    private CommandCard pressedCard;

    LevelListScreen(PApplet g, DesignSpace d, Fonts fonts) {
        this.g = g;
        this.d = d;
        this.fonts = fonts;
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            rows.add(new CommandCard(g, d, fonts));
        }
        prevPage = new CommandCard(g, d, fonts);
        nextPage = new CommandCard(g, d, fonts);
        readyButton = new CommandCard(g, d, fonts);
        layout();
    }

    /** 交给本页显示的关卡。 */
    void setItems(List<Campaign> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        page = 0;
        pressedItem = null;
        hint = "";
    }

    /**
     * @return 当前选中的关卡；没选返回 {@code null}
     */
    Campaign selected() {
        return pressedItem;
    }

    /**
     * 清掉选中，回到"一个都没选"的初始状态。
     *
     * <p>每次进入本页都要调 —— 见 {@code MainMenu.goScreen} 的说明。
     */
    void clearSelection() {
        pressedItem = null;
        hint = "";
    }

    /** @return 总页数（至少 1）。 */
    private int pageCount() {
        return Math.max(1, (items.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
    }

    // ------------------------------------------------------------------
    // 版式
    // ------------------------------------------------------------------

    /**
     * @return 内容区（返回箭头之下、底部留白之上）的矩形（设计坐标）。
     *
     * <p>⚠️ 底部要留出 <b>翻页行</b>的高度。第一版没留，
     * 6 行列表把翻页按钮顶到了窗口外，整条被切掉。
     */
    private float[] content() {
        float top = 96f;
        float bottomPad = 28f + NAV_H + 10f;
        return new float[]{28f, top, DesignSpace.BASE_W - 56f,
                DesignSpace.BASE_H - top - bottomPad};
    }

    private float[] listBox() {
        float[] c = content();
        return new float[]{c[0], c[1], c[2] * LIST_W_FRACTION, c[3]};
    }

    private float[] panelBox() {
        float[] c = content();
        float[] l = listBox();
        float x = l[0] + l[2] + GAP;
        return new float[]{x, c[1], c[0] + c[2] - x, c[3]};
    }

    private void layout() {
        float[] l = listBox();
        float rowGap = 6f;
        float rowH = (l[3] - rowGap * (ROWS_PER_PAGE - 1)) / ROWS_PER_PAGE;
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            rows.get(i).at(d.x(l[0]), d.y(l[1] + i * (rowH + rowGap)),
                    d.w(l[2]), d.h(rowH));
        }
        // 翻页卡放在列表下方的预留行里
        float navY = l[1] + l[3] + 10f;
        prevPage.at(d.x(l[0]), d.y(navY), d.w(NAV_W), d.h(NAV_H));
        nextPage.at(d.x(l[0] + NAV_W + 10f), d.y(navY), d.w(NAV_W), d.h(NAV_H));

        // 「准备作战」在信息面板右下角
        float[] p = panelBox();
        float bw = 150f;
        float bh = 40f;
        readyButton.at(d.x(p[0] + p[2] - bw), d.y(p[1] + p[3] - bh), d.w(bw), d.h(bh));
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    void draw(float mouseX, float mouseY) {
        int now = g.millis();
        fillRows();
        hoveredRow = -1;

        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            CommandCard c = rows.get(i);
            c.update(mouseX, mouseY, now);
            if (c.hit(mouseX, mouseY)) {
                hoveredRow = i;
            }
            c.draw();
        }

        // 翻页：只有多于一页才可用
        boolean multi = pageCount() > 1;
        prevPage.label("‹").meta((page + 1) + "/" + pageCount())
                .tone(CommandCard.Tone.MUTED).disabled(!multi);
        nextPage.label("›").meta("翻页")
                .tone(CommandCard.Tone.MUTED).disabled(!multi);
        prevPage.update(mouseX, mouseY, now);
        nextPage.update(mouseX, mouseY, now);
        prevPage.draw();
        nextPage.draw();

        drawPanel(mouseX, mouseY, now);
        drawHint(now);
    }

    private void fillRows() {
        int start = page * ROWS_PER_PAGE;
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            CommandCard c = rows.get(i);
            int idx = start + i;
            if (idx >= items.size()) {
                // 空格子：不给内容，也不可点（ak-ui 的 __command-blank 就是这个用途）
                c.label("").meta("").watermark("").tone(CommandCard.Tone.DARK)
                        .disabled(true).selected(false);
                continue;
            }
            Campaign camp = items.get(idx);
            boolean sel = pressedItem != null && pressedItem.id().equals(camp.id());
            c.label(camp.name())
                    .meta(shortMeta(camp))
                    .watermark(String.valueOf(idx + 1))
                    .tone(sel ? CommandCard.Tone.MUTED : CommandCard.Tone.DARK)
                    .disabled(false)
                    .selected(sel);
        }
    }

    private static String shortMeta(Campaign c) {
        return c.meta().waves().size() + "波次 · " + c.meta().totalEnemies() + "敌";
    }

    /**
     * 右侧信息面板。
     *
     * <p>底色用 {@code --ak-surface-raised}（与命令卡同色）再乘 50% ——
     * Q5 的决定。面板与命令卡是同一种东西（展示信息的暗面），
     * 同一个面色最一致；另起一个深色值会显得它比卡片更"重"。
     */
    private void drawPanel(float mouseX, float mouseY, int now) {
        float[] p = panelBox();
        g.noStroke();
        g.fill(UITheme.MENU_RAISED, 0.5f * 255);
        CutShape.square(g, d.x(p[0]), d.y(p[1]), d.w(p[2]), d.h(p[3]));
        // 内缩细边框 —— 与命令卡同一手势
        g.noFill();
        g.stroke(255, 255, 255, 0.18f * 255);
        g.strokeWeight(d.len(1f));
        CutShape.insetStroke(g, d.x(p[0]), d.y(p[1]), d.w(p[2]), d.h(p[3]), d.len(3.55f));
        g.noStroke();

        float x = p[0] + 22f;
        float y = p[1] + 26f;

        if (pressedItem == null) {
            fonts.apply(g, Fonts.Role.MONO, 11f);
            g.textAlign(PConstants.LEFT, PConstants.TOP);
            g.fill(UITheme.MENU_TEXT_DIM, 1f * 255);   // 别太暗：这是引导语，要看得见
            fonts.text(g, "点左边的关卡查看信息", d.x(x), d.y(y));
            return;
        }

        // ① 名字（大、等宽 —— ak-ui 的 __level-ring strong 就是等宽数值）
        fonts.apply(g, Fonts.Role.COMMAND, 24f);
        g.textAlign(PConstants.LEFT, PConstants.BASELINE);
        g.fill(UITheme.MENU_TEXT);
        fonts.text(g, fit(pressedItem.name(), p[2] - 44f), d.x(x), d.y(y + 22f));

        // 一条分隔线（ak-ui 的 __line）
        g.stroke(255, 255, 255, 0.30f * 255);
        g.strokeWeight(1f);
        g.line(d.x(x), d.y(y + 38f), d.x(p[0] + p[2] - 22f), d.y(y + 38f));
        g.noStroke();

        // ②③ 波次/敌人、尺寸 —— 只有这三项（Q5 的决定）
        float ry = y + 62f;
        drawField(x, ry, "波次 / 敌人",
                pressedItem.meta().waves().size() + " 波 · "
                        + pressedItem.meta().totalEnemies() + " 敌");
        drawField(x, ry + 46f, "尺寸",
                pressedItem.meta().map().isEmpty() ? "—" : sizeOf(pressedItem));

        // 敌人构成：**整关合计**（选关时要知道"这关有什么、多不多、有没有重甲"，
        // 逐波清单留给编辑器）
        drawEnemyRoster(x, ry + 92f, pressedItem);

        // ④ 「准备作战」——只在选中后出现
        readyButton.label("准备作战")
                .meta("")
                .tone(CommandCard.Tone.DANGER)
                .disabled(false)
                .selected(false);
        readyButton.update(mouseX, mouseY, now);
        readyButton.draw();
    }

    /**
     * 画"敌人构成"：每张敌人图标 + 整关合计数量。
     *
     * @param x   设计坐标 x
     * @param y   设计坐标 y
     * @param c   战役
     */
    private void drawEnemyRoster(float x, float y, Campaign c) {
        // 防御性判空：面板绘制与选中状态可能跨帧不同步（实测 NPE）
        if (c == null || c.meta() == null) {
            return;
        }
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (com.yestercar.common.model.Wave w : c.meta().waves()) {
            for (com.yestercar.common.model.Wave.EnemySpawn sp : w.spawns()) {
                counts.merge(sp.enemy(), sp.count(), Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return;
        }
        // 多的排前面，看起来像"这关以什么为主"
        java.util.List<java.util.Map.Entry<String, Integer>> list =
                new java.util.ArrayList<>(counts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());

        java.util.Map<String, com.yestercar.runtime.battle.EnemyType> types =
                com.yestercar.runtime.battle.EnemyType.loadAll();

        fonts.apply(g, Fonts.Role.MONO, 8f);
        g.textAlign(PConstants.LEFT, PConstants.BASELINE);
        g.fill(UITheme.MENU_TEXT_DIM, 0.85f * 255f);
        fonts.text(g, "敌人构成", d.x(x), d.y(y));

        float ix = x;
        float iy = y + 12f;
        int perRow = 6;
        int n = 0;
        for (java.util.Map.Entry<String, Integer> e : list) {
            if (n >= 12) {
                break;
            }
            if (n > 0 && n % perRow == 0) {
                ix = x;
                iy += 58f;
            }
            com.yestercar.runtime.battle.EnemyType t = types.get(e.getKey());
            processing.core.PImage img = t == null ? null : enemyIcon(t.icon());
            if (img != null) {
                g.imageMode(PConstants.CORNER);
                g.image(img, d.x(ix), d.y(iy), d.w(34f), d.h(34f));
            } else {
                g.noStroke();
                g.fill(255, 255, 255, 0.10f * 255f);
                g.rect(d.x(ix), d.y(iy), d.w(34f), d.h(34f), 3f);
            }
            fonts.apply(g, Fonts.Role.MONO, 8f);
            g.textAlign(PConstants.CENTER, PConstants.TOP);
            g.fill(UITheme.MENU_TEXT, 0.95f * 255f);
            fonts.text(g, "x" + e.getValue(), d.x(ix + 17f), d.y(iy + 38f));
            ix += 44f;
            n++;
        }
    }

    /** 敌人图标缓存（key = 路径）。 */
    private final java.util.Map<String, processing.core.PImage> enemyIcons = new java.util.HashMap<>();

    /**
     * 取敌人图标（带缓存）。
     *
     * @param path 图标路径
     * @return 图片；读不到返回 {@code null}
     */
    private processing.core.PImage enemyIcon(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (enemyIcons.containsKey(path)) {
            return enemyIcons.get(path);
        }
        processing.core.PImage img = g.loadImage(path);
        enemyIcons.put(path, img);
        return img;
    }

    /** 读地图的尺寸。读不出来就显示 {@code —}，不编数字。 */
    private String sizeOf(Campaign c) {
        try {
            if (c.dir() == null) {
                return "官方（在 jar 内）";
            }
            com.yestercar.campaigneditor.model.TileMap m =
                    com.yestercar.campaigneditor.io.MapJsonIO.load(c.mapFile());
            return m.cols() + " × " + m.rows();
        } catch (Exception e) {
            return "—";
        }
    }

    private void drawField(float x, float y, String label, String value) {
        fonts.apply(g, Fonts.Role.MONO, 9f);
        g.textAlign(PConstants.LEFT, PConstants.BASELINE);
        g.fill(UITheme.MENU_TEXT_DIM, 0.6f * 255);
        fonts.text(g, label, d.x(x), d.y(y));

        fonts.apply(g, Fonts.Role.MONO, 15f);
        g.fill(UITheme.MENU_TEXT);
        fonts.text(g, value, d.x(x), d.y(y + 20f));
    }

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
        fonts.text(g, hint, d.x(28f), d.y(DesignSpace.BASE_H - 12f));
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /**
     * 鼠标按下：记住按在哪张卡上。
     *
     * <p>与 {@code .ak-command} 一致 —— **按下只标记，松开才生效**。
     * 这样"按下后拖走再松开"不会误触发，是按钮该有的手感。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     */
    void mousePressed(float mouseX, float mouseY) {
        pressedCard = null;
        for (CommandCard c : allClickable()) {
            boolean on = c.hit(mouseX, mouseY);
            if (on) {
                c.press();
                pressedCard = c;
                break;
            }
        }
    }

    /**
     * 鼠标松开：真的落在同一张卡上才生效。
     *
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
            } else if (target == prevPage) {
                if (pageCount() > 1) {
                    page = Math.floorMod(page - 1, pageCount());
                }
            } else if (target == nextPage) {
                if (pageCount() > 1) {
                    page = Math.floorMod(page + 1, pageCount());
                }
            } else {
                int i = rows.indexOf(target);
                if (i >= 0) {
                    int idx = page * ROWS_PER_PAGE + i;
                    if (idx < items.size()) {
                        // ⚠️ 单击只是选中 —— 前进要靠「准备作战」。
                        // 两次点击各有明确含义：第一次看，第二次走。
                        //
                        // 选中不给提示语：界面上已经有两重反馈
                        // （行高亮 + 右侧面板出现内容与「准备作战」）。
                        // 再加一行"已选中「XX」"只是噪声。
                        pressedItem = items.get(idx);
                    }
                }
            }
        }

        for (CommandCard c : allClickable()) {
            c.release();
        }
        pressedCard = null;
        return onReady;
    }

    private List<CommandCard> allClickable() {
        List<CommandCard> all = new ArrayList<>(rows);
        all.add(prevPage);
        all.add(nextPage);
        all.add(readyButton);
        return all;
    }
}
