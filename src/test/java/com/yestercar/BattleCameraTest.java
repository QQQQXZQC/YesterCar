package com.yestercar;

import com.yestercar.runtime.view.BattleGridRenderer;
import com.yestercar.runtime.view.Camera;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 战斗界面的透视相机。
 *
 * <p>投影数学的 bug（远近平反、拾取偏格、透视消失）在画面上往往"看起来差不多"，
 * 靠肉眼很难判定。所以用断言把几条硬性质锁住。
 */
@DisplayName("战斗相机（透视投影）")
class BattleCameraTest {

    private static final float EPS = 0.01f;

    private static Camera camera() {
        Camera cam = new Camera(1280, 720);
        cam.frameMap(11, 8);
        return cam;
    }

    // ------------------------------------------------------------------
    // 官方参数
    // ------------------------------------------------------------------

    @Test
    @DisplayName("默认俯角与视场角是官方值 30° / 40°")
    void 官方参数正确() {
        assertEquals(30f, Camera.OFFICIAL_PITCH_DEG, EPS, "俯角应为 30°");
        assertEquals(40f, Camera.OFFICIAL_FOV_Y_DEG, EPS, "垂直视场角应为 40°");
        assertEquals(30f, camera().pitchDegrees(), EPS, "相机的实际俯角应为 30°");
    }

    // ------------------------------------------------------------------
    // 透视的本质：近大远小
    // ------------------------------------------------------------------

    @Test
    @DisplayName("近大远小：row 越大越靠近相机，投影越大")
    void 近大远小() {
        Camera cam = camera();
        float[] out = new float[4];

        // 逐行投影，格宽必须单调递增（row 越大越近）
        float[] cellPx = new float[8];
        for (int row = 0; row < 8; row++) {
            cam.project(5f, row, 0f, out);
            cellPx[row] = out[2];
            if (row > 0) {
                assertTrue(cellPx[row] > cellPx[row - 1],
                        "行号每+1 应更靠近相机、投影更大；第 " + row + " 行反了："
                                + cellPx[row] + " vs " + cellPx[row - 1]);
            }
        }

        float farScale = cellPx[0];    // row 0 = 最远
        float nearScale = cellPx[7];   // row 7 = 最近
        assertTrue(nearScale > farScale, "近处的每格像素数应大于远处");
        // 30° 俯角下这个差距应当明显，否则透视感不足
        assertTrue(nearScale > farScale * 1.15f,
                "近远格宽差距太小，透视感不足：近=" + nearScale + " 远=" + farScale);
    }

    @Test
    @DisplayName("row 越小越远，屏幕 y 越大（相机在南侧）")
    void 远处在屏幕上方() {
        Camera cam = camera();
        float[] out = new float[4];

        cam.project(5f, 0f, 0f, out);
        float farY = out[1];
        cam.project(5f, 7f, 0f, out);
        float nearY = out[1];

        assertTrue(farY < nearY,
                "远处（row=0）应画在屏幕更上方，实际 farY=" + farY + " nearY=" + nearY);
    }

    @Test
    @DisplayName("「近处平、远处厚」：侧面占格宽的比例随距离增大")
    void 近薄远厚() {
        Camera cam = camera();
        float[] top = new float[4];
        float[] bottom = new float[4];
        float[] centre = new float[4];

        float farRatio = 0f;
        float nearRatio = 0f;
        float previous = -1f;

        for (int row = 0; row < 8; row++) {
            cam.project(5f, row + 0.5f, BattleGridRenderer.HIGH_RISE, top);
            cam.project(5f, row + 0.5f, 0f, bottom);
            cam.project(5f, row, 0f, centre);

            float sidePx = Math.abs(bottom[1] - top[1]);
            float cellPx = centre[2];
            float ratio = sidePx / cellPx;

            // 越远（row 越小）占比越大
            if (row > 0) {
                assertTrue(ratio < previous,
                        "row 变大应更近、侧面占比应更小；第 " + row + " 行反了："
                                + ratio + " vs " + previous);
            }
            previous = ratio;
            if (row == 0) {
                farRatio = ratio;
            }
            if (row == 7) {
                nearRatio = ratio;
            }
        }

        // 这就是需求要的「近处平放、远处显示厚度」
        assertTrue(farRatio > nearRatio * 1.5f,
                "侧面占比的变化应足够明显：远=" + farRatio + " 近=" + nearRatio);
        assertTrue(nearRatio < 0.35f, "近处侧面应只占很小比例（接近平铺），实际 " + nearRatio);
    }

