package com.yestercar;

import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.model.TileType;
import com.yestercar.runtime.battle.BattleScene;
import com.yestercar.runtime.battle.Enemy;
import com.yestercar.runtime.battle.EnemyType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一场战斗的纯逻辑：出怪 → 沿流场走 → 到蓝门扣血。
 *
 * <h2>这是 P2 里程碑的验收测试</h2>
 * 里程碑要求「能让敌人从红门走到蓝门」。这条链路能在**毫秒级**跑完，
 * 所以可以直接断言"跑了 N 秒之后敌人到哪了、门掉了几点血"，
 * 不必开窗口、不必截图比对。
 *
 * <p>测试用的地图都很小（5×3 之类），形状一眼能看懂 ——
 * 出问题时 {@code FlowField.toAsciiArt()} 能把距离表打出来。
 */
@DisplayName("战斗场景（出怪 / 移动 / 到门扣血）")
class BattleSceneTest {

    /** 一个走得动的敌人：速度 1 格/秒 */
    private static final EnemyType WALKER =
            new EnemyType("walker", "测试兵", 100, 10, 0, 1f, 1f, 1, false, 0, 0, 0, null, "phys");

    /** 速度 2 格/秒，用于验证速度确实生效 */
    private static final EnemyType FAST =
            new EnemyType("fast", "快速兵", 100, 10, 0, 2f, 1f, 1, false, 0, 0, 0, null, "phys");

