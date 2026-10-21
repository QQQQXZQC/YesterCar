package com.yestercar;

import com.yestercar.campaigneditor.model.Door;
import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.model.TileType;
import com.yestercar.campaigneditor.util.MapValidator;
import com.yestercar.campaigneditor.util.ReachabilityChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 红门 → 蓝门的通路校验。
 *
 * <h2>为什么这是错误而不是警告</h2>
 * 没有通路时敌人拿不到任何 waypoint，**表现就是原地不动、永远不死也不掉血**。
 * 这种图"看起来是好的"（有红门有蓝门有地形），玩起来才发现坏了，
 * 而且排查要去看流场。所以保存前就拦下。
 *
 * <p>但**允许明确绕过** —— 因为"红蓝门还没连通"恰恰是画到一半时最常见的状态，
 * 硬拦会让人陷入"要么画通、要么白画"的两难。界面上给的是「仍然保存」。
 */
@DisplayName("通路校验")
class ReachabilityTest {

    /** 造一张全地面的图（不含门）。 */
    private static TileMap ground(int cols, int rows) {
        TileMap map = new TileMap("t", cols, rows);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                map.setTile(col, row, TileType.GROUND);
            }
        }
        return map;
    }

    // ------------------------------------------------------------------
    // ReachabilityChecker
    // ------------------------------------------------------------------

    @Test
    @DisplayName("全开地图：红门到蓝门通畅，没有阻塞组合")
    void 通畅无阻塞() {
        TileMap map = ground(6, 4);
        map.placeRedDoor(map.nextRedDoorId(), 0, 0);
        map.placeBlueDoor(map.nextBlueDoorId(), 5, 3, 3);

        assertTrue(ReachabilityChecker.findBlockedPairs(map).isEmpty(),
                "全开图中红门必定能走到蓝门");
    }

    @Test
    @DisplayName("★ 一堵墙把地图切成两半：红门走不到蓝门")
    void 墙隔断被检出() {
        TileMap map = ground(7, 5);
        // 在第 3 列砌一堵贯通的墙
        for (int row = 0; row < 5; row++) {
            map.setTile(3, row, TileType.IMPASSABLE);
        }
        map.placeRedDoor(map.nextRedDoorId(), 0, 2);
        map.placeBlueDoor(map.nextBlueDoorId(), 6, 2, 3);

        List<ReachabilityChecker.BlockedPair> blocked =
                ReachabilityChecker.findBlockedPairs(map);

        assertEquals(1, blocked.size(), "红门 0 走不到蓝门 0，应当恰好报一对");
        assertEquals(0, blocked.get(0).redDoorId());
        assertEquals(0, blocked.get(0).blueDoorId());
        assertTrue(blocked.get(0).describe().contains("红门 #0"));
        assertTrue(blocked.get(0).describe().contains("蓝门 #0"));
    }

    @Test
    @DisplayName("墙上留一个缺口就能走通")
    void 留缺口则通() {
        TileMap map = ground(7, 5);
        for (int row = 0; row < 5; row++) {
            map.setTile(3, row, TileType.IMPASSABLE);
        }
        map.setTile(3, 2, TileType.GROUND);      // 缺口
        map.placeRedDoor(map.nextRedDoorId(), 0, 2);
        map.placeBlueDoor(map.nextBlueDoorId(), 6, 2, 3);

        assertTrue(ReachabilityChecker.findBlockedPairs(map).isEmpty(),
                "留了缺口就该走得通 —— 否则玩家会莫名其妙被拦");
    }

    @Test
    @DisplayName("⚠️ 未放置的格子算不可通行：画一半的图会被判不通")
    void 未放置格子阻断通路() {
        TileMap map = new TileMap("t", 6, 3);
        // 只画红门那半边，中间留空
        for (int col = 0; col < 2; col++) {
            for (int row = 0; row < 3; row++) {
                map.setTile(col, row, TileType.GROUND);
            }
        }
        map.placeRedDoor(map.nextRedDoorId(), 0, 1);
        map.placeBlueDoor(map.nextBlueDoorId(), 5, 1, 3);

        assertEquals(1, ReachabilityChecker.findBlockedPairs(map).size(),
                "没画的地方不该让敌人通过，所以这算不通 —— 这正是「画一半」的典型状态");
    }

    @Test
    @DisplayName("高台也阻断通路（敌人上不去）")
    void 高台阻断通路() {
        TileMap map = ground(6, 3);
        for (int row = 0; row < 3; row++) {
            map.setTile(3, row, TileType.HIGH);
        }
        map.placeRedDoor(map.nextRedDoorId(), 0, 1);
        map.placeBlueDoor(map.nextBlueDoorId(), 5, 1, 3);

        assertEquals(1, ReachabilityChecker.findBlockedPairs(map).size(),
                "高台只能放远程干员，敌人不能穿过");
    }

    @Test
    @DisplayName("★ 多门组合：只报真正不通的那几对")
    void 多门组合精确报错() {
        TileMap map = new TileMap("t", 7, 5);
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 7; col++) {
                map.setTile(col, row, TileType.GROUND);
            }
        }
        // 在第 3 列砌一堵**贯通上下**的墙 → 地图被切成左右两半。
        //
        // ⚠️ 墙必须贯通（包括最后一行）。曾经这里只砌到第 5 行、留下第 6 列，
        // 于是从右边绕得过去，"一对都不通"—— 那是算法对的，是测试写错了。
        for (int row = 0; row < 5; row++) {
            map.setTile(3, row, TileType.IMPASSABLE);
        }
        // 左半边一个红门、右半边一个红门
        map.placeRedDoor(map.nextRedDoorId(), 0, 0);
        map.placeRedDoor(map.nextRedDoorId(), 6, 4);
        // 左半边一个蓝门、右半边一个蓝门
        map.placeBlueDoor(map.nextBlueDoorId(), 0, 4, 3);
        map.placeBlueDoor(map.nextBlueDoorId(), 6, 0, 3);

        List<ReachabilityChecker.BlockedPair> blocked =
                ReachabilityChecker.findBlockedPairs(map);

        // 四种组合，只有"同侧"的两种通：
        //   红门0(0,0) 左 → 蓝门0(0,4) 左   ✓
        //   红门0(0,0) 左 → 蓝门1(6,0) 右   ✗
        //   红门1(6,4) 右 → 蓝门0(0,4) 左   ✗
        //   红门1(6,4) 右 → 蓝门1(6,0) 右   ✓
        assertEquals(2, blocked.size(), "应当恰好两对不通（跨墙的），实际: " + blocked);
        assertTrue(blocked.stream().anyMatch(b -> b.redDoorId() == 0 && b.blueDoorId() == 1),
                "红门 0（左）到蓝门 1（右）应当不通");
        assertTrue(blocked.stream().anyMatch(b -> b.redDoorId() == 1 && b.blueDoorId() == 0),
                "红门 1（右）到蓝门 0（左）应当不通");
        assertFalse(blocked.stream().anyMatch(b -> b.redDoorId() == 0 && b.blueDoorId() == 0),
                "同侧的红门 0 → 蓝门 0 是通的，不该被报成不通");
    }

    @Test
    @DisplayName("没有门时不报阻塞（那是另一种错误，别重复报）")
    void 没有门不算阻塞() {
        TileMap bare = ground(4, 4);
        assertTrue(ReachabilityChecker.findBlockedPairs(bare).isEmpty(),
                "没有门是 checkDoors 的活，这里不该插一脚");
        assertTrue(ReachabilityChecker.findBlockedPairs(null).isEmpty());
    }

    @Test
    @DisplayName("describe 把多对压成一句，且提到还有几对")
    void describe可读() {
        assertTrue(ReachabilityChecker.describe(List.of()).isEmpty());

        List<ReachabilityChecker.BlockedPair> one = List.of(
                new ReachabilityChecker.BlockedPair(0, 0, 0, 0, 5, 5));
        assertTrue(ReachabilityChecker.describe(one).contains("红门 #0(0,0)"));

        List<ReachabilityChecker.BlockedPair> two = List.of(
                new ReachabilityChecker.BlockedPair(0, 0, 0, 0, 5, 5),
                new ReachabilityChecker.BlockedPair(1, 0, 4, 1, 6, 0));
        String s = ReachabilityChecker.describe(two);
        assertTrue(s.contains("还有 1 对走不通"), "多对时应当提一句总数，实际: " + s);
    }

    // ------------------------------------------------------------------
    // 与 MapValidator 的接合
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 校验器把「没有通路」报成错误（会拦住保存）")
    void 校验器报不通为错误() {
        TileMap map = ground(7, 5);
        for (int row = 0; row < 5; row++) {
            map.setTile(3, row, TileType.IMPASSABLE);
        }
        map.placeRedDoor(map.nextRedDoorId(), 0, 2);
        map.placeBlueDoor(map.nextBlueDoorId(), 6, 2, 3);

        MapValidator.ValidationResult v = MapValidator.validate(map);
        assertFalse(v.ok(), "没有通路必须拦住");
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("没有通路")),
                "错误里应当明确说「没有通路」，实际: " + v.errors());
        assertTrue(v.summary().contains("没有通路"), "摘要也要能看出来");
    }

    @Test
    @DisplayName("★ 跳过通路检查后，同一张图就通过了（「仍然保存」走这条路）")
    void 跳过通路检查则通过() {
        TileMap map = ground(7, 5);
        for (int row = 0; row < 5; row++) {
            map.setTile(3, row, TileType.IMPASSABLE);
        }
        map.placeRedDoor(map.nextRedDoorId(), 0, 2);
        map.placeBlueDoor(map.nextBlueDoorId(), 6, 2, 3);

        MapValidator.ValidationResult v = MapValidator.validate(map, false);
        assertTrue(v.ok(), "跳过通路后就该通过 —— 界面上据此判断「要不要给：仍然保存」");
    }

    @Test
    @DisplayName("⚠️ 跳过通路检查**不能**跳过别的错误")
    void 跳过通路不放过其它错误() {
        // 空图：既没有门也没有地形
        TileMap empty = new TileMap("t", 4, 4);
        MapValidator.ValidationResult v = MapValidator.validate(empty, false);
        assertFalse(v.ok(), "空图在跳过通路后**仍然**应当被拦下");
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("空") || e.contains("红门")));
    }

    @Test
    @DisplayName("门的数据与地形不一致时，不报通路问题（避免噪声）")
    void 数据不一致时不报通路() {
        TileMap map = ground(6, 4);
        map.placeRedDoor(map.nextRedDoorId(), 0, 0);
        map.placeBlueDoor(map.nextBlueDoorId(), 5, 3, 3);
        // 手工把红门格改成地面，制造"门存在于列表但格子上不是门"的不一致
        Door red = map.redDoors().get(0);
        map.setTile(red.col(), red.row(), TileType.GROUND);

        MapValidator.ValidationResult v = MapValidator.validate(map);
        assertFalse(v.ok());
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("数据不一致")),
                "应当报数据不一致，实际: " + v.errors());
        assertFalse(v.errors().stream().anyMatch(e -> e.contains("没有通路")),
                "数据本身不一致时，「不通路」是噪声，不该一起报");
    }

    @Test
    @DisplayName("正常地图仍然通过（没有误报）")
    void 正常地图不误报() {
        TileMap map = ground(12, 8);
        map.placeRedDoor(map.nextRedDoorId(), 0, 0);
        map.placeBlueDoor(map.nextBlueDoorId(), 11, 7, 3);

        MapValidator.ValidationResult v = MapValidator.validate(map);
        assertTrue(v.ok(), "全开图不该被拦，实际: " + v.errors());
    }

    @Test
    @DisplayName("双红门双蓝门全通时通过（上限组合不误报）")
    void 双门全通不误报() {
        int n = TileMap.MAX_ROWS;          // 行数上限现在是 8
        TileMap map = ground(n + 2, n);
        map.placeRedDoor(map.nextRedDoorId(), 0, 0);
        map.placeRedDoor(map.nextRedDoorId(), 0, n - 1);
        map.placeBlueDoor(map.nextBlueDoorId(), n + 1, 0, 3);
        map.placeBlueDoor(map.nextBlueDoorId(), n + 1, n - 1, 3);

        MapValidator.ValidationResult v = MapValidator.validate(map);
        assertTrue(v.ok(), "四个门两两都通，不该报错，实际: " + v.errors());
        assertNotNull(map.redDoorById(1));
    }

    @Test
    @DisplayName("默认的 validate(map) 与 validate(map, true) 结果一致")
    void 默认重载一致() {
        TileMap map = ground(7, 5);
        for (int row = 0; row < 5; row++) {
            map.setTile(3, row, TileType.IMPASSABLE);
        }
        map.placeRedDoor(map.nextRedDoorId(), 0, 2);
        map.placeBlueDoor(map.nextBlueDoorId(), 6, 2, 3);

        assertEquals(MapValidator.validate(map).errors(),
                MapValidator.validate(map, true).errors(),
                "两个重载在「检查通路」这件事上必须一致");
    }

    @Test
    @DisplayName("null 地图会抛异常（不是悄悄返回通过）")
    void null地图抛异常() {
        assertThrows(RuntimeException.class, () -> MapValidator.validate(null));
    }
}