    // ------------------------------------------------------------------
    // 投影 / 反投影自洽
    // ------------------------------------------------------------------

    @Test
    @DisplayName("投影与反投影互为逆运算")
    void 投影反投影可逆() {
        Camera cam = camera();
        float[] screen = new float[4];
        float[] world = new float[2];

        for (float x : new float[]{0f, 3.5f, 7f, 10f}) {
            for (float y : new float[]{0.5f, 3f, 6.5f}) {
                cam.project(x, y, 0f, screen);
                assertTrue(cam.unproject(screen[0], screen[1], 0f, world),
                        "反投影应成功（点应在相机前方）");
                assertEquals(x, world[0], 0.05f, "反投影 x 不一致");
                assertEquals(y, world[1], 0.05f, "反投影 y 不一致");
            }
        }
    }

    @Test
    @DisplayName("反投影到高台高度也自洽")
    void 高处反投影可逆() {
        Camera cam = camera();
        float[] screen = new float[4];
        float[] world = new float[2];

        cam.project(4f, 5f, BattleGridRenderer.HIGH_RISE, screen);
        assertTrue(cam.unproject(screen[0], screen[1],
                BattleGridRenderer.HIGH_RISE, world));
        assertEquals(4f, world[0], 0.05f);
        assertEquals(5f, world[1], 0.05f);
    }

    @Test
    @DisplayName("相机后方的点不会被反投影出来")
    void 相机后方不可反投影() {
        Camera cam = camera();
        // 屏幕上极上方的点，射线朝天空，落不到地面平面上
        assertFalse(cam.unproject(640, -100000, 0f, new float[2]),
                "打向天空的射线不该与地面相交");
    }

    @Test
    @DisplayName("深度恒为正（不会出现除零或负深度）")
    void 深度恒为正() {
        Camera cam = camera();
        for (int row = -5; row < 20; row++) {
            for (int col = -5; col < 20; col++) {
                float d = cam.depthOf(col, row, 0f);
                assertTrue(d > 0f && Float.isFinite(d),
                        "深度应为正有限值：(" + col + "," + row + ") → " + d);
            }
        }
    }

    // ------------------------------------------------------------------
    // 相机控制
    // ------------------------------------------------------------------

    @Test
    @DisplayName("frameMap 让地图装进视口并居中")
    void 自动取景正确() {
        Camera cam = new Camera(1280, 720);
        cam.frameMap(11, 8);

        float[] out = new float[4];
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        for (int ci = 0; ci <= 1; ci++) {
            for (int ri = 0; ri <= 1; ri++) {
                for (float z : new float[]{0f, BattleGridRenderer.HIGH_RISE}) {
                    // 11×8 地图：格 (col,row) 占 [col,col+1] × [row,row+1]，
                    // 所以整张图的范围是 [0,11] × [0,8]（不是 -0.5..10.5）
                    cam.project(ci == 0 ? 0f : 11f,
                            ri == 0 ? 0f : 8f, z, out);
                    minX = Math.min(minX, out[0]);
                    maxX = Math.max(maxX, out[0]);
                    minY = Math.min(minY, out[1]);
                    maxY = Math.max(maxY, out[1]);
                }
            }
        }

        assertTrue(minX >= -1f && maxX <= 1281f, "地图横向不应超出视口：" + minX + ".." + maxX);
        assertTrue(minY >= -1f && maxY <= 721f, "地图纵向不应超出视口：" + minY + ".." + maxY);

        // 居中：包围盒中心接近视口中心
        float cx = (minX + maxX) / 2f;
        float cy = (minY + maxY) / 2f;
        assertEquals(640f, cx, 3f, "横向应居中");
        assertEquals(360f, cy, 3f, "纵向应居中");

        // 「取景够满」不能看包围盒宽度 —— 透视下地图是**梯形**，
        // 包围盒左右两端是"远角"，看着窄；视觉上真正撑满画面的是**近边**。
        // 所以按近边量：以 30° 俯角看，近边约占包围盒宽的 1.19 倍。
        //
        // 另外要注意几何上限：11×8 地图的投影长宽比约 1.69，而视口是 1.78，
        // 受"高"约束时宽度最多只能占约 83%。所以阈值取 0.70 是合理的。
        float[] nearL = new float[4];
        float[] nearR = new float[4];
        cam.project(0f, 8f, 0f, nearL);
        cam.project(11f, 8f, 0f, nearR);
        float nearWidth = Math.abs(nearR[0] - nearL[0]);
        assertTrue(nearWidth / 1280f > 0.70f,
                "近边应撑满画面 70% 以上，实际 " + (nearWidth / 1280f));

        // 纵向高度应接近可用高度（这才是真正的约束方向）
        assertTrue((maxY - minY) / 720f > 0.65f,
                "纵向应基本撑满，实际 " + ((maxY - minY) / 720f));
    }

