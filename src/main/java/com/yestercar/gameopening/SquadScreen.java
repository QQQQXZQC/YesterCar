package com.yestercar.gameopening;

import com.yestercar.common.model.Operator;
import com.yestercar.common.ui.DesignSpace;
import com.yestercar.common.util.UITheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PImage;

/**
 * 编队界面：12 个选择空位（第 17 轮决定的形态）。
 *
 * <h2>布局（设计坐标 1440×810）</h2>
 * <pre>
 *   12 格：120×240（1:2，与 data/imgs/selectskin 的立绘同比例）
 *          6 列 × 2 行，间隙 16，左上角 (90,170)
 *          占 x 90..890，y 170..666
 *   右栏：可选干员列表 + 「开始作战」
 * </pre>
 *
 * <h2>三种格子外观</h2>
 * <ol>
 *   <li><b>空位</b>：半透明底 + 细边框 + 居中「空」</li>
 *   <li><b>已选且有立绘</b>：直接铺立绘（120×240 与 180×360 同比例，等比缩放不失真）</li>
 *   <li><b>已选但没有立绘</b>：半透明底 + 细边框 + 居中问号图标</li>
 * </ol>
 *
 * <h2>交互（本轮确定的最小可用方案）</h2>
 * 右侧列出全部干员，**点一下放进第一个空位**；点已填充的格子**取出来**。
 * 不做拖拽：12 格里只有 3 个有立绘，拖拽的收益远低于成本；
 * 而"点列表 → 进下一空位"与"部署栏按费用排序"不矛盾（槽位位置不承载语义）。
 *
 * <h2>问号图标为什么是画出来的</h2>
 * 项目里的 SVG 图标集（{@code AkIcons}，Remix Icon 子集）**没有问号**。
 * 这里用"细线圆 + 问号字形"画一个等效标记，并保留
 * {@link #drawQuestionMark} 这一个入口 —— 将来提供真正的 SVG 只改这一个方法。
 */
final class SquadScreen {

    /** 编队上限。 */
    static final int MAX_SQUAD = 12;

    /** 空位尺寸（设计坐标，1:2）。 */
    private static final float SLOT_W = 120f;
    private static final float SLOT_H = 240f;

    /** 每行几个空位。 */
    private static final int SLOTS_PER_ROW = 6;

    /** 空位间隙。 */
    private static final float SLOT_GAP = 16f;

    /** 格子区左上角。 */
    private static final float GRID_X = 90f;
    private static final float GRID_Y = 170f;

    /** 右栏（可选干员列表）左缘与行高。 */
    private static final float LIST_X = 930f;
    private static final float LIST_Y = 210f;
    private static final float LIST_ROW_H = 30f;

    /** 主行动按钮。 */
    private static final float GO_W = 300f;
    private static final float GO_H = 88f;

    private final PApplet g;
    private final DesignSpace d;
    private final Fonts fonts;

    /** 12 个空位，{@code null} = 空。 */
    // ⚠️ static：窗口缩放会重建本屏，普通字段会把编队清空
    private static final Operator[] slots = new Operator[MAX_SQUAD];

    /** 全部可选干员（按费用升序、同费按 id —— 与部署栏同一套排序）。 */
    private final List<Operator> available = new ArrayList<>();

    /** 战役界面背景图。 */
    private static final String BG_PATH = "data/imgs/fighting/Mainbackground.png";

    /** 背景图（懒加载）。 */
    private PImage bgImage;

    /** 背景是否已尝试加载（失败就不每帧重试）。 */
    private boolean bgTried;

    /** 立绘缓存，key = 图片路径。 */
    private final Map<String, PImage> portraits = new HashMap<>();

    /** 「开始作战」按钮。 */
    private final CommandCard goButton;

    /** 本关详情（右侧栏显示）。 */
    // ⚠️ static：窗口尺寸变化会重建 MainMenu → 重建本屏，
    //    普通字段会丢，导致"放大缩小后关卡详情消失"。
    private static com.yestercar.common.model.CampaignMeta campaignMeta;

    /** 战役显示名。 */
    private String campaignName = "";

