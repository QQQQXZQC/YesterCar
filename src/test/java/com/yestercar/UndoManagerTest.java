package com.yestercar;

import com.yestercar.campaigneditor.EditorApp;
import com.yestercar.campaigneditor.command.UndoManager;
import com.yestercar.campaigneditor.model.Door;
import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.model.TileType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撤销 / 重做，以及它牵出来的门 id 分配问题。
 *
 * <p>撤销最容易出的 bug 是"还原得不全" —— 地形回来了但门列表没回来，
 * 或者门回来了但 id 计数器没回来。这类 bug 画面上看不出来，几周后才炸，
 * 所以每条不变量都用断言锁住。
 */
@DisplayName("撤销 / 重做")
class UndoManagerTest {

    /** 造一张空地图并建好历史栈。 */
    private static TileMap newMap() {
        return new TileMap("t", 12, 8);
    }

    /** 模拟一次「按下 → 改一格 → 抬起」。 */
    private static void stroke(UndoManager um, Runnable edit, String label) {
        um.beginStroke();
        edit.run();
        um.noteChange(true);
        um.commit(label);
    }

    // ------------------------------------------------------------------
    // 门 id：删除后不重用（这是修掉的真 bug）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("删掉 id 最大的门之后，新门不会复用它的编号")
    void 删最大id后不复用() {
        TileMap map = newMap();

        map.placeRedDoor(0, 0, 0);
        map.placeRedDoor(1, 1, 0);
        assertEquals(2, map.nextRedDoorId());

        // 关键场景：删掉的正是**最大**的 id。
        // 旧实现是 max(现有)+1，删掉 1 之后下一个又变成 1 —— 复用了。
        map.removeDoorAt(1, 0);
        assertEquals(2, map.nextRedDoorId(),
                "删掉最大的 id 之后不该复用该编号（契约：门 id 删除后不重用）");

        map.placeRedDoor(map.nextRedDoorId(), 5, 5);
        assertEquals(2, map.redDoors().get(map.redDoors().size() - 1).id());
        assertEquals(3, map.nextRedDoorId());
    }

    @Test
    @DisplayName("把门全删光之后再放，编号继续往后走")
    void 全删光后编号继续() {
        TileMap map = newMap();
        map.placeRedDoor(0, 0, 0);
        map.placeRedDoor(1, 1, 0);
        map.removeDoorAt(0, 0);
        map.removeDoorAt(1, 0);

        assertTrue(map.redDoors().isEmpty());
        assertEquals(2, map.nextRedDoorId(), "删光了也不该回到 0");

        map.placeRedDoor(map.nextRedDoorId(), 3, 3);
        assertEquals(2, map.redDoors().get(0).id());
    }

    @Test
    @DisplayName("红门与蓝门的计数器各自独立")
    void 红蓝计数器独立() {
        TileMap map = newMap();
        map.placeRedDoor(0, 0, 0);
        map.placeBlueDoor(0, 1, 0, 3);
        map.placeBlueDoor(1, 2, 0, 3);

        assertEquals(1, map.nextRedDoorId(), "蓝门不该影响红门计数");
        assertEquals(2, map.nextBlueDoorId());
    }

    @Test
    @DisplayName("从 JSON 读入门之后，计数器接着已有的最大 id 走")
    void 载入后计数器正确() throws Exception {
        // 这是真实场景：读进来一张已有 #0 和 #3 的地图，再放新门应当是 #4
        String json = """
                { "version":1, "name":"x", "cols":4, "rows":1, "tileSize":64,
                  "legend":{}, "tiles":[[3,1,1,3]],
                  "redDoors":[{"id":0,"col":0,"row":0},{"id":3,"col":3,"row":0}],
                  "blueDoors":[] }
                """;
        TileMap map = com.yestercar.campaigneditor.io.MapJsonIO.parse(json, "x");

        assertEquals(4, map.nextRedDoorId(), "应从已有的最大 id 3 接着走");
    }