    @Test
    @DisplayName("改距离会改变缩放，且被夹在上下限内")
    void 距离有上下限() {
        Camera cam = camera();
        float before = cam.dist();

        cam.zoomBy(-100f);
        assertTrue(cam.dist() >= Camera.MIN_DIST - EPS, "距离不应低于下限");
        cam.zoomBy(1000f);
        assertTrue(cam.dist() <= Camera.MAX_DIST + EPS, "距离不应超过上限");
        cam.zoomBy(-1000f);
        assertTrue(cam.dist() < before, "拉近后距离应变小");
    }

    @Test
    @DisplayName("平移注视点会让地图跟着移动")
    void 平移生效() {
        Camera cam = camera();
        float[] out = new float[4];

        cam.project(5f, 4f, 0f, out);
        float x0 = out[0];
        float y0 = out[1];

        cam.panBy(1f, 0f);
        cam.project(5f, 4f, 0f, out);
        assertTrue(out[0] < x0, "注视点右移，画面上的点应左移");

        cam.panBy(-1f, 1f);
        cam.project(5f, 4f, 0f, out);
        assertTrue(out[1] < y0, "注视点往远处移，画面上的点应上移");
    }

    @Test
    @DisplayName("俯角可调，且被夹在合理范围内")
    void 俯角有范围() {
        Camera cam = camera();
        cam.setPitch(5f);
        assertTrue(cam.pitchDegrees() >= 10f, "俯角不应小于 10°");
        cam.setPitch(120f);
        assertTrue(cam.pitchDegrees() <= 89f, "俯角不应大于 89°");
        cam.setPitch(45f);
        assertEquals(45f, cam.pitchDegrees(), EPS);
    }

    @Test
    @DisplayName("改视口尺寸后主点跟着变")
    void 改视口生效() {
        Camera cam = camera();
        cam.setViewport(800, 600);
        float[] out = new float[4];
        cam.project(cam.targetX(), cam.targetY(), 0f, out);
        // 注视点应投影到视口中心附近
        assertEquals(400f, out[0], 5f, "注视点应约在视口横向中心");
    }

    // ------------------------------------------------------------------
    // 地形高度
    // ------------------------------------------------------------------

    @Test
    @DisplayName("地形高度：高台 > 不可通行 > 门 > 地面 > 未放置")
    void 地形高度有序() {
        assertEquals(0f, BattleGridRenderer.riseOf(
                com.yestercar.campaigneditor.model.TileType.EMPTY), EPS);
        assertEquals(BattleGridRenderer.GROUND_RISE, BattleGridRenderer.riseOf(
                com.yestercar.campaigneditor.model.TileType.GROUND), EPS);
        assertEquals(BattleGridRenderer.DOOR_RISE, BattleGridRenderer.riseOf(
                com.yestercar.campaigneditor.model.TileType.RED_DOOR), EPS);
        assertEquals(BattleGridRenderer.DOOR_RISE, BattleGridRenderer.riseOf(
                com.yestercar.campaigneditor.model.TileType.BLUE_DOOR), EPS);
        assertEquals(BattleGridRenderer.IMPASSABLE_RISE, BattleGridRenderer.riseOf(
                com.yestercar.campaigneditor.model.TileType.IMPASSABLE), EPS);
        assertEquals(BattleGridRenderer.HIGH_RISE, BattleGridRenderer.riseOf(
                com.yestercar.campaigneditor.model.TileType.HIGH), EPS);

        assertTrue(BattleGridRenderer.HIGH_RISE > BattleGridRenderer.IMPASSABLE_RISE);
        assertTrue(BattleGridRenderer.IMPASSABLE_RISE > BattleGridRenderer.DOOR_RISE);
        assertTrue(BattleGridRenderer.DOOR_RISE > BattleGridRenderer.GROUND_RISE);
        assertTrue(BattleGridRenderer.GROUND_RISE > 0f, "地面也该有厚度");
    }
}
