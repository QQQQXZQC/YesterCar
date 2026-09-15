package com.yestercar;

import com.yestercar.common.io.Workspace;
import com.yestercar.common.util.AssetPaths;
import com.yestercar.common.util.Director;
import com.yestercar.common.util.UITheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import processing.data.JSONArray;
import processing.data.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 骨架自检：验证「工程能构建、资源能读、坐标换算正确、可写目录能建」。
 *
 * <p>这些是纯逻辑验证，因此用 JUnit 而不是界面上的自检按钮——可重复执行、可在 CI 跑。
 * 窗口相关的验证本质上是 GUI，只能靠人工，见 {@code docs/第一阶段规划.md} 第四节。
 */
@DisplayName("骨架自检")
class Stage1SmokeTest {

    // ------------------------------------------------------------------
    // 构建与依赖
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Processing 渲染层与 G4P 都在 classpath 上")
    void 依赖可解析() {
        assertTrue(classPresent("processing.core.PApplet"), "缺少 org.processing:core");
        assertTrue(classPresent("g4p_controls.G4P"), "缺少 org.g4p:g4p");
        assertTrue(classPresent("g4p_controls.GWindow"), "G4P 版本不完整");
    }

    // ------------------------------------------------------------------
    // 只读资源
    // ------------------------------------------------------------------

    @Test
    @DisplayName("敌人与干员数据可从 classpath 读取并解析")
    void 资源数据可读取() throws IOException {
        JSONObject enemies = JSONObject.parse(Workspace.readResourceAsString("enemies.json"));
        JSONArray enemyList = enemies.getJSONArray("enemies");
        assertNotNull(enemyList, "enemies.json 缺少 enemies 数组");
        assertTrue(enemyList.size() >= 2, "样例至少应有 2 个敌人");

        JSONObject soldier = enemyList.getJSONObject(0);
        assertEquals("soldier", soldier.getString("id"));
        assertEquals(300, soldier.getInt("hp"));
        assertFalse(soldier.getBoolean("isRanged"), "soldier 应为近战");
        assertTrue(soldier.isNull("range"), "近战敌人的 range 应为 null");

        JSONObject archer = enemyList.getJSONObject(1);
        assertTrue(archer.getBoolean("isRanged"), "archer 应为远程");
        JSONObject range = archer.getJSONObject("range");
        assertNotNull(range);
        assertEquals(12, range.getJSONArray("right").size(), "远程范围朝右应为 12 格");

        JSONObject operators = JSONObject.parse(Workspace.readResourceAsString("operators.json"));
        JSONArray operatorList = operators.getJSONArray("operators");
        assertNotNull(operatorList);
        assertEquals(2, operatorList.size());
        assertEquals("guard_01", operatorList.getJSONObject(0).getString("id"));
        assertEquals("high", operatorList.getJSONObject(1).getString("deployType"));
    }

    // ------------------------------------------------------------------
    // 地图契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("地图契约正确，含负数 key 与二维 tiles")
    void 地图契约正确() throws IOException {
        Path mapPath = Workspace.mapFile("map_001");
        assertTrue(Files.exists(mapPath), "样例地图不存在: " + mapPath);

        JSONObject map = JSONObject.parse(Files.readString(mapPath, StandardCharsets.UTF_8));

        assertEquals(1, map.getInt("version"));
        assertEquals("map_001", map.getString("name"));
        assertEquals(12, map.getInt("cols"));
        assertEquals(8, map.getInt("rows"));
        assertEquals(64, map.getInt("tileSize"));

        // 重点：地形枚举用负数 -1 当 key。JSON 里是字符串 "-1"，Processing 的
        // JSONObject 内部以 String 存 key，因此必须用 getString("-1") 读取；
        // 用 getInt("-1") 会抛 RuntimeException（非数值 key）。契约锁死在这里。
        JSONObject legend = map.getJSONObject("legend");
        assertEquals("empty", legend.getString("-1"));
        assertEquals("ground", legend.getString("1"));
        assertEquals("blue_door", legend.getString("4"));
        assertThrows(RuntimeException.class, () -> legend.getInt("-1"),
                "若这里不再抛异常，说明 Processing 版本行为已变，地图解析代码需同步调整");

        // tiles 维度 = rows 行 x cols 列
        JSONArray tiles = map.getJSONArray("tiles");
        assertEquals(8, tiles.size(), "tiles 行数应等于 rows");
        for (int row = 0; row < tiles.size(); row++) {
            assertEquals(12, tiles.getJSONArray(row).size(),
                    "第 " + row + " 行的列数应等于 cols");
        }

        assertEquals(3, tiles.getJSONArray(0).getInt(0), "左上角应为红门");
        assertEquals(4, tiles.getJSONArray(7).getInt(11), "右下角应为蓝门");

        // 门
        JSONArray redDoors = map.getJSONArray("redDoors");
        JSONArray blueDoors = map.getJSONArray("blueDoors");
        assertEquals(1, redDoors.size());
        assertEquals(1, blueDoors.size());

        JSONObject redDoor = redDoors.getJSONObject(0);
        assertEquals(0, redDoor.getInt("id"));
        assertEquals(0, redDoor.getInt("col"));
        assertEquals(0, redDoor.getInt("row"));

        JSONObject blueDoor = blueDoors.getJSONObject(0);
        assertEquals(0, blueDoor.getInt("id"));
        assertEquals(11, blueDoor.getInt("col"));
        assertEquals(7, blueDoor.getInt("row"));
        assertEquals(3, blueDoor.getInt("hp"), "蓝门默认生命为 3");
    }

