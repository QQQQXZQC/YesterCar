package com.yestercar.campaigneditor.util;

import com.yestercar.campaigneditor.model.Door;
import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.runtime.pathfinding.FlowField;
import com.yestercar.runtime.pathfinding.TileMapGrid;

import java.util.ArrayList;
import java.util.List;

/**
 * 检查**红门能不能走到蓝门**。
 *
 * <h2>为什么必须有这道检查</h2>
 * 运行时每个蓝门算一张流场（{@code BattleScene.rebuildFields}），敌人按波次配置
 * 绑定目标蓝门。如果地形把红门和蓝门**隔断**了，那个蓝门的流场里红门格是
 * {@link FlowField#UNREACHABLE}，敌人拿不到任何 waypoint ——
 * <strong>表现就是原地不动、永远不死也不掉血</strong>。
 *
 * <p>这种图"看起来是好的"（有红门有蓝门有地形），玩起来才发现坏了，
 * 而且排查起来很费劲（要去看流场）。所以在**存图之前**就拦下。
 *
 * <h2>⚠️ 只按静态地形判，忽略动态障碍</h2>
 * 障碍是运行时才放的（P2.4），建图阶段不该被它影响。而且运行时对
 * "目标被围死"还有退到无障碍流场的兜底 —— 真正会让敌人傻站的，
 * 只有**静态地形隔断**这一种。
 *
 * <h2>判据是"任意一对"</h2>
 * 检查**每一道红门 × 每一道蓝门**的所有组合。理由是波次里的出怪组可以
 * 引用任意一对（{@code EnemySpawn.door} / {@code targetBlueDoor}），
 * 所以不能只查"有没有某一条路通"，而要保证**每一对都通**。
 * 上限是 2 红门 × 2 蓝门 = 4 次 Dijkstra，代价可以忽略。
 */
public final class ReachabilityChecker {

    private ReachabilityChecker() {
    }

    /**
     * 一对走不通的门。
     *
     * @param redDoorId  红门 id
     * @param redCol     红门列
     * @param redRow     红门行
     * @param blueDoorId 蓝门 id
     * @param blueCol    蓝门列
     * @param blueRow    蓝门行
     */
    public record BlockedPair(int redDoorId, int redCol, int redRow,
                              int blueDoorId, int blueCol, int blueRow) {

        /**
         * @return 给人看的描述
         */
        public String describe() {
            return "红门 #" + redDoorId + "(" + redCol + "," + redRow + ")"
                    + " 走不到 蓝门 #" + blueDoorId + "(" + blueCol + "," + blueRow + ")";
        }
    }

    /**
     * 找出所有走不通的红蓝门组合。
     *
     * <p>只算 {@code 蓝门数} 次 Dijkstra（每个蓝门一次多源流场），
     * 而不是"每对一次" —— 一张以蓝门为目标的流场里，
     * 所有红门格的距离一次性都拿到了。
     *
     * @param map 地图；{@code null} 或没有门时返回空表
     * @return 走不通的组合；全部走通则返回空表
     */
    public static List<BlockedPair> findBlockedPairs(TileMap map) {
        List<BlockedPair> blocked = new ArrayList<>();
        if (map == null || map.redDoors().isEmpty() || map.blueDoors().isEmpty()) {
            // 没有门是另一种错误（checkDoors 会报），这里不重复报
            return blocked;
        }

        TileMapGrid grid = new TileMapGrid(map);
        for (Door blue : map.blueDoors()) {
            // 以蓝门所在格为目标算一张流场 —— 与运行时的做法一致
            FlowField field = new FlowField(grid, new int[]{blue.col(), blue.row()});
            for (Door red : map.redDoors()) {
                if (!field.isReachable(red.col(), red.row())) {
                    blocked.add(new BlockedPair(red.id(), red.col(), red.row(),
                            blue.id(), blue.col(), blue.row()));
                }
            }
        }
        return blocked;
    }

    /**
     * 把走不通的组合汇成一句给人看的话。
     *
     * @param blocked {@link #findBlockedPairs} 的结果
     * @return 错误文案，例如「红门 #0(0,0) 走不到 蓝门 #0(11,7)」
     */
    public static String describe(List<BlockedPair> blocked) {
        if (blocked.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(blocked.get(0).describe());
        if (blocked.size() > 1) {
            sb.append("（还有 ").append(blocked.size() - 1).append(" 对走不通）");
        }
        return sb.toString();
    }
}
