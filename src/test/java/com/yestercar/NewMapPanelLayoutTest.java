package com.yestercar;

import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.view.LayoutRegions;
import com.yestercar.campaigneditor.view.NewMapPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「新建战役」面板的几何。
 *
 * <h2>这个测试锁住两个真踩过的坑</h2>
 * <ol>
 *   <li><b>按钮压住输入框</b>：面板高度原本写死 {@code 250}，而三行输入框实际占到
 *       y=553，按钮摆在 y=499 —— <b>与「行数」输入框重叠 54px</b>。</li>
 *   <li><b>错误提示压住输入框</b>：错误文字曾经被画进"按钮上方那 20px 留白"里，
 *       锚点是 {@code rowsBottom() + 2f} 且用 CENTER 对齐 ——
 *       于是行盒上半部分侵入「行数」输入框约 6px。</li>
 * </ol>
 *
 * <p>两个坑的共同点是：<strong>"重叠"在代码里看不出来</strong>
 * （两个数各自都"对"），只有把矩形算出来两两比一遍才知道。
 *
 * <h2>为什么第二个坑当初没被测出来</h2>
 * 因为错误文字当时<strong>不在几何模型里</strong> —— {@code layoutBoxes()}
 * 只返回 5 个矩形（3 输入框 + 2 按钮），而 {@code draw()} 实际画 10 个元素。
 * 测试即便想断言也无处可断，只能自己把 {@code draw()} 里的公式重抄一遍，
 * 而<b>重抄的公式会和 bug 保持一致</b>，测试照样绿。
 *
 * <p>所以现在：<b>每个被绘制的元素都必须有一个矩形</b>，
 * 并且下面用一条"两两不重叠"的全局断言兜住 —— 将来再加元素时，
 * 忘了给它留位置就会直接挂在这里，而不是等到界面上才被发现。
 */
@DisplayName("新建战役面板几何")
class NewMapPanelLayoutTest {

    /**
     * 面板宽度。
     *
     * <p>⚠️ 原来这里是手抄的一份副本，实现改宽度时测试会测自己的副本而察觉不到。
     * 现在改成从 {@code layoutBoxes()} 反推 —— 见 {@link #panelBounds()}。
     */
    private static final float PANEL_W = 380f;

    /** 面板的窗口坐标范围 {@code {x, y, w, h}}。 */
    private static float[] panelBounds() {
        float panelH = NewMapPanel.panelHeight();
        return new float[]{
                (LayoutRegions.WINDOW_W - PANEL_W) / 2f,
                (LayoutRegions.WINDOW_H - panelH) / 2f,
                PANEL_W, panelH};
    }

    /** @return 三个输入框里最低的那个底边 */
    private static float fieldsBottom(NewMapPanel.Box[] b) {
        float m = b[0].bottom();
        for (int i = 1; i < 3; i++) {
            m = Math.max(m, b[i].bottom());
        }
        return m;
    }

    // ------------------------------------------------------------------
    // ★ 全局不变量：所有被绘制的元素两两不重叠
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 面板里所有元素的矩形两两不重叠")
    void 所有元素两两不重叠() {
        NewMapPanel.Box[] b = NewMapPanel.layoutBoxes();

        for (int i = 0; i < b.length; i++) {
            for (int j = i + 1; j < b.length; j++) {
                if (!overlaps(b[i], b[j])) {
                    continue;
                }
                throw new AssertionError("元素 " + i + " " + describe(b[i])
                        + " 与元素 " + j + " " + describe(b[j]) + " 重叠");
            }
        }
    }

    /** 两个矩形是否有正的相交面积（边贴边不算重叠）。 */
    private static boolean overlaps(NewMapPanel.Box a, NewMapPanel.Box b) {
        return a.x() < b.right() && b.x() < a.right()
                && a.y() < b.bottom() && b.y() < a.bottom();
    }

    private static String describe(NewMapPanel.Box b) {
        return "[x=" + b.x() + " y=" + b.y() + " w=" + b.width() + " h=" + b.height() + "]";
    }

