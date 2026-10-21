package com.yestercar.common;

import com.yestercar.common.io.Workspace;
import com.yestercar.common.util.AssetPaths;
import com.yestercar.common.util.UITheme;
import javax.swing.UIManager;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 双入口共用的启动流程。
 *
 * <p>本工程有两个程序入口，启动步骤完全一样，只有最后起的窗口不同：
 *
 * <table border="1">
 *   <caption>两个入口</caption>
 *   <tr><th>入口</th><th>起的窗口</th></tr>
 *   <tr><td>{@code com.yestercar.GameMain}</td><td>启动动画 → 主菜单 → …</td></tr>
 *   <tr><td>{@code com.yestercar.EditorMain}</td><td>战役编辑器（不做启动动画）</td></tr>
 * </table>
 *
 * <h2>职责边界（重要）</h2>
 * 本类<strong>不认识任何具体的窗口类</strong>——import 只有 {@link Workspace}、
 * {@link UITheme} 和 {@code IOException}。一旦这里 import 了 {@code SplashScreen}，
 * 编辑器入口就被迫和启动动画绑在一起了。
 *
 * <p>换句话说：<strong>import 列表就是职责边界，它短说明边界是对的。</strong>
 */
public final class AppBoot {

    /** 自检参数：只做无窗口的环境校验后退出，用于 CI 与命令行排查。 */
    public static final String CHECK_FLAG = "--check";

    /** 切换到战役编辑器的参数，由 {@code GameMain} 识别。 */
    public static final String EDITOR_FLAG = "--editor";

    /**
     * 直接开战并加载指定战役的参数，形如 {@code --play _debug}。
     *
     * <p>由战役编辑器的「测试」按钮使用：编辑器把当前地图写成临时战役，
     * 再用这个参数启动一个<strong>独立的游戏进程</strong>。
     */
    public static final String PLAY_FLAG = "--play";

    private AppBoot() {
    }

    /**
     * 取 {@value #PLAY_FLAG} 后面跟的战役名。
     *
     * @param args 命令行参数
     * @return 战役名；没有该参数或后面没跟值时返回 {@code null}
     */
    public static String playCampaignName(String[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if (PLAY_FLAG.equals(args[i])) {
                String name = args[i + 1];
                // 防止把下一个参数当战役名（例如 --play --editor）
                return name.startsWith("--") ? null : name;
            }
        }
        return null;
    }

    /**
     * 判断是否处于自检模式。
     *
     * @param args 命令行参数
     * @return 第一个参数是 {@value #CHECK_FLAG} 时返回 {@code true}
     */
    public static boolean isCheckMode(String[] args) {
        return args != null && args.length > 0 && CHECK_FLAG.equals(args[0]);
    }

