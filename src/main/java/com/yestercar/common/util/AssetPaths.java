package com.yestercar.common.util;

/**
 * 贴图路径常量：**美术侧与代码侧的唯一契约**。
 *
 * <p>所有路径均为相对 {@code src/main/resources/data/} 的相对路径，
 * 直接传给 {@link com.yestercar.common.io.Workspace#resourceExists(String)} /
 * {@link com.yestercar.common.io.Workspace#openResource(String)}。
 *
 * <p>命名与尺寸规范见 {@code docs/具体设计.md} 第五部分——改这里之前先改那份文档，
 * 两边必须一致。
 *
 * <h2>美术规则（策划方案 §2.12）</h2>
 * <ul>
 *   <li>检测贴图文件是否存在；</li>
 *   <li>存在 → 使用贴图；</li>
 *   <li>不存在 → 使用默认色块。</li>
 * </ul>
 * 也就是说：<strong>贴图缺了游戏也能跑</strong>，美术可以随时增量交付，不必等齐。
 *
 * <h2>为什么全部用小写常量而不是 enum</h2>
 * 地形枚举（-1 / 0 / 1 / 2 / 3 / 4）属于数据契约，不在这里重复定义；
 * 本类只负责「贴图文件叫什么名字」，保持单一职责。
 */
public final class AssetPaths {

    /** 贴图根目录（相对 {@code resources/data/}）。 */
    public static final String TEXTURE_ROOT = "textures/";

    /** 地形贴图目录。 */
    public static final String TERRAIN_DIR = TEXTURE_ROOT + "terrain/";

    /** 敌人贴图目录。 */
    public static final String ENEMY_DIR = TEXTURE_ROOT + "enemies/";

    /** 干员贴图目录。 */
    public static final String OPERATOR_DIR = TEXTURE_ROOT + "operators/";

    /** 默认格子边长（像素），与地图契约的 {@code tileSize} 默认值一致。 */
    public static final int TILE_SIZE = 64;

    /** 不可通行地形贴图名。 */
    public static final String TERRAIN_IMPASSABLE = "impassable";

    /** 可通行地面贴图名。 */
    public static final String TERRAIN_GROUND = "ground";

    /** 高台贴图名。 */
    public static final String TERRAIN_HIGH = "high";

    /** 红门贴图名。 */
    public static final String TERRAIN_RED_DOOR = "red_door";

    /** 蓝门贴图名。 */
    public static final String TERRAIN_BLUE_DOOR = "blue_door";

    /** 五种地形贴图名，顺序与地形枚举 0~4 一致。 */
    public static final String[] TERRAIN_NAMES = {
            TERRAIN_IMPASSABLE,
            TERRAIN_GROUND,
            TERRAIN_HIGH,
            TERRAIN_RED_DOOR,
            TERRAIN_BLUE_DOOR
    };

    private AssetPaths() {
        // 常量类，不允许实例化
    }

    /**
     * 地形贴图路径。
     *
     * @param terrainName 地形英文名，如 {@code ground}、{@code high}
     * @return 相对 {@code resources/data/} 的路径
     */
    public static String terrain(String terrainName) {
        return TERRAIN_DIR + terrainName + ".png";
    }

    /**
     * 敌人贴图路径。
     *
     * @param enemyId 敌人 ID，取自 {@code enemies.json} 的 {@code id}，如 {@code soldier}
     * @return 相对 {@code resources/data/} 的路径
     */
    public static String enemy(String enemyId) {
        return ENEMY_DIR + enemyId + ".png";
    }

    /**
     * 干员贴图路径。
     *
     * @param operatorId 干员 ID，取自 {@code operators.json} 的 {@code id}，如 {@code guard_01}
     * @return 相对 {@code resources/data/} 的路径
     */
    public static String operator(String operatorId) {
        return OPERATOR_DIR + operatorId + ".png";
    }
}