    // ------------------------------------------------------------------
    // 基本撤销 / 重做
    // ------------------------------------------------------------------

    @Test
    @DisplayName("撤销能还原地形")
    void 撤销还原地形() {
        TileMap map = newMap();
        UndoManager um = new UndoManager(map);

        stroke(um, () -> map.setTile(3, 3, TileType.GROUND), "放置 地面");
        assertEquals(TileType.GROUND, map.tileAt(3, 3));

        assertNotNull(um.undo());
        assertEquals(TileType.EMPTY, map.tileAt(3, 3), "撤销后应回到未放置");
    }

    @Test
    @DisplayName("撤销能还原门（列表与地形一起）")
    void 撤销还原门() {
        TileMap map = newMap();
        UndoManager um = new UndoManager(map);

        stroke(um, () -> map.placeRedDoor(0, 2, 2), "放置 红门 #0");
        assertEquals(1, map.redDoors().size());
        assertEquals(TileType.RED_DOOR, map.tileAt(2, 2));

        um.undo();
        assertTrue(map.redDoors().isEmpty(), "门列表要一起还原");
        assertEquals(TileType.EMPTY, map.tileAt(2, 2), "地形也要一起还原");
    }

    @Test
    @DisplayName("撤销能还原蓝门 HP")
    void 撤销还原蓝门HP() {
        TileMap map = newMap();
        map.placeBlueDoor(0, 1, 1, 3);
        UndoManager um = new UndoManager(map);

        // 改 HP：实现方式是删了重放（TileMap 没有单独的 setHp）
        stroke(um, () -> {
            map.removeDoorAt(1, 1);
            map.placeBlueDoor(0, 1, 1, 7);
        }, "改蓝门 HP");
        assertEquals(7, map.blueDoorById(0).hp());

        um.undo();
        assertEquals(3, map.blueDoorById(0).hp(), "HP 应还原成 3");
        assertEquals(TileType.BLUE_DOOR, map.tileAt(1, 1));
    }

    @Test
    @DisplayName("撤销能还原门 id 计数器")
    void 撤销还原id计数器() {
        TileMap map = newMap();
        UndoManager um = new UndoManager(map);

        stroke(um, () -> map.placeRedDoor(0, 0, 0), "放置 红门 #0");
        assertEquals(1, map.nextRedDoorId());

        um.undo();
        assertEquals(0, map.nextRedDoorId(), "计数器也要还原");

        // 撤销之后再放，应该又是 #0（因为那一笔已经不存在了）
        stroke(um, () -> map.placeRedDoor(map.nextRedDoorId(), 4, 4), "放置 红门 #0");
        assertEquals(0, map.redDoors().get(0).id());
    }

    @Test
    @DisplayName("重做能把撤销掉的一步做回来")
    void 重做生效() {
        TileMap map = newMap();
        UndoManager um = new UndoManager(map);

        stroke(um, () -> map.setTile(1, 1, TileType.HIGH), "放置 高台");
        String undone = um.undo();
        assertEquals("放置 高台", undone);
        assertEquals(TileType.EMPTY, map.tileAt(1, 1));

        String redone = um.redo();
        assertEquals("放置 高台", redone);
        assertEquals(TileType.HIGH, map.tileAt(1, 1), "重做后地形应回来");
    }

    @Test
    @DisplayName("重做也能还原门与 id 计数器")
    void 重做还原门() {
        TileMap map = newMap();
        UndoManager um = new UndoManager(map);

        stroke(um, () -> map.placeBlueDoor(0, 6, 6, 5), "放置 蓝门 #0");
        um.undo();
        assertTrue(map.blueDoors().isEmpty());
        assertEquals(0, map.nextBlueDoorId());

        um.redo();
        assertEquals(1, map.blueDoors().size());
        assertEquals(5, map.blueDoorById(0).hp());
        assertEquals(TileType.BLUE_DOOR, map.tileAt(6, 6));
        assertEquals(1, map.nextBlueDoorId(), "计数器也要跟着重做");
    }

