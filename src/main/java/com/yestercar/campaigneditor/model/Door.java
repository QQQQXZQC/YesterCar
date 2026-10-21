package com.yestercar.campaigneditor.model;

/**
 * 一道门（红门或蓝门）。
 *
 * <p>对应地图契约里 {@code redDoors} / {@code blueDoors} 数组的一个元素。
 *
 * <h2>为什么 id 要显式保存</h2>
 * 按策划 §3.1：<strong>红蓝门显式保存 ID，删除后不重用</strong>。
 * 因为关卡里的出怪组会用 {@code doorId} / {@code targetBlueDoorId} 引用它们，
 * 如果删掉一道门后新门复用了同一个 id，原先指向"已删除的门"的引用就会
 * 悄悄指向新门 —— 这种 bug 极难查。
 *
 * <p>所以分配 id 的策略是「只增不减」：维护一个 {@code nextId} 计数器，
 * 删除门时<span>不</span>回收 id。
 *
 * @param id  门的编号，从 0 开始，删除后不重用
 * @param col 列（左上角为 0）
 * @param row 行
 * @param hp  生命值；<strong>仅蓝门有意义</strong>，红门恒为 0
 */
public record Door(int id, int col, int row, int hp) {

    /**
     * 造一道红门（无生命值）。
     *
     * @param id  编号
     * @param col 列
     * @param row 行
     * @return 红门
     */
    public static Door red(int id, int col, int row) {
        return new Door(id, col, row, 0);
    }

    /**
     * 造一道蓝门。
     *
     * @param id  编号
     * @param col 列
     * @param row 行
     * @param hp  生命值，最小 1
     * @return 蓝门
     */
    public static Door blue(int id, int col, int row, int hp) {
        return new Door(id, col, row, Math.max(1, hp));
    }

    /**
     * 换一个位置，其它字段不变。
     *
     * @param newCol 新列
     * @param newRow 新行
     * @return 新的门对象
     */
    public Door movedTo(int newCol, int newRow) {
        return new Door(id, newCol, newRow, hp);
    }

    @Override
    public String toString() {
        return "Door#" + id + "(" + col + "," + row + (hp > 0 ? ", hp=" + hp : "") + ")";
    }
}
