package com.yestercar;

import com.yestercar.campaigneditor.io.MapJsonIO;
import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.model.TileType;
import com.yestercar.common.io.CampaignJsonIO;
import com.yestercar.common.io.CampaignLoader;
import com.yestercar.common.io.CampaignStore;
import com.yestercar.common.io.Workspace;
import com.yestercar.common.model.Campaign;
import com.yestercar.common.model.CampaignMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 战役在磁盘上的**布局契约**，以及"各方都按同一份声明找地图"。
 *
 * <h2>这个测试是为了锁住一个真踩过的坑</h2>
 * 战役结构从 {@code maps/map_001.json} 改成 {@code map/<战役名>.json} 之后，
 * <strong>运行时还写死着旧路径</strong>：
 *
 * <pre>{@code
 * Path mapFile = dir.resolve("maps").resolve(Workspace.DEBUG_MAP_FILE_NAME);
 * }</pre>
 *
 * 于是编辑器明明存好了，一点「测试」就报"找不到地图文件" ——
 * 而且报的是 {@code maps\map_001.json} 这个**根本不该再出现的路径**。
 *
 * <p>根因不是"忘了改一个字符串"，而是**同一个约定被两处各自实现**：
 * 编辑器按自己的公式写、运行时按自己的公式读。
 * 所以真正的修法是让运行时也去读 {@code campaign.json} 的声明 ——
 * <b>写的人写什么，读的人就读什么</b>。
 *
 * <p>下面的断言把这个契约钉住：结构里出现的每一个路径，
 * 都必须来自 {@link CampaignMeta} 的约定，而不是各处手写的字面量。
 */
@DisplayName("战役目录布局契约")
class CampaignLayoutTest {

