package com.yestercar;

import com.yestercar.campaigneditor.io.MapJsonIO;
import com.yestercar.campaigneditor.model.Door;
import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.model.TileType;
import com.yestercar.campaigneditor.util.MapNaming;
import com.yestercar.campaigneditor.util.MapValidator;
import com.yestercar.common.io.Workspace;
import com.yestercar.common.model.CampaignMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2.2：保存 / 加载地图 JSON、{@link MapValidator}、文件名规则。
 *
 * <p>文档给这一阶段点名的验收断言是
 * 「{@code MapJsonIO} 存盘后断言字段齐全、门 ID 引用有效」——
 * {@link #存盘后字段齐全} 就是照它写的。
 */
@DisplayName("P2.2 保存 / 加载 / 校验 / 命名")
class MapPersistenceTest {

    // ------------------------------------------------------------------
    // 文件名规则（严格绑定：地图名 ≡ 文件名）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("地图名取文件名的 stem")
    void 地图名取文件名() {
        assertEquals("map_001", MapNaming.nameFromFile(Path.of("x/map_001.json")));
        assertEquals("my_map", MapNaming.nameFromFile(Path.of("D:/a/b/my_map.json")));
        assertEquals("中文名", MapNaming.nameFromFile(Path.of("data/maps/中文名.json")));
        // 多个点：只砍最后一个
        assertEquals("map.v2", MapNaming.nameFromFile(Path.of("map.v2.json")));
        // 没有扩展名
        assertEquals("map_003", MapNaming.nameFromFile(Path.of("data/map_003")));
        // 「只有扩展名」的退化输入：名字取不到，退回 untitled。
        // 不能返回 ".json" —— 那是个以点开头的名字，Windows 上写盘会出问题。
        assertEquals("untitled", MapNaming.nameFromFile(Path.of("data/.json")));
        // 末位是分隔符时 Path 会规范化掉它，getFileName() 得到的是最后一段目录名
        assertEquals("data", MapNaming.nameFromFile(Path.of("data/")));
    }

    @Test
    @DisplayName("另存为会补上 .json 扩展名")
    void 补扩展名() {
        // JFileChooser 不会替用户补扩展名；不补的话下次按 *.json 过滤就看不见这个文件
        assertEquals(Path.of("a/map.json"), MapNaming.ensureExtension(Path.of("a/map")));
        // 已经有了就不重复加
        assertEquals(Path.of("a/map.json"), MapNaming.ensureExtension(Path.of("a/map.json")));
        // 大小写不敏感
        assertEquals(Path.of("a/map.JSON"), MapNaming.ensureExtension(Path.of("a/map.JSON")));
        // 名字里有点但不是 json 扩展名 → 仍然补
        assertEquals(Path.of("a/map.v2.json"), MapNaming.ensureExtension(Path.of("a/map.v2")));
    }

    @Test
    @DisplayName("文件名清洗：去掉非法字符与结尾的点/空格")
    void 文件名清洗() {
        assertEquals("mymap", MapNaming.sanitizeFileName("my:map"),
                "非法字符应删除而不是替换成下划线");
        assertEquals("abc", MapNaming.sanitizeFileName("a\\b/c"));
        assertEquals("ab", MapNaming.sanitizeFileName("a*b"));
        assertEquals("ab", MapNaming.sanitizeFileName("a?b"));
        assertEquals("ab", MapNaming.sanitizeFileName("a\"b"));
        assertEquals("ab", MapNaming.sanitizeFileName("a<b>"));
        assertEquals("ab", MapNaming.sanitizeFileName("a|b"));

        assertEquals("map", MapNaming.sanitizeFileName("  map  "), "首尾空白要去掉");
        assertEquals("map", MapNaming.sanitizeFileName("map."), "结尾的点要去掉（Windows 不允许）");
        assertEquals("map", MapNaming.sanitizeFileName("map..."), "多个结尾点都要去掉");
        assertEquals("map", MapNaming.sanitizeFileName("map   "), "结尾空格要去掉");

        assertEquals("", MapNaming.sanitizeFileName(null), "null 不该抛异常");
        assertEquals("", MapNaming.sanitizeFileName("   "));
        assertEquals("", MapNaming.sanitizeFileName("///"));
        assertEquals("", MapNaming.sanitizeFileName("..."), "全是点 → 清空，调用方应当拒绝");
    }

    @Test
    @DisplayName("另存为的默认文件名")
    void 默认文件名() {
        assertEquals("map_002.json", MapNaming.suggestedFileName("map_002"));
        assertEquals("中文名.json", MapNaming.suggestedFileName("中文名"));
        // 非法名字退回 untitled，而不是给出一个空文件名
        assertEquals("untitled.json", MapNaming.suggestedFileName("..."));
        assertEquals("untitled.json", MapNaming.suggestedFileName(null));
    }

    // ------------------------------------------------------------------
    // 校验（MapValidator）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("校验：空图报错（缺门 + 没地块）")
    void 校验空图() {
        TileMap map = new TileMap("t", 4, 4);
        MapValidator.ValidationResult v = MapValidator.validate(map);

        assertFalse(v.ok(), "空图应当校验失败");
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("红门")), "应报缺红门");
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("蓝门")), "应报缺蓝门");
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("空的")), "应报地图是空的");
    }

    @Test
    @DisplayName("校验：画了一半，未放置格本身只是提示")
    void 未放置格只是提示() {
        // 门之间连着地面 → 通路是通的，所以这里只该有"未放置"这一条提示
        TileMap map = new TileMap("t", 4, 4);
        // 铺一条从 (1,1) 到 (3,3) 的通路
        map.setTile(1, 1, TileType.GROUND);
        map.setTile(2, 1, TileType.GROUND);
        map.setTile(2, 2, TileType.GROUND);
        map.setTile(3, 2, TileType.GROUND);
        map.placeRedDoor(0, 1, 1);
        map.placeBlueDoor(0, 3, 3, 3);

        MapValidator.ValidationResult v = MapValidator.validate(map);
        assertTrue(v.ok(), "有未放置格不该拦保存 —— 画一半存盘是正常需求。实际: " + v.errors());
        assertTrue(v.warnings().stream().anyMatch(w -> w.contains("未放置")),
                "但应当提示还有多少格没画");
        assertFalse(v.clean());
    }

    @Test
    @DisplayName("★ 校验：红蓝门被未放置格隔断时报错，但可以「仍然保存」")
    void 未放置格隔断通路会报错() {
        // 只画了红门那一格，其余全是未放置 —— 这正是"刚起了个头"的典型状态。
        // 未放置格不可通行，所以红门到蓝门没有通路，会被拦下；
        // 而「仍然保存」走的正是 validate(map, false) 这条路。
        TileMap map = new TileMap("t", 4, 4);
        map.setTile(0, 0, TileType.GROUND);
        map.placeRedDoor(0, 1, 1);
        map.placeBlueDoor(0, 3, 3, 3);

        MapValidator.ValidationResult strict = MapValidator.validate(map);
        assertFalse(strict.ok(), "没有通路必须拦住");
        assertTrue(strict.errors().stream().anyMatch(e -> e.contains("没有通路")),
                "错误要说清是没有通路。实际: " + strict.errors());

        MapValidator.ValidationResult forced = MapValidator.validate(map, false);
        assertTrue(forced.ok(), "「仍然保存」应当能存下这张草稿");
        assertTrue(forced.warnings().stream().anyMatch(w -> w.contains("未放置")));
    }

    @Test
    @DisplayName("校验：门数量超过上限报错，且提示是警告不是错误")
    void 校验门数量上限() {
        TileMap map = filledMap(12, 8);
        map.placeRedDoor(0, 0, 0);
        map.placeRedDoor(1, 1, 0);
        map.placeRedDoor(2, 2, 0);       // 第三道，超上限
        map.placeBlueDoor(0, 11, 7, 3);

        MapValidator.ValidationResult v = MapValidator.validate(map);
        assertFalse(v.ok());
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("红门最多")), "应报红门超上限");
    }

    @Test
    @DisplayName("校验：格子像素不是默认值时只是提示")
    void 非默认格子像素只是提示() {
        TileMap map = filledMap(4, 4);
        map.placeRedDoor(0, 0, 0);
        map.placeBlueDoor(0, 3, 3, 3);
        map.setTileSize(32);

        MapValidator.ValidationResult v = MapValidator.validate(map);
        assertTrue(v.ok(), "自定义格子像素是契约允许的，不该拦");
        assertTrue(v.warnings().stream().anyMatch(w -> w.contains("格子像素")));
    }

    @Test
    @DisplayName("校验：门与地形不一致会被抓出来（防手工改坏 JSON）")
    void 校验门与地形不一致() {
        TileMap map = filledMap(6, 6);
        map.placeRedDoor(0, 1, 1);
        map.placeBlueDoor(0, 4, 4, 3);

        // 用 setTile 强行把门的格子改成地面 —— 这绕过了 TileMap 的联动，
        // 相当于手工改坏了 JSON 文件
        map.setTile(1, 1, TileType.GROUND);

        MapValidator.ValidationResult v = MapValidator.validate(map);
        assertFalse(v.ok(), "门列表里有门、地形却不是门 → 必须报错");
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("红门 #0")),
                "错误信息应指出是哪道门：" + v.errors());
    }

    @Test
    @DisplayName("校验摘要：错误优先于提示，且不会太长")
    void 校验摘要可用() {
        TileMap empty = new TileMap("t", 4, 4);
        String s = MapValidator.validate(empty).summary();
        assertFalse(s.isBlank());
        assertTrue(s.length() < 60, "状态栏只有一行位置，摘要要短：" + s);

        TileMap partial = filledMap(4, 4);
        partial.placeRedDoor(0, 0, 0);
        partial.placeBlueDoor(0, 3, 3, 3);
        assertEquals("校验通过", MapValidator.validate(partial).summary());

        assertTrue(MapValidator.validate(empty).detail().contains("必须修掉"));
    }

    // ------------------------------------------------------------------
    // 存盘：文档点名的验收断言
    // ------------------------------------------------------------------

    @Test
    @DisplayName("存盘后字段齐全、门 ID 引用有效")
    void 存盘后字段齐全(@TempDir Path tempDir) throws IOException {
        // 具体设计 §6.3 给 P2.2 点名的验收断言就是这个
        TileMap map = filledMap(12, 8);
        map.setTile(3, 3, TileType.HIGH);
        map.setTile(4, 3, TileType.HIGH);
        map.setTile(6, 2, TileType.IMPASSABLE);
        map.placeRedDoor(0, 0, 0);
        map.placeBlueDoor(0, 11, 7, 5);

        Path file = tempDir.resolve("map_accept.json");
        MapJsonIO.save(map, file);
        assertTrue(Files.isRegularFile(file));

        // 字段齐全：逐项对照契约
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\": 1"), "缺 version");
        assertTrue(json.contains("\"name\": \"map_accept\""), "name 应等于文件名 stem");
        assertTrue(json.contains("\"cols\": 12"), "缺 cols");
        assertTrue(json.contains("\"rows\": 8"), "缺 rows");
        assertTrue(json.contains("\"tileSize\": 64"), "缺 tileSize");
        assertTrue(json.contains("\"-1\": \"empty\""), "legend 的负数 key 不对");
        assertTrue(json.contains("\"tiles\""), "缺 tiles");
        assertTrue(json.contains("\"redDoors\""), "缺 redDoors");
        assertTrue(json.contains("\"blueDoors\""), "缺 blueDoors");
        assertTrue(json.contains("\"hp\": 5"), "蓝门 HP 没写进去");

        // 门 ID 引用有效：读回来之后地形与门列表仍然自洽
        TileMap back = MapJsonIO.load(file);
        assertEquals(1, back.redDoors().size());
        assertEquals(1, back.blueDoors().size());
        for (Door d : back.redDoors()) {
            assertEquals(TileType.RED_DOOR, back.tileAt(d.col(), d.row()),
                    "红门 #" + d.id() + " 在列表里，地形却不是红门");
        }
        for (Door d : back.blueDoors()) {
            assertEquals(TileType.BLUE_DOOR, back.tileAt(d.col(), d.row()),
                    "蓝门 #" + d.id() + " 在列表里，地形却不是蓝门");
        }
        assertEquals(5, back.blueDoorById(0).hp(), "HP 应往返保持");
    }

    @Test
    @DisplayName("存盘再读回，内容完全一致（含中文地图名）")
    void 往返一致含中文名(@TempDir Path tempDir) throws IOException {
        TileMap map = filledMap(6, 5);
        map.setName("中文地图名");
        map.setTile(2, 2, TileType.HIGH);
        map.placeRedDoor(0, 0, 4);
        map.placeBlueDoor(0, 5, 0, 7);

        Path file = tempDir.resolve("中文地图名.json");
        MapJsonIO.save(map, file);
        TileMap back = MapJsonIO.load(file);

        assertEquals("中文地图名", back.name(), "UTF-8 中文名应往返保持");
        assertEquals(TileType.HIGH, back.tileAt(2, 2));
        assertEquals(7, back.blueDoorById(0).hp());
        for (int row = 0; row < map.rows(); row++) {
            for (int col = 0; col < map.cols(); col++) {
                assertEquals(map.tileAt(col, row), back.tileAt(col, row),
                        "(" + col + "," + row + ") 地形不一致");
            }
        }
    }

    @Test
    @DisplayName("存到不存在的目录会自动建目录")
    void 自动建目录(@TempDir Path tempDir) throws IOException {
        TileMap map = filledMap(4, 4);
        map.placeRedDoor(0, 0, 0);
        map.placeBlueDoor(0, 3, 3, 3);

        Path nested = tempDir.resolve("a/b/c/map.json");
        MapJsonIO.save(map, nested);
        assertTrue(Files.isRegularFile(nested));
    }

    @Test
    @DisplayName("地图名与文件名绑定：改名后写盘，读回来是新名字")
    void 改名后写盘生效(@TempDir Path tempDir) throws IOException {
        TileMap map = filledMap(4, 4);
        map.placeRedDoor(0, 0, 0);
        map.placeBlueDoor(0, 3, 3, 3);

        Path first = tempDir.resolve("old_name.json");
        MapJsonIO.save(map, first);
        assertEquals("old_name", MapJsonIO.load(first).name());

        // 「另存为」到另一个文件名 —— 这就是"改名"
        Path second = tempDir.resolve("new_name.json");
        map.setName(MapNaming.nameFromFile(second));   // 编辑器在写盘前会做这一步
        MapJsonIO.save(map, second);

        assertEquals("new_name", MapJsonIO.load(second).name(), "文件里的 name 应跟文件名走");
        assertNotEquals("old_name", MapJsonIO.load(second).name());
        // 老文件还在，内容没被动
        assertTrue(Files.isRegularFile(first));
        assertEquals("old_name", MapJsonIO.load(first).name(), "老文件不该被改");
    }

    @Test
    @DisplayName("载入时以文件名为准，能纠正 JSON 里手改过的 name")
    void 载入以文件名为准(@TempDir Path tempDir) throws IOException {
        // 造一个 name 与文件名不一致的文件（模拟有人手工改过 JSON）
        String json = """
                { "version":1, "name":"故意的错名字", "cols":2, "rows":1, "tileSize":64,
                  "legend":{},
                  "tiles":[[3,1]],
                  "redDoors":[{"id":0,"col":0,"row":0}],
                  "blueDoors":[{"id":0,"col":1,"row":0,"hp":3}] }
                """;
        Path file = tempDir.resolve("real_name.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);

        TileMap loaded = MapJsonIO.load(file);
        assertEquals("故意的错名字", loaded.name(), "MapJsonIO 只负责读，不擅自改名");

        // 纠正发生在编辑器这一层（载入后按文件名重设）
        loaded.setName(MapNaming.nameFromFile(file));
        assertEquals("real_name", loaded.name(), "编辑器应以文件名为准");
    }

    // ------------------------------------------------------------------
    // 路径契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("地图路径由战役约定推导（不再有 data/maps/ 这一层）")
    void 地图路径由战役约定推导() {
        // 旧结构下地图在 data/maps/<地图名>.json，且 Workspace 有
        // mapsDir() / mapFile(name) 两个入口。那两层已经废弃：
        // 「一个战役 = 一张地图」，地图自包含在战役目录里。
        assertEquals("map", CampaignMeta.MAP_DIR);
        assertEquals("map/演示地图.json", CampaignMeta.mapPathFor("演示地图"));

        // 战役目录本身仍在 data/ 下
        assertTrue(Workspace.campaignsDir().startsWith(Workspace.dataDir()),
                "战役目录应当在 data/ 下，实际: " + Workspace.campaignsDir());
    }

    // ------------------------------------------------------------------
    // 存盘不得改动活对象
    // ------------------------------------------------------------------

    @Test
    @DisplayName("存副本不改活地图：测试按钮不该把地图改名成 map_001")
    void 存副本不改活地图(@TempDir Path dir) throws IOException {
        TileMap live = filledMap(6, 4);
        live.setName("我画的地图");
        live.placeRedDoor(live.nextRedDoorId(), 0, 0);
        live.placeBlueDoor(live.nextBlueDoorId(), 5, 3, 3);

        // 模拟「测试」按钮：把副本写成固定文件名 map_001.json
        // （MapJsonIO.save 会把 name 对齐到文件名，这正是原来污染活地图的机制）
        Path debugFile = dir.resolve("map_001.json");
        MapJsonIO.save(live.copy("map_001"), debugFile);

        assertEquals("我画的地图", live.name(),
                "存副本之后活地图的名字不该变 —— 这曾经是「点了测试、地图名永久变 map_001」的 bug");

        // 副本文件本身要写对
        TileMap written = MapJsonIO.load(debugFile);
        assertEquals("map_001", written.name(), "副本里的名字应等于文件名");
        assertEquals(3, written.blueDoors().get(0).hp());

        // 内容也要一致，且两边互不影响
        assertEquals(live.cols(), written.cols());
        assertEquals(live.rows(), written.rows());
        written.setTile(1, 1, TileType.EMPTY);
        assertEquals(TileType.GROUND, live.tileAt(1, 1),
                "改副本不该影响活地图（必须是深拷贝）");
    }

    @Test
    @DisplayName("副本的门 id 计数器也一起拷过来")
    void 副本带上门id计数器() {
        TileMap live = filledMap(6, 4);
        live.placeRedDoor(live.nextRedDoorId(), 0, 0);   // id 0
        live.placeRedDoor(live.nextRedDoorId(), 1, 0);   // id 1
        live.removeDoorAt(1, 0);                         // 删掉最高的那个，计数器必须留在 2

        TileMap c = live.copy("copy");
        assertEquals(2, c.nextRedDoorId(), "副本应当继承 nextRedId，不该重用已被删掉的 id");
    }

    @Test
    @DisplayName("直接存活地图确实会改它的名字（说明为什么必须存副本）")
    void 存活地图会改名(@TempDir Path dir) throws IOException {
        // 这条不是在测"功能正常"，而是在**记录一个已知副作用**：
        // MapJsonIO.save 会把 name 对齐到文件名。
        // 谁要是想省掉 copy()，这条测试会告诉他为什么省不掉。
        TileMap live = filledMap(4, 3);
        live.setName("原名");

        MapJsonIO.save(live, dir.resolve("other_name.json"));
        assertEquals("other_name", live.name(),
                "已知副作用：save 会把 name 对齐到文件名，所以绝不能拿活对象去存调试文件");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 造一张铺满地面的地图（用于"只差门"的校验场景）。 */
    private static TileMap filledMap(int cols, int rows) {
        TileMap map = new TileMap("t", cols, rows);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                map.setTile(col, row, TileType.GROUND);
            }
        }
        return map;
    }
}