    /** 正在为哪个槽位选人；{@code -1} 表示在编队页（不是选择页）。 */
    private int editingSlot = -1;

    /** 选择页的职业筛选；{@code null} = 全部。 */
    private String pickClass;

    /** 筛选后可见的干员（与 available 的 display 顺序一致）。 */
    private final List<Operator> pickShown = new ArrayList<>();

    private static final String[] CLASSES = {
            "Vanguard", "Guard", "Defender", "Sniper", "Caster", "Medic", "Supporter"};

    /** 选择页滚到第几行（每行 6 个）。 */
    private int pickRowOffset;

    /** 选择页的「返回」按钮命中区。 */
    private final float[] backBtn = new float[4];

    /** 选择页的「上一行 / 下一行」按钮命中区。 */
    private final float[] upBtn = new float[4];
    private final float[] downBtn = new float[4];

    /** 操作提示（放不下、重复选择等）。 */
    private String hint = "";
    private int hintAt;

    SquadScreen(PApplet g, DesignSpace d, Fonts fonts) {
        this.g = g;
        this.d = d;
        this.fonts = fonts;
        goButton = new CommandCard(g, d, fonts)
                .label("开始作战")
                .meta("进入战斗")
                .tone(CommandCard.Tone.DANGER)
                .labelSize(32f);
    }

    /**
     * 设置本关可选干员。
     *
     * @param ops 干员（内部会按费用升序、同费按 id 重新排序）
     */
    void setOperators(List<Operator> ops) {
        available.clear();
        if (ops != null) {
            available.addAll(ops);
        }
        // 费用升序、同费按 id —— 与部署栏同一套规则
        available.sort(java.util.Comparator
                .comparingInt(Operator::cost)
                .thenComparing(Operator::id));
        // ⚠️ 这里**不能**再 clearSelection()：
        //    slots 是 static 的，构造函数里 setOperators 一清，
        //    连"旧实例"的编队一起被清掉，于是窗口缩放重建后编队必然为空
        //    （探针实测：清空前 12 → copyStateFrom 时旧槽位已是 0）。
    }

    /**
     * @param name 战役显示名
     */
    /**
     * 设置要显示详情的关卡。
     *
     * @param campaign 关卡
     */
    void setCampaign(com.yestercar.common.model.CampaignMeta campaign) {
        campaignMeta = campaign;
    }

    void setCampaignName(String name) {
        campaignName = name == null ? "" : name;
    }

    /** 清空 12 格（每次从列表页进来都要调）。 */
    /**
     * 把另一份编队状态复制过来（窗口缩放会重建本屏，靠它保住已选内容）。
     *
     * @param old 旧实例
     */
    void copyStateFrom(SquadScreen old) {
        if (old == null) {
            System.out.println("[探针] copyStateFrom(old=null)，什么都没迁移");
            return;
        }
        System.out.println("[探针] copyStateFrom 旧槽位=" + old.selectedCount()
                + " 新实例当前=" + selectedCount());
        System.arraycopy(old.slots, 0, slots, 0, slots.length);
        editingSlot = old.editingSlot;
        pickRowOffset = old.pickRowOffset;
        campaignMeta = old.campaignMeta;
    }

    void clearSelection() {
        java.util.Arrays.fill(slots, null);
        hint = "";
        editingSlot = -1;          // 顺手关掉选人页，避免下次进来还停在这里
        pickRowOffset = 0;
    }

    /** 关闭选人页（保留已选编队）。 */
    void closePick() {
        editingSlot = -1;
        pickRowOffset = 0;
    }

    /**
     * @return 已选干员（**槽位顺序**；调用方若要按费用排，自己再排一次）
     */
    List<String> selectedIds() {
        List<String> out = new ArrayList<>();
        for (Operator op : slots) {
            if (op != null) {
                out.add(op.id());
            }
        }
        return out;
    }