    @Test
    @DisplayName("地图的门坐标与 tiles 上的门地形一致")
    void 门坐标与地形一致() throws IOException {
        JSONObject map = JSONObject.parse(
                Files.readString(Workspace.mapFile("map_001"), StandardCharsets.UTF_8));
        JSONArray tiles = map.getJSONArray("tiles");

        JSONObject redDoor = map.getJSONArray("redDoors").getJSONObject(0);
        assertEquals(3, tiles.getJSONArray(redDoor.getInt("row")).getInt(redDoor.getInt("col")),
                "红门坐标处的地形应为 3");

        JSONObject blueDoor = map.getJSONArray("blueDoors").getJSONObject(0);
        assertEquals(4, tiles.getJSONArray(blueDoor.getInt("row")).getInt(blueDoor.getInt("col")),
                "蓝门坐标处的地形应为 4");
    }

    // ------------------------------------------------------------------
    // 可写数据
    // ------------------------------------------------------------------

    @Test
    @DisplayName("可写数据目录能创建，且路径指向工程内")
    void 可写数据目录能创建() throws IOException {
        Workspace.ensureWritableDirs();

        assertTrue(Files.isDirectory(Workspace.mapsDir()), "data/maps 应已创建");
        assertTrue(Files.isDirectory(Workspace.levelsDir()), "data/levels 应已创建");

        // 关键：路径不能依赖 Processing 的 dataPath()，必须落在工程根下的 data/
        assertEquals(Workspace.projectRoot().resolve("data"), Workspace.dataDir());
        assertTrue(Workspace.dataDir().startsWith(Workspace.projectRoot()),
                "可写数据目录必须位于工程内，实际为 " + Workspace.dataDir());

        assertEquals("map_001.json", Workspace.mapFile("map_001").getFileName().toString());
        assertEquals("map_002.json", Workspace.mapFile("map_002.json").getFileName().toString(),
                "已带后缀时不应重复追加");
        assertEquals("level_001.json", Workspace.levelFile("level_001").getFileName().toString());
    }

    // ------------------------------------------------------------------
    // 坐标换算与朝向
    // ------------------------------------------------------------------

    @Test
    @DisplayName("像素与格子坐标换算正确")
    void 坐标换算正确() {
        // 契约：像素坐标 = col * tileSize + tileSize / 2
        assertEquals(32f, GridMathProbe.centerX(0, 64));
        assertEquals(32f, GridMathProbe.centerY(0, 64));
        assertEquals(736f, GridMathProbe.centerX(11, 64));
        assertEquals(480f, GridMathProbe.centerY(7, 64));

        // 反向：像素落在哪个格
        assertEquals(0, GridMathProbe.colAt(0, 64));
        assertEquals(0, GridMathProbe.colAt(63, 64));
        assertEquals(1, GridMathProbe.colAt(64, 64));
        assertEquals(11, GridMathProbe.colAt(767, 64));
    }

