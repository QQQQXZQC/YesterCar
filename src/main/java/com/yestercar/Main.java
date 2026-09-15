package com.yestercar;

import com.yestercar.common.io.Workspace;
import com.yestercar.common.util.UITheme;
import com.yestercar.launcher.LauncherApp;
import com.yestercar.splash.SplashScreen;
import processing.core.PApplet;

import java.io.IOException;

/**
 * 程序入口。
 *
 * <p>按策划方案 §10.1.4，本类只做三件事，不承担任何业务逻辑：
 * <ol>
 *   <li>初始化 {@link Workspace}（创建可写数据目录）；</li>
 *   <li>播放启动动画 {@link SplashScreen}；</li>
 *   <li>由启动动画切换到主窗口 {@link LauncherApp}。</li>
 * </ol>
 *
 * <h2>运行方式</h2>
 * <pre>
 * IDEA      : 直接运行本类的 main 方法
 * 命令行    : mvn exec:java
 * 打包后    : java -jar target/YesterCar.jar
 * 自检      : java -jar target/YesterCar.jar --check   （不开窗口，只检查环境）
 * </pre>
 */
public final class Main {

    /** 自检参数：只做无窗口的环境校验后退出，用于 CI 与命令行排查。 */
    public static final String CHECK_FLAG = "--check";

    private Main() {
        // 入口类，不允许实例化
    }

    /**
     * 程序主入口。
     *
     * @param args 命令行参数；传入 {@value #CHECK_FLAG} 时只做环境自检
     */
    public static void main(String[] args) {
        if (args.length > 0 && CHECK_FLAG.equals(args[0])) {
            System.exit(runSelfCheck() ? 0 : 1);
        }

        printEnvironment();

        try {
            Workspace.ensureWritableDirs();
        } catch (IOException e) {
            // 目录建不出来不致命：只读功能仍可用，真正写盘时再报错
            System.err.println("[警告] 无法创建可写数据目录: " + e.getMessage());
        }

        PApplet.main(SplashScreen.class);
    }

    /**
     * 无窗口环境自检：校验 JDK、Processing、G4P、数据契约与应用目录是否就绪。
     *
     * <p>之所以不写成 JUnit 用例：这些检查依赖真实的 classpath 与工作目录，
     * 用 {@code --check} 可以在打包后的 exe 上一并验证，覆盖 JUnit 覆盖不到的形态。
     *
     * @return 全部通过返回 {@code true}
     */
    public static boolean runSelfCheck() {
        boolean ok = true;
        System.out.println("=== YesterCar 环境自检 ===");

        ok &= check("JDK 版本 >= 17", Runtime.version().feature() >= 17,
                Runtime.version().toString());
        ok &= check("Processing 渲染层", classPresent("processing.core.PApplet"),
                "org.processing:core");
        ok &= check("G4P 控件库", classPresent("g4p_controls.G4P"),
                "org.g4p:g4p:4.3.11");
        ok &= check("只读资源 enemies.json", Workspace.resourceExists("enemies.json"),
                "src/main/resources/data/enemies.json");
        ok &= check("只读资源 operators.json", Workspace.resourceExists("operators.json"),
                "src/main/resources/data/operators.json");

        try {
            Workspace.ensureWritableDirs();
            ok &= check("可写数据目录", true, Workspace.dataDir().toString());
        } catch (IOException e) {
            ok &= check("可写数据目录", false, e.getMessage());
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
            Class.forName(className, false, Main.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static void printEnvironment() {
        System.out.println("=== YesterCar 启动 ===");
        System.out.println("Java   : " + System.getProperty("java.version")
                + " (" + System.getProperty("java.home") + ")");
        System.out.println(Workspace.describe());
        System.out.println(UITheme.describe());
        System.out.println("======================");
    }
}
