package com.yestercar.runtime.view;

import com.yestercar.common.io.Workspace;
import com.yestercar.runtime.spine.SpineData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PImage;

/**
 * 逐干员的"局内状态条"：用<b>战斗里同一个</b> {@link OperatorAnimator} + {@link SpineMeshPainter}
 * 把六个时刻画成一排，每个干员一张 PNG。
 *
 * <p>为什么还要这个工具（战斗截图不是已经有 GIF 了吗）：
 * <ul>
 *   <li>战斗里<b>不可能稳定复现阵亡</b>（干员会被打死，但什么时候死看数值，凑不齐 29 次），
 *       而"阵亡 + 淡出"是需求要看的；</li>
 *   <li>受击染色、登场动画也都在<b>帧间隙</b>里，单张战斗截图抓不到；</li>
 *   <li>这里驱动的是<b>同一个状态机</b>（{@code update(dt, attackAge, alive)}），
 *       不是另写一套动画播放逻辑，所以画出来的姿态和战斗里一致。</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp target/YesterCar.jar \
 *      com.yestercar.runtime.view.OperatorDemoSheet --out target\demo-states
 * </pre>
 *
 * <p>产物：{@code <out>/<id>-states.png}（登场 / 待机 / 出手 / 受击 / 阵亡中 / 淡出）。
 */
public final class OperatorDemoSheet extends PApplet {

    /** 六格的含义（ASCII，避免默认字体没有中文字形）。 */
    private static final String[] LABELS = {"START", "IDLE", "ATTACK", "HIT(tint)", "DIE", "DIE+fade"};

    /** 每格宽高（像素）。 */
    private static final int CELL_W = 170;
    private static final int CELL_H = 210;

    /** 每格多少像素（战斗中实测 116~118 像素/格，这里取同一量级）。 */
    private static final float PX = 112f;

    /** 顶部标题条高度。 */
    private static final int HEADER = 26;

    private static String outDir = "target/demo-states";
    private static String idsArg;

    private final List<String> ids = new ArrayList<>();
    private int index;
    private int saved;