    /** @return 已选人数 */
    int selectedCount() {
        int n = 0;
        for (Operator op : slots) {
            if (op != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * 诊断用：直接打开第 {@code slot} 格的选择页（截图验证用）。
     *
     * @param slot 槽位下标
     */
    void openPickForTest(int slot) {
        if (slot >= 0 && slot < MAX_SQUAD) {
            editingSlot = slot;
            pickRowOffset = 0;
        }
    }

    /**
     * 诊断用：按顺序填入若干干员（跳过不存在的 id）。
     *
     * @param ids 干员 id
     */
    void selectForTest(String... ids) {
        clearSelection();
        for (String id : ids) {
            for (Operator op : available) {
                if (op.id().equals(id)) {
                    putIntoNextFreeSlot(op);
                    break;
                }
            }
        }
    }

    /**
     * 鼠标按下：只记录，松开才生效（与列表页一致的交互）。
     *
     * @param mx 鼠标 x
     * @param my 鼠标 y
     */
    void mousePressed(float mx, float my) {
        // 选择页是整页覆盖，底下那层「开始作战」不该被按到
        if (editingSlot >= 0) {
            return;
        }
        if (selectedCount() > 0 && goButton.hit(mx, my)) {
            goButton.press();
        }
    }

    /**
     * 鼠标松开：真的落在同一处才生效。
     *
     * @param mx 鼠标 x
     * @param my 鼠标 y
     * @return 点了「开始作战」返回 {@code true}
     */
    boolean mouseReleased(float mx, float my) {
        // ⚠️ 选择页必须先判、且直接 return —— 之前「开始作战」在前面，
        //    而选择页的「下一行」按钮正好落在它的命中区里，于是点翻行
        //    会直接把战斗开起来（用户报的"越过图层开战"）。
        if (editingSlot >= 0) {
            goButton.release();
            handlePickClick(mx, my);
            return false;
        }

        boolean wasGo = goButton.isPressed();
        goButton.release();
        if (wasGo && goButton.hit(mx, my) && selectedCount() > 0) {
            return true;
        }

        // ② 点已填充格子 → 取出；点空格子 → 进选择页
        int slot = slotAt(mx, my);
        if (slot >= 0) {
            if (slots[slot] != null) {
                slots[slot] = null;
            } else {
                editingSlot = slot;
                pickRowOffset = 0;
                rebuildPickList();
                say("为第 " + (slot + 1) + " 格选择干员");
            }
            return false;
        }

        // 右栏是「关卡详情」，**只读**：点它不选干员。
        // （之前这里会把点击当成"列表行"，导致点敌人图标就自动塞人进编队。）
        return false;
    }

    /**
     * 把一个干员放进第一个空位。
     *
     * @param op 干员
     */
    private void putIntoNextFreeSlot(Operator op) {
        for (Operator s : slots) {
            if (s != null && s.id().equals(op.id())) {
                say("「" + op.name() + "」已经在编队里了");
                return;
            }
        }
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                slots[i] = op;
                hint = "";
                return;
            }
        }
        say("编队已满（" + MAX_SQUAD + " 人），先取出一个再放");
    }

    /**
     * 显示一条提示。
     *
     * @param text 提示内容
     */
    private void say(String text) {
        hint = text;
        hintAt = g.millis();
    }

    /**
     * 画整个界面（调用方已画过背景；返回箭头在之后画）。
     *
     * @param mx 鼠标 x
     * @param my 鼠标 y
     */
    void draw(float mx, float my) {
        // ⚠️ 必须自己清屏：MainMenu 在非主菜单屏仍会画背景与立绘
        g.background(UITheme.WINDOW_BG_DARK);

        // ⚠️ 选择页是**整页替换**：之前这里没有分支，所以点了空格只显示一行提示文字，
        //    网格根本没画出来 —— 用户看到的就是"没有干员可选"。
        if (editingSlot >= 0) {
            drawPickPage(mx, my);
            return;
        }

        drawHeader();
        drawSlots(mx, my);
        drawList(mx, my);
        drawGo();
        drawHint();
    }

    /** 顶部：关卡名 + 已选计数。 */
    private void drawHeader() {
        fonts.apply(g, Fonts.Role.MONO, 13f);
        g.textAlign(PConstants.LEFT, PConstants.TOP);
        g.fill(UITheme.MENU_TEXT_DIM, 0.85f * 255f);
        fonts.text(g, campaignName.isEmpty() ? "编队" : "编队 — " + campaignName,
                d.x(GRID_X), d.y(128f));

        int n = selectedCount();
        fonts.apply(g, Fonts.Role.MONO, 11f);
        g.fill(n >= MAX_SQUAD ? UITheme.SIGNAL_ACTION : UITheme.MENU_TEXT_DIM, 0.9f * 255f);
        fonts.text(g, "已选 " + n + " / " + MAX_SQUAD
                        + (n >= MAX_SQUAD ? "（已满）" : "")
                        + "    最少 1 人、最多 12 人",
                d.x(GRID_X), d.y(150f));
    }

    /** 12 个空位。 */
    private void drawSlots(float mx, float my) {
        for (int i = 0; i < MAX_SQUAD; i++) {
            float x = GRID_X + (i % SLOTS_PER_ROW) * (SLOT_W + SLOT_GAP);
            float y = GRID_Y + (i / SLOTS_PER_ROW) * (SLOT_H + SLOT_GAP);
            boolean hover = inside(mx, my, x, y, SLOT_W, SLOT_H);

            Operator op = slots[i];
            if (op == null) {
                drawSlotFrame(x, y, hover);
                drawEmptyMark(x, y);
            } else if (op.hasPortrait()) {
                drawPortrait(op, x, y, hover);
            } else {
                drawSlotFrame(x, y, hover);
                drawQuestionMark(x, y);
                // 名字压在格子底部，便于分辨"有人的空位"和"真空位"
                fonts.apply(g, Fonts.Role.MONO, 8f);
                g.textAlign(PConstants.CENTER, PConstants.BOTTOM);
                g.fill(UITheme.MENU_TEXT, 0.9f * 255f);
                fonts.text(g, op.name(), d.x(x + SLOT_W / 2f), d.y(y + SLOT_H - 8f));
            }
            // 左上角职业图标：必须在 if/else **之外**，
            // 否则只有"无立绘"分支才画得到（实测就是这样漏掉的）。
            // 但空槽位 op == null，这里必须再判一次。
            PImage cls = op == null ? null
                    : g.loadImage("data/imgs/Class/" + op.opClass() + ".png");
            if (cls != null) {
                g.imageMode(PConstants.CORNER);
                g.image(cls, d.x(x + 4f), d.y(y + 4f), d.w(28f), d.h(28f));
            }
        }
    }

    /** 统一底：半透明 + 细边框。 */
    private void drawSlotFrame(float x, float y, boolean hover) {
        g.noStroke();
        g.fill(255, 255, 255, (hover ? 0.10f : 0.06f) * 255f);
        g.rect(d.x(x), d.y(y), d.w(SLOT_W), d.h(SLOT_H));
        g.noFill();
        g.stroke(hover ? UITheme.SIGNAL_ACTION : UITheme.MENU_TEXT_DIM,
                (hover ? 0.9f : 0.45f) * 255f);
        g.strokeWeight(d.len(1f));
        g.rect(d.x(x), d.y(y), d.w(SLOT_W), d.h(SLOT_H));
        g.noStroke();
    }

    /** 空位标记：居中「空」。 */
    private void drawEmptyMark(float x, float y) {
        fonts.apply(g, Fonts.Role.MONO, 16f);
        g.textAlign(PConstants.CENTER, PConstants.CENTER);
        g.fill(UITheme.MENU_TEXT_DIM, 0.5f * 255f);
        fonts.text(g, "空", d.x(x + SLOT_W / 2f), d.y(y + SLOT_H / 2f));
    }

    /**
     * 问号图标（画出来的，见类注释）。
     *
     * <p>将来若要换成真正的 SVG，只改这一个方法。
     *
     * @param x 格子左上角 x
     * @param y 格子左上角 y
     */
    private void drawQuestionMark(float x, float y) {
        float cx = x + SLOT_W / 2f;
        float cy = y + SLOT_H / 2f - 10f;
        float r = Math.min(SLOT_W, SLOT_H) * 0.16f;

        g.noFill();
        g.stroke(UITheme.MENU_TEXT_DIM, 0.55f * 255f);
        g.strokeWeight(d.len(1.6f));
        g.ellipse(d.x(cx), d.y(cy), d.w(r * 2f), d.h(r * 2f));
        g.noStroke();

        fonts.apply(g, Fonts.Role.MONO, r * 1.5f);
        g.textAlign(PConstants.CENTER, PConstants.CENTER);
        g.fill(UITheme.MENU_TEXT_DIM, 0.75f * 255f);
        fonts.text(g, "?", d.x(cx), d.y(cy));
    }

    /** 有立绘的格子：直接铺图（源 180×360 与目标 120×240 同为 1:2，等比缩放）。 */
    private void drawPortrait(Operator op, float x, float y, boolean hover) {
        PImage img = portrait(op);
        if (img == null) {
            // 图丢了：退化成"有人的格子"，不要画成空位
            drawSlotFrame(x, y, hover);
            drawQuestionMark(x, y);
            return;
        }
        g.imageMode(PConstants.CORNER);
        g.image(img, d.x(x), d.y(y), d.w(SLOT_W), d.h(SLOT_H));
        if (hover) {
            g.noFill();
            g.stroke(UITheme.SIGNAL_ACTION, 0.95f * 255f);
            g.strokeWeight(d.len(2f));
            g.rect(d.x(x), d.y(y), d.w(SLOT_W), d.h(SLOT_H));
            g.noStroke();
        }
        // 名字条
        g.noStroke();
        g.fill(0f, 0f, 0f, 0.55f * 255f);
        g.rect(d.x(x), d.y(y + SLOT_H - 34f), d.w(SLOT_W), d.h(34f));
        fonts.apply(g, Fonts.Role.MONO, 14f);
        g.textAlign(PConstants.CENTER, PConstants.CENTER);
        g.fill(UITheme.MENU_TEXT, 0.95f * 255f);
        fonts.text(g, op.name(), d.x(x + SLOT_W / 2f), d.y(y + SLOT_H - 13f));
    }

    /**
     * 取立绘（带缓存）。
     *
     * @param op 干员
     * @return 图片；路径为空或读不到返回 {@code null}
     */
    private PImage portrait(Operator op) {
        if (!op.hasPortrait()) {
            return null;
        }
        String path = op.portrait();
        if (portraits.containsKey(path)) {
            return portraits.get(path);
        }
        PImage img = g.loadImage(path);
        if (img == null) {
            System.out.println("[SquadScreen] 立绘读取失败: " + path);
        }
        portraits.put(path, img);
        return img;
    }

    /** 按职业筛选重建选择页列表（按 id 字典序）。 */
    private void rebuildPickList() {
        pickShown.clear();
        for (Operator o : available) {
            if (pickClass == null || pickClass.equals(o.opClass())) {
                pickShown.add(o);
            }
        }
        pickShown.sort(java.util.Comparator.comparing(Operator::id));
        pickRowOffset = 0;
    }

    /**
     * 干员选择页（整页）。
     *
     * <h2>为什么是整页而不是抽屉</h2>
     * 干员总数会超过 12，整页网格天然可扩展；槽位位置不承载语义，
     * 所以"先点格子再选人"不会引入歧义。
     */
    private void drawPickPage(float mx, float my) {
        // 兜底：任何路径进入选择页，都保证列表是填好的
        if (pickShown.isEmpty()) {
            rebuildPickList();
        }
        fonts.apply(g, Fonts.Role.MONO, 13f);
        g.textAlign(PConstants.LEFT, PConstants.TOP);
        g.fill(UITheme.MENU_TEXT_DIM, 0.85f * 255f);
        fonts.text(g, "选择干员 —— 第 " + (editingSlot + 1) + " 格", d.x(GRID_X), d.y(128f));

        // 返回按钮
        backBtn[0] = d.x(LIST_X - 10f);
        backBtn[1] = d.y(120f);
        backBtn[2] = d.w(150f);
        backBtn[3] = d.h(34f);
        boolean backHover = inside(mx, my, LIST_X - 10f, 120f, 150f, 34f);
        g.noStroke();
        g.fill(backHover ? 0x33FFFFFF : 0x1AFFFFFF);
        g.rect(backBtn[0], backBtn[1], backBtn[2], backBtn[3], 3f);
        g.fill(UITheme.MENU_TEXT, 0.95f * 255f);
        fonts.apply(g, Fonts.Role.MONO, 11f);
        g.textAlign(PConstants.CENTER, PConstants.CENTER);
        fonts.text(g, "< 返回编队", d.x(LIST_X - 10f + 75f), d.y(120f + 17f));

        int cols = SLOTS_PER_ROW;
        int perRow = cols;
        int start = pickRowOffset * perRow;
        for (int i = start; i < pickShown.size() && i < start + perRow * 2; i++) {
            int k = i - start;
            float x = GRID_X + (k % cols) * (SLOT_W + SLOT_GAP);
            float y = GRID_Y + (k / cols) * (SLOT_H + SLOT_GAP);
            if (y + SLOT_H > 700f) {
                break;
            }
            Operator op = pickShown.get(i);
            boolean hover = inside(mx, my, x, y, SLOT_W, SLOT_H);
            boolean used = isChosen(op);

            if (op.hasPortrait()) {
                drawPortrait(op, x, y, hover && !used);
            } else {
                drawSlotFrame(x, y, hover && !used);
                drawQuestionMark(x, y);
                fonts.apply(g, Fonts.Role.MONO, 8f);
                g.textAlign(PConstants.CENTER, PConstants.BOTTOM);
                g.fill(UITheme.MENU_TEXT, 0.9f * 255f);
                fonts.text(g, op.name(), d.x(x + SLOT_W / 2f), d.y(y + SLOT_H - 8f));
            }

            // 职业图标（左上角）
            PImage cls = g.loadImage("data/imgs/Class/" + op.opClass() + ".png");
            if (cls != null) {
                g.imageMode(PConstants.CORNER);
                g.image(cls, d.x(x + 4f), d.y(y + 4f), d.w(28f), d.h(28f));
            }

            // 费用角标
            fonts.apply(g, Fonts.Role.MONO, 14f);
            g.textAlign(PConstants.CENTER, PConstants.CENTER);
            g.fill(0xFFE8C15A, 0.95f * 255f);
            fonts.text(g, op.cost() + "费", d.x(x + SLOT_W / 2f), d.y(y + 14f));

            if (used) {
                g.noStroke();
                g.fill(0, 0, 0, 170);
                g.rect(d.x(x), d.y(y), d.w(SLOT_W), d.h(SLOT_H));
                fonts.apply(g, Fonts.Role.MONO, 12f);
                g.fill(UITheme.MENU_TEXT_DIM, 0.95f * 255f);
                fonts.text(g, "已在编队", d.x(x + SLOT_W / 2f), d.y(y + SLOT_H / 2f));
            }
        }

        // 翻行按钮（干员多于一屏时用）
        int totalRows = (pickShown.size() + perRow - 1) / perRow;
        boolean canDown = pickRowOffset + 2 < totalRows;
        downBtn[0] = d.x(LIST_X - 10f);
        downBtn[1] = d.y(700f);
        downBtn[2] = d.w(150f);
        downBtn[3] = d.h(34f);
        g.noStroke();
        g.fill(canDown ? 0x33FFFFFF : 0x11FFFFFF);
        g.rect(downBtn[0], downBtn[1], downBtn[2], downBtn[3], 3f);
        g.fill(canDown ? UITheme.MENU_TEXT : UITheme.MENU_TEXT_DIM, 0.9f * 255f);
        fonts.text(g, "下一行 v", d.x(LIST_X - 10f + 75f), d.y(700f + 17f));

        upBtn[0] = d.x(LIST_X - 10f);
        upBtn[1] = d.y(660f);
        upBtn[2] = d.w(150f);
        upBtn[3] = d.h(34f);
        boolean canUp = pickRowOffset > 0;
        g.fill(canUp ? 0x33FFFFFF : 0x11FFFFFF);
        g.rect(upBtn[0], upBtn[1], upBtn[2], upBtn[3], 3f);
        g.fill(canUp ? UITheme.MENU_TEXT : UITheme.MENU_TEXT_DIM, 0.9f * 255f);
        fonts.text(g, "^ 上一行", d.x(LIST_X - 10f + 75f), d.y(660f + 17f));
    }

    /**
     * 选择页的点击处理。
     *
     * @param mx 鼠标 x
     * @param my 鼠标 y
     */
    private void handlePickClick(float mx, float my) {
        if (inside(mx, my, LIST_X - 10f, 120f, 150f, 34f)) {
            editingSlot = -1;
            hint = "";
            return;
        }
        if (inside(mx, my, LIST_X - 10f, 700f, 150f, 34f)) {
            int totalRows = (pickShown.size() + SLOTS_PER_ROW - 1) / SLOTS_PER_ROW;
            if (pickRowOffset + 2 < totalRows) {
                pickRowOffset++;
            }
            return;
        }
        if (inside(mx, my, LIST_X - 10f, 660f, 150f, 34f)) {
            pickRowOffset = Math.max(0, pickRowOffset - 1);
            return;
        }

        int start = pickRowOffset * SLOTS_PER_ROW;
        for (int i = start; i < pickShown.size() && i < start + SLOTS_PER_ROW * 2; i++) {
            int k = i - start;
            float x = GRID_X + (k % SLOTS_PER_ROW) * (SLOT_W + SLOT_GAP);
            float y = GRID_Y + (k / SLOTS_PER_ROW) * (SLOT_H + SLOT_GAP);
            if (y + SLOT_H > 700f) {
                break;
            }
            if (!inside(mx, my, x, y, SLOT_W, SLOT_H)) {
                continue;
            }
            Operator op = pickShown.get(i);
            if (isChosen(op)) {
                say("「" + op.name() + "」已经在编队里了");
                return;
            }
            slots[editingSlot] = op;
            hint = "";
            editingSlot = -1;
            return;
        }
    }

    /** 右侧可选干员列表。 */
    /**
     * 右侧栏：关卡详情（关卡名 + 各类型敌人数量）。
     *
     * <p>原来这里是"可选干员"列表，但编队改成"点空格 → 整页选择"之后，
     * 那栏就冗余了，正好腾给关卡详情。
     *
     * @param mx 鼠标 x
     * @param my 鼠标 y
     */
    private void drawList(float mx, float my) {
        fonts.apply(g, Fonts.Role.MONO, 11f);
        g.textAlign(PConstants.LEFT, PConstants.TOP);
        g.fill(UITheme.MENU_TEXT_DIM, 0.9f * 255f);
        fonts.text(g, "关卡详情", d.x(LIST_X), d.y(LIST_Y - 30f));

        if (campaignMeta == null) {
            return;
        }
        fonts.apply(g, Fonts.Role.MONO, 13f);
        g.fill(UITheme.MENU_TEXT);
        fonts.text(g, campaignMeta.name(), d.x(LIST_X), d.y(LIST_Y));

        // 关卡名下的细分隔线
        g.stroke(255, 255, 255, 0.25f * 255f);
        g.strokeWeight(1f);
        g.line(d.x(LIST_X), d.y(LIST_Y + 24f), d.x(LIST_X + GO_W), d.y(LIST_Y + 24f));
        g.noStroke();

        // 敌人构成：整关合计
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (com.yestercar.common.model.Wave w : campaignMeta.waves()) {
            for (com.yestercar.common.model.Wave.EnemySpawn sp : w.spawns()) {
                counts.merge(sp.enemy(), sp.count(), Integer::sum);
            }
        }
        java.util.Map<String, com.yestercar.runtime.battle.EnemyType> types =
                com.yestercar.runtime.battle.EnemyType.loadAll();

        // 敌人构成：图标 + 数量（与自定义关卡列表同一套展示）
        java.util.List<java.util.Map.Entry<String, Integer>> list =
                new java.util.ArrayList<>(counts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        java.util.Map<String, com.yestercar.runtime.battle.EnemyType> allTypes =
                com.yestercar.runtime.battle.EnemyType.loadAll();

        float ix0 = LIST_X;
        float iy = LIST_Y + 48f;
        int n = 0;
        for (java.util.Map.Entry<String, Integer> e : list) {
            if (n >= 12) {
                break;
            }
            if (n == 6) {
                iy += 58f;
            }
            float ix = ix0 + (n % 6) * 44f;
            com.yestercar.runtime.battle.EnemyType t = allTypes.get(e.getKey());
            processing.core.PImage img =
                    t == null ? null : g.loadImage(t.icon());
            if (img != null) {
                g.imageMode(PConstants.CORNER);
                g.image(img, d.x(ix), d.y(iy), d.w(34f), d.h(34f));
            } else {
                g.noStroke();
                g.fill(255, 255, 255, 0.10f * 255f);
                g.rect(d.x(ix), d.y(iy), d.w(34f), d.h(34f), 3f);
            }
            fonts.apply(g, Fonts.Role.MONO, 9f);
            g.textAlign(PConstants.CENTER, PConstants.TOP);
            g.fill(UITheme.MENU_TEXT, 0.95f * 255f);
            fonts.text(g, "x" + e.getValue(), d.x(ix + 17f), d.y(iy + 38f));
            n++;
        }
    }

    /** 「开始作战」按钮。 */
    private void drawGo() {
        boolean canGo = selectedCount() > 0;
        goButton.disabled(!canGo)
                .at(d.x(LIST_X), d.y(660f), d.w(GO_W), d.h(GO_H));
        goButton.draw();
        if (!canGo) {
            fonts.apply(g, Fonts.Role.MONO, 10f);
            g.textAlign(PConstants.LEFT, PConstants.TOP);
            g.fill(UITheme.MENU_TEXT_DIM, 0.7f * 255f);
            fonts.text(g, "至少放入 1 名干员", d.x(LIST_X), d.y(660f + GO_H + 10f));
        }
    }

    /** 操作提示（3 秒后淡出）。 */
    private void drawHint() {
        if (hint.isEmpty()) {
            return;
        }
        int age = g.millis() - hintAt;
        if (age > 3000) {
            return;
        }
        float a = Math.min(1f, (3000 - age) / 600f);
        fonts.apply(g, Fonts.Role.MONO, 10f);
        g.textAlign(PConstants.LEFT, PConstants.TOP);
        g.fill(UITheme.SIGNAL_ACTION, a * 255f);
        fonts.text(g, hint, d.x(GRID_X), d.y(GRID_Y + 2f * (SLOT_H + SLOT_GAP) + 8f));
    }

    // ------------------------------------------------------------------
    // 命中测试与工具
    // ------------------------------------------------------------------

    /**
     * @param mx 鼠标 x
     * @param my 鼠标 y
     * @return 命中的格子下标；未命中返回 -1
     */
    private int slotAt(float mx, float my) {
        for (int i = 0; i < MAX_SQUAD; i++) {
            float x = GRID_X + (i % SLOTS_PER_ROW) * (SLOT_W + SLOT_GAP);
            float y = GRID_Y + (i / SLOTS_PER_ROW) * (SLOT_H + SLOT_GAP);
            if (inside(mx, my, x, y, SLOT_W, SLOT_H)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @param mx 鼠标 x
     * @param my 鼠标 y
     * @return 命中的列表行；未命中返回 -1
     */
    private int listRowAt(float mx, float my) {
        for (int i = 0; i < pickShown.size(); i++) {
            float y = LIST_Y + i * LIST_ROW_H;
            if (inside(mx, my, LIST_X, y, GO_W, LIST_ROW_H - 4f)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @param op 干员
     * @return 是否已在编队里
     */
    private boolean isChosen(Operator op) {
        for (Operator s : slots) {
            if (s != null && s.id().equals(op.id())) {
                return true;
            }
        }
        return false;
    }

    /** 设计坐标下的矩形命中。 */
    private boolean inside(float mx, float my, float x, float y, float w, float h) {
        float px = d.toDesignX(mx);
        float py = d.toDesignY(my);
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }
}
