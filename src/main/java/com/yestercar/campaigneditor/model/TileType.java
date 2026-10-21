package com.yestercar.campaigneditor.model;

/**
 * 地形枚举，与地图契约（策划方案 §3.1）的 {@code legend} 一一对应。
 *
 * <p>这是编辑器的核心类型之一：<strong>整个地图就是一张 TileType 的二维表</strong>。
 *
 * <h2>关于「未放置」</h2>
 * {@link #EMPTY}（{@code -1}）不是"不可通行"，而是**还没画**。
 * 新建地图时整张表都是 {@code EMPTY}，用户选了地形再放下去才会变成别的值。
 * 所以它在渲染上应当明显区别于 {@link #IMPASSABLE} ——
 * 前者是"空白画布"，后者是"这里确实不能走"。
 *
 * <h2>静态可走性</h2>
 * 按策划 §7.2：地形为 {@link #GROUND}、{@link #RED_DOOR}、{@link #BLUE_DOOR} 时静态可走。
 * <strong>注意 {@link #IMPASSABLE} 与 {@link #HIGH} 都不可走</strong> ——
 * 高台只能放远程干员，敌人走不上去。
 */
public enum TileType {

    /** 未放置（{@code -1}）：新建地图的默认值，表示"这里还没画"。 */
    EMPTY(-1, "empty", "未放置"),

    /** 不可通行（{@code 0}）：墙、障碍、深渊。 */
    IMPASSABLE(0, "impassable", "不可通行"),

    /** 可通行地面（{@code 1}）：敌人与近战干员都可走。 */
    GROUND(1, "ground", "地面"),

    /** 高台（{@code 2}）：只能部署远程/医疗干员，敌人走不上去。 */
    HIGH(2, "high", "高台"),

    /** 红门（{@code 3}）：敌人出生点。 */
    RED_DOOR(3, "red_door", "红门"),

    /** 蓝门（{@code 4}）：防守目标。 */
    BLUE_DOOR(4, "blue_door", "蓝门");

    private final int code;
    private final String legendName;
    private final String displayName;

    TileType(int code, String legendName, String displayName) {
        this.code = code;
        this.legendName = legendName;
        this.displayName = displayName;
    }

    /**
     * @return 地图 JSON 里的整数值，如 {@code -1} / {@code 0} / ...
     */
    public int code() {
        return code;
    }

    /**
     * @return {@code legend} 里的英文名，如 {@code "high"}
     */
    public String legendName() {
        return legendName;
    }

    /**
     * @return 界面上显示的中文名
     */
    public String displayName() {
        return displayName;
    }

    /**
     * 地形是否静态可走（不含动态障碍）。
     *
     * <p>按策划 §7.2：地面、红门、蓝门可走；高台与不可通行不可走。
     * {@code EMPTY} 也不可走 —— 没画的地方不该让敌人通过。
     *
     * @return 可走返回 {@code true}
     */
    public boolean isWalkable() {
        return this == GROUND || this == RED_DOOR || this == BLUE_DOOR;
    }

    /**
     * 是否可以在这一格部署近战干员（需要可通行地面）。
     *
     * @return 可以返回 {@code true}
     */
    public boolean isGroundDeployable() {
        return this == GROUND;
    }

    /**
     * 是否可以在这一格部署远程干员（高台）。
     *
     * @return 可以返回 {@code true}
     */
    public boolean isHighDeployable() {
        return this == HIGH;
    }

    /**
     * 是否是门（红门或蓝门）。
     *
     * @return 是门返回 {@code true}
     */
    public boolean isDoor() {
        return this == RED_DOOR || this == BLUE_DOOR;
    }

    /**
     * 按 JSON 里的整数值反查枚举。
     *
     * @param code 地形码，如 {@code -1}
     * @return 对应枚举
     * @throws IllegalArgumentException 未知地形码
     */
    public static TileType fromCode(int code) {
        for (TileType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知地形码: " + code);
    }

    /**
     * 按 JSON 里的整数值反查枚举，未知时返回默认值而不是抛异常。
     *
     * <p>用于读取外部文件（可能被手改坏），避免整个地图加载失败。
     *
     * @param code       地形码
     * @param fallback   未知时的返回值
     * @return 对应枚举或 {@code fallback}
     */
    public static TileType fromCodeOrDefault(int code, TileType fallback) {
        for (TileType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return fallback;
    }
}
