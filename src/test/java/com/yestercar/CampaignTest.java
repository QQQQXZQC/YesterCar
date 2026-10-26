package com.yestercar;

import com.yestercar.common.io.CampaignJsonIO;
import com.yestercar.common.io.CampaignLoader;
import com.yestercar.common.io.CampaignStore;
import com.yestercar.common.io.Workspace;
import com.yestercar.common.model.Campaign;
import com.yestercar.common.model.CampaignMeta;
import com.yestercar.common.model.LevelParams;
import com.yestercar.common.model.Wave;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3.1：{@code campaign.json} 契约、{@link CampaignLoader}、{@link CampaignMeta}。
 *
 * <h2>⚠️ 「一个战役 = 一张地图」</h2>
 * 早期版本按策划 §3.4 做了一个 {@code levels} 数组，指向 {@code levels/level_xxx.json}，
 * 再由后者指向地图。**那一层已经去掉**（产品定义：战役就是关卡地图，
 * 不存在"一个战役包含多个关卡"）。所以本测试的用例也跟着换了 ——
 * 现在验的是「地图 + 规则参数 + 波次」三块直接写在一份文件里。
 *
 * <p>测的重点是契约的硬规则与加载器的两个来源：
 * <ul>
 *   <li><b>{@code id} 与目录名严格绑定</b> —— 与地图名 ≡ 文件名同一条思路；</li>
 *   <li><b>只读来自加载来源</b>（jar 内 = 只读），<b>不写进 JSON</b>；</li>
 *   <li><b>数据不一致只记不拦</b> —— 否则主菜单里连有问题的战役都看不见。</li>
 * </ul>
 */
@DisplayName("战役契约与加载（P3.1）")
class CampaignTest {

    /** 一份完整的 campaign.json（含规则参数与两个波次）。 */
    private static final String SAMPLE = """
            {
              "version": 1,
              "id": "my_campaign",
              "name": "我的战役",
              "author": "测试",
              "description": "一句话简介",
              "map": "map/my_campaign.json",
              "initialCost": 12,
              "costPerSecond": 2,
              "obstacleCost": 4,
              "obstacleHp": 120,
              "obstacleBlock": 25,
              "obstacleCount": 8,
              "obstacleRedeployTime": 6,
              "waves": [
                { "time": 0,
                  "spawns": [
                    { "enemy": "soldier", "door": 0, "targetBlueDoor": 0, "count": 5, "interval": 1.5 }
                  ] },
                { "time": 10,
                  "spawns": [
                    { "enemy": "archer", "door": 1, "targetBlueDoor": 0, "count": 2, "interval": 2 }
                  ] }
              ]
            }
            """;

    // ------------------------------------------------------------------
    // 解析：基本字段
    // ------------------------------------------------------------------

    @Test
    @DisplayName("能解析完整的 campaign.json")
    void 解析完整文件() throws IOException {
        CampaignMeta m = CampaignJsonIO.parse(SAMPLE, "my_campaign");

        assertEquals(1, m.version());
        assertEquals("my_campaign", m.id());
        assertEquals("我的战役", m.name());
        assertEquals("测试", m.author());
        assertEquals("一句话简介", m.description());
        assertEquals("map/my_campaign.json", m.map());
    }

    @Test
    @DisplayName("★ id 以目录名为准：文件里写错会被纠正")
    void id与目录名严格绑定() throws IOException {
        // 文件里写 my_campaign，但目录叫 other_dir
        CampaignMeta m = CampaignJsonIO.parse(SAMPLE, "other_dir");

        assertEquals("other_dir", m.id(),
                "id 应以目录名为准 —— 与地图名 ≡ 文件名同一个规则");
    }

    @Test
    @DisplayName("map 字段可以指向非约定路径（契约允许自定义）")
    void map字段允许自定义() throws IOException {
        // 解析器**不强制** map 走 map/<id>.json —— 手工写的战役可以指向任意文件。
        // 编辑器"打开"时按三条线索找地图，第一条就是读这个声明（见 EditorApp.loadFrom）。
        CampaignMeta m = CampaignJsonIO.parse(
                "{ \"version\": 1, \"map\": \"assets/special.json\" }", "c");
        assertEquals("assets/special.json", m.map(),
                "声明的自定义路径要原样保留，不能被改成约定路径");
    }

    @Test
    @DisplayName("目录名为空时才退回用文件里写的 id")
    void 无目录名时退回声明值() throws IOException {
        CampaignMeta m = CampaignJsonIO.parse(SAMPLE, null);
        assertEquals("my_campaign", m.id());
        assertEquals("my_campaign", CampaignJsonIO.parse(SAMPLE, "").id());
    }

