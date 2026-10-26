package com.yestercar.common.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 干员**普攻特效/命中时机**表，读 {@code data/attack-fx.csv}。
 *
 * <p>为什么单独一个文件：命中时刻与弹体种类是**表现**驱动的数据（来自 Spine 攻击动画的
 * "武器挥到最前"那一帧），但它同时决定"什么时候扣血"，所以战斗层和渲染层必须读同一份，
 * 免得两边各写一套常量。
 *
 * <p>各列含义（表头 {@code id,kind,release,speed}）：
 * <ul>
 *   <li>{@code kind}：{@code none} 近战（挥砍，命中才扣血）、{@code arrow} 狙击、
 *       {@code bolt} 术师/辅助、{@code orb} 医疗；</li>
 *   <li>{@code release}：出手到"打到/离手"的秒数（按动画**实际播放**时间，已含压缩比）；</li>
 *   <li>{@code speed}：弹体速度（格/秒），近战为 0。</li>
 * </ul>
 *
 * <p>读不到文件时返回空表 —— 调用方可以据此退回"即时结算"，比崩掉好。
 */
public final class AttackFxTable {

    /** 一个干员的普攻参数。 */
    public record Spec(String kind, float release, float speed) {

        /** @return 是否近战（挥砍，无飞行） */
        public boolean melee() {
            return "none".equals(kind);
        }
    }

    /** 资源路径。 */
    private static final String RESOURCE = "data/attack-fx.csv";

    /** 解析结果（进程内只读一次）。 */
    private static volatile Map<String, Spec> cache;

    private AttackFxTable() {
    }

    /**
     * @param id 干员 id
     * @return 该干员的普攻参数；表里没有返回 {@code null}
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
        synchronized (AttackFxTable.class) {
            if (cache != null) {
                return cache;
            }
            Map<String, Spec> out = new LinkedHashMap<>();
            try (InputStream in = AttackFxTable.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    System.err.println("[AttackFxTable] 找不到资源: " + RESOURCE);
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
                                parse(c[2], 0.3f), parse(c[3], 0f)));
                    }
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("[AttackFxTable] 读取失败: " + e.getMessage());
            }
            cache = Collections.unmodifiableMap(out);
            return cache;
        }
    }

    /**
     * @param s   文本
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