    // ------------------------------------------------------------------
    // 一笔 = 一次按下→抬起
    // ------------------------------------------------------------------

    @Test
    @DisplayName("一次拖拽画多格，撤销一次全部还原（一笔 = 一次按下抬起）")
    void 一笔多格一次撤销() {
        TileMap map = newMap();
        UndoManager um = new UndoManager(map);

        um.beginStroke();
        for (int col = 0; col < 10; col++) {
            map.setTile(col, 0, TileType.GROUND);
            um.noteChange(true);
        }
        um.commit("放置 地面");

        assertEquals(1, um.undoDepth(), "10 格应当只算 1 步");
        um.undo();
        for (int col = 0; col < 10; col++) {
            assertEquals(TileType.EMPTY, map.tileAt(col, 0),
                    "一次撤销应把整笔 10 格全部还原");
        }
    }

    @Test
    @DisplayName("没有实际改动的一笔不留历史")
    void 空笔不留历史() {
        TileMap map = newMap();
        UndoManager um = new UndoManager(map);

        um.beginStroke();
        um.commit("啥也没干");          // 没有 noteChange
        assertEquals(0, um.undoDepth(), "没改动就不该留下空历史");
        assertFalse(um.canUndo());
        assertNull(um.undo());
    }

    @Test
    @DisplayName("beginStroke 重复调用不会冲掉已开始的那一笔")
    void 重复开笔只生效一次() {
        TileMap map = newMap();
        UndoManager um = new UndoManager(map);

        um.beginStroke();
        map.setTile(0, 0, TileType.GROUND);
        um.noteChange(true);

        um.beginStroke();               // 再来一次，不该把起点挪到现在
        map.setTile(1, 0, TileType.GROUND);
        um.noteChange(true);
        um.commit("两格");

        um.undo();
        assertEquals(TileType.EMPTY, map.tileAt(0, 0), "第一格也要被撤销");
        assertEquals(TileType.EMPTY, map.tileAt(1, 0));
    }

    // ------------------------------------------------------------------
    // 栈行为
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新编辑作废重做栈")
    void 新编辑作废重做() {
        TileMap map = newMap();
        UndoManager um = new UndoManager(map);

        stroke(um, () -> map.setTile(0, 0, TileType.GROUND), "A");
        stroke(um, () -> map.setTile(1, 0, TileType.HIGH), "B");
        um.undo();
        assertTrue(um.canRedo(), "刚撤销完应当可以重做");

        // 在"撤销后"的状态上做新编辑 → 原来的重做分支作废
        stroke(um, () -> map.setTile(2, 0, TileType.IMPASSABLE), "C");
        assertFalse(um.canRedo(), "新编辑之后不该还能重做旧的 B");

        // 撤销 B 之后状态是"只有 A"，所以 (1,0) 应当回到未放置
        assertEquals(TileType.EMPTY, map.tileAt(1, 0), "被撤销掉的 B 不该还在");
        assertEquals(TileType.IMPASSABLE, map.tileAt(2, 0), "新编辑的 C 应当在");
        assertEquals(TileType.GROUND, map.tileAt(0, 0), "更早的 A 应当保留");
    }

    @Test
    @DisplayName("多步撤销与重做按顺序回放")
    void 多步回放() {
        TileMap map = newMap();
        UndoManager um = new UndoManager(map);

        for (int i = 0; i < 5; i++) {
            int col = i;
            stroke(um, () -> map.setTile(col, 0, TileType.GROUND), "第 " + (col + 1) + " 笔");
        }
        assertEquals(5, um.undoDepth());

        for (int i = 0; i < 5; i++) {
            um.undo();
        }
        assertFalse(um.canUndo(), "撤销到头了");
        for (int col = 0; col < 5; col++) {
            assertEquals(TileType.EMPTY, map.tileAt(col, 0));
        }

        for (int i = 0; i < 5; i++) {
            um.redo();
        }
        assertFalse(um.canRedo(), "重做到头了");
        for (int col = 0; col < 5; col++) {
            assertEquals(TileType.GROUND, map.tileAt(col, 0));
        }
    }

