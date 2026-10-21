package com.yestercar.campaigneditor.util;

import java.nio.file.Path;

/**
 * 地图文件的命名规则。
 *
 * <h2>严格绑定：地图名 ≡ 文件名（不含 {@code .json}）</h2>
 * 契约里 {@code name} 与文件名同 stem（样例 {@code map_001.json} → {@code "name": "map_001"}）。
 * 编辑器把这个关系**钉死**，于是：
 *
 * <ul>
 *   <li>「改名」＝「另存为」，不需要单独的改名功能；</li>
 *   <li>不会出现"JSON 里的 name 与文件名不一致"这种中间状态；</li>
 *   <li>载入时<strong>以文件名为准</strong>，手改过 JSON 的 {@code name} 也能被纠正回来。</li>
 * </ul>
 *
 * <p>做成独立工具类是为了能被单元测试直接验证 —— 文件名清洗在 Windows 上很容易踩坑，
 * 而这些坑（非法字符、结尾的点、扩展名漏加）都只在真去写盘时才暴露。
 */
public final class MapNaming {

    /** 默认扩展名。 */
    public static final String EXTENSION = ".json";

    /**
     * Windows 文件名里不允许出现的字符。
     *
     * <p>{@code \ / : * ? " < > |} —— 直接把它们拼进路径会创建失败。
     * 用**空串替换**而不是替换成下划线：用户输 {@code "my:map"} 时，
     * {@code "mymap"} 比 {@code "my_map"} 更接近本意。
     */
    private static final String ILLEGAL_CHARS = "[\\\\/:*?\"<>|]";

    private MapNaming() {
    }

    /**
     * 从文件名推出地图名。
     *
     * @param file 地图文件路径，如 {@code D:\...\data\maps\map_001.json}
     * @return 去扩展名的文件名；空时返回 {@code "untitled"}
     */
    public static String nameFromFile(Path file) {
        Path fileName = file.getFileName();
        if (fileName == null) {
            return "untitled";
        }
        String text = fileName.toString();
        int dot = text.lastIndexOf('.');
        // dot <= 0：要么没有点，要么点在开头（`.json` 这种"只有扩展名"的名字）
        // 后者也应当当作没有名字 —— 直接返回 `.json` 会得到一个以点开头的名字，
        // 而 Windows 不允许文件名以点结尾，写盘时会出问题。
        String stem = dot > 0 ? text.substring(0, dot) : (dot == 0 ? "" : text);
        return stem.isBlank() ? "untitled" : stem;
    }

    /**
     * 补上 {@code .json} 扩展名。
     *
     * <p>{@code JFileChooser} <strong>不会</strong>替用户补扩展名。
     * 不补的话会存出一个没有扩展名的文件，下次「打开地图」按 {@code *.json} 过滤就看不见它 ——
     * 用户会觉得"我明明存了"。
     *
     * @param path 用户选的路径
     * @return 保证以 {@code .json} 结尾的路径
     */
    public static Path ensureExtension(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return path;
        }
        String text = fileName.toString();
        if (text.toLowerCase().endsWith(EXTENSION)) {
            return path;
        }
        return path.resolveSibling(text + EXTENSION);
    }

    /**
     * 把用户输入的名字清成合法文件名。
     *
     * <p>处理三件事：去掉 Windows 非法字符、去掉首尾空白、
     * 去掉结尾的点与空格（Windows 不允许文件名以这两者结尾，
     * 而 {@code "map."} 这种输入在对话框里很常见）。
     *
     * @param raw 原始输入，可为 {@code null}
     * @return 清洗后的名字；全被清掉时返回空串（调用方应当拒绝）
     */
    public static String sanitizeFileName(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll(ILLEGAL_CHARS, "").trim();
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    /**
     * 清洗并补扩展名，得到可直接用于「另存为」默认值的文件名。
     *
     * @param rawName 用户输入的地图名
     * @return 形如 {@code map_002.json}；名字非法时返回 {@code "untitled.json"}
     */
    public static String suggestedFileName(String rawName) {
        String cleaned = sanitizeFileName(rawName);
        return (cleaned.isEmpty() ? "untitled" : cleaned) + EXTENSION;
    }
}
