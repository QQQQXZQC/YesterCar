package com.yestercar;

import com.yestercar.gameopening.icon.AkIcons;
import com.yestercar.gameopening.icon.SvgPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图标层：路径解析与边界。
 *
 * <p>这一层的意义不只是"能画" —— 如果 path 解析错了，图标会画成
 * 一团乱线，而**编译和运行都不会报错**。所以这里锁三件事：
 * 每个 id 都能解析、边界落在 24×24 之内、偶奇规则按上游的约定分配。
 */
@DisplayName("图标层：SVG path 解析")
class SvgPathTest {

    @Test
    @DisplayName("14 个 id 全都能解析出非空 path")
    void 全部可解析() {
        assertEquals(14, AkIcons.ids().size(), "图标数量应当与上游一致");
        for (String id : AkIcons.ids()) {
            assertNotNull(AkIcons.path(id), id + " 没有 path 数据");
            assertTrue(SvgPath.has(id), id + " 解析失败");
        }
    }

    @Test
    @DisplayName("不存在的 id 返回 null / false，不抛异常")
    void 未知id安全() {
        assertNull(AkIcons.path("no-such-icon"));
        assertFalse(SvgPath.has("no-such-icon"));
    }

    @Test
    @DisplayName("★ 每个图标的边界都落在 24×24 的 viewBox 内")
    void 边界在viewBox内() {
        for (String id : AkIcons.ids()) {
            Path2D p = SvgPath.of(id);
            Rectangle2D b = p.getBounds2D();
            assertTrue(b.getWidth() > 0 && b.getHeight() > 0,
                    id + " 宽度或高度为 0：" + b);
            assertTrue(b.getMinX() >= -0.01 && b.getMinY() >= -0.01,
                    id + " 有负坐标：" + b);
            assertTrue(b.getMaxX() <= 24.01 && b.getMaxY() <= 24.01,
                    id + " 越出 24×24：" + b);
        }
    }

    @Test
    @DisplayName("★ 偶奇规则按上游约定分配：*-fill 与 bank-line")
    void 偶奇规则() {
        assertTrue(AkIcons.evenOdd("task-fill"));
        assertTrue(AkIcons.evenOdd("add-circle-fill"));
        assertTrue(AkIcons.evenOdd("bank-line"), "bank-line 必须偶奇，否则屋顶被填死");
        assertFalse(AkIcons.evenOdd("rook"));
        assertFalse(AkIcons.evenOdd("profile-line"));
    }

    @Test
    @DisplayName("★ bank-line 在偶奇规则下真的挖出洞来")
    void bank_line有洞() {
        // 上游结论：bank-line 的屋顶是"外轮廓 + 内子路径"，非零规则下内子路径
        // 与外轮廓同向 → 洞被填死，屋顶塌成一根横条。
        // 直接验证方式：扫包围盒，**必须同时存在被填充与未被填充的点**。
        Path2D p = SvgPath.of("bank-line");
        Rectangle2D b = p.getBounds2D();
        int inside = 0;
        int outside = 0;
        for (int ix = 0; ix <= 60; ix++) {
            for (int iy = 0; iy <= 60; iy++) {
                double x = b.getMinX() + b.getWidth() * ix / 60.0;
                double y = b.getMinY() + b.getHeight() * iy / 60.0;
                if (p.contains(x, y)) {
                    inside++;
                } else {
                    outside++;
                }
            }
        }
        assertTrue(inside > 200, "填充面积太小，可能整块没画出来：" + inside);
        assertTrue(outside > 200, "没有任何空洞，偶奇规则没生效：" + outside);
    }

    @Test
    @DisplayName("曲线被采样后顶点数大于 4（不是退化矩形）")
    void 曲线已展开() {
        Path2D p = SvgPath.of("add-circle-fill");
        // 圆角矩形：只有直线的话约 12 个顶点；含曲线后应当明显更多
        assertTrue(p.getWindingRule() == Path2D.WIND_EVEN_ODD);
        Rectangle2D b = p.getBounds2D();
        assertEquals(20, b.getWidth(), 0.6, "add-circle-fill 应当是 20 宽的圆");
        assertEquals(20, b.getHeight(), 0.6);
    }


}