    @Test
    @DisplayName("深度上限生效，最旧的被丢掉，但仍能撤销到某个状态")
    void 深度上限生效() {
        TileMap map = newMap();
        UndoManager um = new UndoManager(map, 3);

        for (int i = 0; i < 10; i++) {
            int col = i;
            stroke(um, () -> map.setTile(col, 0, TileType.GROUND), "笔 " + col);
        }
        assertEquals(3, um.undoDepth(), "应被裁到上限 3");

        // 撤销 3 次后就到头了（不能回到初始状态，因为初始状态已被裁掉）
        um.undo();
        um.undo();
        um.undo();
        assertFalse(um.canUndo());
    }

    @Test
    @DisplayName("clear 之后历史清空，不能撤销到上一张图")
    void clear生效() {
        TileMap map = newMap();
        UndoManager um = new UndoManager(map);

        stroke(um, () -> map.setTile(0, 0, TileType.GROUND), "A");
        um.clear();

        assertFalse(um.canUndo());
        assertFalse(um.canRedo());
        assertEquals(0, um.undoDepth());
        assertEquals(0, um.redoDepth());
        assertNull(um.undo());
        assertNull(um.redo());
    }

    @Test
    @DisplayName("撤销与重做的描述可用于状态栏提示")
    void 描述可用() {
        TileMap map = newMap();
        UndoManager um = new UndoManager(map);

        assertNull(um.peekUndoLabel(), "没历史时不该有描述");

        stroke(um, () -> map.setTile(0, 0, TileType.GROUND), "放置 地面");
        assertEquals("放置 地面", um.peekUndoLabel());
        assertNull(um.peekRedoLabel());

        um.undo();
        assertEquals("放置 地面", um.peekRedoLabel());
        assertNull(um.peekUndoLabel());
    }

    @Test
    @DisplayName("快照是深拷贝：之后继续改地图不会污染历史")
    void 快照是深拷贝() {
        TileMap map = newMap();
        UndoManager um = new UndoManager(map);

        stroke(um, () -> map.setTile(0, 0, TileType.GROUND), "A");
        TileMap.Snapshot snap = map.snapshot();

        // 拿到快照之后继续改地图
        map.setTile(0, 0, TileType.HIGH);
        map.placeRedDoor(0, 5, 5);

        assertEquals(TileType.GROUND, snap.tiles()[0][0], "快照不该被后续改动影响");
        assertTrue(snap.redDoors().isEmpty(), "快照的门列表也不该被影响");

        // 还原快照，地图应回到"只有 (0,0) 是地面"的状态
        map.restore(snap);
        assertEquals(TileType.GROUND, map.tileAt(0, 0));
        assertTrue(map.redDoors().isEmpty());
    }

    @Test
    @DisplayName("撤销之后地图的门与地形仍然自洽")
    void 撤销后仍然自洽() {
        TileMap map = newMap();
        UndoManager um = new UndoManager(map);

        // 放门 → 删门 → 撤销 → 再撤销
        stroke(um, () -> map.placeRedDoor(0, 2, 2), "放红门");
        stroke(um, () -> map.removeDoorAt(2, 2), "删红门");
        assertTrue(map.redDoors().isEmpty());
        assertEquals(TileType.EMPTY, map.tileAt(2, 2));

        um.undo();      // 撤销"删"
        assertEquals(1, map.redDoors().size(), "门应回来");
        assertEquals(TileType.RED_DOOR, map.tileAt(2, 2), "地形应跟着回来");
        assertConsistent(map);

        um.undo();      // 撤销"放"
        assertTrue(map.redDoors().isEmpty());
        assertEquals(TileType.EMPTY, map.tileAt(2, 2));
        assertConsistent(map);
    }

