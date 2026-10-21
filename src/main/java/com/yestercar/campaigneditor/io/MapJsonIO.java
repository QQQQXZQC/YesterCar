package com.yestercar.campaigneditor.io;

import com.yestercar.campaigneditor.model.Door;
import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.model.TileType;
import com.yestercar.campaigneditor.util.MapNaming;
import processing.data.JSONArray;
import processing.data.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 地图 JSON 的读写，严格按地图契约（策划方案 §3.1）。
 *
 * <h2>契约要点</h2>
 * <pre>
 * {
 *   "version": 1,
 *   "name": "map_001",
 *   "cols": 12, "rows": 8, "tileSize": 64,
 *   "legend": { "-1":"empty", "0":"impassable", "1":"ground",
 *               "2":"high", "3":"red_door", "4":"blue_door" },
 *   "tiles": [[...], ...],                     // rows 行 × cols 列
 *   "redDoors":  [ { "id":0, "col":0, "row":0 } ],
 *   "blueDoors": [ { "id":0, "col":11, "row":7, "hp":3 } ]
 * }
 * </pre>
 *
 * <h2>⚠️ 负数 key 必须用 getString</h2>
 * {@code legend} 用 {@code "-1"} 当 key。Processing 的 {@code JSONObject}
 * 内部以 <strong>String</strong> 存 key，所以只能 {@code getString("-1")}；
 * 用 {@code getInt("-1")} 会抛 {@code RuntimeException}。
 * 这条已经在 {@code Stage1SmokeTest} 里锁住过。
 *
 * <h2>写盘策略</h2>
 * {@link #save} 写的是**格式化过的 JSON**（带缩进），而不是压缩成一行的。
 * 地图文件是要给人看、要提交进 git 的，压成一行会让 diff 完全没法读。
 */
public final class MapJsonIO {

    /** 地形枚举与 legend 名的对应，写出时用。 */
    private static final TileType[] LEGEND_ORDER = {
            TileType.EMPTY, TileType.IMPASSABLE, TileType.GROUND,
            TileType.HIGH, TileType.RED_DOOR, TileType.BLUE_DOOR
    };

    private MapJsonIO() {
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 从文件读地图。
     *
     * @param file 地图 JSON 路径
     * @return 地图数据
     * @throws IOException 文件不存在、读不动、或 JSON 结构不符合契约
     */
    public static TileMap load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("找不到地图文件: " + file);
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return parse(text, file.getFileName().toString());
    }

    /**
     * 从 JSON 文本解析地图。
     *
     * @param json         JSON 文本
     * @param fallbackName 解析不出 {@code name} 时用的名字
     * @return 地图数据
     * @throws IOException JSON 非法或结构不符合契约
     */
    public static TileMap parse(String json, String fallbackName) throws IOException {
        JSONObject root;
        try {
            root = JSONObject.parse(json);
        } catch (RuntimeException e) {
            throw new IOException("地图 JSON 解析失败: " + e.getMessage(), e);
        }
        if (root == null) {
            throw new IOException("地图 JSON 为空");
        }

        int version = root.getInt("version", TileMap.VERSION);
        if (version != TileMap.VERSION) {
            throw new IOException("不支持的地图版本: " + version
                    + "（本程序支持 " + TileMap.VERSION + "）");
        }

        String name = root.getString("name", fallbackName);
        int cols = root.getInt("cols", 0);
        int rows = root.getInt("rows", 0);
        int tileSize = root.getInt("tileSize", TileMap.DEFAULT_TILE_SIZE);

        if (cols < 1 || rows < 1) {
            throw new IOException("地图尺寸非法: " + cols + "x" + rows);
        }
        if (cols > TileMap.MAX_SIZE || rows > TileMap.MAX_ROWS) {
            // ⚠️ 这里是**直接拒绝载入**（Q4 的决定）。
            //
            // 代价要说清：行列创建后不可更改，所以超限的图**打不开也就改不了**
            // —— 它等于被判了死刑。当前接受这个后果，理由是调试阶段不存在超限的图，
            // 为不存在的数据写兼容路径是过度设计。
            //
            // 将来若真的出现超限图（比如上限再调小一次），**这里就是需要放宽的点**：
            // 改成"载入但置为只读、不许保存"，用户至少能打开看、能另存成合规尺寸。
            throw new IOException("这张图超过 " + TileMap.MAX_SIZE + "×" + TileMap.MAX_ROWS
                    + "（实际 " + cols + "×" + rows + "），请改小");
        }

        TileMap map = new TileMap(name, cols, rows, tileSize);

        // legend：只做一次校验性质的读取，见类注释里「负数 key」那条
        JSONObject legend = root.getJSONObject("legend");
        if (legend != null) {
            validateLegend(legend);
        }

        // tiles：必须是 rows 行、每行 cols 列
        JSONArray tiles = root.getJSONArray("tiles");
        if (tiles == null) {
            throw new IOException("地图缺少 tiles 字段");
        }
        if (tiles.size() != rows) {
            throw new IOException("tiles 行数 " + tiles.size() + " 与 rows " + rows + " 不一致");
        }
        for (int row = 0; row < rows; row++) {
            JSONArray line = tiles.getJSONArray(row);
            if (line == null || line.size() != cols) {
                throw new IOException("tiles 第 " + row + " 行的列数 "
                        + (line == null ? "缺失" : line.size()) + " 与 cols " + cols + " 不一致");
            }
            for (int col = 0; col < cols; col++) {
                int code = line.getInt(col);
                // 未知地形码不致命：退回未放置，避免一张图的一处笔误让整个加载失败
                TileType type = TileType.fromCodeOrDefault(code, TileType.EMPTY);
                map.setTile(col, row, type);
            }
        }

        readDoors(root.getJSONArray("redDoors"), true, map);
        readDoors(root.getJSONArray("blueDoors"), false, map);

        return map;
    }

    /** 校验 legend 是否是契约约定的那张表。缺失项只警告不失败。 */
    private static void validateLegend(JSONObject legend) {
        for (TileType t : LEGEND_ORDER) {
            String key = String.valueOf(t.code());
            // 必须用 getString：getInt("-1") 会抛异常，见类注释
            String actual = legend.getString(key, null);
            if (actual == null) {
                System.err.println("[MapJsonIO] legend 缺少 key \"" + key + "\"");
            } else if (!actual.equals(t.legendName())) {
                System.err.println("[MapJsonIO] legend[\"" + key + "\"] = \"" + actual
                        + "\"，契约值是 \"" + t.legendName() + "\"");
            }
        }
    }

    /**
     * 读门列表。
     *
     * <p>用 {@link TileMap#placeRedDoor} / {@code placeBlueDoor} 而不是直接改 tiles ——
     * 那样才能保证「门列表」与「地形表」一致（这是 {@code TileMap} 刻意封装的联动）。
     */
    private static void readDoors(JSONArray array, boolean red, TileMap map) throws IOException {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject d = array.getJSONObject(i);
            if (d == null) {
                continue;
            }
            int id = d.getInt("id", i);
            int col = d.getInt("col", -1);
            int row = d.getInt("row", -1);
            if (!map.inBounds(col, row)) {
                System.err.println("[MapJsonIO] 门的坐标越界，已跳过: id=" + id
                        + " (" + col + "," + row + ")");
                continue;
            }
            if (red) {
                map.placeRedDoor(id, col, row);
            } else {
                map.placeBlueDoor(id, col, row, d.getInt("hp", 3));
            }
        }
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 把地图写成 JSON 文本。
     *
     * <p><strong>为什么自己拼字符串而不是用 {@code JSONObject.toString(indent)}</strong>：
     * Processing 4.5.6 的 {@code JSONObject} <strong>没有缩进输出</strong>
     * （公开方法里只有无参 {@code toString()} 与 {@code write(PrintWriter)}，
     * 没有 indentFactor）。而地图文件是要给人看、要提交进 git 的，
     * 压成一行会让 diff 完全没法读，所以这里自己控制格式。
     *
     * <p>读取仍然用 Processing 的解析器（{@link #parse}）—— 那部分工作正常，
     * 而且负数 key 的行为已经在测试里锁住了。
     *
     * @param map 地图数据
     * @return 格式化好的 JSON 文本（缩进 2 空格）
     */
    public static String toJson(TileMap map) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n");
        sb.append("  \"version\": ").append(TileMap.VERSION).append(",\n");
        sb.append("  \"name\": ").append(quote(map.name())).append(",\n");
        sb.append("  \"cols\": ").append(map.cols()).append(",\n");
        sb.append("  \"rows\": ").append(map.rows()).append(",\n");
        sb.append("  \"tileSize\": ").append(map.tileSize()).append(",\n");

        // legend：key 是字符串，注意 "-1" 这样写
        sb.append("  \"legend\": {\n");
        for (int i = 0; i < LEGEND_ORDER.length; i++) {
            TileType t = LEGEND_ORDER[i];
            sb.append("    ").append(quote(String.valueOf(t.code())))
                    .append(": ").append(quote(t.legendName()));
            sb.append(i < LEGEND_ORDER.length - 1 ? ",\n" : "\n");
        }
        sb.append("  },\n");

        // tiles：一个格子一行，diff 时能按行看出改了哪一格
        sb.append("  \"tiles\": [\n");
        for (int row = 0; row < map.rows(); row++) {
            sb.append("    [");
            for (int col = 0; col < map.cols(); col++) {
                sb.append(map.tileAt(col, row).code());
                if (col < map.cols() - 1) {
                    sb.append(", ");
                }
            }
            sb.append("]");
            sb.append(row < map.rows() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n");

        appendDoors(sb, "redDoors", map.redDoors(), false);
        sb.append(",\n");
        appendDoors(sb, "blueDoors", map.blueDoors(), true);
        sb.append("\n}\n");

        return sb.toString();
    }

    /** 写一个门数组。 */
    private static void appendDoors(StringBuilder sb, String key,
                                    List<Door> doors, boolean withHp) {
        sb.append("  ").append(quote(key)).append(": [");
        if (doors.isEmpty()) {
            sb.append("]");
            return;
        }
        sb.append("\n");
        for (int i = 0; i < doors.size(); i++) {
            Door d = doors.get(i);
            sb.append("    { \"id\": ").append(d.id())
                    .append(", \"col\": ").append(d.col())
                    .append(", \"row\": ").append(d.row());
            if (withHp) {
                sb.append(", \"hp\": ").append(d.hp());
            }
            sb.append(" }");
            sb.append(i < doors.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]");
    }

    /** JSON 字符串转义（只处理必要字符）。 */
    private static String quote(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 把地图写盘。
     *
     * <p>若目标目录不存在会自动创建。
     *
     * <h2>写盘时强制把 {@code name} 对齐到文件名</h2>
     * 契约要求「地图名 ≡ 文件名」（见 {@link MapNaming}）。
     * 与其指望每个调用方都记得同步，不如<strong>在唯一的写盘出口上钉死</strong> ——
     * 这样不可能写出"文件叫 A、里面写着 B"的文件。
     *
     * <p><strong>本方法不校验</strong> —— 校验是
     * {@link com.yestercar.campaigneditor.util.MapValidator} 的职责，
     * 由调用方（编辑器的保存流程）决定「校验不过就不写」。
     *
     * @param map  地图数据（其 {@code name} 会被改成文件名，见上）
     * @param file 目标路径
     * @throws IOException 写盘失败
     */
    public static void save(TileMap map, Path file) throws IOException {
        map.setName(MapNaming.nameFromFile(file));
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, toJson(map), StandardCharsets.UTF_8);
    }
}
