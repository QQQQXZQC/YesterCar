package com.yestercar.campaigneditor.util;

import com.yestercar.campaigneditor.model.Door;
import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.model.TileType;

import java.util.ArrayList;
import java.util.List;

/**
 * 地图校验：回答「这张图能不能存 / 能不能开打」。
 *
 * <h2>两级：错误拦截、警告放行</h2>
 * <table border="1">
 *   <caption>两级反馈</caption>
 *   <tr><th></th><th>含义</th><th>保存时</th></tr>
 *   <tr><td>{@code errors}</td><td>图是坏的，运行时读不了</td><td><b>拦截</b></td></tr>
 *   <tr><td>{@code warnings}</td><td>能跑，但大概是没画完</td><td><b>放行</b>（存草稿）</td></tr>
 * </table>
 *
 * <p>⚠️ <strong>「两级」是超出策划文档的设计</strong>：策划只写了单一层级
 * （标红 / 保存时拦截 / 拒绝 / 明确提示，没有 error/warning 划分）。
 * 之所以分两级，是因为「还有 N 格未放置」这类问题**不该拦保存** ——
 * 画一半存个盘是很正常的需求，一律拦截会让"存草稿"没法做。
 *
 * <h2>与 {@code CampaignValidator} 的分工</h2>
 * 本类只校验**单张地图自身**是否自洽。跨文件的引用完整性
 * （关卡里的出怪组指向了不存在的门 ID、波次为空）属于 {@code CampaignValidator}，
 * 那是 P3 的事 —— 那时才有"战役"这个概念可谈。
 *
 * <h2>规则来源</h2>
 * <ul>
 *   <li>至少一红一蓝、门数量上限、尺寸上限 —— 策划 §10.2.1</li>
 *   <li>红门/蓝门数量不限（早期「各最多 2 道」的限制已取消）</li>
 *   <li>尺寸最大 {@value TileMap#MAX_SIZE}×{@value TileMap#MAX_SIZE}</li>
 *   <li>缺门/空图会被拒绝 —— 具体设计 §4.1.0.4</li>
 * </ul>
 */
public final class MapValidator {

    /** 红门数量**不再限制**（可任意多道）。 */
    public static final int MAX_RED_DOORS = Integer.MAX_VALUE;

    /** 蓝门数量**不再限制**（可任意多道）。 */
    public static final int MAX_BLUE_DOORS = Integer.MAX_VALUE;

    private MapValidator() {
    }

    /**
     * 校验结果。
     *
     * @param errors   错误：必须修掉才能保存
     * @param warnings 警告：可以保存，但最好看一眼
     */
    public record ValidationResult(List<String> errors, List<String> warnings) {

        /**
         * @return 是否通过（无错误）
         */
        public boolean ok() {
            return errors.isEmpty();
        }

        /**
         * @return 是否完全干净（连警告也没有）
         */
        public boolean clean() {
            return errors.isEmpty() && warnings.isEmpty();
        }

        /**
         * 一行摘要，供状态栏显示。
         *
         * <p>状态栏只有一行位置，所以这里要做取舍：
         * 有错误时报第一条错误（最要紧的那条），否则报警告数或「通过」。
         *
         * @return 简短摘要
         */
        public String summary() {
            if (!errors.isEmpty()) {
                return errors.size() == 1
                        ? errors.get(0)
                        : errors.get(0) + " 等 " + errors.size() + " 个错误";
            }
            if (!warnings.isEmpty()) {
                return warnings.size() == 1
                        ? warnings.get(0)
                        : warnings.size() + " 条提示";
            }
            return "校验通过";
        }

