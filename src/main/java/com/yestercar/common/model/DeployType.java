package com.yestercar.common.model;

/**
 * 干员的部署地形类别（策划方案 §3.3 的 {@code deployType}）。
 *
 * <p>它决定这个干员能放在哪一类格子上：
 * <ul>
 *   <li>{@link #GROUND} 只能放可通行地面；</li>
 *   <li>{@link #HIGH} 只能放高台。</li>
 * </ul>
 *
 * <p>JSON 里写的是小写 {@code "ground"} / {@code "high"}；
 * 解析时对大小写与空白都不敏感（手改 JSON 很容易把大小写写错）。
 */
public enum DeployType {

    /** 地面：可通行地面上部署（近卫、重装等）。 */
    GROUND("ground"),

    /** 高台：高台上部署（狙击、术师等）。 */
    HIGH("high");

    private final String jsonName;

    DeployType(String jsonName) {
        this.jsonName = jsonName;
    }

    /**
     * @return JSON 里的写法
     */
    public String jsonName() {
        return jsonName;
    }

    /**
     * 解析 JSON 里的 {@code deployType}。
     *
     * <p>不认识的值一律当成 {@link #GROUND}：宁可让干员"能放地上"，
     * 也不要因为一个拼写错误让他在编队界面里消失。
     *
     * @param raw 原始字符串，可为 {@code null}
     * @return 枚举值，永不返回 {@code null}
     */
    public static DeployType parse(String raw) {
        if (raw == null) {
            return GROUND;
        }
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return "high".equals(s) ? HIGH : GROUND;
    }
}
