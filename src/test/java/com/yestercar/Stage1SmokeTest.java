package com.yestercar;

import com.yestercar.campaigneditor.EditorApp;
import com.yestercar.common.AppBoot;
import com.yestercar.common.io.Workspace;
import com.yestercar.common.model.CampaignMeta;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 骨架自检：验证「工程能构建、资源能读、坐标换算正确、可写目录能建」。
 *
 * <p>这些是纯逻辑验证，因此用 JUnit 而不是界面上的自检按钮——可重复执行、可在 CI 跑。
 * 窗口相关的验证本质上是 GUI，只能靠人工，见 {@code docs/具体设计.md} 第七部分。
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
        // 干员表按素材扩到 29 条（7 职业 × 4 + 玛恩纳）
        assertEquals(29, operatorList.size());
        // 第一条按费用升序应当是 8 费的先锋
        assertEquals("chen", operatorList.getJSONObject(0).getString("id"));
        // 每条都必须有 id/name/deployType/range
        for (int i = 0; i < operatorList.size(); i++) {
            JSONObject o = operatorList.getJSONObject(i);
            assertNotNull(o.getString("id", null), "第 " + i + " 条缺 id");
            assertNotNull(o.getString("name", null), "第 " + i + " 条缺 name");
            assertNotNull(o.getJSONObject("range"), "第 " + i + " 条缺 range");
        }
    }

    // ------------------------------------------------------------------
    // 地图契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("地图契约正确，含负数 key 与二维 tiles")
    void 地图契约正确() throws IOException {
        Path mapPath = demoMapFile();
        assertTrue(Files.exists(mapPath), "示例地图不存在: " + mapPath);

        JSONObject map = JSONObject.parse(Files.readString(mapPath, StandardCharsets.UTF_8));

        assertEquals(1, map.getInt("version"));
        assertEquals(EditorApp.DEMO_MAP_NAME, map.getString("name"));
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
        assertTrue(redDoors.size() >= 1, "样例地图至少有一道红门");
        assertTrue(blueDoors.size() >= 1, "样例地图至少有一道蓝门");

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
                Files.readString(demoMapFile(),
                        StandardCharsets.UTF_8));
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

        // ⚠️ 不再建 data/maps 与 data/levels —— 那两层在
        // 「一个战役 = 一张地图」之后废弃了（地图自包含在战役目录里）
        assertTrue(Files.isDirectory(Workspace.campaignsDir()), "data/campaigns 应已创建");
        assertTrue(Files.isDirectory(Workspace.imgsDir()), "data/imgs 应已创建");

        // 关键：路径不能依赖 Processing 的 dataPath()，必须落在工程根下的 data/
        assertEquals(Workspace.projectRoot().resolve("data"), Workspace.dataDir());
        assertTrue(Workspace.dataDir().startsWith(Workspace.projectRoot()),
                "可写数据目录必须位于工程内，实际为 " + Workspace.dataDir());

        // 地图路径一律由 CampaignMeta 推导，不再有"按名字拼"的入口
        assertEquals("map/演示地图.json", CampaignMeta.mapPathFor(EditorApp.DEMO_MAP_NAME));
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
    @DisplayName("ak-ui 色板常量符合约定")
    void 主题常量正确() {
        // 配色采用 ak-ui（明日方舟风格 UI 库）的官方色板，
        // 来源 UI/ak-ui-1.1.0/src/scss/_variables.scss 与 _tokens.scss。
        // 这些值一旦被误改，整个界面的观感就跑偏了，所以逐个锁住。
        assertEquals(0xFF4AABEA, UITheme.COLOR_PRIMARY, "primary 应为 #4aabea");
        assertEquals(0xFFF1C644, UITheme.COLOR_ADVANCED, "advanced 应为 #f1c644");
        assertEquals(0xFFF6540E, UITheme.COLOR_ACCENT, "accent 应为 #f6540e");
        assertEquals(0xFFD8DD5A, UITheme.COLOR_BASIC, "basic 应为 #d8dd5a");
        assertEquals(0xFF9C9C9C, UITheme.COLOR_LOW, "low 应为 #9c9c9c");
        assertEquals(0xFFCFC2D1, UITheme.COLOR_SECONDARY, "secondary 应为 #cfc2d1");
        assertEquals(0xFF222222, UITheme.COLOR_DARK, "dark 应为 #222222");
        assertEquals(0xFF313131, UITheme.COLOR_GRAY, "gray 应为 #313131");
        // 画面底色与主文字取自 ak-ui 的 surface-canvas / text-primary
        assertEquals(0xFF111315, UITheme.COLOR_WINDOW_BG, "画面底色应为 #111315");
        assertEquals(0xFFF8F8F5, UITheme.COLOR_TEXT, "主文字应为 #f8f8f5");

        assertEquals("Microsoft YaHei", UITheme.FONT_FAMILY);
    }

    @Test
    @DisplayName("间距与几何常量符合 ak-ui 的 4px 体系")
    void 间距常量正确() {
        assertEquals(4, UITheme.SPACE_1);
        assertEquals(8, UITheme.SPACE_2);
        assertEquals(12, UITheme.SPACE_3);
        assertEquals(16, UITheme.SPACE_4);
        assertEquals(24, UITheme.SPACE_5);
        assertEquals(32, UITheme.SPACE_6);
        assertEquals(44, UITheme.CONTROL_HEIGHT, "控件高度应为 44px");
    }

    @Test
    @DisplayName("能解析出真正可显示中文的字体族")
    void 中文字体可解析() {
        // 这条锁的是一个真实 bug：原来直接写死 "Microsoft YaHei"，
        // 但本机 AWT 的字体家族名是「微软雅黑」，英文名查不到会静默回退到 Dialog，
        // 于是 text() 画出来全是方框。
        String family = UITheme.resolveChineseFamily();
        assertNotNull(family, "必须解析出字体族名");

        // 关键：解析结果必须真的能画出中文字形，而不只是"名字看着对"
        java.awt.Font font = new java.awt.Font(family, java.awt.Font.PLAIN, 14);
        assertTrue(font.canDisplay('中'), "解析出的字体不能显示「中」: " + family);
        assertTrue(font.canDisplay('文'), "解析出的字体不能显示「文」: " + family);
        assertTrue(font.canDisplay('战'), "解析出的字体不能显示「战」: " + family);

        // 幂等：解析结果应被缓存，多次调用一致
        assertEquals(family, UITheme.resolveChineseFamily(), "解析结果应被缓存");
    }

    @Test
    @DisplayName("字体候选列表同时包含英文名与本地化中文名")
    void 字体候选列表完整() {
        // 不同机器上 Java 看到的家族名可能是英文或本地化中文，两组都要有
        assertTrue(UITheme.FONT_CANDIDATES.contains("Microsoft YaHei"), "缺英文名");
        assertTrue(UITheme.FONT_CANDIDATES.contains("微软雅黑"), "缺本地化中文名");
        assertTrue(UITheme.FONT_CANDIDATES.size() >= 8, "候选偏少，换机器容易全部落空");
    }

    // ------------------------------------------------------------------
    // 素材契约（美术侧与代码侧的接口，见 docs/具体设计.md 第五部分）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("贴图路径符合素材接口约定")
    void 贴图路径符合约定() {
        // 地形贴图放在**可写目录** data/imgs/Editor/Maps/ 下（不在 classpath 上），
        // 因为它们是编辑器用的素材，美术会直接替换文件而不用重新打包。
        assertEquals("Editor/Maps/", AssetPaths.TERRAIN_DIR);
        assertEquals("textures/enemies/", AssetPaths.ENEMY_DIR);
        assertEquals("textures/operators/", AssetPaths.OPERATOR_DIR);
        assertEquals(64, AssetPaths.TILE_SIZE, "格子基准尺寸应为 64px");

        // 地形贴图文件名必须与 TileType 的**枚举名**一致 ——
        // 这样「加一种地形」只需要在枚举里加一项、再放一张同名图
        assertEquals("GROUND.png", AssetPaths.terrainFile("GROUND"));
        assertEquals("HIGH.png", AssetPaths.terrainFile("HIGH"));
        assertEquals("IMPASSABLE.png", AssetPaths.terrainFile("IMPASSABLE"));
        assertEquals("RED_DOOR.png", AssetPaths.terrainFile("RED_DOOR"));
        assertEquals("BLUE_DOOR.png", AssetPaths.terrainFile("BLUE_DOOR"));

        // 五种地形都要有对应文件，不能漏
        for (com.yestercar.campaigneditor.model.TileType t
                : com.yestercar.campaigneditor.model.TileType.values()) {
            if (t == com.yestercar.campaigneditor.model.TileType.EMPTY) {
                continue; // 未放置没有贴图
            }
            assertEquals(t.name() + ".png", AssetPaths.terrainFile(t.name()),
                    t + " 的贴图文件名应与枚举名一致");
        }

        assertEquals("textures/enemies/soldier.png", AssetPaths.enemy("soldier"));
        assertEquals("textures/operators/chen.png", AssetPaths.operator("chen"));
    }

    @Test
    @DisplayName("地形贴图尺寸是 256×256")
    void 地形贴图尺寸正确() throws Exception {
        // 尺寸是硬约定：格子 64px，地图区最大 4× 缩放，所以贴图要 256×256 才不糊。
        // 这条会真的去读文件；素材还没放进仓库时跳过而不是失败。
        java.nio.file.Path dir = Workspace.imageFile(AssetPaths.TERRAIN_DIR);
        if (!java.nio.file.Files.isDirectory(dir)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "地形贴图目录尚不存在: " + dir);
            return;
        }

        int checked = 0;
        for (com.yestercar.campaigneditor.model.TileType t
                : com.yestercar.campaigneditor.model.TileType.values()) {
            if (t == com.yestercar.campaigneditor.model.TileType.EMPTY) {
                continue;
            }
            java.nio.file.Path file = dir.resolve(AssetPaths.terrainFile(t.name()));
            if (!java.nio.file.Files.isRegularFile(file)) {
                continue; // 缺图是允许的（会退回色块）
            }
            try (javax.imageio.stream.ImageInputStream in =
                         javax.imageio.ImageIO.createImageInputStream(file.toFile())) {
                // 注意：必须用 ImageInputStream —— 给普通 InputStream 会拿不到 reader
                javax.imageio.ImageReader reader = javax.imageio.ImageIO
                        .getImageReaders(in).next();
                reader.setInput(in);
                assertEquals(256, reader.getWidth(0), t + " 贴图宽度应为 256");
                assertEquals(256, reader.getHeight(0), t + " 贴图高度应为 256");
                reader.dispose();
            }
            checked++;
        }
        System.out.println("[测试] 实际检查了 " + checked + " 张地形贴图");
    }

    @Test
    @DisplayName("贴图缺失时程序仍可运行（存在性检测不抛异常）")
    void 贴图缺失不致命() {
        // 「存在则用贴图，否则用色块」这条规则的前提是：检测本身永远不抛异常 ——
        // 哪怕文件不存在、路径不存在、甚至目录都不存在。
        assertFalse(Workspace.resourceExists(AssetPaths.enemy("no_such_enemy")),
                "不存在的贴图应返回 false 而不是抛异常");
        assertFalse(Workspace.resourceExists(
                        AssetPaths.TERRAIN_DIR + "NO_SUCH_TILE.png"),
                "可写目录下的不存在贴图也应返回 false");
        assertFalse(Workspace.resourceExists("no/such/dir/x.png"),
                "连目录都不存在时也应返回 false");

        // 素材说明文档必须随仓库存在，否则美术侧拿不到规范
        assertTrue(Workspace.resourceExists("textures/README.md"),
                "缺少 src/main/resources/data/textures/README.md");
    }

    // ------------------------------------------------------------------
    // 战役资源目录列举（P0.2 验证结论）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("资源目录列举可用（官方战役发现依赖它）")
    void 资源目录列举可用() {
        List<String> campaigns = Workspace.listResourceChildren(
                Workspace.CAMPAIGNS_RESOURCE_DIR);

        assertTrue(campaigns.contains("official_01"), "应列出 official_01，实际: " + campaigns);
        assertTrue(campaigns.contains("official_02"), "应列出 official_02，实际: " + campaigns);

        // 关键反例：同目录下的 index.json 是「文件」，不能被当成子目录列出来。
        // 列目录的实现若偷懒做字符串前缀匹配，这里就会挂。
        assertFalse(campaigns.contains("index.json"),
                "文件不应出现在子目录列表中，实际: " + campaigns);

        // 逐层列举：jar 内的前缀匹配容易被更深的路径骗到，专门验证下一层。
        // official_01 下有 maps/ 与 levels/ 两个子目录 —— 断言"恰好这两个"，
        // 既验证直接子目录都列得出，也验证**不会把更深的路径混进来**
        // （比如里面的 .json 文件、或 map/ 自身的深层内容）。
        // official_01 下只有 map 一个子目录（单数）——
        // 「一个战役 = 一张地图」，不该再有 levels 那一层
        List<String> under01 = Workspace.listResourceChildren("campaigns/official_01");
        assertEquals(List.of("map"), under01,
                "战役目录下只应有 map（单数），实际: " + under01);

        assertTrue(Workspace.listResourceChildren("campaigns/official_01/maps").isEmpty(),
                "maps 下只有文件，没有子目录");
    }

    @Test
    @DisplayName("列举不存在的目录返回空集合而不抛异常")
    void 列举不存在的目录不抛异常() {
        // 这条很重要：资源缺失时必须能优雅降级，而不是让整个程序起不来
        assertTrue(Workspace.listResourceChildren("no/such/dir").isEmpty(),
                "不存在的目录应返回空集合");
        assertDoesNotThrow(() -> Workspace.listResourceChildren(""),
                "空前缀不应导致异常");
    }

    @Test
    @DisplayName("classpath 根资源与 data/ 资源两组 API 不串味")
    void 两组资源_API_不串味() throws IOException {
        // campaigns/ 与 data/ 平级，必须用 readClasspathResourceAsString（不加 data/ 前缀）
        String index = Workspace.readClasspathResourceAsString("campaigns/index.json");
        assertTrue(index.contains("official_01"), "索引内容不符: " + index);

        // data/ 组仍按原来的相对路径工作
        assertTrue(Workspace.readResourceAsString("enemies.json").contains("soldier"),
                "data/ 组 API 被改坏了");

        // 交叉验证：用错 API 会拼出 data/campaigns/... 从而找不到，这正是要防的坑
        assertFalse(Workspace.classpathResourceExists("data/campaigns/index.json"),
                "campaigns/ 不在 data/ 下，不该被 data/ 组 API 找到");
    }

    // ------------------------------------------------------------------
    // 双入口参数解析（P0.1）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("--editor 能被识别，且不影响其它参数")
    void 入口参数识别() {
        assertFalse(AppBoot.isEditorMode(new String[0]), "空参数不算编辑器模式");
        assertFalse(AppBoot.isEditorMode(null), "null 参数不应抛异常");
        assertTrue(AppBoot.isEditorMode(new String[]{"--editor"}));
        assertTrue(AppBoot.isEditorMode(new String[]{"--check", "--editor"}),
                "--editor 出现在后面也应识别");
        assertFalse(AppBoot.isEditorMode(new String[]{"--check"}));
    }

    @Test
    @DisplayName("转发到编辑器时必须保留 --check，只剥掉 --editor")
    void 转发参数剥离正确() {
        // 这条锁的是一个真实踩过的 bug：早期 GameMain 转发 new String[0]，
        // 把 --check 一起丢了，于是 `--check --editor` 不是自检而是真的开了编辑器窗口，
        // 命令行一直挂着不返回。
        String[] forwarded = AppBoot.stripFlags(
                new String[]{"--check", "--editor"}, AppBoot.EDITOR_FLAG);

        assertArrayEquals(new String[]{"--check"}, forwarded,
                "应剥掉 --editor 但保留 --check");
        assertTrue(AppBoot.isCheckMode(forwarded),
                "转发后编辑器入口必须仍能识别 --check");

        // 顺序颠倒也要正确
        assertArrayEquals(new String[]{"--check"},
                AppBoot.stripFlags(new String[]{"--editor", "--check"}, AppBoot.EDITOR_FLAG));

        // 边界：无可剥参数、空数组、null
        assertArrayEquals(new String[]{"--check"},
                AppBoot.stripFlags(new String[]{"--check"}, AppBoot.EDITOR_FLAG));
        assertArrayEquals(new String[0],
                AppBoot.stripFlags(new String[]{"--editor"}, AppBoot.EDITOR_FLAG));
        assertArrayEquals(new String[0], AppBoot.stripFlags(new String[0], AppBoot.EDITOR_FLAG));
        assertArrayEquals(new String[0], AppBoot.stripFlags(null, AppBoot.EDITOR_FLAG));
    }

    @Test
    @DisplayName("--check 只认第一个参数")
    void 自检模式只认首位参数() {
        assertTrue(AppBoot.isCheckMode(new String[]{"--check"}));
        assertTrue(AppBoot.isCheckMode(new String[]{"--check", "--editor"}));
        assertFalse(AppBoot.isCheckMode(new String[]{"--editor"}), "--editor 不是自检");
        assertFalse(AppBoot.isCheckMode(new String[0]));
        assertFalse(AppBoot.isCheckMode(null), "null 参数不应抛异常");
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
     * <p>{@code common.util.GridMath} 尚未实现（见 {@code docs/具体设计.md} 第 6.2 节 P1），
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
