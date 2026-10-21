package com.yestercar.campaigneditor.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 地图数据：一张 {@link TileType} 二维表 + 红蓝门列表。
 *
 * <p>对应地图契约（策划方案 §3.1）的内存表示，是编辑器的核心数据模型。
 *
 * <h2>坐标系与默认值</h2>
 * 左上角为 {@code (0, 0)}，{@code col} 向右、{@code row} 向下。
 * <strong>新建地图时所有格子都是 {@link TileType#EMPTY}（未放置）</strong>，
 * 用户选了地形再放下去才会变成地面/高台/不可通行等。
 *
 * <h2>门与地形的联动</h2>
 * 门不是独立于地形表的另一套东西 —— 放一道红门<strong>同时</strong>要把那一格设成
 * {@link TileType#RED_DOOR}。本类把这层联动封装在 {@link #placeDoor} /
 * {@link #removeDoorAt} 里，避免调用方漏掉一半、导致「有门但地形还是地面」这种矛盾状态。
 */
public final class TileMap {

    /** 地图契约版本。 */
    public static final int VERSION = 1;

    /**
     * 最大列数/行数。
     *
     * <p>策划 §2.1 原本写的是 25，后来按实际需要下调到 **15**。
     * 下调的理由：一屏之内能看清的地图就这么多，
     * 25×25 在编辑器里要缩到看不清格子，在战斗里也超出可读范围。
     *
     * <p>⚠️ 校验与载入都读这个常量，所以改它一处即可全局生效。
     */
    public static final int MAX_SIZE = 15;

    /** 行数上限（编辑器画布最多 8 行）。 */
    public static final int MAX_ROWS = 8;

    /** 格子默认边长（像素）。 */
    public static final int DEFAULT_TILE_SIZE = 64;

    private String name;
    private int cols;
    private int rows;
    private int tileSize;

    /** {@code tiles[row][col]}，注意第一个下标是行。 */
    private TileType[][] tiles;

    private final List<Door> redDoors = new ArrayList<>();
    private final List<Door> blueDoors = new ArrayList<>();

    /**
     * 下一个可用的红门 id，<strong>只增不减</strong>。
     *
     * <p>⚠️ 这个计数器<strong>不能从门列表反推</strong>。曾经的实现是
     * <pre>return max(现有 id) + 1;</pre>
     * 看着像"只增不减"，其实有洞：**删掉 id 最大的那道门之后，下一个 id 就会复用它的编号**。
     * 比如 {0,1} 删掉 1 → 下一个又是 1。
     *
     * <p>这会破坏地图契约「红蓝门显式保存 ID，删除后不重用」：波次里指向"已删除的门 #1"的引用
     * 会悄悄指向新门。而且<strong>撤销功能会天天触发它</strong>（放置 → 撤销 → 放置）。
     *
     * <p>所以改成存一个独立的计数器，只在放置时前进，删除时不动它。
     */
    private int nextRedId;

    /** 下一个可用的蓝门 id，语义同 {@link #nextRedId}。 */
    private int nextBlueId;

    /**
     * 造一张全新的空地图，所有格子都是 {@link TileType#EMPTY}。
     *
     * @param name     地图名，如 {@code map_001}
     * @param cols     列数，1 ~ {@value #MAX_SIZE}
     * @param rows     行数，1 ~ {@value #MAX_SIZE}
     * @param tileSize 格子边长（像素）
     * @throws IllegalArgumentException 尺寸越界
     */
    public TileMap(String name, int cols, int rows, int tileSize) {
        this.name = Objects.requireNonNull(name, "name");
        if (cols < 1 || cols > MAX_SIZE || rows < 1 || rows > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "地图列数 1~" + MAX_SIZE + "、行数 1~" + MAX_ROWS
                            + "，实际 " + cols + "x" + rows);
        }
        this.cols = cols;
        this.rows = rows;
        this.tileSize = tileSize > 0 ? tileSize : DEFAULT_TILE_SIZE;
        this.tiles = new TileType[rows][cols];
        this.nextRedId = 0;
        this.nextBlueId = 0;
        clearAll();
    }

    /**
     * 造一张空地图，用默认格子尺寸。
     *
     * @param name 地图名
     * @param cols 列数
     * @param rows 行数
     */
    public TileMap(String name, int cols, int rows) {
        this(name, cols, rows, DEFAULT_TILE_SIZE);
    }

    // ------------------------------------------------------------------
    // 基本属性
    // ------------------------------------------------------------------

    /** @return 地图名 */
    public String name() {
        return name;
    }

    /**
     * 改名。
     *
     * @param newName 新名字
     */
    public void setName(String newName) {
        this.name = Objects.requireNonNull(newName, "newName");
    }

    /** @return 列数 */
    public int cols() {
        return cols;
    }

    /** @return 行数 */
    public int rows() {
        return rows;
    }

    /** @return 格子边长（像素） */
    public int tileSize() {
        return tileSize;
    }

    /**
     * 改格子边长（只影响渲染，不影响格子坐标）。
     *
     * @param newTileSize 新边长，最小 1
     */
    public void setTileSize(int newTileSize) {
        this.tileSize = Math.max(1, newTileSize);
    }

    // ------------------------------------------------------------------
    // 地形读写
    // ------------------------------------------------------------------

    /**
     * 读一格地形。越界返回 {@link TileType#EMPTY}，不抛异常。
     *
     * <p>越界返回空而不是抛异常，是为了让渲染循环可以放心地画到边界外一圈。
     *
     * @param col 列
     * @param row 行
     * @return 地形；越界时为 {@code EMPTY}
     */
    public TileType tileAt(int col, int row) {
        if (!inBounds(col, row)) {
            return TileType.EMPTY;
        }
        return tiles[row][col];
    }

    /**
     * 写一格地形。
     *
     * <p><strong>不处理门</strong> —— 若把一格设成 {@code RED_DOOR} / {@code BLUE_DOOR}，
     * 门列表不会自动更新。要放门请用 {@link #placeDoor}。
     *
     * @param col   列
     * @param row   行
     * @param type  地形，{@code null} 视为 {@code EMPTY}
     * @return 是否真的改变了（越界或值相同返回 {@code false}）
     */
    public boolean setTile(int col, int row, TileType type) {
        if (!inBounds(col, row)) {
            return false;
        }
        TileType value = type == null ? TileType.EMPTY : type;
        if (tiles[row][col] == value) {
            return false;
        }
        tiles[row][col] = value;
        return true;
    }

    /**
     * 把所有格子重置为未放置，并清空门。
     */
    public void clearAll() {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                tiles[row][col] = TileType.EMPTY;
            }
        }
        redDoors.clear();
        blueDoors.clear();
    }

    /**
     * @param col 列
     * @param row 行
     * @return 是否在地图范围内
     */
    public boolean inBounds(int col, int row) {
        return col >= 0 && col < cols && row >= 0 && row < rows;
    }

    // ------------------------------------------------------------------
    // 门的读写
    // ------------------------------------------------------------------

    /** @return 红门列表（不可变视图） */
    public List<Door> redDoors() {
        return Collections.unmodifiableList(redDoors);
    }

    /** @return 蓝门列表（不可变视图） */
    public List<Door> blueDoors() {
        return Collections.unmodifiableList(blueDoors);
    }

    /**
     * 取下一个可用的红门 id（<strong>不推进计数器</strong>）。
     *
     * <p>真正推进计数器的是 {@link #placeRedDoor}。这样设计是为了让「取了 id 但最终没放」
     * 不会白白吃掉一个编号。
     *
     * @return 可用的新 id
     */
    public int nextRedDoorId() {
        return nextRedId;
    }

    /**
     * 取下一个可用的蓝门 id（<strong>不推进计数器</strong>）。
     *
     * @return 可用的新 id
     */
    public int nextBlueDoorId() {
        return nextBlueId;
    }

    /**
     * 放一道红门，并把该格地形设为 {@link TileType#RED_DOOR}。
     *
     * <p>该格已有门会被替换掉。
     *
     * @param id  门 id
     * @param col 列
     * @param row 行
     */
    public void placeRedDoor(int id, int col, int row) {
        placeDoor(redDoors, Door.red(id, col, row), TileType.RED_DOOR);
        nextRedId = Math.max(nextRedId, id + 1);
    }

    /**
     * 放一道蓝门，并把该格地形设为 {@link TileType#BLUE_DOOR}。
     *
     * @param id  门 id
     * @param col 列
     * @param row 行
     * @param hp  生命值
     */
    public void placeBlueDoor(int id, int col, int row, int hp) {
        placeDoor(blueDoors, Door.blue(id, col, row, hp), TileType.BLUE_DOOR);
        nextBlueId = Math.max(nextBlueId, id + 1);
    }

    /**
     * 放门的公共实现：先移除同格的旧门，再加入，最后设地形。
     *
     * <p>三件事必须一起做，否则会出现「门列表里有、地形表里没有」这种矛盾状态。
     */
    private void placeDoor(List<Door> doors, Door door, TileType type) {
        if (!inBounds(door.col(), door.row())) {
            return;
        }
        // 同一格上的旧门要去掉（红蓝互斥，同色也不允许重叠）
        removeDoorAt(door.col(), door.row());
        doors.add(door);
        tiles[door.row()][door.col()] = type;
    }

    /**
     * 移除某一格上的门（红门或蓝门），并把地形恢复为未放置。
     *
     * @param col 列
     * @param row 行
     * @return 被移除的门；该格没有门时返回 {@code null}
     */
    public Door removeDoorAt(int col, int row) {
        Door removed = findAndRemove(redDoors, col, row);
        if (removed == null) {
            removed = findAndRemove(blueDoors, col, row);
        }
        if (removed != null && inBounds(col, row)) {
            tiles[row][col] = TileType.EMPTY;
        }
        return removed;
    }

    private static Door findAndRemove(List<Door> doors, int col, int row) {
        for (int i = 0; i < doors.size(); i++) {
            Door d = doors.get(i);
            if (d.col() == col && d.row() == row) {
                doors.remove(i);
                return d;
            }
        }
        return null;
    }

    /**
     * 按 id 查红门。
     *
     * @param id 门 id
     * @return 门；不存在返回 {@code null}
     */
    public Door redDoorById(int id) {
        return byId(redDoors, id);
    }

    /**
     * 按 id 查蓝门。
     *
     * @param id 门 id
     * @return 门；不存在返回 {@code null}
     */
    public Door blueDoorById(int id) {
        return byId(blueDoors, id);
    }

    private static Door byId(List<Door> doors, int id) {
        for (Door d : doors) {
            if (d.id() == id) {
                return d;
            }
        }
        return null;
    }

    /**
     * 给蓝门扣血（漏怪时调用）。
     *
     * <p>{@link Door} 是不可变 record，所以是**替换列表里的那一个**，而不是改字段。
     * 这与 {@code TileMap} 其它修改方法一致：所有改动都走列表，快照才抓得全。
     *
     * <p>生命值不会低于 0 —— 归零即失败，再扣也没有意义，
     * 而且负血会让「还有几条命」的显示变得奇怪。
     *
     * @param id     蓝门 id
     * @param damage 扣多少（正数）；非正数时什么都不做
     * @return 扣血后的门；该 id 不是蓝门时返回 {@code null}
     */
    public Door damageBlueDoor(int id, int damage) {
        if (damage <= 0) {
            return blueDoorById(id);
        }
        for (int i = 0; i < blueDoors.size(); i++) {
            Door d = blueDoors.get(i);
            if (d.id() == id) {
                Door hurt = new Door(d.id(), d.col(), d.row(), Math.max(0, d.hp() - damage));
                blueDoors.set(i, hurt);
                return hurt;
            }
        }
        return null;
    }

    /**
     * 给蓝门设置生命值（编辑器改数值时用）。
     *
     * @param id 蓝门 id
     * @param hp 新生命值，最小 0
     * @return 改后的门；该 id 不是蓝门时返回 {@code null}
     */
    public Door setBlueDoorHp(int id, int hp) {
        for (int i = 0; i < blueDoors.size(); i++) {
            Door d = blueDoors.get(i);
            if (d.id() == id) {
                Door changed = new Door(d.id(), d.col(), d.row(), Math.max(0, hp));
                blueDoors.set(i, changed);
                return changed;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 尺寸变更
    // ------------------------------------------------------------------

    /**
     * 改地图尺寸，**保留重叠区域的内容**。
     *
     * <p>这是策划 §6.2「三条联动规则」的第 3 条：改尺寸时越界的门与地形要
     * 裁剪或明确提示，<strong>不能静默丢数据</strong>。
     * 本方法负责"裁剪"这一半：把还在范围内的内容搬过去，范围外的门丢弃，
     * 并返回被丢弃的门数量，供调用方提示用户。
     *
     * @param newCols 新列数
     * @param newRows 新行数
     * @return 被裁剪掉的门数量（红门 + 蓝门）
     * @throws IllegalArgumentException 尺寸越界
     */
    public int resize(int newCols, int newRows) {
        if (newCols < 1 || newCols > MAX_SIZE || newRows < 1 || newRows > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "地图尺寸必须在 1~" + MAX_SIZE + " 之间，实际 " + newCols + "x" + newRows);
        }

        TileType[][] newTiles = new TileType[newRows][newCols];
        for (int row = 0; row < newRows; row++) {
            for (int col = 0; col < newCols; col++) {
                newTiles[row][col] = (row < rows && col < cols)
                        ? tiles[row][col] : TileType.EMPTY;
            }
        }
        this.tiles = newTiles;
        this.cols = newCols;
        this.rows = newRows;

        // 越界的门要裁掉，并数出来
        int dropped = 0;
        dropped += trim(redDoors, newCols, newRows);
        dropped += trim(blueDoors, newCols, newRows);
        return dropped;
    }

    /**
     * 裁掉越界的门，并<strong>同时清掉它们留下的地形</strong>。
     *
     * <p>第二件事容易漏：门被裁了但 {@code tiles} 里还留着 {@code RED_DOOR} /
     * {@code BLUE_DOOR}，就出现「地形说是门、门列表里却没有」的孤儿格 ——
     * 运行时读这张图会拿到一个指向不存在的门的格子。
     *
     * <p>注意必须在 {@link #tiles} 已经被换成新数组**之后**才清，
     * 否则清的是旧数组。
     */
    private int trim(List<Door> doors, int maxCols, int maxRows) {
        int dropped = 0;
        for (int i = doors.size() - 1; i >= 0; i--) {
            Door d = doors.get(i);
            if (d.col() >= maxCols || d.row() >= maxRows) {
                doors.remove(i);
                dropped++;
                if (inBounds(d.col(), d.row())) {
                    tiles[d.row()][d.col()] = TileType.EMPTY;
                }
            }
        }
        return dropped;
    }

    // ------------------------------------------------------------------
    // 快照（撤销栈用）
    // ------------------------------------------------------------------

    /**
     * 地图的一份不可变快照。
     *
     * <p>撤销栈用的就是它：每次编辑提交时存一份，撤销时整份还原。
     *
     * <h2>为什么快照也存 id 计数器</h2>
     * {@code nextRedId} / {@code nextBlueId} 是"只增不减"的计数器，
     * 但**撤销必须把它们一起还原** ——
     * 否则「放置门 #1 → 撤销 → 再放置」会拿到 id 2，而门列表里只有一道 id=2 的门，
     * 用户会觉得编号莫名其妙跳了。而「重做」又必须拿回原来的 #1。
     *
     * <p>所以计数器属于<b>可还原状态的一部分</b>，不是全局单调的。
     * 它"只增不减"的语义是**在历史之内**：同一条时间线上，删掉门不会让编号回收。
     *
     * @param name        地图名
     * @param cols        列数
     * @param rows        行数
     * @param tileSize    格子边长
     * @param tiles       {@code tiles[row][col]} 的深拷贝
     * @param redDoors    红门列表的拷贝
     * @param blueDoors   蓝门列表的拷贝
     * @param nextRedId   下一个红门 id
     * @param nextBlueId  下一个蓝门 id
     */
    public record Snapshot(String name, int cols, int rows, int tileSize,
                           TileType[][] tiles,
                           List<Door> redDoors, List<Door> blueDoors,
                           int nextRedId, int nextBlueId) {

        /**
         * 深拷贝一份，避免调用方之后改动地图影响到快照。
         *
         * @return 独立的快照
         */
        public Snapshot copy() {
            TileType[][] copy = new TileType[tiles.length][];
            for (int i = 0; i < tiles.length; i++) {
                copy[i] = tiles[i].clone();
            }
            return new Snapshot(name, cols, rows, tileSize, copy,
                    new ArrayList<>(redDoors), new ArrayList<>(blueDoors),
                    nextRedId, nextBlueId);
        }
    }

    /**
     * 取当前状态的一份快照。
     *
     * <p>返回的是<strong>深拷贝</strong>，之后继续编辑地图不会影响它。
     *
     * @return 快照
     */
    public Snapshot snapshot() {
        TileType[][] copy = new TileType[rows][];
        for (int i = 0; i < rows; i++) {
            copy[i] = tiles[i].clone();
        }
        return new Snapshot(name, cols, rows, tileSize, copy,
                new ArrayList<>(redDoors), new ArrayList<>(blueDoors),
                nextRedId, nextBlueId);
    }

    /**
     * 整份还原到某个快照。
     *
     * <p>这是撤销的核心：一次性把地形表、两个门列表、两个 id 计数器全部换掉，
     * <strong>不需要逐个字段去改</strong> —— 那样最容易漏。
     *
     * @param snap 目标快照（会被深拷贝，之后改动它不影响本地图）
     */
    public void restore(Snapshot snap) {
        this.name = snap.name();
        this.cols = snap.cols();
        this.rows = snap.rows();
        this.tileSize = snap.tileSize();

        this.tiles = new TileType[snap.rows()][];
        for (int i = 0; i < snap.rows(); i++) {
            this.tiles[i] = snap.tiles()[i].clone();
        }

        redDoors.clear();
        redDoors.addAll(snap.redDoors());
        blueDoors.clear();
        blueDoors.addAll(snap.blueDoors());

        this.nextRedId = snap.nextRedId();
        this.nextBlueId = snap.nextBlueId();
    }

    /**
     * 复制一份独立的地图。
     *
     * <p>用途：<strong>需要把当前地图写成另一个名字的文件时，不要动原来那张</strong>。
     * {@code MapJsonIO.save} 会把 {@code name} 对齐到文件名，直接拿活地图去存
     * 就会顺手把用户手里这张改名（实测踩过：点一次「测试」，
     * 侧栏的地图名就永久变成了 {@code map_001}）。
     *
     * <p>走 {@link #snapshot()} + {@link #restore(Snapshot)} 这条路，
     * 而不是逐个字段拷 —— 后者最容易漏掉门 id 计数器这类状态。
     *
     * @param newName 新地图名
     * @return 一份全新的、与本地图互不影响的副本
     */
    public TileMap copy(String newName) {
        TileMap c = new TileMap(newName, cols, rows, tileSize);
        c.restore(snapshot());
        return c;
    }

    // ------------------------------------------------------------------
    // 诊断
    // ------------------------------------------------------------------

    /**
     * 统计各类型的格子数，用于状态栏与测试。
     *
     * @return 长度与 {@link TileType#values()} 一致的计数数组，下标即 {@code ordinal()}
     */
    public int[] countByType() {
        int[] counts = new int[TileType.values().length];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                counts[tiles[row][col].ordinal()]++;
            }
        }
        return counts;
    }

    /**
     * @return 是否至少有一道红门
     */
    public boolean hasRedDoor() {
        return !redDoors.isEmpty();
    }

    /**
     * @return 是否至少有一道蓝门
     */
    public boolean hasBlueDoor() {
        return !blueDoors.isEmpty();
    }

    /**
     * 打印成字符画，便于测试输出与排查。
     *
     * <p>字符约定：{@code .} 未放置、{@code #} 不可通行、{@code _} 地面、
     * {@code ^} 高台、{@code R} 红门、{@code B} 蓝门。
     *
     * @return 每行一个字符串
     */
    public String toAsciiArt() {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                sb.append(switch (tiles[row][col]) {
                    case EMPTY -> '.';
                    case IMPASSABLE -> '#';
                    case GROUND -> '_';
                    case HIGH -> '^';
                    case RED_DOOR -> 'R';
                    case BLUE_DOOR -> 'B';
                });
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "TileMap[" + name + " " + cols + "x" + rows + " @ " + tileSize
                + " 红门=" + redDoors.size() + " 蓝门=" + blueDoors.size() + "]";
    }
}
