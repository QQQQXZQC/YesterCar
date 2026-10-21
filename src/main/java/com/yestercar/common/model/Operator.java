package com.yestercar.common.model;

import com.yestercar.common.util.Director;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import processing.data.JSONArray;
import processing.data.JSONObject;

/**
 * 干员类型：{@code data/operators.json} 里的一条数值（策划方案 §3.3）。
 *
 * <p>纯数据，不含行为 —— 行为在战斗场景里。这样同一份数值可以被多个实例共享，
 * 也让"编队界面"与"战斗"读的是同一份定义，不会两边各写一套。
 *
 * <h2>攻击范围：以「朝右」为基准书写</h2>
 * 策划 §3.3 的范围一律按朝右写，另外三个朝向由
 * {@link Director#rotate(int, int)} 旋转得到。本类**同时接受** JSON 里写全四个朝向
 * （当前 {@code operators.json} 就是写全的），并提供一个一致性测试
 * （{@code OperatorTest}）来保证四个朝向确实是彼此旋转的关系 ——
 * 手改 JSON 时写错一个坐标会被测试抓到。
 *
 * @param id              标识（编队与关卡引用它）
 * @param name            显示名
 * @param deployType      可部署地形
 * @param cost            部署费用（策划 §2.4）
 * @param block           阻挡数（策划 §2.7）
 * @param hp              生命
 * @param atk             攻击力
 * @param def             防御力
 * @param attackInterval  攻击间隔（秒）
 * @param redeployTime    再部署冷却（秒，策划 §2.5）
 * @param range           四个朝向各自的攻击格集合（含自身格）
 */