    @Test
    @DisplayName("缺字段时用默认值，不炸")
    void 缺字段宽容() throws IOException {
        CampaignMeta m = CampaignJsonIO.parse("{ \"version\": 1 }", "bare");

        assertEquals("bare", m.id(), "id 来自目录名");
        assertEquals("bare", m.name(), "name 缺失时退回 id，不该显示空白");
        assertEquals("", m.author());
        assertEquals("", m.description());
        assertEquals(CampaignMeta.DEFAULT_MAP, m.map(), "map 缺失时用默认相对路径");
        assertEquals(LevelParams.DEFAULT, m.params(), "规则参数缺失时全用默认值");
        assertTrue(m.waves().isEmpty());
        assertFalse(m.hasWaves());
    }

    @Test
    @DisplayName("版本不是 1 时仍尝试读（便于将来做迁移）")
    void 版本不符仍可读() throws IOException {
        CampaignMeta m = CampaignJsonIO.parse("{ \"version\": 99, \"id\": \"x\" }", "x");
        assertEquals(99, m.version(), "版本号原样保留，便于上层判断");
    }

    @Test
    @DisplayName("不是 JSON 时明确报错（这是真错误，不该静默）")
    void 非法JSON报错() {
        assertThrows(IOException.class, () -> CampaignJsonIO.parse("这不是 json", "x"));
        assertThrows(IOException.class, () -> CampaignJsonIO.parse("", "x"));
    }

    // ------------------------------------------------------------------
    // 解析：规则参数
    // ------------------------------------------------------------------

    @Test
    @DisplayName("规则参数逐项读对（字段名与策划 §3.2 一致）")
    void 解析规则参数() throws IOException {
        LevelParams p = CampaignJsonIO.parse(SAMPLE, "c").params();

        assertEquals(12, p.initialCost());
        assertEquals(2f, p.costPerSecond(), 1e-4f);
        assertEquals(4, p.obstacleCost());
        assertEquals(120, p.obstacleHp());
        assertEquals(25, p.obstacleBlock());
        assertEquals(8, p.obstacleCount());
        assertEquals(6f, p.obstacleRedeployTime(), 1e-4f);
    }

    @Test
    @DisplayName("规则参数只写一部分时，缺的那几项用默认值")
    void 规则参数部分缺省() throws IOException {
        LevelParams p = CampaignJsonIO.parse(
                "{ \"version\": 1, \"initialCost\": 20 }", "c").params();

        assertEquals(20, p.initialCost(), "写了的用写的");
        assertEquals(LevelParams.DEFAULT.costPerSecond(), p.costPerSecond(), 1e-4f,
                "没写的用默认值");
        assertEquals(LevelParams.DEFAULT.obstacleHp(), p.obstacleHp());
    }

    @Test
    @DisplayName("规则参数被手工改成非法值时被拉回合理范围")
    void 规则参数兜住非法值() {
        LevelParams bad = new LevelParams(-5, -1f, -3, 0, -1, -9, -2f, 3);

        assertEquals(0, bad.initialCost(), "负数费用拉回 0");
        assertEquals(0f, bad.costPerSecond(), 1e-4f);
        assertEquals(1, bad.obstacleHp(), "HP 至少 1，否则障碍一放就没了");
        assertEquals(0, bad.obstacleCount());
        assertEquals(0f, bad.obstacleRedeployTime(), 1e-4f);
    }

    // ------------------------------------------------------------------
    // 解析：波次
    // ------------------------------------------------------------------

    @Test
    @DisplayName("波次逐项读对，含引用门 ID 与出怪参数")
    void 解析波次() throws IOException {
        CampaignMeta m = CampaignJsonIO.parse(SAMPLE, "c");

        assertEquals(2, m.waves().size());
        assertEquals(7, m.totalEnemies(), "5 + 2 = 7 只");

        Wave w0 = m.waves().get(0);
        assertEquals(0f, w0.time(), 1e-4f);
        assertEquals(1, w0.spawns().size());
        Wave.EnemySpawn s0 = w0.spawns().get(0);
        assertEquals("soldier", s0.enemy());
        assertEquals(0, s0.door());
        assertEquals(0, s0.targetBlueDoor());
        assertEquals(5, s0.count());
        assertEquals(1.5f, s0.interval(), 1e-4f);

        Wave w1 = m.waves().get(1);
        assertEquals(10f, w1.time(), 1e-4f);
        assertEquals("archer", w1.spawns().get(0).enemy());
        assertEquals(1, w1.spawns().get(0).door(), "第二波从另一道红门出");
    }

