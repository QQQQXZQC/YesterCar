package com.yestercar.common.model;

/**
 * 一个格子偏移（相对某个格子的位置）。
 *
 * <p>用来表示攻击范围。<strong>不变量</strong>：{@code dx} 是列偏移（向右为正），
 * {@code dy} 是行偏移（向下为正），与 {@code Director} 的坐标系一致。
 *
 * <p>做成 record 而不是 {@code int[]}：record 的 {@code equals/hashCode}
 * 可以直接用于 {@code Set} 去重与包含判断；{@code int[]} 的 equals 是引用比较，
 * 放进 {@code HashSet} 会全部"不相等"，是个经典陷阱。
 *
 * @param dx 列偏移
 * @param dy 行偏移
 */
public record CellOffset(int dx, int dy) {

    /**
     * 解析 JSON 里的一种写法：{@code [dx, dy]}。
     *
     * @param arr 长度为 2 的数组
     * @return 偏移
     * @throws IllegalArgumentException 长度不是 2 时
     */
    public static CellOffset of(int[] arr) {
        if (arr == null || arr.length != 2) {
            throw new IllegalArgumentException("攻击范围元素必须是 [dx, dy]");
        }
        return new CellOffset(arr[0], arr[1]);
    }

    /**
     * @return 自身格（{@code (0,0)}）
     */
    public static CellOffset self() {
        return new CellOffset(0, 0);
    }
}
