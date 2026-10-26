package com.yestercar.runtime.spine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 诊断入口：把一个（或一整批）spine 素材目录解析出来并把关键数字打出来。
 *
 * <p>用法：
 * <pre>
 * java com.yestercar.runtime.spine.SpineInspect data/spine/operator/amiya
 * java com.yestercar.runtime.spine.SpineInspect data/spine/operator            # 目录下每个子目录当一个干员
 * java com.yestercar.runtime.spine.SpineInspect amiya -v                        # 干员 id + 详细
 * java com.yestercar.runtime.spine.SpineInspect data/spine/operator/amiya -v
 * </pre>
 *
 * <p>这个类<b>不依赖工程里的任何其它代码</b>（也不碰 Processing），
 * 因此可以用 {@code javac} 单独编译、单独跑，正好用来做"解析器是否真的对"的验收：
 * 任何一个模型解析失败都会打印 {@code FAILED} 并以退出码 1 结束，绝不静默当成空模型。
 */
public final class SpineInspect {

    private SpineInspect() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("用法: SpineInspect <目录|干员id> [...] [-v]");
            System.exit(2);
            return;
        }
        boolean verbose = false;
        List<String> targets = new ArrayList<>();
        for (String arg : args) {
            if ("-v".equals(arg) || "--verbose".equals(arg)) {
                verbose = true;
            } else {
                targets.add(arg);
            }
        }
        if (targets.isEmpty()) {
            System.out.println("用法: SpineInspect <目录|干员id> [...] [-v]");
            System.exit(2);
            return;
        }

        int ok = 0;
        int failed = 0;
        List<String> summary = new ArrayList<>();
        for (String target : targets) {
            Path dir = resolve(target);
            if (dir == null) {
                System.out.println("FAILED: 找不到目录 " + target);
                failed++;
                continue;
            }
            for (Path modelDir : expand(dir)) {
                try {
                    SpineData data = SpineData.load(modelDir);
                    ok++;
                    print(modelDir, data, verbose, summary);
                } catch (IOException | RuntimeException e) {
                    failed++;
                    System.out.println("FAILED: " + modelDir + " -> " + e);
                    e.printStackTrace(System.out);
                }
            }
        }

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("目录\t版本\t骨骼\t插槽\t附件\t贴图区域\t动画\t最大顶点\t最大三角\t包围盒(minX,minY)-(maxX,maxY)");
        for (String line : summary) {
            System.out.println(line);
        }
        System.out.println("成功 " + ok + " 个，失败 " + failed + " 个");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void print(Path dir, SpineData data, boolean verbose, List<String> summary) {
        System.out.println("### " + dir.getFileName() + " -> " + data.name() + " (" + data.version() + ")");
        System.out.println("  version=" + data.version()
                + " bones=" + data.boneCount()
                + " slots=" + data.slotCount()
                + " attachments=" + data.attachmentCount()
                + " skins=" + data.skinCount()
                + " atlasRegions=" + data.atlas().regionCount()
                + " 缺贴图区域=" + data.missingAtlasRegions());
        System.out.println("  animations=" + data.animations().size());
        for (String name : data.animations()) {
            System.out.println(String.format(Locale.ROOT, "    %-18s %.3f", name, data.duration(name)));
        }

        SpinePose pose = new SpinePose(data);
        MeshBuffer buffer = new MeshBuffer();
        pose.fill(buffer);
        System.out.println("  t=0 顶点=" + buffer.vertexCount() + " 三角形=" + buffer.triCount());
        System.out.println(String.format(Locale.ROOT,
                "  setup 包围盒=(%.2f, %.2f)-(%.2f, %.2f) 尺寸=%.2f x %.2f",
                data.minX(), data.minY(), data.maxX(), data.maxY(),
                data.maxX() - data.minX(), data.maxY() - data.minY()));
        System.out.println("  maxVertices=" + data.maxVertices() + " maxTriangles=" + data.maxTriangles());

        if (verbose) {
            for (String name : data.animations()) {
                float duration = data.duration(name);
                if (!pose.play(name, false)) {
                    continue;
                }
                pose.setTime(0f);
                pose.fill(buffer);
                int v0 = buffer.vertexCount();
                int t0 = buffer.triCount();
                float x0 = Float.POSITIVE_INFINITY;
                float y0 = Float.POSITIVE_INFINITY;
                float x1 = Float.NEGATIVE_INFINITY;
                float y1 = Float.NEGATIVE_INFINITY;
                float[] xy = buffer.xy();
                float firstX = v0 > 0 ? xy[0] : 0f;
                float firstY = v0 > 0 ? xy[1] : 0f;
                for (int i = 0; i < v0 * 2; i += 2) {
                    x0 = Math.min(x0, xy[i]);
                    y0 = Math.min(y0, xy[i + 1]);
                    x1 = Math.max(x1, xy[i]);
                    y1 = Math.max(y1, xy[i + 1]);
                }
                pose.setTime(duration * 0.5f);
                pose.fill(buffer);
                int vh = buffer.vertexCount();
                float hx = vh > 0 ? buffer.xy()[0] : 0f;
                float hy = vh > 0 ? buffer.xy()[1] : 0f;
                float hx0 = Float.POSITIVE_INFINITY;
                float hy0 = Float.POSITIVE_INFINITY;
                float hx1 = Float.NEGATIVE_INFINITY;
                float hy1 = Float.NEGATIVE_INFINITY;
                float[] xy2 = buffer.xy();
                for (int i = 0; i < vh * 2; i += 2) {
                    hx0 = Math.min(hx0, xy2[i]);
                    hy0 = Math.min(hy0, xy2[i + 1]);
                    hx1 = Math.max(hx1, xy2[i]);
                    hy1 = Math.max(hy1, xy2[i + 1]);
                }
                System.out.println(String.format(Locale.ROOT,
                        "    [%-16s] t=0: v=%d t=%d bbox=(%.2f,%.2f)-(%.2f,%.2f) | t=%.3f: v=%d 首顶点=(%.2f,%.2f) bbox=(%.2f,%.2f)-(%.2f,%.2f)%s",
                        name, v0, t0, x0, y0, x1, y1, duration * 0.5f, vh, hx, hy, hx0, hy0, hx1, hy1,
                        (v0 == vh && (firstX != hx || firstY != hy)) ? "  ← 顶点数不变但位置变了（动画在动）"
                                : ""));
            }
            pose.play(null, false);
        }

        summary.add(String.format(Locale.ROOT,
                "%s\t%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t(%.1f,%.1f)-(%.1f,%.1f)",
                dir.getFileName(), data.version(), data.boneCount(), data.slotCount(),
                data.attachmentCount(), data.atlas().regionCount(), data.animations().size(),
                data.maxVertices(), data.maxTriangles(),
                data.minX(), data.minY(), data.maxX(), data.maxY()));
    }

    /** 目录 → 该目录本身（含 .skel）或它的每个子目录。 */
    private static List<Path> expand(Path dir) {
        List<Path> out = new ArrayList<>();
        if (containsSkel(dir)) {
            out.add(dir);
            return out;
        }
        try (Stream<Path> children = Files.list(dir)) {
            List<Path> subDirs = children.filter(Files::isDirectory).sorted().toList();
            for (Path sub : subDirs) {
                if (containsSkel(sub)) {
                    out.add(sub);
                }
            }
        } catch (IOException e) {
            System.out.println("FAILED: 无法列目录 " + dir + " -> " + e);
        }
        if (out.isEmpty()) {
            out.add(dir);
        }
        return out;
    }

    private static boolean containsSkel(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.anyMatch(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".skel"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 解析命令行给的路径：先当路径，再当干员 id（在 {@code data/spine/operator/<id>} 下找，
     * 从当前目录逐级往上试，方便在工程根或子目录里直接跑）。
     */
    private static Path resolve(String target) {
        Path direct = Paths.get(target);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve("data").resolve("spine").resolve("operator").resolve(target);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            Path candidate2 = p.resolve("data").resolve("spine").resolve(target);
            if (Files.isDirectory(candidate2)) {
                return candidate2;
            }
        }
        return null;
    }
}