public record Operator(String id, String name, DeployType deployType,
                       int cost, int block, int hp, int atk, int def,
                       float attackInterval, int redeployTime,
                       String portrait,
                       Map<Director, Set<CellOffset>> range,
                       int taunt,
                       DamageType damageType,
                       int res,
                       String opClass,
                       String illustration) {

    /** 资源里的干员表路径（与 {@code enemies.json} 同一个目录约定）。 */
    private static final String RESOURCE = "data/operators.json";

    /**
     * 找不到类型时的兜底数值。
     *
     * <p>与 {@code EnemyType.FALLBACK} 同一个理由：数据写错时让人"能放下去但很弱"，
     * 比让整个编队界面崩掉更利于排查。
     */
    public static final Operator FALLBACK = new Operator(
            "unknown", "未知干员", DeployType.GROUND,
            10, 1, 500, 50, 0, 1f, 20,
            null,
            defaultRange(1),
            0,
            DamageType.PHYS,
            0,
            "",
            null);

    /**
     * 紧凑构造器：把范围补全成四个朝向，并做防御性拷贝。
     *
     * <p>{@code Map} 与 {@code Set} 都是可变的，直接存引用会让外部改一行就改变干员定义。
     */
    public Operator {
        EnumMap<Director, Set<CellOffset>> copy = new EnumMap<>(Director.class);
        for (Director d : Director.values()) {
            Set<CellOffset> s = range == null ? null : range.get(d);
            copy.put(d, s == null || s.isEmpty()
                    ? Set.of(CellOffset.self())
                    : Collections.unmodifiableSet(new LinkedHashSet<>(s)));
        }
        range = Collections.unmodifiableMap(copy);
        cost = Math.max(0, cost);
        block = Math.max(0, block);
        hp = Math.max(1, hp);
        atk = Math.max(0, atk);
        def = Math.max(0, def);
        attackInterval = Math.max(0.01f, attackInterval);
        redeployTime = Math.max(0, redeployTime);
    }

    /**
     * 取某个朝向下的攻击格。
     *
     * @param facing 朝向
     * @return 攻击格集合（含自身），永不为 {@code null}
     */
    public Set<CellOffset> rangeFor(Director facing) {
        Set<CellOffset> s = range.get(facing == null ? Director.RIGHT : facing);
        return s == null ? Set.of(CellOffset.self()) : s;
    }

    /**
     * 判断某个相对偏移是否在攻击范围内。
     *
     * @param facing 朝向
     * @param dx     目标相对列偏移
     * @param dy     目标相对行偏移
     * @return 在范围内返回 {@code true}
     */
    public boolean covers(Director facing, int dx, int dy) {
        return rangeFor(facing).contains(new CellOffset(dx, dy));
    }

    /**
     * @return 攻击范围覆盖的格子数（四个朝向取最大，用于 UI 展示与测试）
     */
    public int rangeSize() {
        int max = 0;
        for (Set<CellOffset> s : range.values()) {
            max = Math.max(max, s.size());
        }
        return max;
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /**
     * 从资源读全部干员类型。
     *
     * <p>读不到或格式不对时返回<strong>空表</strong>而不是抛异常 ——
     * 与 {@code EnemyType.loadAll()} 一致：最坏结果是"干员表是空的"，而不是启动崩溃。
     *
     * @return {@code id → 类型}，保持文件里的顺序
     */
    public static Map<String, Operator> loadAll() {
        Map<String, Operator> out = new LinkedHashMap<>();
        try (InputStream in = Operator.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                System.err.println("[Operator] 找不到资源: " + RESOURCE);
                return out;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JSONObject root = JSONObject.parse(text);
            JSONArray arr = root.getJSONArray("operators");
            if (arr == null) {
                System.err.println("[Operator] " + RESOURCE + " 里没有 operators 数组");
                return out;
            }
            for (int i = 0; i < arr.size(); i++) {
                Operator t = fromJson(arr.getJSONObject(i));
                out.put(t.id(), t);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[Operator] 读取失败: " + e.getMessage());
        }
        return out;
    }

    /**
     * 查一个类型，找不到时退回 {@link #FALLBACK}。
     *
     * @param all 类型表
     * @param id  类型 id
     * @return 类型，永不返回 {@code null}
     */
    public static Operator resolve(Map<String, Operator> all, String id) {
        if (all == null || id == null) {
            return FALLBACK;
        }
        Operator t = all.get(id);
        return t == null ? FALLBACK : t;
    }

    /**
     * @param all 类型表
     * @return 全部 id，用于编队界面与测试
     */
    public static List<String> ids(Map<String, Operator> all) {
        return new ArrayList<>(all.keySet());
    }

    /**
     * 解析 {@code portrait} 字段。
     *
     * <p>返回 {@code null} 表示"这个干员没有立绘"—— UI 会画问号占位。
     * 字段缺失、空串、或写着 {@code "null"} 都当作没有。
     *
     * @param o 干员对象
     * @return 相对工程根的图片路径；没有则 {@code null}
     */
    private static String portraitOf(JSONObject o) {
        String raw = o.getString("portrait", null);
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty() || "null".equalsIgnoreCase(t)) {
            return null;
        }
        return t;
    }

    /**
     * @return 有没有立绘（UI 据此决定显示立绘还是问号占位）
     */
    public boolean hasPortrait() {
        return portrait != null && !portrait.isEmpty();
    }

    /** 解析一条 JSON。缺字段时用保守的默认值。 */
    private static Operator fromJson(JSONObject o) {
        return new Operator(
                o.getString("id", "unknown"),
                o.getString("name", "未知干员"),
                DeployType.parse(o.getString("deployType", "ground")),
                o.getInt("cost", 10),
                o.getInt("block", 1),
                o.getInt("hp", 500),
                o.getInt("atk", 50),
                o.getInt("def", 0),
                o.getFloat("attackInterval", 1f),
                o.getInt("redeployTime", 20),
                portraitOf(o),
                parseRange(o.getJSONObject("range")),
                o.getInt("taunt", 0),
                DamageType.parse(o.getString("damageType", "phys")),
                Math.max(0, Math.min(100, o.getInt("res", 0))),
                o.getString("class", ""),
                o.getString("illustration", null));
    }

    /**
     * 解析 {@code range} 对象。
     *
     * <p>四个朝向都从 JSON 读；缺某个朝向时用"朝右"的旋转结果补上，
     * 这样即使只写了一个朝向也能正常工作。
     *
     * @param range {@code range} 对象，可为 {@code null}
     * @return 四个朝向的范围
     */
    private static Map<Director, Set<CellOffset>> parseRange(JSONObject range) {
        EnumMap<Director, Set<CellOffset>> out = new EnumMap<>(Director.class);
        if (range == null) {
            return defaultRange(0);
        }
        Set<CellOffset> right = parseOne(range.getJSONArray("right"));
        if (right.isEmpty()) {
            right = Set.of(CellOffset.self());
        }
        for (Director d : Director.values()) {
            Set<CellOffset> s = parseOne(range.getJSONArray(d.name().toLowerCase(java.util.Locale.ROOT)));
            out.put(d, s.isEmpty() ? rotateOf(right, d) : s);
        }
        return out;
    }

    /** 解析一个朝向的坐标数组。 */
    private static Set<CellOffset> parseOne(JSONArray arr) {
        Set<CellOffset> out = new LinkedHashSet<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONArray pair = arr.getJSONArray(i);
            if (pair != null && pair.size() == 2) {
                out.add(new CellOffset(pair.getInt(0), pair.getInt(1)));
            }
        }
        return out;
    }

    /** 把"朝右"的范围旋转到指定朝向。 */
    private static Set<CellOffset> rotateOf(Set<CellOffset> right, Director d) {
        Set<CellOffset> out = new LinkedHashSet<>();
        for (CellOffset c : right) {
            int[] r = d.rotate(c.dx(), c.dy());
            out.add(new CellOffset(r[0], r[1]));
        }
        return out;
    }

    /**
     * 生成一个"自身 + 正前方 N 格"的线性范围（近卫式）。
     *
     * @param ahead 身前格数
     * @return 四个朝向的范围
     */
    private static Map<Director, Set<CellOffset>> defaultRange(int ahead) {
        EnumMap<Director, Set<CellOffset>> out = new EnumMap<>(Director.class);
        for (Director d : Director.values()) {
            Set<CellOffset> s = new LinkedHashSet<>();
            s.add(CellOffset.self());
            for (int i = 1; i <= ahead; i++) {
                s.add(new CellOffset(d.dx() * i, d.dy() * i));
            }
            out.put(d, s);
        }
        return out;
    }

    /**
     * @return 供日志与测试用的一行摘要
     */
    public String summary() {
        return name + "（" + deployType.jsonName() + "，费用 " + cost
                + "，阻挡 " + block + "，范围 " + rangeSize() + " 格）";
    }
}
