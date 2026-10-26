package com.yestercar.runtime.view;

import com.yestercar.common.io.Workspace;
import com.yestercar.runtime.battle.EnemyType;
import com.yestercar.runtime.spine.MeshBuffer;
import com.yestercar.runtime.spine.SpineData;
import com.yestercar.runtime.spine.SpinePose;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PImage;

/**
 * 诊断工具：把**敌人**骨骼动画离屏渲染成一张对照表（每个敌人一行，每列一个动作）。
 *
 * <p>和 {@link SpineGallery} 同一个思路，但用 {@link EnemyAnimator} 解析动画名 ——
 * 所以"表里看到的动作"就是"战斗里会播的动作"，不会出现"画廊里好好的、
 * 进战斗播的是另一段"这种对不上的情况。
 *
 * <h2>用法</h2>
 * <pre>
 * java -cp target/YesterCar.jar com.yestercar.runtime.view.EnemyGallery --out D:\shots\enemy.png
 * java ... --ids soldier,big_bob --list
 * </pre>
 *
 * <p>参数：{@code --out} 输出 png；{@code --ids} 逗号分隔敌人 id；
 * {@code --t} 每个动作的采样时刻（秒，默认按动画中点）；{@code --list} 只打印清单。
 */
public final class EnemyGallery extends PApplet {

    /** 素材根目录（相对工程根）。 */
    private static final String DIR = "data/spine/enemy/";

    /** 要展示的 4 个动作（列）。 */
    private static final String[] COLUMNS = {"Idle", "Move", "Attack", "Die"};

    private static String outPath = "enemy-gallery.png";
    private static String idsArg;
    private static String timeArg;
    private static boolean listOnly;

    private static final int CELL_W = 400;
    private static final int CELL_H = 96;
    private static final int HEAD = 56;

    private final List<String> names = new ArrayList<>();
    private final List<SpineData> models = new ArrayList<>();
    private final List<PImage> textures = new ArrayList<>();
    private final List<EnemyAnimator> anims = new ArrayList<>();
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
                case "--ids" -> idsArg = args[++i];
                case "--t" -> timeArg = args[++i];
                case "--list" -> listOnly = true;
                default -> System.out.println("[EnemyGallery] 未知参数: " + args[i]);
            }
        }
        if (listOnly) {
            printList();
            return;
        }
        PApplet.main(EnemyGallery.class);
    }

    /**
     * @return 敌人 id 列表（按名字排序；{@code --ids} 指定时只留这些）
     */
    private static List<String> ids() {
        Path root = Workspace.projectRoot().resolve(DIR);
        List<String> out = new ArrayList<>();
        java.util.Set<String> wanted = null;
        if (idsArg != null && !idsArg.isBlank()) {
            wanted = new java.util.HashSet<>(List.of(idsArg.split(",")));
        }
        Map<String, EnemyType> all = EnemyType.loadAll();
        for (String id : new java.util.TreeSet<>(all.keySet())) {
            if (wanted == null || wanted.contains(id)) {
                out.add(id);
            }
        }
        if (!Files.isDirectory(root)) {
            System.out.println("[EnemyGallery] 素材目录不存在: " + root);
        }
        return out;
    }

    /** 只打印"每个敌人解析出的动画名"（不开窗口）。 */
    private static void printList() {
        for (String id : ids()) {
            Path dir = Workspace.projectRoot().resolve(DIR + id);
            try {
                SpineData d = SpineData.load(dir);
                EnemyAnimator a = new EnemyAnimator(id, d, null);
                System.out.printf("%-18s idle=%-10s move=%-10s attack=%-12s die=%-6s 动画数=%d%n",
                        id, a.idleAnimation(), a.moveAnimation(), a.attackAnimation(),
                        a.dieAnimation(), d.animations().size());
            } catch (IOException | RuntimeException ex) {
                System.out.printf("%-18s 失败: %s%n", id, ex);
            }
        }
    }

    @Override
    public void settings() {
        int rows = Math.max(1, ids().size());
        size(CELL_W * COLUMNS.length + 8, HEAD + rows * CELL_H + 8, PConstants.P2D);
        pixelDensity(1);
    }

    @Override
    public void setup() {
        surface.setTitle("EnemyGallery");
        for (String id : ids()) {
            Path dir = Workspace.projectRoot().resolve(DIR + id);
            try {
                SpineData d = SpineData.load(dir);
                PImage tex = loadImage(dir.resolve(d.atlas().page()).toString());
                if (tex == null) {
                    System.out.println("[EnemyGallery] 贴图缺失 " + id);
                    continue;
                }
                names.add(id);
                models.add(d);
                textures.add(tex);
                anims.add(new EnemyAnimator(id, d, tex));
            } catch (IOException | RuntimeException ex) {
                System.out.println("[EnemyGallery] 加载失败 " + id + ": " + ex);
            }
        }
        System.out.println("[EnemyGallery] 共 " + models.size() + " 个敌人");
        background(24);
    }

    @Override
    public void draw() {
        background(24);
        MeshBuffer mesh = new MeshBuffer();
        textAlign(PConstants.LEFT, PConstants.TOP);

        // 表头
        fill(220);
        textSize(13);
        for (int c = 0; c < COLUMNS.length; c++) {
            text(COLUMNS[c], 6 + c * CELL_W, 18);
        }

        for (int i = 0; i < models.size(); i++) {
            SpineData d = models.get(i);
            EnemyAnimator anim = anims.get(i);
            int cy = HEAD + i * CELL_H;
            for (int c = 0; c < COLUMNS.length; c++) {
                int cx = c * CELL_W;
                noStroke();
                fill((i + c) % 2 == 0 ? 34 : 28);
                rect(cx, cy, CELL_W - 4, CELL_H - 2);
                String name = animationFor(anim, COLUMNS[c]);
                if (name == null) {
                    fill(150);
                    textSize(11);
                    text("（素材没有这个动作）", cx + 6, cy + CELL_H / 2f - 6);
                    continue;
                }
                float dur = Math.max(0.001f, d.duration(name));
                float t = timeArg == null ? dur * 0.35f : Float.parseFloat(timeArg);
                SpinePose pose = new SpinePose(d);
                pose.play(name, false);
                pose.setTime(Math.min(dur - 0.001f, t));
                mesh.clear();
                pose.fill(mesh);
                // 与战斗里同一个缩放规则：模型高度 → 0.95 格
                float px = CELL_H * 0.92f / EnemyAnimator.HEIGHT_TILES;
                float scale = (EnemyAnimator.HEIGHT_TILES * px) / Math.max(1f, d.bodyHeight());
                SpineMeshPainter.draw(g, textures.get(i), mesh,
                        cx + CELL_W * 0.5f, cy + CELL_H - 4f, scale, 1f, 0f, 1f, 1f, 0f, 0f);
                fill(255, 210, 120);
                textSize(11);
                text(names.get(i) + " · " + name, cx + 6, cy + 4);
            }
        }
        if (frameCount++ >= 1) {
            saveFrame(outPath);
            System.out.println("[EnemyGallery] 已保存 " + outPath);
            exit();
        }
    }

    /**
     * @param anim 动画器（提供和战斗一致的动画名解析）
     * @param what 列名（Idle/Move/Attack/Die）
     * @return 实际动画名；没有返回 {@code null}
     */
    private static String animationFor(EnemyAnimator anim, String what) {
        return switch (what) {
            case "Idle" -> anim.idleAnimation();
            case "Move" -> anim.moveAnimation();
            case "Attack" -> anim.attackAnimation();
            case "Die" -> anim.dieAnimation();
            default -> null;
        };
    }
}
