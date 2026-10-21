package com.yestercar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yestercar.campaigneditor.io.MapJsonIO;
import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.common.io.CampaignLoader;
import com.yestercar.common.io.Workspace;
import com.yestercar.common.model.Campaign;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 官方战役（在 jar 内）能否被战斗运行时加载。
 *
 * <h2>这些测试防的是什么</h2>
 * 战斗运行时曾经对官方战役直接 `return` 并报
 * 「战斗运行时暂时只能加载磁盘上的自定义战役」——
 * 于是**从主菜单点官方关卡进战斗永远失败**，而编辑器里「测试」按钮
 * （产出的是自定义战役）却一切正常，非常难发现。
 *
 * <p>官方与自定义的差别只在**读取来源**（jar 资源 vs 磁盘文件），
 * 解析用的是同一个 {@link MapJsonIO#parse}。这些测试把那条资源通道钉死。
 */
class OfficialCampaignLoadTest {

    @Test
    void 至少有一个官方战役() {
        List<Campaign> official = CampaignLoader.loadOfficial();
        assertFalse(official.isEmpty(), "应当至少有一个官方战役");
    }

    @Test
    void 每个官方战役的地图资源都存在且能解析() throws Exception {
        for (Campaign c : CampaignLoader.loadAll()) {
            if (!c.readOnly()) {
                continue;      // 只看官方（jar 内）战役
            }
            String path = c.mapResourcePath();
            assertTrue(Workspace.classpathResourceExists(path),
                    c.id() + " 的地图资源不存在: " + path);

            String text = Workspace.readClasspathResourceAsString(path);
            TileMap map = MapJsonIO.parse(text, c.id());

            assertNotNull(map);
            assertTrue(map.cols() > 0 && map.rows() > 0,
                    c.id() + " 的地图尺寸非法");
            // 一个能玩的战役至少要有红门和蓝门各一道
            assertFalse(map.redDoors().isEmpty(), c.id() + " 没有红门");
            assertFalse(map.blueDoors().isEmpty(), c.id() + " 没有蓝门");
        }
    }

    @Test
    void 官方战役的地图资源路径就是战役id拼出来的() {
        for (Campaign c : CampaignLoader.loadAll()) {
            if (c.readOnly()) {
                assertEquals(Workspace.CAMPAIGNS_RESOURCE_DIR + "/" + c.id() + "/" + c.meta().map(),
                        c.mapResourcePath());
            }
        }
    }
}
