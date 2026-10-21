package com.yestercar.runtime.battle;

import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.model.TileType;
import com.yestercar.common.model.DeployType;
import com.yestercar.common.model.Operator;
import com.yestercar.common.util.Director;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署系统（策划方案 §2.5 再部署、§2.8 朝向、以及"放在哪"的合法性）。
 *
 * <h2>部署的判定顺序（顺序本身就是规则）</h2>
 * <ol>
 *   <li>编队里有没有这个人；</li>
 *   <li>再部署冷却好了没；</li>
 *   <li>这一格地形对不对（地面干员要地面、高台干员要高台）；</li>
 *   <li>这一格有没有别人；</li>
 *   <li>费用够不够。</li>
 * </ol>
 * 先判"能不能放"再判"钱够不够" —— 否则玩家会遇到"钱扣了却放不下去"。
 * 本类**只在全部通过后**才扣费。
 *
 * <h2>再部署（策划 §2.5）</h2>
 * 每个干员有独立冷却：死亡或主动撤退后开始计时，冷却期间该干员不能被再次部署。
 * 冷却只在 {@link #update(float)} 里推进，不读系统时钟（与参考实现一致：sim 不读墙钟）。
 */
final class DeploymentSystem {

    /** 部署结果。 */
    enum Result {
        /** 成功。 */
        OK,
        /** 不在编队里（或 id 不认识）。 */
        NOT_IN_SQUAD,
        /** 再部署冷却中。 */
        COOLDOWN,
        /** 地形不对（地面/高台）。 */
        WRONG_TERRAIN,
        /** 这一格已经有干员了。 */
        OCCUPIED,
        /** 超出地图。 */
        OUT_OF_BOUNDS,
        /** 费用不够。 */
        NO_COST,
        /** 这个干员已经在场上了（同一干员同时只能有一个）。 */
        ALREADY_DEPLOYED;

        /**
         * @return 给玩家看的一句话
         */
        String message() {
            return switch (this) {
                case OK -> "部署成功";
                case NOT_IN_SQUAD -> "该干员不在编队里";
                case COOLDOWN -> "再部署冷却中";
                case WRONG_TERRAIN -> "这一格不能部署该类型的干员";
                case OCCUPIED -> "这一格已经有干员了";
                case OUT_OF_BOUNDS -> "超出地图范围";
                case NO_COST -> "费用不足";
                case ALREADY_DEPLOYED -> "该干员已在场上";
            };
        }
    }

    private final TileMap map;
    private final CostSystem cost;
    private final List<Operator> squad;
    private final List<OperatorUnit> deployed = new ArrayList<>();

    /** 每个干员的再部署剩余冷却（秒）。没在表里 = 已就绪。 */
    private final Map<String, Float> cooldown = new HashMap<>();

    /**
     * @param map   地图（判定地形与边界）
     * @param cost  费用系统（扣费与返还）
     * @param squad 编队（按部署栏顺序）
     */
    DeploymentSystem(TileMap map, CostSystem cost, List<Operator> squad) {
        this.map = map;
        this.cost = cost;
        this.squad = squad == null ? List.of() : List.copyOf(squad);
    }

    /**
     * @return 编队成员
     */
    List<Operator> squad() {
        return squad;
    }

    /**
     * @return 场上干员
     */
    List<OperatorUnit> deployed() {
        return deployed;
    }

    /**
     * 某个干员还要等多久才能再部署。
     *
     * @param id 干员 id
     * @return 剩余秒数；0 表示就绪
     */
    float cooldownOf(String id) {
        Float t = cooldown.get(id);
        return t == null ? 0f : Math.max(0f, t);
    }

    /**
     * @param id 干员 id
     * @return 还能不能部署（看冷却**与**是否已在场，不看费用与地形）
     */
    boolean readyToDeploy(String id) {
        return cooldownOf(id) <= 0f && deployedOf(id) == null;
    }

    /**
     * 查某个干员是否已经在场上。
     *
     * @param id 干员 id
     * @return 在场返回该实例；不在返回 {@code null}
     */
    OperatorUnit deployedOf(String id) {
        for (OperatorUnit u : deployed) {
            if (u.alive() && u.type().id().equals(id)) {
                return u;
            }
        }
        return null;
    }

    /**
     * 推进冷却计时。
     *
     * @param dt 步长（秒）
     */
    void update(float dt) {
        if (dt <= 0f) {
            return;
        }
        cooldown.replaceAll((k, v) -> Math.max(0f, v - dt));
    }

    /**
     * 尝试部署。
     *
     * @param type   干员类型（必须在编队里）
     * @param col    列
     * @param row    行
     * @param facing 朝向
     * @return 结果；只有 {@link Result#OK} 才真的扣费并放人
     */
    Result deploy(Operator type, int col, int row, Director facing) {
        Result r = check(type, col, row);
        if (r != Result.OK) {
            return r;
        }
        cost.spend(type.cost());
        deployed.add(new OperatorUnit(type, col, row, facing));
        return Result.OK;
    }

    /**
     * **只判定不执行**：这一格现在能不能放这个干员。
     *
     * <p>为什么需要它：UI 要在鼠标悬停时显示"能放/不能放"，
     * 而 {@link #deploy} 一旦成功就会扣费并放人。
     * 试放再撤退是错的 —— 会真的扣费并触发再部署冷却（我踩过）。
     *
     * @param type 干员类型
     * @param col  列
     * @param row  行
     * @return 判定结果；{@link Result#OK} 表示能放
     */
    Result check(Operator type, int col, int row) {
        if (type == null || !squad.contains(type)) {
            return Result.NOT_IN_SQUAD;
        }
        if (deployedOf(type.id()) != null) {
            // 策划 §2.5 的再部署语义隐含了"同一干员同时只能在场一个"：
            // 只有撤退/阵亡后才会进入冷却，冷却结束才能再放。
            return Result.ALREADY_DEPLOYED;
        }
        if (!readyToDeploy(type.id())) {
            return Result.COOLDOWN;
        }
        if (!map.inBounds(col, row)) {
            return Result.OUT_OF_BOUNDS;
        }
        if (unitAt(col, row) != null) {
            return Result.OCCUPIED;
        }
        if (!terrainAccepts(type.deployType(), map.tileAt(col, row))) {
            return Result.WRONG_TERRAIN;
        }
        if (!cost.canAfford(type.cost())) {
            return Result.NO_COST;
        }
        return Result.OK;
    }

    /**
     * 撤退一个干员：返还 50% 费用并开始再部署冷却（策划 §2.4 / §2.5）。
     *
     * @param unit 要撤退的干员
     * @return 实际返还的费用
     */
    int retreat(OperatorUnit unit) {
        if (unit == null || !deployed.remove(unit)) {
            return 0;
        }
        int back = cost.refund(unit.type().cost());
        cooldown.put(unit.type().id(), (float) unit.type().redeployTime());
        return back;
    }

    /**
     * 干员死亡后的处理：不返还费用，但要开始再部署冷却。
     *
     * <p>与 {@link #retreat} 分开，因为策划 §2.4 明确"干员死亡不返还"。
     *
     * @param unit 阵亡的干员
     */
    void onDeath(OperatorUnit unit) {
        if (unit == null) {
            return;
        }
        deployed.remove(unit);
        cooldown.put(unit.type().id(), (float) unit.type().redeployTime());
    }

    /**
     * 查某格上的干员。
     *
     * @param col 列
     * @param row 行
     * @return 干员；没有返回 {@code null}
     */
    OperatorUnit unitAt(int col, int row) {
        for (OperatorUnit u : deployed) {
            if (u.alive() && u.col() == col && u.row() == row) {
                return u;
            }
        }
        return null;
    }

    /**
     * 地形是否接受该部署类型。
     *
     * @param deployType 干员部署类型
     * @param tile       格子类型
     * @return 能放返回 {@code true}
     */
    static boolean terrainAccepts(DeployType deployType, TileType tile) {
        if (tile == null) {
            return false;
        }
        return deployType == DeployType.HIGH
                ? tile.isHighDeployable()
                : tile.isGroundDeployable();
    }
}
