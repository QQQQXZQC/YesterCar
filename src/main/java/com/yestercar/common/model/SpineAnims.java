package com.yestercar.common.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import processing.data.JSONObject;

/**
 * 骨骼动画**个别干员的覆盖表**，读 {@code data/spine-anims.json}。
 *
 * <p>用来处理"骨骼里没有 {@code Attack} 这段，但官方是用某个技能循环当普攻"的情况：
 * 玛恩纳的骨骼只有三套技能动画（{@code Skill_1/2/3_Start|Loop|End|Idle}），没有 {@code Attack}，
 * 平时按 {@code Idle} 演。这里把他普攻指定成 **{@code Skill_3_Loop}**（也就是官方开 3 技能后的攻击动作），
 * 其余干员留空、照旧走 {@code ATTACK_NAMES} 的候选名。
 *
 * <p>格式：
 * <pre>
 * { "attack": { "mlynar": "Skill_3_Loop" } }
 * </pre>
 */
public final class SpineAnims {

    /** 资源路径。 */
    private static final String RESOURCE = "data/spine-anims.json";

    /** 普攻动画覆盖：干员 id → 动画名。 */
    private static final Map<String, String> ATTACK = load("attack");

    private SpineAnims() {
    }

    /**
     * @param operatorId 干员 id
     * @return 指定给普攻的动画名；没配置返回 {@code null}
     */
    public static String attackOf(String operatorId) {
        return operatorId == null ? null : ATTACK.get(operatorId);
    }

    /**
     * @param key 顶层字段名
     * @return 该字段下的映射（读不到返回空表）
     */
    private static Map<String, String> load(String key) {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = SpineAnims.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return out;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JSONObject root = JSONObject.parse(text);
            JSONObject sub = root.getJSONObject(key);
            if (sub != null) {
                for (Object k : sub.keys()) {
                    String id = String.valueOf(k);
                    String v = sub.getString(id, null);
                    if (v != null && !v.isBlank()) {
                        out.put(id, v);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[SpineAnims] 读取失败: " + e.getMessage());
        }
        return Collections.unmodifiableMap(out);
    }
}
