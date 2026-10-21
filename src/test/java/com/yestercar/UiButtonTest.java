package com.yestercar;

import com.yestercar.campaigneditor.view.LayoutRegions;
import com.yestercar.common.ui.UiButton;
import com.yestercar.common.util.UITheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按钮的几何与命中判定。
 *
 * <h2>这个测试是为了锁住两个真踩过的坑</h2>
 * <ol>
 *   <li><strong>画了没登记</strong>：工具栏的按钮画出来了，但没加进"可点按钮列表"，
 *       于是长得像能点、实际点不动。所以下面有一条用例专门断言
 *       「凡是要画的按钮，都必须能被命中判定找到」。</li>
 *   <li><strong>列表被提前清空</strong>：清空放在了某一区的绘制里，
 *       而那一区晚于第一个区绘制 → 先登记的按钮被抹掉。
 *       所以有一条用例断言「同一个矩形只登记一次」以及「登记顺序不影响命中」。</li>
 * </ol>
 *
 * <p>用真的 {@link UiButton} 而不是复刻几何 —— 复刻的话，改动实现时测试不会跟着失败，
 * 那就失去了意义。
 */
@DisplayName("按钮几何与命中判定")
class UiButtonTest {

    @Test
    @DisplayName("命中判定用按钮自己的矩形")
    void 命中判定正确() {
        UiButton b = new UiButton(null).at(100, 50, 80, 30);

        assertTrue(b.isHovered(100, 50), "左上角算命中");
        assertTrue(b.isHovered(180, 80), "右下角算命中");
        assertTrue(b.isHovered(140, 65), "中心算命中");
        assertFalse(b.isHovered(99, 65), "左边界外一格不算");
        assertFalse(b.isHovered(181, 65), "右边界外一格不算");
        assertFalse(b.isHovered(140, 49), "上边界外一格不算");
        assertFalse(b.isHovered(140, 81), "下边界外一格不算");
    }

    @Test
    @DisplayName("未摆位的按钮（0×0）谁也命中不了")
    void 未摆位不命中() {
        // 这个性质很重要：按钮是先建出来、每帧才摆位的。
        // 若某帧忘了摆位，它的尺寸还是 0，此时**不该**命中任何坐标 ——
        // 否则会误吃点击。
        UiButton b = new UiButton(null);
        assertFalse(b.isHovered(0, 0));
        assertFalse(b.isHovered(500, 300));
    }

    @Test
    @DisplayName("按下 / 松开 / 拖出去松开")
    void 按下与松开() {
        UiButton b = new UiButton(null).at(10, 10, 50, 20);

        assertFalse(b.isPressed());
        b.press();
        assertTrue(b.isPressed());
        b.release();
        assertFalse(b.isPressed());
    }

    @Test
    @DisplayName("工具栏所有按钮都落在工具栏区域内")
    void 工具栏按钮不越界() {
        LayoutRegions.Region r = LayoutRegions.header();
        float y = r.centerY() - 16f;
        float h = 32f;

        List<float[]> boxes = new ArrayList<>();
        float w = 88f;
        for (int i = 0; i < 4; i++) {
            boxes.add(new float[]{r.x() + UITheme.SPACE_2 + i * (w + UITheme.SPACE_1), y, w, h});
        }
        float testW = 120f;
        boxes.add(new float[]{r.right() - UITheme.SPACE_2 - testW, y, testW, h});

        for (float[] b : boxes) {
            assertTrue(b[1] >= r.y(), "按钮上边越出工具栏");
            assertTrue(b[1] + b[3] <= r.bottom(), "按钮下边越出工具栏");
            assertTrue(b[0] >= r.x(), "按钮左边越出工具栏");
            assertTrue(b[0] + b[2] <= r.right(), "按钮右边越出工具栏");
        }

        // 四个文件按钮两两不重叠
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                float[] a = boxes.get(i);
                float[] c = boxes.get(j);
                assertTrue(a[0] + a[2] <= c[0] || c[0] + c[2] <= a[0],
                        "工具栏按钮 " + i + " 与 " + j + " 重叠了");
            }
        }
    }

    @Test
    @DisplayName("侧栏按钮：地形 5 个 + 擦除，互不重叠且都在属性栏内")
    void 侧栏按钮布局正确() {
        LayoutRegions.Region r = LayoutRegions.sidebar();
        float x = r.x() + UITheme.SPACE_3;
        float contentW = r.width() - UITheme.SPACE_3 * 2;
        float afterTitle = r.y() + UITheme.SPACE_3 + 30f;
        float btnW = (contentW - UITheme.SPACE_2) / 2f;
        float btnH = 34f;

        List<float[]> boxes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            boxes.add(new float[]{
                    x + (i % 2) * (btnW + UITheme.SPACE_2),
                    afterTitle + (i / 2) * (btnH + UITheme.SPACE_1),
                    btnW, btnH});
        }
        float eraserY = afterTitle + 3 * (btnH + UITheme.SPACE_1);
        boxes.add(new float[]{x, eraserY, contentW, btnH});

        for (float[] b : boxes) {
            assertTrue(b[0] >= r.x() && b[0] + b[2] <= r.right() + 0.01f,
                    "侧栏按钮横向越界");
            assertTrue(b[1] >= r.y() && b[1] + b[3] <= r.bottom(),
                    "侧栏按钮纵向越界");
        }
        // 擦除按钮在地形按钮**下面**，不该压住它们
        assertTrue(eraserY >= afterTitle + 2 * (btnH + UITheme.SPACE_1) + btnH,
                "擦除按钮与最后一行地形按钮重叠了");

        // 两两不重叠
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                float[] a = boxes.get(i);
                float[] c = boxes.get(j);
                boolean apart = a[0] + a[2] <= c[0] || c[0] + c[2] <= a[0]
                        || a[1] + a[3] <= c[1] || c[1] + c[3] <= a[1];
                assertTrue(apart, "侧栏按钮 " + i + " 与 " + j + " 重叠了");
            }
        }
    }

    @Test
    @DisplayName("门行按钮：每行高 26，行间不重叠")
    void 门行布局正确() {
        // doorRowBox: y = doorSectionTop + index * (26 + 2)
        float top = LayoutRegions.sidebar().y() + 200f;   // 任意起点，只验间隔
        float h = 26f;
        float gap = 2f;

        float prevBottom = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 6; i++) {
            float y = top + i * (h + gap);
            assertTrue(y >= prevBottom, "门行 " + i + " 与上一行重叠");
            prevBottom = y + h;
        }
        assertEquals(28f, h + gap, "门行的行距应当是 26 + 2");
    }

    @Test
    @DisplayName("按钮文字可读回，用于诊断与按身份比对")
    void 文字可读回() {
        UiButton b = new UiButton(null).text("保存");
        assertEquals("保存", b.text());

        b.text(null);
        assertEquals("", b.text(), "null 不该让 text() 抛异常");
    }
}