    @Test
    @DisplayName("朝向旋转符合契约")
    void 朝向旋转正确() {
        // 基准是「朝右」；近卫范围 = 自身 + 身前一格
        assertEquals(Director.RIGHT, Director.fromDelta(1, 0));
        assertEquals(Director.LEFT, Director.fromDelta(-1, 0));
        assertEquals(Director.DOWN, Director.fromDelta(0, 1));
        assertEquals(Director.UP, Director.fromDelta(0, -1));

        assertEquals(Director.DOWN, Director.RIGHT.clockwise());
        assertEquals(Director.UP, Director.RIGHT.counterClockwise());
        assertEquals(Director.LEFT, Director.RIGHT.opposite());

        // 朝右时身前一格 = (1,0)；朝下时应旋转为 (0,1)
        assertArrayEqualsInt(new int[]{1, 0}, Director.RIGHT.rotate(1, 0));
        assertArrayEqualsInt(new int[]{0, 1}, Director.DOWN.rotate(1, 0));
        assertArrayEqualsInt(new int[]{-1, 0}, Director.LEFT.rotate(1, 0));
        assertArrayEqualsInt(new int[]{0, -1}, Director.UP.rotate(1, 0));
    }

    private static void assertArrayEqualsInt(int[] expected, int[] actual) {
        assertEquals(expected[0], actual[0], "列偏移不符");
        assertEquals(expected[1], actual[1], "行偏移不符");
    }

    // ------------------------------------------------------------------
    // 主题
    // ------------------------------------------------------------------

    @Test
    @DisplayName("主题常量符合约定")
    void 主题常量正确() {
        assertEquals(0xFF202020, UITheme.COLOR_WINDOW_BG, "窗口底色应为 #202020");
        assertEquals("Microsoft YaHei", UITheme.FONT_FAMILY);
    }

    // ------------------------------------------------------------------
    // 素材契约（美术侧与代码侧的接口，见 docs/素材接口说明.md）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("贴图路径符合素材接口约定")
    void 贴图路径符合约定() {
        // 目录：全部落在 resources/data/textures/ 下
        assertEquals("textures/terrain/", AssetPaths.TERRAIN_DIR);
        assertEquals("textures/enemies/", AssetPaths.ENEMY_DIR);
        assertEquals("textures/operators/", AssetPaths.OPERATOR_DIR);
        assertEquals(64, AssetPaths.TILE_SIZE, "格子基准尺寸应为 64px");

        // 文件名必须与数据文件里的 id 完全对应
        assertEquals("textures/terrain/ground.png", AssetPaths.terrain(AssetPaths.TERRAIN_GROUND));
        assertEquals("textures/terrain/blue_door.png", AssetPaths.terrain("blue_door"));
        assertEquals("textures/enemies/soldier.png", AssetPaths.enemy("soldier"));
        assertEquals("textures/operators/guard_01.png", AssetPaths.operator("guard_01"));

        // 五种地形贴图名，顺序与地形枚举 0~4 一致
        assertEquals(5, AssetPaths.TERRAIN_NAMES.length);
        assertEquals("impassable", AssetPaths.TERRAIN_NAMES[0]);
        assertEquals("blue_door", AssetPaths.TERRAIN_NAMES[4]);
    }

    @Test
    @DisplayName("贴图缺失时程序仍可运行（存在性检测不抛异常）")
    void 贴图缺失不致命() {
        // 当前仓库尚未放入任何贴图，检测应返回 false 而不是抛异常——
        // 这正是「存在则用贴图，否则用色块」规则的前提。
        assertFalse(Workspace.resourceExists(AssetPaths.terrain(AssetPaths.TERRAIN_GROUND)),
                "贴图尚未交付时应返回 false");
        assertFalse(Workspace.resourceExists(AssetPaths.enemy("soldier")),
                "贴图尚未交付时应返回 false");

        // 素材说明文档必须随仓库存在，否则美术侧拿不到规范
        assertTrue(Workspace.resourceExists("textures/README.md"),
                "缺少 src/main/resources/data/textures/README.md");
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, Stage1SmokeTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * 坐标换算的本地期望值。
     *
     * <p>{@code common.util.GridMath} 属于第二阶段交付物（见第一阶段规划 §七），
     * 本阶段先用内联算式锁定契约，避免测试依赖尚未存在的类。
     */
    private static final class GridMathProbe {
        static float centerX(int col, int tileSize) {
            return col * tileSize + tileSize / 2f;
        }

        static float centerY(int row, int tileSize) {
            return row * tileSize + tileSize / 2f;
        }

        static int colAt(float pixelX, int tileSize) {
            return (int) Math.floor(pixelX / tileSize);
        }
    }
}
