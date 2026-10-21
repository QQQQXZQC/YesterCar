package com.yestercar;

import com.yestercar.campaigneditor.EditorApp;
import com.yestercar.campaigneditor.io.MapJsonIO;
import com.yestercar.campaigneditor.model.Door;
import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.model.TileType;
import com.yestercar.common.io.Workspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import processing.data.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据层：{@link TileMap} 与 {@link MapJsonIO}。
 *
 * <p>这些是纯逻辑，出错的后果却很大 —— 地图存坏了、门 id 撞了，
 * 都会在几天后才以"关卡跑不起来"的形式暴露出来。所以用断言锁紧。
 */
@DisplayName("地图数据与 JSON 读写")
class MapJsonIOTest {

    // ------------------------------------------------------------------
    // 新建地图的默认状态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新建地图所有格子都是未放置")
    void 新建地图是空的() {
        TileMap map = new TileMap("t", 12, 8);

        assertEquals(12, map.cols());
        assertEquals(8, map.rows());
        assertEquals(TileMap.DEFAULT_TILE_SIZE, map.tileSize());

        int[] counts = map.countByType();
        assertEquals(12 * 8, counts[TileType.EMPTY.ordinal()], "应全是未放置");
        for (TileType t : TileType.values()) {
            if (t != TileType.EMPTY) {
                assertEquals(0, counts[t.ordinal()], t + " 不该有格子");
            }
        }
        assertFalse(map.hasRedDoor());
        assertFalse(map.hasBlueDoor());
    }

    @Test
    @DisplayName("新建地图尺寸越界会被拒绝")
    void 尺寸越界被拒() {
        // 读常量而不是硬编码 —— 上限以后可能再调（25 → 15 已经调过一次），
        // 硬编码的测试会在那时变成"实现改了、测试没跟上"的假失败
        int max = TileMap.MAX_SIZE;
        int maxRows = TileMap.MAX_ROWS;
        assertThrows(IllegalArgumentException.class, () -> new TileMap("t", 0, 8));
        assertThrows(IllegalArgumentException.class, () -> new TileMap("t", 12, 0));
        assertThrows(IllegalArgumentException.class, () -> new TileMap("t", max + 1, 8));
        assertThrows(IllegalArgumentException.class, () -> new TileMap("t", 12, maxRows + 1));
        // 边界值应当允许
        new TileMap("t", max, maxRows);
        new TileMap("t", 1, 1);
    }

    @Test
    @DisplayName("越界读格子返回未放置而不是抛异常")
    void 越界读不抛异常() {
        TileMap map = new TileMap("t", 12, 8);
        // 渲染循环会画到边界外一圈，所以这里必须安全
        assertEquals(TileType.EMPTY, map.tileAt(-1, 0));
        assertEquals(TileType.EMPTY, map.tileAt(0, -1));
        assertEquals(TileType.EMPTY, map.tileAt(12, 0));
        assertEquals(TileType.EMPTY, map.tileAt(0, 8));
        assertFalse(map.setTile(-1, 0, TileType.GROUND), "越界写应返回 false");
    }

    // ------------------------------------------------------------------
    // 地形码契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("地形码与 legend 名符合契约")
    void 地形码符合契约() {
        assertEquals(-1, TileType.EMPTY.code());
        assertEquals(0, TileType.IMPASSABLE.code());
        assertEquals(1, TileType.GROUND.code());
        assertEquals(2, TileType.HIGH.code());
        assertEquals(3, TileType.RED_DOOR.code());
        assertEquals(4, TileType.BLUE_DOOR.code());

        assertEquals("empty", TileType.EMPTY.legendName());
        assertEquals("impassable", TileType.IMPASSABLE.legendName());
        assertEquals("ground", TileType.GROUND.legendName());
        assertEquals("high", TileType.HIGH.legendName());
        assertEquals("red_door", TileType.RED_DOOR.legendName());
        assertEquals("blue_door", TileType.BLUE_DOOR.legendName());

        // 反查
        for (TileType t : TileType.values()) {
            assertEquals(t, TileType.fromCode(t.code()));
        }
        assertThrows(IllegalArgumentException.class, () -> TileType.fromCode(99));
        assertEquals(TileType.EMPTY, TileType.fromCodeOrDefault(99, TileType.EMPTY));
    }