    @Test
    @DisplayName("waves 为 null / 不存在 / 不是数组，一律当空表（都是有效输入）")
    void 波次空值宽容() throws IOException {
        assertTrue(CampaignJsonIO.parse(
                "{ \"version\": 1, \"waves\": null }", "c").waves().isEmpty());
        assertTrue(CampaignJsonIO.parse("{ \"version\": 1 }", "c").waves().isEmpty());
        assertTrue(CampaignJsonIO.parse(
                "{ \"version\": 1, \"waves\": 42 }", "c").waves().isEmpty(),
                "类型写错也不该让整个战役读不出来");
    }

    @Test
    @DisplayName("没有 enemy 字段的出怪组被跳过（它指向不了任何敌人）")
    void 空敌人名的出怪组被跳过() throws IOException {
        String text = """
                { "version": 1, "waves": [
                  { "time": 0, "spawns": [
                    { "door": 0, "targetBlueDoor": 0, "count": 5 },
                    { "enemy": "soldier", "door": 0, "targetBlueDoor": 0, "count": 3 }
                  ] } ] }
                """;
        CampaignMeta m = CampaignJsonIO.parse(text, "c");
        assertEquals(1, m.waves().get(0).spawns().size(), "没 enemy 的那组应当被跳过");
        assertEquals("soldier", m.waves().get(0).spawns().get(0).enemy());
    }

    @Test
    @DisplayName("波次里引用到的门 ID 能被收集（供 P4.1 校验用）")
    void 收集引用的门() throws IOException {
        CampaignMeta m = CampaignJsonIO.parse(SAMPLE, "c");
        assertEquals(List.of(0, 1), m.referencedRedDoors());
        assertEquals(List.of(0, 0), m.referencedBlueDoors());
    }

    @Test
    @DisplayName("出怪组数量至少 1、间隔为正")
    void 出怪参数兜住非法值() {
        Wave.EnemySpawn bad = new Wave.EnemySpawn("soldier", 0, 0, 0, -3f);
        assertEquals(1, bad.count(), "数量 0 没有意义，拉到 1");
        assertTrue(bad.interval() > 0f, "间隔必须为正，否则会除零");
    }

    // ------------------------------------------------------------------
    // 写盘
    // ------------------------------------------------------------------

    @Test
    @DisplayName("写盘再读回，含参数与波次全部一致")
    void 往返一致(@TempDir Path dir) throws IOException {
        CampaignMeta src = CampaignJsonIO.parse(SAMPLE, "my_campaign");
        Path file = dir.resolve("my_campaign").resolve(CampaignJsonIO.FILE_NAME);

        CampaignJsonIO.save(src, file);
        CampaignMeta back = CampaignJsonIO.load(file);

        // 文件名与 id 一致，所以 name 也应当跟着对齐到目录名
        assertEquals("my_campaign", back.id());
        assertEquals("my_campaign", back.name(),
                "「一个战役 = 一张地图」→ 显示名与目录名对齐");
        assertEquals(src.author(), back.author());
        assertEquals(src.author(), back.author());
        assertEquals(src.description(), back.description());
        assertEquals(src.map(), back.map());
        assertEquals(src.params(), back.params(), "规则参数应当完整往返");
        assertEquals(src.waves().size(), back.waves().size());
        assertEquals(src.totalEnemies(), back.totalEnemies());
        assertEquals(src.waves().get(1).spawns().get(0).enemy(),
                back.waves().get(1).spawns().get(0).enemy());
        assertEquals(src.waves().get(0).spawns().get(0).interval(),
                back.waves().get(0).spawns().get(0).interval(), 1e-4f);
    }

