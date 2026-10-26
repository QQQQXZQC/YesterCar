package com.yestercar.gameopening;

import com.yestercar.common.model.Operator;
import com.yestercar.common.model.DamageType;
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
 * 干员总览页。
 *
 * <h2>布局</h2>
 * <pre>
 *   背景：data/imgs/selectskin/operatorBackground.png（等比铺满 + 居中裁切）
 *   顶部：7 个「职业 + 名字」图标（150×66），点击筛选，再点取消；切换时页码归位
 *   网格：6 列 × 2 行 = 12 个/页，按 id 字典序；右侧 ▲ 1/3 ▼ 翻页
 *   卡片：120×240 立绘（等比居中）+ 左上角 28×28 职业图标，**不显示名字**
 * </pre>
 *
 * <h2>详情浮层</h2>
 * 点卡片 → 左半屏 75% 灰底浮层：左边全身立绘，右边 7 项数值（图标 26×26）自上而下。
 * 点浮层以外的任何地方关闭。
 */
final class OperatorScreen {

    /** 卡片尺寸（设计坐标，比编队页略小一点以容纳 6 列 + 右侧翻页）。 */
    private static final float CARD_W = 150f;
    private static final float CARD_H = 250f;
    private static final float GAP = 14f;
    private static final int PER_ROW = 6;
    private static final int ROWS = 2;

    /** 网格左上角。 */
    private static final float GRID_X = 170f;   // 用户指定

    /** 翻页控件的 x（固定，不随 GRID_X 变化）。 */
    private static final float PAGER_X = 270f + 6 * (CARD_W + GAP) + 8f;
    private static final float GRID_Y = 200f;

    /** 顶部筛选条。 */
    private static final float CLASS_Y = 96f;
    private static final float CLASS_W = 150f;
    private static final float CLASS_H = 66f;
    private static final float CLASS_GAP = 10f;

    /** 职业顺序（与素材文件同名）。 */
    private static final String[] CLASSES = {
            "Vanguard", "Guard", "Defender", "Sniper", "Caster", "Medic", "Supporter"};
    /** 职业中文名（仅用于提词，筛选条用图片）。 */
    private static final String[] CLASS_CN = {
            "先锋", "近卫", "重装", "狙击", "术师", "医疗", "辅助"};

    private final PApplet g;
    private final DesignSpace d;
    private final Fonts fonts;

    private final List<Operator> all = new ArrayList<>();
    private final List<Operator> shown = new ArrayList<>();

    /** 选中的职业筛选；{@code null} = 全部。 */
    private String filterClass;

    private int page;

    /** 点开的干员；{@code null} = 没开详情。 */
    private Operator detail;

    /** 详情浮层的宽度（设计坐标），随立绘尺寸变化，用于"点外面关闭"。 */
    private float detailPanelW;

    /** 卡片矩形（用于命中测试），与 {@link #shown} 对齐。 */
    private final List<float[]> cardRects = new ArrayList<>();

    /** 图片缓存。 */
    private final Map<String, PImage> images = new HashMap<>();

    private PImage background;

    OperatorScreen(PApplet g, DesignSpace d, Fonts fonts) {
        this.g = g;
        this.d = d;
        this.fonts = fonts;
    }

    /**
     * 设置全部干员（按 id 字典序排好）。
     *
     * @param ops 干员
     */
    void setItems(List<Operator> ops) {
        all.clear();
        if (ops != null) {
            all.addAll(ops);
        }
        all.sort(java.util.Comparator.comparing(Operator::id));
        applyFilter();
    }

    private void applyFilter() {
        shown.clear();
        for (Operator o : all) {
            if (filterClass == null || filterClass.equals(o.opClass())) {
                shown.add(o);
            }
        }
        page = 0;
        detail = null;
    }

    private int pageCount() {
        int per = PER_ROW * ROWS;
        return Math.max(1, (shown.size() + per - 1) / per);
    }

    /**
     * @return 详情浮层是否打开（打开时上层应屏蔽返回按钮，避免误触）
     */
    boolean hasDetail() {
        return detail != null;
    }