    @Test
    @DisplayName("静态可走性符合契约：地面/红门/蓝门可走，高台与不可通行不可走")
    void 可走性正确() {
        assertTrue(TileType.GROUND.isWalkable());
        assertTrue(TileType.RED_DOOR.isWalkable());
        assertTrue(TileType.BLUE_DOOR.isWalkable());

        assertFalse(TileType.EMPTY.isWalkable(), "未放置不该能走");
        assertFalse(TileType.IMPASSABLE.isWalkable());
        assertFalse(TileType.HIGH.isWalkable(), "高台敌人走不上去");

        assertTrue(TileType.GROUND.isGroundDeployable());
        assertFalse(TileType.HIGH.isGroundDeployable());
        assertTrue(TileType.HIGH.isHighDeployable());
        assertFalse(TileType.GROUND.isHighDeployable());

        assertTrue(TileType.RED_DOOR.isDoor());
        assertTrue(TileType.BLUE_DOOR.isDoor());
        assertFalse(TileType.GROUND.isDoor());
    }

    // ------------------------------------------------------------------
    // 门与地形的联动
    // ------------------------------------------------------------------

    @Test
    @DisplayName("放门会同时改地形，移除门会清地形")
    void 门与地形联动() {
        TileMap map = new TileMap("t", 12, 8);

        map.placeRedDoor(0, 0, 0);
        assertEquals(TileType.RED_DOOR, map.tileAt(0, 0), "放红门应把该格地形也改掉");
        assertTrue(map.redDoors().size() >= 1, "样例地图至少有一道红门（门数会随编辑变化）");

        map.placeBlueDoor(0, 11, 7, 3);
        assertEquals(TileType.BLUE_DOOR, map.tileAt(11, 7));
        assertEquals(3, map.blueDoors().get(0).hp(), "蓝门生命值应保存");

        Door removed = map.removeDoorAt(0, 0);
        assertNotNull(removed);
        assertEquals(0, removed.id());
        assertEquals(TileType.EMPTY, map.tileAt(0, 0), "移除门后地形应回到未放置");
        assertTrue(map.redDoors().isEmpty());
    }

    @Test
    @DisplayName("同一格放新门会顶掉旧门，不会留下孤儿地形")
    void 同格放门互斥() {
        TileMap map = new TileMap("t", 12, 8);

        map.placeRedDoor(0, 5, 5);
        map.placeBlueDoor(0, 5, 5, 3);      // 同一格换成蓝门

        assertTrue(map.redDoors().isEmpty(), "红门应被顶掉");
        assertTrue(map.blueDoors().size() >= 1, "样例地图至少有一道蓝门");
        assertEquals(TileType.BLUE_DOOR, map.tileAt(5, 5), "地形应是蓝门，不能同时是红门");
    }

    @Test
    @DisplayName("门 id 删除后不重用（避免关卡引用错乱）")
    void 门id不重用() {
        TileMap map = new TileMap("t", 12, 8);

        map.placeRedDoor(0, 0, 0);
        map.placeRedDoor(1, 1, 0);
        assertEquals(2, map.nextRedDoorId(), "下一个应是 2");

        // 删掉 0 号门（**非最大** id）：下一个 id 仍应是 2，而不是复用 0
        map.removeDoorAt(0, 0);
        assertEquals(2, map.nextRedDoorId(),
                "删除门后 id 不该被回收 —— 否则关卡里指向已删除门的引用会悄悄指到新门");

        map.placeRedDoor(map.nextRedDoorId(), 3, 3);
        assertEquals(2, map.redDoors().get(map.redDoors().size() - 1).id());
    }

    @Test
    @DisplayName("删掉 id 最大的门之后也不重用 —— 这条曾经漏测")
    void 门id删最大后不重用() {
        // ⚠️ 上面那条测试删的是 **id=0**。而"取现有最大 id + 1"的旧实现在那种场景下
        // 恰好是对的（剩下的 max 还是 1，下一个自然是 2），所以它**从来没抓到这个 bug**。
        //
        // 真正会炸的是删掉**最大** id：剩下的 max 变成 0，下一个又变回 1 —— 复用了。
        // 撤销功能会天天触发这条路径（放置 → 撤销 → 放置），所以必须补上。
        TileMap map = new TileMap("t", 12, 8);

        map.placeRedDoor(0, 0, 0);
        map.placeRedDoor(1, 1, 0);

        map.removeDoorAt(1, 0);          // 删的正是最大的 id
        assertEquals(2, map.nextRedDoorId(),
                "删掉最大的 id 之后不该复用该编号");

        map.placeRedDoor(map.nextRedDoorId(), 3, 3);
        assertEquals(2, map.redDoors().get(map.redDoors().size() - 1).id(),
                "新门应当是 #2，不是被回收的 #1");

        // 蓝门同理
        map.placeBlueDoor(0, 5, 5, 3);
        map.placeBlueDoor(1, 6, 5, 3);
        map.removeDoorAt(6, 5);
        assertEquals(2, map.nextBlueDoorId(), "蓝门也不该复用");
    }