    @Test
    @DisplayName("★ 写盘时 id 强制对齐到目录名")
    void 写盘强制对齐id(@TempDir Path dir) throws IOException {
        CampaignMeta m = CampaignMeta.create("写错的名字", "n");
        Path file = dir.resolve("right_name").resolve(CampaignJsonIO.FILE_NAME);

        CampaignJsonIO.save(m, file);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"id\": \"right_name\""),
                "写盘时应把 id 对齐到目录名；实际内容:\n" + text);
        assertFalse(text.contains("写错的名字"), "旧 id 不该出现在文件里");
    }

    @Test
    @DisplayName("写出的是可读的多行 JSON，不是压成一行；中文不转义")
    void 输出可读() {
        String json = CampaignJsonIO.toJson(CampaignMeta.create("c1", "战役一"));

        assertTrue(json.contains("\n  \"version\""), "应当有缩进，实际:\n" + json);
        assertTrue(json.contains("\"waves\": []"), "没有波次时应当写成一行空数组");
        assertTrue(json.contains("战役一"), "中文不该被转义成 \\uXXXX");

        CampaignMeta withWave = CampaignMeta.create("c", "n").withWaves(List.of(
                new Wave(0f, List.of(new Wave.EnemySpawn("soldier", 0, 0, 3, 1f)))));
        String json2 = CampaignJsonIO.toJson(withWave);
        assertTrue(json2.contains("\"enemy\": \"soldier\""), "波次应当展开写:\n" + json2);
        assertTrue(json2.contains("\"count\": 3"));
    }

    @Test
    @DisplayName("整数值的浮点写成整数（1.0 → 1），文件更干净")
    void 浮点整数化() {
        assertEquals("1", CampaignJsonIO.num(1f));
        assertEquals("10", CampaignJsonIO.num(10f));
        assertEquals("0", CampaignJsonIO.num(0f));
        assertEquals("1.5", CampaignJsonIO.num(1.5f));
    }

    @Test
    @DisplayName("字符串里的引号与反斜杠被正确转义")
    void 转义正确(@TempDir Path dir) throws IOException {
        CampaignMeta m = new CampaignMeta(1, "c", "c",
                "作者\\反斜杠", "第一行\n第二行", CampaignMeta.DEFAULT_MAP,
                LevelParams.DEFAULT, List.of());
        Path file = dir.resolve("c").resolve(CampaignJsonIO.FILE_NAME);
        CampaignJsonIO.save(m, file);

        CampaignMeta back = CampaignJsonIO.load(file);
        // name 与目录名一致（写盘时会对齐），所以这里验的是 author / description 的转义
        assertEquals("作者\\反斜杠", back.author(), "反斜杠应当能往返");
        assertEquals("第一行\n第二行", back.description(), "换行应当能往返");
    }

    @Test
    @DisplayName("★ 名字以外的字段可以随便放引号与反斜杠（JSON 转义）")
    void 名字转义往返(@TempDir Path dir) throws IOException {
        // ⚠️ 不要拿带引号的名字当**目录名** —— Windows 文件系统不允许，
        // `Path.resolve` 会直接抛 InvalidPathException。
        // 转义是 JSON 层的事，用 author / description 验就够了。
        String trickyAuthor = "作者\"带引号\"和\\反斜杠";
        String trickyDesc = "第一行\n第二行\t带制表符";
        CampaignMeta m = new CampaignMeta(1, "esc", "转义测试",
                trickyAuthor, trickyDesc, CampaignMeta.DEFAULT_MAP,
                LevelParams.DEFAULT, List.of());
        Path file = dir.resolve("esc").resolve(CampaignJsonIO.FILE_NAME);

        CampaignJsonIO.save(m, file);
        CampaignMeta back = CampaignJsonIO.load(file);

        assertEquals(trickyAuthor, back.author(), "作者名里的引号与反斜杠应当能往返");
        assertEquals(trickyDesc, back.description(), "描述里的换行与制表符应当能往返");
        // ⚠️ name 会**对齐到目录名**（见下一条用例），所以这里不是 "转义测试"
        assertEquals("esc", back.name(), "name 会被对齐到目录名");
    }

    @Test
    @DisplayName("★ 写盘时 name 也与目录名对齐（不只是 id）")
    void 写盘name也对齐() throws IOException {
        CampaignMeta m = CampaignMeta.create("dir_name", "界面上起的名字");
        assertNotEquals("dir_name", m.name(), "造出来时 name 与 id 是不同的");

        Path dir = dir();
        Path file = dir.resolve("dir_name").resolve(CampaignJsonIO.FILE_NAME);
        CampaignJsonIO.save(m, file);

        CampaignMeta back = CampaignJsonIO.load(file);
        assertEquals("dir_name", back.id());
        assertEquals("dir_name", back.name(),
                "「一个战役 = 一张地图」→ 地图名 ≡ 文件名 ≡ 目录名 ≡ 显示名");
    }

    /** 造一个临时目录（不用 @TempDir，方便与上一条共用写法）。 */
    private static Path dir() throws IOException {
        Path p = Files.createTempDirectory("yestercar-campaign");
        p.toFile().deleteOnExit();
        return p;
    }

    @Test
    @DisplayName("目录不存在时自动创建")
    void 自动建目录(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a/b/c").resolve(CampaignJsonIO.FILE_NAME);
        CampaignJsonIO.save(CampaignMeta.create("c", "n"), file);
        assertTrue(Files.isRegularFile(file));
    }

    // ------------------------------------------------------------------
    // 加载器：官方（jar 内）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 能列出两个官方战役，且都标记为只读、没有磁盘目录")
    void 官方战役只读() {
        List<Campaign> all = CampaignLoader.loadOfficial();
        assertFalse(all.isEmpty(), "应当能列出官方战役");

        Campaign c1 = all.stream().filter(c -> c.id().equals("ms-1"))
                .findFirst().orElse(null);
        assertNotNull(c1, "应当有 ms-1");
        assertTrue(c1.readOnly(), "官方战役必须只读");
        assertTrue(c1.isOfficial());
        assertNull(c1.dir(), "官方战役在 jar 内，没有磁盘目录");
        assertNull(c1.mapFile(), "读不到磁盘路径 —— 要走资源通道");
        assertEquals("苏醒", c1.name());
        // 地图文件名绑 **id**（= 目录名），不是显示名 ——
        // 显示名带全角冒号，本来就不能当文件名
        assertEquals(CampaignMeta.mapPathFor("ms-1"), c1.mapRelativePath());
    }

    @Test
    @DisplayName("官方战役的顺序听 index.json（顺序可控 = 显示顺序）")
    void 官方顺序听索引() {
        List<Campaign> all = CampaignLoader.loadOfficial();
        List<String> ids = all.stream().map(Campaign::id).toList();
        assertTrue(ids.indexOf("ms-1") < ids.indexOf("ms-2"),
                "index.json 里 ms-1 在前，实际: " + ids);
        assertTrue(ids.size() >= 2, "至少应当有两个官方战役，实际: " + ids);
    }

    @Test
    @DisplayName("★ 官方战役的数据自洽：地图存在、有波次")
    void 官方战役数据一致() {
        for (Campaign c : CampaignLoader.loadOfficial()) {
            assertTrue(c.issues().isEmpty(),
                    "官方战役「" + c.id() + "」有数据问题: " + c.issues());
            assertTrue(c.meta().hasWaves(), "「" + c.id() + "」应当配了波次");
        }
    }

    @Test
    @DisplayName("官方地图读得到、能解析，且出怪组引用的门真实存在（跨文件一致性）")
    void 官方地图可读() throws IOException {
        for (Campaign c : CampaignLoader.loadOfficial()) {
            String resource = c.mapResourcePath();
            String text = Workspace.readClasspathResourceAsString(resource);
            assertFalse(text.isBlank(), "读不到 " + resource);

            com.yestercar.campaigneditor.model.TileMap map =
                    com.yestercar.campaigneditor.io.MapJsonIO.parse(text,
                            c.id() + "/" + c.mapRelativePath());
            assertTrue(map.cols() > 0 && map.rows() > 0, resource + " 的尺寸非法");

            for (int doorId : c.meta().referencedRedDoors()) {
                assertNotNull(map.redDoorById(doorId),
                        c.id() + " 的出怪组引用了不存在的红门 " + doorId);
            }
            for (int doorId : c.meta().referencedBlueDoors()) {
                assertNotNull(map.blueDoorById(doorId),
                        c.id() + " 的出怪组引用了不存在的蓝门 " + doorId);
            }
        }
    }

    @Test
    @DisplayName("官方战役目录下只有 campaign.json 与 map/（没有 levels、也没有复数 maps）")
    void 官方目录结构无多余层级() {
        List<String> children = Workspace.listResourceChildren("campaigns/ms-1");
        assertEquals(List.of(CampaignMeta.MAP_DIR), children,
                "只应有 map 一个子目录，实际: " + children);
        assertFalse(children.contains("levels"),
                "「一个战役 = 一张地图」，不该再有 levels 这一层");
        assertFalse(children.contains("maps"),
                "子目录是单数 map —— 一个战役只有一张地图，复数会暗示里面有很多张");
    }

    // ------------------------------------------------------------------
    // 加载器：合并
    // ------------------------------------------------------------------

    @Test
    @DisplayName("loadAll 把官方与自定义都收进来")
    void 合并加载() {
        List<Campaign> all = CampaignLoader.loadAll();
        assertTrue(all.stream().anyMatch(c -> c.id().equals("ms-1")));

        Campaign debug = CampaignLoader.findById(Workspace.DEBUG_CAMPAIGN_NAME);
        if (debug != null) {
            assertFalse(debug.readOnly(), "data/ 下的战役应当可写");
            assertNotNull(debug.dir(), "磁盘战役应当有目录");
        }
    }

    @Test
    @DisplayName("找不存在的战役返回 null，不抛异常")
    void 找不到返回null() {
        assertNull(CampaignLoader.findById("根本没有这个战役"));
        assertNull(CampaignLoader.findById(null));
        assertNull(CampaignLoader.findById(""));
    }

    @Test
    @DisplayName("凡是从 data/campaigns/ 加载的都是可写的")
    void 自定义可写() {
        for (Campaign c : CampaignLoader.loadCustom()) {
            assertFalse(c.readOnly(), c.id() + " 来自磁盘，应当可写");
            assertNotNull(c.dir());
            assertNotNull(c.mapFile());
        }
    }

    // ------------------------------------------------------------------
    // Campaign 自身
    // ------------------------------------------------------------------

    @Test
    @DisplayName("只读战役拿不到磁盘路径（写不进去）")
    void 只读战役没有目录() {
        Campaign c = Campaign.readOnlyOfficial(CampaignMeta.create("x", "n"));
        assertNull(c.dir());
        assertNull(c.mapsDir(), "官方战役没有磁盘目录");
        assertNull(c.mapFile());
        assertNull(c.resolve("a", "b"));
        assertTrue(c.readOnly());
        // 地图文件名 ≡ 战役名 —— 所以 create("x", "n") 的地图是 map/n.json
        assertEquals("campaigns/x/" + CampaignMeta.mapPathFor("n"), c.mapResourcePath());
    }

    @Test
    @DisplayName("自定义战役的路径拼得对")
    void 自定义路径正确(@TempDir Path dir) {
        Campaign c = Campaign.writable(CampaignMeta.create("c", "n"), dir);
        // 子目录是单数 map（一个战役只有一张地图）
        assertEquals(dir.resolve(CampaignMeta.MAP_DIR), c.mapsDir());
        // 地图文件名跟着战役名走 —— 战役叫 n，文件就叫 n.json
        assertEquals(dir.resolve(CampaignMeta.mapPathFor("n")), c.mapFile());
        assertEquals(CampaignMeta.mapPathFor("n"), c.mapRelativePath());
    }

    @Test
    @DisplayName("meta 的 waves 不可被外部改动")
    void 波次不可变() {
        CampaignMeta m = CampaignMeta.create("c", "n").withWaves(List.of(
                new Wave(0f, List.of())));
        assertThrows(UnsupportedOperationException.class,
                () -> m.waves().add(new Wave(1f, List.of())));
    }

    @Test
    @DisplayName("摘要里带 [只读] 标记与问题数")
    void 摘要可读() {
        Campaign ro = new Campaign(CampaignMeta.create("c", "n"), null, true, List.of("缺文件"));
        assertTrue(ro.summary().contains("[只读]"));
        assertTrue(ro.summary().contains("1 个问题"));
        assertTrue(ro.hasIssues());

        Campaign rw = Campaign.writable(CampaignMeta.create("c", "n"), Path.of("."));
        assertFalse(rw.summary().contains("[只读]"));
        assertFalse(rw.hasIssues());
    }

    // ------------------------------------------------------------------
    // Workspace 的"列文件"通道
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 能列出资源目录下的直接子文件（复制官方战役靠它）")
    void 列举资源文件可用() {
        List<String> files = Workspace.listResourceFiles(
                "campaigns/ms-1/" + CampaignMeta.MAP_DIR);
        assertEquals(List.of("ms-1.json"), files,
                "map 下应当恰好有一个地图文件（名字 ≡ id），实际: " + files);

        assertFalse(Workspace.listResourceFiles("campaigns").contains("ms-1"),
                "子目录不该出现在文件列表中");
        assertFalse(Workspace.listResourceFiles("campaigns").contains("map_001.json"),
                "更深的文件不该出现在本层文件列表中");
        assertTrue(Workspace.listResourceFiles("no/such/dir").isEmpty());
    }

    // ------------------------------------------------------------------
    // CampaignStore
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 只读战役（官方）不能删")
    void 只读战役拒绝写操作() {
        Campaign ro = Campaign.readOnlyOfficial(CampaignMeta.create("ms-1", "n"));
        assertThrows(IOException.class, () -> CampaignStore.delete(ro),
                "官方战役在 jar 内，删除必须明确失败");
    }

    @Test
    @DisplayName("战役 id 的合法性检查（含路径分隔符与保留名）")
    void id合法性检查() {
        assertNull(CampaignStore.checkId("my_campaign"), "普通名字应当合法");
        assertNull(CampaignStore.checkId("我的战役"), "中文名应当合法");

        assertNotNull(CampaignStore.checkId(null));
        assertNotNull(CampaignStore.checkId(""));
        assertNotNull(CampaignStore.checkId("   "));
        assertNotNull(CampaignStore.checkId(" 有空格"), "首尾空格应当被拒");
        assertNotNull(CampaignStore.checkId("."));
        assertNotNull(CampaignStore.checkId(".."));
        assertNotNull(CampaignStore.checkId("a/b"), "路径分隔符应当被拒");
        assertNotNull(CampaignStore.checkId("a\\b"));
        assertNotNull(CampaignStore.checkId("a:b"), "Windows 非法字符应当被拒");
    }

    @Test
    @DisplayName("列出磁盘上的自定义战役 id（与目录一一对应）")
    void 列出自定义战役() throws IOException {
        // ⚠️ 不要断言"列表里有 _debug"：那是编辑器「测试」按钮生成的东西，
        // 可能根本不存在（测试环境干净、或用户删过）。那种断言会把
        // "生成物在不在"当成契约，一跑就假失败。
        //
        // 可靠的做法是**自己造一个**，验它出现在列表里 —— 那才是契约。
        String id = "_listtest_" + System.nanoTime();
        Campaign made = CampaignStore.create(id, id);
        try {
            assertTrue(CampaignStore.listCustomIds().contains(id),
                    "自己建的战役应当出现在列表里，实际: " + CampaignStore.listCustomIds());
        } finally {
            CampaignStore.delete(made);
        }
        assertFalse(CampaignStore.listCustomIds().contains(id), "删掉后不该再列出");
    }

    // ------------------------------------------------------------------
    // 编辑器产出的战役：编辑与游玩共用同一个文件夹
    // ------------------------------------------------------------------

    /** 造一张带门的 6×4 小图。 */
    private static com.yestercar.campaigneditor.model.TileMap smallMap(String name) {
        com.yestercar.campaigneditor.model.TileMap map =
                new com.yestercar.campaigneditor.model.TileMap(name, 6, 4);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 6; col++) {
                map.setTile(col, row, com.yestercar.campaigneditor.model.TileType.GROUND);
            }
        }
        map.placeRedDoor(map.nextRedDoorId(), 0, 0);
        map.placeBlueDoor(map.nextBlueDoorId(), 5, 3, 3);
        return map;
    }

    @Test
    @DisplayName("★ 编辑器存一张图 = 一个可玩的战役（写进 data/campaigns/）")
    void 编辑器产出即可玩战役() throws IOException {
        String id = "_editortest_" + System.nanoTime();
        com.yestercar.campaigneditor.model.TileMap map = smallMap(id);

        Campaign c = CampaignStore.saveWithMap(id, id, map);
        try {
            // 落点就是主菜单的"自定义"组读的那个文件夹
            assertEquals(Workspace.campaignDir(id), c.dir());
            assertTrue(Files.isRegularFile(c.dir().resolve(CampaignJsonIO.FILE_NAME)));
            assertTrue(Files.isRegularFile(c.dir().resolve(CampaignMeta.mapPathFor(id))),
                    "地图应当写在战役目录里，且文件名叫 " + id + ".json");
            // 三个名字必须一致：文件夹名、campaign.json 的 name、地图文件名
            assertEquals(id, CampaignJsonIO.load(
                    c.dir().resolve(CampaignJsonIO.FILE_NAME)).name());
            assertFalse(Files.exists(c.dir().resolve("map/map.json")),
                    "不该再出现固定的 map.json");

            // 立刻能被加载器发现 —— 编辑与游玩共用同一个文件夹
            Campaign found = CampaignLoader.findById(id);
            assertNotNull(found, "写完应当立刻能在主菜单里看到");
            assertEquals(id, found.name());
            assertFalse(found.readOnly());

            // 地图读得回来，门还在
            com.yestercar.campaigneditor.model.TileMap back =
                    com.yestercar.campaigneditor.io.MapJsonIO.load(found.mapFile());
            assertEquals(6, back.cols());
            assertNotNull(back.redDoorById(0));
            assertNotNull(back.blueDoorById(0));
        } finally {
            CampaignStore.delete(c);
        }
        assertNull(CampaignLoader.findById(id), "清理后应当找不到了");
    }

    @Test
    @DisplayName("覆盖保存时不抹掉已有的波次与规则参数")
    void 覆盖保存保留波次() throws IOException {
        String id = "_keeptest_" + System.nanoTime();
        com.yestercar.campaigneditor.model.TileMap map = smallMap(id);

        Campaign c = CampaignStore.saveWithMap(id, id, map);
        try {
            // 手动配上波次（模拟 P4.1 的波次表单已经填过）
            Path json = c.dir().resolve(CampaignJsonIO.FILE_NAME);
            CampaignMeta withWaves = CampaignJsonIO.load(json).withWaves(List.of(
                    new Wave(0f, List.of(new Wave.EnemySpawn("soldier", 0, 0, 3, 1f)))));
            CampaignJsonIO.save(withWaves, json);

            // 再存一次地图（模拟玩家改完地图又保存）
            map.setTile(2, 2, com.yestercar.campaigneditor.model.TileType.HIGH);
            CampaignStore.saveWithMap(id, id, map);

            CampaignMeta after = CampaignJsonIO.load(json);
            assertEquals(1, after.waves().size(),
                    "重存地图不该把玩家配好的波次抹掉");
            assertEquals(3, after.totalEnemies());
        } finally {
            CampaignStore.delete(c);
        }
    }

    @Test
    @DisplayName("★ 另存为新名字会**新建**一个战役，原战役原封不动")
    void 另存为不覆盖原战役() throws IOException {
        String oldId = "_saveas_old_" + System.nanoTime();
        String newId = "_saveas_new_" + System.nanoTime();

        com.yestercar.campaigneditor.model.TileMap map = smallMap(oldId);
        Campaign original = CampaignStore.saveWithMap(oldId, oldId, map);
        Campaign made = null;
        try {
            // 记住原战役的地图内容指纹
            byte[] beforeBytes = Files.readAllBytes(
                    original.dir().resolve(CampaignMeta.mapPathFor(oldId)));

            // 「另存为」到新名字 —— 模拟编辑器里在保存框中改了名字
            made = CampaignStore.saveWithMapAt(
                    Workspace.campaignDir(newId), newId, newId, map);

            // ① 新的出现了
            assertNotNull(CampaignLoader.findById(newId), "另存为应当造出新战役");
            assertEquals(newId, made.name());
            assertTrue(Files.isRegularFile(
                            made.dir().resolve(CampaignMeta.mapPathFor(newId))),
                    "新战役的地图应当叫 " + newId + ".json");

            // ② 原的**原封不动** —— 这正是用户报的 bug：改了名字却覆盖了原来的
            assertNotNull(CampaignLoader.findById(oldId), "原战役不该消失");
            assertArrayEquals(beforeBytes, Files.readAllBytes(
                            original.dir().resolve(CampaignMeta.mapPathFor(oldId))),
                    "原战役的地图一个字节都不该被动过");
        } finally {
            CampaignStore.delete(original);
            if (made != null) {
                CampaignStore.delete(made);
            }
        }
    }

    @Test
    @DisplayName("★ 三个名字必须一致：文件夹名 / campaign.json 的 name / 地图文件名")
    void 三个名字一致() throws IOException {
        String id = "_triple_" + System.nanoTime();
        Campaign c = CampaignStore.saveWithMap(id, id, smallMap(id));
        try {
            CampaignMeta meta = CampaignJsonIO.load(
                    c.dir().resolve(CampaignJsonIO.FILE_NAME));

            assertEquals(id, c.dir().getFileName().toString(), "文件夹名");
            assertEquals(id, meta.name(), "campaign.json 的 name");
            assertEquals(id, meta.id(), "campaign.json 的 id");
            assertEquals("map/" + id + ".json", meta.map(), "地图相对路径");
            assertTrue(Files.isRegularFile(c.dir().resolve(meta.map())),
                    "声明的地图文件必须真的存在");

            // 打开后地图内部的名字也得一致，否则标题栏显示的是别的
            assertEquals(id, com.yestercar.campaigneditor.io.MapJsonIO
                    .load(c.dir().resolve(meta.map())).name());
        } finally {
            CampaignStore.delete(c);
        }
    }

    @Test
    @DisplayName("★ 改名时地图文件跟着改；但自定义的地图路径不动")
    void 改名处理地图路径() {
        // 约定路径 → 跟着改
        CampaignMeta a = CampaignMeta.create("id1", "旧名");
        assertEquals("map/旧名.json", a.map());
        assertEquals("map/新名.json", a.withName("新名").map());
        assertEquals("map/新id.json", a.withId("新id").map());

        // 自定义路径 → 一个字都不动（那是手工指定的，改了就是弄坏别人的东西）
        CampaignMeta custom = new CampaignMeta(1, "id", "名", "作者", "",
                "assets/special.json", LevelParams.DEFAULT, List.of());
        assertEquals("assets/special.json", custom.withName("换名").map(),
                "自定义地图路径不该被改名动到");
        assertEquals("assets/special.json", custom.withId("换id").map());
    }

    @Test
    @DisplayName("地图路径的约定：map/ 单数，文件名 ≡ 战役名")
    void 地图路径约定() {
        assertEquals("map", CampaignMeta.MAP_DIR, "子目录是单数 map");
        assertEquals("map/演示地图.json", CampaignMeta.mapPathFor("演示地图"));
        assertEquals("map/x.json", CampaignMeta.mapPathFor("x"));
        // 空名字兜底，别拼出 map/.json 这种
        assertEquals("map/map.json", CampaignMeta.mapPathFor(""));
        assertEquals("map/map.json", CampaignMeta.mapPathFor(null));
    }
}
