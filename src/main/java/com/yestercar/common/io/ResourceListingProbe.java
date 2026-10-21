package com.yestercar.common.io;

import java.nio.file.Path;
import java.util.List;

/**
 * P0.2 验证程序：确认 classpath 资源目录列举在「文件系统」与「jar 内」两种形态下是否都可用。
 *
 * <p>它刻意只依赖 {@link Workspace}，不依赖 Processing / G4P / 任何窗口类，
 * 因此可以用最朴素的方式在两种形态下各跑一次：
 *
 * <pre>
 * 形态一（开发期）:  mvn test-compile exec:java -Dexec.classpathScope=test \
 *                       -Dexec.mainClass=com.yestercar.common.io.ResourceListingProbe
 *
 * 形态二（交付期）:  mvn package
 *                    java -cp target/YesterCar.jar com.yestercar.common.io.ResourceListingProbe
 * </pre>
 *
 * <p>验证通过后本类不删除 —— 它是「打包后资源是否完好」的常备检查手段，
 * 将来战役功能出问题时，先跑它就能区分是「资源没打进去」还是「加载逻辑写错」。
 */
public final class ResourceListingProbe {

    private ResourceListingProbe() {
    }

    /**
     * @param args 忽略
     */
    public static void main(String[] args) {
        System.out.println("=== P0.2 资源目录列举验证 ===");
        System.out.println("classpath 位置: " + location());
        System.out.println();

        boolean ok = true;

        // ① 能否列出 campaigns 下的子目录（官方战役发现靠它）
        List<String> children = Workspace.listResourceChildren(
                Workspace.CAMPAIGNS_RESOURCE_DIR);
        System.out.println("listResourceChildren(\"campaigns\") = " + children);
        ok &= expect(!children.isEmpty(), "能列出 campaigns 的子目录");
        ok &= expect(children.contains("official_01"), "包含 official_01");
        ok &= expect(children.contains("official_02"), "包含 official_02");
        // 关键反例：campaigns/index.json 是文件不是目录，不该出现在结果里
        ok &= expect(!children.contains("index.json"), "文件 index.json 未被误当作目录");

        // ② 更深的层级：official_01 下应能列出 maps
        List<String> sub = Workspace.listResourceChildren("campaigns/official_01");
        System.out.println("listResourceChildren(\"campaigns/official_01\") = " + sub);
        ok &= expect(sub.contains("maps"), "official_01 下有 maps");

        // ③ 逐层列彻底（jar 前缀匹配容易被更深的路径骗到）
        List<String> mapFiles = Workspace.listResourceChildren("campaigns/official_01/maps");
        System.out.println("listResourceChildren(\"campaigns/official_01/maps\") = " + mapFiles);
        ok &= expect(mapFiles.isEmpty(), "maps 下只有文件、没有子目录");

        // ④ 不存在的目录必须安全返回空集合，不能抛异常
        List<String> missing = Workspace.listResourceChildren("no/such/dir");
        System.out.println("listResourceChildren(\"no/such/dir\") = " + missing);
        ok &= expect(missing.isEmpty(), "不存在的目录返回空集合而不抛异常");

        // ⑤ 索引文件方案作为对照：无论哪种形态都必须可读
        boolean indexReadable;
        try {
            String index = Workspace.readClasspathResourceAsString(
                    Workspace.CAMPAIGNS_RESOURCE_DIR + "/index.json");
            indexReadable = index.contains("official_01") && index.contains("official_02");
        } catch (Exception e) {
            System.out.println("  读取 index.json 失败: " + e);
            indexReadable = false;
        }
        System.out.println("readClasspathResourceAsString(\"campaigns/index.json\") = " + indexReadable);
        ok &= expect(indexReadable, "索引文件两种形态下都可读");

        System.out.println();
        System.out.println(ok
                ? "结论：资源目录列举**可用** —— 官方战役可以采用「扫描目录」方案。"
                : "结论：资源目录列举**不可靠** —— 官方战役必须改用 campaigns/index.json 索引方案。");
        System.exit(ok ? 0 : 1);
    }

    private static boolean expect(boolean condition, String what) {
        System.out.printf("  [%s] %s%n", condition ? "通过" : "失败", what);
        return condition;
    }

    /** 报告本类是从哪里加载的，用来确认当前跑的是哪一种形态。 */
    private static String location() {
        try {
            return Path.of(ResourceListingProbe.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
        } catch (Exception e) {
            return "未知（" + e + "）";
        }
    }
}
