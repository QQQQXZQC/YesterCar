package com.yestercar.runtime.battle;

import processing.data.JSONArray;
import processing.data.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 敌人类型：{@code data/enemies.json} 里的一条数值。
 *
 * <p>纯数据，不含行为 —— 行为在 {@link Enemy} 与 {@link BattleScene} 里。
 * 这样同一份数值可以被多个敌人实例共享。
 *
 * @param id               标识（关卡文件里 {@code "enemy"} 字段引用的就是它）
 * @param name             显示名
 * @param hp               生命值
 * @param atk              攻击力
 * @param def              防御力
 * @param speed            移动速度（<strong>格/秒</strong>）
 * @param attackInterval   攻击间隔（秒）
 * @param blockWeight      阻挡占位（1 = 占一个阻挡位）
 * @param isRanged         是否远程
 * @param rangeRadius      远程攻击半径（格；官方"攻击范围半径"，可以是小数，如高阶术师 2.2；
 *                         0 = 近战）。{@code enemy_ranges.json} 里配了格子表时优先用格子表
 * @param taunt            嘲讽值（越大越容易被干员优先选中；缺省 0）
 * @param res              法术抗性（0–100，百分数；法术伤害按 {@code ×(1−res/100)} 结算）
 * @param icon             图标路径（{@code data/imgs/enemy/xxx.png}，可为空）
 */