    @Test
    @DisplayName("按 id 查门")
    void 按id查门() {
        TileMap map = new TileMap("t", 12, 8);
        map.placeRedDoor(5, 1, 1);
        map.placeBlueDoor(7, 2, 2, 3);

        assertNotNull(map.redDoorById(5));
        assertEquals(1, map.redDoorById(5).col());
        assertNull(map.redDoorById(6), "不存在的 id 应返回 null");
        assertNotNull(map.blueDoorById(7));
        assertNull(map.redDoorById(7), "蓝门的 id 不该在红门里查到");
    }

    // ------------------------------------------------------------------
    // 尺寸变更
    // ------------------------------------------------------------------

    @Test
    @DisplayName("改尺寸保留重叠区域内容，越界的门被裁剪并清掉地形")
    void 改尺寸保留内容() {
        TileMap map = new TileMap("t", 12, 8);
        map.placeRedDoor(0, 0, 0);        // (0,0) 缩到 6x4 后仍在范围内 → 保留
        map.placeBlueDoor(0, 11, 7, 3);   // (11,7) 越界 → 裁掉

        int dropped = map.resize(6, 4);

        assertEquals(6, map.cols());
        assertEquals(4, map.rows());
        assertEquals(1, dropped, "只有蓝门越界，应报 1");
        assertEquals(1, map.redDoors().size(), "范围内的红门应保留");
        assertTrue(map.blueDoors().isEmpty(), "越界的蓝门应被裁掉");

        // 保留的红门：对象与地形都还在，两者一致
        assertEquals(TileType.RED_DOOR, map.tileAt(0, 0));
    }

    @Test
    @DisplayName("被裁掉的门不会留下孤儿地形")
    void 裁剪不留孤儿地形() {
        TileMap map = new TileMap("t", 12, 8);
        // 把门放在越界位置，缩尺寸后它既不在门列表里，地形也不该说它是门
        map.placeRedDoor(0, 10, 6);

        map.resize(6, 4);

        assertTrue(map.redDoors().isEmpty(), "越界的红门应被裁掉");
        assertEquals(TileType.EMPTY, map.tileAt(10, 6),
                "门没了，地形也不能还写着红门 —— 否则运行时读到的是指向不存在门的格子");
    }

    @Test
    @DisplayName("改尺寸放大时新区域是未放置")
    void 放大后新区域为空() {
        TileMap map = new TileMap("t", 4, 4);
        map.setTile(0, 0, TileType.GROUND);

        map.resize(8, 8);

        assertEquals(TileType.GROUND, map.tileAt(0, 0), "原有内容应保留");
        assertEquals(TileType.EMPTY, map.tileAt(7, 7), "新区域应是未放置");
        // 8x8 里只有 1 格是地面，其余 63 格未放置
        assertEquals(8 * 8 - 1, map.countByType()[TileType.EMPTY.ordinal()]);
    }

    // ------------------------------------------------------------------
    // JSON 读
    // ------------------------------------------------------------------

