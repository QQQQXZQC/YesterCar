package com.yestercar;

import com.yestercar.campaigneditor.view.LayoutRegions;
import com.yestercar.campaigneditor.view.MapViewport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑器布局与视口的纯逻辑测试。
 *
 * <p>这两块的 bug（区域重叠、留缝、点不准格子、缩放漂移）都很难靠肉眼发现 ——
 * 画面上看起来"差不多对"，用起来才发现点偏一格。所以用断言锁住。
 */
@DisplayName("编辑器布局与视口")
class EditorLayoutTest {

    private static final float EPS = 0.01f;

    // ------------------------------------------------------------------
    // 布局几何
    // ------------------------------------------------------------------

    @Test
    @DisplayName("五个功能区都在窗口内，不越界")
    void 功能区不越界() {
        LayoutRegions.Region[] regions = {
                LayoutRegions.header(),
                LayoutRegions.mapArea(),
                LayoutRegions.sidebar(),
                LayoutRegions.wavePanel(),
                LayoutRegions.statusBar()
        };
        for (LayoutRegions.Region r : regions) {
            assertTrue(r.x() >= 0 && r.y() >= 0, "区域左上角越界: " + r);
            assertTrue(r.right() <= LayoutRegions.WINDOW_W + EPS,
                    "区域右边越界: " + r);
            assertTrue(r.bottom() <= LayoutRegions.WINDOW_H + EPS,
                    "区域下边越界: " + r);
            assertTrue(r.width() > 0 && r.height() > 0, "区域尺寸非正: " + r);
        }
    }

    @Test
    @DisplayName("地图区与属性栏横向不重叠")
    void 地图区与属性栏不重叠() {
        LayoutRegions.Region map = LayoutRegions.mapArea();
        LayoutRegions.Region side = LayoutRegions.sidebar();

        // 同一行，所以 y 与高度应当一致
        assertEquals(map.y(), side.y(), EPS, "两者应在同一行");
        assertEquals(map.height(), side.height(), EPS, "两者应等高");

        assertTrue(map.right() <= side.x() + EPS,
                "地图区右边不应越过属性栏左边：map.right=" + map.right() + " side.x=" + side.x());
        assertEquals(LayoutRegions.GAP, side.x() - map.right(), EPS, "两者间距应为 GAP");
    }

    @Test
    @DisplayName("各行纵向依次排列，间距一致且无重叠")
    void 各行纵向不重叠() {
        LayoutRegions.Region header = LayoutRegions.header();
        LayoutRegions.Region map = LayoutRegions.mapArea();
        LayoutRegions.Region wave = LayoutRegions.wavePanel();
        LayoutRegions.Region status = LayoutRegions.statusBar();

        assertEquals(LayoutRegions.GAP, map.y() - header.bottom(), EPS, "工具栏→内容行间距");
        assertEquals(LayoutRegions.GAP, wave.y() - map.bottom(), EPS, "内容行→波次区间距");
        assertEquals(LayoutRegions.GAP, status.y() - wave.bottom(), EPS, "波次区→状态栏间距");

        // 最底一行应当正好贴住下边距
        assertEquals(LayoutRegions.WINDOW_H - LayoutRegions.MARGIN, status.bottom(), EPS,
                "状态栏下边应贴住窗口下边距");
    }

    @Test
    @DisplayName("同一行的四个区左右边距对称")
    void 左右边距对称() {
        LayoutRegions.Region header = LayoutRegions.header();
        LayoutRegions.Region wave = LayoutRegions.wavePanel();
        LayoutRegions.Region status = LayoutRegions.statusBar();

        for (LayoutRegions.Region r : new LayoutRegions.Region[]{header, wave, status}) {
            assertEquals(LayoutRegions.MARGIN, r.x(), EPS, "左边距");
            assertEquals(LayoutRegions.MARGIN, LayoutRegions.WINDOW_W - r.right(), EPS, "右边距");
        }
    }

