package com.yestercar.common.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 敌方**普攻命中时机/弹体**表，读 {@code data/enemy-attack.csv}。
 *
 * <p>与干员的 {@link AttackFxTable} 同构：命中时刻是"攻击动画里打到人"的那一帧，
 * 战斗层靠它把扣血推迟到那一刻，渲染层靠它在同一时刻放弹体 —— 两边必须读同一份数据。
 *
 * <p>各列含义（表头 {@code id,kind,release,speed}）：
 * <ul>
 *   <li>{@code kind}：{@code none} 近战（挥砍，命中才扣血）、{@code enemy} 远程法术弹；</li>
 *   <li>{@code release}：出手到"打到/离手"的秒数（实测自该敌人的 {@code Attack} 动画）；</li>
 *   <li>{@code speed}：弹体速度（格/秒），近战为 0。</li>
 * </ul>
 *
 * <p>表里没有的敌人、或 {@code release <= 0} 的敌人：退回"出手即结算"（旧行为）。
 */
public final class EnemyAttackTable {

    /** 一个敌人的普攻参数。 */
    public record Spec(String kind, float release, float speed) {

        /** @return 是否近战（挥砍，无飞行段） */
        public boolean melee() {
            return "none".equals(kind);
        }
    }

    /** 资源路径。 */
    private static final String RESOURCE = "data/enemy-attack.csv";

    /** 解析结果（进程内只读一次）。 */
    private static volatile Map<String, Spec> cache;

    private EnemyAttackTable() {
    }

    /**
     * @param id 敌人 id（与 {@code enemies.csv} 一致）
     * @return 该敌人的普攻参数；表里没有返回 {@code null}
     */
    public static Spec get(String id) {
        if (id == null) {
            return null;
        }
        return loadAll().get(id);
    }

    /**
     * @return {@code id → Spec}，保持文件顺序
     */
    public static Map<String, Spec> loadAll() {
        Map<String, Spec> local = cache;
        if (local != null) {
            return local;
        }
        synchronized (EnemyAttackTable.class) {
            if (cache != null) {
                return cache;
            }
            Map<String, Spec> out = new LinkedHashMap<>();
            try (InputStream in = EnemyAttackTable.class.getClassLoader()
                    .getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    System.err.println("[EnemyAttackTable] 找不到资源: " + RESOURCE);
                } else {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    for (String raw : text.split("\n")) {
                        String line = raw.trim();
                        if (line.isEmpty() || line.startsWith("#") || line.startsWith("id,")) {
                            continue;
                        }
                        String[] c = line.split(",");
                        if (c.length < 4) {
                            continue;
                        }
                        out.put(c[0].trim(), new Spec(c[1].trim(),
                                parse(c[2], 0f), parse(c[3], 0f)));
                    }
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("[EnemyAttackTable] 读取失败: " + e.getMessage());
            }
            cache = Collections.unmodifiableMap(out);
            return cache;
        }
    }

    /**
     * @param s    文本
     * @param dflt 解析失败时的兜底
     * @return 数值
     */
    private static float parse(String s, float dflt) {
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