    @Test
    @DisplayName("能读入示例地图，且契约各项正确")
    void 能读样例地图() throws IOException {
        // 示例地图现在住在「演示地图」这个战役里（map/<战役名>.json）——
        // 走 CampaignLoader 读它，而不是自己拼路径。
        // （旧结构下它在 data/maps/ 下，那个目录已经废弃。）
        Path sample = demoMapFile();
        assertTrue(Files.isRegularFile(sample), "示例地图应存在: " + sample);

        TileMap map = MapJsonIO.load(sample);

        assertEquals(EditorApp.DEMO_MAP_NAME, map.name(), "name 应与文件名一致（严格绑定）");
        assertEquals(12, map.cols());
        assertEquals(8, map.rows());
        assertEquals(64, map.tileSize());

        // tiles 维度：8 行 × 12 列
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 12; col++) {
                assertNotNull(map.tileAt(col, row), "(" + col + "," + row + ") 应有地形");
            }
        }
        assertEquals(0, map.countByType()[TileType.EMPTY.ordinal()], "样例地图不该有未放置格");

        // 门
        assertTrue(map.redDoors().size() >= 1, "样例地图至少有一道红门（门数会随编辑变化）");
        assertTrue(map.blueDoors().size() >= 1, "样例地图至少有一道蓝门");
        Door red = map.redDoors().get(0);
        assertEquals(0, red.id());
        assertEquals(0, red.col());
        assertEquals(0, red.row());

        Door blue = map.blueDoors().get(0);
        assertEquals(0, blue.id());
        assertEquals(11, blue.col());
        assertEquals(7, blue.row());
        assertEquals(3, blue.hp(), "蓝门默认生命应为 3");

        // 门的地形必须与门列表一致
        assertEquals(TileType.RED_DOOR, map.tileAt(red.col(), red.row()));
        assertEquals(TileType.BLUE_DOOR, map.tileAt(blue.col(), blue.row()));
    }

    @Test
    @DisplayName("legend 的负数 key 能被正确读到")
    void 负数key可读() throws IOException {
        String json = Files.readString(demoMapFile(),
                StandardCharsets.UTF_8);
        JSONObject root = JSONObject.parse(json);
        JSONObject legend = root.getJSONObject("legend");

        assertNotNull(legend);
        // 这是本契约最容易踩的地方：legend 用字符串 key，必须 getString
        assertEquals("empty", legend.getString("-1"));
        assertEquals("impassable", legend.getString("0"));
        assertEquals("blue_door", legend.getString("4"));
        assertThrows(RuntimeException.class, () -> legend.getInt("-1"),
                "getInt(\"-1\") 会抛异常，这就是为什么解析必须用 getString");
    }

    @Test
    @DisplayName("未知地形码退回未放置，不让整张图加载失败")
    void 未知地形码降级() throws IOException {
        String json = """
                {
                  "version": 1, "name": "bad", "cols": 2, "rows": 1, "tileSize": 64,
                  "legend": { "-1": "empty" },
                  "tiles": [[1, 99]],
                  "redDoors": [], "blueDoors": []
                }
                """;
        TileMap map = MapJsonIO.parse(json, "bad");

        assertEquals(TileType.GROUND, map.tileAt(0, 0));
        assertEquals(TileType.EMPTY, map.tileAt(1, 0), "未知码应降级为未放置");
    }

    @Test
    @DisplayName("结构性错误会被拒绝：行数/列数不符、版本不对")
    void 结构错误被拒() {
        String wrongRows = """
                { "version":1, "name":"x", "cols":2, "rows":2, "tileSize":64,
                  "legend":{}, "tiles":[[1,1]], "redDoors":[], "blueDoors":[] }
                """;
        assertThrows(IOException.class, () -> MapJsonIO.parse(wrongRows, "x"));

        String wrongCols = """
                { "version":1, "name":"x", "cols":3, "rows":1, "tileSize":64,
                  "legend":{}, "tiles":[[1,1]], "redDoors":[], "blueDoors":[] }
                """;
        assertThrows(IOException.class, () -> MapJsonIO.parse(wrongCols, "x"));

        String badVersion = """
                { "version":9, "name":"x", "cols":1, "rows":1, "tileSize":64,
                  "legend":{}, "tiles":[[1]], "redDoors":[], "blueDoors":[] }
                """;
        assertThrows(IOException.class, () -> MapJsonIO.parse(badVersion, "x"));
    }

    @Test
    @DisplayName("★ 超过上限的地图在**载入时**就被拒绝（Q4 的决定）")
    void 超限地图载入被拒() {
        int over = TileMap.MAX_ROWS + 1;
        // 造一行 over 个 1，以及 over 行
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < over; i++) {
            row.append(i == 0 ? "1" : ",1");
        }
        StringBuilder tiles = new StringBuilder();
        for (int r = 0; r < over; r++) {
            tiles.append(r == 0 ? "" : ",").append('[').append(row).append(']');
        }
        String json = "{ \"version\":1, \"name\":\"big\", \"cols\":" + over
                + ", \"rows\":" + over + ", \"tileSize\":64,"
                + " \"legend\":{}, \"tiles\":[" + tiles + "],"
                + " \"redDoors\":[], \"blueDoors\":[] }";

        IOException e = assertThrows(IOException.class, () -> MapJsonIO.parse(json, "big"));
        assertTrue(e.getMessage().contains("超过"),
                "提示要说清是超限，实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("改小"),
                "提示要给出下一步动作（请改小），实际: " + e.getMessage());

        // 恰好等于上限是允许的 —— 边界不能误伤
        String exact = "{ \"version\":1, \"name\":\"edge\", \"cols\":1, \"rows\":1,"
                + " \"tileSize\":64, \"legend\":{}, \"tiles\":[[1]],"
                + " \"redDoors\":[], \"blueDoors\":[] }";
        assertDoesNotThrow(() -> MapJsonIO.parse(exact, "edge"));
    }

    // ------------------------------------------------------------------
    // JSON 写 + 往返
    // ------------------------------------------------------------------

    @Test
    @DisplayName("写出再读回，内容完全一致（往返一致性）")
    void 往返一致(@TempDir Path tempDir) throws IOException {
        TileMap original = new TileMap("round_trip", 12, 8);
        original.setTile(0, 0, TileType.GROUND);
        original.setTile(1, 0, TileType.HIGH);
        original.setTile(2, 0, TileType.IMPASSABLE);
        original.setTile(5, 3, TileType.HIGH);
        original.placeRedDoor(0, 0, 7);
        original.placeBlueDoor(0, 11, 0, 5);

        Path file = tempDir.resolve("round_trip.json");
        MapJsonIO.save(original, file);
        TileMap loaded = MapJsonIO.load(file);

        assertEquals(original.name(), loaded.name());
        assertEquals(original.cols(), loaded.cols());
        assertEquals(original.rows(), loaded.rows());
        assertEquals(original.tileSize(), loaded.tileSize());

        for (int row = 0; row < original.rows(); row++) {
            for (int col = 0; col < original.cols(); col++) {
                assertEquals(original.tileAt(col, row), loaded.tileAt(col, row),
                        "(" + col + "," + row + ") 地形不一致");
            }
        }

        assertEquals(original.redDoors().size(), loaded.redDoors().size());
        assertEquals(original.redDoors().get(0), loaded.redDoors().get(0));
        assertEquals(original.blueDoors().size(), loaded.blueDoors().size());
        assertEquals(original.blueDoors().get(0), loaded.blueDoors().get(0));
        assertEquals(5, loaded.blueDoors().get(0).hp(), "蓝门生命值应往返保持");
    }

    @Test
    @DisplayName("写出的 JSON 是格式化过的（不是一行）")
    void 写出是格式化的() {
        TileMap map = new TileMap("t", 3, 2);
        String json = MapJsonIO.toJson(map);

        assertTrue(json.contains("\n"), "应当有换行，否则 diff 没法读");
        assertTrue(json.contains("\"version\": 1"), "应含版本字段");
        assertTrue(json.contains("\"-1\": \"empty\""), "legend 的负数 key 应写成字符串");
        assertTrue(json.lines().count() > 5, "应有多行");
    }

    @Test
    @DisplayName("写盘会自动创建缺失的目录")
    void 写盘自动建目录(@TempDir Path tempDir) throws IOException {
        TileMap map = new TileMap("t", 2, 2);
        Path nested = tempDir.resolve("a/b/c/map.json");

        MapJsonIO.save(map, nested);

        assertTrue(Files.isRegularFile(nested), "应自动创建 a/b/c 并写出文件");
    }

    // ------------------------------------------------------------------
    // 注意：校验（MapValidator）的测试已移到 MapPersistenceTest
    //
    // 校验在 P2.2 从 MapJsonIO 抽成了独立的 campaigneditor.util.MapValidator，
    // 测试也跟着走。**这里不再重复一份** —— 同一个规则两处维护，
    // 迟早会只改一处，然后两条测试互相矛盾。
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 调试战役的交接契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("调试战役目录常量与路径拼接正确")
    void 调试战役路径正确() {
        assertEquals("_debug", Workspace.DEBUG_CAMPAIGN_NAME);

        Path dir = Workspace.campaignDir(Workspace.DEBUG_CAMPAIGN_NAME);
        assertEquals(Workspace.campaignsDir().resolve("_debug"), dir);
        assertTrue(dir.startsWith(Workspace.dataDir()), "调试战役应在 data/ 下");
    }

    /**
     * 「演示地图」战役里的地图文件。
     *
     * <p>旧结构下示例地图在 {@code data/maps/演示地图.json}，那个目录已废弃；
     * 现在走 {@code CampaignLoader} 拿到战役，再用它声明的路径。
     */
    private static java.nio.file.Path demoMapFile() {
        com.yestercar.common.model.Campaign c =
                com.yestercar.common.io.CampaignLoader.findById(
                        com.yestercar.campaigneditor.EditorApp.DEMO_MAP_NAME);
        org.junit.jupiter.api.Assertions.assertNotNull(c, "应当有「演示地图」这个战役");
        return c.mapFile();
    }
}
