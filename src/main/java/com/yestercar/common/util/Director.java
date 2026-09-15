package com.yestercar.common.util;

/**
 * 四方向枚举。
 *
 * <p>用于干员部署朝向、敌人朝向与攻击范围旋转（策划方案 §2.8 / §2.9）。
 * 坐标系约定：{@code col} 向右递增，{@code row} 向下递增，左上角为 {@code (0,0)}。
 */
public enum Director {

    /** 上：行号减小。 */
    UP(0, -1),

    /** 下：行号增大。 */
    DOWN(0, 1),

    /** 左：列号减小。 */
    LEFT(-1, 0),

    /** 右：列号增大。 */
    RIGHT(1, 0);

    private final int dx;
    private final int dy;

    Director(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }

    /**
     * @return 列方向的单位增量
     */
    public int dx() {
        return dx;
    }

    /**
     * @return 行方向的单位增量
     */
    public int dy() {
        return dy;
    }

    /**
     * 顺时针旋转 90°。
     *
     * @return 旋转后的朝向
     */
    public Director clockwise() {
        return switch (this) {
            case UP -> RIGHT;
            case RIGHT -> DOWN;
            case DOWN -> LEFT;
            case LEFT -> UP;
        };
    }

    /**
     * 逆时针旋转 90°。
     *
     * @return 旋转后的朝向
     */
    public Director counterClockwise() {
        return switch (this) {
            case UP -> LEFT;
            case LEFT -> DOWN;
            case DOWN -> RIGHT;
            case RIGHT -> UP;
        };
    }

    /**
     * @return 与当前朝向相反的朝向
     */
    public Director opposite() {
        return switch (this) {
            case UP -> DOWN;
            case DOWN -> UP;
            case LEFT -> RIGHT;
            case RIGHT -> LEFT;
        };
    }

    /**
     * 把「朝右为基准」的相对偏移旋转到当前朝向。
     *
     * <p>策划方案 §3.3 的攻击范围一律以 {@code right} 为基准书写，
     * 本方法负责把基准坐标换算成实际朝向下的世界坐标偏移。
     *
     * @param baseDx 朝右时的列偏移
     * @param baseDy 朝右时的行偏移
     * @return 长度为 2 的数组，{@code [0]} 为列偏移，{@code [1]} 为行偏移
     */
    public int[] rotate(int baseDx, int baseDy) {
        return switch (this) {
            case RIGHT -> new int[]{baseDx, baseDy};
            // 顺时针 90°：(x, y) -> (-y, x)
            case DOWN -> new int[]{-baseDy, baseDx};
            case LEFT -> new int[]{-baseDx, -baseDy};
            // 逆时针 90°：(x, y) -> (y, -x)
            case UP -> new int[]{baseDy, -baseDx};
        };
    }

    /**
     * 由像素或格子位移推断朝向，用于敌人「朝向随移动方向变化」。
     *
     * <p>当两轴位移都非零时优先取列方向，保证结果确定。
     *
     * @param dx 列方向位移
     * @param dy 行方向位移
     * @return 推断出的朝向；{@code dx} 与 {@code dy} 同时为 0 时返回 {@link #RIGHT}
     */
    public static Director fromDelta(int dx, int dy) {
        if (dx == 0 && dy == 0) {
            return RIGHT;
        }
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx >= 0 ? RIGHT : LEFT;
        }
        return dy >= 0 ? DOWN : UP;
    }
}