    /** 画整页。 */
    void draw(float mx, float my) {
        drawBackground();
        drawClassBar(mx, my);
        drawGrid(mx, my);
        drawPager(mx, my);
        drawDetail(mx, my);
    }

    private void drawBackground() {
        if (background == null) {
            background = load("data/imgs/selectskin/operatorBackground.png");
        }
        g.background(UITheme.WINDOW_BG_DARK);
        if (background != null) {
            g.imageMode(PConstants.CORNER);
            float sc = Math.max(g.width / (float) background.width,
                    g.height / (float) background.height);
            float w = background.width * sc;
            float h = background.height * sc;
            g.image(background, (g.width - w) / 2f, (g.height - h) / 2f, w, h);
        }
    }

    /** 顶部 7 个职业筛选（图片版，点击切换，再点取消）。 */
    private void drawClassBar(float mx, float my) {
        float total = CLASSES.length * CLASS_W + (CLASSES.length - 1) * CLASS_GAP;
        float x0 = (g.width - d.w(total)) / 2f;
        for (int i = 0; i < CLASSES.length; i++) {
            String cls = CLASSES[i];
            float x = x0 + i * d.w(CLASS_W + CLASS_GAP);
            float y = d.y(CLASS_Y);
            boolean sel = cls.equals(filterClass);
            PImage img = load("data/imgs/Class/" + cls + "withname.png");
            float alpha = sel ? 255f : 170f;
            // 灰色底板：深色背景上图标本身对比度不够，垫一层才看得清边缘
            g.noStroke();
            g.fill(235f, 235f, 238f, sel ? 240f : 170f);   // 白色底板
            g.rect(x, y, d.w(CLASS_W), d.h(CLASS_H), 3f);
            if (img != null) {
                g.tint(255f, alpha);
                g.imageMode(PConstants.CORNER);
                g.image(img, x, y, d.w(CLASS_W), d.h(CLASS_H));
                g.noTint();
            }
            if (sel) {
                g.stroke(UITheme.SIGNAL_ACTION);
                g.strokeWeight(3f);
                g.line(x, y + d.h(CLASS_H) + 4f, x + d.w(CLASS_W), y + d.h(CLASS_H) + 4f);
                g.noStroke();
            }
        }
    }

    /** 6×2 网格。 */
    private void drawGrid(float mx, float my) {
        cardRects.clear();
        int per = PER_ROW * ROWS;
        int from = page * per;
        for (int i = 0; i < per && from + i < shown.size(); i++) {
            Operator op = shown.get(from + i);
            float cx = GRID_X + (i % PER_ROW) * (CARD_W + GAP);
            float cy = GRID_Y + (i / PER_ROW) * (CARD_H + GAP);
            cardRects.add(new float[]{cx, cy, CARD_W, CARD_H});

            g.noFill();
            g.stroke(255, 255, 255, 40);
            g.strokeWeight(1f);
            g.rect(d.x(cx), d.y(cy), d.w(CARD_W), d.h(CARD_H));
            g.noStroke();

            PImage img = load(op.portrait());
            if (img != null) {
                // 等比缩放后居中（素材尺寸不统一：75×150 与 180×360 混用）
                float sc = Math.min(d.w(CARD_W) / (float) img.width,
                        d.h(CARD_H) / (float) img.height);
                float w = img.width * sc;
                float h = img.height * sc;
                g.imageMode(PConstants.CORNER);
                g.image(img, d.x(cx) + (d.w(CARD_W) - w) / 2f,
                        d.y(cy) + (d.h(CARD_H) - h) / 2f, w, h);
            }

            // 左上角职业图标
            PImage cls = load("data/imgs/Class/" + op.opClass() + ".png");
            if (cls != null) {
                g.imageMode(PConstants.CORNER);
                g.image(cls, d.x(cx + 4f), d.y(cy + 4f), d.w(28f), d.h(28f));
            }
        }
    }

