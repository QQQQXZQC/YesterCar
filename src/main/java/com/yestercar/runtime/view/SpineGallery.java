package com.yestercar.runtime.view;

import com.yestercar.common.io.Workspace;
import com.yestercar.runtime.spine.MeshBuffer;
import com.yestercar.runtime.spine.SpineData;
import com.yestercar.runtime.spine.SpinePose;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PImage;

/**
 * 诊断工具：把干员骨骼动画**离屏渲染成一张网格图**，用来验证接入是否正确。
 *
 * <p>为什么需要它：战斗截图只能证明"某几个干员在某时某刻画出来了"，
 * 没法逐模型、逐动画核对。这个工具一次把所有模型按指定动画/时刻铺开，
 * 出图后可以直接和 {@code character-viewer/*-viewer/*&#47;out/*.png}
 * （同事用 libGDX 官方运行时验证过的截图）并排比。
 *
 * <h2>用法</h2>
 * <pre>
 * java -cp target/YesterCar.jar com.yestercar.runtime.view.SpineGallery \
 *      --out D:\shots\gallery.png --anim Idle --t 0.5
 *
 * java ... --ids amiya,surtr,kroos --anim Attack --t 0.3
 * java ... --list           # 只打印每个模型的动画清单，不开窗口
 * </pre>
 *
 * <p>参数：{@code --out} 输出 png；{@code --anim} 动画名（默认 Idle）；
 * {@code --t} 秒（{@code --t mid} 取动画中点，默认 0）；
 * {@code --ids} 逗号分隔的干员 id；{@code --cols} 每行几个（默认 8）；
 * {@code --cell} 单元格边长（默认 200）；{@code --list} 只打印清单。
 */
public final class SpineGallery extends PApplet {

    /** 素材根目录（相对工程根）。 */
    private static final String DIR = "data/spine/operator/";

    private static String outPath = "spine-gallery.png";
    private static String anim = "Idle";
    private static String timeArg = "0";
    private static String idsArg;
    private static boolean listOnly;
    private static int cols = 8;
    private static int cell = 200;

    /** 加载好的模型（与 ids 一一对应）。 */
    private final List<SpineData> models = new ArrayList<>();
    private final List<PImage> textures = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final List<String> loadErrors = new ArrayList<>();
    private int frameCount;