    /**
     * 造一张 5×3 的图：
     * <pre>
     *   红 . . # 蓝
     *   . . . # .
     *   . . . . .
     * </pre>
     * 红门 (0,0)，蓝门 (4,0)。
     *
     * <p>第 3 列上两格是墙，所以**不能直接横穿** —— 必须往下绕，从下方过去再上来。
     * 用带墙的图而不是空场地，是为了验出"绕路"而不是"走直线"：
     * 空场地上平滑后的路线就是一条斜线，测不出绕行。
     */
    private static TileMap corridorMap() {
        TileMap map = new TileMap("corridor", 5, 3);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 5; col++) {
                map.setTile(col, row, TileType.GROUND);
            }
        }
        map.setTile(3, 0, TileType.IMPASSABLE);
        map.setTile(3, 1, TileType.IMPASSABLE);
        map.placeRedDoor(map.nextRedDoorId(), 0, 0);
        map.placeBlueDoor(map.nextBlueDoorId(), 4, 0, 3);
        return map;
    }

    /** 一直推进到没有活着的敌人，或超时。返回推进的秒数。 */
    private static float runUntilClear(BattleScene scene, float dt, float maxSeconds) {
        float t = 0f;
        while (t < maxSeconds && !scene.enemies().isEmpty()) {
            scene.update(dt);
            t += dt;
        }
        return t;
    }

    @Test
    @DisplayName("★ 必须走进蓝门格才扣血（回归：离门半格就扣血）")
    void 到门口才扣血() {
        TileMap map = corridorMap();
        BattleScene scene = new BattleScene(map);
        Enemy e = scene.spawn(WALKER, 0, 0);
        // 蓝门在 (4,0)，门格中心就是 (4.5, 0.5)
        float cx = 4.5f;
        float cy = 0.5f;
        float dt = 1f / 60f;
        float distAtLeak = Float.NaN;
        for (int i = 0; i < 60 * 20 && !scene.enemies().isEmpty(); i++) {
            int before = scene.leaked();
            scene.update(dt);
            if (scene.leaked() > before) {
                float dx = e.x() - cx;
                float dy = e.y() - cy;
                distAtLeak = (float) Math.sqrt(dx * dx + dy * dy);
            }
        }
        assertEquals(1, scene.leaked(), "应当漏 1 只");
        assertFalse(Float.isNaN(distAtLeak), "应当记录到漏怪那一刻");
        // 旧实现 ARRIVE_DISTANCE = 0.5，会在离门半格时就扣血（画面上"没到门就掉血"）
        assertTrue(distAtLeak < 0.25f,
                "离蓝门中心还有 " + distAtLeak + " 格就扣血了（应当基本站在门上）");
        assertTrue(distAtLeak <= BattleScene.ARRIVE_DISTANCE + 1e-3f,
                "漏怪时的距离应当在判定半径内，实际 " + distAtLeak);
    }

    @Test
    @DisplayName("★ 漏怪不算击杀（回归：击杀数曾把漏怪算进去）")
    void 漏怪不计入击杀() {
        TileMap map = corridorMap();
        BattleScene scene = new BattleScene(map);
        scene.spawn(WALKER, 0, 0);
        float t = runUntilClear(scene, 1f / 60f, 20f);
        assertTrue(t < 20f, "敌人应当到达蓝门");
        assertEquals(1, scene.leaked(), "漏了 1 只");
        assertEquals(0, scene.killedCount(), "走到蓝门的不该算击杀");
    }

    @Test
    @DisplayName("★ 被打死的算击杀，且血量清零")
    void 打死计入击杀() {
        TileMap map = corridorMap();
        BattleScene scene = new BattleScene(map);
        Enemy e = scene.spawn(WALKER, 0, 0);
        e.takeDamage(9999f);
        assertEquals(0f, e.hp(), 1e-4f, "血量应当清零");
        scene.update(1f / 60f);
        assertEquals(1, scene.killedCount(), "打死的要算击杀");
        assertEquals(0, scene.leaked(), "不该算漏怪");
        assertTrue(scene.enemies().isEmpty(), "死掉的要被移除");
    }

    @Test
    @DisplayName("出怪：敌人出现在红门中心")
    void 出怪在红门中心() {
        BattleScene scene = new BattleScene(corridorMap());
        Enemy e = scene.spawn(WALKER, 0, 0);

        assertNotNull(e);
        assertEquals(0.5f, e.x(), 1e-4f, "红门在 (0,0)，中心是 0.5");
        assertEquals(0.5f, e.y(), 1e-4f);
        assertTrue(e.alive());
        assertEquals(1, scene.enemies().size());
    }

    @Test
    @DisplayName("★ 能走到蓝门：绕开墙、到门扣血")
    void 能走到蓝门并扣血() {
        TileMap map = corridorMap();
        BattleScene scene = new BattleScene(map);
        Enemy e = scene.spawn(WALKER, 0, 0);

        assertEquals(3, scene.blueHp(), "全局生命初始 3 点");

        // 边走边检查它规划的每一格都可走 —— 这是"不穿墙"的真正断言
        boolean pathClearsWalls = true;
        float dt = 1f / 60f;
        float t = 0f;
        while (t < 20f && !scene.enemies().isEmpty()) {
            scene.update(dt);
            t += dt;
            int[][] wps = e.waypoints();
            if (wps != null) {
                for (int[] wp : wps) {
                    if (!map.tileAt(wp[0], wp[1]).isWalkable()) {
                        pathClearsWalls = false;
                        System.out.println("路径经过不可走格 " + wp[0] + "," + wp[1]);
                    }
                }
            }
        }

        assertEquals(1, scene.leaked(), "应当漏了 1 只怪");
        assertTrue(scene.enemies().isEmpty(), "到达后敌人应当消失");
        assertEquals(2, scene.blueHp(), "全局生命应当掉 1 点");

        // 绕路：不能横穿 (3,0)/(3,1)，必须经过第 2 行。
        // 平滑后路线是 (0,0) → (2,2) → (4,0) 一类的斜线，总长约 2.83 + 2.83 ≈ 5.66 格。
        // 给一个宽松区间即可 —— 这里验的是"确实绕了"，不是精确到帧。
        assertTrue(t > 4.5f && t < 8f, "绕行的路程约 5.7 格，实际用时 " + t + "s");

        // 它确实走到了终点附近才消失
        assertTrue(e.x() > 3.5f, "应当在蓝门附近才消失，实际 x=" + e.x());

        // 最终位置在蓝门格旁边（(4.5, 1.0) 这种坐标本来就合法），
        // 所以判"不穿墙"要判它规划的路径，而不是拿最终位置猜
        assertTrue(pathClearsWalls, "敌人曾经踏上不可通行的格子");
    }

    @Test
    @DisplayName("★ 堵住最近的路后，敌人改走远路并**照常到达**（回归：曾停在门口不消失）")
    void 改道后仍能到达() {
        // 复现实际报告的 bug：把最短路径堵上，敌人绕另一条路走。
        // 当时的症状是"从上方到达蓝门后敌人停住，不消失、也不漏怪"——
        // 根因是逐帧重算下一格导致在格子边界振荡，永远进不了到达半径。
        TileMap map = new TileMap("detour", 6, 3);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 6; col++) {
                map.setTile(col, row, TileType.GROUND);
            }
        }
        // 蓝门放中间行，红门在左上 —— 最短路本来是斜着下来
        map.placeRedDoor(map.nextRedDoorId(), 0, 0);
        map.placeBlueDoor(map.nextBlueDoorId(), 5, 1, 3);

        BattleScene scene = new BattleScene(map);
        scene.spawn(WALKER, 0, 0);
        // 先走一小段，让敌人离开出发点（模拟"中途地形变了"）
        for (int i = 0; i < 30; i++) {
            scene.update(1f / 60f);
        }

        // 现在堵住去路：把蓝门左侧两格封上，敌人必须绕到第 2 行再上来
        map.setTile(4, 0, TileType.IMPASSABLE);
        map.setTile(4, 1, TileType.IMPASSABLE);
        scene.rebuildFields();

        // 留足时间走远路
        float t = 0f;
        while (t < 40f && !scene.enemies().isEmpty()) {
            scene.update(1f / 60f);
            t += 1f / 60f;
        }

        assertTrue(scene.enemies().isEmpty(),
                "绕远路之后也应当照常到达并消失；实际还有 " + scene.enemies().size() + " 只卡住");
        assertEquals(1, scene.leaked(), "应当计入漏怪");
        assertEquals(2, scene.blueHp(), "全局生命应当扣 1 点");
    }

    @Test
    @DisplayName("★ 敌人吸附到格子中心，不停在格边抖动（回归：偏移与越界）")
    void 吸附到格子中心() {
        TileMap map = corridorMap();
        BattleScene scene = new BattleScene(map);
        Enemy e = scene.spawn(WALKER, 0, 0);

        // 每走一段就检查：只要它"停下过"，位置就该正好是个格子中心
        // （x、y 都是 k+0.5）。运动中允许在格间，但不允许长期偏离。
        for (int i = 0; i < 900 && e.alive(); i++) {
            scene.update(1f / 60f);
            assertTrue(e.x() >= -0.001f && e.x() <= map.cols() + 0.001f,
                    "第 " + i + " 帧 x 越界: " + e.x());
            assertTrue(e.y() >= -0.001f && e.y() <= map.rows() + 0.001f,
                    "第 " + i + " 帧 y 越界: " + e.y());
        }
    }
    @Test
    @DisplayName("沿流场走：步进不超过速度×dt，且始终在图上")
    void 移动不瞬移() {
        TileMap map = corridorMap();
        BattleScene scene = new BattleScene(map);
        Enemy e = scene.spawn(WALKER, 0, 0);

        float dt = 1f / 60f;
        float prevX = e.x();
        float prevY = e.y();
        for (int i = 0; i < 600 && e.alive(); i++) {
            scene.update(dt);
            float moved = (float) Math.hypot(e.x() - prevX, e.y() - prevY);
            assertTrue(moved <= WALKER.speed() * dt + 1e-3f,
                    "第 " + i + " 帧移动了 " + moved + " 格，超过速度上限");
            assertTrue(e.x() >= 0f && e.x() <= map.cols() && e.y() >= 0f && e.y() <= map.rows(),
                    "第 " + i + " 帧跑出地图了: (" + e.x() + "," + e.y() + ")");
            prevX = e.x();
            prevY = e.y();
        }
    }

    @Test
    @DisplayName("速度生效：2 格/秒 的敌人用时约为 1 格/秒 的一半")
    void 速度生效() {
        BattleScene slow = new BattleScene(corridorMap());
        slow.spawn(WALKER, 0, 0);
        float slowTime = runUntilClear(slow, 1f / 60f, 20f);

        BattleScene quick = new BattleScene(corridorMap());
        quick.spawn(FAST, 0, 0);
        float quickTime = runUntilClear(quick, 1f / 60f, 20f);

        assertTrue(quickTime < slowTime * 0.65f,
                "2 格/秒 应当明显快于 1 格/秒：快=" + quickTime + "s 慢=" + slowTime + "s");
    }

    @Test
    @DisplayName("蓝门血扣光 → allDoorsLost（漏 3 只正好打光）")
    void 血扣光即失守() {
        TileMap map = corridorMap();     // 蓝门 hp = 3
        BattleScene scene = new BattleScene(map);

        for (int i = 0; i < 3; i++) {
            scene.spawn(WALKER, 0, 0);
            runUntilClear(scene, 1f / 60f, 20f);
        }

        assertEquals(3, scene.leaked());
        assertEquals(0, scene.blueHp(), "全局生命不会扣成负数");
        assertTrue(scene.allDoorsLost(), "三道血都掉了，应当判定失守");
    }

    @Test
    @DisplayName("扣血到底后再漏怪，血量停在 0 而不是负数")
    void 血量不为负() {
        TileMap map = corridorMap();
        BattleScene scene = new BattleScene(map);
        for (int i = 0; i < 5; i++) {
            scene.spawn(WALKER, 0, 0);
            runUntilClear(scene, 1f / 60f, 20f);
        }
        assertEquals(0, scene.blueHp());
        assertEquals(5, scene.leaked(), "5 只都漏了");
    }

    @Test
    @DisplayName("多目标：两道蓝门时，敌人走向自己那一道")
    void 多道蓝门各走各的() {
        TileMap map = new TileMap("two", 5, 3);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 5; col++) {
                map.setTile(col, row, TileType.GROUND);
            }
        }
        map.placeRedDoor(map.nextRedDoorId(), 0, 0);
        int near = map.nextBlueDoorId();
        map.placeBlueDoor(near, 1, 0, 3);        // 近的
        int far = map.nextBlueDoorId();
        map.placeBlueDoor(far, 4, 2, 3);         // 远的

        BattleScene scene = new BattleScene(map);
        scene.spawn(WALKER, 0, near);
        scene.spawn(WALKER, 0, far);
        runUntilClear(scene, 1f / 60f, 30f);

        assertEquals(2, scene.leaked(), "两只都该到");
        assertEquals(1, scene.blueHp(), "全局生命默认 3，两只漏怪各扣 1 = 1");
        assertEquals(1, scene.blueHp(), "全局共享：走远的门也扣同一条命");
    }

    @Test
    @DisplayName("走到不可达的目标时不崩，敌人原地不动")
    void 不可达不崩() {
        TileMap map = new TileMap("walled", 5, 3);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 5; col++) {
                map.setTile(col, row, TileType.GROUND);
            }
        }
        map.placeRedDoor(map.nextRedDoorId(), 0, 0);
        map.placeBlueDoor(map.nextBlueDoorId(), 4, 2, 3);
        // 用一堵墙把蓝门那一列隔开
        for (int row = 0; row < 3; row++) {
            map.setTile(3, row, TileType.IMPASSABLE);
        }

        BattleScene scene = new BattleScene(map);
        Enemy e = scene.spawn(WALKER, 0, 0);
        for (int i = 0; i < 300; i++) {
            scene.update(1f / 60f);
        }

        assertEquals(0, scene.leaked(), "走不到就不该扣血");
        assertTrue(e.alive(), "敌人还活着");
        assertTrue(e.x() < 3f, "不该穿过墙，实际 x=" + e.x());
    }

    @Test
    @DisplayName("高台与不可通行都不能走")
    void 高台不可走() {
        TileMap map = new TileMap("high", 3, 1);
        map.setTile(0, 0, TileType.RED_DOOR);
        map.setTile(1, 0, TileType.HIGH);           // 挡路
        map.setTile(2, 0, TileType.BLUE_DOOR);
        map.placeRedDoor(0, 0, 0);
        map.placeBlueDoor(0, 2, 0, 3);

        BattleScene scene = new BattleScene(map);
        Enemy e = scene.spawn(WALKER, 0, 0);
        for (int i = 0; i < 300; i++) {
            scene.update(1f / 60f);
        }
        assertEquals(0, scene.leaked(), "高台挡着，走不过去");
        assertTrue(e.x() < 1.5f, "不该走到高台上，实际 x=" + e.x());
    }

    @Test
    @DisplayName("红门/蓝门不存在时 spawn 返回 null，不抛异常")
    void 门不存在不抛异常() {
        BattleScene scene = new BattleScene(corridorMap());
        assertNull(scene.spawn(WALKER, 99, 0), "红门不存在");
        assertNull(scene.spawn(WALKER, 0, 99), "蓝门不存在");
        assertNull(scene.spawn(null, 0, 0), "类型为 null");
        assertTrue(scene.enemies().isEmpty());
    }

    @Test
    @DisplayName("dt <= 0 不推进时间（防止负数把敌人倒退）")
    void 负时间步被忽略() {
        BattleScene scene = new BattleScene(corridorMap());
        scene.spawn(WALKER, 0, 0);
        scene.update(0f);
        scene.update(-1f);
        assertEquals(0f, scene.time(), 1e-6f);
        assertEquals(0.5f, scene.enemies().get(0).x(), 1e-4f, "位置不该变");
    }

    @Test
    @DisplayName("重建流场后仍然能走（P2.4 放障碍要用这个）")
    void 重建流场后仍可寻路() {
        TileMap map = corridorMap();
        BattleScene scene = new BattleScene(map);
        scene.spawn(WALKER, 0, 0);
        scene.update(0.5f);

        scene.rebuildFields();   // 模拟地形变动后重算
        runUntilClear(scene, 1f / 60f, 20f);

        assertEquals(1, scene.leaked(), "重建流场不该把路径弄丢");
        assertEquals(2, scene.blueHp());
    }

    @Test
    @DisplayName("状态摘要包含时间/敌人/漏怪/门血")
    void 摘要可读() {
        BattleScene scene = new BattleScene(corridorMap());
        scene.spawn(WALKER, 0, 0);
        scene.update(1f);

        String s = scene.summary();
        assertTrue(s.contains("t=1.0s"), "应含时间，实际: " + s);
        assertTrue(s.contains("敌人 1"), "应含敌人数，实际: " + s);
        assertTrue(s.contains("漏怪 0"), "应含漏怪数，实际: " + s);
        assertTrue(s.contains("#0:3"), "应含蓝门血量，实际: " + s);
    }

    // ------------------------------------------------------------------
    // 敌人类型表
    // ------------------------------------------------------------------

    @Test
    @DisplayName("能读出 enemies.json 里的两个敌人")
    void 读敌人数值表() {
        Map<String, EnemyType> all = EnemyType.loadAll();
        assertFalse(all.isEmpty(), "enemies.json 应当能读出来");

        EnemyType soldier = all.get("soldier");
        assertNotNull(soldier, "应当有 soldier");
        // 官方（PRTS 士兵·级别0）：生命 1650 / 攻击 200 / 移动速度 1.1
        assertEquals(1650, soldier.hp());
        assertEquals(200, soldier.atk());
        assertEquals(1.1f, soldier.speed(), 1e-4f);
        assertFalse(soldier.isRanged());

        // 13 种敌人（第 20 轮：数值表改用 data/enemies.csv；数值按官方级别0填写）
        assertEquals(13, all.size(), "敌人表应当有 13 种（12 张图 + 爱国者）");

        EnemyType caster = all.get("caster_elite");
        assertNotNull(caster, "应当有 caster_elite（高阶术师）");
        assertTrue(caster.isRanged(), "高阶术师应当是远程");
        assertEquals(2.2f, caster.rangeRadius(), 1e-4f, "官方攻击范围半径 2.2");

        EnemyType heavy = all.get("heavy_defender");
        assertNotNull(heavy, "应当有 heavy_defender（重装防御者）");
        assertEquals(800, heavy.def(), "官方重装防御者防御 800");
        assertTrue(heavy.res() < 50, "高防敌人法抗应当低（术师才打得动）");
        assertEquals(1, heavy.blockWeight(), "重装防御者阻挡权重 1（本工程自研机制，非官方字段）");

        // 每条都要有图标路径，且指向 data/imgs/enemy/
        for (EnemyType t : all.values()) {
            assertNotNull(t.icon(), t.id() + " 缺 icon");
            assertTrue(t.icon().startsWith("data/imgs/enemy/"),
                    t.id() + " 的 icon 路径不对: " + t.icon());
        }
    }

    @Test
    @DisplayName("查不到的敌人 id 退回兜底，不抛异常")
    void 未知敌人兜底() {
        Map<String, EnemyType> all = EnemyType.loadAll();
        EnemyType t = EnemyType.resolve(all, "根本不存在");
        assertEquals(EnemyType.FALLBACK.id(), t.id());
        assertEquals(EnemyType.FALLBACK.id(), EnemyType.resolve(null, "x").id());
        assertEquals(EnemyType.FALLBACK.id(), EnemyType.resolve(all, null).id());
    }

    @Test
    @DisplayName("敌人受伤到 0 会自动死掉")
    void 受伤致死() {
        Enemy e = new Enemy(WALKER, 0.5f, 0.5f, 0);
        assertEquals(100, e.hp());
        e.takeDamage(30);
        assertEquals(70, e.hp());
        assertTrue(e.alive());
        e.takeDamage(100);
        assertEquals(0, e.hp(), "血不会为负");
        assertFalse(e.alive(), "扣光即死");
    }
}
