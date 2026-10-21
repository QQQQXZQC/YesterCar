package com.yestercar;

import com.yestercar.campaigneditor.model.EditMode;
import com.yestercar.campaigneditor.model.TileType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「当前在放什么」的状态规则。
 *
 * <h2>这个类为什么存在</h2>
 * 原来「选中的地形」与「擦除开关」是两个独立 boolean，
 * 于是**两个按钮会同时高亮**，用户以为两个都要按，实际只有擦除生效（实测踩过）。
 * 收成一个类之后互斥是结构上的保证 —— 下面这些断言就是在锁这一点。
 */
@DisplayName("编辑模式（工具与擦除互斥）")
class EditModeTest {

    private static final List<TileType> TOOLS = List.of(
            TileType.GROUND, TileType.HIGH, TileType.IMPASSABLE,
            TileType.RED_DOOR, TileType.BLUE_DOOR);

    private static EditMode mode() {
        return new EditMode(TOOLS);
    }

    @Test
    @DisplayName("初始状态：第一个工具，不是擦除")
    void 初始状态() {
        EditMode m = mode();
        assertFalse(m.isEraser());
        assertEquals(TileType.GROUND, m.tool());
        assertEquals(0, m.toolIndex());
        assertEquals(TileType.GROUND, m.placeTarget());
    }

    @Test
    @DisplayName("选了工具就不是擦除（互斥）")
    void 选工具后不是擦除() {
        EditMode m = mode();

        m.selectEraser();
        assertTrue(m.isEraser());

        m.selectTool(1);
        assertFalse(m.isEraser(), "选了工具之后擦除必须自动关掉");
        assertEquals(TileType.HIGH, m.tool());
        assertEquals(TileType.HIGH, m.placeTarget());
    }

    @Test
    @DisplayName("切到擦除后，不再有任何工具被选中")
    void 切擦除后没有工具() {
        EditMode m = mode();
        m.selectTool(2);
        assertEquals(TileType.IMPASSABLE, m.tool());

        m.selectEraser();
        assertTrue(m.isEraser());
        assertNull(m.tool(), "擦除状态下不该还有「选中的工具」");
        assertEquals(-1, m.toolIndex(), "擦除状态下不该有工具下标 —— 界面靠它决定高亮谁");
        assertNull(m.placeTarget(), "左键应当擦除");
    }

    @Test
    @DisplayName("开关擦除：开→关，关→开")
    void 开关擦除() {
        EditMode m = mode();

        assertTrue(m.toggleEraser(), "第一次开应当变成擦除");
        assertTrue(m.isEraser());

        assertFalse(m.toggleEraser(), "第二次开应当关掉");
        assertFalse(m.isEraser(), "关掉之后不该还是擦除");
        assertNotNull(m.tool(), "关掉之后应当有一个工具可用");

        assertTrue(m.toggleEraser());
        assertTrue(m.isEraser());
    }

    @Test
    @DisplayName("同一时刻只有一个按钮该高亮")
    void 只有一个高亮() {
        // 这是那个 bug 的核心断言：以前两个 boolean 会同时为真
        EditMode m = mode();

        for (int i = 0; i < TOOLS.size(); i++) {
            m.selectTool(i);
            boolean toolLit = m.toolIndex() == i;
            boolean eraserLit = m.isEraser();
            assertTrue(toolLit, "选中工具 " + i + " 后它应当高亮");
            assertFalse(eraserLit, "选中工具 " + i + " 后擦除不该也高亮");
        }

        m.selectEraser();
        assertTrue(m.isEraser());
        assertEquals(-1, m.toolIndex(), "擦除时不该有任何工具高亮");
    }

    @Test
    @DisplayName("下标越界不会改变现状")
    void 下标越界被忽略() {
        EditMode m = mode();
        m.selectTool(3);

        m.selectTool(-1);
        m.selectTool(99);
        assertEquals(TileType.RED_DOOR, m.tool(), "越界下标应当被忽略，而不是抛异常或重置");
    }

    @Test
    @DisplayName("放什么与状态栏文案一致")
    void 描述与实际一致() {
        EditMode m = mode();

        m.selectTool(0);
        assertEquals("放置 地面", m.describe());
        assertEquals(TileType.GROUND, m.placeTarget());

        m.selectEraser();
        assertEquals("擦除", m.describe());
        assertNull(m.placeTarget(), "文案说擦除，左键就必须真的擦除");
    }

    @Test
    @DisplayName("工具列表为空是编程错误，应当直接拒绝")
    void 空工具列表被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new EditMode(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new EditMode(null));
    }

    @Test
    @DisplayName("工具列表不可被外部改动")
    void 工具列表不可变() {
        EditMode m = mode();
        assertThrows(UnsupportedOperationException.class,
                () -> m.tools().add(TileType.HIGH));
    }
}