    /**
     * 入口。
     *
     * @param args 见类注释
     */
    public static void main(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> outPath = args[++i];
                case "--anim" -> anim = args[++i];
                case "--t" -> timeArg = args[++i];
                case "--ids" -> idsArg = args[++i];
                case "--cols" -> cols = Integer.parseInt(args[++i]);
                case "--cell" -> cell = Integer.parseInt(args[++i]);
                case "--list" -> listOnly = true;
                default -> {
                    System.out.println("[Gallery] 未知参数: " + args[i]);
                }
            }
        }
        if (listOnly) {
            printList();
            return;
        }
        PApplet.main(SpineGallery.class);
    }

    /** 只打印清单（不需要窗口）。 */
    private static void printList() {
        for (Path dir : dirs()) {
            try {
                SpineData d = SpineData.load(dir);
                // 诊断：setup 姿态顶点云的 Y 分位数，用来判断"身体高度"该取什么
                SpinePose probe = new SpinePose(d);
                MeshBuffer probeBuf = new MeshBuffer();
                probe.fill(probeBuf);
                int n = probeBuf.vertexCount();
                float[] ys = new float[n];
                for (int i = 0; i < n; i++) {
                    ys[i] = probeBuf.xy()[i * 2 + 1];
                }
                java.util.Arrays.sort(ys);
                float p05 = n == 0 ? 0f : ys[(int) (n * 0.05f)];
                float p50 = n == 0 ? 0f : ys[n / 2];
                float p95 = n == 0 ? 0f : ys[Math.min(n - 1, (int) (n * 0.95f))];
                System.out.printf("%-24s %-8s bones=%-3d slots=%-3d anims=%-2d 画布=%.0fx%.0f "
                                + "包围盒高=%.0f 分位p05..p95=%.0f..%.0f (%.0f)%n",
                        dir.getFileName(), d.version(), d.boneCount(), d.slotCount(),
                        d.animations().size(), d.artWidth(), d.artHeight(),
                        d.maxY() - d.minY(), p05, p95, p95 - p05);
            } catch (IOException | RuntimeException ex) {
                System.out.printf("%-24s 失败: %s%n", dir.getFileName(), ex);
            }
        }
    }

    /**
     * @return 素材目录（按名字排序）
     */
    private static List<Path> dirs() {
        Path root = Workspace.projectRoot().resolve(DIR);
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory).sorted().forEach(out::add);
        } catch (IOException ex) {
            System.out.println("[Gallery] 列目录失败: " + ex);
        }
        return out;
    }

    @Override
    public void settings() {
        size(1600, 1080, PConstants.P2D);
        pixelDensity(1);
    }

    @Override
    public void setup() {
        surface.setTitle("SpineGallery");
        List<Path> dirs = dirs();
        java.util.Set<String> wanted = null;
        if (idsArg != null && !idsArg.isBlank()) {
            wanted = new java.util.HashSet<>(List.of(idsArg.split(",")));
        }
        for (Path dir : dirs) {
            String id = dir.getFileName().toString();
            if (wanted != null && !wanted.contains(id)) {
                continue;
            }
            try {
                SpineData d = SpineData.load(dir);
                PImage tex = loadImage(dir.resolve(d.atlas().page()).toString());
                if (tex == null) {
                    loadErrors.add(id + " 贴图缺失");
                    continue;
                }
                models.add(d);
                textures.add(tex);
                names.add(id);
                System.out.printf("%-14s %-8s anims=%-2d 画布=%.0fx%.0f 顶点上限=%d%n",
                        id, d.version(), d.animations().size(), d.artWidth(), d.artHeight(),
                        d.maxVertices());
            } catch (IOException | RuntimeException ex) {
                loadErrors.add(id + " " + ex);
                System.out.println("[Gallery] 加载失败 " + id + ": " + ex);
            }
        }
        System.out.println("[Gallery] 共 " + models.size() + " 个模型，失败 " + loadErrors.size());
        background(24);
    }

    @Override
    public void draw() {
        background(24);
        if (models.isEmpty()) {
            fill(255);
            text("没有可用模型", 20, 40);
            finish();
            return;
        }
        int rows = (models.size() + cols - 1) / cols;
        int gridH = Math.max(1, (height - 40) / Math.max(1, rows));
        int cw = Math.min(cell, width / cols);
        MeshBuffer mesh = new MeshBuffer();
        textAlign(PConstants.CENTER, PConstants.TOP);

        for (int i = 0; i < models.size(); i++) {
            SpineData d = models.get(i);
            PImage tex = textures.get(i);
            int cx = (i % cols) * cw;
            int cy = 30 + (i / cols) * gridH;

            // 格子底纹
            noStroke();
            fill(i % 2 == 0 ? 34 : 28);
            rect(cx, cy, cw, gridH);

            String a = resolveAnim(d, anim);
            if (a == null) {
                fill(200);
                textSize(11);
                text(names.get(i) + "  (无动画)", cx + cw / 2f, cy + 4);
                continue;
            }
            float t = resolveTime(d, a);
            SpinePose pose = new SpinePose(d);
            // "--anim setup" 时不播放任何动画，直接出 setup 姿态：
            // 用来区分"骨骼基座/蒙皮错了"还是"时间轴采样错了"
            if (!"setup".equals(anim)) {
                pose.play(a, false);
                pose.setTime(t);
            }
            mesh.clear();
            pose.fill(mesh);

            float modelH = Math.max(1f, d.bodyHeight());
            float scale = (gridH * 0.78f) / modelH;
            SpineMeshPainter.draw(g, tex, mesh,
                    cx + cw / 2f, cy + gridH * 0.94f, scale, 1f, 0f, 1f, 1f, 0f, 0f);

            fill(200);
            textSize(11);
            text(names.get(i) + "  " + a + " t=" + String.format("%.2f", t),
                    cx + cw / 2f, cy + 4);
        }
        if (frameCount++ >= 1) {
            finish();
        }
    }

    /** 保存并退出。 */
    private void finish() {
        saveFrame(outPath);
        System.out.println("[Gallery] 已保存 " + outPath);
        System.exit(0);
    }

    /**
     * 取模型真的有的动画名（找不到退到 Idle/Default/第一个）。
     *
     * @param d  模型
     * @param a  期望动画名
     * @return 实际用的动画名
     */
    private static String resolveAnim(SpineData d, String a) {
        if (d.duration(a) >= 0f) {
            return a;
        }
        // 与运行时共用同一套候选名，保证"画廊里看到的"就是"战斗里会播的"
        String picked = "Attack".equals(a)
                ? OperatorAnimator.firstOf(d, OperatorAnimator.ATTACK_NAMES)
                : OperatorAnimator.firstOf(d, OperatorAnimator.IDLE_NAMES);
        if (picked != null) {
            return picked;
        }
        for (String cand : OperatorAnimator.IDLE_NAMES) {
            if (d.duration(cand) >= 0f) {
                return cand;
            }
        }
        return d.animations().isEmpty() ? null : d.animations().get(0);
    }

    /**
     * @param d 模型
     * @param a 动画名
     * @return 该动画的采样时刻
     */
    private static float resolveTime(SpineData d, String a) {
        float dur = a == null ? 0f : Math.max(0f, d.duration(a));
        if ("mid".equals(timeArg)) {
            return dur * 0.5f;
        }
        if ("end".equals(timeArg)) {
            return Math.max(0f, dur - 0.001f);
        }
        return Float.parseFloat(timeArg);
    }
}