        /**
         * 多行详情，供弹窗显示。
         *
         * @return 每行一条；完全干净时返回「校验通过」
         */
        public String detail() {
            if (clean()) {
                return "校验通过。";
            }
            StringBuilder sb = new StringBuilder();
            if (!errors.isEmpty()) {
                sb.append("必须修掉：").append(System.lineSeparator());
                for (String e : errors) {
                    sb.append("  · ").append(e).append(System.lineSeparator());
                }
            }
            if (!warnings.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(System.lineSeparator());
                }
                sb.append("提示（可以照常保存）：").append(System.lineSeparator());
                for (String w : warnings) {
                    sb.append("  · ").append(w).append(System.lineSeparator());
                }
            }
            return sb.toString().stripTrailing();
        }
    }

    /**
     * 校验一张地图。
     *
     * @param map 地图数据
     * @return 校验结果
     */
    public static ValidationResult validate(TileMap map) {
        return validate(map, true);
    }

    /**
     * 校验一张地图，可以指定**跳过通路检查**。
     *
     * <h2>为什么需要这个开关</h2>
     * 通路检查是唯一一条"正常保存路径会拦住、但用户能明确选择绕过"的规则。
     * 理由是编辑器的现实：**"红蓝门还没连通"恰恰是画到一半时最常见的状态**，
     * 硬拦会让人陷入"要么画通、要么白画"的两难。
     * 所以界面上弹出错误的同时给一个「仍然保存」，走的就是这个重载。
     *
     * <p>⚠️ 只跳过**这一条**。门数量、空图、尺寸超限这些照旧拦住 ——
     * 那些结果不是"图不好玩"，而是"图没法运行"或"违反契约"。
     *
     * @param map                地图数据
     * @param checkReachability  {@code false} 时跳过红门→蓝门的通路检查
     * @return 校验结果
     */
    public static ValidationResult validate(TileMap map, boolean checkReachability) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        checkDoors(map, errors);
        checkTiles(map, errors, warnings);
        checkSize(map, errors, warnings);
        if (checkReachability) {
            checkReachability(map, errors);
        }

        return new ValidationResult(List.copyOf(errors), List.copyOf(warnings));
    }

    /**
     * 红门到蓝门是否有通路。
     *
     * <p>没有通路时敌人会**原地不动**（拿不到 waypoint），
     * 所以要拦下 —— 详见 {@link ReachabilityChecker}。
     */
    private static void checkReachability(TileMap map, List<String> errors) {
        // 门本身有问题时先不查通路：那种情况下算出来的"不通"是噪声
        if (map.redDoors().isEmpty() || map.blueDoors().isEmpty()) {
            return;
        }
        for (Door d : map.redDoors()) {
            if (map.tileAt(d.col(), d.row()) != TileType.RED_DOOR) {
                return;
            }
        }
        for (Door d : map.blueDoors()) {
            if (map.tileAt(d.col(), d.row()) != TileType.BLUE_DOOR) {
                return;
            }
        }

        List<ReachabilityChecker.BlockedPair> blocked = ReachabilityChecker.findBlockedPairs(map);
        if (!blocked.isEmpty()) {
            errors.add("红门和蓝门之间没有通路，" + ReachabilityChecker.describe(blocked)
                    + " —— 敌人会原地不动");
        }
    }

    /** 门的数量与存在性。 */
    private static void checkDoors(TileMap map, List<String> errors) {
        if (map.redDoors().isEmpty()) {
            errors.add("至少要有一道红门（敌人的出生点）");
        }
        if (map.blueDoors().isEmpty()) {
            errors.add("至少要有一道蓝门（防守目标）");
        }
        if (map.redDoors().size() > MAX_RED_DOORS) {
            errors.add("红门最多 " + MAX_RED_DOORS + " 道，当前 " + map.redDoors().size());
        }
        if (map.blueDoors().size() > MAX_BLUE_DOORS) {
            errors.add("蓝门最多 " + MAX_BLUE_DOORS + " 道，当前 " + map.blueDoors().size());
        }

        // 门与地形必须一致。TileMap 已经封装了联动，正常用不会不一致；
        // 但既然是"存进文件就改不掉"的东西，多查一遍不亏 —— 手工改过 JSON 的图正好能拦住。
        for (Door d : map.redDoors()) {
            if (map.tileAt(d.col(), d.row()) != TileType.RED_DOOR) {
                errors.add("红门 #" + d.id() + " 所在格 (" + d.col() + "," + d.row()
                        + ") 的地形不是红门（数据不一致）");
            }
        }
        for (Door d : map.blueDoors()) {
            if (map.tileAt(d.col(), d.row()) != TileType.BLUE_DOOR) {
                errors.add("蓝门 #" + d.id() + " 所在格 (" + d.col() + "," + d.row()
                        + ") 的地形不是蓝门（数据不一致）");
            }
            if (d.hp() < 1) {
                errors.add("蓝门 #" + d.id() + " 的生命值必须至少为 1，当前 " + d.hp());
            }
        }
    }

    /** 格子内容是否画完了。 */
    private static void checkTiles(TileMap map, List<String> errors, List<String> warnings) {
        int total = map.cols() * map.rows();
        int empty = map.countByType()[TileType.EMPTY.ordinal()];

        if (empty == total) {
            errors.add("地图还是空的，一个地块都没放");
            return;
        }
        if (empty > 0) {
            // 放行 —— 画一半存草稿是正常需求。见类注释里"两级是超出文档的设计"那段。
            warnings.add("还有 " + empty + " 格未放置（可以照常保存）");
        }
    }

    /** 尺寸是否在契约范围内。 */
    private static void checkSize(TileMap map, List<String> errors, List<String> warnings) {
        int max = TileMap.MAX_SIZE;
        if (map.cols() > max || map.rows() > max) {
            errors.add("地图尺寸超过上限 " + max + "×" + max
                    + "，当前 " + map.cols() + "×" + map.rows());
            return;
        }
        if (map.tileSize() <= 0) {
            errors.add("格子像素必须为正数，当前 " + map.tileSize());
        } else if (map.tileSize() != TileMap.DEFAULT_TILE_SIZE) {
            // 不是错误：契约允许自定义格子像素，只是渲染基准是按 64 设计的
            warnings.add("格子像素为 " + map.tileSize() + "，与默认 "
                    + TileMap.DEFAULT_TILE_SIZE + " 不同");
        }
    }
}
