package com.yestercar;

import com.yestercar.campaigneditor.model.Door;
import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.io.MapJsonIO;
import com.yestercar.common.io.Workspace;
import com.yestercar.runtime.battle.BattleScene;
import com.yestercar.runtime.battle.Enemy;
import com.yestercar.runtime.battle.EnemyType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用**官方战役的真实地图**验证"必须走到蓝门格才扣血"。
 *
 * <p>为什么要单独用真图：合成的小地图里蓝门格一定可走、waypoint 链一定以门格结尾；
 * 而真实地图（ms-1）的门格地形、朝向、是否靠边都可能不一样。
 * 如果门格不可走、链只到门前一格，那么把到达半径收小到 0.2 就会**永远漏不出去**
 * （敌人停在门口不消失）—— 这条用例就是防这个回归。
 */
class RealMapLeakTest {

    private static Path officialMap() {
        return Workspace.projectRoot()
                .resolve("src").resolve("main").resolve("resources")
                .resolve("campaigns").resolve("ms-1").resolve("map")
                .resolve("ms-1.json");
    }

    @Test
    @DisplayName("★ ms-1 真实地图：走到蓝门格中心才扣血")
    void 真实地图到门才扣血() throws Exception {
        TileMap map = MapJsonIO.load(officialMap());
        assertNotNull(map, "ms-1 地图应当能加载");
        BattleScene scene = new BattleScene(map);
        Map<String, EnemyType> types = EnemyType.loadAll();
        EnemyType soldier = types.get("soldier");
        assertNotNull(soldier);

        Door red = map.redDoors().get(0);
        Door blue = map.blueDoors().get(0);
        Enemy e = scene.spawn(soldier, red.id(), blue.id());
        assertNotNull(e, "应当能在红门生成敌人");

        float cx = blue.col() + 0.5f;
        float cy = blue.row() + 0.5f;
        float dt = 1f / 30f;                       // 与引擎同样的固定步长
        float distAtLeak = Float.NaN;
        float t = 0f;
        while (t < 120f && !scene.enemies().isEmpty()) {
            int before = scene.leaked();
            scene.update(dt);
            t += dt;
            if (scene.leaked() > before) {
                float dx = e.x() - cx;
                float dy = e.y() - cy;
                distAtLeak = (float) Math.sqrt(dx * dx + dy * dy);
            }
        }
        assertEquals(1, scene.leaked(), "敌人应当走到蓝门并漏出去（不能停在门口）");
        assertTrue(!Float.isNaN(distAtLeak), "应当记录到漏怪那一刻");
        assertTrue(distAtLeak < 0.25f,
                "漏怪时应当已经在蓝门格内，实际离门心 " + distAtLeak + " 格");
        assertTrue(t < 120f, "应当在超时前到达，实际 " + t + "s");
    }
}
