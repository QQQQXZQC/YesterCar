package com.yestercar.common.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 编辑器的小配置：目前只记「上次用过的目录」。
 *
 * <h2>为什么不用 Java 的 {@code Preferences}</h2>
 * 那会把状态写进 Windows 注册表：重装系统或换机器就丢，而且排查问题时看不见、
 * 也不好手工改。放进 {@code data/settings.properties} 则人可读、可手改、
 * 跟着工程目录走，符合「一切产出都在 {@code data/}」这条约定。
 *
 * <h2>读写都不能致命</h2>
 * 配置文件坏了、目录只读、磁盘满 —— 任何一种都不该让编辑器打不开。
 * 所以本类所有方法<strong>都不抛异常</strong>：读不到就用默认值，写不成就静默放弃
 * （状态栏那种级别的提示留给调用方决定要不要做）。
 *
 * <p>做成实例而不是全静态，是为了让单元测试能指向临时目录 —— 否则测试会去动真实工作区。
 */
public final class Settings {

    /** 配置文件名，位于 {@code data/} 下。 */
    public static final String FILE_NAME = "settings.properties";

    /** 键：上次「打开地图 / 另存为」用过的目录。 */
    public static final String KEY_LAST_MAP_DIR = "editor.lastMapDir";

    /** 键：玩家代号（主菜单档案面板显示它；Q4 决定"记住并预填"）。 */
    public static final String KEY_USERNAME = "player.name";

    /** 上次的窗口宽（决定书 D14：可缩放并记住尺寸）。 */
    public static final String KEY_WINDOW_W = "window.width";

    /** 上次的窗口高。 */
    public static final String KEY_WINDOW_H = "window.height";

    /** 默认窗口宽 —— 16:9。 */
    public static final int DEFAULT_WINDOW_W = 1920;

    /** 默认窗口高 —— 16:9。 */
    public static final int DEFAULT_WINDOW_H = 1080;

    private final Path file;
    private final Properties props = new Properties();

    /**
     * @param file 配置文件路径
     */
    public Settings(Path file) {
        this.file = file;
        load();
    }

    /**
     * 指向真实的 {@code data/settings.properties}。
     *
     * @return 配置对象
     */
    public static Settings inWorkspace() {
        return new Settings(Workspace.dataDir().resolve(FILE_NAME));
    }

    /** 读盘。文件不存在或读坏都只是"没有配置"，不是错误。 */
    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException | RuntimeException e) {
            // 配置坏了不该让程序打不开 —— 丢掉内容，一切回默认值
            System.err.println("[Settings] 配置读取失败，使用默认值: " + e.getMessage());
            props.clear();
        }
    }

    /**
     * 取一个字符串配置。
     *
     * @param key          键
     * @param defaultValue 没有时返回的值
     * @return 配置值
     */
    public String get(String key, String defaultValue) {
        String v = props.getProperty(key);
        return v == null || v.isBlank() ? defaultValue : v;
    }

    /**
     * 设一个字符串配置。只改内存，不写盘 —— 写盘靠 {@link #save()}。
     *
     * @param key   键
     * @param value 值；{@code null} 或空白表示删除该键
     */
    public void set(String key, String value) {
        if (value == null || value.isBlank()) {
            props.remove(key);
        } else {
            props.setProperty(key, value);
        }
    }

    /**
     * 取上次用过的目录。
     *
     * @param fallback 没记录过、或记录的目录已经不存在时返回它
     * @return 一个确实存在的目录
     */
    public Path getLastMapDir(Path fallback) {
        String raw = get(KEY_LAST_MAP_DIR, null);
        if (raw != null) {
            try {
                Path p = Path.of(raw);
                // 目录可能已经被删掉或换了盘符 —— 那就别用它
                if (Files.isDirectory(p)) {
                    return p;
                }
            } catch (RuntimeException e) {
                // 路径字符串非法（比如换了平台），当作没记录
                System.err.println("[Settings] 记录的目录不合法，已忽略: " + raw);
            }
        }
        return fallback;
    }

    /**
     * 记下这次用过的目录。
     *
     * @param dir 目录；{@code null} 表示不记
     */
    public void setLastMapDir(Path dir) {
        set(KEY_LAST_MAP_DIR, dir == null ? null : dir.toAbsolutePath().toString());
    }

    /**
     * 上次的窗口尺寸（决定书 D14：可缩放并记住尺寸）。
     *
     * @return {@code {w, h}}；没有记录过或数值不合理时返回默认 1600×900
     */
    public int[] getWindowSize() {
        int w = num(KEY_WINDOW_W, DEFAULT_WINDOW_W);
        int h = num(KEY_WINDOW_H, DEFAULT_WINDOW_H);
        // 兜底：太小或离谱的值直接丢弃（历史配置或手改坏的情况）
        if (w < 800 || h < 450 || w > 10000 || h > 10000) {
            return new int[]{DEFAULT_WINDOW_W, DEFAULT_WINDOW_H};
        }
        return new int[]{w, h};
    }

    /**
     * 记住窗口尺寸。
     *
     * @param w 宽
     * @param h 高
     */
    public void setWindowSize(int w, int h) {
        if (w < 800 || h < 450) {
            return;      // 别把最小化/异常尺寸记下来
        }
        set(KEY_WINDOW_W, String.valueOf(w));
        set(KEY_WINDOW_H, String.valueOf(h));
    }

    /**
     * 读一个整数配置。
     *
     * @param key          键
     * @param defaultValue 缺省值
     * @return 值
     */
    private int num(String key, int defaultValue) {
        String raw = get(key, null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 上次用过的玩家代号。
     *
     * <p>⚠️ 返回**空串**而不是某个默认值 —— 调用方要能区分
     * "用户把名字删空了"和"从来没设过"。默认代号由
     * {@code com.yestercar.gameopening.Username} 决定，
     * 那是"代号该怎么用"的知识，不该塞进配置文件这一层。
     *
     * @return 上次的代号；没有则空串
     */
    public String getUsername() {
        return get(KEY_USERNAME, "");
    }

    /**
     * 记下玩家代号。
     *
     * @param name 代号；{@code null} 或空白表示不记
     */
    public void setUsername(String name) {
        set(KEY_USERNAME, name == null || name.isBlank() ? null : name);
    }

    /**
     * 写盘。目录不存在会自动创建。
     *
     * @return 写成功返回 {@code true}；失败返回 {@code false}（不抛异常）
     */
    public boolean save() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "YesterCar 编辑器配置（可手工编辑）");
            }
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[Settings] 配置写入失败（不影响使用）: " + e.getMessage());
            return false;
        }
    }

    /**
     * @return 配置文件路径，供日志与测试使用
     */
    public Path file() {
        return file;
    }
}