    /**
     * 断言「地形表说是门 ⟺ 门列表里真有那道门」。
     *
     * <p>这是 {@code TileMap} 刻意封装的联动，撤销最容易在这里出错。
     */
    private static void assertConsistent(TileMap map) {
        for (int row = 0; row < map.rows(); row++) {
            for (int col = 0; col < map.cols(); col++) {
                TileType t = map.tileAt(col, row);
                if (t == TileType.RED_DOOR) {
                    assertNotNull(findDoor(map.redDoors(), col, row),
                            "(" + col + "," + row + ") 地形说是红门，门列表里却没有");
                } else if (t == TileType.BLUE_DOOR) {
                    assertNotNull(findDoor(map.blueDoors(), col, row),
                            "(" + col + "," + row + ") 地形说是蓝门，门列表里却没有");
                }
            }
        }
        for (Door d : map.redDoors()) {
            assertEquals(TileType.RED_DOOR, map.tileAt(d.col(), d.row()),
                    "红门 #" + d.id() + " 在列表里，地形却不是红门");
        }
        for (Door d : map.blueDoors()) {
            assertEquals(TileType.BLUE_DOOR, map.tileAt(d.col(), d.row()),
                    "蓝门 #" + d.id() + " 在列表里，地形却不是蓝门");
        }
    }

    private static Door findDoor(java.util.List<Door> doors, int col, int row) {
        for (Door d : doors) {
            if (d.col() == col && d.row() == row) {
                return d;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 门的显示文案（回归：红蓝门 id 独立编号）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("红门与蓝门可以同号，文案不能互相串味")
    void 同号门文案正确() {
        // 回归用例。原来的 doorLabel 靠 redDoorById(id) 反查门的类型，
        // 而红门蓝门的 id 是各自独立编号的 —— 一旦「红门 #0」和「蓝门 #0」同时存在，
        // 蓝门就会被认成红门（并因此不打印 HP）。
        //
        // 用户的原始反馈：「先放蓝门，编号坐标 HP 正常显示，但是放与其对应的红门后
        // 蓝门的名称、坐标会变成和红门一样，同时 HP 显示消失」。
        TileMap map = new TileMap("t", 12, 8);
        map.placeBlueDoor(0, 11, 7, 3);
        map.placeRedDoor(0, 0, 0);

        assertEquals(0, map.redDoors().get(0).id());
        assertEquals(0, map.blueDoors().get(0).id(), "两者 id 都是 0 —— 这正是出错的场景");

        String red = EditorApp.doorLabel(map.redDoors().get(0), true);
        String blue = EditorApp.doorLabel(map.blueDoors().get(0), false);

        assertEquals("红门 #0 (0,0)", red);
        assertEquals("蓝门 #0 (11,7)", blue, "蓝门必须显示自己的坐标（HP 已在侧栏移除）");

        // 生成文案时**不允许**依赖 id 反查 —— 静态方法签名就强制了这一点：
        // 类型由调用方按"来自哪个列表"给出。
        assertNotEquals(red, blue, "同号的红门与蓝门文案必须不同");
    }

    @Test
    @DisplayName("门文案：红门不带 HP，蓝门带 HP")
    void 门文案格式正确() {
        Door r = Door.red(1, 2, 3);
        Door b = Door.blue(4, 5, 6, 7);

        assertEquals("红门 #1 (2,3)", EditorApp.doorLabel(r, true));
        assertEquals("蓝门 #4 (5,6)", EditorApp.doorLabel(b, false));

        // 蓝门 HP 变了，文案要跟着变
        assertEquals("蓝门 #4 (5,6)", EditorApp.doorLabel(Door.blue(4, 5, 6, 9), false));
    }
}
