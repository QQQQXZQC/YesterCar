package com.yestercar.campaigneditor.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 编辑器布局在**不同窗口尺寸**下的自洽性。
 *
 * <p>背景：窗口改成可缩放之后，布局不能再依赖写死的 1600×960。
 * 这些用例钉住三件事：
 * <ol>
 *   <li>各功能区不重叠、不越界；</li>
 *   <li>最小尺寸下（1200×760）地图区仍有正面积；</li>
 *   <li>最大化后（1920×1080）地图区变大，而工具栏/侧栏宽度不变。</li>
 * </ol>
 */
class LayoutRegionsTest {

    @AfterEach
    void restore() {
        LayoutRegions.setWindowSize(1600, 960);
    }

    private static void assertInsideAndDisjoint(int w, int h) {
        LayoutRegions.setWindowSize(w, h);

        LayoutRegions.Region header = LayoutRegions.header();
        LayoutRegions.Region map = LayoutRegions.mapArea();
        LayoutRegions.Region side = LayoutRegions.sidebar();
        LayoutRegions.Region wave = LayoutRegions.wavePanel();
        LayoutRegions.Region status = LayoutRegions.statusBar();

        for (LayoutRegions.Region r : new LayoutRegions.Region[]{header, map, side, wave, status}) {
            assertTrue(r.x() >= 0, "区域左边界不得为负：" + r);
            assertTrue(r.y() >= 0, "区域上边界不得为负：" + r);
            assertTrue(r.right() <= w + 0.5f, "区域右边界越界：" + r + " 窗口宽=" + w);
            assertTrue(r.bottom() <= h + 0.5f, "区域下边界越界：" + r + " 窗口高=" + h);
            assertTrue(r.width() > 0, "区域宽必须为正：" + r);
            assertTrue(r.height() > 0, "区域高必须为正：" + r);
        }

        // 地图区与属性栏同一行且不重叠
        assertFalse(overlap(map, side), "地图区与属性栏重叠：" + map + " / " + side);
        // 各行的纵向顺序：工具栏 → 内容行 → 波次区 → 状态栏
        assertTrue(header.bottom() <= map.y(), "工具栏应在地图区之上");
        assertTrue(map.bottom() <= wave.y() + 0.5f, "内容行应在波次区之上");
        assertTrue(wave.bottom() <= status.y() + 0.5f, "波次区应在状态栏之上");
    }

    private static boolean overlap(LayoutRegions.Region a, LayoutRegions.Region b) {
        return a.x() < b.right() && b.x() < a.right()
                && a.y() < b.bottom() && b.y() < a.bottom();
    }

    @Test
    @DisplayName("默认尺寸 1600×960：各区域不越界不重叠")
    void 默认尺寸自洽() {
        assertInsideAndDisjoint(1600, 960);
    }

    @Test
    @DisplayName("最小尺寸 1200×700：地图区仍有正面积")
    void 最小尺寸自洽() {
        assertInsideAndDisjoint(LayoutRegions.MIN_WINDOW_W, LayoutRegions.MIN_WINDOW_H);
        assertTrue(LayoutRegions.mapArea().width() > 300f,
                "最小尺寸下地图区太窄：" + LayoutRegions.mapArea());
        assertTrue(LayoutRegions.mapArea().height() > 250f,
                "最小尺寸下地图区太矮：" + LayoutRegions.mapArea());
    }

    @Test
    @DisplayName("最大化 1920×1080：地图区变大，侧栏宽度不变")
    void 最大化后地图区变大() {
        LayoutRegions.setWindowSize(1600, 960);
        float mapW0 = LayoutRegions.mapArea().width();
        float mapH0 = LayoutRegions.mapArea().height();
        float side0 = LayoutRegions.sidebar().width();

        assertInsideAndDisjoint(1920, 1080);
        assertTrue(LayoutRegions.mapArea().width() > mapW0, "最大化后地图区应更宽");
        assertTrue(LayoutRegions.mapArea().height() > mapH0, "最大化后地图区应更高");
        assertEquals(side0, LayoutRegions.sidebar().width(), 0.01f, "侧栏宽度应保持固定");
    }

    @Test
    @DisplayName("小于最小尺寸时被夹到最小值，不产生负面积")
    void 过小尺寸被夹住() {
        LayoutRegions.setWindowSize(800, 500);
        assertEquals(LayoutRegions.MIN_WINDOW_W, LayoutRegions.WINDOW_W);
        assertEquals(LayoutRegions.MIN_WINDOW_H, LayoutRegions.WINDOW_H);
        assertTrue(LayoutRegions.mapArea().width() > 0f);
        assertTrue(LayoutRegions.mapArea().height() > 0f);
    }
}