    /**
     * 判断是否请求打开战役编辑器。
     *
     * @param args 命令行参数
     * @return 参数中出现 {@value #EDITOR_FLAG} 时返回 {@code true}
     */
    public static boolean isEditorMode(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (EDITOR_FLAG.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 参数里有没有某个旗标。
     *
     * <p>与 {@link #isEditorMode} 同一个写法 —— 旗标可以出现在任意位置。
     * 抽出来是因为"解析一个旗标"这件事已经有第三处需要了，
     * 再各写一遍循环就会有不一致的那天。
     *
     * @param args  命令行参数
     * @param flag  旗标，如 {@code --menu}
     * @return 出现过返回 {@code true}
     */
    public static boolean hasFlag(String[] args, String flag) {
        if (args == null || flag == null) {
            return false;
        }
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取 {@code --key value} 形式里紧跟在后面的那个值。
     *
     * @param args 命令行参数
     * @param flag 旗标，如 {@code --shot}
     * @return 紧随其后的参数；旗标不存在或后面没东西时返回 {@code null}
     */
    public static String valueAfter(String[] args, String flag) {
        if (args == null || flag == null) {
            return null;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    /**
     * 剔除控制类参数，只留下真正要传给下游的参数。
     *
     * <p>存在的原因：{@code GameMain} 在转发给 {@link com.yestercar.EditorMain} 时，
     * 必须剥掉 {@value #EDITOR_FLAG}（否则下游又会转发一次、死循环），
     * 但<strong>必须保留</strong> {@value #CHECK_FLAG} 之类的其它参数。
     *
     * <p>这里踩过一个坑：早期写成转发 {@code new String[0]}，把 {@code --check} 一并丢掉，
     * 导致 {@code --check --editor} 不是自检而是真的打开了编辑器窗口，
     * 命令行因此一直挂着不返回。
     *
     * @param args         原始参数
     * @param flagsToStrip 要去掉的参数值
     * @return 剥离后的新数组（可能为空）
     */
    public static String[] stripFlags(String[] args, String... flagsToStrip) {
        if (args == null || args.length == 0) {
            return new String[0];
        }
        List<String> kept = new ArrayList<>(args.length);
        for (String arg : args) {
            boolean strip = false;
            for (String flag : flagsToStrip) {
                if (flag.equals(arg)) {
                    strip = true;
                    break;
                }
            }
            if (!strip) {
                kept.add(arg);
            }
        }
        return kept.toArray(new String[0]);
    }

    /**
     * 启动前的准备工作：打印环境信息 + 创建可写数据目录。
     *
     * <p>两个入口都必须调用它，且<strong>只调用一次</strong>。
     * 建目录放在这里而不是各入口的 {@code main} 里，是为了避免两处重复。
     *
     * @param entryClass 调用方自己的 {@code Class}，只用于日志区分入口
     * @param appName    应用显示名，如 {@code "YesterCar"}
     */
    public static void prepare(Class<?> entryClass, String appName) {
        System.out.println("=== " + appName + " 启动 ===");
        System.out.println("Java : " + System.getProperty("java.version")
                + " (" + System.getProperty("java.home") + ")");
        System.out.println(Workspace.describe());
        System.out.println(UITheme.describe());
        System.out.println("入口 : " + entryClass.getSimpleName() + "  (" + appName + ")");

        // 必须在任何 Swing 组件出现之前设外观 —— Swing 的 UIManager 一旦被读过就定下来了
        applySystemLookAndFeel();

        try {
            Workspace.ensureWritableDirs();
        } catch (IOException e) {
            // 目录建不出来不致命：只读功能仍可用，真正写盘时再报错
            System.err.println("[警告] 无法创建可写数据目录: " + e.getMessage());
        }
        System.out.println("======================");
    }

    /**
     * 把 Swing 外观切成系统原生（Windows 上是 Windows 风格）。
     *
     * <h2>为什么需要</h2>
     * 文件对话框与输入框都是 Swing 组件。默认的 Metal 主题是上世纪的样子 ——
     * 灰色、字体小、文件类型下拉里只有「所有文件」。
     * 换成系统外观后，它们长得就像用户熟悉的 Windows 对话框。
     *
     * <p>失败的后果只是"外观还是默认的"，所以<strong>静默退回</strong>，不打断启动。
     * 在 Linux/macOS 上系统外观可能是 {@code GTK}/{@code Aqua}，也一样适用。
     */
    private static void applySystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            System.out.println("外观 : " + UIManager.getLookAndFeel().getName());
        } catch (Exception e) {
            // 极少见（无图形环境、外观类缺失），退回默认即可
            System.err.println("[提示] 系统外观不可用，沿用默认外观: " + e.getMessage());
        }
    }

    /**
     * 无窗口环境自检：校验 JDK、Processing、G4P、数据契约与应用目录是否就绪。
     *
     * <p>之所以不写成 JUnit 用例：这些检查依赖真实的 classpath 与工作目录，
     * 用 {@value #CHECK_FLAG} 可以在打包后的形态上一并验证，覆盖 JUnit 覆盖不到的场景。
     *
     * @param appName 应用显示名，出现在标题里
     * @return 全部通过返回 {@code true}
     */
    public static boolean runSelfCheck(String appName) {
        boolean ok = true;
        System.out.println("=== " + appName + " 环境自检 ===");

        ok &= check("JDK 版本 >= 17", Runtime.version().feature() >= 17,
                Runtime.version().toString());
        ok &= check("Processing 渲染层", classPresent("processing.core.PApplet"),
                "org.processing:core");
        ok &= check("G4P 控件库", classPresent("g4p_controls.G4P"),
                "org.g4p:g4p:4.3.11");
        ok &= check("只读资源 enemies.csv", Workspace.resourceExists("enemies.csv"),
                "src/main/resources/data/enemies.csv");
        ok &= check("只读资源 operators.json", Workspace.resourceExists("operators.json"),
                "src/main/resources/data/operators.json");

        try {
            Workspace.ensureWritableDirs();
            ok &= check("可写数据目录", true, Workspace.dataDir().toString());
        } catch (IOException e) {
            ok &= check("可写数据目录", false, e.getMessage());
        }

        // 界面图检查：路径常量属于公共模块，所以这里不需要认识任何窗口类
        for (String image : AssetPaths.REQUIRED_UI_IMAGES) {
            java.nio.file.Path path = Workspace.imageFile(image);
            ok &= check("界面图 " + path.getFileName(),
                    Files.isRegularFile(path), path.toString());
        }

        System.out.println("-------------------------");
        System.out.println(ok ? "自检通过。" : "自检失败，请检查上面标记为 [失败] 的项。");
        return ok;
    }

    private static boolean check(String name, boolean passed, String detail) {
        System.out.printf("[%s] %-22s %s%n", passed ? "通过" : "失败", name, detail);
        return passed;
    }

    private static boolean classPresent(String className) {
        try {
            // 用本类自己的 ClassLoader：不要引用入口类，否则又绑一起了
            Class.forName(className, false, AppBoot.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
