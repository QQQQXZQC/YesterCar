package com.yestercar.gameopening;

import com.yestercar.common.io.Settings;
import com.yestercar.common.util.UITheme;

/**
 * 玩家代号的处理：**清洗**与**记住**。
 *
 * <h2>为什么要单独一个类</h2>
 * 用户名最后会显示在主菜单的档案面板上，也会进 {@code Settings} 存盘。
 * 但"用户敲进来的东西"不能直接当用户名用 —— 屏幕上的字符宽度、
 * 存进 JSON 的合法性、以及"看起来像空白但其实有字"这类情况都要处理。
 *
 * <p>清洗顺序是<strong>刻意</strong>的，不能换：
 * <ol>
 *   <li>去首尾空白（用户容易在末尾多敲一个空格，那会让"看起来一样的名字"不相等）；</li>
 *   <li>丢掉<b>控制字符</b>与<b>零宽/双向/BOM</b>字符 —— 它们在屏幕上不可见，
 *       却会让名字变成两个不同的字符串，是"存进去了但看不出来"的典型来源；</li>
 *   <li>把连续空白压成一个（中文输入法很容易敲出全角空格）；</li>
 *   <li>截断到上限；</li>
 *   <li>再 trim 一次 —— 截断可能正好切在空格上。</li>
 * </ol>
 *
 * <p>⚠️ 与 {@code NewMapPanel} 的输入框分工不同：那里的输入框**边输边拦**
 * （非法字符根本打不进去），这里只做<b>提交时清洗</b> ——
 * 因为代号是自由文本，边输边吞字符会让人以为键盘坏了。
 */
public final class Username {

    /** 名字长度上限（UTF-16 单元）。 */
    public static final int MAX_LENGTH = 12;

    /**
     * 主菜单里显示代号时加的前缀。
     *
     * <p>用户第 5 轮要求：进入主界面后代号前默认加 {@code Dr.}。
     * <b>只影响显示</b> —— 存进配置的仍是用户输入的原样，
     * 否则"记住并预填"会把前缀也预填进去，改名越改越长。
     */
    public static final String TITLE_PREFIX = "Dr.";

    /** 没填时用的默认代号。 */
    public static final String DEFAULT_NAME = "博士";

    private Username() {
    }

    /**
     * 清洗一个用户输入的代号。
     *
     * @param raw 原始输入，可为 {@code null}
     * @return 清洗后的代号；输入全是不可见字符时返回空串
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // ① 控制字符：换行、制表符、以及 C1 区间。它们会破坏布局。
            if (Character.isISOControl(c)) {
                continue;
            }
            // ② 零宽 / 双向控制 / BOM / 软连字符：屏幕上不可见，
            //    但会让"看起来一样"的两个名字不相等。
            if (isInvisible(c)) {
                continue;
            }
            sb.append(c);
        }

        // ③ 空白压成一个（含全角空格 U+3000）
        String collapsed = sb.toString().replaceAll("[\\s\\u3000]+", " ").trim();

        // ④ 截断，⑤ 再 trim（可能正好切在空格上）
        if (collapsed.length() > MAX_LENGTH) {
            collapsed = collapsed.substring(0, MAX_LENGTH);
        }
        return collapsed.trim();
    }

    /**
     * @param c 字符
     * @return 是不是"看不见但存在"的字符
     */
    /**
     * 是否是"屏幕上不可见但会占位"的字符（零宽、双向控制、BOM、软连字符）。
     *
     * <p>给 {@link NameFieldFilter} 复用 —— 输入框插入时也要剔掉这些，
     * 否则用户能靠一堆零宽字符把长度撑满。
     *
     * @param c 待判定字符
     * @return 是否应剔除
     */
    static boolean isInvisibleChar(char c) {
        return isInvisible(c);
    }

    private static boolean isInvisible(char c) {
        return c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\u200E'
                || c == '\u200F' || c == '\uFEFF' || c == '\u00AD'
                // 双向控制字符：会让显示顺序与存储顺序不一致
                || (c >= '\u202A' && c <= '\u202E')
                || (c >= '\u2066' && c <= '\u2069');
    }

    /**
     * 清洗，并在结果为空时退回默认代号。
     *
     * @param raw 原始输入
     * @return 一定非空的代号
     */
    public static String sanitizeOrDefault(String raw) {
        String s = sanitize(raw);
        return s.isEmpty() ? DEFAULT_NAME : s;
    }

    /**
     * 读出上次用过的代号（Q4 的决定：记住并预填，但仍然显示输入界面）。
     *
     * @return 上次的代号；没有则返回空串（不是默认值 —— 调用方要能区分
     *         "用户删掉了名字"和"从来没设过"）
     */
    public static String remembered() {
        return sanitize(Settings.inWorkspace().getUsername());
    }

    /**
     * 记住一个代号，下次预填。
     *
     * @param name 代号（会先清洗）
     */
    public static void remember(String name) {
        String cleaned = sanitize(name);
        if (cleaned.isEmpty()) {
            return;             // 空名字不覆盖已有的记忆
        }
        Settings s = Settings.inWorkspace();
        s.setUsername(cleaned);
        s.save();
    }

    /**
     * 当前代号该显示成什么（清洗 + 兜底）。
     *
     * @param raw 原始输入
     * @return 显示用代号
     */
    /**
     * 主菜单里显示的代号（带 {@link #TITLE_PREFIX}）。
     *
     * @param raw 原始代号
     * @return 例如 {@code Dr.博士}
     */
    public static String titled(String raw) {
        String name = sanitizeOrDefault(raw);
        return TITLE_PREFIX + name;
    }

    public static String display(String raw) {
        return sanitizeOrDefault(raw);
    }

    /**
     * @return 代号长度上限的说明文字，供界面提示
     */
    public static String limitHint() {
        return "最多 " + MAX_LENGTH + " 个字";
    }
}