    /**
     * 入口。
     *
     * @param args {@code --out <目录>} / {@code --ids a,b,c}
     */
    public static void main(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> outDir = args[++i];
                case "--ids" -> idsArg = args[++i];
                default -> System.out.println("[Sheet] 未知参数: " + args[i]);
            }
        }
        PApplet.main(OperatorDemoSheet.class);
    }

    @Override
    public void settings() {
        size(CELL_W * LABELS.length, HEADER + CELL_H, PConstants.P2D);
        pixelDensity(1);
    }

    @Override
    public void setup() {
        surface.setTitle("OperatorDemoSheet");
        Path root = Workspace.projectRoot();
        List<String> wanted = new ArrayList<>();
        if (idsArg != null && !idsArg.isBlank()) {
            for (String raw : idsArg.split(",")) {
                if (!raw.isBlank()) {
                    wanted.add(raw.trim());
                }
            }
        } else {
            Path spineRoot = root.resolve(SpineUnitLayer.SPINE_DIR);
            try (var s = Files.list(spineRoot)) {
                s.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .forEach(wanted::add);
            } catch (IOException ex) {
                System.out.println("[Sheet] 列素材失败: " + ex);
            }
        }
        ids.addAll(wanted);
        try {
            Files.createDirectories(root.resolve(outDir));
        } catch (IOException ex) {
            System.out.println("[Sheet] 建目录失败: " + ex);
        }
        textFont(createFont("SansSerif", 12));
        System.out.println("[Sheet] 待出图 " + ids.size() + " 个干员 → " + outDir);
    }

    @Override
    public void draw() {
        if (index >= ids.size()) {
            System.out.println("[Sheet] 完成，共 " + saved + " 张 → " + outDir);
            exit();
            return;
        }
        String id = ids.get(index);
        try {
            renderRow(id);
            saveFrame(new java.io.File(Workspace.projectRoot().resolve(outDir).toFile(),
                    id + "-states.png").getPath());
            saved++;
            System.out.println("[Sheet] " + id + " ok");
        } catch (IOException | RuntimeException ex) {
            System.out.println("[Sheet] " + id + " 失败: " + ex);
        }
        index++;
    }

    /**
     * 画一个干员的六格。
     *
     * @param id 干员 id
     * @throws IOException 读素材失败
     */
    private void renderRow(String id) throws IOException {
        Path dir = Workspace.projectRoot().resolve(SpineUnitLayer.SPINE_DIR + id);
        SpineData data = SpineData.load(dir);
        PImage tex = loadImage(dir.resolve(data.atlas().page()).toString());
        if (tex == null) {
            throw new IOException("贴图读不到");
        }
        String idle = OperatorAnimator.firstOf(data, OperatorAnimator.IDLE_NAMES);
        String attack = OperatorAnimator.firstOf(data, OperatorAnimator.ATTACK_NAMES);
        String die = OperatorAnimator.firstOf(data, OperatorAnimator.DIE_NAMES);
        float attackDur = attack == null ? 0.3f : Math.max(0.05f, data.duration(attack));
        float dieDur = die == null ? 0.3f : Math.max(0.05f, data.duration(die));
        // 攻击间隔决定"一次出手最多占多久"，和战斗里同一个来源（operators.json）
        float interval = intervalOf(id);
        // 攻击动画真正会被播到的时间窗（动画比间隔长时会被压缩，但采样点要落在窗内）
        float spanAttack = interval > 0.05f ? Math.min(attackDur, interval) : attackDur;

        background(26);
        // 标题：干员 id + 实际解析到的动画名（和战斗里用的是同一套候选名规则）
        fill(230);
        textAlign(PConstants.LEFT, PConstants.TOP);
        textSize(12);
        text(String.format(Locale.ROOT, "%s   idle=%s  attack=%s(%.2fs)  die=%s(%.2fs)  bodyH=%.0f",
                id, idle, attack, attackDur, die, dieDur, data.bodyHeight()), 8, 7);

        for (int i = 0; i < LABELS.length; i++) {
            int cx = i * CELL_W;
            noStroke();
            fill(i % 2 == 0 ? 32 : 27);
            rect(cx, HEADER, CELL_W, CELL_H);
            fill(120);
            textSize(10);
            text(LABELS[i], cx + 6, HEADER + 4);

            OperatorAnimator anim = new OperatorAnimator(id, data, tex, interval);
            float tint = 0f;
            switch (i) {
                case 0 -> step(anim, 0.25f, -1f, true, 12);
                case 1 -> {
                    step(anim, 0.9f, -1f, true, 30);
                    step(anim, 0.5f, -1f, true, 15);
                }
                case 2 -> step(anim, 0.02f, 0f, true, spanAttack * 0.45f, attackDur);
                case 3 -> {
                    step(anim, 0.02f, 0f, true, spanAttack * 0.45f, attackDur);
                    tint = 1f;
                }
                case 4 -> step(anim, 0.02f, -1f, false, dieDur * 0.5f, dieDur);
                default -> step(anim, 0.02f, -1f, false, dieDur + 0.32f, dieDur);
            }
            anim.draw(g, cx + CELL_W / 2f, HEADER + CELL_H * 0.95f, PX, tint);
        }
    }

    /**
     * @param id 干员 id
     * @return 该干员的攻击间隔（秒）；查不到返回 0（动画器退回"只看动画时长"）
     */
    private static float intervalOf(String id) {
        for (com.yestercar.common.model.Operator op
                : com.yestercar.common.model.Operator.loadAll().values()) {
            if (op.id().equals(id)) {
                return op.attackInterval();
            }
        }
        return 0f;
    }

    /**
     * 以 1/30 秒固定步长推进状态机（和战斗引擎同步长，避免"大 dt 一步跳过去"）。
     *
     * @param anim      动画器
     * @param dt        每步秒数
     * @param attackAge 出手后经过的秒数（{@code -1} = 不出手）
     * @param alive     是否活着
     * @param seconds   总共推进多少秒
     */
    private static void step(OperatorAnimator anim, float dt, float attackAge, boolean alive,
                             float seconds) {
        int n = Math.max(1, Math.round(seconds / dt));
        for (int k = 0; k < n; k++) {
            anim.update(dt, attackAge, alive);
        }
    }

    /**
     * 堆到"出手后 {@code target} 秒"：attackAge 是绝对量，直接给状态机即可。
     *
     * @param anim      动画器
     * @param dt        每步秒数
     * @param unused    attackAge 初值（占位，保持签名统一）
     * @param alive     是否活着
     * @param target    目标 attackAge（秒）
     * @param dur       攻击动画时长（用于步数估算）
     */
    private static void step(OperatorAnimator anim, float dt, float unused, boolean alive,
                             float target, float dur) {
        int n = Math.max(1, Math.round(target / dt));
        float age = 0f;
        for (int k = 0; k < n; k++) {
            age += dt;
            anim.update(dt, age, alive);
        }
    }
}