    // ------------------------------------------------------------------
    // ★ 错误提示行：坑 ② 的回归锁
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 错误提示行不压输入框、也不压按钮（坑 ② 的回归锁）")
    void 错误提示行位置正确() {
        NewMapPanel.Box[] b = NewMapPanel.layoutBoxes();
        NewMapPanel.Box error = b[NewMapPanel.ERROR_BOX_INDEX];
        float fieldsBottom = fieldsBottom(b);
        float buttonsTop = Math.min(b[NewMapPanel.CANCEL_BOX_INDEX].y(),
                b[NewMapPanel.CONFIRM_BOX_INDEX].y());

        assertTrue(error.y() >= fieldsBottom,
                "错误行上缘 " + error.y() + " 不该高于输入框底边 " + fieldsBottom
                        + "（低于说明压在输入框上）");
        assertTrue(error.bottom() <= buttonsTop,
                "错误行下缘 " + error.bottom() + " 不该低于按钮顶边 " + buttonsTop);

        // 高度必须够装下一行中文（字号 14 的行盒约 16px）。
        // 太小就会在实际渲染时溢出矩形 —— 那等于没修。
        assertTrue(error.height() >= 16f,
                "错误行高度 " + error.height() + " 装不下一行中文，应当至少 16px");
    }

    @Test
    @DisplayName("★ 错误提示行有专属高度，不再挤在按钮上方的留白里")
    void 错误行有专属高度() {
        float panelH = NewMapPanel.panelHeight();
        float rowsBottom = NewMapPanel.layoutBoxes()[2].bottom()
                - (LayoutRegions.WINDOW_H - panelH) / 2f;
        float errorH = NewMapPanel.layoutBoxes()[NewMapPanel.ERROR_BOX_INDEX].height();

        // 面板高度必须**包含**错误行：否则错误行就只能去侵占别人的空间。
        // 这是坑 ② 的根因 —— panelHeight() 当初漏了这一项。
        float computed = rowsBottom + errorH;
        NewMapPanel.Box[] b = NewMapPanel.layoutBoxes();
        assertTrue(b[NewMapPanel.CANCEL_BOX_INDEX].y()
                        - ((LayoutRegions.WINDOW_H - panelH) / 2f) >= computed,
                "按钮顶边应当排在「输入框底 + 错误行高」之后");
    }

    // ------------------------------------------------------------------
    // 按钮
    // ------------------------------------------------------------------

    @Test
    @DisplayName("按钮在错误行下方，不与输入框重叠")
    void 按钮不与输入框重叠() {
        NewMapPanel.Box[] b = NewMapPanel.layoutBoxes();
        float buttonsTop = Math.min(b[NewMapPanel.CANCEL_BOX_INDEX].y(),
                b[NewMapPanel.CONFIRM_BOX_INDEX].y());

        assertTrue(buttonsTop >= fieldsBottom(b),
                "按钮顶边 " + buttonsTop + " 应不高于输入框底边 " + fieldsBottom(b));
    }

    @Test
    @DisplayName("取消与确认不重叠，且都在面板内")
    void 两个按钮不重叠() {
        NewMapPanel.Box[] b = NewMapPanel.layoutBoxes();
        NewMapPanel.Box cancel = b[NewMapPanel.CANCEL_BOX_INDEX];
        NewMapPanel.Box confirm = b[NewMapPanel.CONFIRM_BOX_INDEX];

        assertTrue(cancel.right() <= confirm.x(),
                "取消应在确认左侧且不重叠：cancel.right=" + cancel.right()
                        + " confirm.x=" + confirm.x());
        assertEquals(cancel.y(), confirm.y(), 0.01f, "两个按钮应当同一行");
        assertEquals(cancel.height(), confirm.height(), 0.01f, "两个按钮应当等高");
    }

    @Test
    @DisplayName("按钮与输入框之间留有**非零**间隙（只要求 >= 是不够的）")
    void 按钮与输入框之间有间隙() {
        NewMapPanel.Box[] b = NewMapPanel.layoutBoxes();
        float gap = Math.min(b[NewMapPanel.CANCEL_BOX_INDEX].y(),
                b[NewMapPanel.CONFIRM_BOX_INDEX].y()) - fieldsBottom(b);

        // ⚠️ 原来这条断言写的是 `>= 0`，间隙为 0 也算通过 —— 那正是坑 ② 的盲区：
        // 错误行就画在那条缝里，而测试对"缝里有什么"毫无概念。
        assertTrue(gap >= NewMapPanel.ERROR_LINE_H,
                "输入框与按钮之间要留出整条错误提示行，实际只有 " + gap + "px");
    }