    /** 造一张带一红一蓝门的小图。 */
    private static TileMap smallMap(String name) {
        TileMap map = new TileMap(name, 6, 4);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 6; col++) {
                map.setTile(col, row, TileType.GROUND);
            }
        }
        map.placeRedDoor(map.nextRedDoorId(), 0, 0);
        map.placeBlueDoor(map.nextBlueDoorId(), 5, 3, 3);
        return map;
    }

    // ------------------------------------------------------------------
    // 布局本身
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 战役目录里恰好是 campaign.json + map/<id>.json，没有别的")
    void 目录布局符合契约() throws IOException {
        String id = "_layout_" + System.nanoTime();
        Campaign c = CampaignStore.saveWithMap(id, id, smallMap(id));
        try {
            assertEquals(java.util.List.of("campaign.json", CampaignMeta.MAP_DIR),
                    Files.list(c.dir()).map(p -> p.getFileName().toString()).sorted().toList(),
                    "战役目录里只应有两样东西：campaign.json 与 map/");

            // map/ 里恰好一个文件，名字是 <id>.json
            assertEquals(java.util.List.of(id + ".json"),
                    Files.list(c.dir().resolve(CampaignMeta.MAP_DIR))
                            .map(p -> p.getFileName().toString()).sorted().toList(),
                    "map/ 里应当恰好一个地图文件，且文件名 ≡ id");
        } finally {
            CampaignStore.delete(c);
        }
    }

    @Test
    @DisplayName("★ 子目录是单数 map，不是复数 maps")
    void 子目录是单数() {
        assertEquals("map", CampaignMeta.MAP_DIR,
                "一个战役只有一张地图，复数 maps 会暗示里面有很多张");
        assertEquals("map/演示地图.json", CampaignMeta.mapPathFor("演示地图"));
        assertFalse(CampaignMeta.mapPathFor("x").startsWith("maps/"),
                "不该再出现 maps/ 这个前缀");
    }

    @Test
    @DisplayName("★ 磁盘上不该存在名为 maps 的子目录（旧结构的残留）")
    void 没有旧的maps目录() {
        for (Campaign c : CampaignLoader.loadCustom()) {
            assertFalse(Files.isDirectory(c.dir().resolve("maps")),
                    "「" + c.id() + "」下还有旧的 maps/ 目录，说明没迁移干净");
        }
        // 官方战役资源也一样
        for (Campaign c : CampaignLoader.loadOfficial()) {
            assertFalse(Workspace.listResourceChildren(
                            Workspace.CAMPAIGNS_RESOURCE_DIR + "/" + c.id()).contains("maps"),
                    "官方战役「" + c.id() + "」下还有旧的 maps/");
        }
    }

    // ------------------------------------------------------------------
    // ★ 各方都读同一份声明
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 运行时解析出的地图路径 ≡ campaign.json 声明的路径")
    void 运行时按声明找地图() throws IOException {
        String id = "_resolve_" + System.nanoTime();
        Campaign made = CampaignStore.saveWithMap(id, id, smallMap(id));
        try {
            // 运行时走的是 CampaignLoader.findById(...).mapFile()，与编辑器同一条路
            Campaign found = CampaignLoader.findById(id);
            assertNotNull(found);

            CampaignMeta meta = CampaignJsonIO.load(
                    made.dir().resolve(CampaignJsonIO.FILE_NAME));

            assertEquals(made.dir().resolve(meta.map()), found.mapFile(),
                    "运行时用的路径必须等于 campaign.json 里声明的那个");
            assertTrue(Files.isRegularFile(found.mapFile()),
                    "按声明解析出来的路径必须真的存在 —— 否则就是「写的人写 A、读的人找 B」");
        } finally {
            CampaignStore.delete(made);
        }
    }

    @Test
    @DisplayName("★ 存盘时地图文件内部的名字会跟着战役名（打开后标题栏才不是旧名）")
    void 地图内部名字对齐() throws IOException {
        String id = "_inner_" + System.nanoTime();
        // 故意用一张名字完全不同的地图去存
        TileMap weird = smallMap("完全不相关的名字");
        Campaign c = CampaignStore.saveWithMap(id, id, weird);
        try {
            assertEquals(id, c.name(), "战役名");
            assertEquals(id, MapJsonIO.load(c.mapFile()).name(),
                    "地图内部的 name 也要对齐到战役名");
        } finally {
            CampaignStore.delete(c);
        }
    }

    @Test
    @DisplayName("★ 改名时 id/name/map 三者一起走，不留孤儿也不留错声明")
    void 改名三者一致() throws IOException {
        String base = "_rename_" + System.nanoTime();
        String renamed = base + "_new";

        Campaign before = CampaignStore.saveWithMap(base, base, smallMap(base));
        Campaign after = null;
        Path newDir = Workspace.campaignDir(renamed);
        try {
            assertTrue(Files.isRegularFile(before.dir().resolve(CampaignMeta.mapPathFor(base))));

            // 改名 = 存到**新目录**（真实流程：另存为时输入了新名字）
            after = CampaignStore.saveWithMapAt(newDir, renamed, renamed, smallMap(renamed));

            // 三个名字必须一起变成新的
            assertEquals(renamed, after.id(), "id");
            assertEquals(renamed, after.name(), "name");
            assertEquals("map/" + renamed + ".json", after.mapRelativePath(), "map");
            assertEquals(renamed, MapJsonIO.load(after.mapFile()).name(),
                    "地图内部的 name 也要是新名字");

            // ★ 声明的路径必须真的存在 —— "写的人写 A、读的人找 B" 就是在这里出问题
            assertTrue(Files.isRegularFile(after.mapFile()),
                    "声明的地图路径必须真的存在: " + after.mapRelativePath());

            // 原战役不受影响
            assertTrue(Files.isRegularFile(
                            before.dir().resolve(CampaignMeta.mapPathFor(base))),
                    "原战役的地图不该被动过");
        } finally {
            CampaignStore.delete(before);
            if (after != null) {
                CampaignStore.delete(after);
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具脚本能验的东西，测试也验一遍（防止脚本与实现脱节）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 磁盘上所有自定义战役的 id 都 ≡ 目录名、map 都存在")
    void 磁盘战役全部自洽() {
        for (Campaign c : CampaignLoader.loadCustom()) {
            assertEquals(c.dir().getFileName().toString(), c.id(),
                    c.dir() + " 的目录名与 id 不符");
            assertEquals(CampaignMeta.mapPathFor(c.id()), c.mapRelativePath(),
                    "「" + c.id() + "」的 map 字段不是约定路径");
            assertTrue(Files.isRegularFile(c.mapFile()),
                    "「" + c.id() + "」声明的地图文件不存在: " + c.mapRelativePath());
        }
    }

    @Test
    @DisplayName("★ 官方战役资源也全部自洽")
    void 官方战役自洽() throws IOException {
        for (Campaign c : CampaignLoader.loadOfficial()) {
            assertEquals(CampaignMeta.mapPathFor(c.id()), c.mapRelativePath(),
                    "官方「" + c.id() + "」的 map 字段不是约定路径");
            String resource = c.mapResourcePath();
            assertFalse(Workspace.readClasspathResourceAsString(resource).isBlank(),
                    "读不到官方地图资源: " + resource);
        }
    }

    @Test
    @DisplayName("官方战役的显示名允许与 id 不同（含全角冒号，不能当文件名）")
    void 官方显示名可以含非法文件名字符() {
        Campaign c1 = CampaignLoader.loadOfficial().stream()
                .filter(c -> c.id().equals("ms-1"))
                .findFirst().orElse(null);
        assertNotNull(c1, "应当有 ms-1");

        assertEquals("ms-1", c1.id(), "id 必须是合法目录名");
        // 显示名与目录名解耦：现在官方关卡叫 MS-1，id 仍是 ms-1
        assertNotEquals(c1.id(), c1.name(),
                "显示名可以与 id 不同，实际: " + c1.name());
        assertFalse(CampaignMeta.mapPathFor(c1.id()).contains("："),
                "但地图文件名绑 id，不该含全角冒号");
    }

    @Test
    @DisplayName("data/maps/ 已不再是任何路径的来源（不再被读）")
    void 旧地图目录已弃用() {
        // 旧结构下地图散在 data/maps/ 下，现在战役自包含。
        // 判断依据是"每个战役的地图都在它自己目录里"，
        // 而不是去指认某个具体路径 —— 那样才不会被目录布局变化弄成假失败。
        for (Campaign c : CampaignLoader.loadAll()) {
            if (c.dir() == null) {
                continue;
            }
            assertTrue(c.mapFile().startsWith(c.dir()),
                    "「" + c.id() + "」的地图不在自己的战役目录里: " + c.mapFile());
        }
        assertNull(CampaignLoader.findById("__不存在的战役__"));
    }
}