public record EnemyType(String id, String name, int hp, int atk, int def,
                        float speed, float attackInterval, int blockWeight,
                        boolean isRanged, float rangeRadius, int taunt,
                        int res, String icon, String damageType) {

    /** 敌人攻击范围表（id → 朝右的格偏移）；与 operators.json 的 range 结构一致。 */
    private static final java.util.Map<String, java.util.List<int[]>> RANGES =
            new java.util.HashMap<>();

    private static boolean rangeLoaded;

    /**
     * @return 攻击范围格偏移（朝右）；没配置返回空表（表示仍用 rangeRadius 判定）
     */
    public java.util.List<int[]> rangeCells() {
        return RANGES.getOrDefault(id, java.util.List.of());
    }

    /**
     * 按**朝向**取得攻击范围（表里只写了朝右的一套，其余方向在这里旋转出来）。
     *
     * <p>旋转规则与干员一致：{@code right} 那一套的 x 是"前方"、y 是"侧向"。
     *
     * @param facing 朝向（{@code null} 当作朝右）
     * @return 格子偏移列表（{@code {dx, dy}}）
     */
    public java.util.List<int[]> rangeCells(com.yestercar.common.util.Director facing) {
        java.util.List<int[]> base = rangeCells();
        if (facing == null || facing == com.yestercar.common.util.Director.RIGHT) {
            return base;
        }
        java.util.List<int[]> out = new java.util.ArrayList<>(base.size());
        for (int[] c : base) {
            int x = c[0];
            int y = c[1];
            switch (facing) {
                case LEFT -> out.add(new int[] {-x, y});
                case UP -> out.add(new int[] {y, -x});
                case DOWN -> out.add(new int[] {-y, x});
                default -> out.add(new int[] {x, y});
            }
        }
        return out;
    }

    /** 首次访问时读 {@code data/enemy_ranges.json}（用 Processing 自带的 JSONObject）。 */
    public static void ensureRangesLoaded() {
        if (rangeLoaded) {
            return;
        }
        rangeLoaded = true;
        try (java.io.InputStream in =
                     EnemyType.class.getResourceAsStream("/data/enemy_ranges.json")) {
            if (in == null) {
                return;
            }
            String txt = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            processing.data.JSONObject root = processing.data.JSONObject.parse(txt);
            processing.data.JSONObject ranges = root.getJSONObject("ranges");
            if (ranges == null) {
                return;
            }
            for (Object k : ranges.keys()) {
                String key = String.valueOf(k);
                processing.data.JSONObject one = ranges.getJSONObject(key);
                if (one == null) {
                    continue;
                }
                processing.data.JSONArray arr = one.getJSONArray("right");
                if (arr == null) {
                    continue;
                }
                java.util.List<int[]> cells = new java.util.ArrayList<>();
                for (int i = 0; i < arr.size(); i++) {
                    processing.data.JSONArray pair = arr.getJSONArray(i);
                    cells.add(new int[]{pair.getInt(0), pair.getInt(1)});
                }
                if (!cells.isEmpty()) {
                    RANGES.put(key, cells);
                }
            }
        } catch (Exception ex) {
            System.out.println("[EnemyType] 读取 enemy_ranges.json 失败: " + ex);
        }
    }

    /** 资源里的敌人表路径。 */
    private static final String RESOURCE = "data/enemies.csv";

    /** 找不到类型时用的兜底数值，保证游戏不会因为一个拼错的 id 就崩。 */
    public static final EnemyType FALLBACK =
            new EnemyType("unknown", "未知敌人", 100, 10, 0, 1f, 1f, 1, false, 0, 0, 0, null, "phys");

    /**
     * 从资源目录读全部敌人类型。
     *
     * <p>读不到或格式不对时返回<strong>空表</strong>而不是抛异常 ——
     * 与 {@code Workspace.listResourceChildren} 的处理一致：
     * 最坏结果是"敌人表是空的"，而不是启动崩溃。
     *
     * @return {@code id → 类型}，保持文件里的顺序（用 {@link LinkedHashMap}）
     */
    public static Map<String, EnemyType> loadAll() {
        Map<String, EnemyType> out = new LinkedHashMap<>();
        try (InputStream in = EnemyType.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                System.err.println("[EnemyType] 找不到资源: " + RESOURCE);
                return out;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            out.putAll(parseCsv(text));
        } catch (IOException | RuntimeException e) {
            System.err.println("[EnemyType] 读取失败: " + e.getMessage());
        }
        return out;
    }

    /**
     * 查一个类型，找不到时退回 {@link #FALLBACK}。
     *
     * <p>关卡文件里写了一个不存在的敌人 id 是**数据错误**，
     * 但让它变成"场上有个未知敌人"比"整个游戏崩掉"对调试更友好 ——
     * 而且 {@code CampaignValidator} 会在保存时就拦截这类错误（P4.1）。
     *
     * @param all 类型表
     * @param id  类型 id
     * @return 类型，永不返回 {@code null}
     */
    public static EnemyType resolve(Map<String, EnemyType> all, String id) {
        if (all == null || id == null) {
            return FALLBACK;
        }
        EnemyType t = all.get(id);
        return t == null ? FALLBACK : t;
    }

    /**
     * 解析敌人数值表（CSV，一行一个敌人，可直接用 Excel 改）。
     *
     * <h2>列</h2>
     * <pre>
     *   id,name,hp,atk,def,res,speed,attackInterval,blockWeight,ranged,rangeRadius,taunt,icon
     * </pre>
     * 第一行是表头，必须完全一致；空行与 {@code #} 开头的注释行跳过。
     *
     * @param text 整个文件内容
     * @return {@code id → 类型}（保持文件顺序）
     */
    static Map<String, EnemyType> parseCsv(String text) {
        Map<String, EnemyType> out = new LinkedHashMap<>();
        if (text == null) {
            return out;
        }
        String[] lines = text.split("\r?\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] c = line.split(",", -1);
            if (c.length < 12 || "id".equals(c[0].trim())) {
                continue;                       // 表头或坏行
            }
            try {
                out.put(c[0].trim(), new EnemyType(
                        c[0].trim(),
                        c[1].trim(),
                        Math.max(1, Integer.parseInt(c[2].trim())),
                        Math.max(0, Integer.parseInt(c[3].trim())),
                        Math.max(0, Integer.parseInt(c[4].trim())),
                        Math.max(0.01f, Float.parseFloat(c[6].trim())),
                        Math.max(0.01f, Float.parseFloat(c[7].trim())),
                        Math.max(0, Integer.parseInt(c[8].trim())),   // 0 = 不可阻挡
                        "1".equals(c[9].trim()),
                        Math.max(0f, Float.parseFloat(c[10].trim())),
                        Integer.parseInt(c[11].trim()),
                        Math.max(0, Math.min(100, Integer.parseInt(c[5].trim()))),
                        c.length > 12 && !c[12].isBlank() ? c[12].trim() : null,
                        c.length > 13 && !c[13].isBlank() ? c[13].trim() : "phys"));
            } catch (NumberFormatException e) {
                System.err.println("[EnemyType] 跳过坏行: " + line);
            }
        }
        return out;
    }

    /**
     * 从 {@code range} 对象算远程半径：取所有朝向上格子的**切比雪夫距离**最大值。
     *
     * <p>Defender 用 {@code rangeRadius + 0.25}（自身碰撞体）做圆形索敌；
     * 我们是格子制，用切比雪夫半径近似一圈，够用且可解释。
     *
     * @param range 范围对象，可为 {@code null}
     * @return 半径；{@code null} 或空时返回 0（近战）
     */
    private static int radiusOf(Object raw) {
        // ⚠️ 必须走 get() + instanceof：enemies.json 里 soldier 的 range 是 JSON 的 null，
        //    getJSONObject() 遇到 null / 非对象会直接抛
        //    「JSONObject["range"] is not a JSONObject」，然后被 loadAll 的
        //    catch(RuntimeException) 吞掉 → 整张敌人表变空（实测踩过）。
        if (!(raw instanceof JSONObject range)) {
            return 0;
        }
        int max = 0;
        for (String dir : new String[]{"right", "up", "down", "left"}) {
            JSONArray arr = range.getJSONArray(dir);
            if (arr == null) {
                continue;
            }
            for (int i = 0; i < arr.size(); i++) {
                JSONArray p = arr.getJSONArray(i);
                if (p != null && p.size() == 2) {
                    max = Math.max(max, Math.max(Math.abs(p.getInt(0)), Math.abs(p.getInt(1))));
                }
            }
        }
        return max;
    }

    /**
     * @return 全部类型的 id 列表，用于下拉框与测试
     */
    public static List<String> ids(Map<String, EnemyType> all) {
        return new ArrayList<>(all.keySet());
    }
}