    @Test
    @DisplayName("地图区容量：容得下示例地图 12×8，但装不下满行")
    void 地图区容量符合预期() {
        int[] fits = LayoutRegions.fitsAt(MapViewport.TILE_SIZE);

        // 上限从 25 下调到 15 之后，列方向已经能 1:1 放下（15 < 18），
        // 但行方向仍然放不下（15 > 9）—— 所以缩放功能依旧必要。
        // ⚠️ 断言读常量而不是写死 25/15：上限已经改过一次，写死的测试会变成假失败。
        int max = com.yestercar.campaigneditor.model.TileMap.MAX_SIZE;
        assertTrue(fits[1] < max,
                "地图区不该能直接放下满行（" + max + " 行），否则缩放功能没必要。实际: "
                        + fits[0] + "×" + fits[1]);
        assertTrue(fits[0] >= 12 && fits[1] >= 8,
                "至少应能放下示例地图 12×8。实际: " + fits[0] + "×" + fits[1]);
    }

    // ------------------------------------------------------------------
    // 视口：坐标换算
    // ------------------------------------------------------------------

    @Test
    @DisplayName("地图坐标与屏幕坐标互为逆变换")
    void 坐标换算可逆() {
        MapViewport vp = new MapViewport(12, 8, LayoutRegions.mapArea());

        float[][] points = {{0, 0}, {100, 50}, {768, 512}, {-30, 200}};
        for (float[] p : points) {
            float[] screen = vp.mapToScreen(p[0], p[1]);
            float[] back = vp.screenToMap(screen[0], screen[1]);
            assertEquals(p[0], back[0], 0.01f, "x 往返不一致");
            assertEquals(p[1], back[1], 0.01f, "y 往返不一致");
        }
    }

    @Test
    @DisplayName("缩放后坐标换算仍然可逆")
    void 缩放后坐标换算可逆() {
        MapViewport vp = new MapViewport(25, 25, LayoutRegions.mapArea());
        vp.zoomAt(600, 400, 3);

        float[] screen = vp.mapToScreen(320, 640);
        float[] back = vp.screenToMap(screen[0], screen[1]);
        assertEquals(320, back[0], 0.01f);
        assertEquals(640, back[1], 0.01f);
    }