    /** 右侧翻页。 */
    private void drawPager(float mx, float my) {
        float x = PAGER_X;
        float y = GRID_Y + 40f;
        drawArrow(x, y, true);
        fonts.apply(g, Fonts.Role.MONO, 12f);
        g.textAlign(PConstants.CENTER, PConstants.TOP);
        g.fill(UITheme.MENU_TEXT);
        fonts.text(g, (page + 1) + " / " + pageCount(), d.x(x + 18f), d.y(y + 40f));
        drawArrow(x, y + 70f, false);
    }

    private void drawArrow(float x, float y, boolean up) {
        g.noStroke();
        g.fill(255, 255, 255, 160);
        g.beginShape();
        if (up) {
            g.vertex(d.x(x), d.y(y + 22f));
            g.vertex(d.x(x + 18f), d.y(y + 22f));
            g.vertex(d.x(x + 9f), d.y(y + 8f));
        } else {
            g.vertex(d.x(x), d.y(y + 8f));
            g.vertex(d.x(x + 18f), d.y(y + 8f));
            g.vertex(d.x(x + 9f), d.y(y + 22f));
        }
        g.endShape(PConstants.CLOSE);
    }

    /** 左半屏详情浮层。 */
    private void drawDetail(float mx, float my) {
        // 取局部副本：同一帧里 detail 可能被输入逻辑置空
        Operator op = detail;
        if (op == null) {
            return;
        }
        // 立绘按**窗口像素高**铺满（不再经过设计坐标换算，全屏时必然放大）
        PImage img = load(op.illustration());
        float artPxH = g.height;
        float artPxW = artPxH;
        if (img != null) {
            float sc = Math.min(artPxH / (float) img.height, artPxW / (float) img.width);
            artPxW = img.width * sc;
            artPxH = img.height * sc;
        }
        float panelPx = artPxW + d.w(420f);   // 右边收窄 100
        detailPanelW = d.toDesignX(panelPx);

        g.noStroke();
        g.fill(128f, 128f, 128f, 0.75f * 255f);
        g.rect(0, 0, panelPx, g.height);

        if (img != null) {
            g.imageMode(PConstants.CORNER);
            g.image(img, 0, g.height - artPxH, artPxW, artPxH);
        }

        // 右上角关闭按钮 ×
        float bx = detailPanelW - 52f;
        g.stroke(235f, 235f, 238f, 220f);
        g.strokeWeight(3f);
        g.line(d.x(bx - 10f), d.y(26f), d.x(bx + 10f), d.y(46f));
        g.line(d.x(bx + 10f), d.y(26f), d.x(bx - 10f), d.y(46f));
        g.noStroke();

        // 右侧 7 项数值
        float sx = d.toDesignX(artPxW + d.w(30f)) - 50f;
        float sy = 180f;
        fonts.apply(g, Fonts.Role.COMMAND, 26f);
        g.textAlign(PConstants.LEFT, PConstants.TOP);
        g.fill(UITheme.MENU_TEXT);
        fonts.text(g, op.name(), d.x(sx), d.y(sy - 50f));

        String atkIcon = op.damageType() == DamageType.ARTS ? "法术攻击力" : "攻击力";
        String[][] rows = {
                {"生命值", String.valueOf(op.hp())},
                {atkIcon, String.valueOf(op.atk())},
                {"防御力", String.valueOf(op.def())},
                {"法抗", String.valueOf(op.res())},
                {"阻挡数", String.valueOf(op.block())},
                {"部署费用", String.valueOf(op.cost())},
                {"再部署时间", op.redeployTime() + "s"},
        };
        float y = sy;
        for (String[] row : rows) {
            PImage icon = load(com.yestercar.common.util.AssetPaths.attributeIcon(row[0]));
            if (icon != null) {
                g.imageMode(PConstants.CORNER);
                g.image(icon, d.x(sx), d.y(y), d.w(34f), d.h(34f));
            }
            // 图标 → 中文说明 → 数值
            fonts.apply(g, Fonts.Role.MONO, 22f);
            g.textAlign(PConstants.LEFT, PConstants.CENTER);
            g.fill(UITheme.MENU_TEXT, 0.85f * 255f);
            fonts.text(g, row[0], d.x(sx + 46f), d.y(y + 17f));
            g.fill(UITheme.MENU_TEXT);
            fonts.text(g, row[1], d.x(sx + 230f), d.y(y + 17f));
            y += 56f;
        }

        // 特性说明：小标题用特性的名字（不写“特性”二字），数据来自 data/traits.json
        String traitName = com.yestercar.common.model.TraitTable.nameOf(op.id());
        String desc = com.yestercar.common.model.TraitTable.descriptionOf(op.id());
        if (desc != null && traitName != null) {
            y += 6f;
            fonts.apply(g, Fonts.Role.COMMAND, 20f);
            g.textAlign(PConstants.LEFT, PConstants.TOP);
            g.fill(UITheme.MENU_TEXT, 0.7f * 255f);
            fonts.text(g, traitName, d.x(sx), d.y(y));
            y += 38f;
            fonts.apply(g, Fonts.Role.SANS, 18f);
            g.fill(UITheme.MENU_TEXT, 0.88f * 255f);
            float maxW = d.w(330f);
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < desc.length(); i++) {
                char ch = desc.charAt(i);
                if (line.length() > 0 && g.textWidth(line.toString() + ch) > maxW) {
                    fonts.text(g, line.toString(), d.x(sx), d.y(y));
                    y += 30f;
                    line.setLength(0);
                }
                line.append(ch);
            }
            if (line.length() > 0) {
                fonts.text(g, line.toString(), d.x(sx), d.y(y));
            }
        }
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /**
     * @param mx 鼠标 x
     * @param my 鼠标 y
     * @return 是否消费了这次点击
     */
    boolean mousePressed(float mx, float my) {
        float dx = d.toDesignX(mx);
        float dy = d.toDesignY(my);

        // 详情浮层：点浮层以外关闭
        if (detail != null) {
            float bx = detailPanelW - 52f;
            boolean onClose = dx >= bx - 16f && dx <= bx + 16f && dy >= 20f && dy <= 52f;
            if (onClose || dx >= detailPanelW) {
                detail = null;
                return true;
            }
            return true;
        }

        // 职业筛选
        float total = CLASSES.length * CLASS_W + (CLASSES.length - 1) * CLASS_GAP;
        float x0 = d.toDesignX((g.width - d.w(total)) / 2f);
        for (int i = 0; i < CLASSES.length; i++) {
            float x = x0 + i * (CLASS_W + CLASS_GAP);
            if (dx >= x && dx <= x + CLASS_W && dy >= CLASS_Y && dy <= CLASS_Y + CLASS_H) {
                String cls = CLASSES[i];
                filterClass = cls.equals(filterClass) ? null : cls;
                applyFilter();
                return true;
            }
        }

        // 翻页：上箭头 / 下箭头
        float ax = PAGER_X;
        if (dx >= ax && dx <= ax + 18f) {
            if (dy >= GRID_Y + 40f && dy <= GRID_Y + 64f) {
                page = Math.max(0, page - 1);
                return true;
            }
            if (dy >= GRID_Y + 110f && dy <= GRID_Y + 134f) {
                page = Math.min(pageCount() - 1, page + 1);
                return true;
            }
        }

        // 卡片
        for (int i = 0; i < cardRects.size(); i++) {
            float[] r = cardRects.get(i);
            if (dx >= r[0] && dx <= r[0] + r[2] && dy >= r[1] && dy <= r[1] + r[3]) {
                int idx = page * PER_ROW * ROWS + i;
                if (idx < shown.size()) {
                    detail = shown.get(idx);
                }
                return true;
            }
        }
        return false;
    }

    void mouseReleased(float mx, float my) {
        // 全部逻辑都在 mousePressed：Released 阶段画布坐标可能已经变化，
        // 而且之前的实现放在这里从没被调用到（用户报告"点不动"）。
    }

    /**
     * @param path 相对工程根的图片路径
     * @return 图片；读不到返回 {@code null}
     */
    private PImage load(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (images.containsKey(path)) {
            return images.get(path);
        }
        PImage img = g.loadImage(path);
        if (img == null) {
            System.out.println("[OperatorScreen] 读不到: " + path);
        }
        images.put(path, img);
        return img;
    }
}