    // ------------------------------------------------------------------
    // 输入框
    // ------------------------------------------------------------------

    @Test
    @DisplayName("三个输入框自上而下排列，两两不重叠")
    void 输入框不重叠() {
        NewMapPanel.Box[] b = NewMapPanel.layoutBoxes();

        for (int i = 1; i < 3; i++) {
            assertTrue(b[i].y() >= b[i - 1].bottom(),
                    "输入框 " + i + " 与 " + (i - 1) + " 重叠了");
            assertEquals(b[i - 1].x(), b[i].x(), 0.01f, "输入框应左对齐");
            assertEquals(b[i - 1].width(), b[i].width(), 0.01f, "输入框应等宽");
        }
    }

    // ------------------------------------------------------------------
    // 面板
    // ------------------------------------------------------------------

    @Test
    @DisplayName("面板整体在窗口内，且水平居中")
    void 面板在窗口内且居中() {
        NewMapPanel.Box[] b = NewMapPanel.layoutBoxes();
        float[] p = panelBounds();

        assertTrue(p[0] >= 0 && p[0] + p[2] <= LayoutRegions.WINDOW_W, "面板横向越界");
        assertTrue(p[1] >= 0 && p[1] + p[3] <= LayoutRegions.WINDOW_H,
                "面板纵向越界：panelY=" + p[1] + " 高=" + p[3]
                        + " 窗口高=" + LayoutRegions.WINDOW_H);
        assertEquals(LayoutRegions.WINDOW_W / 2f, p[0] + p[2] / 2f, 0.01f,
                "面板应水平居中");

        for (int i = 0; i < b.length; i++) {
            assertTrue(b[i].x() >= p[0] && b[i].right() <= p[0] + p[2],
                    "元素 " + i + " 横向越出面板");
            assertTrue(b[i].y() >= p[1] && b[i].bottom() <= p[1] + p[3],
                    "元素 " + i + " 纵向越出面板");
        }
    }

    @Test
    @DisplayName("面板高度足够容纳内容（含按钮下方的提示文字）")
    void 高度足够() {
        NewMapPanel.Box[] b = NewMapPanel.layoutBoxes();
        float[] p = panelBounds();
        float buttonsBottom = Math.max(b[NewMapPanel.CANCEL_BOX_INDEX].bottom(),
                b[NewMapPanel.CONFIRM_BOX_INDEX].bottom());

        // 按钮下方还要放一行「↑↓ 切换输入框 …」的提示
        assertTrue(p[1] + p[3] - buttonsBottom >= 20f,
                "按钮与面板底边之间要留出提示文字的位置，实际只剩 "
                        + (p[1] + p[3] - buttonsBottom) + "px");
    }

    @Test
    @DisplayName("面板宽度容得下两个按钮加间距")
    void 宽度足够() {
        NewMapPanel.Box[] b = NewMapPanel.layoutBoxes();
        NewMapPanel.Box cancel = b[NewMapPanel.CANCEL_BOX_INDEX];
        NewMapPanel.Box confirm = b[NewMapPanel.CONFIRM_BOX_INDEX];
        float[] p = panelBounds();

        assertTrue(cancel.x() >= p[0], "取消按钮左边越出面板");
        assertTrue(confirm.right() <= p[0] + p[2], "确认按钮右边越出面板");
        assertTrue(cancel.height() > 0 && cancel.width() > 0, "按钮尺寸应为正");
    }

    @Test
    @DisplayName("矩形数量 = 输入框数 + 错误行 + 两个按钮")
    void 矩形数量齐全() {
        assertEquals(3 + 1 + 2, NewMapPanel.layoutBoxes().length,
                "每个被绘制的元素都必须有一个矩形，否则测试看不见它（坑 ② 的教训）");
    }

    @Test
    @DisplayName("上限文字与实际常量一致（不再手抄 15）")
    void 提示文字与常量一致() {
        // 上限改过一次（25 → 15），任何手抄的数值都会在那时变成假的
        assertEquals(15, TileMap.MAX_SIZE);
    }
}