    @Test
    @DisplayName("屏幕坐标能正确落到格子上")
    void 屏幕到格子正确() {
        MapViewport vp = new MapViewport(12, 8, LayoutRegions.mapArea());
        vp.resetView();

        // 地图 (0,0) 对应的屏幕点，应当落在格子 (0,0)
        float[] origin = vp.mapToScreen(0, 0);
        int[] cell = vp.screenToCell(origin[0], origin[1]);
        assertEquals(0, cell[0], "左上角应为第 0 列");
        assertEquals(0, cell[1], "左上角应为第 0 行");

        // 格子中心点应当解析回同一格
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 12; col++) {
                float cx = col * MapViewport.TILE_SIZE + MapViewport.TILE_SIZE / 2f;
                float cy = row * MapViewport.TILE_SIZE + MapViewport.TILE_SIZE / 2f;
                float[] s = vp.mapToScreen(cx, cy);
                int[] c = vp.screenToCell(s[0], s[1]);
                assertEquals(col, c[0], "格子中心反解列号不符 (" + col + "," + row + ")");
                assertEquals(row, c[1], "格子中心反解行号不符 (" + col + "," + row + ")");
            }
        }
    }

    // ------------------------------------------------------------------
    // 视口：缩放
    // ------------------------------------------------------------------

    @Test
    @DisplayName("缩放围绕锚点：锚点下的地图点保持不动")
    void 缩放围绕锚点() {
        MapViewport vp = new MapViewport(25, 25, LayoutRegions.mapArea());
        zoomUntilLargerThanArea(vp);

        float anchorX = LayoutRegions.mapArea().centerX();
        float anchorY = LayoutRegions.mapArea().centerY();
        float[] before = vp.screenToMap(anchorX, anchorY);

        vp.zoomAt(anchorX, anchorY, 4);

        float[] after = vp.screenToMap(anchorX, anchorY);
        assertEquals(before[0], after[0], 0.5f, "缩放后锚点对应的地图 x 变了");
        assertEquals(before[1], after[1], 0.5f, "缩放后锚点对应的地图 y 变了");
    }

    /**
     * 把视口放大到「地图明确大于视口」为止。
     *
     * <p>为什么要这一步：地图能完整放下时，视口会<strong>强制居中并禁止拖动</strong>
     * （这是有意设计 —— 小地图拖了只会露白）。所以测锚点吸附与拖动边界之前，
     * 必须先放大到地图超出视口，否则测的是居中逻辑而不是目标逻辑。
     */
    private void zoomUntilLargerThanArea(MapViewport vp) {
        LayoutRegions.Region area = LayoutRegions.mapArea();
        for (int i = 0; i < 40; i++) {
            if (vp.scaledMapWidth() > area.width() && vp.scaledMapHeight() > area.height()) {
                return;
            }
            vp.zoomAt(area.centerX(), area.centerY(), 1);
        }
        throw new AssertionError("无法把地图放大到超出视口，缩放上限可能太小");
    }

    @Test
    @DisplayName("缩放被夹在上下限内")
    void 缩放有上下限() {
        MapViewport vp = new MapViewport(12, 8, LayoutRegions.mapArea());

        vp.zoomAt(500, 400, 100);          // 猛放大
        assertTrue(vp.scale() <= MapViewport.MAX_SCALE + EPS,
                "缩放超过上限: " + vp.scale());

        vp.zoomAt(500, 400, -200);         // 猛缩小
        assertTrue(vp.scale() >= MapViewport.MIN_SCALE - EPS,
                "缩放低于下限: " + vp.scale());
    }

    @Test
    @DisplayName("滚轮步数为 0 时不改变缩放")
    void 零步数不缩放() {
        MapViewport vp = new MapViewport(12, 8, LayoutRegions.mapArea());
        float before = vp.scale();
        assertFalse(vp.zoomAt(500, 400, 0), "0 步应返回 false");
        assertEquals(before, vp.scale(), EPS);
    }

    // ------------------------------------------------------------------
    // 视口：拖动与边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("地图小于区域时居中且拖不动")
    void 小地图居中锁死() {
        // 12×8 @64 = 768×512，地图区约 1192×608，能放下
        MapViewport vp = new MapViewport(12, 8, LayoutRegions.mapArea());
        vp.resetView();

        LayoutRegions.Region area = LayoutRegions.mapArea();
        float expectedX = (area.width() - vp.scaledMapWidth()) / 2f;
        float expectedY = (area.height() - vp.scaledMapHeight()) / 2f;
        assertEquals(expectedX, vp.panX(), 0.5f, "小地图应横向居中");
        assertEquals(expectedY, vp.panY(), 0.5f, "小地图应纵向居中");

        // 试图拖动：应被夹回居中
        vp.panBy(300, 200);
        assertEquals(expectedX, vp.panX(), 0.5f, "小地图不该能横向拖动");
        assertEquals(expectedY, vp.panY(), 0.5f, "小地图不该能纵向拖动");
    }

    @Test
    @DisplayName("大地图放大后可以拖动，但不留白")
    void 大地图拖动有边界() {
        MapViewport vp = new MapViewport(25, 25, LayoutRegions.mapArea());
        zoomUntilLargerThanArea(vp);

        assertTrue(vp.scaledMapWidth() > LayoutRegions.mapArea().width(),
                "放大后地图应宽于区域，才能测试拖动");
        assertTrue(vp.scaledMapHeight() > LayoutRegions.mapArea().height(),
                "放大后地图应高于区域，才能测试纵向拖动");

        // 往右下猛拖：左边不应离开区域左边界（即 pan 不能为正）
        vp.panBy(9999, 9999);
        assertTrue(vp.panX() <= EPS, "拖动后左边界不应留白，panX=" + vp.panX());
        assertTrue(vp.panY() <= EPS, "拖动后上边界不应留白，panY=" + vp.panY());

        // 往左上猛拖：右边不应离开区域右边界
        vp.panBy(-99999, -99999);
        float minPanX = LayoutRegions.mapArea().width() - vp.scaledMapWidth();
        float minPanY = LayoutRegions.mapArea().height() - vp.scaledMapHeight();
        assertEquals(minPanX, vp.panX(), 0.5f, "拖动后右边界不应留白");
        assertEquals(minPanY, vp.panY(), 0.5f, "拖动后下边界不应留白");
    }

    @Test
    @DisplayName("缩放后拖动能精确移动，且到边界就停住")
    void 放大后可自由拖动() {
        MapViewport vp = new MapViewport(25, 25, LayoutRegions.mapArea());
        zoomUntilLargerThanArea(vp);
        // 再放大几档，让两个方向都有足够的可拖动余量
        vp.zoomAt(LayoutRegions.mapArea().centerX(), LayoutRegions.mapArea().centerY(), 8);

        LayoutRegions.Region area = LayoutRegions.mapArea();

        // 拖到左上极端
        dragBy(vp, -(int) vp.scaledMapWidth(), -(int) vp.scaledMapHeight());
        float leftEdgeX = vp.panX();
        float topEdgeY = vp.panY();
        assertEquals(area.width() - vp.scaledMapWidth(), leftEdgeX, 0.5f, "应被夹在左边界");
        assertEquals(area.height() - vp.scaledMapHeight(), topEdgeY, 0.5f, "应被夹在上边界");

        // 拖动量取「到对侧边界余量」的三分之一，保证不会撞到边界 ——
        // 撞到就会被夹住，那样测的就不是「精确移动」而是「夹取」了
        int dragX = (int) ((-leftEdgeX) / 3);
        int dragY = (int) ((-topEdgeY) / 3);
        assertTrue(dragX > 50 && dragY > 50,
                "余量应足够做拖动测试，实际 dx=" + dragX + " dy=" + dragY);

        dragBy(vp, dragX, dragY);
        assertEquals(leftEdgeX + dragX, vp.panX(), 0.5f, "横向平移量应与拖动距离一致");
        assertEquals(topEdgeY + dragY, vp.panY(), 0.5f, "纵向平移量应与拖动距离一致");

        // 再往右下猛拖：应停在边界，不越界
        dragBy(vp, 99999, 99999);
        assertEquals(0f, vp.panX(), 0.5f, "应被夹在右边界");
        assertEquals(0f, vp.panY(), 0.5f, "应被夹在下边界");

        // 拖动之后换算仍自洽
        float[] map = vp.screenToMap(area.centerX(), area.centerY());
        float[] screen = vp.mapToScreen(map[0], map[1]);
        assertEquals(area.centerX(), screen[0], 0.5f, "拖动后换算不自洽");
        assertEquals(area.centerY(), screen[1], 0.5f, "拖动后换算不自洽");
    }

    /** 按真实拖动的方式平移：拆成每步不超过 40px 的多次调用。 */
    private void dragBy(MapViewport vp, int dx, int dy) {
        int remainingX = dx;
        int remainingY = dy;
        while (remainingX != 0 || remainingY != 0) {
            int stepX = Math.max(-40, Math.min(40, remainingX));
            int stepY = Math.max(-40, Math.min(40, remainingY));
            vp.panBy(stepX, stepY);
            remainingX -= stepX;
            remainingY -= stepY;
        }
    }

    @Test
    @DisplayName("复位视图：能放下就不放大，放不下才缩小")
    void 复位视图行为正确() {
        LayoutRegions.Region area = LayoutRegions.mapArea();

        // 12×8 能放下 → 缩放保持 1
        MapViewport small = new MapViewport(12, 8, area);
        small.resetView();
        assertEquals(1f, small.scale(), EPS, "能放下的地图不该被放大或缩小");

        // 超过上限的尺寸放不下 → 缩放小于 1。
        // ⚠️ 读常量 +1，不写死 25 —— 上限已经改过一次（25 → 15）
        int oversize = com.yestercar.campaigneditor.model.TileMap.MAX_SIZE + 1;
        MapViewport big = new MapViewport(oversize, oversize, area);
        big.resetView();
        assertTrue(big.scale() < 1f, "放不下的地图应被缩小，实际 " + big.scale());
        assertTrue(big.scaledMapWidth() <= area.width() + 0.5f, "缩小后应能横放");
        assertTrue(big.scaledMapHeight() <= area.height() + 0.5f, "缩小后应能纵放");
    }

    @Test
    @DisplayName("改地图尺寸不改变当前缩放与平移")
    void 改地图尺寸不跳视野() {
        MapViewport vp = new MapViewport(12, 8, LayoutRegions.mapArea());
        vp.zoomAt(600, 400, 2);
        float scale = vp.scale();
        float panX = vp.panX();

        vp.setMapSize(20, 16);

        assertEquals(scale, vp.scale(), EPS, "改尺寸不该改缩放");
        assertEquals(20, vp.cols());
        assertEquals(16, vp.rows());
        // 平移可能因夹取而变，但不该跳到"重新居中"那种幅度
        assertTrue(Math.abs(vp.panX() - panX) <= vp.scaledMapWidth(),
                "平移不该跳到不合理的值");
    }

    @Test
    @DisplayName("光标判定与地图范围判定正确")
    void 命中判定正确() {
        MapViewport vp = new MapViewport(12, 8, LayoutRegions.mapArea());
        LayoutRegions.Region area = LayoutRegions.mapArea();

        assertTrue(vp.containsPointer(area.centerX(), area.centerY()), "区域内应命中");
        assertFalse(vp.containsPointer(area.x() - 5, area.centerY()), "区域左外不应命中");
        assertFalse(vp.containsPointer(area.centerX(), area.bottom() + 5), "区域下外不应命中");

        assertTrue(vp.isInsideMap(0, 0));
        assertTrue(vp.isInsideMap(11, 7));
        assertFalse(vp.isInsideMap(12, 7), "列越界应返回 false");
        assertFalse(vp.isInsideMap(0, 8), "行越界应返回 false");
        assertFalse(vp.isInsideMap(-1, 0), "负列应返回 false");
    }

    @Test
    @DisplayName("「光标在地图上」用的是格子范围，不是地图区矩形")
    void 地图区留白不算在地图上() {
        // 回归用例。地图是**居中放在地图区画布里**的，12×8 @64 = 768×512，
        // 而地图区是 1200×584 —— 四周有留白。
        //
        // 原来的光标提示判据是「光标在地图区矩形内」，于是**留白上也给手形光标**，
        // 让人以为那里也能放东西。正确判据是「光标下的格子在地图范围内」。
        LayoutRegions.Region area = LayoutRegions.mapArea();
        MapViewport vp = new MapViewport(12, 8, area);
        vp.resetView();

        // 地图投影后在地图区里的矩形
        float[] origin = vp.mapToScreen(0, 0);
        float w = vp.scaledMapWidth();
        float h = vp.scaledMapHeight();

        // 地图内部：应命中
        assertTrue(isOverMap(vp, origin[0] + w / 2f, origin[1] + h / 2f), "地图中心应命中");

        // 地图左侧的留白：在地图区矩形内，但在地图外 → 不该命中
        float gapX = (origin[0] - area.x()) / 2f;
        assertTrue(gapX > 5f, "测试前提：地图左侧应当有留白，实际 " + gapX);
        float probeX = area.x() + gapX;
        assertTrue(vp.containsPointer(probeX, origin[1] + h / 2f),
                "测试前提：该点应在地图区矩形内");
        assertFalse(isOverMap(vp, probeX, origin[1] + h / 2f),
                "地图左侧留白不该算在地图上");

        // 地图下方的留白：同理
        float gapY = (origin[1] - area.y()) / 2f;
        assertTrue(gapY > 5f, "测试前提：地图上方应当有留白，实际 " + gapY);
        float probeY = area.y() + gapY;
        assertTrue(vp.containsPointer(origin[0] + w / 2f, probeY),
                "测试前提：该点应在地图区矩形内");
        assertFalse(isOverMap(vp, origin[0] + w / 2f, probeY),
                "地图上方留白不该算在地图上");
    }

    /**
     * 复刻 {@code EditorApp.isOverMap()} 的判据。
     *
     * <p>那边是实例方法且依赖 PApplet 的 {@code mouseX/mouseY}，没法直接调；
     * 但判据本身是纯逻辑，在这里以同样两步复刻即可锁住行为。
     *
     * @param vp   视口
     * @param screenX 屏幕 x
     * @param screenY 屏幕 y
     * @return 光标是否落在地图的某一格上
     */
    private static boolean isOverMap(MapViewport vp, float screenX, float screenY) {
        if (!vp.containsPointer(screenX, screenY)) {
            return false;
        }
        int[] cell = vp.screenToCell(screenX, screenY);
        return vp.isInsideMap(cell[0], cell[1]);
    }
}
